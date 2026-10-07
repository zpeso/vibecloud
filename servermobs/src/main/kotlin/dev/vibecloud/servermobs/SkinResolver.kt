package dev.vibecloud.servermobs

import dev.vibecloud.servermobs.model.NpcSkin
import org.yaml.snakeyaml.Yaml
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

/**
 * Turns an admin-supplied skin spec into a base64 texture [NpcSkin].
 *
 * Accepted specs:
 *  - `http(s)://...`     a direct skin texture URL (no signature),
 *  - `<value>;<signature>` a raw Mojang texture value + signature,
 *  - `<value>`           a raw base64 texture value,
 *  - `SomePlayerName`    looked up from the Mojang API (blocking; call [lookupPlayer] off-thread).
 */
class SkinResolver(private val logger: Logger) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val yaml = Yaml()

    /**
     * Resolves everything that needs no network access. Returns null when [spec] is a player name
     * that must be looked up with [lookupPlayer].
     */
    fun resolveLocal(spec: String): NpcSkin? {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return NpcSkin(value = valueForUrl(trimmed), signature = null, source = trimmed)
        }
        if (trimmed.contains(';')) {
            val value = trimmed.substringBefore(';').trim()
            val signature = trimmed.substringAfter(';').trim().takeIf { it.isNotEmpty() }
            if (value.isEmpty()) return null
            return NpcSkin(value = value, signature = signature, source = "value;signature")
        }
        // A raw base64 texture value always decodes to a JSON object starting with `{`.
        if (trimmed.startsWith("eyJ") || looksLikeBase64Json(trimmed)) {
            return NpcSkin(value = trimmed, signature = null, source = "value")
        }
        return null
    }

    /** Resolves the skin of a player name through the Mojang API. Blocks on network I/O. */
    fun lookupPlayer(name: String): NpcSkin? {
        val profileId = mojangProfileId(name) ?: return null
        val body = get("https://sessionserver.mojang.com/session/minecraft/profile/$profileId?unsigned=false")
            ?: return null
        val properties = (parse(body)["properties"] as? List<*>).orEmpty()
        for (property in properties) {
            val map = property as? Map<*, *> ?: continue
            if ((map["name"] as? String) != "textures") continue
            val value = (map["value"] as? String)?.trim().orEmpty()
            if (value.isEmpty()) continue
            return NpcSkin(
                value = value,
                signature = (map["signature"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                source = name,
            )
        }
        logger.warning("Mojang returned no textures property for '$name'")
        return null
    }

    /** Runs [lookupPlayer] on a background thread and delivers the (nullable) result to [callback]. */
    fun lookupPlayerAsync(name: String, callback: (NpcSkin?) -> Unit) {
        CompletableFuture.supplyAsync {
            runCatching { lookupPlayer(name) }
                .onFailure { logger.warning("Skin lookup for '$name' failed: ${it.message}") }
                .getOrNull()
        }.thenAccept(callback)
    }

    private fun mojangProfileId(name: String): String? {
        val encoded = java.net.URLEncoder.encode(name, Charsets.UTF_8)
        val body = get("https://api.mojang.com/users/profiles/minecraft/$encoded") ?: return null
        val id = (parse(body)["id"] as? String)?.trim().orEmpty()
        if (id.isEmpty()) return null
        return if (id.length == 32) {
            buildString {
                append(id, 0, 8).append('-').append(id, 8, 12).append('-')
                append(id, 12, 16).append('-').append(id, 16, 20).append('-').append(id, 20, 32)
            }
        } else {
            id
        }
    }

    private fun get(url: String): String? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                logger.fine("Mojang request $url returned HTTP ${response.statusCode()}")
                null
            } else {
                response.body()
            }
        } catch (failure: Exception) {
            logger.fine("Mojang request $url failed: ${failure.message}")
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(json: String): Map<String, Any?> =
        runCatching { yaml.load<Any?>(json) as? Map<String, Any?> }.getOrNull().orEmpty()

    /** Wraps a texture URL into the base64 JSON texture property the client expects. */
    private fun valueForUrl(url: String): String {
        val json = """{"textures":{"SKIN":{"url":${quote(url)}}}}"""
        return Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun looksLikeBase64Json(value: String): Boolean {
        if (!value.matches(Regex("[A-Za-z0-9+/=]+"))) return false
        return runCatching {
            String(Base64.getDecoder().decode(value), Charsets.UTF_8).trimStart().startsWith("{")
        }.getOrDefault(false)
    }
}
