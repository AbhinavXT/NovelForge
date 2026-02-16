package com.example.novelreader.data.database

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
    val paragraphIndex: Int = 0,  // Which paragraph user was reading (changed from scrollPosition)
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