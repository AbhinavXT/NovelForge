package com.abhinavxt.novelforge.data.sync

import com.google.gson.JsonObject

/** Names of the record kinds. Unknown kinds are legal and pass through untouched. */
object SyncKind {
    const val BOOK = "book"
    const val STATUS = "status"
    const val LOOK = "look"
    const val PROGRESS = "progress"
    const val BOOKMARK = "bookmark"
    const val HIGHLIGHT = "highlight"
    const val CATEGORY = "category"
    const val CATLINK = "catlink"
    const val STAT = "stat"

    /** Kinds that belong to one book and die with it. */
    val CHILDREN: Set<String> = setOf(STATUS, LOOK, PROGRESS, BOOKMARK, HIGHLIGHT, CATLINK)

    /** Kinds the merger and the local store understand. Everything else is carried verbatim. */
    val KNOWN: Set<String> = CHILDREN + setOf(BOOK, CATEGORY, STAT)
}

object SyncFormat {
    const val CURRENT = 1
}

/**
 * Key helpers. A book key must never contain the child separator '|', so any part that can
 * (a novel id that is a URL, say) has to go through [escape] before it is put in a key.
 * The merger relies on a child key being `<bookKey>|...` (or exactly `<bookKey>`).
 */
object SyncKeys {
    const val SEP = '|'

    fun escape(part: String): String = part.replace("%", "%25").replace("|", "%7C")

    fun unescape(part: String): String = part.replace("%7C", "|").replace("%25", "%")

    fun child(bookKey: String, vararg parts: Any): String =
        (listOf(bookKey) + parts.map { escape(it.toString()) }).joinToString(SEP.toString())

    /** The book a record of a child kind belongs to: the key up to the first '|' (or the whole key). */
    fun parentBookKey(key: String): String = key.substringBefore(SEP)
}

/** One merged record. [payload] is null for tombstones. Unknown payload fields are preserved. */
data class SyncRecord(
    val kind: String,
    val key: String,
    val hlc: Long,
    val origin: String,
    val deleted: Boolean,
    val payload: JsonObject?,
)

/** The file one device publishes. [format] above [SyncFormat.CURRENT] means "cannot read". */
data class SyncFile(
    val format: Int = SyncFormat.CURRENT,
    val deviceId: String,
    val deviceName: String,
    val appVersion: String,
    val writtenAt: Long,
    val records: List<SyncRecord>,
) {
    val isSupported: Boolean get() = format <= SyncFormat.CURRENT
}

/** What a snapshot of the local database reports for one synced row. */
data class LocalRecord(
    val kind: String,
    val key: String,
    val payload: JsonObject?,
    /** Hash of the meaningful fields only; equal digest means "not edited". */
    val digest: String,
    /** Real edit time (device clock, ms) if the row records one. */
    val editedAt: Long? = null,
    /** Row creation time (device clock, ms) if known; used for the stale-restore rule. */
    val createdAt: Long? = null,
)

/**
 * A record of the last merged state ("shadow") plus what this device knows about it locally.
 * [applied] is true when a local row exists for it and is linked (sync_records.localId != null).
 * Records that were never applied are never turned into tombstones. [digest] is the digest of
 * the local row when it was last reconciled (null if none).
 */
data class ShadowEntry(
    val record: SyncRecord,
    val applied: Boolean,
    val digest: String? = null,
)

/** A change the local database does not yet reflect. */
sealed class LocalChange {
    abstract val record: SyncRecord

    /** Create or update the local row from [record].payload. Idempotent; may find no book to attach to. */
    data class Upsert(override val record: SyncRecord) : LocalChange()

    /** Delete the local row; [record] is the tombstone that justifies it. */
    data class Delete(override val record: SyncRecord) : LocalChange()
}

data class MergeCounts(
    val localEdits: Int = 0,
    val localDeletions: Int = 0,
    val remoteUpserts: Int = 0,
    val remoteDeletions: Int = 0,
    val droppedChildren: Int = 0,
)

data class MergeResult(
    /**
     * The new full shadow to save. Entries touched by [changes] have applied=false and digest=null:
     * the store must set both once it applied them (or leave applied=false if there is no local row).
     */
    val shadow: List<ShadowEntry>,
    /** The records to publish in our own file (the shadow records, tombstones included). */
    val records: List<SyncRecord>,
    val changes: List<LocalChange>,
    val counts: MergeCounts,
    /** The merge would delete too many books (here or on other devices); nothing was changed, ask the user. */
    val massDeletionGuardTripped: Boolean,
    /** When the guard tripped: books this device lost that would be tombstoned for everyone. */
    val guardLocalDeletions: Int = 0,
    /** When the guard tripped: books other devices removed that would be deleted from this device. */
    val guardRemoteDeletions: Int = 0,
    /**
     * Set with the guard tripped: the answer given for the remote side (delete here, or keep) would have
     * been used by this merge. The merger then commits it and holds the local side back.
     */
    val remoteAnswerUsed: Boolean = false,
    /** Devices whose file has a higher format (deviceName, or deviceId when blank). */
    val skippedHigherFormat: List<String>,
)

/** Why a sync runs. Manual waits for a running sync; the others return [SyncOutcome.Busy]. */
enum class SyncTrigger { AppStart, ReadingStopped, Manual, Periodic, Foreground }

/** Result of one sync run. Messages never contain the password or the server address. */
sealed class SyncOutcome {
    data class Success(
        /** Remote changes written to this device's database. */
        val applied: Int,
        /** Records in the file this device publishes. */
        val published: Int,
        /** Remote records kept but not applicable here yet (book not on this device, chapter not fetched). */
        val notApplied: Int,
        /** Other devices that have a file on the server. */
        val devicesSeen: Int,
        /** A device clock differs from the server's by more than 2 minutes. */
        val skewWarning: Boolean,
        /** Devices whose file has a newer format than this app reads. */
        val skippedDevices: List<String>,
        /** False when nothing had changed since the last upload. */
        val uploaded: Boolean,
    ) : SyncOutcome()

    object NotConfigured : SyncOutcome()
    object AuthFailed : SyncOutcome()
    data class ServerUnreachable(val message: String) : SyncOutcome()
    data class ServerError(val status: Int) : SyncOutcome()

    /**
     * Too many books would be deleted ([fromRemote]: other devices removed them and this device would
     * follow; otherwise this device lost them); nothing was applied or uploaded. Ask the user; the answers are remembered until a sync commits. */
    data class GuardTripped(val count: Int, val fromRemote: Boolean = false) : SyncOutcome()

    /** The server folder was written by a newer NovelForge (sync.json format); nothing was changed. */
    data class ServerFormatTooNew(val format: Int) : SyncOutcome()

    /** Another sync is running in this process. */
    object Busy : SyncOutcome()

    /** An unexpected local failure; local data and the remote file are unchanged. */
    data class Failed(val message: String, val retry: Boolean = true) : SyncOutcome()
}

/** What the sync engine needs from the local database. Implemented by [RoomSyncStore]. */
interface SyncLocalStore {
    suspend fun <T> transaction(block: suspend () -> T): T

    /** One [LocalRecord] per synced row, with chapter positions by chapter number. */
    suspend fun snapshot(): List<LocalRecord>

    /** Apply [changes] and report what was applied; the caller then saves the shadow. */
    suspend fun apply(changes: List<LocalChange>): ApplyReport

    /** The last merged state, with the applied flag and digest per record. */
    suspend fun shadow(): List<ShadowEntry>

    /** Replace the shadow with [entries] (applied and digest filled in for local rows). */
    suspend fun saveShadow(entries: List<ShadowEntry>)
}

/** One change the store handled. [localId]/[digest] are null for a delete (the row is gone). */
data class AppliedRow(val localId: String?, val digest: String?)

/**
 * Outcome of [SyncLocalStore.apply]. [applied] holds every change that was written (upserts) or is
 * already satisfied (deletes, including "was not there"), keyed by (kind, key). A change that is in
 * neither [applied] nor absent from the batch was NOT applied: it is in [notApplied] (no book on this
 * device, chapter not fetched yet, ...) and the merger re-offers it on a later sync.
 */
data class ApplyReport(
    val applied: Map<Pair<String, String>, AppliedRow> = emptyMap(),
    val notApplied: Set<Pair<String, String>> = emptySet(),
    /**
     * Work outside the database that belongs to the changes (folder-scan ignore record, cover file,
     * file access, formatting files, widget). The caller runs it only AFTER the transaction that
     * applied the changes committed; a rolled-back transaction must never run it.
     */
    val sideEffects: List<suspend () -> Unit> = emptyList(),
) {
    /**
     * Turns the merger's shadow into the one to save: upserts the store applied get applied=true and
     * the digest of the row as stored (so the next snapshot sees no edit); everything else is unchanged.
     * Call, inside the same transaction: `store.saveShadow(report.applyTo(mergeResult.shadow))`.
     */
    fun applyTo(shadow: List<ShadowEntry>): List<ShadowEntry> = shadow.map { e ->
        val row = applied[e.record.kind to e.record.key]
        if (row != null && !e.record.deleted && row.digest != null) e.copy(applied = true, digest = row.digest) else e
    }
}
