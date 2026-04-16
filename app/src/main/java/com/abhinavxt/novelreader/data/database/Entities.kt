package com.abhinavxt.novelreader.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "novels")
data class NovelEntity(
    @PrimaryKey
    val id: String,

    val title: String,
    val author: String,
    val coverUrl: String?,
    val description: String,
    val source: String,
    val status: String,
    val totalChapters: Int,

    val addedToLibraryAt: Long = System.currentTimeMillis(),
    val lastUpdatedAt: Long = System.currentTimeMillis(),

    // ── NEW: Tracks the chapter count at last user-viewed state ──
    // When UpdateCheckerWorker finds new chapters, totalChapters updates
    // but previousTotalChapters stays at the old value. The detail screen
    // uses the delta to show "X new chapters since you last checked."
    // Set to totalChapters when user opens the detail screen.
    val previousTotalChapters: Int = 0
)

@Entity(tableName = "chapters")
data class ChapterEntity(
    @PrimaryKey
    val id: String,
    val novelId: String,
    val number: Int,
    val title: String,
    val url: String,
    val isDownloaded: Boolean = false,
    val content: String? = null,
    val downloadedAt: Long? = null
)

@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey
    val novelId: String,
    val currentChapterId: String,
    val currentChapterNumber: Int = 0,
    val paragraphIndex: Int = 0,
    val chaptersRead: Int = 0,
    val lastReadAt: Long
)

@Entity(tableName = "reader_settings")
data class ReaderSettingsEntity(
    @PrimaryKey
    val id: Int = 1,

    val fontSize: Int = 16,
    val theme: String = "LIGHT",
    val lineSpacing: Float = 1.6f,
    val font: String = "SANS_SERIF",

    // ── NEW: reading mode, page transition, margins ─────────────
    val readingMode: String = "SCROLL",           // "SCROLL" or "PAGED"
    val pageTransition: String = "SLIDE",         // "SLIDE", "FADE", "CURL", "NONE"
    val horizontalMargin: Int = 16,               // dp, 8–40
    val keepScreenOn: Boolean = true,
    val volumeKeyNavigation: Boolean = false
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val novelId: String,
    val chapterId: String,
    val chapterUrl: String,
    val chapterNumber: Int,
    val chapterTitle: String,

    val paragraphIndex: Int,
    val textSnippet: String,
    val note: String? = null,

    val createdAt: Long = System.currentTimeMillis()
)

// ============ Pronunciation Dictionary ============

@Entity(tableName = "pronunciation_entries")
data class PronunciationEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val word: String,          // Original word (e.g. "Xiulan")
    val replacement: String,   // Phonetic replacement (e.g. "shoo-lan")

    val createdAt: Long = System.currentTimeMillis()
)

// ============ Reading Stats ============

@Entity(tableName = "reading_stats")
data class ReadingStatEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val novelId: String,
    val chapterId: String,
    val wordsRead: Int,           // Word count of the chapter
    val readingTimeMs: Long,      // Time spent reading this session (ms)
    val completedAt: Long = System.currentTimeMillis()
)

// ============ Highlights & Annotations ============

/**
 * A highlight is a user-selected span of text within a chapter paragraph.
 * Users can optionally attach a note (annotation) to each highlight.
 *
 * Color is stored as an enum name (YELLOW, GREEN, BLUE, PINK, PURPLE)
 * so it renders consistently across themes.
 *
 * The text snippet is stored so we can display highlights even if
 * the source content changes (e.g. web novel author edits chapter).
 *
 * Unlike bookmarks (which mark a paragraph position), highlights mark
 * specific text selections and can have multiple per paragraph.
 */
@Entity(tableName = "highlights")
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val novelId: String,
    val chapterId: String,
    val chapterNumber: Int,
    val chapterTitle: String,

    // Position within the chapter — which paragraph and where in it
    val paragraphIndex: Int,
    val startOffset: Int,        // Character offset within the paragraph
    val endOffset: Int,          // Character offset (exclusive) within the paragraph

    // The actual highlighted text — stored for display in highlights list
    val selectedText: String,

    // Optional user annotation
    val note: String? = null,

    // Highlight color — stored as enum name, rendered per-theme
    val color: String = "YELLOW",   // HighlightColor enum name

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Highlight color palette. Each color has a translucent version for
 * the reader overlay and an opaque version for the highlights list.
 */
enum class HighlightColor(val displayName: String) {
    YELLOW("Yellow"),
    GREEN("Green"),
    BLUE("Blue"),
    PINK("Pink"),
    PURPLE("Purple"),
    ORANGE("Orange")
}