package com.abhinavxt.novelforge.data.translation

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.database.ChapterEntity
import com.abhinavxt.novelforge.data.database.NovelEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TranslationManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private var calls = 0

    /** "Translates" by upper-casing, and reports one new name. */
    private val manager = TranslationManager(db.translationDao(), null) { _, _ ->
        ChapterTranslator { paragraphs, glossary ->
            calls++
            TranslationText.Reply(
                paragraphs.map { Glossary.apply(it, glossary).uppercase() },
                listOf(Term("lin", "Lin", "female, she/her"))
            )
        } to TranslationManager.ENGINE_AI
    }

    @After
    fun close() = db.close()

    private fun seed(content: String?) = runBlocking {
        db.novelDao().insertNovel(NovelEntity("n", "Book", "", null, "", "src", "", 1, translate = true))
        db.chapterDao().insertChapter(ChapterEntity("c", "n", 1, "One", "url", isDownloaded = content != null, content = content))
    }

    @Test
    fun translatingStoresTheTranslationKeepsTheOriginalAndLearnsNames() = runBlocking {
        seed("first line\n\nsecond line")
        val shown = manager.forReading("n", "c", "first line\n\nsecond line")
        assertEquals("FIRST LINE\n\nSECOND LINE", shown)
        assertEquals(shown, db.translationDao().getContent("c"))
        assertEquals("first line\n\nsecond line", db.translationDao().getOriginal("c")!!.original)
        assertEquals(listOf("lin" to "Lin"), db.translationDao().getGlossary("n").map { it.source to it.target })

        // Opening it again costs nothing.
        assertEquals(shown, manager.forReading("n", "c", shown))
        assertEquals(1, calls)
        assertEquals(emptyList<String>(), manager.untranslatedChapters("n"))
    }

    @Test
    fun theTitleIsTranslatedWithTheChapterAndRestoredWithIt() = runBlocking {
        seed("first line")
        assertEquals("FIRST LINE", manager.forReading("n", "c", "first line"))
        assertEquals("ONE", db.chapterDao().getChapterById("c")!!.title)

        assertEquals(1, manager.restoreBook("n"))
        val chapter = db.chapterDao().getChapterById("c")!!
        assertEquals("One", chapter.title)
        assertEquals("first line", chapter.content)
    }

    @Test
    fun aChapterReadOnlineIsStoredOnceTranslated() = runBlocking {
        seed(null)
        manager.forReading("n", "c", "fetched text")
        val chapter = db.chapterDao().getChapterById("c")!!
        assertTrue(chapter.isDownloaded)
        assertEquals("FETCHED TEXT", chapter.content)
    }

    @Test
    fun theGlossaryTheReaderSetIsUsedAndKept() = runBlocking {
        seed("lin smiled")
        manager.saveTerm(com.abhinavxt.novelforge.data.database.GlossaryTermEntity(novelId = "n", source = "lin", target = "Rin"))
        assertEquals("RIN SMILED", manager.forReading("n", "c", "lin smiled"))
        // The translator's own suggestion for "lin" doesn't overwrite the reader's.
        assertEquals("Rin", db.translationDao().getGlossary("n").single().target)
    }

    @Test
    fun aBookNotSetToTranslateIsLeftAlone() = runBlocking {
        seed("text")
        manager.setTranslateOn("n", false)
        assertEquals("text", manager.forReading("n", "c", "text"))
        assertEquals(0, calls)
    }

    @Test
    fun restoringPutsTheOriginalBackAndARedownloadEndsTheTranslation() = runBlocking {
        seed("one")
        manager.forReading("n", "c", "one")
        assertEquals(1, manager.restoreBook("n"))
        assertEquals("one", db.translationDao().getContent("c"))
        assertEquals(listOf("c"), manager.untranslatedChapters("n"))

        manager.forReading("n", "c", "one")
        db.chapterDao().markChapterDownloaded("c", "fresh raw text", 0)
        assertFalse(manager.isTranslated("c", "fresh raw text"))
        // The stale original isn't written over the new download.
        assertEquals(0, manager.restoreBook("n"))
        assertEquals("fresh raw text", db.translationDao().getContent("c"))
    }

    @Test
    fun aiTranslatorSplitsABatchWhoseReplyDoesNotLineUp() = runBlocking {
        val sizes = mutableListOf<Int>()
        val translator = AiTranslator("English") { _, user ->
            val count = com.google.gson.JsonParser.parseString(user).asJsonObject.getAsJsonArray("paragraphs").size()
            sizes += count
            // Big batches come back one paragraph short; single ones are fine.
            if (count > 1) """{"paragraphs": ["x"]}""" else """{"paragraphs": ["ok"], "new_terms": [{"source": "王", "target": "Wang"}]}"""
        }
        val reply = translator.translate(listOf("a", "b", "c"), emptyList())
        assertEquals(listOf("ok", "ok", "ok"), reply.paragraphs)
        assertEquals(listOf(3, 1, 2, 1, 1), sizes)
        assertEquals(listOf(Term("王", "Wang")), reply.newTerms)
    }

    /** The upgrade's hand-written tables must be exactly what Room builds, or Room refuses the database. */
    @Test
    fun upgradeTablesMatchRoomsOwn() {
        val manual = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(null).callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        AppDatabase.createTranslationTables(db)
                        AppDatabase.addOriginalTitleColumn(db)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }
            ).build()
        )
        fun describe(db: SupportSQLiteDatabase): List<String> = listOf(
            "PRAGMA table_info(chapter_originals)", "PRAGMA index_list(chapter_originals)",
            "PRAGMA index_info(index_chapter_originals_novelId)",
            "PRAGMA table_info(glossary_terms)", "PRAGMA index_list(glossary_terms)",
            "PRAGMA index_info(index_glossary_terms_novelId_source)"
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
    }
}
