package com.abhinavxt.novelforge.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_records")
    suspend fun all(): List<SyncRecordEntity>

    @Query("SELECT * FROM sync_records WHERE kind = :kind")
    suspend fun byKind(kind: String): List<SyncRecordEntity>

    @Query("SELECT * FROM sync_records WHERE kind = :kind AND `key` = :key")
    suspend fun get(kind: String, key: String): SyncRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<SyncRecordEntity>)

    @Query("DELETE FROM sync_records")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM sync_records")
    suspend fun count(): Int
}
