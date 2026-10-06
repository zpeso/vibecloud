package dev.vibecloud.api.template

import dev.vibecloud.api.server.ServerBuild
import dev.vibecloud.api.server.ServerType
import dev.vibecloud.api.service.Service

/** Access to versioned server templates and service provisioning. */
interface TemplateManager {
    /** Lists local, ready-to-use template keys for this distribution. */
    fun availableVersions(type: ServerType): List<String>

    /** Downloads and verifies an upstream build into a versioned local template directory. */
    suspend fun install(build: ServerBuild)

    suspend fun provision(service: Service)

    /**
     * A cheap fingerprint of a group's template sources (build template + applicable every_server
     * or every_proxy layer + group overlay). Equal fingerprints mean the template content is
     * unchanged, so services need no refresh.
     */
    fun templateFingerprint(type: ServerType, version: String, groupName: String): String

    /**
     * Copies the current template (build template + applicable shared deployment layer + group overlay) **over** an existing service
     * directory without deleting unknown files. Used to propagate template updates to static
     * services on start: modified/added files are replaced, worlds and plugin data survive.
     * Falls back to [provision] when the service directory does not exist yet.
     */
    suspend fun updateFromTemplate(service: Service)
}
