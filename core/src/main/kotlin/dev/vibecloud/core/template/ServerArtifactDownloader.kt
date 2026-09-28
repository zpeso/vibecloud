package dev.vibecloud.core.template

import dev.vibecloud.api.server.ServerBuild
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

fun interface ServerArtifactDownloader {
    suspend fun download(build: ServerBuild, destination: Path)
}

internal class HttpServerArtifactDownloader(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val userAgent: String = "VibeCloudController/0.1.0 (https://github.com/)",
    private val allowedHosts: Set<String> = setOf("fill-data.papermc.io", "hub.spigotmc.org", "ci.md-5.net"),
) : ServerArtifactDownloader {
    override suspend fun download(build: ServerBuild, destination: Path) = withContext(Dispatchers.IO) {
        val uri = build.downloadUrl
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host !in allowedHosts) {
            throw TemplateException("Refusing untrusted server artifact URL: $uri")
        }
        Files.createDirectories(destination.parent)
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMinutes(10))
            .header("Accept", "application/java-archive, application/octet-stream")
            .header("User-Agent", userAgent)
            .GET()
            .build()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofFile(destination))
        } catch (failure: CancellationException) {
            Files.deleteIfExists(destination)
            throw failure
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            Files.deleteIfExists(destination)
            throw CancellationException("Download of ${build.fileName} was cancelled").also { it.initCause(failure) }
        } catch (failure: Exception) {
            Files.deleteIfExists(destination)
            throw TemplateException("Could not download ${build.fileName}: ${failure.message}", failure)
        }
        if (response.statusCode() !in 200..299) {
            Files.deleteIfExists(destination)
            throw TemplateException("Download of ${build.fileName} returned HTTP ${response.statusCode()}")
        }
        if (!Files.isRegularFile(destination) || Files.size(destination) == 0L) {
            Files.deleteIfExists(destination)
            throw TemplateException("Download of ${build.fileName} produced an empty file")
        }
    }
}
