package dev.vibecloud.core.port

import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Synchronized local-node allocator. It checks the OS before reserving a port and
 * also tracks cloud-owned reservations to avoid collisions between services.
 */
class PortRangeAllocator(
    private val range: IntRange,
    private val isBindable: (Int) -> Boolean = ::canBindLocally,
) : PortAllocator {
    private val ownerToPort = mutableMapOf<String, Int>()
    private val portToOwner = mutableMapOf<Int, String>()

    init {
        require(!range.isEmpty()) { "Port range must not be empty" }
        require(range.first in 1..65535 && range.last in 1..65535) { "Port range must be within 1..65535" }
    }

    @Synchronized
    override fun allocate(owner: String): Int {
        require(owner.isNotBlank()) { "Port owner must not be blank" }
        ownerToPort[owner]?.let { return it }

        for (port in range) {
            if (port !in portToOwner && isBindable(port)) {
                ownerToPort[owner] = port
                portToOwner[port] = owner
                return port
            }
        }
        throw PortAllocationException("No free port is available in configured range ${range.first}-${range.last}")
    }

    @Synchronized
    override fun reserve(owner: String, port: Int) {
        require(owner.isNotBlank()) { "Port owner must not be blank" }
        require(port in 1..65535) { "Invalid TCP port $port" }
        val existing = ownerToPort[owner]
        if (existing != null && existing != port) {
            throw PortAllocationException("Owner '$owner' already holds port $existing, cannot also reserve $port")
        }
        val otherOwner = portToOwner[port]
        if (otherOwner != null && otherOwner != owner) {
            throw PortAllocationException("Port $port is already reserved by '$otherOwner' and cannot be assigned to '$owner'")
        }
        ownerToPort[owner] = port
        portToOwner[port] = owner
    }

    @Synchronized
    override fun release(owner: String) {
        ownerToPort.remove(owner)?.let { port ->
            if (portToOwner[port] == owner) portToOwner.remove(port)
        }
    }

    @Synchronized
    fun reservations(): Map<String, Int> = ownerToPort.toMap()

    private companion object {
        fun canBindLocally(port: Int): Boolean = try {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress("0.0.0.0", port))
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
