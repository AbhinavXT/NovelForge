package com.abhinavxt.novelforge.data

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.novelforge.data.database.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class VocabularyTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun knowingACardSpacesItOutAndMissingItStartsOver() {
        assertEquals(1 to 1_000 + day, Vocabulary.next(0, knewIt = true, now = 1_000))
        assertEquals(2 to 3 * day, Vocabulary.next(1, knewIt = true, now = 0))
        assertEquals(3 to 7 * day, Vocabulary.next(2, knewIt = true, now = 0))
        // The top box repeats every 30 days.
        assertEquals(5 to 30 * day, Vocabulary.next(5, knewIt = true, now = 0))
        assertEquals(0 to 77L, Vocabulary.next(4, knewIt = false, now = 77))
    }

    @Test
    fun sentenceIsTheOneThatUsesTheWholeWord() {
        val paragraphs = listOf(
            "He was stoic. Nothing else.",
            "The sect was austere, even grim. Its elder smiled! Then left.",
        )
        assertEquals("The sect was austere, even grim.", Vocabulary.sentenceWith("Austere", paragraphs))
        // Whole words only: "elder" isn't found inside "elderly", and "stoic" isn't "stoicism".
        assertEquals("Its elder smiled!", Vocabulary.sentenceWith("elder", paragraphs))
        assertNull(Vocabulary.sentenceWith("grimace", paragraphs))
        assertNull(Vocabulary.sentenceWith("  ", paragraphs))
        val long = "word " + "x".repeat(400)
        assertEquals(300, Vocabulary.sentenceWith("word", listOf(long))!!.length)
    }

    @Test
    fun definitionTextKeepsThreeDistinctLines() {
        assertEquals("a\nb\nc", Vocabulary.definitionText(listOf(" a ", "b", "a", "", "c", "d")))
    }

    @Test
    fun savingTwiceKeepsTheFirstCardAndForgettingRemovesIt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = NovelRepository(db)
        repo.saveWord("Austere", "severe", "It was austere.", "n_1", "Book", 3)
        val card = repo.observeVocabulary().first().single()
        repo.reviewWord(card, knewIt = true)
        repo.saveWord("austere", "other", null, null, null, null)
        val again = repo.observeVocabulary().first().single()
        assertEquals("severe", again.definition)
        assertEquals(1, again.box)
        assertTrue(repo.isWordSaved("AUSTERE "))
        repo.forgetWord("Austere")
        assertFalse(repo.isWordSaved("austere"))
        db.close()
    }

    /** The upgrade's hand-written table must be exactly what Room builds, or Room refuses the database. */
    @Test
    fun upgradeTableMatchesRoomsOwn() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val room = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val manual = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(null).callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = AppDatabase.createVocabularyTable(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }
            ).build()
        )
        fun describe(db: SupportSQLiteDatabase): List<String> =
            listOf("PRAGMA table_info(vocabulary)", "PRAGMA index_list(vocabulary)", "PRAGMA index_info(index_vocabulary_key)")
                .flatMap { sql ->
                    db.query(sql).use { c ->
                        generateSequence { if (c.moveToNext()) c else null }.map { row ->
                            // index_list's seq column differs by creation order; drop it.
                            (0 until row.columnCount).filter { row.getColumnName(it) != "seq" }
                                .joinToString("|") { row.getColumnName(it) + "=" + row.getString(it) }
                        }.toList()
                    }
                }
        assertEquals(describe(room.openHelper.writableDatabase), describe(manual.writableDatabase))
        room.close()
        manual.close()
    }
}
