package dev.vibecloud.core.service

import dev.vibecloud.core.server.CloudShuttingDownException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Stops admission and lets cloud shutdown await already-admitted mutations. */
internal class OperationGate {
    private val mutex = Mutex()
    private var accepting = true
    private var activeOperations = 0
    private var drained = CompletableDeferred<Unit>().apply { complete(Unit) }

    suspend fun <T> withOperation(block: suspend () -> T): T {
        enter()
        try {
            return block()
        } finally {
            withContext(NonCancellable) { leave() }
        }
    }

    suspend fun closeAndDrain() {
        val waitFor = mutex.withLock {
            accepting = false
            if (activeOperations == 0) null else drained
        }
        waitFor?.await()
    }

    private suspend fun enter() {
        mutex.withLock {
            if (!accepting) throw CloudShuttingDownException()
            if (activeOperations == 0) drained = CompletableDeferred()
            activeOperations++
        }
    }

    private suspend fun leave() {
        mutex.withLock {
            activeOperations--
            check(activeOperations >= 0) { "Operation count underflow" }
            if (activeOperations == 0) drained.complete(Unit)
        }
    }
}
