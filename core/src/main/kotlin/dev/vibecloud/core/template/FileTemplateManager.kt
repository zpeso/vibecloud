package dev.vibecloud.core.template

import dev.vibecloud.api.server.ServerBuild
import dev.vibecloud.api.server.ServerCatalog
import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.template.TemplateManager
import dev.vibecloud.common.config.RuntimeSettings
import dev.vibecloud.core.proxy.VelocityConfigNormalizer
import dev.vibecloud.core.server.ProxyForwarding
import dev.vibecloud.core.server.ServerAdapterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class FileTemplateManager(
    templateRoot: Path,
    serviceRoot: Path,
    private val adapters: ServerAdapterRegistry,
    private val runtime: RuntimeSettings,
    private val serverCatalog: ServerCatalog? = null,
    private val artifactDownloader: ServerArtifactDownloader = HttpServerArtifactDownloader(),
    private val forwardingProvider: () -> ProxyForwarding? = { null },
) : TemplateManager {
    private val templateRoot = templateRoot.toAbsolutePath().normalize()
    private val serviceRoot = serviceRoot.toAbsolutePath().normalize()
    private val installLocks = ConcurrentHashMap<String, Mutex>()

    override fun availableVersions(type: ServerType): List<String> {
        val typeDirectory = templateRoot.resolve(type.templateKey).normalize()
        if (!typeDirectory.startsWith(templateRoot) || !Files.isDirectory(typeDirectory)) return emptyList()
        return try {
            Files.list(typeDirectory).use { entries ->
                entries.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                    .map { it.fileName.toString() }
                    .sorted()
                    .toList()
            }
        } catch (failure: IOException) {
            throw TemplateException("Could not list templates under $typeDirectory", failure)
        }
    }

    override suspend fun install(build: ServerBuild) = withContext(Dispatchers.IO) {
        val installKey = "${build.type.templateKey}/${build.key}"
        installLocks.computeIfAbsent(installKey) { Mutex() }.withLock {
            installBuildLocked(build)
        }
    }

    private suspend fun installBuildLocked(build: ServerBuild) {
        val typeDirectory = templateRoot.resolve(build.type.templateKey).normalize()
        val destination = typeDirectory.resolve(build.key).normalize()
        if (!typeDirectory.startsWith(templateRoot) || !destination.startsWith(templateRoot)) {
            throw TemplateException("Server build template path escapes configured template root")
        }
        Files.createDirectories(typeDirectory)
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            verifyCachedBuild(build, destination)
            return
        }

        val staging = typeDirectory.resolve(".${build.key}.install-${UUID.randomUUID()}").normalize()
        if (!staging.startsWith(typeDirectory)) throw TemplateException("Generated staging path escaped template directory")
        Files.createDirectory(staging)
        val partialJar = staging.resolve("server.jar.part")
        try {
            artifactDownloader.download(build, partialJar)
            val downloadedSize = Files.size(partialJar)
            if (build.sizeBytes != null && downloadedSize != build.sizeBytes) {
                throw TemplateException(
                    "Size mismatch for ${build.fileName}: expected ${build.sizeBytes} bytes, received $downloadedSize",
                )
            }
            val actualSha256 = sha256(partialJar)
            if (build.sha256 != null && !actualSha256.equals(build.sha256, ignoreCase = true)) {
                throw TemplateException("SHA-256 mismatch for ${build.fileName}: expected ${build.sha256}, received $actualSha256")
            }
            Files.move(partialJar, staging.resolve("server.jar"))
            createDefaultProxyConfig(build, staging)
            writeBuildMetadata(build, staging, downloadedSize, actualSha256)
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging, destination)
            }
        } catch (failure: Exception) {
            runCatching { deleteRecursively(staging) }
            if (failure is CancellationException) throw failure
            if (failure is TemplateException) throw failure
            throw TemplateException("Could not install ${build.displayName}: ${failure.message}", failure)
        }
    }

    private fun verifyCachedBuild(build: ServerBuild, directory: Path) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw TemplateException("Template destination is not a directory: $directory")
        }
        val jar = directory.resolve("server.jar")
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
            throw TemplateException("Template directory already exists but has no server.jar: $directory")
        }
        val metadataFile = directory.resolve(BUILD_METADATA_FILE)
        if (!Files.isRegularFile(metadataFile, LinkOption.NOFOLLOW_LINKS)) {
            throw TemplateException("Template directory already exists without build metadata: $directory")
        }
        val properties = Properties()
        Files.newInputStream(metadataFile).use(properties::load)
        if (properties.getProperty("build-key") != build.key) {
            throw TemplateException("Cached template metadata does not match requested build '${build.key}'")
        }
        val actualSha256 = sha256(jar)
        val expectedSha256 = build.sha256 ?: properties.getProperty("local-sha256")
        if (expectedSha256 != null && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
            throw TemplateException("Cached server.jar failed SHA-256 verification: $jar")
        }
        if (build.sizeBytes != null && Files.size(jar) != build.sizeBytes) {
            throw TemplateException("Cached server.jar failed size verification: $jar")
        }
    }

    private fun createDefaultProxyConfig(build: ServerBuild, directory: Path) {
        when (build.type) {
            ServerType.VELOCITY -> {
                val config = directory.resolve("velocity.toml")
                if (!Files.exists(config)) {
                    val generated = """# Generated defaults. Configure forwarding and backend servers before public use.
config-version = "2.7"
bind = "0.0.0.0:25565"
motd = "A VibeCloud Velocity Proxy"
show-max-players = 500
online-mode = true
force-key-authentication = true
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
announce-forge = false
kick-existing-players = false
ping-passthrough = "DISABLED"
enable-player-address-logging = true

[servers]
lobby = "127.0.0.1:25566"
try = ["lobby"]

[forced-hosts]
"""
                    VelocityConfigNormalizer.writeAtomically(config, generated)
                }
            }

            ServerType.BUNGEECORD -> {
                val config = directory.resolve("config.yml")
                if (!Files.exists(config)) {
                    val generated = """# Generated defaults. Configure backend servers and forwarding before public use.
listeners:
  - query_port: 25577
    motd: 'A VibeCloud BungeeCord Proxy'
    tab_list: GLOBAL_PING
    query_enabled: false
    proxy_protocol: false
    forced_hosts: {}
    ping_passthrough: false
    priorities:
      - lobby
    bind_local_address: true
    host: 0.0.0.0:25577
    max_players: 500
    tab_size: 60
    force_default_server: false
servers:
  lobby:
    motd: 'Lobby'
    address: 127.0.0.1:25566
    restricted: false
online_mode: true
ip_forward: false
connection_throttle: 4000
"""
                    VelocityConfigNormalizer.writeAtomically(config, generated)
                }
            }

            else -> Unit
        }
    }

    private fun writeBuildMetadata(build: ServerBuild, directory: Path, size: Long, localSha256: String) {
        val properties = Properties().apply {
            setProperty("build-key", build.key)
            setProperty("distribution", build.distribution)
            setProperty("server-type", build.type.name)
            setProperty("version", build.version)
            setProperty("build", build.build)
            setProperty("channel", build.channel)
            setProperty("artifact-name", build.fileName)
            setProperty("download-url", build.downloadUrl.toString())
            setProperty("size-bytes", size.toString())
            setProperty("local-sha256", localSha256)
            build.sha256?.let { setProperty("upstream-sha256", it) }
            build.releasedAt?.let { setProperty("released-at", it.toString()) }
            build.metadata.forEach { (key, value) -> setProperty("catalog.$key", value) }
        }
        Files.newOutputStream(directory.resolve(BUILD_METADATA_FILE))
            .use { properties.store(it, "Verified server distribution metadata") }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    override suspend fun provision(service: Service) = withContext(Dispatchers.IO) {
        var source = templateRoot
            .resolve(service.type.templateKey)
            .resolve(service.version)
            .normalize()
        if (!source.startsWith(templateRoot)) {
            throw TemplateException("Template path escapes configured template root: $source")
        }
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            val upstreamBuild = serverCatalog?.build(service.type, service.version)
            if (upstreamBuild != null) {
                install(upstreamBuild)
                source = templateRoot.resolve(service.type.templateKey).resolve(upstreamBuild.key).normalize()
            }
        }
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw TemplateException("Missing template for ${service.type.name} ${service.version}: $source")
        }
        val jar = source.resolve("server.jar")
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
            throw TemplateException("Template is missing server.jar: $jar")
        }
        if (service.directory != serviceRoot && !service.directory.startsWith(serviceRoot)) {
            throw TemplateException("Service directory escapes configured services root: ${service.directory}")
        }
        if (Files.exists(service.directory, LinkOption.NOFOLLOW_LINKS)) {
            throw TemplateException("Service destination already exists: ${service.directory}")
        }

        try {
            copyTemplate(source, service.directory)
            // Group overlay layer: templates/groups/<group>/ is copied on top of the shared build
            // template so each group can ship its own plugins and configs while the jar cache and
            // auto-installed builds stay shared between all groups of the same type + version.
            // Non-static services are wiped before this runs, so the result is always exactly
            // build template (base) + group overlay (top) + adapter-managed files — never more.
            val overlay = groupOverlayDirectory(service.groupName)
            if (Files.isDirectory(overlay, LinkOption.NOFOLLOW_LINKS)) {
                copyTemplate(overlay, service.directory, overwrite = true)
            }
            adapters.get(service.type).configure(
                service,
                service.directory,
                runtime.minecraftEulaAccepted,
                forwardingProvider(),
            )
        } catch (failure: Exception) {
            deleteRecursively(service.directory)
            if (failure is TemplateException) throw failure
            throw TemplateException("Could not provision ${service.name} from $source: ${failure.message}", failure)
        }
    }

    /**
     * Resolves the optional group overlay folder `templates/groups/<group>/` for the given group
     * name. Group names are normalized case-insensitively; path-traversal names are rejected.
     */
    private fun groupOverlayDirectory(groupName: String): Path {
        val sanitized = groupName.trim().lowercase(Locale.ROOT)
        require(
            sanitized.isNotEmpty() && sanitized != "." && sanitized != ".." &&
                    !sanitized.contains('/') && !sanitized.contains('\\'),
        ) { "Invalid group name for template overlay: '$groupName'" }
        val overlay = templateRoot.resolve("groups").resolve(sanitized).normalize()
        if (!overlay.startsWith(templateRoot)) {
            throw TemplateException("Group overlay path escapes configured template root: $overlay")
        }
        return overlay
    }

    private fun copyTemplate(source: Path, destination: Path, overwrite: Boolean = false) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(dir)) {
                    throw TemplateException("Symbolic links are not allowed in server templates: $dir")
                }
                val target = destination.resolve(source.relativize(dir).toString()).normalize()
                if (!target.startsWith(destination)) throw TemplateException("Template entry escapes destination: $dir")
                Files.createDirectories(target)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(file)) {
                    throw TemplateException("Symbolic links are not allowed in server templates: $file")
                }
                val target = destination.resolve(source.relativize(file).toString()).normalize()
                if (!target.startsWith(destination)) throw TemplateException("Template entry escapes destination: $file")
                if (overwrite) {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    Files.copy(file, target)
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, failure: IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private companion object {
        const val BUILD_METADATA_FILE = ".server-build.properties"
    }
}
