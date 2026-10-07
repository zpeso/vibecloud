package dev.vibecloud.servermobs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage

/** Shared MiniMessage rendering and the plugin's branded prefix. */
object Text {
    private val mini = MiniMessage.miniMessage()

    const val PREFIX = "<#ed3030>ServerMobs <dark_gray>» <gray>"

    fun component(raw: String): Component = mini.deserialize(raw)

    /** A prefixed message; [raw] may contain MiniMessage tags. */
    fun prefixed(raw: String): Component = component(PREFIX + raw)
}
