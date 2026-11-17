package com.abhinavxt.novelforge.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.abhinavxt.novelforge.data.sync.KeystoreSecretStore
import com.abhinavxt.novelforge.data.sync.SyncSettings
import com.abhinavxt.novelforge.data.sync.SyncTrigger
import java.util.concurrent.TimeUnit

/** Sync is scheduled only when the user turned it on and it has a server and password. */
fun shouldSchedule(enabled: Boolean, configured: Boolean): Boolean = enabled && configured

/**
 * Queue policy of a request. App start, return to the foreground and periodic runs do not stack up
 * while the phone is offline: if a run is already waiting, the new request is dropped (KEEP). A manual
 * run and the run fired by the reading debounce queue behind a running sync (APPEND_OR_REPLACE).
 */
fun workPolicyFor(trigger: SyncTrigger): ExistingWorkPolicy = when (trigger) {
    SyncTrigger.AppStart, SyncTrigger.Periodic, SyncTrigger.Foreground -> ExistingWorkPolicy.KEEP
    SyncTrigger.Manual, SyncTrigger.ReadingStopped -> ExistingWorkPolicy.APPEND_OR_REPLACE
}

/** Minimum time between two syncs requested by returning to the foreground. */
const val FOREGROUND_SYNC_INTERVAL_MS = 10 * 60 * 1000L

/** True when a foreground return at [nowMs] may request a sync, given the time of the last one (null = none). */
fun shouldSyncOnForeground(lastRequestMs: Long?, nowMs: Long, minIntervalMs: Long = FOREGROUND_SYNC_INTERVAL_MS): Boolean =
    lastRequestMs == null || nowMs - lastRequestMs >= minIntervalMs || nowMs < lastRequestMs

/**
 * In-memory rate limit for the reading-progress hook, which fires on every save.
 * [tryAcquire] is true at most once per [minIntervalMs]; thread-safe, no I/O.
 */
class ProgressDebouncer(private val minIntervalMs: Long = 30_000L) {
    private var last = Long.MIN_VALUE

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        if (last != Long.MIN_VALUE && nowMs - last < minIntervalMs) return false
        last = nowMs
        return true
    }
}

/**
 * When a sync runs.
 *
 * Two unique names keep a running sync safe from the debounce:
 *  - [DEBOUNCE_WORK] holds only the delayed ReadingStopped timer. It uses REPLACE, so the
 *    timer restarts while the reader keeps saving. Its worker does no sync work; when the
 *    delay ends it enqueues the real run, so REPLACE can only ever cancel a timer.
 *  - [SYNC_WORK] holds the real runs, with APPEND_OR_REPLACE (as in the design): a request
 *    arriving during a run is queued behind it and never cancels it.
 */
object SyncScheduler {
    const val SYNC_WORK = "nf-sync"
    const val DEBOUNCE_WORK = "nf-sync-debounce"
    const val PERIODIC_WORK = "nf-sync-periodic"
    const val READING_STOPPED_DELAY_MS = 120_000L

    private val progressDebouncer = ProgressDebouncer()

    /** Last time (wall clock) a start or foreground return requested a sync; memory only. */
    @Volatile private var lastForegroundRequest: Long? = null

    private fun settings(context: Context) =
        SyncSettings(context.applicationContext, KeystoreSecretStore(context.applicationContext))

    /** One SharedPreferences read when sync is off; the password is checked only when it is on. */
    private fun active(context: Context): Boolean {
        val s = settings(context)
        // Presence check only: this runs on the main thread and must not decrypt the Keystore secret.
        return s.enabled && shouldSchedule(true, s.looksConfigured)
    }

    private fun connected() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun request(context: Context, trigger: SyncTrigger, delayMs: Long = 0) {
        if (!active(context)) return
        val wm = WorkManager.getInstance(context.applicationContext)
        val delayed = delayMs > 0 && trigger == SyncTrigger.ReadingStopped
        val data = Data.Builder()
            .putString(SyncWorker.KEY_TRIGGER, trigger.name)
            .putBoolean(SyncWorker.KEY_DEBOUNCE, delayed)
            .build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(connected())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(data)
            .apply { if (delayed) setInitialDelay(delayMs, TimeUnit.MILLISECONDS) }
            .build()
        if (delayed) {
            wm.enqueueUniqueWork(DEBOUNCE_WORK, ExistingWorkPolicy.REPLACE, request)
        } else {
            wm.enqueueUniqueWork(SYNC_WORK, workPolicyFor(trigger), request)
        }
    }

    /** Called after every reading-progress save. Cheap: memory check first, WorkManager at most every 30 s. */
    fun onProgressSaved(context: Context) {
        if (!progressDebouncer.tryAcquire(System.currentTimeMillis())) return
        request(context, SyncTrigger.ReadingStopped, READING_STOPPED_DELAY_MS)
    }

    /** The app came to the foreground (the first started activity). At most once per 10 minutes; free when sync is off. */
    fun onForeground(context: Context) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (!shouldSyncOnForeground(lastForegroundRequest, now)) return
            lastForegroundRequest = now
        }
        request(context, SyncTrigger.Foreground)
    }

    fun enablePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .setInputData(Data.Builder().putString(SyncWorker.KEY_TRIGGER, SyncTrigger.Periodic.name).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun disablePeriodic(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(PERIODIC_WORK)
    }

    /** Call when the user saves, enables, disables or clears sync settings. */
    fun onSettingsChanged(context: Context) {
        if (active(context)) {
            enablePeriodic(context)
        } else {
            val wm = WorkManager.getInstance(context.applicationContext)
            wm.cancelUniqueWork(PERIODIC_WORK)
            wm.cancelUniqueWork(DEBOUNCE_WORK)
            wm.cancelUniqueWork(SYNC_WORK)
        }
    }

    /** Process start: re-arms the periodic work, and does nothing at all when sync is off. */
    fun onAppStart(context: Context) {
        if (!active(context)) return
        enablePeriodic(context)
        lastForegroundRequest = System.currentTimeMillis() // the start sync counts as the latest one
        request(context, SyncTrigger.AppStart)
    }
}
