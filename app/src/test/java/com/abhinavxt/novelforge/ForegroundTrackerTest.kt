package com.abhinavxt.novelforge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTrackerTest {
    @Test
    fun aRotationIsNotAForegroundReturn() {
        val t = ForegroundTracker()
        assertTrue("first start", t.onStarted())
        t.onStopped(changingConfigurations = true)
        assertFalse("the restarted activity after a rotation", t.onStarted())
        t.onStopped(changingConfigurations = false)
        assertTrue("a real return from the background", t.onStarted())
    }

    @Test
    fun movingBetweenScreens_isNotAForegroundReturn() {
        val t = ForegroundTracker()
        assertTrue(t.onStarted())
        assertFalse(t.onStarted())
        t.onStopped(false)
        t.onStopped(false)
        assertTrue(t.onStarted())
    }
}
