package com.abhinavxt.novelforge.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.abhinavxt.novelforge.BuildConfig
import com.abhinavxt.novelforge.data.sync.KeystoreSecretStore
import com.abhinavxt.novelforge.data.sync.ServerUrlResult
import com.abhinavxt.novelforge.data.sync.SyncEngine
import com.abhinavxt.novelforge.data.sync.SyncHttp
import com.abhinavxt.novelforge.data.sync.SyncOutcome
import com.abhinavxt.novelforge.data.sync.SyncSettings
import com.abhinavxt.novelforge.data.sync.SyncTrigger
import com.abhinavxt.novelforge.data.sync.validateServerUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Runs one sync. The default calls the engine directly; the lead wires [onSettingsChanged] to the scheduler. */
interface SyncRunner {
    suspend fun syncNow(
        allowRemoteDeletions: Boolean = false,
        allowLocalDeletions: Boolean = false,
        keepLocalBooks: Boolean = false,
    ): SyncOutcome
    fun onSettingsChanged()
}

class DefaultSyncRunner(private val context: Context) : SyncRunner {
    override suspend fun syncNow(
        allowRemoteDeletions: Boolean,
        allowLocalDeletions: Boolean,
        keepLocalBooks: Boolean,
    ): SyncOutcome = withContext(Dispatchers.IO) {
        SyncEngine.create(context)?.sync(SyncTrigger.Manual, allowRemoteDeletions, allowLocalDeletions, keepLocalBooks) ?: SyncOutcome.NotConfigured
    }

    override fun onSettingsChanged() =
        com.abhinavxt.novelforge.worker.SyncScheduler.onSettingsChanged(context)
}

sealed class ProbeResult {
    object Ok : ProbeResult()
    object BadLogin : ProbeResult()
    object NotWebDav : ProbeResult()
    object Unreachable : ProbeResult()
    object CertNotTrusted : ProbeResult()
    data class ServerError(val status: Int) : ProbeResult()
}

/** One harmless request with the entered credentials. Nothing is stored. */
interface ConnectionProber {
    suspend fun probe(url: String, user: String, password: String, pinSha256: String?): ProbeResult
    suspend fun fetchCertificate(url: String): String?
}

class WebDavProber : ConnectionProber {
    override suspend fun probe(url: String, user: String, password: String, pinSha256: String?): ProbeResult =
        withContext(Dispatchers.IO) {
            val base = url.toHttpUrl()
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val req = chain.request()
                    val auth = SyncHttp.authHeaderFor(req.url, base, user, password)
                    chain.proceed(if (auth != null) req.newBuilder().header("Authorization", auth).build() else req)
                }
            val pin = pinSha256?.let(SyncHttp::normalizeFingerprint)
            if (pin != null) {
                val tm = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                        throw CertificateException("client certificates not supported")

                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                        val leaf = chain.firstOrNull() ?: throw CertificateException("empty chain")
                        if (!SyncHttp.pinMatches(pin, SyncHttp.fingerprintOf(leaf))) {
                            throw CertificateException("certificate does not match")
                        }
                    }

                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                val ctx = SSLContext.getInstance("TLS")
                ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
                builder.sslSocketFactory(ctx.socketFactory, tm)
                builder.hostnameVerifier(HostnameVerifier { host, _ -> host == base.host })
            }
            val body = "<?xml version=\"1.0\"?><propfind xmlns=\"DAV:\"><prop><resourcetype/></prop></propfind>"
                .toRequestBody("application/xml".toMediaType())
            val req = Request.Builder().url(base).method("PROPFIND", body).header("Depth", "0").build()
            try {
                builder.build().newCall(req).execute().use { resp ->
                    when (resp.code) {
                        207 -> ProbeResult.Ok
                        401, 403 -> ProbeResult.BadLogin
                        200, 301, 302, 303, 307, 308, 404, 405, 501 -> ProbeResult.NotWebDav
                        else -> ProbeResult.ServerError(resp.code)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (isCertificateProblem(e)) ProbeResult.CertNotTrusted else ProbeResult.Unreachable
            }
        }

    override suspend fun fetchCertificate(url: String): String? =
        withContext(Dispatchers.IO) { SyncHttp.fetchCertificateSha256(url.toHttpUrl()) }

    private fun isCertificateProblem(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 6) {
            if (t is SSLHandshakeException || t is SSLPeerUnverifiedException || t is CertificateException) return true
            t = t.cause
            depth++
        }
        return false
    }
}

data class SyncMessage(val text: String, val isError: Boolean)

data class SyncUiState(
    val serverUrl: String = "",
    val username: String = "",
    /** What the person is typing now. Cleared after a save; the stored password is never put here. */
    val passwordInput: String = "",
    val passwordSaved: Boolean = false,
    val deviceName: String = "",
    val enabled: Boolean = false,
    /** SHA-256 of the trusted server certificate, colon-separated, or null. */
    val pinnedFingerprint: String? = null,
    val lastSyncTime: Long = 0L,
    val lastResult: String = "",
    val skewWarning: Boolean = false,
    val skippedDevices: List<String> = emptyList(),
    val busy: Boolean = false,
    val urlError: String? = null,
    val message: SyncMessage? = null,
    /** Set while the person is asked whether to trust this certificate fingerprint. */
    val certificatePrompt: String? = null,
    /** Sync is paused: other devices removed this many books (0 = no such pause). Stored, so it survives restarts. */
    val pendingRemoteRemovals: Int = 0,
    /** Sync is paused: this device no longer has this many synced books (0 = no such pause). */
    val pendingLocalRemovals: Int = 0,
    /** True once settings are saved on this device (a server, a user and a password exist). */
    val connected: Boolean = false,
)

class SyncViewModel(
    private val settings: SyncSettings,
    private val runner: SyncRunner,
    private val prober: ConnectionProber,
    private val allowLocalCleartext: Boolean = BuildConfig.DEBUG,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    // A side the person already answered is not asked again; the answer is applied by the next sync.
    private fun unansweredRemote() = if (settings.remoteAnswer != SyncSettings.ANSWER_NONE) 0 else settings.pendingRemoteRemovals
    private fun unansweredLocal() = if (settings.allowLocalRemovals) 0 else settings.pendingLocalRemovals

    private fun load(): SyncUiState = SyncUiState(
        serverUrl = settings.serverUrl,
        username = settings.username,
        passwordSaved = !settings.password.isNullOrEmpty(),
        deviceName = settings.deviceName,
        enabled = settings.enabled,
        pinnedFingerprint = settings.pinnedCertSha256?.let(SyncHttp::normalizeFingerprint)
            ?.chunked(2)?.joinToString(":"),
        lastSyncTime = settings.lastSyncTime,
        lastResult = settings.lastResult,
        skewWarning = settings.lastSkewWarning,
        skippedDevices = settings.lastSkippedDevices,
        pendingRemoteRemovals = unansweredRemote(),
        pendingLocalRemovals = unansweredLocal(),
        connected = settings.isConfigured,
    )

    /**
     * Fields that sync outcomes change, re-read from settings. Also called when the section is opened:
     * a background sync may have paused sync while nobody was looking.
     */
    fun refreshStatus() = _state.update {
        it.copy(
            lastSyncTime = settings.lastSyncTime,
            lastResult = settings.lastResult,
            skewWarning = settings.lastSkewWarning,
            skippedDevices = settings.lastSkippedDevices,
            pendingRemoteRemovals = unansweredRemote(),
            pendingLocalRemovals = unansweredLocal(),
        )
    }

    fun onServerUrlChange(v: String) = _state.update { it.copy(serverUrl = v, urlError = null, message = null) }
    fun onUsernameChange(v: String) = _state.update { it.copy(username = v, message = null) }
    fun onPasswordChange(v: String) = _state.update { it.copy(passwordInput = v, message = null) }
    fun onDeviceNameChange(v: String) = _state.update { it.copy(deviceName = v) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun checkedUrl(): String? {
        return when (val r = validateServerUrl(_state.value.serverUrl, allowLocalCleartext)) {
            is ServerUrlResult.Valid -> r.url
            is ServerUrlResult.Invalid -> {
                _state.update { it.copy(urlError = r.reason, message = null) }
                null
            }
        }
    }

    fun testConnection() {
        val s = _state.value
        if (s.busy) return
        val url = checkedUrl() ?: return
        val password = s.passwordInput.ifEmpty { settings.password.orEmpty() }
        if (s.username.isBlank() || password.isEmpty()) {
            _state.update { it.copy(message = SyncMessage("Enter the username and password for the server.", true)) }
            return
        }
        _state.update { it.copy(busy = true, message = null, certificatePrompt = null) }
        scope.launch {
            val result = try {
                prober.probe(url, s.username.trim(), password, _state.value.pinnedFingerprint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProbeResult.Unreachable
            }
            if (result is ProbeResult.CertNotTrusted && _state.value.pinnedFingerprint == null) {
                val fp = try { prober.fetchCertificate(url) } catch (e: Exception) { null }
                if (fp != null) {
                    _state.update {
                        it.copy(
                            busy = false,
                            certificatePrompt = fp,
                            message = SyncMessage(describeProbe(result, false), true),
                        )
                    }
                    return@launch
                }
            }
            val pinned = _state.value.pinnedFingerprint != null
            _state.update {
                it.copy(busy = false, message = SyncMessage(describeProbe(result, pinned), result != ProbeResult.Ok))
            }
        }
    }

    /** The person confirmed the fingerprint shown; remember it and test again. */
    fun trustCertificate() {
        val fp = _state.value.certificatePrompt ?: return
        _state.update { it.copy(pinnedFingerprint = fp, certificatePrompt = null, message = null) }
        testConnection()
    }

    fun declineCertificate() = _state.update { it.copy(certificatePrompt = null) }

    fun forgetCertificate() {
        settings.pinnedCertSha256 = null
        _state.update {
            it.copy(pinnedFingerprint = null, message = SyncMessage("This device no longer trusts that certificate.", false))
        }
    }

    fun save() {
        val s = _state.value
        if (s.busy) return
        val url = checkedUrl() ?: return
        val password = s.passwordInput.ifEmpty { settings.password.orEmpty() }
        if (s.username.isBlank() || password.isEmpty()) {
            _state.update { it.copy(message = SyncMessage("Enter the username and password for the server.", true)) }
            return
        }
        settings.serverUrl = url
        settings.username = s.username.trim()
        if (s.passwordInput.isNotEmpty()) settings.password = s.passwordInput
        settings.pinnedCertSha256 = s.pinnedFingerprint
        if (s.deviceName.isNotBlank()) settings.deviceName = s.deviceName.trim()
        settings.enabled = true
        _state.update {
            it.copy(
                serverUrl = url,
                username = s.username.trim(),
                passwordInput = "",
                passwordSaved = true,
                deviceName = settings.deviceName,
                enabled = true,
                connected = true,
                urlError = null,
            )
        }
        runner.onSettingsChanged()
        runSync()
    }

    fun syncNow() {
        if (_state.value.busy) return
        runSync()
    }

    fun setEnabled(on: Boolean) {
        if (!settings.isConfigured) return
        settings.enabled = on
        _state.update { it.copy(enabled = on) }
        runner.onSettingsChanged()
    }

    /** "Remove here": follow the other devices' removals. Answers only the remote side. */
    fun removeHere() {
        if (_state.value.pendingRemoteRemovals == 0 || _state.value.busy) return
        runSync(allowRemoteDeletions = true)
    }

    /** "Keep them": the books stay and are announced again to the other devices. */
    fun keepThem() {
        if (_state.value.pendingRemoteRemovals == 0 || _state.value.busy) return
        runSync(keepLocalBooks = true)
    }

    /** "Remove everywhere": this device's missing books are removed from the other devices too. */
    fun removeEverywhere() {
        if (_state.value.pendingLocalRemovals == 0 || _state.value.busy) return
        runSync(allowLocalDeletions = true)
    }

    fun disconnect() {
        settings.enabled = false
        settings.clear()
        _state.value = load().copy(
            message = SyncMessage(
                "Sync is off and the saved login is removed from this device. Nothing was deleted on the server or on your other devices.",
                false,
            ),
        )
        runner.onSettingsChanged()
    }

    private fun runSync(
        allowRemoteDeletions: Boolean = false,
        allowLocalDeletions: Boolean = false,
        keepLocalBooks: Boolean = false,
    ) {
        _state.update { it.copy(busy = true, message = null) }
        scope.launch {
            val outcome = try {
                runner.syncNow(allowRemoteDeletions, allowLocalDeletions, keepLocalBooks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncOutcome.Failed("Sync failed")
            }
            refreshStatus()
            _state.update {
                it.copy(
                    busy = false,
                    message = describeOutcome(outcome),
                    // The engine stores the counts; this only covers a runner that did not.
                    pendingRemoteRemovals = if (outcome is SyncOutcome.GuardTripped && outcome.fromRemote && it.pendingRemoteRemovals == 0)
                        outcome.count else it.pendingRemoteRemovals,
                    pendingLocalRemovals = if (outcome is SyncOutcome.GuardTripped && !outcome.fromRemote && it.pendingLocalRemovals == 0)
                        outcome.count else it.pendingLocalRemovals,
                    skewWarning = (outcome as? SyncOutcome.Success)?.skewWarning ?: it.skewWarning,
                    skippedDevices = (outcome as? SyncOutcome.Success)?.skippedDevices ?: it.skippedDevices,
                )
            }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SyncViewModel(SyncSettings(app, KeystoreSecretStore(app)), DefaultSyncRunner(app), WebDavProber()) as T
    }

    companion object {
        fun describeProbe(r: ProbeResult, pinned: Boolean): String = when (r) {
            ProbeResult.Ok -> "Connected. The server accepted the login."
            ProbeResult.BadLogin -> "The server rejected the username or password."
            ProbeResult.NotWebDav ->
                "That address is not a WebDAV folder. Check the path; for Nextcloud it ends in remote.php/dav/files/YOUR-NAME/."
            ProbeResult.Unreachable -> "Could not reach the server. Check the address and your connection."
            ProbeResult.CertNotTrusted ->
                if (pinned) "The server's certificate is not the one you trusted. It may have been renewed."
                else "This phone does not trust the server's certificate."
            is ProbeResult.ServerError -> "The server answered with an error (${r.status})."
        }

        fun describeOutcome(o: SyncOutcome): SyncMessage = when (o) {
            is SyncOutcome.Success -> SyncMessage(
                if (o.applied == 0) "Synced. Nothing new from your other devices."
                else "Synced. ${o.applied} ${if (o.applied == 1) "change" else "changes"} from your other devices.",
                false,
            )
            SyncOutcome.NotConfigured -> SyncMessage("Sync is not set up on this device.", true)
            SyncOutcome.AuthFailed -> SyncMessage("The server rejected the username or password.", true)
            is SyncOutcome.ServerUnreachable ->
                SyncMessage("Could not reach the server. Check the address and your connection.", true)
            is SyncOutcome.ServerError -> SyncMessage("The server answered with an error (${o.status}).", true)
            is SyncOutcome.GuardTripped -> SyncMessage("Sync is paused. Nothing was changed.", true)
            is SyncOutcome.ServerFormatTooNew ->
                SyncMessage("This server folder was written by a newer NovelForge. Update NovelForge to sync.", true)
            SyncOutcome.Busy -> SyncMessage("Another sync is already running.", false)
            is SyncOutcome.Failed -> SyncMessage(o.message, true)
        }
    }
}
