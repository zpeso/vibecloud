package dev.vibecloud.core.group

import dev.vibecloud.api.group.Group
import dev.vibecloud.api.group.GroupManager
import dev.vibecloud.api.server.ServerBuild
import dev.vibecloud.api.server.ServerCatalog
import dev.vibecloud.api.service.ServiceManager
import dev.vibecloud.api.service.ServiceState
import dev.vibecloud.api.template.TemplateManager
import dev.vibecloud.core.server.GroupInUseException
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Deletes a group together with every service it provisioned: the group's desired state is zeroed
 * first (so the reconciler stops fighting the teardown or re-provisioning mid-delete), then each
 * service record is stopped and deleted (files included), and finally the group itself is removed.
 * Used by the interactive CLI and the `/cloud` bridge surface so both behave identically.
 */
class GroupCascade(private val services: ServiceManager, private val groups: GroupManager) {

    /** Stops and deletes all services of [rawName], then deletes the group. Returns deleted service names. */
    suspend fun deleteGroup(rawName: String, onProgress: (String) -> Unit = {}): List<String> {
        val name = rawName.trim().lowercase(Locale.ROOT)
        val group = groups.get(name) ?: throw NoSuchElementException("Group '$name' does not exist")
        val groupServices = services.all().filter { it.groupName == group.name }.sortedBy { it.name }

        // Zero the desired state first: the reconciler targets max(min, alwaysRunning), so with
        // 0/0 it stops helping keep services alive and never re-provisions during the teardown.
        if (group.minServices > 0 || group.alwaysRunningServices > 0) {
            runCatching { groups.update(group.copy(minServices = 0, alwaysRunningServices = 0)) }
                .onFailure { failure ->
                    onProgress("Could not pause reconciliation for '${group.name}': ${failure.message}")
                }
        }

        val deleted = mutableListOf<String>()
        groupServices.forEach { service ->
            try {
                // delete() stops the service first, then removes its directory and frees the port.
                services.delete(service.name)
                deleted += service.name
                onProgress("Deleted ${service.name}")
            } catch (failure: Exception) {
                onProgress("Could not delete ${service.name}: ${failure.message}")
            }
        }

        // A reconciler cycle that slipped in between our snapshot and the group delete may have
        // provisioned a replacement record; sweep stragglers and retry before giving up.
        var lastFailure: Exception? = null
        repeat(4) { attempt ->
            try {
                groups.delete(name)
                return deleted
            } catch (failure: GroupInUseException) {
                lastFailure = failure
                services.all().filter { it.groupName == name }.forEach { record ->
                    runCatching { services.delete(record.name) }.onSuccess { deleted += record.name }
                }
                if (attempt < 3) delay(250)
            }
        }
        throw lastFailure ?: IllegalStateException("Group '$name' could not be deleted")
    }
}

/**
 * Switches a group's server version within its own system (paper → paper, velocity → velocity —
 * the group type is immutable by design). Resolves the request against the online catalog (latest
 * stable build unless one is pinned) or an already-installed local template, downloads the build
 * when needed, and persists the new version.
 */
class GroupVersionSwitch(
    private val groups: GroupManager,
    private val templates: TemplateManager,
    private val catalog: ServerCatalog?,
) {
    data class Outcome(val group: Group, val previousVersion: String, val installedFileName: String?)

    /**
     * @param requestedVersion a catalog version id/display name (e.g. `1.8.8` or `Paper 1.8.8`),
     *   a full pinned build key (e.g. `paper-1.8.8-491`), or `local:<template-key>` for an
     *   installed template.
     * @param buildOption pins a specific upstream build (`--build`), or "latest".
     */
    suspend fun switch(
        rawName: String,
        requestedVersion: String,
        buildOption: String? = null,
        onProgress: (String) -> Unit = {},
    ): Outcome {
        val name = rawName.trim().lowercase(Locale.ROOT)
        val request = requestedVersion.trim()
        val group = groups.get(name) ?: throw NoSuchElementException("Group '$name' does not exist")
        val type = group.type // the system never changes: versions resolve within the group's own type only

        fun requireDifferent(target: String) {
            if (target == group.version) {
                throw IllegalStateException("Group '$name' is already on ${group.version}")
            }
        }

        // 1) local template (explicit local: prefix, or a bare match when no build is pinned)
        val localKey = request
            .removePrefix("local:")
            .removePrefix("template:")
            .lowercase(Locale.ROOT)
        val localVersions = runCatching { templates.availableVersions(type) }.getOrDefault(emptyList())
        if (localKey in localVersions && (buildOption == null || request.startsWith("local:", true))) {
            requireDifferent(localKey)
            val updated = group.copy(version = localKey)
            groups.update(updated)
            return Outcome(updated, group.version, null)
        }

        // 2) online catalog: match the version, pick the build, download, pin its key
        val versions = catalog?.let { runCatching { it.versions(type) }.getOrDefault(emptyList()) }.orEmpty()
        val chosen = versions.firstOrNull { version ->
            listOf(version.id, version.version, version.displayName).any { it.equals(request, ignoreCase = true) }
        } ?: throw IllegalArgumentException(
            "Unknown ${type.name} version '$request'. Use an online ${type.name} version or local:<template> for an installed template.",
        )
        val availableBuilds = catalog?.builds(type, chosen.id)
            ?: throw IllegalStateException("No ${type.name} catalog is available; install a local template instead")
        if (availableBuilds.isEmpty()) throw IllegalStateException("No downloadable builds were returned for ${chosen.displayName}")
        val build: ServerBuild = buildOption?.let { requested ->
            resolveBuild(requested, availableBuilds)
        } ?: availableBuilds.firstOrNull { it.channel.equals("STABLE", ignoreCase = true) } ?: availableBuilds.first()
        requireDifferent(build.key)

        onProgress("Downloading ${build.fileName}…")
        templates.install(build)
        val updated = group.copy(version = build.key)
        groups.update(updated)
        return Outcome(updated, group.version, build.fileName)
    }

    private fun resolveBuild(requested: String, builds: List<ServerBuild>): ServerBuild {
        if (requested.equals("latest", ignoreCase = true)) {
            return builds.firstOrNull { it.channel.equals("STABLE", ignoreCase = true) } ?: builds.first()
        }
        return builds.firstOrNull { build ->
            listOf(build.key, build.build, "${build.version}-${build.build}", build.fileName)
                .any { it.equals(requested.trim(), ignoreCase = true) }
        } ?: throw IllegalArgumentException("Unknown build '$requested'; choose a listed build number or key")
    }
}
