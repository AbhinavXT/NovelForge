package com.abhinavxt.novelreader.data

import com.abhinavxt.novelreader.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Silently pre-fetches upcoming chapters so users don't see a loading screen
 * when navigating to the next chapter.
 *
 * How it works:
 *  1. After a chapter loads successfully, [prefetchAround] is called with the
 *     current chapter index and the full chapter list.
 *  2. The next [PREFETCH_COUNT] chapters that aren't already downloaded are
 *     fetched in the background and stored in an in-memory LRU cache.
 *  3. When [getIfCached] is called (from the repository), the cached content
 *     is returned instantly, skipping the network request entirely.
 *
 * Cache eviction: oldest entries are dropped when the cache exceeds [MAX_CACHE_SIZE].
 * The cache is per-session — it's cleared when the user leaves the reader.
 *
 * Thread safety: all cache access is synchronized. Background fetches use
 * [SupervisorJob] so one failure doesn't cancel others.
 */
class ChapterPrefetcher(
    private val repository: NovelRepository
) {
    companion object {
        private const val TAG = "ChapterPrefetcher"

        /** How many chapters ahead to pre-fetch */
        private const val PREFETCH_COUNT = 2

        /** Max chapters to keep in memory (each ~10-50KB of text) */
        private const val MAX_CACHE_SIZE = 5
    }

    // LRU cache: chapterId → content string
    // LinkedHashMap with accessOrder=true gives us LRU eviction
    private val cache = object : LinkedHashMap<String, String>(MAX_CACHE_SIZE + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefetchJob: Job? = null

    /**
     * Check if a chapter's content is already in the prefetch cache.
     * Returns the content string, or null if not cached.
     */
    @Synchronized
    fun getIfCached(chapterId: String): String? {
        val content = cache[chapterId]
        if (content != null) {
            Logger.d(TAG, "Cache HIT: $chapterId (${content.length} chars)")
        }
        return content
    }

    /**
     * Pre-fetch the next [PREFETCH_COUNT] chapters from [currentIndex] onwards.
     *
     * Skips chapters that are:
     *  - Already in the cache
     *  - Already downloaded to the database
     *
     * Called by ReaderViewModel after each successful chapter load.
     *
     * @param novelId       The current novel ID
     * @param currentIndex  Index of the current chapter in [chapters]
     * @param chapters      Full ordered chapter list (id, url pairs)
     */
    fun prefetchAround(
        novelId: String,
        currentIndex: Int,
        chapters: List<ChapterRef>
    ) {
        // Cancel any in-progress prefetch from the previous chapter
        prefetchJob?.cancel()

        prefetchJob = scope.launch {
            val startIndex = currentIndex + 1
            val endIndex = (startIndex + PREFETCH_COUNT).coerceAtMost(chapters.size)

            for (i in startIndex until endIndex) {
                val chapter = chapters[i]

                // Skip if already cached
                if (getIfCached(chapter.id) != null) continue

                // Skip if already downloaded in the database
                try {
                    if (repository.isChapterDownloaded(chapter.id)) {
                        Logger.d(TAG, "Skip prefetch ${chapter.id} — already downloaded")
                        continue
                    }
                } catch (e: Exception) {
                    // DB check failed, try fetching anyway
                }

                try {
                    Logger.d(TAG, "Prefetching chapter ${i + 1}: ${chapter.id}")
                    val content = repository.fetchChapterContent(novelId, chapter.url)
                    if (content != null) {
                        synchronized(this@ChapterPrefetcher) {
                            cache[chapter.id] = content
                        }
                        Logger.d(TAG, "Prefetched ${chapter.id} (${content.length} chars)")
                    }
                } catch (e: Exception) {
                    // Prefetch is best-effort — don't crash on failure
                    Logger.e(TAG, "Prefetch failed for ${chapter.id}", e)
                }
            }
        }
    }

    /**
     * Clear the cache. Called when leaving the reader screen.
     */
    @Synchronized
    fun clear() {
        prefetchJob?.cancel()
        cache.clear()
        Logger.d(TAG, "Cache cleared")
    }

    /**
     * Lightweight reference to a chapter for prefetch scheduling.
     * Avoids passing full Chapter objects which carry more data than needed.
     */
    data class ChapterRef(
        val id: String,
        val url: String
    )
}
