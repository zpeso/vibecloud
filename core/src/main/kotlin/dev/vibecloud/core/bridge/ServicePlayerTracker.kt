package dev.vibecloud.core.bridge

import dev.vibecloud.api.service.Service
import dev.vibecloud.api.service.ServiceState

/**
 * Tracks per-service online player counts from console output. Backends without a bridge agent
 * still report approximate counts this way (join/leave messages), while agents provide exact
 * values via heartbeats; agent values always win because they arrive later and are authoritative.
 */
class ServicePlayerTracker {
    private data class Entry(val names: LinkedHashSet<String>, val fromAgent: Boolean)

    private val players = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    /** Feed one console line recorded for [serviceName]. Cheap: a few contains checks. */
    fun onConsoleLine(serviceName: String, line: String) {
        val name = extractPlayerName(line, JOIN_PATTERNS)
        if (name != null) {
            players.compute(serviceName) { _, entry ->
                val base = entry?.takeUnless { it.fromAgent } ?: Entry(LinkedHashSet(), false)
                base.names += name
                base
            }
            return
        }
        val left = extractPlayerName(line, LEAVE_PATTERNS)
        if (left != null) {
            players.computeIfPresent(serviceName) { _, entry ->
                entry.names -= left
                entry
            }
        }
    }

    /** Drops all tracked state for a service (called when it stops or is deleted). */
    fun clear(serviceName: String) {
        players.remove(serviceName)
    }

    /** Registers an authoritative agent-reported roster, replacing any console-derived estimate. */
    fun applyAgentReport(serviceName: String, agentPlayers: List<String>) {
        players[serviceName] = Entry(LinkedHashSet(agentPlayers), fromAgent = true)
    }

    fun playerNames(serviceName: String): List<String> =
        players[serviceName]?.names?.toList().orEmpty()

    fun playerCount(serviceName: String): Int = players[serviceName]?.names?.size ?: 0

    /** All services currently being tracked (used to clean up deleted services). */
    fun names(): List<String> = players.keys.toList()

    fun snapshot(): Map<String, Int> =
        players.entries.associate { it.key to it.value.names.size }

    /** Console estimates are reset when a process exits; agent reports survive only while fresh. */
    fun onServiceStateChange(service: Service) {
        if (service.state != ServiceState.RUNNING) clear(service.name)
    }

    private fun extractPlayerName(line: String, patterns: List<Regex>): String? {
        for (pattern in patterns) {
            val result = pattern.find(line) ?: continue
            val name = result.groupValues[1]
            if (name.isNotEmpty() && name.length <= MAX_NAME_LENGTH) return name
        }
        return null
    }

    private companion object {
        const val MAX_NAME_LENGTH = 16

        // Vanilla/Paper log lines, e.g. "Steve joined the game" / "Steve left the game".
        val JOIN_PATTERNS = listOf(
            Regex("""\b([A-Za-z0-9_]{1,16}) joined the game"""),
        )
        val LEAVE_PATTERNS = listOf(
            Regex("""\b([A-Za-z0-9_]{1,16}) left the game"""),
        )
    }
}
