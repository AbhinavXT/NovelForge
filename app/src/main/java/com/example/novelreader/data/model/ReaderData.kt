package com.example.novelreader.data.model

// Holds all information needed to display a chapter in the reader
data class ReaderChapter(
    val novelId: String,
    val novelTitle: String,
    val chapterId: String,
    val chapterTitle: String,
    val chapterNumber: Int,
    val content: String,
    val totalChapters: Int,

    // Navigation info
    val prevChapter: ChapterNavInfo?,
    val nextChapter: ChapterNavInfo?
)

// Minimal info needed to navigate to a chapter
data class ChapterNavInfo(
    val id: String,
    val title: String,
    val url: String
)