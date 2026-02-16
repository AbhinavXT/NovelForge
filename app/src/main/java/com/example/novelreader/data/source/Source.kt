package com.example.novelreader.data.source

import com.example.novelreader.data.model.Chapter
import com.example.novelreader.data.model.Novel
import com.example.novelreader.data.model.NovelPreview

// Interface that all novel sources must implement.
// This abstraction lets us add new sources (WebNovel, ScribbleHub, etc.)
// without changing the rest of the app.
interface Source {
    // Unique identifier for this source
    val id: String

    // Display name shown to users
    val name: String

    // Base URL of the website
    val baseUrl: String

    // Search for novels by query
    suspend fun search(query: String): List<NovelPreview>

    // Get popular/trending novels (for browse screen)
    suspend fun getPopular(page: Int = 1): List<NovelPreview>

    // Get full novel details including chapter list
    suspend fun getNovelDetails(novelUrl: String): Novel?

    // Get the content of a specific chapter
    suspend fun getChapterContent(chapterUrl: String): String?
}