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
}
