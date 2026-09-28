package dev.vibecloud.core.server

import dev.vibecloud.api.server.ServerBuild
import dev.vibecloud.api.server.ServerCatalog
import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.server.ServerVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ServerCatalogException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Catalog for PaperMC Fill v3 (Paper, Velocity, and Waterfall) plus the official SpigotMC
 * Jenkins metadata API for BungeeCord. Spigot itself is intentionally left to BuildTools/local
 * templates because SpigotMC does not publish a ready-to-run server JAR through Fill.
 */
class PaperMcServerCatalog(
    private val fillBaseUri: URI = URI.create("https://fill.papermc.io/v3/"),
    private val bungeeJobUri: URI = URI.create("https://hub.spigotmc.org/jenkins/job/BungeeCord/"),
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val cacheDuration: Duration = Duration.ofMinutes(2),
) : ServerCatalog {
    private val responseCache = ConcurrentHashMap<URI, CachedResponse>()
    private val fillBase = URI.create(fillBaseUri.toString().trimEnd('/') + "/")
    private val bungeeJob = URI.create(bungeeJobUri.toString().trimEnd('/') + "/")

    override suspend fun versions(type: ServerType): List<ServerVersion> = when (type) {
        ServerType.PAPER -> fillVersions("paper", "Paper")
        ServerType.VELOCITY -> fillVersions("velocity", "Velocity")
        ServerType.BUNGEECORD -> buildList {
            add(
                ServerVersion(
                    id = BUNGEE_VERSION_ID,
                    distribution = "BungeeCord (official CI)",
                    version = "latest upstream builds",
                    metadata = mapOf("source" to "SpigotMC Jenkins"),
                ),
            )
            val waterfallVersions = try {
                fillVersions("waterfall", "Waterfall (Bungee-compatible, EOL)")
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                emptyList()
            }
            waterfallVersions.forEach { waterfall ->
                add(waterfall.copy(id = "$WATERFALL_VERSION_PREFIX${waterfall.id}"))
            }
        }

        ServerType.SPIGOT -> emptyList()
    }

    override suspend fun build(type: ServerType, key: String): ServerBuild? {
        if (type == ServerType.SPIGOT) return null
        return when {
            type == ServerType.BUNGEECORD && key.startsWith("bungeecord-") ->
                builds(type, BUNGEE_VERSION_ID).firstOrNull { it.key == key }

            type == ServerType.BUNGEECORD && key.startsWith("waterfall-") -> {
                val versionAndBuild = key.removePrefix("waterfall-")
                val split = versionAndBuild.lastIndexOf('-')
                if (split <= 0 || versionAndBuild.substring(split + 1).toLongOrNull() == null) return null
                val versionId = "$WATERFALL_VERSION_PREFIX${versionAndBuild.substring(0, split)}"
                builds(type, versionId).firstOrNull { it.key == key }
            }

            type != ServerType.BUNGEECORD && key.startsWith("${type.templateKey}-") -> {
                val versionAndBuild = key.removePrefix("${type.templateKey}-")
                val split = versionAndBuild.lastIndexOf('-')
                if (split <= 0 || versionAndBuild.substring(split + 1).toLongOrNull() == null) return null
                builds(type, versionAndBuild.substring(0, split)).firstOrNull { it.key == key }
            }

            else -> null
        }
    }

    override suspend fun builds(type: ServerType, versionId: String): List<ServerBuild> = when (type) {
        ServerType.PAPER -> fillBuilds(type, "paper", "Paper", versionId)
        ServerType.VELOCITY -> fillBuilds(type, "velocity", "Velocity", versionId)
        ServerType.BUNGEECORD -> when {
            versionId == BUNGEE_VERSION_ID -> bungeeBuilds()
            versionId.startsWith(WATERFALL_VERSION_PREFIX) -> {
                val gameVersion = versionId.removePrefix(WATERFALL_VERSION_PREFIX)
                fillBuilds(
                    type = type,
                    project = "waterfall",
                    distribution = "Waterfall (Bungee-compatible, EOL)",
                    version = gameVersion,
                    keyPrefix = "waterfall",
                )
            }

            else -> throw ServerCatalogException("Unknown BungeeCord catalog version '$versionId'")
        }

        ServerType.SPIGOT -> emptyList()
    }

    private suspend fun fillVersions(project: String, distribution: String): List<ServerVersion> {
        val root = requestJson(fillUri("projects/$project")).objectMap("$project project metadata")
        val versionGroups = root["versions"].objectMap("$project version list")
        return versionGroups.flatMap { (group, rawVersions) ->
            (rawVersions as? List<*>)?.mapNotNull { rawVersion ->
                rawVersion?.toString()?.takeIf(String::isNotBlank)?.let { version ->
                    ServerVersion(
                        id = version,
                        distribution = distribution,
                        version = version,
                        metadata = mapOf("version-group" to group),
                    )
                }
            } ?: emptyList()
        }.distinctBy(ServerVersion::id)
    }

    private suspend fun fillBuilds(
        type: ServerType,
        project: String,
        distribution: String,
        version: String,
        keyPrefix: String = project,
    ): List<ServerBuild> {
        if (!VERSION_SEGMENT.matches(version)) {
            throw ServerCatalogException("Unsafe $distribution version '$version'")
        }
        val detailUri = fillUri("projects/$project/versions/${pathSegment(version)}")
        val buildUri = fillUri("projects/$project/versions/${pathSegment(version)}/builds")
        val detail = requestJson(detailUri).objectMap("$distribution $version metadata")
        val versionMetadata = detail["version"].objectMap("$distribution $version metadata.version")
        val rawBuilds = requestJson(buildUri) as? List<*>
            ?: throw ServerCatalogException("Unexpected build list response from $buildUri")
        val flattenedVersionMetadata = linkedMapOf<String, String>()
        flattenMetadata("version", versionMetadata, flattenedVersionMetadata)

        return rawBuilds.mapNotNull { rawBuild ->
            val build = rawBuild as? Map<*, *> ?: return@mapNotNull null
            val buildId = build["id"]?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val downloads = build["downloads"].objectMap("$distribution build $buildId downloads")
            val artifact = (downloads["server:default"] ?: downloads["application"])
                .objectMap("$distribution build $buildId server download")
            val rawUrl = artifact["url"]?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val url = runCatching { URI.create(rawUrl) }.getOrElse {
                throw ServerCatalogException("Invalid download URL in $distribution build $buildId", it)
            }
            val name = artifact["name"]?.toString()?.takeIf(String::isNotBlank)
                ?: "${project}-$version-$buildId.jar"
            val checksum = artifact["checksums"].objectMap("$distribution build $buildId checksums")["sha256"]
                ?.toString()?.takeIf(String::isNotBlank)
            val metadata = LinkedHashMap(flattenedVersionMetadata)
            val buildFields = build.entries.associate { (key, value) -> key.toString() to value }
            flattenMetadata("build", buildFields, metadata, excludedKeys = setOf("downloads"))
            metadata["source"] = "PaperMC Fill v3"
            metadata["project"] = project
            metadata["minecraft-or-software-version"] = version
            val commits = build["commits"] as? List<*>
            commits?.forEachIndexed { index, rawCommit ->
                val commit = rawCommit as? Map<*, *> ?: return@forEachIndexed
                commit["sha"]?.toString()?.let { metadata["commit.${index + 1}.sha"] = it }
                commit["time"]?.toString()?.let { metadata["commit.${index + 1}.time"] = it }
                commit["message"]?.toString()?.let { metadata["commit.${index + 1}.message"] = it.take(1000) }
            }
            val timestamp = build["time"]?.toString()?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val key = "$keyPrefix-$version-$buildId"
            ServerBuild(
                type = type,
                key = key,
                distribution = distribution,
                version = version,
                build = buildId,
                channel = build["channel"]?.toString() ?: "UNKNOWN",
                fileName = name,
                downloadUrl = url,
                sha256 = checksum,
                sizeBytes = artifact["size"].toLongOrNull(),
                releasedAt = timestamp,
                metadata = metadata,
            )
        }
    }

    private suspend fun bungeeBuilds(): List<ServerBuild> {
        val query = "tree=builds%5Bnumber%2Cresult%2Ctimestamp%2Curl%2Cartifacts%5BfileName%2CrelativePath%5D%5D"
        val root = requestJson(URI.create("${bungeeJob}api/json?$query")).objectMap("BungeeCord Jenkins metadata")
        val builds = root["builds"] as? List<*>
            ?: throw ServerCatalogException("BungeeCord Jenkins did not return a build list")
        return builds.mapNotNull { rawBuild ->
            val build = rawBuild as? Map<*, *> ?: return@mapNotNull null
            if (!build["result"].toString().equals("SUCCESS", ignoreCase = true)) return@mapNotNull null
            val number = build["number"].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val artifacts = build["artifacts"] as? List<*> ?: return@mapNotNull null
            val artifact = artifacts.asSequence()
                .mapNotNull { it as? Map<*, *> }
                .firstOrNull { it["fileName"]?.toString() == "BungeeCord.jar" }
                ?: return@mapNotNull null
            val relativePath = artifact["relativePath"]?.toString()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val buildUrl = build["url"]?.toString()?.takeIf(String::isNotBlank)
                ?: "${bungeeJob}${number}/"
            val downloadUrl = URI.create("${buildUrl.trimEnd('/')}/artifact/${relativePath.trimStart('/')}")
            val timestamp =
                build["timestamp"].toLongOrNull()?.let { runCatching { Instant.ofEpochMilli(it) }.getOrNull() }
            ServerBuild(
                type = ServerType.BUNGEECORD,
                key = "bungeecord-$number",
                distribution = "BungeeCord (official CI)",
                version = "BungeeCord",
                build = number.toString(),
                channel = "SUCCESS",
                fileName = artifact["fileName"].toString(),
                downloadUrl = downloadUrl,
                releasedAt = timestamp,
                metadata = mapOf(
                    "source" to "SpigotMC Jenkins",
                    "jenkins-build-url" to buildUrl,
                    "artifact-path" to relativePath,
                    "integrity" to "Upstream does not publish a SHA-256 checksum for this artifact",
                ),
            )
        }.sortedByDescending { it.build.toLongOrNull() ?: 0L }
    }

    private suspend fun requestJson(uri: URI): Any? {
        val cached = responseCache[uri]
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) return cached.value
        val value = withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .GET()
                .build()
            val response = try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("Catalog request to $uri was cancelled").also { it.initCause(failure) }
            } catch (failure: Exception) {
                throw ServerCatalogException("Could not reach server catalog $uri: ${failure.message}", failure)
            }
            if (response.statusCode() !in 200..299) {
                throw ServerCatalogException("Server catalog returned HTTP ${response.statusCode()} for $uri")
            }
            try {
                Yaml(SafeConstructor(LoaderOptions().apply { codePointLimit = MAX_JSON_CHARACTERS }))
                    .load<Any?>(response.body())
            } catch (failure: Exception) {
                throw ServerCatalogException(
                    "Could not parse server catalog response from $uri: ${failure.message}",
                    failure
                )
            }
        }
        responseCache[uri] = CachedResponse(value, Instant.now().plus(cacheDuration))
        return value
    }

    private fun fillUri(path: String): URI = fillBase.resolve(path)

    private fun pathSegment(value: String): String = java.net.URLEncoder
        .encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")

    private fun Any?.objectMap(context: String): Map<String, Any?> {
        val value = this as? Map<*, *>
            ?: throw ServerCatalogException("Unexpected response structure for $context")
        return value.entries.associate { (key, item) ->
            val name = key as? String ?: throw ServerCatalogException("Non-string JSON key in $context")
            name to item
        }
    }

    private fun Any?.toLongOrNull(): Long? = when (this) {
        is Number -> toLong()
        is String -> toLongOrNull()
        else -> null
    }

    private fun flattenMetadata(
        prefix: String,
        values: Map<String, Any?>,
        target: MutableMap<String, String>,
        excludedKeys: Set<String> = emptySet(),
    ) {
        values.forEach { (key, value) ->
            if (key in excludedKeys || value == null) return@forEach
            val fullKey = "$prefix.$key"
            when (value) {
                is Map<*, *> -> {
                    val nested = value.entries.associate { it.key.toString() to it.value }
                    flattenMetadata(fullKey, nested, target)
                }

                is List<*> -> target[fullKey] = value.joinToString(" | ") { item ->
                    when (item) {
                        is Map<*, *> -> item.entries.joinToString(", ") { "${it.key}=${it.value}" }
                        else -> item.toString()
                    }
                }.take(2000)

                else -> target[fullKey] = value.toString().take(2000)
            }
        }
    }

    private data class CachedResponse(val value: Any?, val expiresAt: Instant)

    private companion object {
        const val BUNGEE_VERSION_ID = "bungeecord"
        const val WATERFALL_VERSION_PREFIX = "waterfall:"
        const val MAX_JSON_CHARACTERS = 8 * 1024 * 1024
        const val DEFAULT_USER_AGENT = "VibeCloudController/0.1.0 (https://github.com/)"
        val VERSION_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}")
    }
}
