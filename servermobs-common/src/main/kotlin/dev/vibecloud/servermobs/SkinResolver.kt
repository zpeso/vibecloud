package dev.vibecloud.servermobs

import dev.vibecloud.servermobs.model.NpcSkin
import org.yaml.snakeyaml.Yaml
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

/**
 * Turns an admin-supplied skin spec into a base64 texture [NpcSkin].
 *
 * Accepted specs:
 *  - `http(s)://...`     a direct skin image URL. Clients require a **signed** texture, so the URL
 *                        is sent to [MineSkin](https://mineskin.org) to be signed (when
 *                        [signSkins] is on); if that fails it falls back to an unsigned value,
 *  - `<value>;<signature>` a raw Mojang texture value + signature (used as-is),
 *  - `<value>`           a raw base64 texture value (used as-is),
 *  - `SomePlayerName`    looked up from the Mojang API (blocking; call [lookupPlayerAsync]).
 *
 * Everything network-bound is exposed through [resolveAsync]; call it off the main thread.
 * Uses `HttpURLConnection` (not `java.net.http`) so the same code compiles for the Java 8 build.
 */
class SkinResolver(
    private val logger: Logger,
    private val signSkins: Boolean = true,
    private val mineSkinApi: String = DEFAULT_MINESKIN_API,
) {
    private val yaml = Yaml()

    /** True when [spec] is a direct image URL (which has to be signed before clients render it). */
    fun isUrl(spec: String): Boolean {
        val trimmed = spec.trim()
        return trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
    }

    /**
     * Resolves everything that needs no network access: `value;signature` and bare base64 values.
     * Returns null for a URL or a player name — both need [resolveAsync].
     */
    fun resolveLocal(spec: String): NpcSkin? {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) return null
        if (isUrl(trimmed)) return null
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

    /**
     * Resolves a URL or player name to a skin on a background thread and hands the (nullable)
     * result to [callback] on that thread.
     */
    fun resolveAsync(spec: String, callback: (NpcSkin?) -> Unit) {
        val trimmed = spec.trim()
        CompletableFuture.supplyAsync {
            runCatching {
                if (isUrl(trimmed)) resolveUrl(trimmed) else lookupPlayer(trimmed)
            }.onFailure {
                logger.warning("Skin resolution for '$trimmed' failed: ${it.message}")
            }.getOrNull()
        }.thenAccept(callback)
    }

    /**
     * Resolves a direct image URL: signs it through MineSkin (so every client accepts it) or, when
     * signing is disabled or fails, wraps it into an unsigned texture value as a fallback.
     */
    fun resolveUrl(url: String): NpcSkin {
        if (signSkins) {
            signUrl(url)?.let { return it }
            logger.warning(
                "Could not sign skin URL '$url' through MineSkin; using it unsigned. " +
                        "Clients on 1.8 (and 1.20.2+) may show the default skin — use a " +
                        "signed '<value>;<signature>' pair instead if this keeps failing.",
            )
        }
        return NpcSkin(value = valueForUrl(url), signature = null, source = url)
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

    /** Asks MineSkin to sign [url] and returns the signed value + signature, or null on any failure. */
    private fun signUrl(url: String): NpcSkin? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URI("$mineSkinApi/generate/url").toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val body = "url=" + URLEncoder.encode(url, "UTF-8") + "&name=" + URLEncoder.encode("ServerMobs", "UTF-8")
            connection.outputStream.use { stream -> stream.write(body.toByteArray(charset("UTF-8"))) }
            val code = connection.responseCode
            if (code != 200) {
                logger.warning("MineSkin returned HTTP $code for '$url'")
                null
            } else {
                parseSigned(connection.inputStream.use { stream: InputStream -> stream.bufferedReader().readText() }, url)
            }
        } catch (failure: Exception) {
            logger.warning("MineSkin request for '$url' failed: ${failure.message}")
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** Reads a signed skin out of a MineSkin response (accepts both the V1 and the V2 shape). */
    private fun parseSigned(json: String, url: String): NpcSkin? {
        val root = parse(json)
        // V1: { "data": { "texture": { "value": ..., "signature": ... } } }
        val v1Texture = (root["data"] as? Map<*, *>)?.get("texture") as? Map<*, *>
        signedSkin(v1Texture, url)?.let { return it }
        // V2: { "skin": { "texture": { "data": { "value": ..., "signature": ... } } } }
        val v2Data = ((root["skin"] as? Map<*, *>)?.get("texture") as? Map<*, *>)?.get("data") as? Map<*, *>
        return signedSkin(v2Data, url)
    }

    private fun signedSkin(texture: Map<*, *>?, url: String): NpcSkin? {
        val value = (texture?.get("value") as? String)?.trim().orEmpty()
        if (value.isEmpty()) return null
        return NpcSkin(
            value = value,
            signature = (texture?.get("signature") as? String)?.trim()?.takeIf { it.isNotEmpty() },
            source = url,
        )
    }

    private fun mojangProfileId(name: String): String? {
        val encoded = URLEncoder.encode(name, "UTF-8")
        val body = get("https://api.mojang.com/users/profiles/minecraft/$encoded") ?: return null
        val id = (parse(body)["id"] as? String)?.trim().orEmpty()
        if (id.isEmpty()) return null
        return if (id.length == 32) {
            id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-" +
                    id.substring(16, 20) + "-" + id.substring(20, 32)
        } else {
            id
        }
    }

    private fun get(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != 200) {
                logger.fine("Mojang request $url returned HTTP ${connection.responseCode}")
                null
            } else {
                connection.inputStream.use { stream: InputStream -> stream.bufferedReader().readText() }
            }
        } catch (failure: Exception) {
            logger.fine("Mojang request $url failed: ${failure.message}")
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(json: String): Map<String, Any?> =
        runCatching { yaml.load<Any?>(json) as? Map<String, Any?> }.getOrNull().orEmpty()

    /** Wraps a texture URL into the base64 JSON texture property the client expects. */
    private fun valueForUrl(url: String): String {
        val json = "{\"textures\":{\"SKIN\":{\"url\":" + quote(url) + "}}}"
        return Base64.getEncoder().encodeToString(json.toByteArray(charset("UTF-8")))
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun looksLikeBase64Json(value: String): Boolean {
        if (!value.matches(Regex("[A-Za-z0-9+/=]+"))) return false
        return runCatching {
            String(Base64.getDecoder().decode(value), charset("UTF-8")).trimStart().startsWith("{")
        }.getOrDefault(false)
    }

    companion object {
        const val DEFAULT_MINESKIN_API = "https://api.mineskin.org"
        private const val USER_AGENT = "VibeCloud-ServerMobs/1.0 (+https://github.com/zpeso/vibecloud)"
    }
}
