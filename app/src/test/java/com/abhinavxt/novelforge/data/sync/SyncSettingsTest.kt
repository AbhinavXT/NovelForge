package com.abhinavxt.novelforge.data.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class FakeSecretStore : SecretStore {
    val map = mutableMapOf<String, String>()
    override fun put(name: String, value: String?) {
        if (value == null) map.remove(name) else map[name] = value
    }
    override fun get(name: String): String? = map[name]
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncSettingsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun settings(secrets: SecretStore = FakeSecretStore()) = SyncSettings(context, secrets)

    @Test
    fun roundTrip() {
        val secrets = FakeSecretStore()
        val s = settings(secrets)
        s.clear()
        assertFalse(s.isConfigured)
        s.serverUrl = "https://dav.example.com/nf/"
        s.username = "me"
        s.password = "hunter2"
        s.pinnedCertSha256 = "AA:BB"
        s.enabled = true
        s.deviceName = "Pixel"
        s.lastSyncTime = 42L
        s.lastResult = "ok"
        s.saveKnownEtags(mapOf("a.json" to "\"e1\"", "b.json" to "W/\"e2\""))

        val t = settings(secrets)
        assertEquals("https://dav.example.com/nf/", t.serverUrl)
        assertEquals("me", t.username)
        assertEquals("hunter2", t.password)
        assertEquals("AA:BB", t.pinnedCertSha256)
        assertTrue(t.enabled)
        assertEquals("Pixel", t.deviceName)
        assertEquals(42L, t.lastSyncTime)
        assertEquals("ok", t.lastResult)
        assertEquals(mapOf("a.json" to "\"e1\"", "b.json" to "W/\"e2\""), t.knownEtags())
        assertTrue(t.isConfigured)
    }

    @Test
    fun clearWipesEverythingIncludingSecret() {
        val secrets = FakeSecretStore()
        val s = settings(secrets)
        s.serverUrl = "https://x/"
        s.username = "u"
        s.password = "p"
        s.enabled = true
        s.saveKnownEtags(mapOf("a" to "b"))
        s.clear()
        assertEquals("", s.serverUrl)
        assertEquals("", s.username)
        assertNull(s.password)
        assertFalse(s.enabled)
        assertTrue(s.knownEtags().isEmpty())
        assertFalse(s.isConfigured)
        assertTrue(secrets.map.isEmpty())
    }

    @Test
    fun passwordNeverInPlainPrefs() {
        val s = settings()
        s.clear()
        s.serverUrl = "https://x/"
        s.username = "u"
        s.password = "s3cret-password-value"
        val all = context.getSharedPreferences("sync", Context.MODE_PRIVATE).all
        assertFalse(all.values.any { it.toString().contains("s3cret-password-value") })
    }

    @Test
    fun deviceIdStableAcrossInstances() {
        val a = settings().deviceId
        val b = settings().deviceId
        assertTrue(a.isNotBlank())
        assertEquals(a, b)
    }

    @Test
    fun corruptEtagsGiveEmptyMap() {
        context.getSharedPreferences("sync", Context.MODE_PRIVATE).edit().putString("etags", "{nope").commit()
        assertTrue(settings().knownEtags().isEmpty())
    }

    /** A secret store that fails the test if anything is decrypted. */
    private class NoDecryptStore : SecretStore {
        val map = mutableMapOf<String, String>()
        override fun put(name: String, value: String?) { if (value == null) map.remove(name) else map[name] = value }
        override fun get(name: String): String? = throw AssertionError("decrypted on the caller's thread")
        override fun has(name: String): Boolean = name in map
    }

    @Test
    fun looksConfigured_checksPresenceWithoutDecrypting() {
        val store = NoDecryptStore()
        val s = SyncSettings(context, store)
        s.clear()
        assertFalse(s.looksConfigured)
        s.serverUrl = "https://x/"
        s.username = "u"
        assertFalse("no password stored", s.looksConfigured)
        store.map["password"] = "ciphertext"
        assertTrue(s.looksConfigured)
    }

    @Test
    fun restoreTokenIsClearedOnlyWhenItStillMatches_andPendingGuardIsRemovedByClear() {
        val s = settings()
        s.clear()
        assertFalse(s.restorePending)
        SyncSettings.markRestorePending(context)
        val first = s.restoreToken
        assertTrue(first != 0L)
        SyncSettings.markRestorePending(context)
        assertTrue(s.restoreToken != first)
        s.clearRestoreToken(first)
        assertTrue("a different token stays", s.restorePending)
        s.clearRestoreToken(s.restoreToken)
        assertFalse(s.restorePending)
        s.pendingRemoteRemovals = 4
        s.pendingLocalRemovals = 2
        assertEquals(4, settings().pendingRemoteRemovals)
        s.clear()
        assertEquals(0, s.pendingRemoteRemovals)
        assertEquals(0, s.pendingLocalRemovals)
    }

    @Test
    fun clear_keepsTheLastSyncTimeAndTheRestoreToken_andRemovesEverythingElse() {
        val s = settings()
        s.serverUrl = "https://x.example/dav/"
        s.username = "u"
        s.password = "p"
        s.pinnedCertSha256 = "AA"
        s.lastSyncTime = 12_345L
        s.lastResult = "Synced"
        s.lastUploadHash = "h"
        s.lastUploadTag = "t"
        s.saveKnownEtags(mapOf("a.json.gz" to "e:1"))
        s.pendingRemoteRemovals = 3
        s.pendingLocalRemovals = 4
        s.remoteAnswer = SyncSettings.ANSWER_KEEP
        s.allowLocalRemovals = true
        SyncSettings.markRestorePending(context)
        val token = s.restoreToken
        s.clear()
        assertEquals(12_345L, s.lastSyncTime)
        assertEquals(token, s.restoreToken)
        assertEquals("", s.serverUrl)
        assertEquals("", s.username)
        assertNull(s.password)
        assertNull(s.pinnedCertSha256)
        assertEquals("", s.lastResult)
        assertNull(s.lastUploadHash)
        assertNull(s.lastUploadTag)
        assertTrue(s.knownEtags().isEmpty())
        assertEquals(0, s.pendingRemoteRemovals)
        assertEquals(0, s.pendingLocalRemovals)
        assertEquals(SyncSettings.ANSWER_NONE, s.remoteAnswer)
        assertFalse(s.allowLocalRemovals)
        // A restore while disconnected can still set a (new) token.
        SyncSettings.markRestorePending(context)
        assertTrue(s.restoreToken != token)
    }

    @Test
    fun clear_onAFirstEverSetup_leavesNothingBehind() {
        val s = settings()
        s.clear()
        s.serverUrl = "https://x.example/dav/"
        s.clear()
        assertEquals(0L, s.lastSyncTime)
        assertFalse(s.restorePending)
    }
}
