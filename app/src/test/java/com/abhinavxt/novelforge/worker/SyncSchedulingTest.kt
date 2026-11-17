package com.abhinavxt.novelforge.worker

import com.abhinavxt.novelforge.data.sync.SyncOutcome
import com.abhinavxt.novelforge.data.sync.SyncTrigger
import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSchedulingTest {
    private val success = SyncOutcome.Success(0, 0, 0, 0, false, emptyList(), false)

    @Test fun permanentOutcomesAreDone() {
        listOf(
            success, SyncOutcome.Busy, SyncOutcome.NotConfigured, SyncOutcome.GuardTripped(9),
            SyncOutcome.AuthFailed, SyncOutcome.ServerFormatTooNew(2), SyncOutcome.ServerError(404),
        ).forEach { assertEquals(it.toString(), SyncWorkVerdict.Done, verdictFor(it, 0)) }
    }

    @Test fun transientOutcomesRetryThenGiveUp() {
        listOf(SyncOutcome.ServerUnreachable("x"), SyncOutcome.ServerError(503), SyncOutcome.Failed("x")).forEach {
            assertEquals(SyncWorkVerdict.Retry, verdictFor(it, 0))
            assertEquals(SyncWorkVerdict.Retry, verdictFor(it, 1))
            assertEquals(SyncWorkVerdict.Done, verdictFor(it, 2))
        }
    }

    @Test fun scheduleOnlyWhenEnabledAndConfigured() {
        assertTrue(shouldSchedule(true, true))
        assertFalse(shouldSchedule(true, false))
        assertFalse(shouldSchedule(false, true))
        assertFalse(shouldSchedule(false, false))
    }

    @Test fun debouncerFiresOncePerWindow() {
        val d = ProgressDebouncer(30_000)
        assertTrue(d.tryAcquire(1_000))
        assertFalse(d.tryAcquire(1_001))
        assertFalse(d.tryAcquire(30_999))
        assertTrue(d.tryAcquire(31_000))
        assertFalse(d.tryAcquire(40_000))
    }

    @Test fun outOfMemoryFailureIsNeverRetried() {
        assertEquals(SyncWorkVerdict.Done, verdictFor(SyncOutcome.Failed("memory", retry = false), 0))
        assertEquals(SyncWorkVerdict.Retry, verdictFor(SyncOutcome.Failed("other"), 0))
    }

    @Test fun foregroundSyncIsRateLimitedToOncePerTenMinutes() {
        assertTrue(shouldSyncOnForeground(null, 1_000))
        assertFalse(shouldSyncOnForeground(1_000, 1_000 + 9 * 60_000))
        assertTrue(shouldSyncOnForeground(1_000, 1_000 + FOREGROUND_SYNC_INTERVAL_MS))
        assertTrue("clock moved back: allow", shouldSyncOnForeground(50_000, 10_000))
    }

    @Test fun startedRunsDoNotStackWhileOffline() {
        for (t in listOf(SyncTrigger.AppStart, SyncTrigger.Periodic, SyncTrigger.Foreground)) {
            assertEquals(t.name, ExistingWorkPolicy.KEEP, workPolicyFor(t))
        }
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, workPolicyFor(SyncTrigger.Manual))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, workPolicyFor(SyncTrigger.ReadingStopped))
    }

    @Test fun triggerParsing() {
        assertEquals(SyncTrigger.AppStart, SyncWorker.parseTrigger("AppStart"))
        assertEquals(SyncTrigger.Periodic, SyncWorker.parseTrigger(null))
        assertEquals(SyncTrigger.Periodic, SyncWorker.parseTrigger("bogus"))
    }
}
