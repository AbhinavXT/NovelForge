package com.abhinavxt.novelforge.data

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.database.SyncRecordEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncRecordTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun daoRoundTripsNullsAndReplacesOnSameKey() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.syncDao()
        val a = SyncRecordEntity("book", "o:1", 10L, "devA", false, null, null, null)
        val b = SyncRecordEntity("pos", "o:1", 11L, "devA", true, "{\"p\":3}", "n_1", "d1")
        dao.upsertAll(listOf(a, b))
        assertEquals(2, dao.count())
        assertEquals(a, dao.get("book", "o:1"))
        assertNull(dao.get("book", "nope"))
        assertEquals(listOf(b), dao.byKind("pos"))

        val a2 = a.copy(hlc = 20L, origin = "devB", payload = "{}", localId = "n_1")
        dao.upsertAll(listOf(a2))
        assertEquals(2, dao.count())
        assertEquals(a2, dao.get("book", "o:1"))
        assertEquals(2, dao.all().size)

        dao.deleteAll()
        assertEquals(0, dao.count())
        db.close()
    }

    /** The migration's table must be exactly what Room builds, or Room refuses the database. */
    @Test
    fun migrationTableMatchesRoomsOwn() {
        val room = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val manual = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(null).callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = AppDatabase.MIGRATION_30_31.migrate(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }
            ).build()
        )
        fun describe(db: SupportSQLiteDatabase): List<String> =
            listOf(
                "PRAGMA table_info(sync_records)",
                "PRAGMA index_list(sync_records)",
                "PRAGMA index_info(index_sync_records_kind_localId)"
            ).flatMap { sql ->
                db.query(sql).use { c ->
                    generateSequence { if (c.moveToNext()) c else null }.map { row ->
                        (0 until row.columnCount).filter { row.getColumnName(it) != "seq" }
                            .joinToString("|") { row.getColumnName(it) + "=" + row.getString(it) }
                    }.toList()
                }
            }
        val expected = describe(room.openHelper.writableDatabase)
        assertTrue(expected.size > 8)
        assertEquals(expected, describe(manual.writableDatabase))
        room.close()
        manual.close()
    }
}
