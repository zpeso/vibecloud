package dev.vibecloud.core.service

import dev.vibecloud.api.service.Service
import dev.vibecloud.core.process.ManagedProcess
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps a bounded output history per service and manages one interactive console session
 * (`service screen`). Output is recorded from process launch so attaching shows recent lines;
 * live lines are delivered to the attached session.
 */
internal class ServiceConsoleManager {
    private val buffers = ConcurrentHashMap<String, ConsoleBuffer>()
    private val attachedService = AtomicReference<String?>(null)

    @Volatile
    private var outputSink: ((String) -> Unit)? = null

    /** Called by the service manager for every service output line. */
    fun record(serviceName: String, line: String) {
        buffers.computeIfAbsent(serviceName) { ConsoleBuffer() }.add(line)
        if (attachedService.get() == serviceName) outputSink?.invoke(line)
    }

    fun isAttachedTo(serviceName: String): Boolean = attachedService.get() == serviceName

    fun history(serviceName: String, maxLines: Int): List<String> =
        buffers[serviceName]?.snapshot(maxLines) ?: emptyList()

    /**
     * Attaches to [service]'s console and blocks until the user types the detach command or the
     * process exits. Returns false when another session is already attached.
     */
    suspend fun attach(
        service: Service,
        process: ManagedProcess,
        inputReader: suspend () -> String?,
        onOutput: (String) -> Unit,
        historyLines: Int = DEFAULT_HISTORY_LINES,
    ): Boolean {
        if (!attachedService.compareAndSet(null, service.name)) {
            onOutput("Already attached to another console. Detach there first (type 'exit').")
            return false
        }
        outputSink = onOutput
        try {
            history(service.name, historyLines).forEach(onOutput)
            onOutput("-- ${service.name} console -- type a command, or 'exit' to detach --")
            while (process.isRunning && attachedService.get() == service.name) {
                val raw = inputReader() ?: break
                val text = raw.trim()
                if (text.equals(DETACH_COMMAND, ignoreCase = true)) break
                if (text.isEmpty()) continue
                onOutput("> " + text)
                if (!process.sendCommand(text)) {
                    onOutput("Could not send the command; the process is no longer running.")
                    break
                }
            }
            return true
        } finally {
            if (attachedService.compareAndSet(service.name, null)) {
                outputSink = null
                onOutput("-- detached from ${service.name} console --")
            }
        }
    }

    /** Forcefully detaches any active session (used when the cloud stops or the process dies). */
    fun detach() {
        attachedService.getAndSet(null)
        outputSink = null
    }

    private class ConsoleBuffer {
        private val lock = Any()
        private val lines = ArrayDeque<String>()

        fun add(line: String) {
            synchronized(lock) {
                lines.addLast(line)
                while (lines.size > CAPACITY) lines.removeFirst()
            }
        }

        fun snapshot(maxLines: Int): List<String> = synchronized(lock) {
            val count = maxLines.coerceIn(1, CAPACITY)
            lines.toList().takeLast(count)
        }

        private companion object {
            const val CAPACITY = 1000
        }
    }

    private companion object {
        const val DETACH_COMMAND = "exit"
        const val DEFAULT_HISTORY_LINES = 25
    }
}
