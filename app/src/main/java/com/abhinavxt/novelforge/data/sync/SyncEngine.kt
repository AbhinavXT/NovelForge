package com.abhinavxt.novelforge.data.sync

import android.content.Context
import com.abhinavxt.novelforge.BuildConfig
import com.abhinavxt.novelforge.NovelReaderApplication
import com.abhinavxt.novelforge.data.LibraryRemover
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Runs one sync (docs/state/SYNC_DESIGN.md section 5):
 * ensure the remote layout, download the files of the other devices whose ETag changed, then in ONE
 * database transaction merge / guard / apply / save the shadow, then upload our own file.
 *
 * Unchanged remote files are not downloaded and not merged again. That is correct because the shadow
 * (saved in the same transaction as the apply) already holds the winner of every record those files
 * contained, and the merger only ever replaces a record with a strictly newer one. An ETag is
 * recorded only after the transaction that merged that file committed.
 *
 * Failure handling: any failure before the transaction changes nothing; the transaction rolls back as
 * a whole; a failure after it (upload) leaves the new local state in place and the next sync uploads,
 * because the upload hash is only stored after a successful upload.
 *
 * Concurrency: one [Mutex] per process ([SyncGate.mutex] by default, which a backup restore also holds). A [SyncTrigger.Manual] sync waits for a
 * running one; every other trigger returns [SyncOutcome.Busy] at once.
 */
class SyncEngine(
    private val store: SyncLocalStore,
    private val dav: WebDavClient,
    private val clock: HybridClock,
    private val settings: SyncSettings,
    private val appVersion: String = BuildConfig.VERSION_NAME,
    private val now: () -> Long = System::currentTimeMillis,
    private val mutex: Mutex = SyncGate.mutex,
    /** Test seam: runs after the downloads and before the transaction. */
    private val afterDownloads: suspend () -> Unit = {},
) {

    suspend fun sync(
        trigger: SyncTrigger,
        /** Answer: delete here the books other devices removed. Remembered until a sync commits. */
        allowRemoteDeletions: Boolean = false,
        /** Answer: remove everywhere the books this device lost. Remembered until a sync commits. */
        allowLocalDeletions: Boolean = false,
        /** Answer: keep the local books other devices removed and announce them again. Remembered until a sync commits. */
        keepLocalBooks: Boolean = false,
    ): SyncOutcome {
        if (!settings.isConfigured) return SyncOutcome.NotConfigured
        if (trigger == SyncTrigger.Manual) mutex.lock() else if (!mutex.tryLock()) return SyncOutcome.Busy
        try {
            // Each answer is stored first, so it also holds for the background runs that follow.
            if (allowRemoteDeletions) settings.remoteAnswer = SyncSettings.ANSWER_ALLOW
            if (keepLocalBooks) settings.remoteAnswer = SyncSettings.ANSWER_KEEP
            if (allowLocalDeletions) settings.allowLocalRemovals = true
            val outcome = try {
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: WebDavException) {
                if (e.status == 401 || e.status == 403) SyncOutcome.AuthFailed else SyncOutcome.ServerError(e.status)
            } catch (e: IOException) {
                // Never e.message: it can carry the server address.
                SyncOutcome.ServerUnreachable("Could not reach the server (${e.javaClass.simpleName})")
            } catch (e: Exception) {
                SyncOutcome.Failed("Sync failed (${e.javaClass.simpleName})")
            }
            settings.lastResult = describe(outcome)
            return outcome
        } finally {
            mutex.unlock()
        }
    }

    private class Downloaded(
        val remotes: MutableList<SyncFile>,
        val etags: Map<String, String>,
        val devices: Int,
        val skew: Boolean,
        /** Device file name -> short reason the file was skipped. */
        val skips: Map<String, String>,
    )

    private sealed class Tx {
        class Guard(val res: MergeResult) : Tx()
        class Done(val res: MergeResult, val report: ApplyReport) : Tx()
    }

    private suspend fun run(): SyncOutcome {
        val deviceId = settings.deviceId
        // Time of the last committed sync, as recorded BEFORE this run: edits after it are never reverts.
        val lastSync = settings.lastSyncTime
        val allowRemote = settings.remoteAnswer == SyncSettings.ANSWER_ALLOW
        val keepLocalBooks = settings.remoteAnswer == SyncSettings.ANSWER_KEEP
        val allowLocal = settings.allowLocalRemovals

        // b. Layout.
        ensureLayout()?.let { return it }

        // c. List and download.
        val entries = dav.list(DEVICES)
        for (e in entries) {
            // A crashed upload of ours leaves a temp file; nobody reads it, remove it when we can.
            if (!e.isCollection && e.name.startsWith("$deviceId$SUFFIX$TMP")) {
                try { dav.delete("$DEVICES/${e.name}") } catch (_: IOException) {} catch (_: WebDavException) {}
            }
        }
        val ownName = "$deviceId$SUFFIX"
        val ownExists = entries.any { !it.isCollection && it.name == ownName }
        val dl = download(entries, deviceId)
        afterDownloads()

        // d. The clock reads dav.serverDateOffsetMs on every tick (see create()); a request has been made.

        // e. One transaction.
        // The token a restore left; cleared after the commit only if it is still this value.
        val restoreToken = settings.restoreToken
        val preferShadow = restoreToken != 0L
        // Taken before the snapshot: an edit made while we upload is never at or before it.
        val syncStartedAt = now()
        val tx = try {
            store.transaction {
                val shadow = store.shadow()
                val snapshot = store.snapshot()
                val res = SyncMerger.merge(
                    shadow, snapshot, dl.remotes, clock, deviceId, preferShadow, allowRemote, allowLocal,
                    lastSyncTime = lastSync, keepLocalBooks = keepLocalBooks,
                )
                if (res.massDeletionGuardTripped) return@transaction Tx.Guard(res)
                val report = store.apply(res.changes)
                store.saveShadow(report.applyTo(res.shadow))
                Tx.Done(res, report)
            }
        } catch (e: OutOfMemoryError) {
            // Too much data for this device: let go of everything, change nothing, and do not retry.
            dl.remotes.clear()
            return SyncOutcome.Failed(OOM_MESSAGE, retry = false)
        }
        if (tx is Tx.Guard) {
            // Remembered across restarts, also when a background sync tripped: the Sync section shows it.
            // Only the sides that tripped are written: the other side was not settled by a run that
            // committed nothing, so its pending count and answer stay as they were.
            if (tx.res.guardRemoteDeletions > 0) settings.pendingRemoteRemovals = tx.res.guardRemoteDeletions
            if (tx.res.guardLocalDeletions > 0) settings.pendingLocalRemovals = tx.res.guardLocalDeletions
            val fromRemote = tx.res.guardLocalDeletions == 0
            return SyncOutcome.GuardTripped(
                if (fromRemote) tx.res.guardRemoteDeletions else tx.res.guardLocalDeletions, fromRemote,
            )
        }
        tx as Tx.Done

        // The transaction committed. From here nothing may be lost to a cancellation: the effects (cover
        // files, file access, folder-scan ignore), the restore token and the recorded ETags all run, and
        // one failing effect never skips the others.
        val skipped = tx.res.skippedHigherFormat.map { "$it: needs a newer NovelForge" } + dl.skips.values
        withContext(NonCancellable) {
            if (preferShadow) settings.clearRestoreToken(restoreToken)
            for (effect in tx.report.sideEffects) {
                try {
                    effect()
                } catch (_: Throwable) {
                    // A failed cleanup leaves a stray file at worst; the sync itself succeeded.
                }
            }
            // The merge is committed: these files are now part of the shadow.
            settings.saveKnownEtags(dl.etags)
            settings.saveKnownSkips(dl.skips)
            if (tx.res.guardLocalDeletions > 0) {
                // The remote answer was applied; this device's missing books are still waiting for theirs.
                settings.clearRemoteSide()
                settings.pendingLocalRemovals = tx.res.guardLocalDeletions
            } else {
                settings.clearPendingGuard()
            }
        }

        // f. Upload.
        val file = SyncFile(SyncFormat.CURRENT, deviceId, settings.deviceName, appVersion, now(), tx.res.records)
        val hash = sha256(SyncFileCodec.encode(file.copy(writtenAt = 0L)))
        // An unchanged hash only skips the upload while the server still shows our file as we left it.
        val ownTag = entries.firstOrNull { !it.isCollection && it.name == ownName }?.let { tagOf(it) }
        val recordedTag = settings.lastUploadTag
        val serverIntact = ownExists && (recordedTag == null || ownTag == recordedTag)
        val upload = !(serverIntact && hash == settings.lastUploadHash)
        if (upload) {
            upload(deviceId, SyncFileCodec.encode(file))
            settings.lastUploadHash = hash
            settings.lastUploadTag = try {
                dav.list(DEVICES).firstOrNull { !it.isCollection && it.name == ownName }?.let { tagOf(it) }
            } catch (_: IOException) { null } catch (_: WebDavException) { null }
        }

        // g. Bookkeeping.
        settings.lastSyncTime = syncStartedAt
        settings.lastSkewWarning = dl.skew
        settings.lastSkippedDevices = skipped
        settings.lastDevicesSeen = dl.devices
        return SyncOutcome.Success(
            applied = tx.report.applied.size,
            published = tx.res.records.size,
            notApplied = tx.report.notApplied.size,
            devicesSeen = dl.devices,
            skewWarning = dl.skew,
            skippedDevices = skipped,
            uploaded = upload,
        )
    }

    /** Null when the layout is usable, otherwise the outcome to return. */
    private suspend fun ensureLayout(): SyncOutcome? {
        when (val g = dav.get("$ROOT/sync.json", null, 1024L * 1024)) {
            DavGet.TooLarge -> return SyncOutcome.Failed("The server's sync.json cannot be read")
            is DavGet.Body -> {
                val format = runCatching {
                    JsonParser.parseString(String(g.bytes, Charsets.UTF_8)).asJsonObject.get("format").asInt
                }.getOrNull() ?: return SyncOutcome.Failed("The server's sync.json cannot be read")
                if (format > SyncFormat.CURRENT) return SyncOutcome.ServerFormatTooNew(format)
            }
            else -> {
                dav.mkcol(DEVICES)
                val meta = JsonObject().apply {
                    addProperty("format", SyncFormat.CURRENT)
                    addProperty("created", now())
                    add("enc", com.google.gson.JsonNull.INSTANCE)
                }
                dav.put("$ROOT/sync.json", meta.toString().toByteArray(Charsets.UTF_8), "application/json")
            }
        }
        return null
    }

    private suspend fun download(entries: List<DavEntry>, ownId: String): Downloaded {
        val known = if (store.shadow().isEmpty()) emptyMap() else settings.knownEtags()
        val etags = HashMap<String, String>()
        val skips = LinkedHashMap<String, String>()
        val notes = if (known.isEmpty()) emptyMap() else settings.knownSkips()
        val remotes = ArrayList<SyncFile>()
        var devices = 0
        var skew = false
        val ownName = "$ownId$SUFFIX"
        for (e in entries) {
            if (e.isCollection || !e.name.endsWith(SUFFIX) || e.name.contains(TMP) || e.name == ownName) continue
            devices++
            val tag = tagOf(e)
            if (tag != null && known[e.name] == tag) {
                etags[e.name] = tag
                notes[e.name]?.let { skips[e.name] = it } // still skipped: it has not changed
                continue
            }
            val label = "Device ${e.name.removeSuffix(SUFFIX).take(8)}"
            val got = dav.get("$DEVICES/${e.name}", null, SyncFileCodec.MAX_COMPRESSED_BYTES)
            if (got is DavGet.TooLarge) {
                // Reported, and not downloaded again until the file changes.
                skips[e.name] = "$label: file too large"
                tag?.let { etags[e.name] = it }
                continue
            }
            val body = got as? DavGet.Body ?: continue
            val file = try {
                SyncFileCodec.decode(body.bytes)
            } catch (_: SyncFileCorruptException) {
                skips[e.name] = "$label: file damaged" // ETag not recorded: read again next time
                continue
            }
            if (file.deviceId == ownId) continue
            val limit = ((now() + (dav.serverDateOffsetMs ?: 0L)).coerceAtLeast(0L) + MAX_FUTURE_MS)
            if (file.isSupported && file.records.any { HybridClock.physicalOf(it.hlc) > limit }) {
                skips[e.name] = "${file.deviceName.ifBlank { label }}: its clock is more than a day ahead"
                (body.etag?.let { "e:$it" } ?: tag)?.let { etags[e.name] = it }
                continue
            }
            remotes += file
            if (file.isSupported) {
                (body.etag?.let { "e:$it" } ?: tag)?.let { etags[e.name] = it }
            }
            val lm = e.lastModified
            if (lm != null && file.writtenAt > 0 && Math.abs(file.writtenAt - lm) > SKEW_MS) skew = true
        }
        return Downloaded(remotes, etags, devices, skew, skips)
    }

    private fun tagOf(e: DavEntry): String? = when {
        e.etag != null -> "e:${e.etag}"
        e.lastModified != null && e.size != null -> "m:${e.lastModified}:${e.size}"
        else -> null
    }

    private suspend fun upload(deviceId: String, bytes: ByteArray) {
        val finalPath = "$DEVICES/$deviceId$SUFFIX"
        val tmp = "$finalPath$TMP${UUID.randomUUID().toString().replace("-", "").take(12)}"
        putWithParents(tmp, bytes)
        try {
            dav.move(tmp, finalPath)
        } catch (e: WebDavException) {
            // MOVE missing or refused: write the file directly, then drop the temp file.
            dav.put(finalPath, bytes, TYPE)
            try { dav.delete(tmp) } catch (_: IOException) {} catch (_: WebDavException) {}
        }
    }

    private suspend fun putWithParents(path: String, bytes: ByteArray) {
        try {
            dav.put(path, bytes, TYPE)
        } catch (e: WebDavException) {
            if (e.status != 409) throw e
            dav.mkcol(DEVICES)
            dav.put(path, bytes, TYPE)
        }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun describe(o: SyncOutcome): String = when (o) {
        is SyncOutcome.Success -> "Synced: ${o.applied} applied, ${o.published} records published"
        SyncOutcome.NotConfigured -> "Sync is not set up"
        SyncOutcome.AuthFailed -> "The server rejected the username or password"
        is SyncOutcome.ServerUnreachable -> o.message
        is SyncOutcome.ServerError -> "The server answered with an error (${o.status})"
        is SyncOutcome.GuardTripped ->
            if (o.fromRemote) "Stopped: another device removed ${o.count} books" else "Stopped: ${o.count} books would be removed from this device"
        is SyncOutcome.ServerFormatTooNew -> "The server folder needs a newer NovelForge"
        SyncOutcome.Busy -> "Another sync is running"
        is SyncOutcome.Failed -> o.message
    }

    companion object {
        private const val ROOT = "NovelForge"
        private const val DEVICES = "NovelForge/devices"
        private const val SUFFIX = ".json.gz"
        private const val TMP = ".tmp-"
        private const val TYPE = "application/octet-stream"
        private const val MAX_FUTURE_MS = 24 * 60 * 60 * 1000L
        private const val SKEW_MS = 2 * 60 * 1000L

        const val OOM_MESSAGE = "Not enough memory to sync this much data on this device"

        /**
         * The engine wired to the app's database and the stored settings, or null when sync is not
         * configured. The clock reads the server offset from the client on every tick; its state is
         * not persisted because the merger observes every hlc of the shadow before it ticks, so a
         * fresh clock after a restart still stamps above everything already merged.
         */
        fun create(context: Context): SyncEngine? {
            val app = context.applicationContext
            val settings = SyncSettings(app, KeystoreSecretStore(app))
            if (!settings.isConfigured) return null
            val base = settings.baseUrl() ?: return null
            val repository = (app as NovelReaderApplication).repository
            val db = AppDatabase.getDatabase(app)
            val store = RoomSyncStore(db, LibraryRemover(repository, app))
            val dav = WebDavClient(base, SyncHttp.build(settings))
            val clock = HybridClock(System::currentTimeMillis) { dav.serverDateOffsetMs ?: 0L }
            return SyncEngine(store, dav, clock, settings)
        }
    }
}
