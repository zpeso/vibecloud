package dev.vibecloud.core.port

interface PortAllocator {
    /** Reserves and returns an available port for [owner]. Repeated calls are idempotent. */
    fun allocate(owner: String): Int

    /** Restores a persisted reservation when services are loaded from disk. */
    fun reserve(owner: String, port: Int)

    /** Releases a reservation when a service is permanently deleted. */
    fun release(owner: String)
}

class PortAllocationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
