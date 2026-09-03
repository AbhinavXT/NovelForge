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

    /**
     * Display floor. Applied when the codex is READ, never when it is
     * written — filtering at write time made the result depend on how
     * the scan was chunked. A name mentioned once in each of six
     * chapters totals six if all six are scanned together and zero if
     * they're scanned one at a time, because each pass discarded its
     * own sub-threshold count and the watermark guarantees those
     * chapters are never revisited. Scanning as you download is the
     * normal pattern, so the common case was the broken one.
     */
    const val MIN_OCCURRENCES = 3

    /**
     * Sub-threshold names are kept while they might still be
     * accumulating, and dropped once they've gone this many chapters
     * without recurring. Without it, storing every partial count means
     * storing every capitalized token in the book forever; with it,
     * the table holds real entries plus one window's worth of
     * candidates, which is bounded by chapter content rather than by
     * novel length.
     */
    private const val PRUNE_WINDOW = 150

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
        data class Agg(
            var occurrences: Int,
            var chapterCount: Int,
            var firstChapter: Int,
            var lastChapter: Int,
            var speechHits: Int
        )
        val agg = HashMap<String, Agg>()

        toScan.forEachIndexed { idx, chapter ->
            coroutineContext.ensureActive()
            val content = repository.getDownloadedChapterContent(chapter.id) ?: return@forEachIndexed
            val paragraphs = ParagraphSplitter.split(content)
            for ((name, stats) in NameExtractor.extract(paragraphs)) {
                val a = agg.getOrPut(name) { Agg(0, 0, chapter.number, chapter.number, 0) }
                a.occurrences += stats.occurrences
                a.chapterCount += 1
                a.speechHits += stats.speechHits
                if (chapter.number < a.firstChapter) a.firstChapter = chapter.number
                if (chapter.number > a.lastChapter) a.lastChapter = chapter.number
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
                firstChapterNumber = minOf(prev?.firstChapterNumber ?: Int.MAX_VALUE, a.firstChapter),
                lastChapterNumber = maxOf(prev?.lastChapterNumber ?: 0, a.lastChapter),
                speechHits = (prev?.speechHits ?: 0) + a.speechHits
            )
        }

        // Everything is written, including counts below the display
        // floor — they're the partial evidence that used to be thrown
        // away. Nothing is filtered here.
        repository.upsertCodexNames(merged)

        // Then drop the candidates that have clearly gone nowhere:
        // still under the floor, and last seen a whole window ago.
        // A name at the floor is safe regardless of how old it is.
        val watermarkAfter = toScan.last().number
        // merged first: distinctBy keeps the first hit, and for a name
        // touched in this pass the merged row is the current one. The
        // other order would judge a name that just crossed the floor
        // by its pre-scan count and prune it on the way in.
        val stale = (merged + existing.values)
            .distinctBy { it.name }
            .filter { it.occurrences < MIN_OCCURRENCES && it.lastChapterNumber < watermarkAfter - PRUNE_WINDOW }
        if (stale.isNotEmpty()) {
            repository.deleteCodexNames(novelId, stale.map { it.name })
            Logger.d("CodexEngine", "Pruned ${stale.size} stale candidates for $novelId")
        }
        repository.saveCodexScanInfo(
            CodexScanInfoEntity(
                novelId = novelId,
                lastScannedNumber = toScan.last().number,
                scannedChapters = (repository.getCodexScanInfo(novelId)?.scannedChapters ?: 0) + toScan.size,
                updatedAt = System.currentTimeMillis()
            )
        )
        Logger.d(
            "CodexEngine",
            "Scanned ${toScan.size} chapters for $novelId, " +
                    "${merged.count { it.occurrences >= MIN_OCCURRENCES }} entries " +
                    "(${merged.size} tracked)"
        )
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