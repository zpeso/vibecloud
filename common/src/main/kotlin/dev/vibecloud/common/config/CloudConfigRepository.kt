package dev.vibecloud.common.config

import dev.vibecloud.api.group.Group
import dev.vibecloud.api.server.ServerType
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

class ConfigurationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Loads a safe YAML document and atomically persists group CRUD operations. */
class CloudConfigRepository(configFile: Path) {
    val configFile: Path = configFile.toAbsolutePath().normalize()
    private val baseDirectory: Path = this.configFile.parent ?: Path.of(".").toAbsolutePath().normalize()
    private val loader = Yaml(SafeConstructor(LoaderOptions().apply {
        isAllowDuplicateKeys = false
        maxAliasesForCollections = 20
        codePointLimit = 3 * 1024 * 1024
    }))
    private val dumper = Yaml(DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        indent = 2
        indicatorIndent = 0
        isPrettyFlow = true
    })
    private var cached: CloudConfig? = null

    @Synchronized
    fun loadOrCreate(): CloudConfig {
        if (!Files.exists(configFile)) {
            val defaults = defaultConfig()
            write(defaults)
            cached = defaults
            return defaults
        }
        val parsed = readFile()
        cached = parsed
        return parsed
    }

    @Synchronized
    fun reload(): CloudConfig {
        if (!Files.exists(configFile)) throw ConfigurationException("Configuration file does not exist: $configFile")
        val parsed = readFile()
        cached = parsed
        return parsed
    }

    @Synchronized
    fun saveGroups(groups: Collection<Group>) {
        val current = cached ?: loadOrCreate()
        val updated = current.copy(groups = groups.sortedBy { it.name })
        write(updated)
        cached = updated
    }

    private fun defaultConfig() = CloudConfig(
        directories = CloudDirectories(
            templates = baseDirectory.resolve("templates").normalize(),
            services = baseDirectory.resolve("services").normalize(),
        ),
        portRange = PortRange(25565, 25664),
        runtime = RuntimeSettings(
            javaCommand = "java",
            minMemoryMb = 512,
            maxMemoryMb = 2048,
            jvmArgs = emptyList(),
            startupTimeout = Duration.ofSeconds(180),
            shutdownTimeout = Duration.ofSeconds(30),
            minecraftEulaAccepted = true,
        ),
        reconciliation = ReconciliationSettings(Duration.ofSeconds(5)),
        groups = emptyList(),
    )

    private fun readFile(): CloudConfig {
        try {
            val root = Files.newBufferedReader(configFile).use { reader -> loader.load<Any?>(reader) }
            val rootMap = asStringMap(root, "root")
            val directoriesMap = asStringMap(rootMap["directories"], "directories")
            val portsMap = asStringMap(rootMap["ports"], "ports")
            val runtimeMap = asStringMap(rootMap["runtime"], "runtime")
            val reconciliationMap = asStringMap(rootMap["reconciliation"], "reconciliation")
            val bridgeMap = asStringMap(rootMap["bridge"], "bridge")
            val groupsMap = asStringMap(rootMap["groups"], "groups")

            val runtime = RuntimeSettings(
                javaCommand = runtimeMap.string("java-command", "java"),
                minMemoryMb = runtimeMap.int("min-memory-mb", 512),
                maxMemoryMb = runtimeMap.int("max-memory-mb", 2048),
                jvmArgs = runtimeMap.stringList("jvm-args"),
                startupTimeout = Duration.ofSeconds(runtimeMap.long("startup-timeout-seconds", 180L)),
                shutdownTimeout = Duration.ofSeconds(runtimeMap.long("shutdown-timeout-seconds", 30L)),
                minecraftEulaAccepted = runtimeMap.boolean("eula-accepted", false),
                legacyJavaCommand = runtimeMap.string("legacy-java-command", ""),
            )

            val groups = groupsMap.map { (name, rawGroup) ->
                val groupMap = asStringMap(rawGroup, "groups.$name")
                try {
                    Group(
                        name = name,
                        type = ServerType.parse(groupMap.requiredString("type", "groups.$name")),
                        version = groupMap.requiredString("version", "groups.$name"),
                        minServices = groupMap.int("min-services", 1),
                        maxServices = groupMap.int("max-services", 5),
                        alwaysRunningServices = groupMap.int("always-running-services", 0),
                        static = groupMap.boolean("static", true),
                        maxMemoryMb = groupMap.intOrNull("max-memory-mb"),
                    )
                } catch (failure: IllegalArgumentException) {
                    throw ConfigurationException("Invalid group '$name': ${failure.message}", failure)
                }
            }

            return CloudConfig(
                directories = CloudDirectories(
                    templates = resolvePath(directoriesMap.string("templates", "templates")),
                    services = resolvePath(directoriesMap.string("services", "services")),
                ),
                portRange = PortRange(
                    portsMap.int("start", 25565),
                    portsMap.int("end", 25664),
                ),
                runtime = runtime,
                reconciliation = ReconciliationSettings(
                    Duration.ofSeconds(reconciliationMap.long("interval-seconds", 5L)),
                ),
                bridge = BridgeSettings(
                    enabled = bridgeMap.boolean("enabled", true),
                    port = bridgeMap.int("port", 25580),
                    bindAddress = bridgeMap.string("bind-address", "127.0.0.1"),
                    heartbeatInterval = Duration.ofSeconds(bridgeMap.long("heartbeat-interval-seconds", 5L)),
                    offlineTimeout = Duration.ofSeconds(bridgeMap.long("offline-timeout-seconds", 20L)),
                    agentJar = bridgeMap.string("agent-jar", ""),
                ),
                groups = groups,
            )
        } catch (failure: ConfigurationException) {
            throw failure
        } catch (failure: Exception) {
            throw ConfigurationException("Could not load configuration '$configFile': ${failure.message}", failure)
        }
    }

    private fun resolvePath(value: String): Path {
        val configured = Path.of(value)
        return (if (configured.isAbsolute) configured else baseDirectory.resolve(configured)).normalize()
            .toAbsolutePath()
    }

    private fun write(config: CloudConfig) {
        val parent = configFile.parent
        try {
            if (parent != null) Files.createDirectories(parent)
            val document = linkedMapOf<String, Any>(
                "directories" to linkedMapOf(
                    "templates" to relativeIfPossible(config.directories.templates),
                    "services" to relativeIfPossible(config.directories.services),
                ),
                "ports" to linkedMapOf(
                    "start" to config.portRange.start,
                    "end" to config.portRange.endInclusive,
                ),
                "runtime" to linkedMapOf(
                    "java-command" to config.runtime.javaCommand,
                    "min-memory-mb" to config.runtime.minMemoryMb,
                    "max-memory-mb" to config.runtime.maxMemoryMb,
                    "jvm-args" to config.runtime.jvmArgs,
                    "startup-timeout-seconds" to config.runtime.startupTimeout.seconds,
                    "shutdown-timeout-seconds" to config.runtime.shutdownTimeout.seconds,
                    "eula-accepted" to config.runtime.minecraftEulaAccepted,
                    "legacy-java-command" to config.runtime.legacyJavaCommand,
                ),
                "reconciliation" to linkedMapOf(
                    "interval-seconds" to config.reconciliation.interval.seconds,
                ),
                "bridge" to linkedMapOf(
                    "enabled" to config.bridge.enabled,
                    "port" to config.bridge.port,
                    "bind-address" to config.bridge.bindAddress,
                    "heartbeat-interval-seconds" to config.bridge.heartbeatInterval.seconds,
                    "offline-timeout-seconds" to config.bridge.offlineTimeout.seconds,
                ),
                "groups" to linkedMapOf<String, Any>(),
            )

            @Suppress("UNCHECKED_CAST")
            val groupDocument = document["groups"] as LinkedHashMap<String, Any>
            config.groups.sortedBy { it.name }.forEach { group ->
                groupDocument[group.name] = linkedMapOf<String, Any>(
                    "type" to group.type.name,
                    "version" to group.version,
                    "min-services" to group.minServices,
                    "max-services" to group.maxServices,
                    "always-running-services" to group.alwaysRunningServices,
                    "static" to group.static,
                ).also { entry ->
                    group.maxMemoryMb?.let { entry["max-memory-mb"] = it }
                }
            }
            val temporary = configFile.resolveSibling("${configFile.fileName}.tmp")
            Files.newBufferedWriter(temporary).use { writer -> dumper.dump(document, writer) }
            try {
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: IOException) {
            throw ConfigurationException("Could not write configuration '$configFile': ${failure.message}", failure)
        }
    }

    private fun relativeIfPossible(path: Path): String = try {
        baseDirectory.relativize(path.toAbsolutePath().normalize()).toString().ifBlank { "." }
    } catch (_: IllegalArgumentException) {
        path.toString()
    }

    private fun asStringMap(value: Any?, context: String): Map<String, Any?> {
        if (value == null) return emptyMap()
        val raw = value as? Map<*, *>
            ?: throw ConfigurationException("'$context' must be a YAML mapping")
        val result = LinkedHashMap<String, Any?>()
        raw.forEach { (key, item) ->
            val stringKey = key as? String
                ?: throw ConfigurationException("'$context' contains a non-string key")
            result[stringKey] = item
        }
        return result
    }

    private fun Map<String, Any?>.requiredString(name: String, context: String): String =
        this[name] as? String ?: throw ConfigurationException("'$context.$name' must be a string")

    private fun Map<String, Any?>.string(name: String, default: String): String {
        if (!containsKey(name)) return default
        return this[name] as? String ?: throw ConfigurationException("'$name' must be a string")
    }

    /** Present-but-null YAML values (e.g. `max-memory-mb:` with nothing after it) read as absent. */
    private fun Map<String, Any?>.intOrNull(name: String): Int? =
        if (!containsKey(name) || this[name] == null) null else int(name, 0).takeIf { it != 0 }

    private fun Map<String, Any?>.int(name: String, default: Int): Int {
        if (!containsKey(name)) return default
        val value = exactLong(this[name], name)
        if (value < Int.MIN_VALUE.toLong() || value > Int.MAX_VALUE.toLong()) {
            throw ConfigurationException("'$name' is outside the integer range")
        }
        return value.toInt()
    }

    private fun Map<String, Any?>.long(name: String, default: Long): Long =
        if (containsKey(name)) exactLong(this[name], name) else default

    private fun Map<String, Any?>.boolean(name: String, default: Boolean): Boolean {
        if (!containsKey(name)) return default
        val value = this[name]
        return value as? Boolean ?: (value as? String)?.toBooleanStrictOrNull()
        ?: throw ConfigurationException("'$name' must be true or false")
    }

    private fun Map<String, Any?>.stringList(name: String): List<String> {
        if (!containsKey(name)) return emptyList()
        val values = this[name] as? List<*> ?: throw ConfigurationException("'$name' must be a list of strings")
        return values.mapIndexed { index, item ->
            item as? String ?: throw ConfigurationException("'$name[$index]' must be a string")
        }
    }

    private fun exactLong(value: Any?, name: String): Long = try {
        when (value) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            is java.math.BigInteger -> value.longValueExact()
            is java.math.BigDecimal -> value.longValueExact()
            is String -> value.toLongOrNull() ?: throw ConfigurationException("'$name' must be an integer")
            else -> throw ConfigurationException("'$name' must be an integer")
        }
    } catch (failure: ArithmeticException) {
        throw ConfigurationException("'$name' is outside the supported integer range", failure)
    }
}
