package com.abhinavxt.novelforge.data.sync

import android.content.Context
import android.os.Build
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.util.UUID

sealed class ServerUrlResult {
    data class Valid(val url: String) : ServerUrlResult()
    data class Invalid(val reason: String) : ServerUrlResult()
}

/**
 * Accepts https, or http to exactly localhost / 127.0.0.1 when [allowLocalCleartext].
 * Rejects embedded credentials, query, fragment and a missing host. The result always
 * ends in a slash.
 */
fun validateServerUrl(url: String, allowLocalCleartext: Boolean): ServerUrlResult {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return ServerUrlResult.Invalid("Enter a server address")
    val parsed = trimmed.toHttpUrlOrNull() ?: return ServerUrlResult.Invalid("Not a valid address")
    if (parsed.host.isEmpty()) return ServerUrlResult.Invalid("Address has no host")
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
        return ServerUrlResult.Invalid("Do not put a username or password in the address")
    }
    if (parsed.query != null || parsed.fragment != null) {
        return ServerUrlResult.Invalid("Address must not have a query or fragment")
    }
    if (!parsed.isHttps) {
        val local = parsed.host == "localhost" || parsed.host == "127.0.0.1"
        if (!(allowLocalCleartext && local)) {
            return ServerUrlResult.Invalid("Address must start with https://")
        }
    }
    val s = parsed.toString()
    return ServerUrlResult.Valid(if (s.endsWith("/")) s else "$s/")
}

class SyncSettings(private val context: Context, private val secrets: SecretStore) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(K_URL, "") ?: ""
        set(v) = prefs.edit().putString(K_URL, v).apply()

    var username: String
        get() = prefs.getString(K_USER, "") ?: ""
        set(v) = prefs.edit().putString(K_USER, v).apply()

    var password: String?
        get() = secrets.get(K_PASSWORD)
        set(v) = secrets.put(K_PASSWORD, v)

    /** Hex, colons optional; null when no certificate is pinned. */
    var pinnedCertSha256: String?
        get() = prefs.getString(K_PIN, null)
        set(v) = prefs.edit().apply { if (v.isNullOrBlank()) remove(K_PIN) else putString(K_PIN, v) }.apply()

    var enabled: Boolean
        get() = prefs.getBoolean(K_ENABLED, false)
        set(v) = prefs.edit().putBoolean(K_ENABLED, v).apply()

    var deviceName: String
        get() = prefs.getString(K_DEVICE_NAME, null) ?: Build.MODEL.orEmpty().ifBlank { "Android" }
        set(v) = prefs.edit().putString(K_DEVICE_NAME, v).apply()

    var lastSyncTime: Long
        get() = prefs.getLong(K_LAST_TIME, 0L)
        set(v) = prefs.edit().putLong(K_LAST_TIME, v).apply()

    var lastResult: String
        get() = prefs.getString(K_LAST_RESULT, "") ?: ""
        set(v) = prefs.edit().putString(K_LAST_RESULT, v).apply()

    /** Hash of the records in the file we last uploaded successfully; lets an unchanged sync skip the upload. */
    var lastUploadHash: String?
        get() = prefs.getString(K_UPLOAD_HASH, null)
        set(v) = prefs.edit().apply { if (v == null) remove(K_UPLOAD_HASH) else putString(K_UPLOAD_HASH, v) }.apply()

    /** What the devices listing showed for our own file right after the last upload (ETag, or date and size). */
    var lastUploadTag: String?
        get() = prefs.getString(K_UPLOAD_TAG, null)
        set(v) = prefs.edit().apply { if (v == null) remove(K_UPLOAD_TAG) else putString(K_UPLOAD_TAG, v) }.apply()

    /**
     * Token of the latest app backup restore not yet merged (0 = none); see [markRestorePending]. The
     * engine clears it with [clearRestoreToken] only if it still holds the value the merge ran with.
     */
    val restoreToken: Long
        get() = prefs.getLong(K_RESTORE_TOKEN, 0L)

    val restorePending: Boolean get() = restoreToken != 0L

    @Synchronized
    fun clearRestoreToken(expected: Long) {
        if (expected != 0L && restoreToken == expected) prefs.edit().remove(K_RESTORE_TOKEN).apply()
    }

    /** Books other devices removed that sync would delete here; 0 = no pending prompt. Survives restarts. */
    var pendingRemoteRemovals: Int
        get() = prefs.getInt(K_PENDING_REMOTE, 0)
        set(v) = prefs.edit().putInt(K_PENDING_REMOTE, v).apply()

    /** Books this device no longer has that sync would remove from the other devices; 0 = none. */
    var pendingLocalRemovals: Int
        get() = prefs.getInt(K_PENDING_LOCAL, 0)
        set(v) = prefs.edit().putInt(K_PENDING_LOCAL, v).apply()

    /**
     * The person's answer for the remote side ([ANSWER_NONE], [ANSWER_ALLOW] = delete here, [ANSWER_KEEP]).
     * Remembered until a sync commits, so a background run cannot undo it or ask again.
     */
    var remoteAnswer: Int
        get() = prefs.getInt(K_ANSWER_REMOTE, ANSWER_NONE)
        set(v) = prefs.edit().apply { if (v == ANSWER_NONE) remove(K_ANSWER_REMOTE) else putInt(K_ANSWER_REMOTE, v) }.apply()

    /** The person's answer for the local side: remove this device's missing books everywhere. */
    var allowLocalRemovals: Boolean
        get() = prefs.getBoolean(K_ANSWER_LOCAL, false)
        set(v) = prefs.edit().apply { if (v) putBoolean(K_ANSWER_LOCAL, true) else remove(K_ANSWER_LOCAL) }.apply()

    /** A sync committed: both sides were dealt with, the pending counts and the answers go. */
    fun clearPendingGuard() {
        prefs.edit().remove(K_PENDING_REMOTE).remove(K_PENDING_LOCAL).remove(K_ANSWER_REMOTE).remove(K_ANSWER_LOCAL).apply()
    }

    /** A sync committed the remote side's answer while the local side stays pending. */
    fun clearRemoteSide() {
        prefs.edit().remove(K_PENDING_REMOTE).remove(K_ANSWER_REMOTE).apply()
    }

    /** The last successful sync saw a device whose clock differs from the server's by more than 2 minutes. */
    var lastSkewWarning: Boolean
        get() = prefs.getBoolean(K_SKEW, false)
        set(v) = prefs.edit().putBoolean(K_SKEW, v).apply()

    /** Names of devices whose file the last successful sync could not read (newer format). */
    var lastSkippedDevices: List<String>
        get() = (prefs.getString(K_SKIPPED, "") ?: "").split('\n').filter { it.isNotEmpty() }
        set(v) = prefs.edit().putString(K_SKIPPED, v.joinToString("\n")).apply()

    /** Number of other devices that had a file at the last successful sync. */
    var lastDevicesSeen: Int
        get() = prefs.getInt(K_DEVICES, 0)
        set(v) = prefs.edit().putInt(K_DEVICES, v).apply()

    /** Stable random id, kept outside backups so a cloned install never shares it. */
    val deviceId: String
        @Synchronized get() {
            val f = File(context.noBackupFilesDir, "sync_device_id")
            runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
            val id = UUID.randomUUID().toString()
            f.parentFile?.mkdirs()
            f.writeText(id)
            return id
        }

    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && !password.isNullOrEmpty()

    /**
     * Cheap check for the main thread: the server, user and a stored password exist. Never decrypts the
     * Keystore secret (use [isConfigured] on a background thread before really syncing).
     */
    val looksConfigured: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && secrets.has(K_PASSWORD)

    fun knownEtags(): Map<String, String> {
        val raw = prefs.getString(K_ETAGS, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
    }

    fun saveKnownEtags(map: Map<String, String>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString(K_ETAGS, o.toString()).apply()
    }

    /** Device file name -> why the last sync skipped it, for files whose ETag is recorded (not re-read until they change). */
    fun knownSkips(): Map<String, String> {
        val raw = prefs.getString(K_SKIP_NOTES, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
    }

    fun saveKnownSkips(map: Map<String, String>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString(K_SKIP_NOTES, o.toString()).apply()
    }

    fun baseUrl(): HttpUrl? = serverUrl.toHttpUrlOrNull()

    /**
     * Wipes every setting and the stored password. The device id file is kept, and so are the last sync
     * time and the restore token: they describe the shadow, which Disconnect keeps, and a restore done
     * while disconnected must still be recognised after reconnecting.
     */
    fun clear() {
        secrets.put(K_PASSWORD, null)
        val lastTime = prefs.getLong(K_LAST_TIME, 0L)
        val token = prefs.getLong(K_RESTORE_TOKEN, 0L)
        prefs.edit().apply {
            clear()
            if (lastTime != 0L) putLong(K_LAST_TIME, lastTime)
            if (token != 0L) putLong(K_RESTORE_TOKEN, token)
        }.apply()
    }

    companion object {
        private const val PREFS = "sync"

        /**
         * Called after an app backup was restored: the next sync prefers what the other devices hold over
         * the restored rows. Touches only the preferences file, so it works when sync was never set up.
         */
        fun markRestorePending(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var token = java.util.concurrent.ThreadLocalRandom.current().nextLong()
            if (token == 0L || token == prefs.getLong(K_RESTORE_TOKEN, 0L)) token = 1L + System.nanoTime()
            prefs.edit().putLong(K_RESTORE_TOKEN, token).apply()
        }

        const val ANSWER_NONE = 0
        const val ANSWER_ALLOW = 1
        const val ANSWER_KEEP = 2
        private const val K_ANSWER_REMOTE = "answer_remote"
        private const val K_ANSWER_LOCAL = "answer_local"
        private const val K_URL = "server_url"
        private const val K_USER = "username"
        private const val K_PASSWORD = "password"
        private const val K_PIN = "pinned_cert_sha256"
        private const val K_ENABLED = "enabled"
        private const val K_DEVICE_NAME = "device_name"
        private const val K_LAST_TIME = "last_sync_time"
        private const val K_LAST_RESULT = "last_result"
        private const val K_ETAGS = "etags"
        private const val K_UPLOAD_HASH = "last_upload_hash"
        private const val K_SKEW = "last_skew_warning"
        private const val K_SKIPPED = "last_skipped_devices"
        private const val K_DEVICES = "last_devices_seen"
        private const val K_RESTORE_TOKEN = "restore_token"
        private const val K_PENDING_REMOTE = "pending_remote_removals"
        private const val K_PENDING_LOCAL = "pending_local_removals"
        private const val K_SKIP_NOTES = "skip_notes"
        private const val K_UPLOAD_TAG = "last_upload_tag"
    }
}
