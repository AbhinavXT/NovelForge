package com.abhinavxt.novelforge.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.database.CodexNameEntity
import com.abhinavxt.novelforge.data.database.HighlightEntity
import com.abhinavxt.novelforge.data.model.Chapter
import com.abhinavxt.novelforge.data.model.Novel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecapTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun charactersAreRankedByWholeWordMentionsAndNeverSpoiled() {
        val names = listOf("Li Wei" to 1, "Zhao" to 2, "Mei" to 9, "Han" to 1)
        val texts = listOf("Li Wei met Zhao. Zhao bowed.", "Zhao left; Li Wei stayed. Mei waved. Hannah ran.")
        // Mei first appears in chapter 9, past the ceiling; "Hannah" isn't "Han".
        assertEquals(listOf("Zhao", "Li Wei"), Recap.characters(names, ceiling = 5, texts = texts))
        assertEquals(listOf("Zhao"), Recap.characters(names, ceiling = 5, texts = texts, limit = 1))
        assertEquals(emptyList<String>(), Recap.characters(names, ceiling = 5, texts = emptyList()))
    }

    @Test
    fun stoppedAtIsTheParagraphShortened() {
        assertEquals("Two.", Recap.stoppedAt(listOf("One.", " Two. "), 1))
        assertNull(Recap.stoppedAt(listOf("One."), 4))
        assertEquals(280, Recap.stoppedAt(listOf("x".repeat(500)), 0)!!.length)
    }

    @Test
    fun recapAfterAWeekFromTextOnTheDevice() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = NovelRepository(db)
        repo.addToLibrary(Novel(
            id = "n_1", title = "T", author = "A", coverUrl = null, description = "", source = "S", status = "Ongoing",
            chapters = (1..6).map { Chapter(id = "c$it", number = it, title = "Chapter $it", url = "u$it") }
        ))
        db.chapterDao().markChapterDownloaded("c4", "Zhao came.\n\nZhao spoke to Li Wei.", 0)
        db.chapterDao().markChapterDownloaded("c5", "Li Wei slept.\n\nThe night was long.", 0)
        db.chapterDao().markChapterDownloaded("c6", "Not read yet. Mei arrives.", 0)
        db.codexDao().upsertNames(listOf(
            CodexNameEntity(novelId = "n_1", name = "Zhao", occurrences = 3, chapterCount = 1, firstChapterNumber = 4),
            CodexNameEntity(novelId = "n_1", name = "Li Wei", occurrences = 2, chapterCount = 2, firstChapterNumber = 4),
            CodexNameEntity(novelId = "n_1", name = "Mei", occurrences = 1, chapterCount = 1, firstChapterNumber = 6)
        ))
        fun highlight(chapter: Int, text: String) = HighlightEntity(
            novelId = "n_1", chapterId = "c$chapter", chapterNumber = chapter, chapterTitle = "Chapter $chapter",
            paragraphIndex = 0, startOffset = 0, endOffset = text.length, selectedText = text
        )
        db.highlightDao().insertHighlight(highlight(1, "too old"))
        db.highlightDao().insertHighlight(highlight(4, "Zhao came"))
        db.highlightDao().insertHighlight(highlight(5, "night was long"))

        val now = 100 * day
        val progress = com.abhinavxt.novelforge.data.database.ReadingProgressEntity(
            novelId = "n_1", currentChapterId = "c5", currentChapterNumber = 5, paragraphIndex = 1, lastReadAt = now - 10 * day
        )
        val recap = repo.buildRecap(progress, now)!!
        assertEquals(10, recap.daysAway)
        assertEquals("The night was long.", recap.stoppedAt)
        // Chapters 3-5 only: Mei (chapter 6) is a spoiler.
        assertEquals(listOf("Li Wei", "Zhao"), recap.characters)
        assertEquals(listOf("night was long", "Zhao came"), recap.highlights)

        // Less than a week away: nothing.
        assertNull(repo.buildRecap(progress.copy(lastReadAt = now - 6 * day), now))
        db.close()
    }
}
