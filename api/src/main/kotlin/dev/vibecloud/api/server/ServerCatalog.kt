package dev.vibecloud.api.server

import java.net.URI
import java.time.Instant

/** A selectable software/game version returned by an upstream distribution catalog. */
data class ServerVersion(
    /** Opaque value passed back to [ServerCatalog.builds]. */
    val id: String,
    val distribution: String,
    val version: String,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "Server version id must not be blank" }
        require(distribution.isNotBlank()) { "Distribution must not be blank" }
        require(version.isNotBlank()) { "Version must not be blank" }
    }

    val displayName: String get() = "$distribution $version"
}

/** Immutable upstream artifact information, including the exact source and integrity metadata. */
data class ServerBuild(
    val type: ServerType,
    /** Safe, immutable key used for the template directory and group version. */
    val key: String,
    val distribution: String,
    val version: String,
    val build: String,
    val channel: String,
    val fileName: String,
    val downloadUrl: URI,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val releasedAt: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(KEY_PATTERN.matches(key)) { "Unsafe server build key '$key'" }
        require(distribution.isNotBlank() && version.isNotBlank() && build.isNotBlank()) {
            "Distribution, version, and build must not be blank"
        }
        require(fileName.isNotBlank() && !fileName.contains('/') && !fileName.contains('\\')) {
            "Artifact filename must be a simple filename"
        }
        require(downloadUrl.isAbsolute && downloadUrl.scheme.equals("https", ignoreCase = true)) {
            "Server artifacts must use an absolute HTTPS URL"
        }
        require(sha256 == null || SHA256_PATTERN.matches(sha256)) { "SHA-256 must contain exactly 64 hexadecimal characters" }
        require(sizeBytes == null || sizeBytes > 0) { "Artifact size must be positive when provided" }
    }

    val displayName: String
        get() = "$distribution $version build $build [$channel]"

    companion object {
        private val KEY_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}")
        private val SHA256_PATTERN = Regex("[A-Fa-f0-9]{64}")
    }
}

/** Live metadata lookup for official server distribution endpoints. */
interface ServerCatalog {
    suspend fun versions(type: ServerType): List<ServerVersion>

    /** Returns all builds supplied by the selected upstream version, newest first. */
    suspend fun builds(type: ServerType, versionId: String): List<ServerBuild>

    /** Resolves an exact pinned template/build key, or returns null when it is not from this catalog. */
    suspend fun build(type: ServerType, key: String): ServerBuild?
}
