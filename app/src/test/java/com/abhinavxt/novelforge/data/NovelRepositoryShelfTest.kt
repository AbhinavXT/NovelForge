package com.abhinavxt.novelforge.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.database.BookmarkEntity
import com.abhinavxt.novelforge.data.database.CategoryEntity
import com.abhinavxt.novelforge.data.database.HighlightEntity
import com.abhinavxt.novelforge.data.database.NovelCategoryCrossRef
import com.abhinavxt.novelforge.data.database.ReadingStatus
import com.abhinavxt.novelforge.data.model.BookLook
import com.abhinavxt.novelforge.data.model.Chapter
import com.abhinavxt.novelforge.data.model.Novel
import com.abhinavxt.novelforge.data.model.ReaderSettings
import com.abhinavxt.novelforge.data.model.ReaderTheme
import com.abhinavxt.novelforge.ui.screens.highlightOfTheDay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reading status, per-book look, highlight review and source migration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NovelRepositoryShelfTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: NovelRepository

    private fun novel(id: String, chapters: IntRange, status: String = "Ongoing", source: String = "Site") = Novel(
        id = id, title = "The Sword Path", author = "A", coverUrl = null, description = "",
        source = source, status = status,
        chapters = chapters.map { Chapter(id = "${id}_c$it", number = it, title = "Chapter $it", url = "https://x/$id/$it") }
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = NovelRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun startingABookMarksItReadingButKeepsAChosenStatus() = runBlocking {
        repo.addToLibrary(novel("a_1", 1..3))
        repo.addToLibrary(novel("b_1", 1..3))
        repo.setReadingStatus("a_1", ReadingStatus.PLAN_TO_READ)
        repo.setReadingStatus("b_1", ReadingStatus.ON_HOLD)

        repo.saveReadingProgress("a_1", "a_1_c1", 1)
        repo.saveReadingProgress("b_1", "b_1_c1", 1)

        val statuses = repo.getReadingStatuses().first()
        assertEquals(ReadingStatus.READING, statuses["a_1"])
        assertEquals(ReadingStatus.ON_HOLD, statuses["b_1"])
    }

    @Test
    fun finishingOnlyCompletesABookThatIsOver() = runBlocking {
        repo.addToLibrary(novel("a_1", 1..3, status = "Ongoing"))
        repo.addToLibrary(novel("b_1", 1..3, status = "Completed"))
        repo.addToLibrary(novel("c_1", 1..3, status = "Completed"))
        repo.setReadingStatus("c_1", ReadingStatus.DROPPED)

        listOf("a_1", "b_1", "c_1").forEach { repo.markFinished(it) }

        val statuses = repo.getReadingStatuses().first()
        assertNull(statuses["a_1"])
        assertEquals(ReadingStatus.COMPLETED, statuses["b_1"])
        assertEquals(ReadingStatus.DROPPED, statuses["c_1"])
    }

    @Test
    fun bookLookRoundTripsAndBadJsonIsIgnored() = runBlocking {
        repo.addToLibrary(novel("a_1", 1..3))
        val look = BookLook.of(ReaderSettings(fontSize = 22, theme = ReaderTheme.NORD))
        repo.setBookLook("a_1", look)
        assertEquals(look, repo.getBookLook("a_1"))

        db.novelDao().setReaderSettingsJson("a_1", """{"fontSize":20,"theme":"NOT_A_THEME"}""")
        assertNull(repo.getBookLook("a_1"))

        repo.setBookLook("a_1", null)
        assertNull(repo.getBookLook("a_1"))
    }

    @Test
    fun migrationMovesTheReadersThingsAndDropsTheOldBook() = runBlocking {
        repo.addToLibrary(novel("old_1", 1..10))
        repo.setReadingStatus("old_1", ReadingStatus.ON_HOLD)
        repo.setBookLook("old_1", BookLook.of(ReaderSettings(fontSize = 20)))
        repo.saveReadingProgress("old_1", "old_1_c7", 7, paragraphIndex = 12)
        db.bookmarkDao().insertBookmark(
            BookmarkEntity(novelId = "old_1", chapterId = "old_1_c3", chapterUrl = "u", chapterNumber = 3,
                chapterTitle = "Chapter 3", paragraphIndex = 2, textSnippet = "s")
        )
        db.bookmarkDao().insertBookmark(
            BookmarkEntity(novelId = "old_1", chapterId = "old_1_c9", chapterUrl = "u9", chapterNumber = 9,
                chapterTitle = "Chapter 9", paragraphIndex = 5, textSnippet = "late")
        )
        db.highlightDao().insertHighlight(
            HighlightEntity(novelId = "old_1", chapterId = "old_1_c9", chapterNumber = 9, chapterTitle = "Chapter 9",
                paragraphIndex = 0, startOffset = 0, endOffset = 4, selectedText = "Wei!")
        )
        val cat = db.categoryDao().insertCategory(CategoryEntity(name = "Cultivation"))
        db.categoryDao().insertCrossRefs(listOf(NovelCategoryCrossRef("old_1", cat)))

        // The new source is missing chapters 8-10.
        val result = repo.migrateNovel("old_1", novel("new_1", 1..7))

        assertEquals(7, result.matchedChapters)
        assertFalse(repo.isInLibrary("old_1"))
        assertTrue(repo.isInLibrary("new_1"))
        assertEquals(ReadingStatus.ON_HOLD, repo.getReadingStatuses().first()["new_1"])
        assertEquals(20, repo.getBookLook("new_1")?.fontSize)

        val progress = repo.getReadingProgress("new_1")!!
        assertEquals("new_1_c7", progress.currentChapterId)
        assertEquals(12, progress.paragraphIndex)
        assertNull(repo.getReadingProgress("old_1"))

        val bookmarks = db.bookmarkDao().getBookmarksForNovelOnce("new_1").associateBy { it.textSnippet }
        assertEquals("new_1_c3", bookmarks.getValue("s").chapterId)
        assertEquals("https://x/new_1/3", bookmarks.getValue("s").chapterUrl)
        assertEquals(2, bookmarks.getValue("s").paragraphIndex)
        // Chapter 9 has no counterpart: the bookmark opens at the top of chapter 7, the nearest before it.
        val late = bookmarks.getValue("late")
        assertEquals("new_1_c7", late.chapterId)
        assertEquals("https://x/new_1/7", late.chapterUrl)
        assertEquals(7, late.chapterNumber)
        assertEquals(0, late.paragraphIndex)
        assertEquals(1, result.movedBookmarks)
        assertEquals(1, result.bookmarksToNearest)

        // Chapter 9 has no counterpart: the highlight moves book but keeps its old chapter.
        val highlight = db.highlightDao().getHighlightsForNovelOnce("new_1").single()
        assertEquals("old_1_c9", highlight.chapterId)
        assertEquals(0, result.movedHighlights)

        assertEquals(setOf(cat), repo.getCategoryIdsForNovel("new_1"))
        assertEquals(0, db.chapterDao().getChapterCount("old_1"))
    }

    @Test
    fun upgradeReattachesBookmarksStrandedByAnEarlierMigration() = runBlocking {
        // The book now has only chapters 2 and 5.
        repo.addToLibrary(novel("new_1", 1..0).copy(chapters = listOf(2, 5).map {
            Chapter(id = "new_1_c$it", number = it, title = "Chapter $it", url = "https://x/new_1/$it")
        }))
        fun bookmark(novelId: String, chapterId: String, number: Int) = BookmarkEntity(
            novelId = novelId, chapterId = chapterId, chapterUrl = "old", chapterNumber = number,
            chapterTitle = "Old", paragraphIndex = 9, textSnippet = "$novelId/$chapterId"
        )
        db.bookmarkDao().insertBookmark(bookmark("new_1", "old_1_c4", 4))   // gone: nearest before is 2
        db.bookmarkDao().insertBookmark(bookmark("new_1", "old_1_c9", 9))   // gone: nearest before is 5
        db.bookmarkDao().insertBookmark(bookmark("new_1", "old_1_c1", 1))   // gone, nothing before: first, 2
        db.bookmarkDao().insertBookmark(bookmark("new_1", "new_1_c5", 5))   // fine: untouched
        db.bookmarkDao().insertBookmark(bookmark("gone_1", "gone_1_c3", 3)) // book has no chapters: untouched

        AppDatabase.reattachStrandedBookmarks(db.openHelper.writableDatabase)

        val all = (db.bookmarkDao().getBookmarksForNovelOnce("new_1") +
            db.bookmarkDao().getBookmarksForNovelOnce("gone_1")).associateBy { it.textSnippet }
        fun check(key: String, chapterId: String, number: Int, paragraph: Int) {
            val b = all.getValue(key)
            assertEquals(key, chapterId, b.chapterId)
            assertEquals(key, number, b.chapterNumber)
            assertEquals(key, paragraph, b.paragraphIndex)
        }
        check("new_1/old_1_c4", "new_1_c2", 2, 0)
        check("new_1/old_1_c9", "new_1_c5", 5, 0)
        check("new_1/old_1_c1", "new_1_c2", 2, 0)
        check("new_1/new_1_c5", "new_1_c5", 5, 9)
        check("gone_1/gone_1_c3", "gone_1_c3", 3, 9)
        assertEquals("https://x/new_1/5", all.getValue("new_1/old_1_c9").chapterUrl)
        assertEquals("Chapter 5", all.getValue("new_1/old_1_c9").chapterTitle)
    }

    @Test
    fun migrationRefusesABookAlreadyInTheLibrary() = runBlocking {
        repo.addToLibrary(novel("old_1", 1..3))
        repo.addToLibrary(novel("new_1", 1..3))
        try {
            repo.migrateNovel("old_1", novel("new_1", 1..3))
            fail("expected a refusal")
        } catch (e: UserFacingException) {
            assertTrue(repo.isInLibrary("old_1"))
        }
    }

    @Test
    fun closestChapterFallsBackToTheLastOneBefore() {
        val chapters = listOf(1, 2, 5, 6).map { Chapter("c$it", it, "", "") }
        assertEquals(5, closestChapter(chapters, 5)?.number)
        assertEquals(2, closestChapter(chapters, 4)?.number)
        assertEquals(1, closestChapter(chapters, 0)?.number)
        assertNull(closestChapter(emptyList(), 3))
    }

    @Test
    fun highlightOfTheDayCyclesAndJoinsTheBook() = runBlocking {
        repo.addToLibrary(novel("a_1", 1..3))
        repeat(3) { i ->
            db.highlightDao().insertHighlight(
                HighlightEntity(novelId = "a_1", chapterId = "a_1_c1", chapterNumber = 1, chapterTitle = "Chapter 1",
                    paragraphIndex = i, startOffset = 0, endOffset = 1, selectedText = "h$i", createdAt = i.toLong())
            )
        }
        val all = repo.getAllHighlightsWithBook().first()
        assertEquals(listOf("h2", "h1", "h0"), all.map { it.highlight.selectedText })
        assertEquals("The Sword Path", all.first().novelTitle)
        assertEquals("https://x/a_1/1", all.first().chapterUrl)
        assertEquals(all[1], highlightOfTheDay(all, 4))
        assertNull(highlightOfTheDay(emptyList(), 4))
    }
}
