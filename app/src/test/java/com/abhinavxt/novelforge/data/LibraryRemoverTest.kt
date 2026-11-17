package com.abhinavxt.novelforge.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.database.BookmarkEntity
import com.abhinavxt.novelforge.data.database.NovelEntity
import com.abhinavxt.novelforge.data.epub.LibraryFolder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryRemoverTest {
    private lateinit var db: AppDatabase
    private lateinit var app: Application
    private val deletedFiles = mutableListOf<String>()
    private lateinit var remover: LibraryRemover

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        remover = LibraryRemover(NovelRepository(db), app) { deletedFiles += it }
        LibraryFolder.clearIgnoredUris(app)
    }

    @After
    fun tearDown() = db.close()

    private fun novel(id: String, uri: String?) = NovelEntity(
        id = id, title = "t", author = "a", coverUrl = null, description = "", source = "local",
        status = "", totalChapters = 0, sourceUri = uri,
    )

    private val folderUri = "content://com.android.externalstorage.documents/tree/primary%3ABooks/document/primary%3ABooks%2Fa.epub"
    private val pickedUri = "content://com.android.providers.downloads.documents/document/5"

    @Test
    fun folderBook_isRemembered_rowsGone_coverFilesDeleted() = runBlocking {
        db.novelDao().insertNovel(novel("local_1", folderUri))
        db.bookmarkDao().insertBookmark(BookmarkEntity(
            novelId = "local_1", chapterId = "c", chapterUrl = "u", chapterNumber = 1,
            chapterTitle = "t", paragraphIndex = 0, textSnippet = "s",
        ))
        remover.remove("local_1")
        assertTrue(db.novelDao().getAllNovelsOnce().isEmpty())
        assertTrue(db.bookmarkDao().getAllBookmarksOnce().isEmpty())
        assertEquals(setOf(folderUri), LibraryFolder.getIgnoredUris(app))
        assertEquals(listOf("local_1"), deletedFiles)
    }

    @Test
    fun handPickedBook_isNotAddedToTheIgnoreList() = runBlocking {
        db.novelDao().insertNovel(novel("local_2", pickedUri))
        remover.remove("local_2")
        assertTrue(db.novelDao().getAllNovelsOnce().isEmpty())
        assertFalse(pickedUri in LibraryFolder.getIgnoredUris(app))
    }

    @Test
    fun onlineBook_hasNoFileSteps() = runBlocking {
        db.novelDao().insertNovel(novel("rr_1", null).copy(source = "Royal Road"))
        remover.remove("rr_1")
        assertTrue(db.novelDao().getAllNovelsOnce().isEmpty())
        assertTrue(deletedFiles.isEmpty())
        assertTrue(LibraryFolder.getIgnoredUris(app).isEmpty())
    }

    @Test
    fun aFailingCleanup_stillReleasesTheFileGrant() = runBlocking {
        val released = mutableListOf<String>()
        val failing = LibraryRemover(NovelRepository(db, onProgressSaved = { throw Error("cleanup failed") }), app) { deletedFiles += it }
        failing.releaseFileAccess = { released += it }
        db.novelDao().insertNovel(novel("local_3", pickedUri))
        val fx = failing.removeRows("local_3")
        try {
            fx.afterCommit()
            org.junit.Assert.fail("the failure should still reach the caller")
        } catch (e: Error) {
            assertEquals("cleanup failed", e.message)
        }
        assertEquals(listOf(pickedUri), released)
    }
}
