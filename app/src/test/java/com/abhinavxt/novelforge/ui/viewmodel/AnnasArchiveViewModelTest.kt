package com.abhinavxt.novelforge.ui.viewmodel

import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.abhinavxt.novelforge.data.annas.AnnasBook
import com.abhinavxt.novelforge.ui.viewmodel.AnnasArchiveViewModel.Companion.editionsOf
import com.abhinavxt.novelforge.ui.viewmodel.AnnasArchiveViewModel.Companion.titleKey
import com.abhinavxt.novelforge.ui.viewmodel.AnnasArchiveViewModel.Companion.toDownloadStates
import com.abhinavxt.novelforge.ui.viewmodel.AnnasArchiveViewModel.DownloadState
import com.abhinavxt.novelforge.worker.AnnasDownloadWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AnnasArchiveViewModelTest {

    private fun book(md5: Char, title: String, author: String = "") =
        AnnasBook(md5 = md5.toString().repeat(32), title = title, author = author)

    // ── Editions ────────────────────────────────────────────────────────

    @Test
    fun `title key ignores case, punctuation and subtitles`() {
        assertEquals("pride and prejudice", titleKey("Pride & Prejudice"))
        assertEquals("pride and prejudice", titleKey("Pride and Prejudice: Annotated Edition"))
        assertEquals("dune", titleKey("Dune (Ace, 2005)"))
        assertEquals("dune", titleKey("DUNE - 40th anniversary"))
    }

    @Test
    fun `editions share a title and an author`() {
        val a = book('a', "Dune", "Frank Herbert")
        val b = book('b', "Dune: Deluxe Edition", "Herbert, Frank")
        val c = book('c', "Dune", "Someone Else")
        val d = book('d', "Dune Messiah", "Frank Herbert")
        val e = book('e', "Dune") // no author listed: title alone decides
        assertEquals(listOf(b, e), editionsOf(a, listOf(a, b, c, d, e)))
    }

    // ── Download states ─────────────────────────────────────────────────

    private fun info(
        md5: Char,
        state: WorkInfo.State,
        output: androidx.work.Data = workDataOf(),
        progress: androidx.work.Data = workDataOf()
    ) = WorkInfo(
        UUID.randomUUID(),
        state,
        setOf(AnnasDownloadWorker.TAG_DOWNLOAD, "${AnnasDownloadWorker.TAG_DOWNLOAD}:${md5.toString().repeat(32)}"),
        output,
        progress,
        0,
        0
    )

    @Test
    fun `work states map to download states by md5`() {
        val states = toDownloadStates(
            listOf(
                info('a', WorkInfo.State.ENQUEUED),
                info(
                    'b', WorkInfo.State.RUNNING,
                    progress = workDataOf(AnnasDownloadWorker.KEY_BYTES to 50L, AnnasDownloadWorker.KEY_TOTAL to 100L)
                ),
                info(
                    'c', WorkInfo.State.SUCCEEDED,
                    output = workDataOf(AnnasDownloadWorker.KEY_NOVEL_ID to "local_1", AnnasDownloadWorker.KEY_RESULT_TITLE to "Dune")
                ),
                info(
                    'd', WorkInfo.State.SUCCEEDED,
                    output = workDataOf(AnnasDownloadWorker.KEY_DUPLICATE to true, AnnasDownloadWorker.KEY_RESULT_TITLE to "Emma")
                ),
                info('e', WorkInfo.State.FAILED, output = workDataOf(AnnasDownloadWorker.KEY_ERROR to "No mirror")),
                info('f', WorkInfo.State.CANCELLED),
            )
        )
        fun of(c: Char) = states[c.toString().repeat(32)]
        assertEquals(DownloadState.Queued, of('a'))
        assertEquals(DownloadState.Running(50, 100), of('b'))
        assertEquals(DownloadState.Imported("local_1", "Dune"), of('c'))
        assertEquals(DownloadState.AlreadyInLibrary("Emma"), of('d'))
        assertEquals(DownloadState.Failed("No mirror"), of('e'))
        assertTrue("a cancelled download leaves no badge", 'f'.toString().repeat(32) !in states)
    }

    @Test
    fun `a retry in progress hides the failure before it, in either order`() {
        val failed = info('a', WorkInfo.State.FAILED, output = workDataOf(AnnasDownloadWorker.KEY_ERROR to "x"))
        val retry = info('a', WorkInfo.State.ENQUEUED)
        val md5 = "a".repeat(32)
        assertEquals(DownloadState.Queued, toDownloadStates(listOf(failed, retry))[md5])
        assertEquals(DownloadState.Queued, toDownloadStates(listOf(retry, failed))[md5])
    }

    @Test
    fun `running progress without a size reports no total`() {
        val states = toDownloadStates(
            listOf(info('a', WorkInfo.State.RUNNING, progress = workDataOf(AnnasDownloadWorker.KEY_BYTES to 10L, AnnasDownloadWorker.KEY_TOTAL to -1L)))
        )
        assertEquals(DownloadState.Running(10, null), states["a".repeat(32)])
    }
}
