package com.abhinavxt.novelforge.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class HybridClockTest {
    @Test
    fun tick_usesNowPlusOffsetShifted() {
        val c = HybridClock({ 1_000L }, { 50L })
        assertEquals(1_050L shl 16, c.tick())
    }

    @Test
    fun tick_isStrictlyIncreasingWithinOneMillisecond() {
        val c = HybridClock({ 1_000L }, { 0L })
        val a = c.tick()
        val b = c.tick()
        val d = c.tick()
        assertTrue(a < b && b < d)
        assertEquals(1_000L, HybridClock.physicalOf(a))
    }

    @Test
    fun tick_neverGoesBackwardsWhenWallClockDoes() {
        var now = 5_000L
        val c = HybridClock({ now }, { 0L })
        val a = c.tick()
        now = 1_000L
        assertTrue(c.tick() > a)
    }

    @Test
    fun tick_physicalArgumentWinsWhenAhead_andIsIgnoredWhenBehind() {
        val c = HybridClock({ 1_000L }, { 0L })
        assertEquals(9_000L shl 16, c.tick(9_000L))
        val c2 = HybridClock({ 1_000L }, { 0L })
        assertEquals(1_000L shl 16, c2.tick(10L))
    }

    @Test
    fun observe_advancesPastRemote_andIgnoresOlder() {
        val c = HybridClock({ 1_000L }, { 0L })
        val remote = 8_000L shl 16
        c.observe(remote)
        assertTrue(c.tick() > remote)
        val before = c.tick()
        c.observe(1L)
        assertTrue(c.tick() > before)
    }

    @Test
    fun counterOverflow_carriesIntoPhysicalPart_andStaysUnique() {
        val c = HybridClock({ 1_000L }, { 0L })
        val seen = HashSet<Long>()
        var prev = 0L
        repeat(70_000) {
            val t = c.tick()
            assertTrue(t > prev)
            assertTrue(seen.add(t))
            prev = t
        }
        // 70k values in one ms spill into the next physical milliseconds, never wrap.
        assertEquals(1_001L, HybridClock.physicalOf(prev))
    }

    @Test
    fun at_appliesOffsetAndDoesNotAdvanceClock() {
        val c = HybridClock({ 1_000L }, { 100L })
        assertEquals(600L shl 16, c.at(500L))
        assertEquals(1_100L shl 16, c.tick())
    }

    @Test
    fun tick_isThreadSafe() {
        val c = HybridClock({ 1_000L }, { 0L })
        val out = Collections.synchronizedList(ArrayList<Long>())
        val start = CountDownLatch(1)
        val threads = (1..8).map {
            thread {
                start.await()
                repeat(1_000) { out.add(c.tick()) }
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(8_000, out.toSet().size)
    }

    @Test
    fun hostileHlcNearLongMax_saturatesInsteadOfOverflowing() {
        val c = HybridClock({ 1_000L }, { 0L })
        c.observe(Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, c.tick())
        assertEquals(Long.MAX_VALUE, c.tick())
        c.observe(Long.MAX_VALUE - 1)
        assertEquals(Long.MAX_VALUE, c.tick())
        assertTrue(c.at(Long.MAX_VALUE) > 0)
        assertTrue(HybridClock({ Long.MAX_VALUE }, { Long.MAX_VALUE }).tick() > 0)
    }
}
