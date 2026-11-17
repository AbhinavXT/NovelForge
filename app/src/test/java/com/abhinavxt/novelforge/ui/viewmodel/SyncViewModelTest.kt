package com.abhinavxt.novelforge.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.novelforge.data.sync.FakeSecretStore
import com.abhinavxt.novelforge.data.sync.SyncOutcome
import com.abhinavxt.novelforge.data.sync.SyncSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeRunner : SyncRunner {
    var next: SyncOutcome = SyncOutcome.Success(0, 0, 0, 0, false, emptyList(), false)
    var gate: CompletableDeferred<Unit>? = null
    val calls = mutableListOf<Boolean>()      // allowRemoteDeletions
    val locals = mutableListOf<Boolean>()     // allowLocalDeletions
    val keeps = mutableListOf<Boolean>()
    var settingsChanged = 0

    override suspend fun syncNow(
        allowRemoteDeletions: Boolean,
        allowLocalDeletions: Boolean,
        keepLocalBooks: Boolean,
    ): SyncOutcome {
        calls += allowRemoteDeletions
        locals += allowLocalDeletions
        keeps += keepLocalBooks
        gate?.await()
        return next
    }

    override fun onSettingsChanged() { settingsChanged++ }
}

private class FakeProber : ConnectionProber {
    var result: ProbeResult = ProbeResult.Ok
    var fingerprint: String? = "AA:BB"
    val pins = mutableListOf<String?>()

    override suspend fun probe(url: String, user: String, password: String, pinSha256: String?): ProbeResult {
        pins += pinSha256
        // After the certificate is trusted, the probe succeeds.
        return if (pinSha256 != null) ProbeResult.Ok else result
    }

    override suspend fun fetchCertificate(url: String): String? = fingerprint
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val secrets = FakeSecretStore()
    private val settings = SyncSettings(context, secrets)
    private val runner = FakeRunner()
    private val prober = FakeProber()

    private fun vm(cleartext: Boolean = false) = SyncViewModel(
        settings, runner, prober, cleartext, CoroutineScope(Dispatchers.Unconfined)
    )

    private fun SyncViewModel.fill(url: String = "https://cloud.example.com/dav/", pass: String = "s3cret") {
        onServerUrlChange(url)
        onUsernameChange("anna")
        onPasswordChange(pass)
    }

    @Test
    fun `http address is rejected with a reason`() {
        val v = vm()
        v.fill(url = "http://cloud.example.com/dav/")
        v.testConnection()
        assertEquals("Address must start with https://", v.state.value.urlError)
        v.save()
        assertFalse(settings.isConfigured)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `empty address and local http in debug`() {
        val v = vm()
        v.save()
        assertEquals("Enter a server address", v.state.value.urlError)
        val d = vm(cleartext = true)
        d.fill(url = "http://127.0.0.1:8080/")
        d.save()
        assertNull(d.state.value.urlError)
        assertTrue(settings.isConfigured)
    }

    @Test
    fun `save stores settings, hides the password and syncs`() {
        val v = vm()
        v.fill()
        v.save()
        assertEquals("https://cloud.example.com/dav/", settings.serverUrl)
        assertEquals("anna", settings.username)
        assertEquals("s3cret", settings.password)
        assertTrue(settings.enabled)
        val s = v.state.value
        assertEquals("", s.passwordInput)
        assertTrue(s.passwordSaved)
        assertFalse(s.toString().contains("s3cret"))
        assertEquals(listOf(false), runner.calls)
        assertEquals(1, runner.settingsChanged)
        assertFalse(s.busy)
    }

    @Test
    fun `saving again with an empty password keeps the stored one`() {
        val v = vm()
        v.fill()
        v.save()
        v.onUsernameChange("bea")
        v.save()
        assertEquals("s3cret", settings.password)
        assertEquals("bea", settings.username)
    }

    @Test
    fun `save needs a password the first time`() {
        val v = vm()
        v.fill(pass = "")
        v.save()
        assertFalse(settings.isConfigured)
        assertTrue(v.state.value.message!!.isError)
    }

    @Test
    fun `disconnect clears settings and the secret and says nothing is deleted`() {
        val v = vm()
        v.fill()
        v.save()
        v.disconnect()
        assertEquals("", settings.serverUrl)
        assertNull(settings.password)
        assertFalse(settings.enabled)
        val s = v.state.value
        assertFalse(s.connected)
        assertFalse(s.passwordSaved)
        assertTrue(s.message!!.text.contains("Nothing was deleted on the server"))
    }

    @Test
    fun `each outcome maps to a message`() {
        val ok = SyncViewModel.describeOutcome(SyncOutcome.Success(2, 5, 0, 1, false, emptyList(), true))
        assertFalse(ok.isError)
        assertTrue(ok.text.contains("2 changes"))
        assertTrue(SyncViewModel.describeOutcome(SyncOutcome.AuthFailed).text.contains("username or password"))
        assertTrue(SyncViewModel.describeOutcome(SyncOutcome.ServerUnreachable("x")).isError)
        assertTrue(SyncViewModel.describeOutcome(SyncOutcome.ServerError(500)).text.contains("500"))
        assertTrue(SyncViewModel.describeOutcome(SyncOutcome.ServerFormatTooNew(9)).text.contains("Update NovelForge"))
        assertFalse(SyncViewModel.describeOutcome(SyncOutcome.Busy).isError)
        assertEquals("boom", SyncViewModel.describeOutcome(SyncOutcome.Failed("boom")).text)
        assertTrue(SyncViewModel.describeOutcome(SyncOutcome.NotConfigured).isError)
    }

    @Test
    fun `success outcome sets skew and skipped device flags`() {
        runner.next = SyncOutcome.Success(0, 1, 0, 2, true, listOf("Tablet"), true)
        val v = vm()
        v.fill()
        v.save()
        assertTrue(v.state.value.skewWarning)
        assertEquals(listOf("Tablet"), v.state.value.skippedDevices)
    }

    @Test
    fun `guard tripped shows the notice, remove here syncs with mass delete allowed`() {
        settings.pendingRemoteRemovals = 7 // what the engine stores
        runner.next = SyncOutcome.GuardTripped(7, fromRemote = true)
        val v = vm()
        v.fill()
        v.save()
        assertEquals(7, v.state.value.pendingRemoteRemovals)
        runner.next = SyncOutcome.Success(7, 0, 0, 1, false, emptyList(), true)
        settings.clearPendingGuard() // a successful sync clears it
        v.removeHere()
        assertEquals(listOf(false, true), runner.calls)
        assertEquals("remove here answers only the remote side", listOf(false, false), runner.locals)
        assertEquals(listOf(false, false), runner.keeps)
        assertEquals(0, v.state.value.pendingRemoteRemovals)
    }

    @Test
    fun `keep them syncs with keepLocalBooks and no mass delete`() {
        settings.pendingRemoteRemovals = 7
        val v = vm()
        v.fill()
        v.save()
        runner.calls.clear(); runner.locals.clear(); runner.keeps.clear()
        v.keepThem()
        assertEquals(listOf(false), runner.calls)
        assertEquals(listOf(false), runner.locals)
        assertEquals(listOf(true), runner.keeps)
    }

    @Test
    fun `pending guard stored by a background sync is shown when the section opens`() {
        settings.serverUrl = "https://cloud.example.com/dav/"
        settings.username = "anna"
        settings.password = "x"
        settings.pendingRemoteRemovals = 4
        settings.pendingLocalRemovals = 9
        val v = vm()
        assertEquals(4, v.state.value.pendingRemoteRemovals)
        assertEquals(9, v.state.value.pendingLocalRemovals)
        settings.clearPendingGuard()
        v.refreshStatus()
        assertEquals(0, v.state.value.pendingRemoteRemovals)
        assertEquals(0, v.state.value.pendingLocalRemovals)
    }

    @Test
    fun `remove everywhere answers the local notice, nothing pending means nothing syncs`() {
        val v = vm()
        v.fill()
        v.save()
        runner.calls.clear(); runner.locals.clear()
        v.removeEverywhere()
        v.removeHere()
        v.keepThem()
        assertTrue(runner.calls.isEmpty())
        settings.pendingLocalRemovals = 6
        v.refreshStatus()
        v.removeEverywhere()
        assertEquals("remove everywhere answers only the local side", listOf(false), runner.calls)
        assertEquals(listOf(true), runner.locals)
    }

    @Test
    fun `an answered side is not offered again while the other side waits`() {
        settings.serverUrl = "https://cloud.example.com/dav/"
        settings.username = "anna"
        settings.password = "x"
        settings.pendingRemoteRemovals = 4
        settings.pendingLocalRemovals = 9
        settings.remoteAnswer = SyncSettings.ANSWER_KEEP
        val v = vm()
        assertEquals(0, v.state.value.pendingRemoteRemovals)
        assertEquals(9, v.state.value.pendingLocalRemovals)
    }

    @Test
    fun `disconnect clears the pending guard`() {
        settings.pendingRemoteRemovals = 3
        val v = vm()
        v.fill()
        v.save()
        v.disconnect()
        assertEquals(0, v.state.value.pendingRemoteRemovals)
        assertEquals(0, settings.pendingRemoteRemovals)
    }

    @Test
    fun `busy flag blocks a second run`() {
        val v = vm()
        v.fill()
        v.save()
        runner.calls.clear()
        runner.gate = CompletableDeferred()
        v.syncNow()
        assertTrue(v.state.value.busy)
        v.syncNow()
        v.save()
        assertEquals(1, runner.calls.size)
        runner.gate!!.complete(Unit)
        assertFalse(v.state.value.busy)
    }

    @Test
    fun `test connection maps probe results`() {
        val v = vm()
        v.fill()
        prober.result = ProbeResult.BadLogin
        v.testConnection()
        assertTrue(v.state.value.message!!.text.contains("username or password"))
        prober.result = ProbeResult.NotWebDav
        v.testConnection()
        assertTrue(v.state.value.message!!.text.contains("not a WebDAV folder"))
        prober.result = ProbeResult.Unreachable
        v.testConnection()
        assertTrue(v.state.value.message!!.text.contains("Could not reach"))
        prober.result = ProbeResult.Ok
        v.testConnection()
        assertFalse(v.state.value.message!!.isError)
        assertFalse(v.state.value.busy)
        assertFalse(settings.isConfigured) // testing stores nothing
    }

    @Test
    fun `untrusted certificate is offered and pinned only after confirming`() {
        val v = vm()
        v.fill()
        prober.result = ProbeResult.CertNotTrusted
        v.testConnection()
        assertEquals("AA:BB", v.state.value.certificatePrompt)
        assertNull(v.state.value.pinnedFingerprint)
        assertNull(settings.pinnedCertSha256)
        v.trustCertificate()
        assertEquals("AA:BB", v.state.value.pinnedFingerprint)
        assertNull(v.state.value.certificatePrompt)
        assertEquals("AA:BB", prober.pins.last())
        assertFalse(v.state.value.message!!.isError)
        v.save()
        assertNotNull(settings.pinnedCertSha256)
    }

    @Test
    fun `declining the certificate pins nothing`() {
        val v = vm()
        v.fill()
        prober.result = ProbeResult.CertNotTrusted
        v.testConnection()
        v.declineCertificate()
        assertNull(v.state.value.certificatePrompt)
        assertNull(v.state.value.pinnedFingerprint)
    }
}
