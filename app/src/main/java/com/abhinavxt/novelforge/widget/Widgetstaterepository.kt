package com.abhinavxt.novelforge.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Small pre-computed cache that the ContinueReadingWidget reads.
 *
 * Why a separate cache instead of querying Room from the widget? Glance's
 * provideGlance runs in a short-lived background context and must render
 * fast. Hitting Room + resolving cover URLs on every widget refresh would
 * exceed the widget's time budget. Instead, we maintain a tiny DataStore
 * with exactly the fields the widget needs, and the app pushes updates
 * at the moments when state actually changes.
 */
class WidgetStateRepository(private val context: Context) {

    companion object {
        private const val TAG = "WidgetStateRepo"

        private val KEY_HAS_DATA = longPreferencesKey("has_data")
        private val KEY_NOVEL_ID = stringPreferencesKey("novel_id")
        private val KEY_NOVEL_TITLE = stringPreferencesKey("novel_title")
        private val KEY_NOVEL_AUTHOR = stringPreferencesKey("novel_author")
        private val KEY_CHAPTER_ID = stringPreferencesKey("chapter_id")
        private val KEY_CHAPTER_URL = stringPreferencesKey("chapter_url")
        private val KEY_CHAPTER_TITLE = stringPreferencesKey("chapter_title")
        private val KEY_CHAPTER_NUMBER = longPreferencesKey("chapter_number")
        private val KEY_TOTAL_CHAPTERS = longPreferencesKey("total_chapters")
        private val KEY_NOVEL_URL = stringPreferencesKey("novel_url")
        private val KEY_COVER_PATH = stringPreferencesKey("cover_path")
        private val KEY_UPDATED_AT = longPreferencesKey("updated_at")
    }

    sealed class WidgetState {
        data object Empty : WidgetState()
        data class HasNovel(
            val novelId: String,
            val novelTitle: String,
            val novelAuthor: String,
            val novelUrl: String,
            val chapterId: String,
            val chapterUrl: String,
            val chapterTitle: String,
            val chapterNumber: Int,
            val totalChapters: Int,
            val coverPath: String?,
            val updatedAt: Long
        ) : WidgetState() {
            val progress: Float
                get() = if (totalChapters > 0) {
                    (chapterNumber.toFloat() / totalChapters.toFloat()).coerceIn(0f, 1f)
                } else 0f
        }
    }

    private val dataStore = context.widgetDataStore

    val state: Flow<WidgetState> = dataStore.data.map { prefs -> prefs.toState() }

    suspend fun getStateOnce(): WidgetState {
        return dataStore.data.first().toState()
    }

    private suspend fun setContinueReading(
        novelId: String,
        novelTitle: String,
        novelAuthor: String,
        novelUrl: String,
        chapterId: String,
        chapterUrl: String,
        chapterTitle: String,
        chapterNumber: Int,
        totalChapters: Int,
        coverPath: String?
    ) {
        dataStore.edit { prefs ->
            prefs[KEY_HAS_DATA] = 1L
            prefs[KEY_NOVEL_ID] = novelId
            prefs[KEY_NOVEL_TITLE] = novelTitle
            prefs[KEY_NOVEL_AUTHOR] = novelAuthor
            prefs[KEY_NOVEL_URL] = novelUrl
            prefs[KEY_CHAPTER_ID] = chapterId
            prefs[KEY_CHAPTER_URL] = chapterUrl
            prefs[KEY_CHAPTER_TITLE] = chapterTitle
            prefs[KEY_CHAPTER_NUMBER] = chapterNumber.toLong()
            prefs[KEY_TOTAL_CHAPTERS] = totalChapters.toLong()
            if (coverPath != null) {
                prefs[KEY_COVER_PATH] = coverPath
            } else {
                prefs.remove(KEY_COVER_PATH)
            }
            prefs[KEY_UPDATED_AT] = System.currentTimeMillis()
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    /**
     * Rebuild widget state from the repository's current most-recent
     * progress. This is the main entry point — Application wires
     * NovelRepository.onProgressSaved to call this.
     *
     * Uses getNovelById() for novel metadata and getChaptersOnce() for
     * the chapter list (getNovelById returns a Novel with empty chapters
     * — see toDomainModel in NovelRepository).
     */
    suspend fun rebuildFromRepository(repository: NovelRepository) {
        try {
            val progress = repository.getMostRecentProgress()
            if (progress == null) {
                clear()
                WidgetUpdater.requestUpdate(context)
                return
            }

            val novel = repository.getNovelById(progress.novelId)
            if (novel == null) {
                // Novel no longer in library — clear widget.
                clear()
                WidgetUpdater.requestUpdate(context)
                return
            }

            // getNovelById returns the novel with empty chapters list;
            // fetch chapters separately via the dedicated helper.
            val chapters = repository.getChaptersOnce(progress.novelId)
            val chapter = chapters.firstOrNull { it.id == progress.currentChapterId }
                ?: chapters.firstOrNull()
                ?: run {
                    clear()
                    WidgetUpdater.requestUpdate(context)
                    return
                }

            val coverPath = resolveCoverPath(novel.coverUrl)

            setContinueReading(
                novelId = novel.id,
                novelTitle = novel.title,
                novelAuthor = novel.author,
                // Novel's domain model doesn't carry a url field; we
                // reconstruct it via SourceManager — same pattern used
                // by Navigation.kt throughout the app.
                novelUrl = com.abhinavxt.novelforge.data.source.SourceManager
                    .constructNovelUrl(novel.id),
                chapterId = chapter.id,
                chapterUrl = chapter.url,
                chapterTitle = chapter.title,
                chapterNumber = progress.currentChapterNumber,
                totalChapters = chapters.size,
                coverPath = coverPath
            )
            WidgetUpdater.requestUpdate(context)
        } catch (e: Exception) {
            Logger.e(TAG, "rebuildFromRepository failed", e)
            // Don't clear on error — old state is better than empty
            // state when something transient (DB lock) fails.
        }
    }

    /**
     * Resolve a novel's cover URL to a local file path.
     *  1. null → widget shows gradient fallback
     *  2. "/..." → local EPUB cover path
     *  3. http(s) → look up in Coil's disk cache (we don't trigger
     *     downloads — if not cached, fall back to gradient)
     */
    private fun resolveCoverPath(coverUrl: String?): String? {
        if (coverUrl == null) return null
        if (coverUrl.startsWith("/")) {
            return if (File(coverUrl).exists()) coverUrl else null
        }
        return try {
            val imageLoader = coil.Coil.imageLoader(context)
            val snapshot = imageLoader.diskCache?.openSnapshot(coverUrl)
            val path = snapshot?.data?.toFile()?.absolutePath
            snapshot?.close()
            path
        } catch (e: Exception) {
            Logger.d(TAG, "cover not in Coil cache: $coverUrl")
            null
        }
    }

    private fun Preferences.toState(): WidgetState {
        val has = this[KEY_HAS_DATA] ?: 0L
        if (has == 0L) return WidgetState.Empty

        return WidgetState.HasNovel(
            novelId = this[KEY_NOVEL_ID] ?: return WidgetState.Empty,
            novelTitle = this[KEY_NOVEL_TITLE] ?: "",
            novelAuthor = this[KEY_NOVEL_AUTHOR] ?: "",
            novelUrl = this[KEY_NOVEL_URL] ?: "",
            chapterId = this[KEY_CHAPTER_ID] ?: return WidgetState.Empty,
            chapterUrl = this[KEY_CHAPTER_URL] ?: "",
            chapterTitle = this[KEY_CHAPTER_TITLE] ?: "",
            chapterNumber = (this[KEY_CHAPTER_NUMBER] ?: 0L).toInt(),
            totalChapters = (this[KEY_TOTAL_CHAPTERS] ?: 0L).toInt(),
            coverPath = this[KEY_COVER_PATH],
            updatedAt = this[KEY_UPDATED_AT] ?: 0L
        )
    }
}

private val Context.widgetDataStore by preferencesDataStore("continue_reading_widget")