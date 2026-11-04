package com.abhinavxt.novelforge.data.database

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.novelforge.data.epub.EpubImporter
import com.abhinavxt.novelforge.data.epub.LocalBookArchive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Against a real file, not an in-memory database: the point is that the
 * file gets smaller, which only a file can show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DatabaseCompactorTest {

    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var compactor: DatabaseCompactor
    private lateinit var dir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(NAME)
        db = Room.databaseBuilder(context, AppDatabase::class.java, NAME)
            .allowMainThreadQueries()
            .build()
        compactor = DatabaseCompactor(context, db)
        dir = File(context.cacheDir, "compact-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(NAME)
        dir.deleteRecursively()
    }

    /** A few MB of varied text, so the search index has real work in it. */
    private fun bigBook(): Uri {
        val text = buildString {
            append("# A Long Book\n\n")
            repeat(40) { ch ->
                append("## Chapter ${ch + 1}\n\n")
                repeat(400) { p ->
                    append("Paragraph $p of chapter $ch mentions word${p * 31 + ch} ")
                    append("and goes on for a while so that it is not tiny.\n\n")
                }
            }
        }
        return Uri.fromFile(File(dir, "big.md").apply { writeText(text) })
    }

    @Test
    fun `compacting after an archive gives the space back`() = runBlocking {
        val importer = EpubImporter(context, db)
        val archive = LocalBookArchive(context, db, importer)
        val id = (importer.importDocument(bigBook(), rememberSource = true)
                as EpubImporter.ImportResult.Success).novelId

        val full = compactor.usage().fileBytes
        assertTrue(archive.archive(id) is LocalBookArchive.ArchiveResult.Archived)

        val result = compactor.compact()
        assertTrue(result.toString(), result is DatabaseCompactor.Result.Compacted)
        result as DatabaseCompactor.Result.Compacted
        assertNull(result.vacuumSkipped)
        // The text was most of the file; well over half of it should go.
        assertTrue(
            "before=$full after=${result.afterBytes}",
            result.afterBytes < full / 2
        )
        assertEquals(result.afterBytes, compactor.usage().fileBytes)
        assertEquals(0L, compactor.usage().reclaimableBytes)

        // Nothing the user owns went with it, and the book still comes back.
        assertEquals(40, db.chapterDao().getChaptersForNovelOnce(id).size)
        assertEquals(LocalBookArchive.RestoreResult.Restored(40), archive.restore(id))
        assertTrue(db.chapterDao().searchChapterContent("word31").isNotEmpty())
    }

    @Test
    fun `compacting a database with nothing to free changes nothing`() = runBlocking {
        val result = compactor.compact()
        assertTrue(result is DatabaseCompactor.Result.Compacted)
    }

    private companion object {
        const val NAME = "compact-test.db"
    }
}
