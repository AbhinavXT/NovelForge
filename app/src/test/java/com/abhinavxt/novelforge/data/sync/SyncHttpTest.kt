package com.abhinavxt.novelforge.data.sync

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncHttpTest {

    private val base = "https://dav.example.com:8443/nf/".toHttpUrl()

    @Test
    fun authHeaderForConfiguredHostAndPort() {
        val h = SyncHttp.authHeaderFor("https://dav.example.com:8443/nf/x.json".toHttpUrl(), base, "u", "p")
        assertEquals(Credentials.basic("u", "p", Charsets.UTF_8), h)
    }

    @Test
    fun authHeaderUsesUtf8() {
        val h = SyncHttp.authHeaderFor(base, base, "ü", "pä")!!
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("ü:pä".toByteArray()), h)
    }

    @Test
    fun noAuthHeaderElsewhere() {
        assertNull(SyncHttp.authHeaderFor("https://evil.example.com:8443/".toHttpUrl(), base, "u", "p"))
        assertNull(SyncHttp.authHeaderFor("https://dav.example.com/nf/".toHttpUrl(), base, "u", "p"))
        assertNull(SyncHttp.authHeaderFor("https://dav.example.com:9000/nf/".toHttpUrl(), base, "u", "p"))
        assertNull(SyncHttp.authHeaderFor("http://dav.example.com:8443/nf/".toHttpUrl(), base, "u", "p"))
    }

    @Test
    fun noAuthHeaderWithoutConfig() {
        assertNull(SyncHttp.authHeaderFor(base, null, "u", "p"))
        assertNull(SyncHttp.authHeaderFor(base, base, "", "p"))
    }

    @Test
    fun fingerprintFormatting() {
        val bytes = ByteArray(32) { it.toByte() }
        val f = SyncHttp.formatFingerprint(bytes)
        assertTrue(f.startsWith("00:01:02:"))
        assertEquals(32 * 3 - 1, f.length)
        assertEquals(f, f.uppercase())
    }

    @Test
    fun pinMatchIgnoresCaseAndColons() {
        val f = SyncHttp.formatFingerprint(ByteArray(32) { (it * 7).toByte() })
        assertTrue(SyncHttp.pinMatches(f, f))
        assertTrue(SyncHttp.pinMatches(f.lowercase(), f))
        assertTrue(SyncHttp.pinMatches(f.replace(":", ""), f))
        assertTrue(SyncHttp.pinMatches(" $f ", f))
    }

    @Test
    fun pinMismatchOrMalformedNeverMatches() {
        val f = SyncHttp.formatFingerprint(ByteArray(32) { it.toByte() })
        val other = SyncHttp.formatFingerprint(ByteArray(32) { (it + 1).toByte() })
        assertFalse(SyncHttp.pinMatches(other, f))
        assertFalse(SyncHttp.pinMatches(null, f))
        assertFalse(SyncHttp.pinMatches("", f))
        assertFalse(SyncHttp.pinMatches("AA:BB", "AA:BB"))
        assertFalse(SyncHttp.pinMatches(f, "zz"))
    }
}
