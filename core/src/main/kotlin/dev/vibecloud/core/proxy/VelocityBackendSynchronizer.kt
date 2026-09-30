package dev.vibecloud.core.proxy

import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.common.logging.Logger
import dev.vibecloud.core.server.ProxyForwarding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*

/** Keeps Velocity's backend table in step with provisioned Paper/Spigot services. */
internal class VelocityBackendSynchronizer(
    private val logger: Logger,
    private val forwardingProvider: () -> ProxyForwarding? = { null },
    /** Live view of all services; used to re-apply configs right after a template merge. */
    private val serviceProvider: (() -> Collection<Service>)? = null,
) {
    private val mutex = Mutex()
    private val pendingReloads = mutableSetOf<String>()

    suspend fun synchronize(
        services: () -> Collection<Service>,
        reloadProxy: suspend (String) -> Boolean,
    ) = mutex.withLock {
        val allServices = services().toList()
        val backends = allServices.filter { it.type == ServerType.PAPER || it.type == ServerType.SPIGOT }
        val servers = backendEntries(backends)
        val defaultServer = defaultServerFor(backends, servers)
        val proxyServices = allServices.filter { it.type == ServerType.VELOCITY }
        val proxyNames = proxyServices.mapTo(mutableSetOf()) { it.name }
        pendingReloads.retainAll(proxyNames)

        proxyServices.forEach { proxy ->
            applyToConfig(
                proxy = proxy,
                servers = servers,
                defaultServer = defaultServer,
                legacyGroupAliases = backends.mapTo(mutableSetOf()) { it.groupName },
                reloadProxy = reloadProxy,
            )
        }
    }

    /**
     * Re-applies the cloud-managed backend table to a single proxy right after its static files
     * were merged from the template. The merge copies the template's (stale) `[servers]` table
     * over the proxy's config — without this pass, a freshly merged proxy would boot with the
     * template's backend list and lose the user's `try` join order.
     *
     * [preservedTry] is the join order read from the proxy's config BEFORE the merge overwrote
     * it; it is re-applied when it only references registered backends.
     */
    suspend fun resyncAfterTemplateMerge(proxy: Service, preservedTry: List<String>? = null) {
        val provider = serviceProvider ?: return
        mutex.withLock {
            val allServices = provider().toList()
            val backends = allServices.filter { it.type == ServerType.PAPER || it.type == ServerType.SPIGOT }
            if (backends.isEmpty()) return@withLock
            val servers = backendEntries(backends)
            try {
                applyToConfig(
                    proxy = proxy,
                    servers = servers,
                    defaultServer = defaultServerFor(backends, servers),
                    legacyGroupAliases = backends.mapTo(mutableSetOf()) { it.groupName },
                    reloadProxy = null,
                    preservedTry = preservedTry,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                logger.warn("Could not re-apply Velocity backends to ${proxy.name} after template refresh: ${failure.message}", failure)
            }
        }
    }

    private suspend fun applyToConfig(
        proxy: Service,
        servers: Map<String, String>,
        defaultServer: String,
        legacyGroupAliases: Set<String>,
        reloadProxy: (suspend (String) -> Boolean)?,
        preservedTry: List<String>? = null,
    ) {
        val config = proxy.directory.resolve("velocity.toml")
        if (!Files.isRegularFile(config)) {
            logger.warn("Cannot sync Velocity backends for ${proxy.name}: missing config $config")
            return
        }
        forwardingProvider()?.let { forwarding -> repairForwardingSecretFile(proxy, forwarding) }
        try {
            val changed = VelocityTomlBackendTable.update(
                config = config,
                serverAddresses = servers,
                defaultServer = defaultServer,
                legacyGroupAliases = legacyGroupAliases,
                preservedTry = preservedTry,
            )
            if (reloadProxy == null) {
                if (changed) {
                    logger.info("Re-applied ${servers.size} Velocity backend route(s) to $config after template refresh")
                }
                return
            }
            val needsReload = changed || proxy.name in pendingReloads
            if (changed) {
                logger.info("Updated ${servers.size} Velocity backend route(s) in $config")
                pendingReloads += proxy.name
            }
            if (needsReload && proxy.state == ServiceState.RUNNING) {
                if (reloadProxy(proxy.name)) {
                    pendingReloads -= proxy.name
                    logger.info("Reloaded backend configuration for Velocity proxy ${proxy.name}")
                } else {
                    logger.warn("Could not send a configuration reload to ${proxy.name}; changes will apply at its next start")
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            logger.warn("Could not sync Velocity backend config $config: ${failure.message}", failure)
        }
    }

    private fun defaultServerFor(backends: List<Service>, servers: Map<String, String>): String =
        backends.sortedWith(backendPreference).firstNotNullOfOrNull { service ->
            service.name.takeIf { it in servers }
        } ?: servers.keys.first()

    /** Writes the shared forwarding secret if the proxy's secret file is missing or has drifted. */
    private fun repairForwardingSecretFile(proxy: Service, forwarding: ProxyForwarding) {
        val secretFile = proxy.directory.resolve("forwarding.secret")
        try {
            val current = if (Files.isRegularFile(secretFile)) {
                Files.readString(secretFile, StandardCharsets.UTF_8).trim()
            } else {
                null
            }
            if (current != forwarding.secret) {
                Files.writeString(secretFile, forwarding.secret, StandardCharsets.UTF_8)
            }
        } catch (failure: Exception) {
            logger.warn("Could not write forwarding secret for ${proxy.name}: ${failure.message}", failure)
        }
    }

    /**
     * One entry per backend service instance. Group aliases are intentionally NOT registered:
     * an alias and its primary instance resolve to the same address, so Velocity lists the same
     * server twice and switching between them triggers a duplicate-login kick on the backend.
     */
    private fun backendEntries(backends: List<Service>): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        backends.sortedWith(compareBy<Service> { it.name }).forEach { service ->
            if (SERVER_NAME.matches(service.name)) {
                entries[service.name] = "127.0.0.1:${service.port}"
            } else {
                logger.warn("Skipping backend service '${service.name}' because it is not a valid Velocity server name")
            }
        }
        if (entries.isEmpty()) {
            // Retain a valid, familiar default while no backend services have been provisioned yet.
            entries["lobby"] = "127.0.0.1:25566"
        }
        return entries.toSortedMap()
    }

    private companion object {
        val SERVER_NAME = Regex("[A-Za-z0-9_-]{1,64}")
        val backendPreference = compareBy<Service> {
            when (it.state) {
                ServiceState.RUNNING -> 0
                ServiceState.STARTING, ServiceState.CREATED -> 1
                ServiceState.STOPPING -> 2
                ServiceState.STOPPED -> 3
                ServiceState.CRASHED -> 4
            }
        }.thenBy(Service::name)
    }
}

/** Small line-oriented editor for Velocity's [servers] TOML table; unrelated settings stay intact. */
internal object VelocityTomlBackendTable {
    private const val BEGIN_MARKER = "# BEGIN VibeCloud managed backends"
    private const val END_MARKER = "# END VibeCloud managed backends"
    private val sectionHeader = Regex("^\\s*\\[([^]]+)]\\s*(?:#.*)?$")
    private val assignment = Regex("^\\s*(?:\"([^\"]+)\"|'([^']+)'|([A-Za-z0-9_-]+))\\s*=\\s*(.*)$")

    @Throws(IOException::class)
    fun update(
        config: Path,
        serverAddresses: Map<String, String>,
        defaultServer: String,
        legacyGroupAliases: Set<String> = emptySet(),
        preservedTry: List<String>? = null,
    ): Boolean {
        require(serverAddresses.isNotEmpty()) { "At least one Velocity backend entry is required" }
        require(defaultServer in serverAddresses) { "Default Velocity server '$defaultServer' is not registered" }
        val source = Files.readString(config, StandardCharsets.UTF_8)
        // Auto-heal configs provisioned before cloud-managed forwarding existed: an absent
        // [forced-hosts] table makes Velocity fall back to sample hosts and refuse to start, and an
        // inline secret keeps proxies from sharing the cloud-managed secret file.
        val normalized = VelocityConfigNormalizer.ensureForcedHostsSection(
            VelocityConfigNormalizer.applyForwardingSecretFile(source),
        )
        if (normalized != source) {
            VelocityConfigNormalizer.writeAtomically(config, normalized)
        }
        val newline = if ("\r\n" in normalized) "\r\n" else "\n"
        val trailingNewline = normalized.endsWith('\n') || normalized.endsWith('\r')
        val lines = normalized.lineSequence().toMutableList()
        while (lines.lastOrNull()?.isEmpty() == true) lines.removeAt(lines.lastIndex)
        var sectionStart = lines.indexOfFirst { line ->
            sectionHeader.matchEntire(line)?.groupValues?.get(1) == "servers"
        }
        if (sectionStart < 0) {
            while (lines.lastOrNull()?.isBlank() == true) lines.removeAt(lines.lastIndex)
            if (lines.isNotEmpty()) lines += ""
            lines += "[servers]"
            sectionStart = lines.lastIndex
        }
        val sectionEnd = (sectionStart + 1 until lines.size).firstOrNull { index ->
            sectionHeader.matches(lines[index])
        } ?: lines.size
        // Group aliases written by older cloud versions are treated as managed so upgrades
        // automatically drop them from existing proxy configs. `try` is listed for symmetry —
        // its names are always regenerated, but its VALUE is read from the raw section below.
        val managedKeys = serverAddresses.keys + legacyGroupAliases + "try"
        // Capture the raw section BEFORE removeOldManagedBlock strips anything: users edit
        // `try` inside the managed block (that is where the cloud writes it), so the preserved
        // value must be read from the untouched lines.
        val rawSection = lines.subList(sectionStart + 1, sectionEnd)
        val body = removeOldManagedBlock(rawSection)
        // Honor a user-chosen join order: an existing `try` that only references registered
        // servers is preserved instead of being reset to the cloud's default choice. A caller
        // may also pass [preservedTry] explicitly — read before the file was overwritten — so a
        // template merge cannot lose the user's join order.
        val effectiveDefault = (preservedTry?.takeIf { list ->
            list.isNotEmpty() && list.all { it in serverAddresses.keys }
        } ?: preservedTryValues(rawSection, serverAddresses.keys)) ?: listOf(defaultServer)
        val cleanedBody = removeManagedAssignments(body, managedKeys)
        while (cleanedBody.lastOrNull()?.isBlank() == true) cleanedBody.removeAt(cleanedBody.lastIndex)

        val generated = buildList {
            add(BEGIN_MARKER)
            serverAddresses.toSortedMap().forEach { (name, address) ->
                add("${quote(name)} = ${quote(address)}")
            }
            // Keep the preserved order verbatim; regenerating alphabetically would silently
            // reorder a user-chosen join priority.
            add("try = [" + effectiveDefault.joinToString(", ") { quote(it) } + "]")
            add(END_MARKER)
        }
        val replacement = buildList {
            addAll(lines.subList(0, sectionStart + 1))
            addAll(cleanedBody)
            if (isNotEmpty() && last().isNotBlank()) add("")
            addAll(generated)
            if (sectionEnd < lines.size) add("")
            addAll(lines.subList(sectionEnd, lines.size))
        }
        val cleanedReplacement = removeDanglingExampleForcedHosts(replacement)
        val updated = cleanedReplacement.joinToString(newline) + if (trailingNewline) newline else ""
        if (updated == normalized) return false

        val temp = Files.createTempFile(config.parent, ".velocity-sync-", ".tmp")
        try {
            Files.writeString(temp, updated, StandardCharsets.UTF_8)
            try {
                Files.move(temp, config, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, config, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        return true
    }

    /**
     * Reads the `try` assignment from a raw velocity.toml source, wherever it appears. Returns
     * the quoted names verbatim (unvalidated) or null when no try list exists. Used by callers
     * that must snapshot the user's join order before something overwrites the config.
     */
    fun readTryValues(source: String): List<String>? {
        for (line in source.lineSequence()) {
            val match = assignment.matchEntire(line.trim()) ?: continue
            if (assignmentKey(match) != "try") continue
            val names = SERVER_NAME_LITERAL.findAll(match.groupValues[4])
                .map { it.groupValues[1] }
                .toList()
            return names.takeIf { it.isNotEmpty() }
        }
        return null
    }

    /**
     * Reads the current `try` value from the [servers] section (including inside the managed
     * block) and returns it when it is a non-empty list that only references registered servers
     * — i.e. a join order worth keeping. Any other state (missing, empty, stale names) falls
     * back to the cloud's default server.
     */
    private fun preservedTryValues(lines: List<String>, validNames: Set<String>): List<String>? {
        for (line in lines) {
            val match = assignment.matchEntire(line) ?: continue
            if (assignmentKey(match) != "try") continue
            val names = SERVER_NAME_LITERAL.findAll(match.groupValues[4])
                .map { it.groupValues[1] }
                .toList()
            return names.takeIf { it.isNotEmpty() && names.all { name -> name in validNames } }
        }
        return null
    }

    private fun removeOldManagedBlock(lines: List<String>): MutableList<String> {
        val result = mutableListOf<String>()
        var insideManagedBlock = false
        lines.forEach { line ->
            when (line.trim()) {
                BEGIN_MARKER -> insideManagedBlock = true
                END_MARKER -> insideManagedBlock = false
                else -> if (!insideManagedBlock) result += line
            }
        }
        return result
    }

    private fun removeManagedAssignments(lines: List<String>, keys: Set<String>): MutableList<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val match = assignment.matchEntire(line)
            val key = match?.groupValues?.drop(1)?.firstOrNull(String::isNotEmpty)
            if (key !in keys) {
                result += line
                index++
                continue
            }
            val value = match?.groupValues?.get(4).orEmpty()
            index++
            if ('[' in value && ']' !in value) {
                while (index < lines.size && ']' !in lines[index]) index++
                if (index < lines.size) index++
            }
        }
        return result
    }

    private fun removeDanglingExampleForcedHosts(lines: List<String>): List<String> {
        val validServerNames = sectionKeys(lines, "servers") - "try"
        val sectionStart = lines.indexOfFirst { line ->
            sectionHeader.matchEntire(line)?.groupValues?.get(1) == "forced-hosts"
        }
        if (sectionStart < 0) return lines
        val sectionEnd = (sectionStart + 1 until lines.size).firstOrNull { index ->
            sectionHeader.matches(lines[index])
        } ?: lines.size
        val result = lines.toMutableList()
        var index = sectionStart + 1
        while (index < sectionEnd) {
            val match = assignment.matchEntire(lines[index])
            val host = match?.let(::assignmentKey)
            val isSampleHost = host != null && host.lowercase(Locale.ROOT) in SAMPLE_HOSTS
            val value = match?.groupValues?.get(4).orEmpty()
            val valueLines = mutableListOf(lines[index])
            var next = index + 1
            if (isSampleHost && '[' in value && ']' !in value) {
                while (next < sectionEnd) {
                    valueLines += lines[next]
                    if (']' in lines[next++]) break
                }
            }
            if (isSampleHost) {
                val targets = SERVER_NAME_LITERAL.findAll(valueLines.joinToString("\\n"))
                    .map { it.groupValues[1] }
                    .toList()
                if (targets.isNotEmpty() && targets.any { it !in validServerNames }) {
                    repeat(next - index) { result[index + it] = "" }
                }
            }
            index = next.coerceAtLeast(index + 1)
        }
        return result
    }

    private fun sectionKeys(lines: List<String>, section: String): Set<String> {
        val start = lines.indexOfFirst { line -> sectionHeader.matchEntire(line)?.groupValues?.get(1) == section }
        if (start < 0) return emptySet()
        val end = (start + 1 until lines.size).firstOrNull { sectionHeader.matches(lines[it]) } ?: lines.size
        return (start + 1 until end).mapNotNull { index ->
            assignment.matchEntire(lines[index])?.let(::assignmentKey)
        }.toSet()
    }

    private fun assignmentKey(match: MatchResult): String =
        listOf(match.groupValues[1], match.groupValues[2], match.groupValues[3]).first(String::isNotEmpty)

    private fun quote(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"") + "\""

    private val SAMPLE_HOSTS = setOf("factions.example.com", "minigames.example.com")
    private val SERVER_NAME_LITERAL = Regex("[\\\"']([A-Za-z0-9_-]+)[\\\"']")
}
