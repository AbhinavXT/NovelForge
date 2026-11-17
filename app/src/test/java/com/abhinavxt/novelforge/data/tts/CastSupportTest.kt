package com.abhinavxt.novelforge.data.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class CastSupportTest {

    @Test
    fun lruKeepsTheMostRecentlyUsedAndReleasesTheRest() {
        val released = mutableListOf<String>()
        val lru = ModelLru<String>(2) { id, _ -> released += id }
        lru.put("a", "A")
        lru.put("b", "B")
        lru.get("a")            // a is now more recent than b
        lru.put("c", "C")       // b is the least recently used
        assertEquals(listOf("b"), released)
        assertNull(lru.get("b"))
        assertEquals(listOf("a", "c"), lru.ids())
        lru.put("d", "D")       // a is next
        assertEquals(listOf("b", "a"), released)
    }

    @Test
    fun lruClearReleasesEverythingAndReplacingReleasesTheOldValue() {
        val released = mutableListOf<String>()
        val lru = ModelLru<String>(2) { id, v -> released += "$id=$v" }
        lru.put("a", "1")
        lru.put("a", "2")
        assertEquals(listOf("a=1"), released)
        lru.put("b", "3")
        lru.clear()
        assertEquals(listOf("a=1", "a=2", "b=3"), released)
        assertEquals(emptyList<String>(), lru.ids())
    }

    @Test
    fun resamplingChangesLengthByTheRateRatio() {
        val ramp = FloatArray(160) { it / 160f }
        val up = Resample.linear(ramp, 16000, 22050)
        assertEquals(220, up.size)
        assertEquals(0f, up.first(), 1e-6f)
        assertTrue((1 until up.size).all { up[it] >= up[it - 1] })   // still a rising ramp
        assertEquals(80, Resample.linear(ramp, 22050, 11025).size)
    }

    @Test
    fun resamplingToTheSameRateOrOfNothingIsUntouched() {
        val x = floatArrayOf(0.1f, 0.2f)
        assertArrayEquals(x, Resample.linear(x, 22050, 22050), 0f)
        assertEquals(0, Resample.linear(FloatArray(0), 16000, 22050).size)
    }

    @Test
    fun pcm16BytesBecomeFloats() {
        val bytes = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte())   // 16384, -16384
        val f = Resample.pcm16ToFloat(bytes)
        assertEquals(0.5f, f[0], 1e-4f)
        assertEquals(-0.5f, f[1], 1e-4f)
    }

    @Test fun failedLoadsAreRememberedUntilCleared() {
        val f = FailedLoads()
        assertFalse(f.hasFailed("m"))
        f.markFailed("m")
        assertTrue(f.hasFailed("m"))
        assertFalse(f.hasFailed("other"))
        f.clear()
        assertFalse(f.hasFailed("m"))
    }

    @Test fun takeAllEmptiesWithoutReleasing() {
        val released = mutableListOf<String>()
        val lru = ModelLru<String>(2) { id, _ -> released += id }
        lru.put("a", "1"); lru.put("b", "2")
        assertEquals(listOf("1", "2"), lru.takeAll())
        assertEquals(emptyList<String>(), lru.ids())
        assertTrue(released.isEmpty())
    }
}
