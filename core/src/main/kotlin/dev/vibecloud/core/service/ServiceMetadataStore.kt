package dev.vibecloud.core.service

import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState
import java.io.IOException
import java.nio.file.*
import java.time.Instant
import java.util.*

internal class ServiceMetadataStore(private val serviceRoot: Path) {
    private val metadataFileName = ".cloud-service.properties"

    fun metadataFile(directory: Path): Path = directory.resolve(metadataFileName)

    fun loadAll(): List<Service> {
        if (!Files.isDirectory(serviceRoot)) return emptyList()
        val loaded = mutableListOf<Service>()
        Files.list(serviceRoot).use { directories ->
            directories.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { directory ->
                val metadata = metadataFile(directory)
                if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) return@forEach
                try {
                    val properties = Properties()
                    Files.newInputStream(metadata).use(properties::load)
                    val name = properties.required("name")
                    val group = properties.required("group")
                    val type = ServerType.parse(properties.required("type"))
                    val version = properties.required("version")
                    val id = properties.getProperty("id")?.takeIf(String::isNotBlank) ?: name
                    val port = properties.required("port").toIntOrNull()
                        ?: throw IllegalArgumentException("invalid port")
                    val createdAt = properties.getProperty("created-at")?.let(Instant::parse) ?: Instant.EPOCH
                    val updatedAt = properties.getProperty("updated-at")?.let(Instant::parse) ?: createdAt
                    loaded += Service(
                        id = id,
                        name = name,
                        groupName = group,
                        type = type,
                        version = version,
                        state = ServiceState.STOPPED,
                        port = port,
                        directory = directory.toAbsolutePath().normalize(),
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        lastExitCode = properties.getProperty("last-exit-code")?.toIntOrNull(),
                        lastError = properties.getProperty("last-error"),
                        static = properties.getProperty("static")?.toBooleanStrictOrNull() ?: true,
                    )
                } catch (failure: Exception) {
                    throw IllegalStateException("Invalid service metadata in $metadata: ${failure.message}", failure)
                }
            }
        }
        return loaded.sortedBy { it.name }
    }

    fun write(service: Service) {
        val properties = Properties().apply {
            setProperty("id", service.id)
            setProperty("name", service.name)
            setProperty("group", service.groupName)
            setProperty("type", service.type.name)
            setProperty("version", service.version)
            setProperty("port", service.port.toString())
            setProperty("state", service.state.name)
            setProperty("created-at", service.createdAt.toString())
            setProperty("updated-at", service.updatedAt.toString())
            service.lastExitCode?.let { setProperty("last-exit-code", it.toString()) }
            service.lastError?.let { setProperty("last-error", it.take(1000)) }
            setProperty("static", service.static.toString())
        }
        val target = metadataFile(service.directory)
        val temporary = service.directory.resolve("$metadataFileName.tmp")
        try {
            Files.newOutputStream(temporary).use { properties.store(it, "VibeCloud service metadata") }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: IOException) {
            throw IllegalStateException(
                "Could not persist service metadata for ${service.name}: ${failure.message}",
                failure
            )
        }
    }

    private fun Properties.required(name: String): String = getProperty(name)?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("missing '$name'")
}
