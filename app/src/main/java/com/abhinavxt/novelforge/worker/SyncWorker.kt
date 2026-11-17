package com.abhinavxt.novelforge.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.abhinavxt.novelforge.data.sync.KeystoreSecretStore
import com.abhinavxt.novelforge.data.sync.SyncEngine
import com.abhinavxt.novelforge.data.sync.SyncOutcome
import com.abhinavxt.novelforge.data.sync.SyncSettings
import com.abhinavxt.novelforge.data.sync.SyncTrigger
import com.abhinavxt.novelforge.util.Logger

/** What a finished sync means for WorkManager. */
enum class SyncWorkVerdict { Done, Retry }

/** Give up after this many runs of one request. */
const val SYNC_MAX_ATTEMPTS = 3

/**
 * Outcomes a retry cannot fix are Done (the text is already in settings for the UI);
 * transient ones retry with the work's backoff until [SYNC_MAX_ATTEMPTS] runs were used.
 * [runAttemptCount] is zero-based, as WorkManager reports it.
 */
fun verdictFor(outcome: SyncOutcome, runAttemptCount: Int): SyncWorkVerdict {
    val transient = when (outcome) {
        is SyncOutcome.ServerUnreachable -> true
        // Out of memory (retry = false) would only repeat itself.
        is SyncOutcome.Failed -> outcome.retry
        is SyncOutcome.ServerError -> outcome.status >= 500
        else -> false
    }
    return if (transient && runAttemptCount + 1 < SYNC_MAX_ATTEMPTS) SyncWorkVerdict.Retry else SyncWorkVerdict.Done
}

/**
 * Background sync run. Also doubles as the debounce timer for ReadingStopped: a request
 * carrying [KEY_DEBOUNCE] only enqueues the real, immediate run when its delay elapses
 * (see [SyncScheduler.request]).
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trigger = parseTrigger(inputData.getString(KEY_TRIGGER))
        val settings = SyncSettings(applicationContext, KeystoreSecretStore(applicationContext))
        if (!settings.enabled || !settings.isConfigured) return Result.success()

        if (inputData.getBoolean(KEY_DEBOUNCE, false)) {
            SyncScheduler.request(applicationContext, trigger)
            return Result.success()
        }

        val engine = SyncEngine.create(applicationContext) ?: return Result.success()
        return try {
            val outcome = engine.sync(trigger)
            when (verdictFor(outcome, runAttemptCount)) {
                SyncWorkVerdict.Retry -> Result.retry()
                SyncWorkVerdict.Done -> Result.success()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "sync crashed", e)
            if (runAttemptCount + 1 < SYNC_MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val KEY_TRIGGER = "trigger"
        const val KEY_DEBOUNCE = "debounce"

        fun parseTrigger(name: String?): SyncTrigger =
            SyncTrigger.values().firstOrNull { it.name == name } ?: SyncTrigger.Periodic
    }
}
