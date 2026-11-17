package com.abhinavxt.novelforge.data.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.database.BookmarkEntity
import com.abhinavxt.novelforge.data.database.CategoryEntity
import com.abhinavxt.novelforge.data.database.ChapterEntity
import com.abhinavxt.novelforge.data.database.HighlightEntity
import com.abhinavxt.novelforge.data.database.NovelCategoryCrossRef
import com.abhinavxt.novelforge.data.database.NovelEntity
import com.abhinavxt.novelforge.data.database.ReadingProgressEntity
import com.abhinavxt.novelforge.data.database.ReadingStatEvent
import com.abhinavxt.novelforge.data.database.SyncRecordEntity
import com.abhinavxt.novelforge.data.epub.LocalBookArchive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class RoomSyncStoreSnapshotTest {

    private lateinit var db: AppDatabase
    private lateinit var store: RoomSyncStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        store = RoomSyncStore(db)
    }

    @After
    fun tearDown() = db.close()

    private fun novel(id: String, source: String, fp: String? = null, hash: String? = null, uri: String? = null) =
        NovelEntity(
            id = id, title = "Title $id", author = "Au", coverUrl = "http://c/$id.jpg", description = "d",
            source = source, status = "Ongoing", totalChapters = 3, addedToLibraryAt = 1000L,
            sourceUri = uri, contentHash = hash, textFingerprint = fp, language = "en",
        )

    /** One online book "rr_1" and one imported "local_x" with every kind of child on the online one. */
    private suspend fun seed() {
        db.novelDao().insertNovel(novel("rr_1", "Royal Road"))
        db.novelDao().insertNovel(novel("local_x", "local", fp = "FP", hash = "HASH", uri = "content://f"))
        db.novelDao().setReadingStatus("rr_1", "READING")
        db.novelDao().setReaderSettingsJson("rr_1", "{\"fontSize\":20}")
        db.chapterDao().insertChapter(ChapterEntity("rr_1_ch2", "rr_1", 2, "Two", "http://u/2"))
        db.novelDao().insertProgressReplace(ReadingProgressEntity("rr_1", "rr_1_ch2", 2, 5, 1, 5000L))
        db.bookmarkDao().insertBookmark(BookmarkEntity(
            novelId = "rr_1", chapterId = "rr_1_ch2", chapterUrl = "http://u/2", chapterNumber = 2,
            chapterTitle = "Two", paragraphIndex = 7, textSnippet = "snip", note = "n1", createdAt = 2000L,
        ))
        db.highlightDao().insertHighlight(HighlightEntity(
            novelId = "rr_1", chapterId = "rr_1_ch2", chapterNumber = 2, chapterTitle = "Two",
            paragraphIndex = 3, startOffset = 4, endOffset = 9, selectedText = "quote", note = "hn",
            color = "BLUE", createdAt = 2500L, updatedAt = 3000L,
        ))
        val cat = db.categoryDao().insertCategory(CategoryEntity(name = "Cultivation", createdAt = 10L))
        db.categoryDao().insertCrossRefs(listOf(NovelCategoryCrossRef("rr_1", cat)))
        db.readingStatDao().insertEvent(ReadingStatEvent(
            novelId = "rr_1", chapterId = "rr_1_ch2", wordsRead = 100, readingTimeMs = 60000L, completedAt = 4000L,
        ))
    }

    private suspend fun byKind(kind: String) = store.snapshot().filter { it.kind == kind }

    @Test
    fun everyKindAppearsWithKeyAndPayload() = runBlocking {
        seed()
        val snap = store.snapshot()
        val byKey = snap.associateBy { it.kind to it.key }

        val online = byKey.getValue(SyncKind.BOOK to "o:rr_1")
        assertEquals("http://c/rr_1.jpg", online.payload!!.get("coverUrl").asString)
        assertEquals(1000L, online.createdAt)
        val imported = byKey.getValue(SyncKind.BOOK to "t:FP")
        assertEquals("HASH", imported.payload!!.get("contentHash").asString)
        assertEquals("FP", imported.payload!!.get("textFingerprint").asString)
        assertFalse(imported.payload!!.has("coverUrl"))
        assertFalse(imported.payload!!.toString().contains("content://f"))

        assertEquals("READING", byKey.getValue(SyncKind.STATUS to "o:rr_1").payload!!.get("status").asString)
        assertEquals("{\"fontSize\":20}", byKey.getValue(SyncKind.LOOK to "o:rr_1").payload!!.get("json").asString)

        val prog = byKey.getValue(SyncKind.PROGRESS to "o:rr_1")
        assertEquals(2, prog.payload!!.get("chapterNumber").asInt)
        assertEquals(5, prog.payload!!.get("paragraph").asInt)
        assertEquals(5000L, prog.editedAt)

        val bm = byKey.getValue(SyncKind.BOOKMARK to "o:rr_1|2|7")
        assertEquals("n1", bm.payload!!.get("note").asString)
        assertEquals("snip", bm.payload!!.get("snippet").asString)

        val hl = byKey.getValue(SyncKind.HIGHLIGHT to "o:rr_1|2|3|4|9")
        assertEquals("BLUE", hl.payload!!.get("color").asString)
        assertEquals("quote", hl.payload!!.get("text").asString)
        assertEquals(3000L, hl.editedAt)
        assertEquals(2500L, hl.createdAt)

        assertEquals("Cultivation", byKey.getValue(SyncKind.CATEGORY to "cultivation").payload!!.get("name").asString)
        assertNotNull(byKey[SyncKind.CATLINK to "o:rr_1|cultivation"])
        assertNotNull(byKey[SyncKind.STAT to "o:rr_1|4000|2"])
        // no chapter id leaks into any payload or key
        assertTrue(snap.none { it.payload.toString().contains("_ch") || it.key.contains("_ch") })
    }


    @Test
    fun digestsStableWithoutChanges() = runBlocking {
        seed()
        assertEquals(store.snapshot(), store.snapshot())
    }

    private fun digestOf(snap: List<LocalRecord>, kind: String, key: String) =
        snap.single { it.kind == kind && it.key == key }.digest

    @Test
    fun digestsChangeAfterEachUserEdit() = runBlocking {
        seed()
        val before = store.snapshot()
        val hid = db.highlightDao().getAllHighlightsOnce().single().id
        val bid = db.bookmarkDao().getAllBookmarksOnce().single().id

        db.novelDao().setReadingStatus("rr_1", "DROPPED")
        db.novelDao().setReaderSettingsJson("rr_1", "{\"fontSize\":22}")
        db.readingProgressDao().updateProgress("rr_1", "rr_1_ch2", 2, 6, 9999L)
        db.bookmarkDao().updateNote(bid, "changed")
        db.highlightDao().updateColor(hid, "PINK", 9000L)
        val after = store.snapshot()

        for ((kind, key) in listOf(
            SyncKind.STATUS to "o:rr_1", SyncKind.LOOK to "o:rr_1", SyncKind.PROGRESS to "o:rr_1",
            SyncKind.BOOKMARK to "o:rr_1|2|7", SyncKind.HIGHLIGHT to "o:rr_1|2|3|4|9",
        )) {
            assertNotEquals(kind, digestOf(before, kind, key), digestOf(after, kind, key))
        }
        // untouched kinds keep their digest
        for ((kind, key) in listOf(SyncKind.BOOK to "o:rr_1", SyncKind.CATEGORY to "cultivation")) {
            assertEquals(kind, digestOf(before, kind, key), digestOf(after, kind, key))
        }
    }

    @Test
    fun progressDigestIgnoresTimestampOnlyChange() = runBlocking {
        seed()
        val d = digestOf(store.snapshot(), SyncKind.PROGRESS, "o:rr_1")
        db.readingProgressDao().incrementChaptersRead("rr_1", 123456L)
        assertEquals(d, digestOf(store.snapshot(), SyncKind.PROGRESS, "o:rr_1"))
    }

    @Test
    fun deletedRowDisappears() = runBlocking {
        seed()
        db.bookmarkDao().deleteBookmark(db.bookmarkDao().getAllBookmarksOnce().single().id)
        db.novelDao().setReadingStatus("rr_1", null)
        val snap = store.snapshot()
        assertTrue(snap.none { it.kind == SyncKind.BOOKMARK || it.kind == SyncKind.STATUS })
        db.novelDao().deleteNovelById("rr_1")
        assertNull(store.snapshot().firstOrNull { it.key.startsWith("o:rr_1") })
    }

    @Test
    fun shadowRoundTripKeepsAppliedFlagAndDigest() = runBlocking {
        seed()
        val rec = { kind: String, key: String -> SyncRecord(kind, key, 5L, "devA", false, null) }
        val entries = listOf(
            ShadowEntry(rec(SyncKind.BOOK, "o:rr_1"), applied = true, digest = "d1"),
            ShadowEntry(rec(SyncKind.BOOK, "t:FP"), applied = true, digest = "d2"),
            ShadowEntry(rec(SyncKind.BOOK, "t:NOPE"), applied = false, digest = null),
            ShadowEntry(rec(SyncKind.BOOKMARK, "t:FP|1|1"), applied = true, digest = "d3"),
            ShadowEntry(SyncRecord(SyncKind.BOOK, "o:gone", 6L, "devB", true, null), applied = false, digest = null),
        )
        store.saveShadow(entries)
        val back = store.shadow().associateBy { it.record.kind to it.record.key }
        assertEquals(5, back.size)
        assertEquals(true, back.getValue(SyncKind.BOOK to "o:rr_1").applied)
        assertEquals("d1", back.getValue(SyncKind.BOOK to "o:rr_1").digest)
        assertEquals(false, back.getValue(SyncKind.BOOK to "t:NOPE").applied)
        assertNull(back.getValue(SyncKind.BOOK to "t:NOPE").digest)
        assertTrue(back.getValue(SyncKind.BOOK to "o:gone").record.deleted)
        // links resolve to the real local novel id
        assertEquals("local_x", db.syncDao().get(SyncKind.BOOK, "t:FP")!!.localId)
        assertEquals("local_x", db.syncDao().get(SyncKind.BOOKMARK, "t:FP|1|1")!!.localId)
        assertEquals("rr_1", db.syncDao().get(SyncKind.BOOK, "o:rr_1")!!.localId)
    }

    @Test
    fun linkedKeySurvivesFingerprintChange() = runBlocking {
        db.novelDao().insertNovel(novel("local_x", "local", fp = "OLD"))
        store.saveShadow(listOf(ShadowEntry(SyncRecord(SyncKind.BOOK, "t:OLD", 1L, "d", false, null), true, "x")))
        db.novelDao().setFingerprint("local_x", "NEW")
        assertEquals("t:OLD", byKind(SyncKind.BOOK).single().key)
    }

    @Test
    fun fingerprintBackfilledFromStoredText() = runBlocking {
        db.novelDao().insertNovel(novel("local_z", "local", hash = "H").copy(totalChapters = 2))
        db.chapterDao().insertChapters(listOf(
            ChapterEntity("local_z_ch1", "local_z", 1, "a", "", true, "alpha text"),
            ChapterEntity("local_z_ch0", "local_z", 0, "b", "", true, "zero text"),
        ))
        val expected = LocalBookArchive.fingerprint(listOf("zero text", "alpha text"))
        val rec = byKind(SyncKind.BOOK).single()
        assertEquals("t:$expected", rec.key)
        assertEquals(expected, db.novelDao().getNovelById("local_z")!!.textFingerprint)
    }

    @Test
    fun fingerprintBackfillSkipsPartialText() = runBlocking {
        db.novelDao().insertNovel(novel("local_p", "local", hash = "H"))
        db.chapterDao().insertChapters(listOf(
            ChapterEntity("local_p_ch0", "local_p", 0, "a", "", true, "text"),
            ChapterEntity("local_p_ch1", "local_p", 1, "b", "", false, null),
        ))
        assertEquals("h:H", byKind(SyncKind.BOOK).single().key)
    }

    @Test
    fun importedWithNothingToIdentifyItIsSkipped() = runBlocking {
        db.novelDao().insertNovel(novel("local_n", "local"))
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun mergingTwiceCausesNoPhantomEdits() = runBlocking {
        seed()
        val clock = HybridClock(now = { 100_000L }, offset = { 0L })
        suspend fun round(): MergeResult {
            val res = SyncMerger.merge(store.shadow(), store.snapshot(), emptyList(), clock, "devA")
            store.saveShadow(res.shadow)
            return res
        }
        val first = round()
        assertTrue(first.counts.localEdits > 0)
        val second = round()
        assertTrue(second.changes.isEmpty())
        assertEquals(MergeCounts(), second.counts)
        assertEquals(first.records, second.records)
        val third = round()
        assertEquals(first.records, third.records)
        assertTrue(third.shadow.all { it.applied })
    }

    @Test
    fun novelLinkedUnderTwoKeys_isReportedUnderTheLowestHlcKey_andNeitherKeyIsMissing() = runBlocking {
        db.novelDao().insertNovel(novel("local_x", "local", fp = "FP2", hash = "H"))
        db.chapterDao().insertChapter(ChapterEntity("local_x_ch2", "local_x", 2, "Two", "u"))
        db.novelDao().insertProgressReplace(ReadingProgressEntity("local_x", "local_x_ch2", 2, 5, 1, 5000L))
        // Inserted high-hlc key first so insertion order cannot decide.
        db.syncDao().upsertAll(listOf(
            SyncRecordEntity(SyncKind.BOOK, "t:K1", 9L shl 16, "o", false, "{}", "local_x", "d"),
            SyncRecordEntity(SyncKind.BOOK, "t:K2", 3L shl 16, "o", false, "{}", "local_x", "d"),
        ))
        val snap = store.snapshot()
        assertEquals(setOf("t:K1", "t:K2"), snap.filter { it.kind == SyncKind.BOOK }.map { it.key }.toSet())
        assertEquals(listOf("t:K2"), snap.filter { it.kind == SyncKind.PROGRESS }.map { it.key })
        // Same answer on every call.
        assertEquals(snap.map { it.kind to it.key }, store.snapshot().map { it.kind to it.key })
    }

    @Test
    fun statWithoutAChapterRow_isNotReported() = runBlocking {
        seed()
        db.readingStatDao().insertEvent(ReadingStatEvent(
            novelId = "rr_1", chapterId = "ghost_chapter", wordsRead = 5, readingTimeMs = 10L, completedAt = 4500L,
        ))
        val stats = byKind(SyncKind.STAT)
        assertEquals(1, stats.size)
        assertTrue(stats.none { it.key.contains("-1") })
    }
}
