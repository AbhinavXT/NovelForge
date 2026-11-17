package com.abhinavxt.novelforge.data.sync

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.novelforge.data.LibraryRemover
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.database.BookmarkEntity
import com.abhinavxt.novelforge.data.database.ChapterEntity
import com.abhinavxt.novelforge.data.database.HighlightEntity
import com.abhinavxt.novelforge.data.database.NovelEntity
import com.abhinavxt.novelforge.data.database.ReadingProgressEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncEngineTest {

    /** A context whose preferences and no-backup directory belong to one simulated device. */
    private class DeviceContext(base: Context, private val tag: String) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("$tag-$name", mode)
        override fun getNoBackupFilesDir(): File = File(baseContext.noBackupFilesDir, tag).also { it.mkdirs() }
    }

    private class Injector : Interceptor {
        var fail: ((Request) -> Boolean)? = null
        var failures = 0
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            if (fail?.invoke(req) == true) {
                failures++
                throw IOException("injected failure")
            }
            return chain.proceed(req)
        }
    }

    /** Records what a [LibraryRemover] does outside the database. */
    private class Effects {
        val ignored = ArrayList<String>()
        val deletedFiles = ArrayList<String>()
        val released = ArrayList<String>()
        /** Runs inside the file-deletion step of each removed local book (may cancel or throw). */
        var onFile: ((String) -> Unit)? = null
        fun none() = ignored.isEmpty() && deletedFiles.isEmpty() && released.isEmpty()
    }

    /** Lets a test make the transaction fail after apply, the last step before the commit. */
    private class FlakyStore(private val inner: SyncLocalStore) : SyncLocalStore by inner {
        var failSave = false
        var onSnapshot: (() -> Unit)? = null
        var oomOnSnapshot = false
        override suspend fun snapshot(): List<LocalRecord> {
            onSnapshot?.invoke()
            if (oomOnSnapshot) throw OutOfMemoryError("injected")
            return inner.snapshot()
        }
        override suspend fun saveShadow(entries: List<ShadowEntry>) {
            if (failSave) error("injected failure after apply")
            inner.saveShadow(entries)
        }
    }

    private inner class Device(val tag: String, val effects: Effects? = null) {
        val db: AppDatabase
        val ctx: Context
        val flaky: FlakyStore
        val store: RoomSyncStore
        val settings: SyncSettings
        val injector = Injector()
        var hook: suspend () -> Unit = {}
        val engine: SyncEngine

        init {
            ctx = DeviceContext(ApplicationProvider.getApplicationContext<Application>(), tag)
            db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
            val remover = if (effects == null) LibraryRemover(NovelRepository(db)) else
                LibraryRemover(NovelRepository(db), ctx) { effects.deletedFiles += it; effects.onFile?.invoke(it) }.also {
                    it.ignoreFolderUri = { uri -> effects.ignored += uri }
                    it.releaseFileAccess = { uri -> effects.released += uri }
                }
            store = RoomSyncStore(db, remover)
            flaky = FlakyStore(store)
            settings = SyncSettings(ctx, FakeSecretStore())
            settings.serverUrl = fake.base.toString()
            settings.username = "u"
            settings.password = "p"
            settings.deviceName = "Device $tag"
            val dav = WebDavClient(fake.base, fake.client(listOf(injector)), now = { t })
            val clock = HybridClock({ t }) { dav.serverDateOffsetMs ?: 0L }
            engine = SyncEngine(flaky, dav, clock, settings, "test", { t }, Mutex()) { hook() }
            devices += this
        }

        suspend fun sync(
            allowRemoteDeletions: Boolean = false,
            allowLocalDeletions: Boolean = false,
            keep: Boolean = false,
        ): SyncOutcome {
            t += 10_000
            return engine.sync(SyncTrigger.Manual, allowRemoteDeletions, allowLocalDeletions, keep)
        }

        suspend fun ok(): SyncOutcome.Success {
            val o = sync()
            assertTrue("expected success but was $o", o is SyncOutcome.Success)
            return o as SyncOutcome.Success
        }

        fun close() = db.close()

        suspend fun novelIds() = db.novelDao().getAllNovelsOnce().map { it.id }.sorted()
        suspend fun shadow() = db.syncDao().all()
    }

    private lateinit var fake: FakeWebDav
    private val devices = ArrayList<Device>()
    private var t = 1_700_000_000_000L

    @Before
    fun setUp() {
        fake = FakeWebDav().also { it.clock = { t } }
    }

    @After
    fun tearDown() {
        devices.forEach { it.close() }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private suspend fun addNovel(
        d: Device, id: String, source: String, fp: String? = null, hash: String? = null, chapters: Int = 3,
        uri: String? = null,
    ) {
        d.db.novelDao().insertNovel(
            NovelEntity(
                id = id, title = "Title $id", author = "Au", coverUrl = null, description = "d",
                source = source, status = "Ongoing", totalChapters = chapters, addedToLibraryAt = t,
                sourceUri = uri, contentHash = hash, textFingerprint = fp, language = "en",
            )
        )
        addChapters(d, id, chapters)
    }

    private suspend fun addChapters(d: Device, id: String, count: Int) {
        for (n in 1..count) d.db.chapterDao().insertChapter(
            ChapterEntity("${id}_ch$n", id, n, "Chapter $n", "http://u/$id/$n")
        )
    }

    private suspend fun setProgress(d: Device, id: String, chapter: Int, para: Int) {
        d.db.readingProgressDao().saveProgress(ReadingProgressEntity(id, "${id}_ch$chapter", chapter, para, 1, t))
    }

    private suspend fun addBookmark(d: Device, id: String, note: String?) {
        d.db.bookmarkDao().insertBookmark(
            BookmarkEntity(
                novelId = id, chapterId = "${id}_ch2", chapterUrl = "http://u/2", chapterNumber = 2,
                chapterTitle = "Chapter 2", paragraphIndex = 7, textSnippet = "snip", note = note, createdAt = t,
            )
        )
    }

    private suspend fun addHighlight(d: Device, id: String, note: String?) {
        d.db.highlightDao().insertHighlight(
            HighlightEntity(
                novelId = id, chapterId = "${id}_ch2", chapterNumber = 2, chapterTitle = "Chapter 2",
                paragraphIndex = 3, startOffset = 4, endOffset = 9, selectedText = "quote", note = note,
                color = "BLUE", createdAt = t, updatedAt = t,
            )
        )
    }

    private suspend fun content(d: Device): List<String> {
        val out = ArrayList<String>()
        for (n in d.db.novelDao().getAllNovelsOnce().sortedBy { it.id }) out += "novel ${n.id}"
        for (p in d.db.novelDao().getAllProgressOnce().sortedBy { it.novelId }) {
            out += "progress ${p.novelId} ${p.currentChapterNumber}/${p.paragraphIndex}"
        }
        for (b in d.db.bookmarkDao().getAllBookmarksOnce().sortedBy { it.id }) {
            out += "bookmark ${b.novelId} ${b.chapterNumber}/${b.paragraphIndex} ${b.note}"
        }
        for (h in d.db.highlightDao().getAllHighlightsOnce().sortedBy { it.id }) {
            out += "highlight ${h.novelId} ${h.chapterNumber}/${h.paragraphIndex}/${h.startOffset} ${h.note} ${h.color}"
        }
        return out
    }

    private fun puts() = fake.requestLog.filter { it.startsWith("PUT") || it.startsWith("MOVE") }

    private fun deviceFile(d: Device) = fake.files.entries.single { it.key.endsWith("/${d.settings.deviceId}.json.gz") }.value

    private fun leftoverTemps() = fake.files.keys.filter { ".tmp-" in it }

    // ---- convergence ------------------------------------------------------------------------

    @Test
    fun onlineBookConvergesBothWays_andASettledSyncDoesNotUpload() = runBlocking {
        val a = Device("a"); val b = Device("b")
        addNovel(a, "rr_1", "Royal Road")
        setProgress(a, "rr_1", 2, 5)
        addBookmark(a, "rr_1", "n1")
        addHighlight(a, "rr_1", "hn")
        a.ok()
        b.ok()
        assertEquals(listOf("rr_1"), b.novelIds())
        // The chapter list is fetched later on B; then the position and marks arrive.
        addChapters(b, "rr_1", 3)
        b.ok()
        assertEquals(content(a), content(b))
        assertEquals(5, b.db.readingProgressDao().getProgress("rr_1")!!.paragraphIndex)

        // B moves the position and edits the highlight note.
        t += 60_000
        setProgress(b, "rr_1", 3, 1)
        b.db.highlightDao().updateNote(b.db.highlightDao().getAllHighlightsOnce().single().id, "edited", t)
        b.ok()
        a.ok()
        assertEquals(3, a.db.readingProgressDao().getProgress("rr_1")!!.currentChapterNumber)
        assertEquals("edited", a.db.highlightDao().getAllHighlightsOnce().single().note)
        assertEquals(content(a), content(b))

        // Settle, then a round with nothing to say writes nothing.
        b.ok(); a.ok(); b.ok()
        fake.requestLog.clear()
        b.ok(); a.ok()
        assertEquals(emptyList<String>(), puts())
        assertEquals(1, a.db.bookmarkDao().getAllBookmarksOnce().size)
        assertEquals(1, b.db.highlightDao().getAllHighlightsOnce().size)
    }

    @Test
    fun deletionsPropagate_andNothingComesBack() = runBlocking {
        val a = Device("a"); val b = Device("b")
        addNovel(a, "rr_1", "Royal Road")
        addNovel(a, "rr_2", "Royal Road")
        addHighlight(a, "rr_2", "hn")
        a.ok(); b.ok()
        addChapters(b, "rr_1", 3); addChapters(b, "rr_2", 3)
        b.ok(); a.ok()
        assertEquals(1, b.db.highlightDao().getAllHighlightsOnce().size)

        // A removes a book; B removes a highlight.
        t += 60_000
        NovelRepository(a.db).removeFromLibrary("rr_1")
        b.db.highlightDao().deleteHighlight(b.db.highlightDao().getAllHighlightsOnce().single().id)
        a.ok(); b.ok(); a.ok(); b.ok()
        assertEquals(listOf("rr_2"), b.novelIds())
        assertEquals(listOf("rr_2"), a.novelIds())
        assertTrue(a.db.highlightDao().getAllHighlightsOnce().isEmpty())
        a.ok(); b.ok(); a.ok()
        assertEquals(listOf("rr_2"), a.novelIds())
        assertEquals(listOf("rr_2"), b.novelIds())
        assertTrue(b.db.highlightDao().getAllHighlightsOnce().isEmpty())
    }

    @Test
    fun importedBookConverges_andABookOnOneDeviceStaysUnappliedAndUntombstoned() = runBlocking {
        val a = Device("a"); val b = Device("b")
        addNovel(a, "local_a", "local", fp = "F", hash = "H")
        addNovel(b, "local_b", "local", fp = "F", hash = "H")
        addNovel(a, "local_only", "local", fp = "G", hash = "HG")
        setProgress(a, "local_a", 2, 5)
        addBookmark(a, "local_a", "n1")
        addHighlight(a, "local_a", "hn")
        a.ok(); b.ok()
        assertEquals(5, b.db.readingProgressDao().getProgress("local_b")!!.paragraphIndex)
        assertEquals("n1", b.db.bookmarkDao().getBookmarksForNovelOnce("local_b").single().note)
        assertEquals("hn", b.db.highlightDao().getHighlightsForNovelOnce("local_b").single().note)

        t += 60_000
        setProgress(b, "local_b", 3, 2)
        b.ok(); a.ok()
        assertEquals(2, a.db.readingProgressDao().getProgress("local_a")!!.paragraphIndex)

        // The book only A has does not appear on B and B never tombstones it.
        assertEquals(listOf("local_b"), b.novelIds())
        val g = b.shadow().single { it.kind == SyncKind.BOOK && it.key == "t:G" }
        assertFalse(g.deleted)
        assertTrue(b.shadow().none { it.deleted })
        b.ok(); a.ok()
        assertEquals(listOf("local_a", "local_only"), a.novelIds())

        // Later B imports the same file: A's position is picked up.
        setProgress(a, "local_only", 2, 9)
        a.ok(); b.ok()
        addNovel(b, "local_g", "local", fp = "G", hash = "HG")
        b.ok()
        assertEquals(9, b.db.readingProgressDao().getProgress("local_g")!!.paragraphIndex)
        a.ok(); b.ok()
        assertEquals(listOf("local_a", "local_only"), a.novelIds())
        assertEquals(listOf("local_b", "local_g"), b.novelIds())
    }

    @Test
    fun sameFileWithDifferentFingerprintIsNeverLinked_andNothingIsDeleted() = runBlocking {
        val a = Device("a"); val b = Device("b")
        addNovel(a, "local_a", "local", fp = "F1", hash = "H")
        addNovel(b, "local_b", "local", fp = "F2", hash = "H")
        a.ok(); b.ok()
        for (i in 1..3) { a.ok(); b.ok() }
        assertEquals(listOf("local_a"), a.novelIds())
        assertEquals(listOf("local_b"), b.novelIds())
        for (d in listOf(a, b)) {
            assertTrue("tombstone on ${d.tag}", d.shadow().none { it.deleted })
        }
        // Each book is linked to its own key only.
        assertEquals(setOf("t:F1", "t:F2"), a.shadow().filter { it.kind == SyncKind.BOOK }.map { it.key }.toSet())
        assertEquals("local_a", a.shadow().single { it.key == "t:F1" }.localId)
        assertEquals("local_b", b.shadow().single { it.key == "t:F2" }.localId)
        assertNull(a.shadow().single { it.key == "t:F2" }.localId)
    }

    // ---- failures ---------------------------------------------------------------------------

    @Test
    fun networkFailureDuringDownloads_changesNothing_andRetryConverges() = runBlocking {
        val a = Device("a"); val b = Device("b")
        addNovel(a, "rr_1", "Royal Road")
        setProgress(a, "rr_1", 2, 5)
        a.ok()
        addNovel(b, "rr_2", "Royal Road")
        val before = content(b)
        b.injector.fail = { it.method == "GET" && it.url.encodedPath.endsWith(".json.gz") }
        val o = b.sync()
        assertTrue(o is SyncOutcome.ServerUnreachable)
        assertFalse((o as SyncOutcome.ServerUnreachable).message.contains("dav.example"))
        assertEquals(before, content(b))
        assertTrue(b.shadow().isEmpty())
        b.injector.fail = null
        b.ok(); a.ok()
        assertEquals(listOf("rr_1", "rr_2"), a.novelIds())
        assertEquals(listOf("rr_1", "rr_2"), b.novelIds())
    }

    @Test
    fun failureDuringPut_keepsLocalState_andTheNextSyncUploads() = runBlocking {
        val a = Device("a"); val b = Device("b")
        addNovel(a, "rr_1", "Royal Road")
        a.injector.fail = { it.method == "PUT" && ".tmp-" in it.url.encodedPath }
        assertTrue(a.sync() is SyncOutcome.ServerUnreachable)
        assertTrue(fake.files.keys.none { it.endsWith(".json.gz") })
        assertEquals(listOf("rr_1"), a.novelIds())
        a.injector.fail = null
        assertTrue(a.ok().uploaded)
        b.ok()
        assertEquals(listOf("rr_1"), b.novelIds())
    }

    @Test
    fun failureBetweenPutAndMove_leavesPreviousFileIntact_andRecovers() = runBlocking {
        val a = Device("a"); val b = Device("b")
        addNovel(a, "rr_1", "Royal Road")
        a.ok()
        val previous = deviceFile(a).copyOf()
        addNovel(a, "rr_2", "Royal Road")
        a.injector.fail = { it.method == "MOVE" }
        assertTrue(a.sync() is SyncOutcome.ServerUnreachable)
        assertTrue(previous.contentEquals(deviceFile(a)))
        assertEquals(1, leftoverTemps().size)
        a.injector.fail = null
        assertTrue(a.ok().uploaded)
        assertTrue(leftoverTemps().isEmpty())
        b.ok()
        assertEquals(listOf("rr_1", "rr_2"), b.novelIds())
        a.ok(); b.ok()
        assertEquals(1, a.db.novelDao().getAllNovelsOnce().count { it.id == "rr_1" })
    }

    @Test
    fun unsupportedMove_fallsBackToPut() = runBlocking {
        val a = Device("a"); val b = Device("b")
        fake.moveSupported = false
        addNovel(a, "rr_1", "Royal Road")
        assertTrue(a.ok().uploaded)
        assertTrue(leftoverTemps().isEmpty())
        b.ok()
        assertEquals(listOf("rr_1"), b.novelIds())
    }

    @Test
    fun authFailure_returnsAuthFailed_andTouchesNothing() = runBlocking {
        val a = Device("a")
        addNovel(a, "rr_1", "Royal Road")
        fake.requireAuth = true
        assertEquals(SyncOutcome.AuthFailed, a.sync())
        assertEquals(listOf("rr_1"), a.novelIds())
        assertTrue(a.shadow().isEmpty())
        assertTrue(fake.files.isEmpty())
    }

    @Test
    fun corruptDeviceFile_isSkipped_deletesNothing_andItsEtagIsNotRecorded() = runBlocking {
        val a = Device("a")
        addNovel(a, "rr_1", "Royal Road")
        a.ok()
        fake.seed("NovelForge/devices/zzz.json.gz", "not gzip".toByteArray())
        val o = a.ok()
        assertEquals(listOf("rr_1"), a.novelIds())
        assertEquals(1, o.devicesSeen)
        assertNull(a.settings.knownEtags()["zzz.json.gz"])
        assertTrue(a.shadow().none { it.deleted })
    }

    @Test
    fun notConfigured_returnsNotConfigured() = runBlocking {
        val a = Device("a")
        a.settings.password = null
        assertEquals(SyncOutcome.NotConfigured, a.sync())
        assertTrue(fake.requestLog.isEmpty())
    }

    // ---- guard ------------------------------------------------------------------------------

    @Test
    fun massDeletionGuard_uploadsNothing_untilConfirmed() = runBlocking {
        val a = Device("a"); val b = Device("b")
        for (i in 1..8) addNovel(a, "rr_$i", "Royal Road")
        a.ok(); b.ok()
        val published = deviceFile(a).copyOf()
        val shadowBefore = a.shadow()
        for (i in 2..8) NovelRepository(a.db).removeFromLibrary("rr_$i")
        fake.requestLog.clear()
        val o = a.sync()
        assertEquals(SyncOutcome.GuardTripped(7), o)
        assertTrue(puts().isEmpty())
        assertTrue(published.contentEquals(deviceFile(a)))
        assertEquals(shadowBefore, a.shadow())

        val ok = a.sync(allowLocalDeletions = true)
        assertTrue("was $ok", ok is SyncOutcome.Success)
        assertTrue(a.shadow().none { it.key.contains("pad") })
        // The other device now sees 7 of its 8 books removed remotely: the guard works on that side too.
        fake.requestLog.clear()
        assertEquals(SyncOutcome.GuardTripped(7, fromRemote = true), b.sync())
        assertTrue("nothing uploaded", puts().isEmpty())
        assertEquals(8, b.novelIds().size)
        assertTrue("was a ok", b.sync(allowRemoteDeletions = true) is SyncOutcome.Success)
        assertEquals(listOf("rr_1"), b.novelIds())
        a.ok(); b.ok()
        assertEquals(listOf("rr_1"), a.novelIds())
    }

    // ---- formats ----------------------------------------------------------------------------

    @Test
    fun newerSyncJson_changesNothing() = runBlocking {
        val a = Device("a")
        addNovel(a, "rr_1", "Royal Road")
        fake.seed("NovelForge/sync.json", "{\"format\":2,\"created\":1}".toByteArray())
        fake.requestLog.clear()
        assertEquals(SyncOutcome.ServerFormatTooNew(2), a.sync())
        assertTrue(puts().isEmpty())
        assertTrue(a.shadow().isEmpty())
        assertEquals(listOf("rr_1"), a.novelIds())
    }

    @Test
    fun newerDeviceFile_isSkippedAndReported() = runBlocking {
        val a = Device("a")
        addNovel(a, "rr_1", "Royal Road")
        val future = SyncFile(format = 2, deviceId = "future", deviceName = "Tablet", appVersion = "9", writtenAt = t, records = emptyList())
        fake.seed("NovelForge/devices/future.json.gz", SyncFileCodec.encode(future))
        val o = a.ok()
        assertEquals(listOf("Tablet: needs a newer NovelForge"), o.skippedDevices)
        assertEquals(listOf("Tablet: needs a newer NovelForge"), a.settings.lastSkippedDevices)
        assertNull(a.settings.knownEtags()["future.json.gz"])
        assertEquals(listOf("rr_1"), a.novelIds())
    }

    @Test
    fun clockSkewBetweenDeviceAndServerIsReported() = runBlocking {
        val a = Device("a")
        addNovel(a, "rr_1", "Royal Road")
        val skewed = SyncFile(deviceId = "other", deviceName = "Old phone", appVersion = "1", writtenAt = t - 10 * 60_000, records = emptyList())
        fake.seed("NovelForge/devices/other.json.gz", SyncFileCodec.encode(skewed))
        assertTrue(a.ok().skewWarning)
        assertTrue(a.settings.lastSkewWarning)
    }

    // ---- ETags and concurrency ---------------------------------------------------------------

    @Test
    fun unchangedRemoteFile_isNotDownloadedAgain_andResultStaysCorrect() = runBlocking {
        val a = Device("a"); val b = Device("b")
        addNovel(a, "rr_1", "Royal Road")
        a.ok(); b.ok()
        val aFile = "GET dav/NovelForge/devices/${a.settings.deviceId}.json.gz"
        assertTrue(fake.requestLog.contains(aFile))
        fake.requestLog.clear()
        b.ok()
        assertFalse(fake.requestLog.contains(aFile))
        assertEquals(listOf("rr_1"), b.novelIds())

        // A changes: the file is fetched again and the change arrives.
        addNovel(a, "rr_2", "Royal Road")
        a.ok()
        fake.requestLog.clear()
        b.ok()
        assertTrue(fake.requestLog.contains(aFile))
        assertEquals(listOf("rr_1", "rr_2"), b.novelIds())
    }

    @Test
    fun secondSyncWhileOneRuns_returnsBusy() = runBlocking {
        val a = Device("a")
        addNovel(a, "rr_1", "Royal Road")
        var inner: SyncOutcome? = null
        // The shared mutex of this engine is held while the hook runs.
        val shared = Mutex()
        val dav = WebDavClient(fake.base, fake.client(), now = { t })
        val e = SyncEngine(a.store, dav, HybridClock({ t }) { 0L }, a.settings, "test", { t }, shared) {
            inner = SyncEngine(a.store, dav, HybridClock({ t }) { 0L }, a.settings, "test", { t }, shared)
                .sync(SyncTrigger.Periodic)
        }
        assertTrue(e.sync(SyncTrigger.AppStart) is SyncOutcome.Success)
        assertEquals(SyncOutcome.Busy, inner)
    }

    @Test
    fun twoEnginesSyncingAtTheSameTime_convergeAfterOneMoreRound() = runBlocking {
        val a = Device("a"); val b = Device("b")
        addNovel(a, "rr_1", "Royal Road")
        setProgress(a, "rr_1", 2, 5)
        addNovel(b, "rr_2", "Royal Road")
        // A has downloaded (nothing there yet) when B runs a whole sync; then A merges and uploads.
        var armed = true
        a.hook = {
            if (armed) {
                armed = false
                b.ok()
            }
        }
        a.ok()
        b.ok(); a.ok()
        assertEquals(listOf("rr_1", "rr_2"), a.novelIds())
        assertEquals(listOf("rr_1", "rr_2"), b.novelIds())
        b.ok(); a.ok()
        fake.requestLog.clear()
        b.ok(); a.ok()
        assertEquals(emptyList<String>(), puts())
        assertEquals(1, a.db.novelDao().getAllNovelsOnce().count { it.id == "rr_1" })
        assertNotNull(a.db.readingProgressDao().getProgress("rr_1"))
    }

    @Test
    fun threeDevicesConverge() = runBlocking {
        val a = Device("a"); val b = Device("b"); val c = Device("c")
        addNovel(a, "rr_1", "Royal Road")
        addNovel(b, "rr_2", "Royal Road")
        addNovel(c, "rr_3", "Royal Road")
        for (i in 1..2) { a.ok(); b.ok(); c.ok() }
        val all = listOf("rr_1", "rr_2", "rr_3")
        assertEquals(all, a.novelIds()); assertEquals(all, b.novelIds()); assertEquals(all, c.novelIds())
    }

    // ---- review fixes -----------------------------------------------------------------------

    private val folderUri = "content://com.android.externalstorage.documents/tree/primary%3ABooks/document/primary%3ABooks%2Fa.epub"
    private val pickedUri = "content://com.android.providers.media.documents/document/doc%3A1"

    @Test
    fun changedFingerprint_neverLinksOneBookToTwoKeys_andNoBookIsDeletedAnywhere() = runBlocking {
        val a = Device("a"); val b = Device("b"); val c = Device("c")
        addNovel(a, "local_a", "local", fp = "F1", hash = "H1")
        addNovel(b, "local_b", "local", fp = "F1", hash = "H1")
        addBookmark(a, "local_a", "keep me")
        a.ok(); b.ok(); a.ok(); b.ok()
        assertEquals(1, b.db.bookmarkDao().getAllBookmarksOnce().size)

        // The text of A's copy changes (restore with accept-changes), and C imports the new text.
        a.db.novelDao().setFingerprint("local_a", "F2")
        addNovel(c, "local_c", "local", fp = "F2", hash = "H2")
        for (i in 1..3) { c.ok(); a.ok(); b.ok() }

        for (d in listOf(a, b, c)) {
            assertEquals("one book on ${d.tag}", 1, d.novelIds().size)
            assertTrue("no tombstone on ${d.tag}", d.shadow().none { it.deleted })
        }
        assertEquals(1, a.db.bookmarkDao().getAllBookmarksOnce().size)
        assertEquals(1, b.db.bookmarkDao().getAllBookmarksOnce().size)
        // A's book is linked under exactly one key.
        assertEquals(1, a.shadow().count { it.kind == SyncKind.BOOK && it.localId == "local_a" })
    }

    private suspend fun restoreScenario(markRestore: Boolean): Pair<Device, Device> {
        val a = Device("a"); val b = Device("b")
        addNovel(a, "rr_1", "Royal Road")
        val oldT = t
        setProgress(a, "rr_1", 2, 5)
        a.db.novelDao().setReadingStatus("rr_1", "Reading")
        a.db.novelDao().setReaderSettingsJson("rr_1", "L1")
        addHighlight(a, "rr_1", "h1")
        addBookmark(a, "rr_1", "gone later")
        a.ok(); b.ok()
        addChapters(b, "rr_1", 3)
        b.ok(); a.ok()
        assertEquals("h1", b.db.highlightDao().getAllHighlightsOnce().single().note)

        // Newer edits on A, including deleting the bookmark; B learns all of it.
        t += 3_600_000
        setProgress(a, "rr_1", 3, 1)
        a.db.novelDao().setReadingStatus("rr_1", "Completed")
        a.db.novelDao().setReaderSettingsJson("rr_1", "L2")
        val hid = a.db.highlightDao().getAllHighlightsOnce().single().id
        a.db.highlightDao().updateNote(hid, "h2", t)
        a.db.bookmarkDao().deleteBookmark(a.db.bookmarkDao().getAllBookmarksOnce().single().id)
        a.ok(); b.ok(); a.ok(); b.ok()

        // The old backup is restored on A: rows are REPLACEd with the old values and times.
        t += 3_600_000
        a.db.readingProgressDao().saveProgress(ReadingProgressEntity("rr_1", "rr_1_ch2", 2, 5, 1, oldT))
        a.db.novelDao().setReadingStatus("rr_1", "Reading")
        a.db.novelDao().setReaderSettingsJson("rr_1", "L1")
        a.db.highlightDao().updateNote(hid, "h1", oldT)
        a.db.bookmarkDao().insertBookmark(
            BookmarkEntity(
                novelId = "rr_1", chapterId = "rr_1_ch2", chapterUrl = "u", chapterNumber = 2,
                chapterTitle = "Chapter 2", paragraphIndex = 7, textSnippet = "snip", note = "gone later", createdAt = oldT,
            )
        )
        if (markRestore) SyncSettings.markRestorePending(a.ctx)
        a.ok(); b.ok(); a.ok(); b.ok()
        return a to b
    }

    private suspend fun assertNewerPositionHighlightAndBookmarkSurvive(a: Device, b: Device) {
        for (d in listOf(a, b)) {
            val p = d.db.readingProgressDao().getProgress("rr_1")!!
            assertEquals("position on ${d.tag}", 3 to 1, p.currentChapterNumber to p.paragraphIndex)
            assertEquals("highlight on ${d.tag}", "h2", d.db.highlightDao().getAllHighlightsOnce().single().note)
            assertTrue("bookmark stays deleted on ${d.tag}", d.db.bookmarkDao().getAllBookmarksOnce().isEmpty())
        }
    }

    @Test
    fun restoreOfAnOlderBackup_keepsNewerValuesEverywhere_andLeavesNoPingPong() = runBlocking {
        val (a, b) = restoreScenario(markRestore = true)
        assertNewerPositionHighlightAndBookmarkSurvive(a, b)
        for (d in listOf(a, b)) {
            assertEquals("status on ${d.tag}", "Completed", d.db.novelDao().getAllNovelsOnce().single().readingStatus)
            assertEquals("look on ${d.tag}", "L2", d.db.novelDao().getAllNovelsOnce().single().readerSettingsJson)
        }
        assertFalse("flag cleared after the merge committed", a.settings.restorePending)
        // Settled: further rounds change nothing and publish nothing.
        a.ok(); b.ok()
        fake.requestLog.clear()
        a.ok(); b.ok(); a.ok()
        assertEquals(emptyList<String>(), puts())
        assertNewerPositionHighlightAndBookmarkSurvive(a, b)
    }

    @Test
    fun restoreWithoutTheFlag_stillCannotRevertPositionHighlightOrBringBackABookmark() = runBlocking {
        val (a, b) = restoreScenario(markRestore = false)
        assertNewerPositionHighlightAndBookmarkSurvive(a, b)
    }

    @Test
    fun restoreFlag_isKeptWhenTheMergeDoesNotCommit() = runBlocking {
        val a = Device("a")
        addNovel(a, "rr_1", "Royal Road")
        SyncSettings.markRestorePending(a.ctx)
        a.flaky.failSave = true
        assertTrue(a.sync() is SyncOutcome.Failed)
        assertTrue(a.settings.restorePending)
        a.flaky.failSave = false
        a.ok()
        assertFalse(a.settings.restorePending)
    }

    @Test
    fun failedTransaction_leavesCoverIgnoreListAndFileAccessUntouched_andCommitRunsThemOnce() = runBlocking {
        val fx = Effects()
        val a = Device("a"); val b = Device("b", fx)
        addNovel(a, "local_f", "local", fp = "FF", hash = "HF")
        addNovel(a, "local_p", "local", fp = "FP", hash = "HP")
        addNovel(b, "local_f", "local", fp = "FF", hash = "HF", uri = folderUri)
        addNovel(b, "local_p", "local", fp = "FP", hash = "HP", uri = pickedUri)
        a.ok(); b.ok(); a.ok()
        NovelRepository(a.db).removeFromLibrary("local_f")
        NovelRepository(a.db).removeFromLibrary("local_p")
        a.ok()

        b.flaky.failSave = true
        assertTrue(b.sync() is SyncOutcome.Failed)
        assertEquals(listOf("local_f", "local_p"), b.novelIds()) // rolled back: books are still there
        // No cover/file/grant work before a commit. The folder-scan ignore entry is written early on purpose
        // (so a cancelled effects step cannot lose it); for a book still in the library it is harmless.
        assertTrue("no file work before a commit: ${fx.deletedFiles} ${fx.released}", fx.deletedFiles.isEmpty() && fx.released.isEmpty())
        assertEquals(setOf(folderUri), fx.ignored.toSet())

        b.flaky.failSave = false
        b.ok()
        assertTrue(b.novelIds().isEmpty())
        assertEquals(setOf(folderUri), fx.ignored.toSet())
        assertEquals(listOf("local_f", "local_p"), fx.deletedFiles.sorted())
        assertEquals(listOf(pickedUri), fx.released)
        val ignoredCount = fx.ignored.size
        b.ok()
        assertEquals(ignoredCount, fx.ignored.size)
    }

    @Test
    fun deviceJoiningAPopulatedFolder_doesNotOverwriteNewerStatusLookOrBookmarkNote() = runBlocking {
        val a = Device("a"); val b = Device("b")
        addNovel(a, "rr_1", "Royal Road"); addNovel(b, "rr_1", "Royal Road")
        // B's values are old; A's are newer and already in the shared folder.
        b.db.novelDao().setReadingStatus("rr_1", "Reading")
        b.db.novelDao().setReaderSettingsJson("rr_1", "look-b")
        addBookmark(b, "rr_1", "note-b")
        t += 3_600_000
        a.db.novelDao().setReadingStatus("rr_1", "Completed")
        a.db.novelDao().setReaderSettingsJson("rr_1", "look-a")
        addBookmark(a, "rr_1", "note-a")
        a.ok()
        t += 3_600_000
        b.ok(); a.ok(); b.ok()
        for (d in listOf(a, b)) {
            val n = d.db.novelDao().getAllNovelsOnce().single()
            assertEquals("status on ${d.tag}", "Completed", n.readingStatus)
            assertEquals("look on ${d.tag}", "look-a", n.readerSettingsJson)
            assertEquals("note on ${d.tag}", "note-a", d.db.bookmarkDao().getAllBookmarksOnce().single().note)
        }
    }

    @Test
    fun folderScanBook_canBeReAddedAfterATombstone() = runBlocking {
        val a = Device("a"); val b = Device("b")
        addNovel(a, "local_f", "local", fp = "FF", hash = "HF", uri = folderUri)
        addNovel(b, "local_f", "local", fp = "FF", hash = "HF", uri = folderUri)
        a.ok(); b.ok(); a.ok()
        t += 60_000
        NovelRepository(a.db).removeFromLibrary("local_f")
        a.ok(); b.ok()
        assertTrue(b.novelIds().isEmpty())

        // Later the folder scan brings the file back on A.
        t += 3_600_000
        addNovel(a, "local_f2", "local", fp = "FF", hash = "HF", uri = folderUri)
        for (i in 1..2) { a.ok(); b.ok() }
        assertEquals(listOf("local_f2"), a.novelIds())
        assertFalse(a.shadow().single { it.kind == SyncKind.BOOK && it.key == "t:FF" }.deleted)
        assertFalse(b.shadow().single { it.kind == SyncKind.BOOK && it.key == "t:FF" }.deleted)
    }

    @Test
    fun unchangedUploadHash_stillUploadsWhenTheServerHoldsAnotherFileThanWeLeft() = runBlocking {
        val a = Device("a")
        addNovel(a, "rr_1", "Royal Road")
        assertTrue(a.ok().uploaded)
        assertFalse(a.ok().uploaded)
        // The server reverted or lost our file's content (new ETag, same name).
        val empty = SyncFile(SyncFormat.CURRENT, a.settings.deviceId, "x", "t", t, emptyList())
        fake.seed("NovelForge/devices/${a.settings.deviceId}.json.gz", SyncFileCodec.encode(empty))
        assertTrue(a.ok().uploaded)
        assertEquals(1, SyncFileCodec.decode(deviceFile(a)).records.count { it.kind == SyncKind.BOOK })
        assertFalse(a.ok().uploaded)
    }

    private fun gzipOf(write: (java.io.OutputStream) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { write(it) }
        return bos.toByteArray()
    }

    @Test
    fun hostileDeviceFiles_areSkipped_withoutACrash_andTheirEtagIsNotRecorded() = runBlocking {
        val a = Device("a")
        addNovel(a, "rr_1", "Royal Road")
        a.ok()
        fake.seed("NovelForge/devices/big.json.gz", ByteArray(21 * 1024 * 1024))
        fake.seed("NovelForge/devices/bomb.json.gz", gzipOf { g ->
            g.write("""{"format":1,"deviceId":"bomb","records":[""".toByteArray())
            val chunk = ByteArray(1 shl 20) { ' '.code.toByte() }
            repeat(101) { g.write(chunk) } // more than 100 MB of valid whitespace
            g.write("]}".toByteArray())
        })
        fake.seed("NovelForge/devices/nested.json.gz", gzipOf { g ->
            g.write("""{"format":1,"deviceId":"nested","records":""".toByteArray())
            g.write("[".repeat(300_000).toByteArray())
            g.write("]".repeat(300_000).toByteArray())
            g.write("}".toByteArray())
        })
        val future = (t + 3 * 24 * 3_600_000L) shl 16
        val hostile = SyncFile(
            SyncFormat.CURRENT, "future", "Future", "t", t, listOf(
                SyncRecord(SyncKind.BOOK, "o:rr_1", future, "future", true, null),
                SyncRecord(SyncKind.BOOK, "o:rr_9", future, "future", false, com.google.gson.JsonObject().apply { addProperty("title", "X") }),
            )
        )
        fake.seed("NovelForge/devices/future.json.gz", SyncFileCodec.encode(hostile))

        val o = a.ok()
        assertEquals(4, o.devicesSeen)
        assertEquals(listOf("rr_1"), a.novelIds())
        assertTrue(a.shadow().none { it.deleted })
        for (name in listOf("bomb", "nested")) {
            assertNull("etag of corrupt $name", a.settings.knownEtags()["$name.json.gz"])
        }
        // Too large and future-clock files are reported with a reason and their ETag is recorded.
        for (name in listOf("big", "future")) {
            assertNotNull("etag of $name", a.settings.knownEtags()["$name.json.gz"])
        }
        val reasons = a.settings.lastSkippedDevices
        assertEquals(4, reasons.size)
        assertTrue(reasons.toString(), reasons.any { it.contains("too large") })
        assertTrue(reasons.toString(), reasons.any { it.startsWith("Future") && it.contains("clock") })
        assertEquals(2, reasons.count { it.contains("damaged") })
        // Still fine on the next round; the unchanged big and future files are not downloaded again,
        // and are still reported.
        fake.requestLog.clear()
        a.ok()
        assertEquals(listOf("rr_1"), a.novelIds())
        assertTrue(fake.requestLog.none { it.endsWith("devices/big.json.gz") || it.endsWith("devices/future.json.gz") })
        assertTrue(fake.requestLog.any { it.startsWith("GET") && it.endsWith("devices/bomb.json.gz") })
        assertEquals(4, a.settings.lastSkippedDevices.size)
    }

    // ---- second review pass -----------------------------------------------------------------

    private fun newEngine(d: Device, mutex: Mutex) =
        SyncEngine(d.flaky, WebDavClient(fake.base, fake.client(), now = { t }), HybridClock({ t }) { 0L }, d.settings, "test", { t }, mutex)

    @Test
    fun s1_syncStartedDuringARestore_waitsThenRunsWithPreferShadow() = runBlocking {
        val a = Device("a")
        addNovel(a, "rr_1", "Royal Road")
        a.db.novelDao().setReadingStatus("rr_1", "Reading")
        a.ok()
        val gated = newEngine(a, SyncGate.mutex)
        var job: Deferred<SyncOutcome>? = null
        SyncGate.restoring(a.ctx) { markPending ->
            markPending()
            a.db.novelDao().setReadingStatus("rr_1", "Restored-old") // the restore's write
            t += 10_000
            job = async(Dispatchers.Default) { gated.sync(SyncTrigger.Manual) }
            delay(300)
            assertFalse("the sync must wait for the restore", job!!.isCompleted)
            assertTrue(a.settings.restorePending)
        }
        val o = job!!.await()
        assertTrue("was $o", o is SyncOutcome.Success)
        // It ran with the token: the restored status did not overwrite what sync holds.
        assertEquals("Reading", a.db.novelDao().getAllNovelsOnce().single().readingStatus)
        assertFalse(a.settings.restorePending)
        assertFalse("gate released", SyncGate.mutex.isLocked)
    }

    @Test
    fun s1_restoreReleasesTheGateOnException_andKeepsTheToken() = runBlocking {
        val a = Device("a")
        try {
            SyncGate.restoring(a.ctx) { markPending ->
                markPending()
                error("restore failed halfway")
            }
        } catch (_: IllegalStateException) {
        }
        assertFalse(SyncGate.mutex.isLocked)
        assertTrue("a failed restore after its first write leaves the token", a.settings.restorePending)
    }

    @Test
    fun s1_aSyncThatBeganBeforeARestore_doesNotClearTheRestoresToken() = runBlocking {
        val a = Device("a")
        addNovel(a, "rr_1", "Royal Road")
        SyncSettings.markRestorePending(a.ctx)
        val first = a.settings.restoreToken
        // A second restore lands while the merge of the first token's sync is running.
        a.flaky.onSnapshot = { a.flaky.onSnapshot = null; SyncSettings.markRestorePending(a.ctx) }
        a.ok()
        assertTrue("the newer token is still pending", a.settings.restorePending)
        assertTrue(a.settings.restoreToken != first)
        a.ok()
        assertFalse(a.settings.restorePending)
    }

    @Test
    fun s2_afterARestore_aPositionAndHighlightEditMadeAfterTheLastSync_surviveAndADeletionIsTombstoned() = runBlocking {
        val a = Device("a"); val b = Device("b")
        addNovel(a, "rr_1", "Royal Road")
        setProgress(a, "rr_1", 2, 5)
        addHighlight(a, "rr_1", "h1")
        addBookmark(a, "rr_1", "mark")
        a.db.novelDao().setReadingStatus("rr_1", "Reading")
        a.ok(); b.ok()
        addChapters(b, "rr_1", 3)
        b.ok(); a.ok()
        assertEquals(1, b.db.bookmarkDao().getAllBookmarksOnce().size)

        SyncSettings.markRestorePending(a.ctx)               // the restore
        a.db.novelDao().setReadingStatus("rr_1", "Old")      // a value from the backup: must not win
        t += 5_000
        setProgress(a, "rr_1", 3, 1)                          // real edits after the restore
        a.db.highlightDao().updateNote(a.db.highlightDao().getAllHighlightsOnce().single().id, "h2", t)
        a.db.bookmarkDao().deleteBookmark(a.db.bookmarkDao().getAllBookmarksOnce().single().id)
        a.ok(); b.ok()
        for (d in listOf(a, b)) {
            val p = d.db.readingProgressDao().getProgress("rr_1")!!
            assertEquals("position on ${d.tag}", 3 to 1, p.currentChapterNumber to p.paragraphIndex)
            assertEquals("highlight on ${d.tag}", "h2", d.db.highlightDao().getAllHighlightsOnce().single().note)
            assertTrue("bookmark deleted on ${d.tag}", d.db.bookmarkDao().getAllBookmarksOnce().isEmpty())
            assertEquals("status on ${d.tag}", "Reading", d.db.novelDao().getAllNovelsOnce().single().readingStatus)
        }
    }

    @Test
    fun s3_aStatusSetOnADeviceThatAlreadySyncs_beatsAnOlderRemoteStatus_andALaterJoinAdoptsRemote() = runBlocking {
        val a = Device("a"); val b = Device("b")
        addNovel(a, "rr_1", "Royal Road"); addNovel(b, "rr_1", "Royal Road")
        a.ok(); b.ok(); a.ok(); b.ok()                      // both already sync the book, neither has a status
        t += 3_600_000
        a.db.novelDao().setReadingStatus("rr_1", "READING"); a.ok()
        t += 3_600_000
        b.db.novelDao().setReadingStatus("rr_1", "Completed"); b.ok(); a.ok()
        assertEquals("Completed", b.db.novelDao().getAllNovelsOnce().single().readingStatus)
        assertEquals("Completed", a.db.novelDao().getAllNovelsOnce().single().readingStatus)

        // A device that joins (its book is not synced yet) still adopts what the others hold.
        val c = Device("c")
        addNovel(c, "rr_1", "Royal Road")
        c.db.novelDao().setReadingStatus("rr_1", "Dropped")
        t += 3_600_000
        c.ok()
        assertEquals("Completed", c.db.novelDao().getAllNovelsOnce().single().readingStatus)
    }

    private suspend fun skewScenario(remoteAheadMs: Long, dateHeader: Boolean) {
        fake.sendDateHeader = dateHeader
        val a = Device("a"); val b = Device("b")
        addNovel(a, "rr_1", "Royal Road"); addNovel(b, "rr_1", "Royal Road")
        a.ok(); b.ok(); a.ok(); b.ok()
        // A device whose clock runs ahead has published a position for this book.
        val ahead = SyncFile(
            SyncFormat.CURRENT, "zzz-skewed", "Skewed", "t", t, listOf(
                SyncRecord(
                    SyncKind.PROGRESS, "o:rr_1", (t + remoteAheadMs) shl 16, "zzz-skewed", false,
                    com.google.gson.JsonObject().apply {
                        addProperty("chapterNumber", 2); addProperty("paragraph", 1)
                        addProperty("chaptersRead", 0); addProperty("lastReadAt", t + remoteAheadMs)
                    },
                )
            )
        )
        fake.seed("NovelForge/devices/zzz-skewed.json.gz", SyncFileCodec.encode(ahead))
        a.ok(); b.ok()
        assertEquals(1, a.db.readingProgressDao().getProgress("rr_1")!!.paragraphIndex)
        // A real edit made after A's last sync must win over the skewed record.
        t += 60_000
        setProgress(a, "rr_1", 3, 7)
        a.ok(); b.ok()
        for (d in listOf(a, b)) {
            val p = d.db.readingProgressDao().getProgress("rr_1")!!
            assertEquals("position on ${d.tag}", 3 to 7, p.currentChapterNumber to p.paragraphIndex)
        }
    }

    @Test
    fun s4_aSkewedRemoteHlc23HoursAhead_doesNotSwallowARealEdit() = runBlocking {
        skewScenario(23 * 3_600_000L, dateHeader = true)
    }

    @Test
    fun s4_noDateHeaderAndARemoteAhead_doesNotSwallowARealEdit() = runBlocking {
        skewScenario(10 * 60_000L, dateHeader = false)
    }

    @Test
    fun s5_cancellingTheEnginesCoroutineAfterTheCommit_stillRunsEveryEffect() = runBlocking {
        val fx = Effects()
        val a = Device("a"); val b = Device("b", fx)
        addNovel(a, "local_f", "local", fp = "FF", hash = "HF")
        addNovel(a, "local_p", "local", fp = "FP", hash = "HP")
        addNovel(b, "local_f", "local", fp = "FF", hash = "HF", uri = folderUri)
        addNovel(b, "local_p", "local", fp = "FP", hash = "HP", uri = pickedUri)
        a.ok(); b.ok(); a.ok()
        NovelRepository(a.db).removeFromLibrary("local_f")
        NovelRepository(a.db).removeFromLibrary("local_p")
        a.ok()

        t += 10_000
        var job: kotlinx.coroutines.Job? = null
        fx.onFile = { job?.cancel() }                        // cancelled while the first effect runs
        job = launch(Dispatchers.Default) { b.engine.sync(SyncTrigger.Manual) }
        job.join()
        assertTrue(b.novelIds().isEmpty())
        assertEquals(listOf("local_f", "local_p"), fx.deletedFiles.sorted())
        assertEquals(listOf(pickedUri), fx.released)
        assertTrue(folderUri in fx.ignored)
        assertTrue("ETags still recorded", b.settings.knownEtags().isNotEmpty())
    }

    @Test
    fun s5_oneFailingEffectDoesNotSkipTheOthers() = runBlocking {
        val fx = Effects()
        val a = Device("a"); val b = Device("b", fx)
        addNovel(a, "local_f", "local", fp = "FF", hash = "HF")
        addNovel(a, "local_p", "local", fp = "FP", hash = "HP")
        addNovel(b, "local_f", "local", fp = "FF", hash = "HF", uri = folderUri)
        addNovel(b, "local_p", "local", fp = "FP", hash = "HP", uri = pickedUri)
        a.ok(); b.ok(); a.ok()
        NovelRepository(a.db).removeFromLibrary("local_f")
        NovelRepository(a.db).removeFromLibrary("local_p")
        a.ok()
        fx.onFile = { throw IOException("file is locked") }
        b.ok()
        assertEquals(listOf("local_f", "local_p"), fx.deletedFiles.sorted())
        assertEquals("the grant of the second book is still released", listOf(pickedUri), fx.released)
        assertTrue(folderUri in fx.ignored)
    }

    private suspend fun guardScenario(): Pair<Device, Device> {
        val a = Device("a"); val b = Device("b")
        for (i in 1..8) addNovel(a, "rr_$i", "Royal Road")
        a.ok(); b.ok()
        for (i in 2..8) NovelRepository(a.db).removeFromLibrary("rr_$i")
        assertEquals(SyncOutcome.GuardTripped(7), a.sync())
        assertTrue(a.sync(allowLocalDeletions = true) is SyncOutcome.Success)
        return a to b
    }

    @Test
    fun s6_aBackgroundSyncThatTripsTheGuard_storesThePendingStateForTheSettingsScreen() = runBlocking {
        val (_, b) = guardScenario()
        t += 10_000
        val o = b.engine.sync(SyncTrigger.Periodic)           // not the screen: a background run
        assertEquals(SyncOutcome.GuardTripped(7, fromRemote = true), o)
        assertEquals(7, SyncSettings(b.ctx, FakeSecretStore()).pendingRemoteRemovals) // survives a new instance
        assertEquals(0, b.settings.pendingLocalRemovals)
        assertEquals(8, b.novelIds().size)
    }

    @Test
    fun s6_removeHere_deletesThemAndClearsTheNotice() = runBlocking {
        val (_, b) = guardScenario()
        b.sync()
        assertEquals(7, b.settings.pendingRemoteRemovals)
        assertTrue(b.sync(allowRemoteDeletions = true) is SyncOutcome.Success)
        assertEquals(listOf("rr_1"), b.novelIds())
        assertEquals(0, b.settings.pendingRemoteRemovals)
    }

    @Test
    fun s6_keepThem_restoresTheBooksOnTheOtherDevice_andClearsTheNotice() = runBlocking {
        val (a, b) = guardScenario()
        addBookmark(b, "rr_2", "keep me too")
        b.sync()
        assertEquals(7, b.settings.pendingRemoteRemovals)
        t += 10_000
        val o = b.engine.sync(SyncTrigger.Manual, keepLocalBooks = true)
        assertTrue("was $o", o is SyncOutcome.Success)
        assertEquals(8, b.novelIds().size)
        assertEquals(1, b.db.bookmarkDao().getAllBookmarksOnce().size)
        assertEquals(0, b.settings.pendingRemoteRemovals)
        a.ok(); b.ok(); a.ok()
        assertEquals("the other device sees a re-add", 8, a.novelIds().size)
        assertEquals(8, b.novelIds().size)
        assertTrue(a.shadow().none { it.deleted && it.kind == SyncKind.BOOK })
    }

    @Test
    fun s6_bothGuardsTripped_bothCountsAreStored() = runBlocking {
        val a = Device("a"); val b = Device("b")
        for (i in 1..14) addNovel(a, "rr_$i", "Royal Road")
        a.ok(); b.ok(); a.ok(); b.ok()
        for (i in 7..14) NovelRepository(b.db).removeFromLibrary("rr_$i")
        for (i in 1..6) NovelRepository(a.db).removeFromLibrary("rr_$i")
        assertTrue(a.sync() is SyncOutcome.Success)
        val o = b.sync()
        assertTrue("was $o", o is SyncOutcome.GuardTripped)
        assertEquals(8, b.settings.pendingLocalRemovals)
        assertEquals(6, b.settings.pendingRemoteRemovals)
    }

    @Test
    fun outOfMemoryInTheMerge_isFailedWithoutRetry_andChangesNothing() = runBlocking {
        val a = Device("a")
        addNovel(a, "rr_1", "Royal Road")
        a.flaky.oomOnSnapshot = true
        val o = a.sync()
        assertEquals(SyncOutcome.Failed(SyncEngine.OOM_MESSAGE, retry = false), o)
        assertTrue(a.shadow().isEmpty())
        a.flaky.oomOnSnapshot = false
        assertTrue(a.sync() is SyncOutcome.Success)
    }

    @Test
    fun staleRestoredBook_isDeletedWithItsChildrenAndNeverClaimed() = runBlocking {
        val a = Device("a"); val b = Device("b")
        addNovel(a, "rr_1", "Royal Road"); addNovel(a, "rr_2", "Royal Road")
        a.ok(); b.ok(); a.ok()
        NovelRepository(a.db).removeFromLibrary("rr_2")
        a.ok()
        // B restores an old backup: rr_2 comes back with an old creation time, plus a status and a bookmark.
        b.db.novelDao().insertNovel(
            NovelEntity(
                id = "rr_2", title = "T", author = "A", coverUrl = null, description = "", source = "Royal Road",
                status = "Ongoing", totalChapters = 3, addedToLibraryAt = 1_000L, language = "en",
            )
        )
        addChapters(b, "rr_2", 3)
        b.db.novelDao().setReadingStatus("rr_2", "Reading")
        addBookmark(b, "rr_2", "stale")
        b.ok(); a.ok()
        assertEquals(listOf("rr_1"), b.novelIds())
        assertEquals(listOf("rr_1"), a.novelIds())
        val live = b.shadow().filter { it.key.startsWith("o:rr_2") && !it.deleted && it.kind != SyncKind.BOOK }
        assertTrue("no live record of the stale book's children: $live", live.isEmpty())
    }

    // ---- third review: two independent answers, restore after reconnect, small fixes -------------

    /** B has 6 books another device removed (rr_1..6) and lost 8 itself (rr_7..14): both guards pending. */
    private suspend fun bothPending(): Pair<Device, Device> {
        val a = Device("a"); val b = Device("b")
        for (i in 1..14) addNovel(a, "rr_$i", "Royal Road")
        a.ok(); b.ok(); a.ok(); b.ok()
        for (i in 7..14) NovelRepository(b.db).removeFromLibrary("rr_$i")
        for (i in 1..6) NovelRepository(a.db).removeFromLibrary("rr_$i")
        assertTrue(a.sync() is SyncOutcome.Success)
        assertTrue(b.sync() is SyncOutcome.GuardTripped)
        assertEquals(8, b.settings.pendingLocalRemovals)
        assertEquals(6, b.settings.pendingRemoteRemovals)
        return a to b
    }

    private suspend fun Device.bookIsTombstoned(i: Int) =
        shadow().any { it.kind == SyncKind.BOOK && it.key.endsWith("rr_$i") && it.deleted }

    @Test
    fun answers_removeHereAlone_deletesOnlyTheRemoteRemovedBooks_andLeavesTheLocalSidePending() = runBlocking {
        val (a, b) = bothPending()
        val o = b.sync(allowRemoteDeletions = true)
        assertTrue("was $o", o is SyncOutcome.Success)
        assertTrue("the six removed elsewhere are gone here", b.novelIds().none { it in (1..6).map { i -> "rr_$i" } })
        assertEquals("nothing tombstoned for the books this device lost", 0, (7..14).count { b.bookIsTombstoned(it) })
        assertEquals(8, b.settings.pendingLocalRemovals)
        assertEquals(0, b.settings.pendingRemoteRemovals)
        // The other device still has its books 7..14: nothing was removed there.
        a.ok()
        assertEquals((7..14).map { "rr_$it" }.sorted(), a.novelIds())
        // The second answer finishes the job.
        assertTrue(b.sync(allowLocalDeletions = true) is SyncOutcome.Success)
        assertEquals(0, b.settings.pendingLocalRemovals)
        // Now A sees 8 books removed by another device: its own guard asks.
        assertTrue(a.sync() is SyncOutcome.GuardTripped)
        assertTrue(a.sync(allowRemoteDeletions = true) is SyncOutcome.Success)
        assertTrue(a.novelIds().isEmpty())
    }

    @Test
    fun answers_removeEverywhereAlone_doesNotDeleteTheRemoteRemovedBooksHere() = runBlocking {
        val (a, b) = bothPending()
        fake.requestLog.clear()
        val o = b.sync(allowLocalDeletions = true)
        assertTrue("was $o", o is SyncOutcome.GuardTripped)
        assertEquals((1..6).map { "rr_$it" }, b.novelIds())
        assertTrue(puts().isEmpty())
        assertEquals(6, b.settings.pendingRemoteRemovals)
        assertEquals("the pending local count is not wiped by a run that settled nothing", 8, b.settings.pendingLocalRemovals)
        assertTrue(b.settings.allowLocalRemovals)
        a.ok()
        assertEquals((7..14).map { "rr_$it" }.sorted(), a.novelIds())
    }

    @Test
    fun answers_aBackgroundSyncBetweenTheTwoAnswers_doesNotLoseTheFirstOne() = runBlocking {
        val (a, b) = bothPending()
        assertTrue(b.sync(allowLocalDeletions = true) is SyncOutcome.GuardTripped)
        t += 10_000
        val bg = b.engine.sync(SyncTrigger.Periodic)
        assertTrue("was $bg", bg is SyncOutcome.GuardTripped)
        assertTrue("the answer is still recorded", b.settings.allowLocalRemovals)
        assertEquals(6, b.settings.pendingRemoteRemovals)
        assertEquals(8, b.settings.pendingLocalRemovals)
        // A new settings instance (a restart) sees it too.
        assertTrue(SyncSettings(b.ctx, FakeSecretStore()).allowLocalRemovals)
        // The second answer applies both.
        assertTrue(b.sync(allowRemoteDeletions = true) is SyncOutcome.Success)
        assertTrue(b.novelIds().isEmpty())
        assertEquals(8, (7..14).count { b.bookIsTombstoned(it) })
        assertEquals(0, b.settings.pendingRemoteRemovals)
        assertEquals(0, b.settings.pendingLocalRemovals)
        assertFalse(b.settings.allowLocalRemovals)
        assertTrue(a.sync(allowRemoteDeletions = true) is SyncOutcome.Success)
        assertTrue(a.novelIds().isEmpty())
    }

    @Test
    fun answers_keepThemThenRemoveEverywhere_keepsTheKeptBooksOnBothDevices() = runBlocking {
        val (a, b) = bothPending()
        val first = b.sync(keep = true)
        assertTrue("was $first", first is SyncOutcome.Success)
        assertEquals("the kept books stay", (1..6).map { "rr_$it" }, b.novelIds())
        assertEquals("the local side still waits", 8, b.settings.pendingLocalRemovals)
        assertEquals(0, b.settings.pendingRemoteRemovals)
        assertEquals(SyncSettings.ANSWER_NONE, b.settings.remoteAnswer)
        assertEquals(0, (7..14).count { b.bookIsTombstoned(it) })
        // The second answer must not delete the kept books, even though the local guard tripped meanwhile.
        assertTrue(b.sync(allowLocalDeletions = true) is SyncOutcome.Success)
        assertEquals((1..6).map { "rr_$it" }, b.novelIds())
        assertTrue(a.sync(allowRemoteDeletions = true) is SyncOutcome.Success)
        b.ok(); a.ok()
        assertEquals((1..6).map { "rr_$it" }, a.novelIds())
        assertEquals((1..6).map { "rr_$it" }, b.novelIds())
    }

    @Test
    fun answers_keepThem_gives_theOtherDevice_theKeptBooksChildrenBack() = runBlocking {
        val a = Device("a"); val b = Device("b")
        for (i in 1..8) addNovel(a, "rr_$i", "Royal Road")
        setProgress(a, "rr_2", 2, 5)
        addBookmark(a, "rr_2", "bm")
        addHighlight(a, "rr_2", "hl")
        a.ok(); b.ok()
        addChapters(b, "rr_2", 3)
        b.ok(); a.ok()
        assertEquals(1, b.db.bookmarkDao().getAllBookmarksOnce().size)
        for (i in 2..8) NovelRepository(a.db).removeFromLibrary("rr_$i")
        assertTrue(a.sync(allowLocalDeletions = true) is SyncOutcome.Success)
        assertTrue(b.sync() is SyncOutcome.GuardTripped)
        assertTrue(b.sync(keep = true) is SyncOutcome.Success)
        assertEquals(8, b.novelIds().size)
        // The other device gets the books back with their bookmark, highlight and position.
        a.ok()
        assertEquals(8, a.novelIds().size)
        // The chapters come back with the next download of the book; the rows then follow from the records.
        addChapters(a, "rr_2", 3)
        a.ok()
        assertEquals("bookmark", 1, a.db.bookmarkDao().getAllBookmarksOnce().size)
        assertEquals("highlight", 1, a.db.highlightDao().getAllHighlightsOnce().size)
        val live = a.shadow().filter { it.key.contains("rr_2") && !it.deleted }.map { it.kind }
        assertTrue("position record is live: $live", SyncKind.PROGRESS in live)
    }

    private fun reconnect(d: Device) {
        d.settings.serverUrl = fake.base.toString()
        d.settings.username = "u"
        d.settings.password = "p"
    }

    @Test
    fun disconnectRestoreReconnect_doesNotPushTheRestoredValuesToTheOtherDevices() = runBlocking {
        val a = Device("a"); val b = Device("b")
        addNovel(a, "rr_1", "Royal Road")
        val oldT = t
        setProgress(a, "rr_1", 2, 5)
        a.ok(); b.ok()
        addChapters(b, "rr_1", 3)
        b.ok(); a.ok()
        t += 3_600_000
        setProgress(b, "rr_1", 3, 1)
        b.ok(); a.ok()
        assertEquals(3, a.db.readingProgressDao().getProgress("rr_1")!!.currentChapterNumber)
        assertTrue(a.settings.lastSyncTime > 0)

        // Disconnect keeps the shadow; a backup is restored while disconnected; then reconnect.
        a.settings.clear()
        t += 3_600_000
        a.db.readingProgressDao().saveProgress(ReadingProgressEntity("rr_1", "rr_1_ch2", 2, 5, 1, oldT))
        SyncSettings.markRestorePending(a.ctx)
        reconnect(a)
        a.ok(); b.ok(); a.ok(); b.ok()
        for (d in listOf(a, b)) {
            val p = d.db.readingProgressDao().getProgress("rr_1")!!
            assertEquals("position on ${d.tag}", 3 to 1, p.currentChapterNumber to p.paragraphIndex)
        }
    }

    @Test
    fun lastSyncTime_isTakenBeforeTheSnapshot_soAnEditDuringTheRunIsNeverAtOrBeforeIt() = runBlocking {
        val a = Device("a")
        addNovel(a, "rr_1", "Royal Road")
        var atSnapshot = 0L
        a.flaky.onSnapshot = { a.flaky.onSnapshot = null; atSnapshot = t; t += 7_000 }
        a.ok()
        assertEquals(atSnapshot, a.settings.lastSyncTime)
        assertTrue(a.settings.lastSyncTime < t)
    }
}
