package com.abhinavxt.novelforge.data

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.database.ChapterEntity
import com.abhinavxt.novelforge.data.database.DownloadQueueStatus
import com.abhinavxt.novelforge.data.database.NovelEntity
import com.abhinavxt.novelforge.data.model.Chapter
import com.abhinavxt.novelforge.ui.viewmodel.nextUnreadToDownload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadQueueTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val queue = DownloadQueue(db.downloadQueueDao(), db.chapterDao())

    @After
    fun close() = db.close()

    private fun chapters(count: Int) = (1..count).map { Chapter("c$it", it, "Chapter $it", "url$it") }

    private fun seedNovel(count: Int) = runBlocking {
        db.novelDao().insertNovel(NovelEntity("n", "Book", "", null, "", "src", "", count))
        db.chapterDao().insertChapters(chapters(count).map { ChapterEntity(it.id, "n", it.number, it.title, it.url) })
    }

    @Test
    fun enqueueSkipsDownloadedAndAlreadyQueuedChapters() = runBlocking {
        seedNovel(5)
        db.chapterDao().markChapterDownloaded("c1", "text", 1L)

        // c2 is flagged downloaded by the caller's stale copy; c1 only in the database.
        val asked = chapters(5).map { if (it.number == 2) it.copy(isDownloaded = true) else it }
        assertEquals(3, queue.enqueue("n", "Book", asked))
        assertEquals(listOf("c3", "c4", "c5"), db.downloadQueueDao().observeAll().first().map { it.chapterId })

        // Asking again changes nothing, and a row already in progress keeps its state.
        db.downloadQueueDao().markDownloading("c3")
        assertEquals(0, queue.enqueue("n", "Book", asked))
        assertEquals(DownloadQueueStatus.DOWNLOADING, db.downloadQueueDao().observeAll().first().first().status)
    }

    @Test
    fun enqueueCreatesMissingChapterRowsButKeepsDownloadedText() = runBlocking {
        seedNovel(1)
        db.chapterDao().markChapterDownloaded("c1", "kept", 1L)
        queue.enqueue("n", "Book", chapters(2))
        assertEquals("kept", db.chapterDao().getChapterById("c1")!!.content)
        assertEquals("Chapter 2", db.chapterDao().getChapterById("c2")!!.title)
    }

    @Test
    fun aSavedChapterLeavesTheQueue() = runBlocking {
        seedNovel(1)
        queue.enqueue("n", "Book", chapters(1))
        val outcome = queue.attempt(queue.next()!!) { "the text" }
        assertEquals(DownloadQueue.Outcome.Done, outcome)
        assertEquals("the text", db.chapterDao().getChapterById("c1")!!.content)
        assertTrue(db.chapterDao().getChapterById("c1")!!.isDownloaded)
        assertNull(queue.next())
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun aChapterIsRetriedTwiceThenMarkedFailedAndSkipped() = runBlocking {
        seedNovel(2)
        queue.enqueue("n", "Book", chapters(2))
        val dao = db.downloadQueueDao()

        assertEquals(DownloadQueue.Outcome.WillRetry, queue.attempt(queue.next()!!) { throw java.io.IOException("offline") })
        val afterFirst = dao.observeAll().first().first()
        assertEquals(DownloadQueueStatus.QUEUED, afterFirst.status)
        assertEquals(1, afterFirst.attempts)

        assertEquals(DownloadQueue.Outcome.WillRetry, queue.attempt(queue.next()!!) { null })
        val last = queue.attempt(queue.next()!!) { throw java.io.IOException("offline") }
        assertEquals(DownloadQueue.Outcome.GaveUp("offline"), last)

        val failed = dao.observeAll().first().first()
        assertEquals(DownloadQueueStatus.FAILED, failed.status)
        assertEquals(3, failed.attempts)
        assertEquals("offline", failed.error)
        // The queue moves on to the next chapter and the failed one stays put.
        assertEquals("c2", queue.next()!!.chapterId)
        assertEquals(1, dao.observeCounts().first().failed)

        queue.retryFailed()
        val retried = dao.observeAll().first().first()
        assertEquals(DownloadQueueStatus.QUEUED, retried.status)
        assertEquals(0, retried.attempts)
        assertNull(retried.error)
    }

    @Test
    fun interruptedRowsGoBackInLine() = runBlocking {
        seedNovel(2)
        queue.enqueue("n", "Book", chapters(2))
        db.downloadQueueDao().markDownloading("c1")
        queue.requeueInterrupted()
        assertEquals(DownloadQueueStatus.QUEUED, db.downloadQueueDao().observeAll().first().first().status)
        assertEquals("c1", queue.next()!!.chapterId)
    }

    @Test
    fun nextUnreadStartsAfterTheReadingPositionAndSkipsDownloaded() {
        val all = chapters(10).map { if (it.number == 5) it.copy(isDownloaded = true) else it }
        assertEquals(listOf("c4", "c6", "c7"), nextUnreadToDownload(all, "c3", 3).map { it.id })
        assertEquals(listOf("c1", "c2"), nextUnreadToDownload(all, null, 2).map { it.id })
        assertEquals(5, nextUnreadToDownload(all, "c5", null).size)
        assertEquals(emptyList<Chapter>(), nextUnreadToDownload(all, "c10", 5))
    }

    /** The upgrade's hand-written table must be exactly what Room builds, or Room refuses the database. */
    @Test
    fun migrationCreatesTheTableRoomExpects() {
        val manual = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(null).callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = AppDatabase.createDownloadQueueTable(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }
            ).build()
        )
        fun describe(db: SupportSQLiteDatabase): List<String> = listOf(
            "PRAGMA table_info(download_queue)", "PRAGMA index_list(download_queue)",
            "PRAGMA index_info(index_download_queue_novelId)"
        ).flatMap { sql ->
            db.query(sql).use { c ->
                generateSequence { if (c.moveToNext()) c else null }.map { row ->
                    (0 until row.columnCount).filter { row.getColumnName(it) != "seq" }
                        .joinToString("|") { row.getColumnName(it) + "=" + row.getString(it) }
                }.toList()
            }
        }
        assertEquals(describe(db.openHelper.writableDatabase), describe(manual.writableDatabase))
        manual.close()
        assertEquals(27, AppDatabase.MIGRATION_27_28.startVersion)
        assertEquals(28, AppDatabase.MIGRATION_27_28.endVersion)
    }
}
