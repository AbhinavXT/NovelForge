package com.abhinavxt.novelforge.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.database.BookmarkEntity
import com.abhinavxt.novelforge.data.database.ChapterEntity
import com.abhinavxt.novelforge.data.database.ChapterOriginalEntity
import com.abhinavxt.novelforge.data.database.HighlightEntity
import com.abhinavxt.novelforge.data.database.NovelEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeleteAfterReadingTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val cleanup = ReadChapterCleanup(db)

    @After
    fun close() = db.close()

    private fun decide(
        enabled: Boolean = true,
        isLocalBook: Boolean = false,
        isDownloaded: Boolean = true,
        hasTranslationOriginal: Boolean = false,
        hasHighlightOrBookmark: Boolean = false,
        isBeingRead: Boolean = false
    ) = shouldDeleteAfterReading(
        enabled, isLocalBook, isDownloaded, hasTranslationOriginal, hasHighlightOrBookmark, isBeingRead
    )

    @Test
    fun aPlainDownloadedChapterIsDeleted() = assertTrue(decide())

    @Test
    fun eachProtectionKeepsTheChapter() {
        assertFalse(decide(enabled = false))
        assertFalse(decide(isLocalBook = true))
        assertFalse(decide(isDownloaded = false))
        assertFalse(decide(hasTranslationOriginal = true))
        assertFalse(decide(hasHighlightOrBookmark = true))
        assertFalse(decide(isBeingRead = true))
    }

    @Test
    fun highlightedBookmarkedAndTranslatedChaptersSurviveWhileAPlainOneLosesItsText() = runBlocking {
        db.novelDao().insertNovel(NovelEntity("n", "Book", "", null, "", "src", "", 5))
        db.chapterDao().insertChapters((1..5).map { ChapterEntity("c$it", "n", it, "Chapter $it", "url$it") })
        (1..5).forEach { db.chapterDao().markChapterDownloaded("c$it", "text $it", 1L) }
        db.highlightDao().insertHighlight(
            HighlightEntity(
                novelId = "n", chapterId = "c1", chapterNumber = 1, chapterTitle = "Chapter 1",
                paragraphIndex = 0, startOffset = 0, endOffset = 4, selectedText = "text"
            )
        )
        db.bookmarkDao().insertBookmark(
            BookmarkEntity(
                novelId = "n", chapterId = "c2", chapterUrl = "url2", chapterNumber = 2,
                chapterTitle = "Chapter 2", paragraphIndex = 0, textSnippet = "text"
            )
        )
        db.translationDao().saveOriginal(ChapterOriginalEntity("c3", "n", "original", 0, "ai"))

        assertFalse(cleanup.clean("n", "c1", "c5", enabled = true))
        assertFalse(cleanup.clean("n", "c2", "c5", enabled = true))
        assertFalse(cleanup.clean("n", "c3", "c5", enabled = true))
        assertTrue(cleanup.clean("n", "c4", "c5", enabled = true))

        assertEquals("text 1", db.chapterDao().getChapterById("c1")?.content)
        assertEquals("text 2", db.chapterDao().getChapterById("c2")?.content)
        assertEquals("text 3", db.chapterDao().getChapterById("c3")?.content)
        val plain = db.chapterDao().getChapterById("c4")
        assertNull(plain?.content)
        assertFalse(plain?.isDownloaded == true)
    }

    @Test
    fun nothingIsDeletedWhenTheSettingIsOffOrTheBookIsLocal() = runBlocking {
        db.novelDao().insertNovel(NovelEntity("local_b", "Book", "", null, "", "src", "", 1))
        db.chapterDao().insertChapters(listOf(ChapterEntity("l1", "local_b", 1, "One", "u")))
        db.chapterDao().markChapterDownloaded("l1", "only copy", 1L)

        assertFalse(cleanup.clean("local_b", "l1", "l2", enabled = true))
        assertFalse(cleanup.clean("local_b", "l1", "l2", enabled = false))
        assertEquals("only copy", db.chapterDao().getChapterById("l1")?.content)
    }
}
