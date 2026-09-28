package dev.vibecloud.api.cloud

import dev.vibecloud.api.event.EventBus
import dev.vibecloud.api.group.GroupManager
import dev.vibecloud.api.server.ServerCatalog
import dev.vibecloud.api.service.ServiceManager
import dev.vibecloud.api.template.TemplateManager

interface Cloud {
    val groups: GroupManager
    val services: ServiceManager
    val events: EventBus
    val templates: TemplateManager
    val serverCatalog: ServerCatalog
    val state: CloudState

    suspend fun start()
    suspend fun reload()
    suspend fun shutdown()
}

enum class CloudState {
    NEW,
    RUNNING,
    STOPPING,
    STOPPED,
}
