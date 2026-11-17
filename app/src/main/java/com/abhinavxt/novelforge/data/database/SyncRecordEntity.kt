package com.abhinavxt.novelforge.data.database

import androidx.room.Entity
import androidx.room.Index

/**
 * The sync engine's local copy of the merged state, tombstones included.
 * [payload] is a JSON string; [localId] links a record to the local row it was
 * applied to (null while no local row exists); [digest] fingerprints the
 * meaningful fields so a later snapshot can tell an edit from no change.
 */
@Entity(
    tableName = "sync_records",
    primaryKeys = ["kind", "key"],
    indices = [Index(value = ["kind", "localId"])]
)
data class SyncRecordEntity(
    val kind: String,
    val key: String,
    val hlc: Long,
    val origin: String,
    val deleted: Boolean,
    val payload: String?,
    val localId: String?,
    val digest: String?
)
