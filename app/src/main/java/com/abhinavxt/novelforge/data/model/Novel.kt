package com.abhinavxt.novelforge.data.model

// A data class is a special Kotlin class designed to hold data.
// The compiler automatically generates equals(), hashCode(), toString(), and copy() methods.
// This is perfect for model objects where we care about the data, not behavior.
//
// Think of this as a struct in C++ or a plain object in JavaScript,
// but with automatic utility methods that make comparison and debugging easier.
data class NovelPreview(
    // Each property is declared in the primary constructor.
    // 'val' means these are read-only (immutable) after creation.

    val id: String,           // Unique identifier for this novel
    val title: String,        // The novel's name
    val author: String,       // Who wrote it
    val coverUrl: String?,    // URL to cover image (nullable - might not exist)
    val description: String,  // Short summary
    val source: String        // Which website this came from (e.g., "Royal Road")
)

// Chapter represents a single chapter in a novel.
// Each chapter has its own ID, a number for ordering, a title, and a URL
// where we can fetch its content (for when we implement web scraping).
data class Chapter(
    val id: String,
    val number: Int,          // Chapter number for display and ordering
    val title: String,        // Chapter title like "Chapter 1: The Beginning"
    val url: String,           // URL to fetch chapter content from
    val isDownloaded: Boolean = false
)

// Novel is the full detail model with everything we know about a novel.
// This is used on the detail screen where we show complete information.
// It contains a list of chapters, unlike NovelPreview which is just a summary.
data class Novel(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val description: String,
    val source: String,
    val status: String,       // "Ongoing", "Completed", "Hiatus"
    val chapters: List<Chapter>
)

// We call it NovelPreview because this is the summary shown in search results.
// Later we'll have a full Novel class with chapters, reading progress, etc.
// Starting with a simpler model keeps things manageable.