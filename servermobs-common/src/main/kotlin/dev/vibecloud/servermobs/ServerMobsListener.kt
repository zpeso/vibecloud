package dev.vibecloud.servermobs

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity
import org.bukkit.Bukkit

/**
 * Turns client interactions with a fake-player NPC into actions.
 *
 * A right-click arrives as `INTERACT_ENTITY` (and, on older clients, also as an `INTERACT_AT`
 * packet in the same tick); [NpcManager.acceptInteraction] debounces the pair. Left-clicks
 * (`ATTACK`) are ignored so only a normal click fires an action.
 */
class ServerMobsListener(private val runtime: ServerMobsRuntime) :
    PacketListenerAbstract(PacketListenerPriority.NORMAL) {
    override fun onPacketReceive(event: PacketReceiveEvent) {
        if (event.packetType != PacketType.Play.Client.INTERACT_ENTITY) return
        val manager = runtime.npcManager
        val packet = WrapperPlayClientInteractEntity(event)
        val actionName = runCatching { packet.action?.name }.getOrNull()
        if (actionName == "ATTACK") return
        val npc = manager.byEntityId(packet.entityId) ?: return
        val playerId = event.user.uuid
        if (!manager.acceptInteraction(playerId, npc.data.name)) return
        val player = Bukkit.getPlayer(playerId) ?: return
        runtime.bukkitPlugin.server.scheduler.runTask(
            runtime.bukkitPlugin,
            Runnable { manager.runActions(player, npc) },
        )
    }
}
