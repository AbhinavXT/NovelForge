package com.abhinavxt.novelforge.data.epub

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.database.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Formatting and contents through import, archive, restore and removal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookFormattingStoreTest {

    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var store: BookFormattingStore
    private lateinit var importer: EpubImporter
    private lateinit var archive: LocalBookArchive
    private lateinit var dir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        store = BookFormattingStore(context, db)
        importer = EpubImporter(context, db)
        archive = LocalBookArchive(context, db, importer)
        dir = File(context.cacheDir, "formatting-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private val nav = TestEpub.Item(
        "nav", "nav.xhtml", "application/xhtml+xml",
        TestEpub.xhtml(
            """<nav epub:type="toc"><ol>
              <li><a href="Text/c1.xhtml">One: The Map</a></li>
              <li><a href="Text/c2.xhtml">Two: The Road</a></li></ol></nav>"""
        ).toByteArray(),
        properties = "nav"
    )

    private fun writeBook(): Uri {
        val bytes = TestEpub.build(
            listOf(
                TestEpub.chapter("c1", "<h1>1</h1><p>She looked at the <i>old</i> map for a long while.</p><p><img src=\"../Images/map.png\"/></p>"),
                TestEpub.chapter("c2", "<h1>2</h1><p>Then she set off down the road, <b>alone</b>, before dawn.</p>")
            ),
            listOf(nav, TestEpub.Item("map", "Images/map.png", "image/png", byteArrayOf(4, 5, 6)))
        )
        return Uri.fromFile(File(dir, "book.epub").apply { writeBytes(bytes) })
    }

    private fun imagesOf(novelId: String) = BookFormattingStore.imagesDir(context, novelId)

    @Test
    fun `import keeps formatting, pictures and contents`() = runBlocking {
        val novelId = (importer.importDocument(writeBook(), rememberSource = true) as EpubImporter.ImportResult.Success).novelId

        val chapters = db.chapterDao().getChaptersForNovelOnce(novelId)
        assertEquals(listOf("One: The Map", "Two: The Road"), chapters.map { it.title })

        val f1 = store.getFormatting(chapters[0].id)!!
        assertEquals(InlineStyle.ITALIC, f1.spans.single().style)
        val image = f1.images.single()
        assertTrue(File(imagesOf(novelId), image.path).readBytes().contentEquals(byteArrayOf(4, 5, 6)))
        assertEquals(InlineStyle.BOLD, store.getFormatting(chapters[1].id)!!.spans.single().style)

        assertEquals(listOf(TocEntry("One: The Map", 0, 1, 0), TocEntry("Two: The Road", 0, 2, 0)), store.getToc(novelId))
    }

    @Test
    fun `archiving drops formatting and pictures, restoring brings them back`() = runBlocking {
        val novelId = (importer.importDocument(writeBook(), rememberSource = true) as EpubImporter.ImportResult.Success).novelId
        val firstId = db.chapterDao().getChaptersForNovelOnce(novelId)[0].id

        assertTrue(archive.archive(novelId) is LocalBookArchive.ArchiveResult.Archived)
        assertNull(store.getFormatting(firstId))
        assertFalse(imagesOf(novelId).exists())
        assertEquals(2, store.getToc(novelId).size) // the contents stay, like the chapter list

        assertTrue(archive.restore(novelId) is LocalBookArchive.RestoreResult.Restored)
        val restored = store.getFormatting(firstId)!!
        assertEquals(InlineStyle.ITALIC, restored.spans.single().style)
        assertTrue(File(imagesOf(novelId), restored.images.single().path).exists())
    }

    @Test
    fun `removing the book removes everything`() = runBlocking {
        val novelId = (importer.importDocument(writeBook(), rememberSource = true) as EpubImporter.ImportResult.Success).novelId
        val firstId = db.chapterDao().getChaptersForNovelOnce(novelId)[0].id
        NovelRepository(db, formatting = store).removeFromLibrary(novelId)

        assertNull(store.getFormatting(firstId))
        assertTrue(store.getToc(novelId).isEmpty())
        assertFalse(imagesOf(novelId).exists())
    }

    @Test
    fun `an archived import keeps its contents but stores no formatting`() = runBlocking {
        val novelId = (importer.importDocument(writeBook(), rememberSource = true, archived = true)
            as EpubImporter.ImportResult.Success).novelId
        val firstId = db.chapterDao().getChaptersForNovelOnce(novelId)[0].id
        assertNull(store.getFormatting(firstId))
        assertFalse(imagesOf(novelId).exists())
        assertEquals(2, store.getToc(novelId).size)
    }
}
