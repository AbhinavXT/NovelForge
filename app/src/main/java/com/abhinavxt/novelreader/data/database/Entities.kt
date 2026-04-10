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
    val lastUpdatedAt: Long = System.currentTimeMillis()
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
    val font: String = "SANS_SERIF"
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