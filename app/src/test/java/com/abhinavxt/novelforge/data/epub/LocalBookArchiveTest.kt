package com.abhinavxt.novelforge.data.epub

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.database.ReadingProgressEntity
import com.abhinavxt.novelforge.data.epub.LocalBookArchive.ArchiveResult
import com.abhinavxt.novelforge.data.epub.LocalBookArchive.RestoreResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalBookArchiveTest {

    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var importer: EpubImporter
    private lateinit var archive: LocalBookArchive
    private lateinit var dir: File

    private val book = """# The Sword Path

## Chapter 1: Awakening

Li Wei opened his eyes. The morning light was harsh.

He stood up slowly.

## Chapter 2: The Elder

Elder Chen was waiting at the gate.

## Chapter 3: Departure

They left before dawn. It was a long walk and nobody spoke.
"""

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        importer = EpubImporter(context, db)
        archive = LocalBookArchive(context, db, importer)
        dir = File(context.cacheDir, "archive-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun writeBook(name: String = "book.md", text: String = book): Uri =
        Uri.fromFile(File(dir, name).apply { writeText(text) })

    private suspend fun importBook(uri: Uri, remember: Boolean = true): String {
        val result = importer.importDocument(uri, rememberSource = remember)
        return (result as EpubImporter.ImportResult.Success).novelId
    }

    private suspend fun texts(novelId: String): List<String?> =
        db.chapterDao().getChaptersForNovelOnce(novelId)
            .map { db.chapterDao().getChapterById(it.id)?.content }

    @Test
    fun `archive drops the text and restore brings back exactly the same chapters`() = runBlocking {
        val id = importBook(writeBook())
        val before = texts(id)
        db.novelDao().insertProgressReplace(
            ReadingProgressEntity(id, "${id}_ch2", 2, 1, 1, lastReadAt = 123L)
        )

        val archived = archive.archive(id)
        assertTrue(archived is ArchiveResult.Archived)
        assertTrue((archived as ArchiveResult.Archived).freedBytes > 0)
        assertTrue(texts(id).all { it == null })
        assertNotNull(db.novelDao().getNovelById(id)!!.archivedAt)
        assertEquals(ArchiveResult.AlreadyArchived, archive.archive(id))

        assertEquals(RestoreResult.Restored(before.size), archive.restore(id))
        assertEquals(before, texts(id))
        assertNull(db.novelDao().getNovelById(id)!!.archivedAt)
        // What belongs to the user is untouched throughout.
        assertEquals(2, db.readingProgressDao().getProgress(id)!!.currentChapterNumber)
        assertEquals(RestoreResult.NothingToRestore, archive.restore(id))
    }

    @Test
    fun `search index forgets an archived book and relearns it on restore`() = runBlocking {
        val id = importBook(writeBook())
        assertTrue(db.chapterDao().searchChapterContent("Elder").isNotEmpty())
        archive.archive(id)
        assertTrue(db.chapterDao().searchChapterContent("Elder").isEmpty())
        archive.restore(id)
        assertTrue(db.chapterDao().searchChapterContent("Elder").isNotEmpty())
    }

    @Test
    fun `archive refuses a book whose file no longer matches and keeps its text`() = runBlocking {
        val uri = writeBook()
        val id = importBook(uri)
        File(uri.path!!).writeText(book.replace("Elder Chen", "Elder Zhao"))

        assertEquals(ArchiveResult.FileChanged, archive.archive(id))
        assertTrue(texts(id).all { it != null })
        assertNull(db.novelDao().getNovelById(id)!!.archivedAt)
    }

    @Test
    fun `archive refuses a book that never recorded its file`() = runBlocking {
        val id = importBook(writeBook(), remember = false)
        assertEquals(ArchiveResult.NoFile, archive.archive(id))
        assertTrue(texts(id).all { it != null })
    }

    @Test
    fun `archive reports a missing file instead of losing the text`() = runBlocking {
        val uri = writeBook()
        val id = importBook(uri)
        File(uri.path!!).delete()

        assertTrue(archive.archive(id) is ArchiveResult.FileUnavailable)
        assertTrue(texts(id).all { it != null })
    }

    @Test
    fun `restore stops on changed text unless told to accept it`() = runBlocking {
        val uri = writeBook()
        val id = importBook(uri)
        archive.archive(id)
        val edited = book.replace("Elder Chen", "Elder Zhao")
        File(uri.path!!).writeText(edited)

        assertTrue(archive.restore(id) is RestoreResult.DifferentText)
        assertNotNull(db.novelDao().getNovelById(id)!!.archivedAt)
        assertTrue(texts(id).all { it == null })

        assertTrue(archive.restore(id, acceptChanges = true) is RestoreResult.Restored)
        assertTrue(texts(id).any { it!!.contains("Elder Zhao") })
        // The accepted text becomes the new baseline.
        archive.archive(id)
        assertTrue(archive.restore(id) is RestoreResult.Restored)
    }

    @Test
    fun `restore from a relocated file adopts it as the new source`() = runBlocking {
        val uri = writeBook()
        val id = importBook(uri)
        archive.archive(id)
        File(uri.path!!).delete()

        assertTrue(archive.restore(id) is RestoreResult.FileUnavailable)

        val moved = writeBook(name = "moved.md")
        assertTrue(archive.restore(id, fileUri = moved) is RestoreResult.Restored)
        assertEquals(moved.toString(), db.novelDao().getSourceUri(id))
    }

    @Test
    fun `a book with no recorded file asks for one`() = runBlocking {
        val id = importBook(writeBook(), remember = false)
        db.chapterDao().deleteAllDownloadsForNovel(id) // as after a text-less backup restore

        assertEquals(RestoreResult.NeedsFile, archive.restore(id))
        assertTrue(archive.restore(id, fileUri = writeBook("found.md")) is RestoreResult.Restored)
        assertTrue(texts(id).all { it != null })
    }

    @Test
    fun `restore rejects a different book picked by mistake`() = runBlocking {
        val id = importBook(writeBook())
        archive.archive(id)
        val other = writeBook(
            name = "other.md",
            text = "# Other\n\n## One\n\nSomething else entirely.\n"
        )
        assertTrue(archive.restore(id, fileUri = other) is RestoreResult.DifferentText)
        assertTrue(texts(id).all { it == null })
    }

    @Test
    fun `picking a legacy book's file again makes it archivable`() = runBlocking {
        // Imported the old way: no file recorded.
        val id = importBook(writeBook(), remember = false)
        assertEquals(ArchiveResult.NoFile, archive.archive(id))

        val again = writeBook(name = "again.md")
        val result = importer.importDocument(again, rememberSource = true)
        assertTrue(result is EpubImporter.ImportResult.Duplicate)
        assertEquals(again.toString(), db.novelDao().getSourceUri(id))
        assertTrue(archive.archive(id) is ArchiveResult.Archived)
    }

    @Test
    fun `a duplicate never replaces the file a book already has`() = runBlocking {
        val first = writeBook()
        val id = importBook(first)
        importer.importDocument(writeBook(name = "copy.md"), rememberSource = true)
        assertEquals(first.toString(), db.novelDao().getSourceUri(id))
    }

    @Test
    fun `folder files are told apart from hand-picked ones`() {
        val base = "content://com.android.externalstorage.documents"
        assertTrue(LibraryFolder.isInFolder("$base/tree/primary%3ABooks/document/primary%3ABooks%2Fa.epub"))
        assertTrue(!LibraryFolder.isInFolder("$base/document/primary%3ABooks%2Fa.epub"))
        assertTrue(!LibraryFolder.isInFolder("not a uri"))
    }

    @Test
    fun `reading an archived chapter through the repository restores the book`() = runBlocking {
        val id = importBook(writeBook())
        val chapter = db.chapterDao().getChaptersForNovelOnce(id).first()
        val expected = db.chapterDao().getChapterById(chapter.id)!!.content
        archive.archive(id)

        val repository = com.abhinavxt.novelforge.data.NovelRepository(
            database = db,
            restoreArchived = { archive.restore(it) is RestoreResult.Restored }
        )
        assertEquals(expected, repository.getChapterContent(id, chapter.id, chapter.url))
        assertTrue(!repository.isArchived(id))
    }

    @Test
    fun `restoring from a picked file adopts it, and a rejected pick changes nothing`() = runBlocking {
        val uri = writeBook()
        val id = importBook(uri)
        archive.archive(id)

        val wrong = writeBook(name = "wrong.md", text = "# W\n\n## One\n\nNot it.\n")
        assertTrue(archive.restoreFromPickedFile(id, wrong) is RestoreResult.DifferentText)
        assertEquals(uri.toString(), db.novelDao().getSourceUri(id))

        val right = writeBook(name = "right.md")
        assertTrue(archive.restoreFromPickedFile(id, right) is RestoreResult.Restored)
        assertEquals(right.toString(), db.novelDao().getSourceUri(id))
    }

    @Test
    fun `fingerprint notices text moving across a chapter boundary`() {
        assertNotEquals(
            LocalBookArchive.fingerprint(listOf("ab", "c")),
            LocalBookArchive.fingerprint(listOf("a", "bc"))
        )
        assertEquals(
            LocalBookArchive.fingerprint(listOf("a", "bc")),
            LocalBookArchive.fingerprint(listOf("a", "bc"))
        )
    }

    // ── Phase 4: archived imports and bulk archive ────────────────────

    /** A book the duplicate checks will not mistake for [book]. */
    private fun otherBook(n: Int): String =
        book.replace("The Sword Path", "Book $n").replace("Li Wei", "Hero $n")

    @Test
    fun `an archived import stores the chapters but not their text`() = runBlocking {
        val full = importBook(writeBook(name = "full.md"))
        val expected = texts(full)
        val fullFingerprint = db.novelDao().getNovelById(full)!!.textFingerprint

        val uri = writeBook(name = "lazy.md", text = otherBook(1))
        val result = importer.importDocument(uri, rememberSource = true, archived = true)
        val id = (result as EpubImporter.ImportResult.Success).novelId

        val novel = db.novelDao().getNovelById(id)!!
        assertNotNull(novel.archivedAt)
        assertNotNull(novel.textFingerprint)
        assertNotEquals(fullFingerprint, novel.textFingerprint)
        assertEquals(expected.size, novel.totalChapters)
        assertEquals(
            db.chapterDao().getChaptersForNovelOnce(full).map { it.number to it.title },
            db.chapterDao().getChaptersForNovelOnce(id).map { it.number to it.title }
        )
        assertTrue(texts(id).all { it == null })
        assertTrue(db.chapterDao().searchChapterContent("Hero").isEmpty())

        // The first restore checks the file against the fingerprint taken at
        // import, so it gets exactly what a full import would have stored.
        assertEquals(RestoreResult.Restored(expected.size), archive.restore(id))
        assertEquals(expected.map { it?.replace("Li Wei", "Hero 1") }, texts(id))
        assertTrue(db.chapterDao().searchChapterContent("Hero").isNotEmpty())
    }

    @Test
    fun `an archived import of a file that later changes is caught on restore`() = runBlocking {
        val uri = writeBook()
        val id = (importer.importDocument(uri, rememberSource = true, archived = true)
                as EpubImporter.ImportResult.Success).novelId
        File(uri.path!!).writeText(book.replace("Elder Chen", "Elder Zhao"))

        assertTrue(archive.restore(id) is RestoreResult.DifferentText)
        assertTrue(texts(id).all { it == null })
    }

    @Test
    fun `an archived import without a remembered file is imported in full`() = runBlocking {
        val id = (importer.importDocument(writeBook(), rememberSource = false, archived = true)
                as EpubImporter.ImportResult.Success).novelId
        assertNull(db.novelDao().getNovelById(id)!!.archivedAt)
        assertTrue(texts(id).all { it != null })
    }

    @Test
    fun `archive all archives what it can and leaves the rest untouched`() = runBlocking {
        val good1 = importBook(writeBook(name = "1.md", text = otherBook(1)))
        val good2 = importBook(writeBook(name = "2.md", text = otherBook(2)))
        val noFile = importBook(writeBook(name = "3.md", text = otherBook(3)), remember = false)
        val changedUri = writeBook(name = "4.md", text = otherBook(4))
        val changed = importBook(changedUri)
        File(changedUri.path!!).writeText(otherBook(4).replace("Elder Chen", "Elder Zhao"))
        val goneUri = writeBook(name = "5.md", text = otherBook(5))
        val gone = importBook(goneUri)
        File(goneUri.path!!).delete()

        val progress = mutableListOf<Pair<Int, Int>>()
        val summary = archive.archiveAll(onProgress = { d, t -> progress += d to t })

        assertEquals(2, summary.archived)
        assertEquals(1, summary.noFile)
        assertEquals(1, summary.changed)
        assertEquals(1, summary.unavailable)
        assertTrue(summary.freedBytes > 0)
        assertEquals(5 to 5, progress.last())
        assertTrue(texts(good1).all { it == null } && texts(good2).all { it == null })
        for (id in listOf(noFile, changed, gone)) {
            assertTrue(texts(id).all { it != null })
            assertNull(db.novelDao().getNovelById(id)!!.archivedAt)
        }

        // A second run only looks at what is left, and archives none of it.
        assertEquals(0, archive.archiveAll().archived)
    }

    @Test
    fun `archive all stops between books and the next run carries on`() = runBlocking {
        val ids = (1..3).map { importBook(writeBook(name = "$it.md", text = otherBook(it))) }
        var seen = 0
        val first = archive.archiveAll(
            onProgress = { _, _ -> seen++ },
            shouldStop = { seen >= 1 }
        )
        assertTrue(first.stopped)
        assertEquals(1, first.archived)

        val second = archive.archiveAll()
        assertEquals(2, second.archived)
        assertTrue(ids.all { db.novelDao().getNovelById(it)!!.archivedAt != null })
    }

    @Test
    fun `a book stored by an older version keeps that text through archive and restore`() = runBlocking {
        val uri = Uri.fromFile(File(dir, "old.epub").apply {
            writeBytes(TestEpub.build(listOf(TestEpub.chapter("c1",
                "<blockquote>The first paragraph of the story.</blockquote><blockquote>The second paragraph of the story.</blockquote>"
            ))))
        })
        val id = importBook(uri)
        val glued = "The first paragraph of the story.The second paragraph of the story."
        assertNotEquals(listOf(glued), texts(id))
        db.chapterDao().markChapterDownloaded("${id}_ch1", glued, 1L)

        assertTrue(archive.archive(id) is ArchiveResult.Archived)
        assertTrue(archive.restore(id) is RestoreResult.Restored)
        assertEquals(listOf<String?>(glued), texts(id))
    }
}
