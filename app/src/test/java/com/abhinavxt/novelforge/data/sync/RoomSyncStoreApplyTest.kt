package com.abhinavxt.novelforge.data.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.novelforge.data.LibraryRemover
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.database.BookmarkEntity
import com.abhinavxt.novelforge.data.database.CategoryEntity
import com.abhinavxt.novelforge.data.database.ChapterEntity
import com.abhinavxt.novelforge.data.database.HighlightEntity
import com.abhinavxt.novelforge.data.database.NovelCategoryCrossRef
import com.abhinavxt.novelforge.data.database.NovelEntity
import com.abhinavxt.novelforge.data.database.ReadingProgressEntity
import com.abhinavxt.novelforge.data.database.ReadingStatEvent
import com.abhinavxt.novelforge.data.epub.LibraryFolder
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomSyncStoreApplyTest {

    private lateinit var a: AppDatabase
    private lateinit var b: AppDatabase
    private lateinit var storeA: RoomSyncStore
    private lateinit var storeB: RoomSyncStore
    private var clockMs = 1_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        a = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        b = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        storeA = RoomSyncStore(a)
        storeB = RoomSyncStore(b)
    }

    @After
    fun tearDown() {
        a.close()
        b.close()
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun novel(id: String, source: String, fp: String? = null, hash: String? = null, uri: String? = null) =
        NovelEntity(
            id = id, title = "Title $id", author = "Au", coverUrl = "http://c/$id.jpg", description = "d",
            source = source, status = "Ongoing", totalChapters = 3, addedToLibraryAt = 1000L,
            sourceUri = uri, contentHash = hash, textFingerprint = fp, language = "en",
        )

    private suspend fun chapters(db: AppDatabase, novelId: String, vararg numbers: Int) {
        for (n in numbers) db.chapterDao().insertChapter(
            ChapterEntity("${novelId}_ch$n", novelId, n, "Chapter $n", "http://u/$novelId/$n")
        )
    }

    /** Online book rr_1 with one of every child kind. */
    private suspend fun seedOnline(db: AppDatabase) {
        db.novelDao().insertNovel(novel("rr_1", "Royal Road"))
        db.novelDao().setReadingStatus("rr_1", "READING")
        db.novelDao().setReaderSettingsJson("rr_1", "{\"fontSize\":20}")
        chapters(db, "rr_1", 2)
        seedChildren(db, "rr_1", "rr_1_ch2")
    }

    private suspend fun seedChildren(db: AppDatabase, id: String, chapterId: String) {
        db.novelDao().insertProgressReplace(ReadingProgressEntity(id, chapterId, 2, 5, 1, 5000L))
        db.bookmarkDao().insertBookmark(BookmarkEntity(
            novelId = id, chapterId = chapterId, chapterUrl = "http://u/2", chapterNumber = 2,
            chapterTitle = "Chapter 2", paragraphIndex = 7, textSnippet = "snip", note = "n1", createdAt = 2000L,
        ))
        db.highlightDao().insertHighlight(HighlightEntity(
            novelId = id, chapterId = chapterId, chapterNumber = 2, chapterTitle = "Chapter 2",
            paragraphIndex = 3, startOffset = 4, endOffset = 9, selectedText = "quote", note = "hn",
            color = "BLUE", createdAt = 2500L, updatedAt = 3000L,
        ))
        val cat = db.categoryDao().insertCategory(CategoryEntity(name = "Cultivation", createdAt = 10L))
        db.categoryDao().insertCrossRefs(listOf(NovelCategoryCrossRef(id, cat)))
        db.readingStatDao().insertEvent(ReadingStatEvent(
            novelId = id, chapterId = chapterId, wordsRead = 100, readingTimeMs = 60000L, completedAt = 4000L,
        ))
    }

    /** One full sync round on [store] with the way the engine must use the store. */
    private suspend fun round(store: RoomSyncStore, deviceId: String, vararg remotes: SyncFile): Pair<MergeResult, ApplyReport> {
        clockMs += 1000
        val clock = HybridClock({ clockMs }, { 0L })
        return store.transaction {
            val res = SyncMerger.merge(store.shadow(), store.snapshot(), remotes.toList(), clock, deviceId)
            val report = store.apply(res.changes)
            store.saveShadow(report.applyTo(res.shadow))
            res to report
        }
    }

    private suspend fun fileOf(store: RoomSyncStore, deviceId: String): SyncFile =
        SyncFile(1, deviceId, deviceId, "t", 0, round(store, deviceId).first.records)

    private suspend fun digests(store: RoomSyncStore) =
        store.snapshot().associate { (it.kind to it.key) to it.digest }

    private fun up(kind: String, key: String, vararg kv: Pair<String, Any?>, hlc: Long = 1L shl 20) =
        LocalChange.Upsert(SyncRecord(kind, key, hlc, "A", false, JsonObject().apply {
            for ((k, v) in kv) when (v) {
                null -> add(k, com.google.gson.JsonNull.INSTANCE)
                is Number -> addProperty(k, v)
                else -> addProperty(k, v.toString())
            }
        }))

    private fun del(kind: String, key: String) =
        LocalChange.Delete(SyncRecord(kind, key, 1L shl 21, "A", true, null))

    private fun onlineBook(id: String = "rr_1") =
        up(SyncKind.BOOK, "o:$id", "id" to id, "imported" to false, "title" to "T", "author" to "Au",
            "source" to "Royal Road", "status" to "Ongoing", "description" to "d", "totalChapters" to 3,
            "coverUrl" to "http://c/x.jpg", "language" to "en", "addedToLibraryAt" to 1000L)

    private fun importedBook(fp: String = "FP", hash: String = "HASH") =
        up(SyncKind.BOOK, "t:$fp", "imported" to true, "title" to "T", "author" to "Au", "source" to "local",
            "textFingerprint" to fp, "contentHash" to hash, "addedToLibraryAt" to 1000L)

    // ---- round trip -------------------------------------------------------------------------

    @Test
    fun roundTripEveryKind_andNoPhantomEditAfterwards() = runBlocking {
        seedOnline(a)
        a.novelDao().insertNovel(novel("local_x", "local", fp = "FP", hash = "HASH", uri = "content://f"))
        val fileA = fileOf(storeA, "A")

        // Empty device B: the online book arrives (no chapters); the imported file is not on B.
        val (_, r1) = round(storeB, "B", fileA)
        assertEquals(listOf("rr_1"), b.novelDao().getAllNovelsOnce().map { it.id })
        assertEquals(0, b.chapterDao().getChapterCount("rr_1"))
        assertNotNull(r1.applied[SyncKind.BOOK to "o:rr_1"])
        assertTrue((SyncKind.BOOK to "t:FP") in r1.notApplied)
        // Positions need the chapter row, which an unfetched online book does not have yet.
        assertTrue((SyncKind.PROGRESS to "o:rr_1") in r1.notApplied)
        assertTrue((SyncKind.BOOKMARK to "o:rr_1|2|7") in r1.notApplied)
        assertTrue((SyncKind.HIGHLIGHT to "o:rr_1|2|3|4|9") in r1.notApplied)
        assertTrue((SyncKind.STAT to "o:rr_1|4000|2") in r1.notApplied)
        // These do not need a chapter.
        assertNotNull(r1.applied[SyncKind.STATUS to "o:rr_1"])
        assertNotNull(r1.applied[SyncKind.LOOK to "o:rr_1"])
        assertNotNull(r1.applied[SyncKind.CATEGORY to "cultivation"])
        assertNotNull(r1.applied[SyncKind.CATLINK to "o:rr_1|cultivation"])

        // The chapter list is fetched later: the retry applies the rest.
        chapters(b, "rr_1", 2)
        val (_, r2) = round(storeB, "B", fileA)
        assertTrue(r2.notApplied.filter { it.first != SyncKind.BOOK }.isEmpty())

        val snapA = storeA.snapshot().filter { it.key != "t:FP" }.associateBy { it.kind to it.key }
        val snapB = storeB.snapshot().associateBy { it.kind to it.key }
        assertEquals(snapA.keys, snapB.keys)
        for ((k, la) in snapA) assertEquals("digest of $k", la.digest, snapB.getValue(k).digest)
        val bm = snapB.getValue(SyncKind.BOOKMARK to "o:rr_1|2|7").payload!!
        assertEquals("snip", bm.get("snippet").asString)
        assertEquals("n1", bm.get("note").asString)
        val hl = snapB.getValue(SyncKind.HIGHLIGHT to "o:rr_1|2|3|4|9").payload!!
        assertEquals("quote", hl.get("text").asString)
        assertEquals("BLUE", hl.get("color").asString)
        val prog = snapB.getValue(SyncKind.PROGRESS to "o:rr_1")
        assertEquals(2, prog.payload!!.get("chapterNumber").asInt)
        assertEquals(5, prog.payload!!.get("paragraph").asInt)
        assertEquals(5000L, prog.editedAt)
        assertEquals("rr_1_ch2", b.readingProgressDao().getProgress("rr_1")!!.currentChapterId)
        assertEquals("READING", snapB.getValue(SyncKind.STATUS to "o:rr_1").payload!!.get("status").asString)

        // No phantom edit: everything applied is followed by a merge with zero changes.
        val (r3, rep3) = round(storeB, "B", fileA)
        assertEquals(MergeCounts(), r3.counts)
        // Only the imported book that has no copy on B is re-offered (and stays not applied).
        assertEquals(listOf(SyncKind.BOOK to "t:FP"), r3.changes.map { it.record.kind to it.record.key })
        assertEquals(setOf(SyncKind.BOOK to "t:FP"), rep3.notApplied)
        assertTrue(rep3.applied.isEmpty())
        // ... and B publishes nothing that differs from A's records for the same keys.
        val aRecs = fileA.records.associateBy { it.kind to it.key }
        for (rec in r3.records) aRecs[rec.kind to rec.key]?.let { assertEquals(it.hlc, rec.hlc) }
    }

    @Test
    fun deletionRoundTrip_removesBookOnOtherDevice_andSettles() = runBlocking {
        seedOnline(a)
        val file1 = fileOf(storeA, "A")
        chapters(b, "rr_1")
        round(storeB, "B", file1)
        assertEquals(1, b.novelDao().getAllNovelsOnce().size)

        NovelRepository(a).removeFromLibrary("rr_1")
        val file2 = fileOf(storeA, "A")
        assertTrue(file2.records.any { it.kind == SyncKind.BOOK && it.deleted })

        val (_, rep) = round(storeB, "B", file2)
        assertTrue(b.novelDao().getAllNovelsOnce().isEmpty())
        assertEquals(0, b.bookmarkDao().getAllBookmarksOnce().size)
        assertNotNull(rep.applied[SyncKind.BOOK to "o:rr_1"])
        val (again, _) = round(storeB, "B", file2)
        // The tombstone may be re-offered; it is already satisfied and changes nothing.
        assertEquals(MergeCounts(), again.counts)
        // Stats are never deleted, so the other device's stat is re-offered and stays unapplied.
        assertTrue(again.changes.all { it is LocalChange.Delete || it.record.kind == SyncKind.STAT })
        assertTrue(b.novelDao().getAllNovelsOnce().isEmpty())
    }

    // ---- imported books -----------------------------------------------------------------------

    @Test
    fun importedBookWithoutLocalCopy_isNotApplied_andNothingIsCreated() = runBlocking {
        val report = storeB.apply(listOf(
            importedBook(),
            up(SyncKind.PROGRESS, "t:FP", "chapterNumber" to 2, "paragraph" to 1, "chaptersRead" to 1, "lastReadAt" to 5L),
            up(SyncKind.BOOKMARK, "t:FP|2|7", "chapterNumber" to 2, "paragraph" to 7, "note" to "x"),
            up(SyncKind.STATUS, "t:FP", "status" to "READING"),
        ))
        assertTrue(report.applied.isEmpty())
        assertEquals(4, report.notApplied.size)
        assertTrue(b.novelDao().getAllNovelsOnce().isEmpty())
        assertTrue(b.chapterDao().getChaptersForNovelOnce("local_x").isEmpty())
        assertTrue(b.bookmarkDao().getAllBookmarksOnce().isEmpty())
    }

    @Test
    fun importedBookPresentUnderDifferentId_isLinked_andReceivesChildren() = runBlocking {
        a.novelDao().insertNovel(novel("local_x", "local", fp = "FP", hash = "HASH", uri = "content://f"))
        chapters(a, "local_x", 2)
        seedChildren(a, "local_x", "local_x_ch2")
        val fileA = fileOf(storeA, "A")

        b.novelDao().insertNovel(novel("local_y", "local", fp = "FP", hash = "HASH"))
        chapters(b, "local_y", 1, 2)
        val (_, rep) = round(storeB, "B", fileA)
        assertEquals("local_y", rep.applied.getValue(SyncKind.PROGRESS to "t:FP").localId)
        assertEquals(1, b.novelDao().getAllNovelsOnce().size)
        assertEquals("local_y_ch2", b.readingProgressDao().getProgress("local_y")!!.currentChapterId)
        assertEquals(5, b.readingProgressDao().getProgress("local_y")!!.paragraphIndex)
        assertEquals("n1", b.bookmarkDao().getBookmarksForNovelOnce("local_y").single().note)
        assertEquals("local_y_ch2", b.bookmarkDao().getBookmarksForNovelOnce("local_y").single().chapterId)
        assertEquals("quote", b.highlightDao().getHighlightsForNovelOnce("local_y").single().selectedText)
        assertEquals(MergeCounts(), round(storeB, "B", fileA).first.counts)
    }

    @Test
    fun importedBookMatchedByHash_keepsTheRemoteKeyAfterSavingTheShadow() = runBlocking {
        // Local copy has a different fingerprint but the same file hash.
        b.novelDao().insertNovel(novel("local_y", "local", fp = "OTHER", hash = "HASH"))
        chapters(b, "local_y", 2)
        val changes = listOf(
            importedBook(),
            up(SyncKind.BOOKMARK, "t:FP|2|7", "chapterNumber" to 2, "paragraph" to 7, "snippet" to "s", "note" to "x"),
        )
        val report = storeB.apply(changes)
        assertEquals("local_y", report.applied.getValue(SyncKind.BOOK to "t:FP").localId)
        assertEquals("local_y", report.applied.getValue(SyncKind.BOOKMARK to "t:FP|2|7").localId)

        val shadow = changes.map { ShadowEntry(it.record, applied = false) }
        storeB.saveShadow(report.applyTo(shadow))
        val snap = storeB.snapshot().associateBy { it.kind to it.key }
        assertNotNull(snap[SyncKind.BOOK to "t:FP"])
        assertEquals(
            report.applied.getValue(SyncKind.BOOKMARK to "t:FP|2|7").digest,
            snap.getValue(SyncKind.BOOKMARK to "t:FP|2|7").digest,
        )
    }

    // ---- idempotence and kinds ----------------------------------------------------------------

    @Test
    fun upsertsAreIdempotent() = runBlocking {
        b.novelDao().insertNovel(novel("rr_1", "Royal Road"))
        chapters(b, "rr_1", 2)
        val changes = listOf(
            onlineBook(),
            up(SyncKind.STATUS, "o:rr_1", "status" to "ON_HOLD"),
            up(SyncKind.LOOK, "o:rr_1", "json" to "{}"),
            up(SyncKind.PROGRESS, "o:rr_1", "chapterNumber" to 2, "paragraph" to 4, "chaptersRead" to 2, "lastReadAt" to 9L),
            up(SyncKind.BOOKMARK, "o:rr_1|2|7", "chapterNumber" to 2, "paragraph" to 7, "snippet" to "s", "note" to "n"),
            up(SyncKind.HIGHLIGHT, "o:rr_1|2|3|4|9", "chapterNumber" to 2, "paragraph" to 3, "start" to 4, "end" to 9,
                "text" to "q", "note" to null, "color" to "PINK", "createdAt" to 1L, "updatedAt" to 2L),
            up(SyncKind.CATEGORY, "cultivation", "name" to "Cultivation", "createdAt" to 5L),
            up(SyncKind.CATLINK, "o:rr_1|cultivation", "category" to "Cultivation"),
            up(SyncKind.STAT, "o:rr_1|4000|2", "chapterNumber" to 2, "words" to 10, "ms" to 20L, "completedAt" to 4000L),
        )
        val r1 = storeB.apply(changes)
        val r2 = storeB.apply(changes)
        assertEquals(r1, r2)
        assertTrue(r1.notApplied.isEmpty())
        assertEquals(1, b.bookmarkDao().getAllBookmarksOnce().size)
        assertEquals(1, b.highlightDao().getAllHighlightsOnce().size)
        assertEquals(1, b.novelDao().getAllCategoriesOnce().size)
        assertEquals(1, b.novelDao().getAllCrossRefsOnce().size)
        assertEquals(1, b.novelDao().getStatSyncRows().size)
        assertEquals(1, b.novelDao().getAllProgressOnce().size)
        assertNull(b.highlightDao().getAllHighlightsOnce().single().note)
        assertEquals("PINK", b.highlightDao().getAllHighlightsOnce().single().color)
    }

    @Test
    fun editedBookmarkAndHighlightUpdateTheRowWithTheSameKey() = runBlocking {
        seedOnline(b)
        val bmId = b.bookmarkDao().getAllBookmarksOnce().single().id
        val hlId = b.highlightDao().getAllHighlightsOnce().single().id
        storeB.apply(listOf(
            up(SyncKind.BOOKMARK, "o:rr_1|2|7", "chapterNumber" to 2, "paragraph" to 7, "note" to "new"),
            up(SyncKind.HIGHLIGHT, "o:rr_1|2|3|4|9", "chapterNumber" to 2, "paragraph" to 3, "start" to 4, "end" to 9,
                "note" to "newer", "color" to "GREEN", "updatedAt" to 9000L),
        ))
        val bm = b.bookmarkDao().getAllBookmarksOnce().single()
        assertEquals(bmId, bm.id)
        assertEquals("new", bm.note)
        val hl = b.highlightDao().getAllHighlightsOnce().single()
        assertEquals(hlId, hl.id)
        assertEquals("newer", hl.note)
        assertEquals("GREEN", hl.color)
        assertEquals(9000L, hl.updatedAt)
    }

    @Test
    fun childForUnknownBookIsNotApplied() = runBlocking {
        val report = storeB.apply(listOf(
            up(SyncKind.STATUS, "o:ghost", "status" to "READING"),
            up(SyncKind.CATLINK, "o:ghost|cultivation", "category" to "Cultivation"),
            up(SyncKind.STAT, "o:ghost|1|-1", "chapterNumber" to -1, "words" to 1, "ms" to 1L, "completedAt" to 1L),
        ))
        assertEquals(3, report.notApplied.size)
        assertTrue(b.novelDao().getAllCategoriesOnce().isEmpty())
        assertTrue(b.novelDao().getStatSyncRows().isEmpty())
    }

    @Test
    fun onlineBookPresentLocally_keepsItsOwnCoverAndFillsGaps() = runBlocking {
        b.novelDao().insertNovel(novel("rr_1", "Royal Road").copy(coverUrl = "file:///local/cover.jpg", description = "", totalChapters = 9))
        val report = storeB.apply(listOf(onlineBook()))
        assertNotNull(report.applied[SyncKind.BOOK to "o:rr_1"])
        val n = b.novelDao().getNovelById("rr_1")!!
        assertEquals("file:///local/cover.jpg", n.coverUrl)
        assertEquals("d", n.description)
        assertEquals(9, n.totalChapters)
    }

    @Test
    fun deletesForEveryKind_andForRowsAlreadyGone() = runBlocking {
        seedOnline(b)
        val keys = listOf(
            SyncKind.BOOKMARK to "o:rr_1|2|7", SyncKind.HIGHLIGHT to "o:rr_1|2|3|4|9",
            SyncKind.CATLINK to "o:rr_1|cultivation", SyncKind.CATEGORY to "cultivation",
        )
        val report = storeB.apply(keys.map { del(it.first, it.second) })
        assertTrue(keys.all { it in report.applied })
        assertTrue(b.bookmarkDao().getAllBookmarksOnce().isEmpty())
        assertTrue(b.highlightDao().getAllHighlightsOnce().isEmpty())
        assertTrue(b.novelDao().getAllCrossRefsOnce().isEmpty())
        assertTrue(b.novelDao().getAllCategoriesOnce().isEmpty())
        assertEquals(1, b.novelDao().getAllNovelsOnce().size)

        // Same again: already gone counts as applied.
        val again = storeB.apply(keys.map { del(it.first, it.second) } + del(SyncKind.BOOK, "o:never"))
        assertEquals(5, again.applied.size)
        assertTrue(again.notApplied.isEmpty())

        storeB.apply(listOf(del(SyncKind.STATUS, "o:rr_1"), del(SyncKind.LOOK, "o:rr_1"), del(SyncKind.PROGRESS, "o:rr_1")))
        val n = b.novelDao().getNovelById("rr_1")!!
        assertNull(n.readingStatus)
        assertNull(n.readerSettingsJson)
        assertNull(b.readingProgressDao().getProgress("rr_1"))
    }

    @Test
    fun bookDeleteRemovesItsChildrenAndLeavesOtherBooksAlone() = runBlocking {
        seedOnline(b)
        b.novelDao().insertNovel(novel("rr_2", "Royal Road"))
        chapters(b, "rr_2", 2)
        seedChildrenOnly(b, "rr_2")
        val report = storeB.apply(listOf(del(SyncKind.BOOK, "o:rr_1")))
        assertNotNull(report.applied[SyncKind.BOOK to "o:rr_1"])
        assertEquals(listOf("rr_2"), b.novelDao().getAllNovelsOnce().map { it.id })
        assertEquals(0, b.chapterDao().getChapterCount("rr_1"))
        assertNull(b.readingProgressDao().getProgress("rr_1"))
        assertEquals(listOf("rr_2"), b.bookmarkDao().getAllBookmarksOnce().map { it.novelId })
        assertEquals(listOf("rr_2"), b.highlightDao().getAllHighlightsOnce().map { it.novelId })
        assertEquals(1, b.chapterDao().getChapterCount("rr_2"))
        assertNotNull(b.readingProgressDao().getProgress("rr_2"))
    }

    private suspend fun seedChildrenOnly(db: AppDatabase, id: String) {
        db.novelDao().insertProgressReplace(ReadingProgressEntity(id, "${id}_ch2", 2, 1, 1, 10L))
        db.bookmarkDao().insertBookmark(BookmarkEntity(
            novelId = id, chapterId = "${id}_ch2", chapterUrl = "u", chapterNumber = 2,
            chapterTitle = "t", paragraphIndex = 1, textSnippet = "s",
        ))
        db.highlightDao().insertHighlight(HighlightEntity(
            novelId = id, chapterId = "${id}_ch2", chapterNumber = 2, chapterTitle = "t",
            paragraphIndex = 1, startOffset = 0, endOffset = 2, selectedText = "ab",
        ))
    }

    @Test
    fun syncDrivenDeleteOfFolderBook_isRememberedSoTheScanDoesNotReimportIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ABooks/document/primary%3ABooks%2Fa.epub"
        b.novelDao().insertNovel(novel("local_y", "local", fp = "FP", hash = "HASH", uri = uri))
        val store = RoomSyncStore(b, LibraryRemover(NovelRepository(b), context) { })
        LibraryFolder.clearIgnoredUris(context)
        val report = store.apply(listOf(del(SyncKind.BOOK, "t:FP")))
        assertNotNull(report.applied[SyncKind.BOOK to "t:FP"])
        assertTrue(b.novelDao().getAllNovelsOnce().isEmpty())
        // The ignore record is written before the commit, so it exists even if the effects never run
        // (cancellation, process death); running the effects afterwards changes nothing.
        assertTrue(uri in LibraryFolder.getIgnoredUris(context))
        report.sideEffects.forEach { it() }
        assertTrue(uri in LibraryFolder.getIgnoredUris(context))
    }

    @Test
    fun applyInsideTheEngineTransactionIsRolledBackWithIt() = runBlocking {
        try {
            storeB.transaction {
                storeB.apply(listOf(onlineBook()))
                error("engine failed")
            }
        } catch (_: IllegalStateException) {
        }
        assertTrue(b.novelDao().getAllNovelsOnce().isEmpty())
        assertFalse(digests(storeB).isNotEmpty())
    }

    @Test
    fun duplicateBookmarks_applyAndSnapshotPickTheSameRow() = runBlocking {
        b.novelDao().insertNovel(novel("rr_1", "Royal Road"))
        chapters(b, "rr_1", 2)
        for ((note, created) in listOf("first" to 9000L, "second" to 1000L)) {
            b.bookmarkDao().insertBookmark(BookmarkEntity(
                novelId = "rr_1", chapterId = "rr_1_ch2", chapterUrl = "u", chapterNumber = 2,
                chapterTitle = "Chapter 2", paragraphIndex = 7, textSnippet = "s", note = note, createdAt = created,
            ))
        }
        val key = "o:rr_1|2|7"
        assertEquals("first", b.bookmarkDao().getAllBookmarksOnce().minByOrNull { it.id }!!.note)
        val before = digests(storeB)
        val report = storeB.transaction {
            storeB.snapshot()
            storeB.apply(listOf(up(SyncKind.BOOKMARK, key, "chapterNumber" to 2, "paragraph" to 7, "note" to "remote")))
        }
        // The row the snapshot reports is the one apply wrote: the digest it returns equals the next snapshot's.
        assertEquals(report.applied[SyncKind.BOOKMARK to key]!!.digest, digests(storeB)[SyncKind.BOOKMARK to key])
        assertEquals("remote", b.bookmarkDao().getAllBookmarksOnce().minByOrNull { it.id }!!.note)
        assertNotNull(before)
    }
}
