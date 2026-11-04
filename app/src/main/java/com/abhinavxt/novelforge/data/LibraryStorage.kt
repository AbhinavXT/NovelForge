package com.abhinavxt.novelforge.data

import com.abhinavxt.novelforge.data.database.DatabaseCompactor
import com.abhinavxt.novelforge.data.epub.LocalBookArchive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The library-wide storage jobs: archive every imported book, and shrink the
 * database file afterwards.
 *
 * Runs in the application's scope rather than a screen's, because archiving
 * thousands of books takes minutes and nobody watches a settings screen for
 * that long. Leaving the screen keeps it going; the process dying stops it,
 * which is safe — each book is archived in its own transaction, so starting
 * again carries on from the first book still holding its text.
 */
class LibraryStorage(
    private val archive: LocalBookArchive,
    private val compactor: DatabaseCompactor,
    private val scope: CoroutineScope
) {

    sealed interface State {
        data object Idle : State
        data class Archiving(val done: Int, val total: Int) : State
        data object Compacting : State

        /** [archive] is null when only a compaction ran. */
        data class Finished(
            val archive: LocalBookArchive.BulkArchiveSummary?,
            val compaction: DatabaseCompactor.Result
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _usage = MutableStateFlow<DatabaseCompactor.Usage?>(null)
    val usage: StateFlow<DatabaseCompactor.Usage?> = _usage.asStateFlow()

    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    fun refreshUsage() {
        scope.launch { _usage.value = compactor.usage() }
    }

    /**
     * Archive every imported book that can be, then compact — archiving
     * alone frees nothing on disk. Does nothing if a job is already running.
     */
    fun archiveAllAndCompact() = launchJob {
        _state.value = State.Archiving(0, 0)
        val summary = archive.archiveAll(
            onProgress = { done, total -> _state.value = State.Archiving(done, total) },
            shouldStop = { stopRequested.get() }
        )
        // Compacted even when stopped: whatever was archived before the stop
        // is space the user is owed.
        _state.value = State.Compacting
        State.Finished(summary, compactor.compact())
    }

    fun compact() = launchJob {
        _state.value = State.Compacting
        State.Finished(null, compactor.compact())
    }

    /** Ask a bulk archive to stop after the current book. */
    fun stop() = stopRequested.set(true)

    /** Clear a [State.Finished] once the user has seen it. */
    fun acknowledge() {
        if (_state.value is State.Finished) _state.value = State.Idle
    }

    private fun launchJob(block: suspend () -> State.Finished) {
        if (!running.compareAndSet(false, true)) return
        stopRequested.set(false)
        scope.launch {
            try {
                _state.value = block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = State.Idle
                throw e
            } catch (e: Exception) {
                _state.value = State.Finished(
                    null, DatabaseCompactor.Result.Failed(e.message ?: "unknown error")
                )
            } finally {
                running.set(false)
                _usage.value = runCatching { compactor.usage() }.getOrNull()
            }
        }
    }
}
