package com.abhinavxt.novelforge.data.sync

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The process-wide lock between a sync and a backup restore. [SyncEngine] holds [mutex] for the whole of
 * a sync (its default); a restore holds it through [restoring], so the two never overlap and a restore
 * started during a sync waits for it. Works when sync was never set up: nothing here needs a server.
 */
object SyncGate {
    val mutex = Mutex()

    /**
     * Runs [block] holding the gate. [block] receives a function that sets the restore token; call it
     * before the first write of the restore. The gate is released on every exit, including exceptions.
     */
    suspend fun <T> restoring(context: Context, block: suspend (markPending: () -> Unit) -> T): T =
        mutex.withLock { block { SyncSettings.markRestorePending(context) } }
}
