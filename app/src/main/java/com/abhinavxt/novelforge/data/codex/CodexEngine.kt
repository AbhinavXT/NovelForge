package com.abhinavxt.novelforge.data.codex

import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.database.CodexNameEntity
import com.abhinavxt.novelforge.data.database.CodexScanInfoEntity
import com.abhinavxt.novelforge.util.Logger
import com.abhinavxt.novelforge.util.ParagraphSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Orchestrates codex scans: streams downloaded chapters one at a
 * time (same memory discipline as EPUB export — one chapter's text
 * in memory, ever), runs NameExtractor on each, and merges the
 * aggregates into codex_names.
 *
 * Incremental by design: codex_scan_info stores the highest chapter
 * NUMBER scanned per novel, so a rescan after downloading new
 * chapters only touches the new ones. Merge math stays correct
 * because each chapter is processed exactly once:
 * occurrences/chapterCount add, firstChapterNumber takes min.
 */
object CodexEngine {

    /** Names below this total count are noise, not codex entries. */
    private const val MIN_OCCURRENCES = 3

    data class ScanProgress(val scanned: Int, val total: Int)

    /**
     * Scans un-scanned downloaded chapters. Returns the number of
     * chapters processed (0 = codex already up to date).
     */
    suspend fun scan(
        repository: NovelRepository,
        novelId: String,
        onProgress: (ScanProgress) -> Unit = {}
    ): Int = withContext(Dispatchers.Default) {
        val watermark = repository.getCodexScanInfo(novelId)?.lastScannedNumber ?: -1
        val toScan = repository.getChaptersOnce(novelId)
            .filter { it.isDownloaded && it.number > watermark }
            .sortedBy { it.number }
        if (toScan.isEmpty()) return@withContext 0

        // Aggregate for THIS scan pass only; merged with stored rows at the end.
        data class Agg(var occurrences: Int, var chapterCount: Int, var firstChapter: Int)
        val agg = HashMap<String, Agg>()

        toScan.forEachIndexed { idx, chapter ->
            coroutineContext.ensureActive()
            val content = repository.getDownloadedChapterContent(chapter.id) ?: return@forEachIndexed
            val paragraphs = ParagraphSplitter.split(content)
            for ((name, count) in NameExtractor.extract(paragraphs)) {
                val a = agg.getOrPut(name) { Agg(0, 0, chapter.number) }
                a.occurrences += count
                a.chapterCount += 1
                if (chapter.number < a.firstChapter) a.firstChapter = chapter.number
            }
            onProgress(ScanProgress(idx + 1, toScan.size))
        }

        // Merge with previously stored aggregates. REPLACE upsert is
        // safe because we read-modify-write the union here.
        val existing = repository.getCodexNamesOnce(novelId).associateBy { it.name }
        val merged = agg.map { (name, a) ->
            val prev = existing[name]
            CodexNameEntity(
                novelId = novelId,
                name = name,
                occurrences = (prev?.occurrences ?: 0) + a.occurrences,
                chapterCount = (prev?.chapterCount ?: 0) + a.chapterCount,
                firstChapterNumber = minOf(prev?.firstChapterNumber ?: Int.MAX_VALUE, a.firstChapter)
            )
        }.filter { it.occurrences >= MIN_OCCURRENCES }

        repository.upsertCodexNames(merged)
        repository.saveCodexScanInfo(
            CodexScanInfoEntity(
                novelId = novelId,
                lastScannedNumber = toScan.last().number,
                scannedChapters = (repository.getCodexScanInfo(novelId)?.scannedChapters ?: 0) + toScan.size,
                updatedAt = System.currentTimeMillis()
            )
        )
        Logger.d("CodexEngine", "Scanned ${toScan.size} chapters for $novelId, ${merged.size} entries")
        toScan.size
    }

    /**
     * Full rebuild — used when the user wants to re-run with a
     * clean slate (e.g. after re-downloading fixed chapters).
     */
    suspend fun rebuild(
        repository: NovelRepository,
        novelId: String,
        onProgress: (ScanProgress) -> Unit = {}
    ): Int {
        repository.clearCodex(novelId)
        return scan(repository, novelId, onProgress)
    }

    /**
     * FTS MATCH phrase for a codex name: internal quotes stripped,
     * whole name quoted so multi-word names match as a phrase
     * ("Li Wei", not Li AND Wei anywhere in the chapter).
     */
    fun toPhraseQuery(name: String): String =
        "\"" + name.replace("\"", " ").trim() + "\""
}
