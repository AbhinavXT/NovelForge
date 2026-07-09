package com.abhinavxt.novelforge.util

/**
 * Single source of truth for how chapter content is split into
 * paragraphs. The reader's scroll positions, saved progress,
 * bookmarks, highlights AND full-text-search jump targets are all
 * paragraph indexes into this split — so the logic must live in
 * exactly one place. Extracted verbatim from ReaderViewModel.
 */
object ParagraphSplitter {

    fun split(content: String): List<String> {
        return content
            .split("\n\n", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Index of the first paragraph containing [query]
     * (case-insensitive). Used to turn a full-text search hit into
     * a reader jump target. Falls back to matching the first token
     * alone — the FTS layer treats multi-word queries as AND across
     * the whole chapter, so all words may not share one paragraph.
     * Returns 0 (top of chapter) if nothing matches locally.
     */
    fun findFirstMatch(paragraphs: List<String>, query: String): Int {
        val q = query.trim()
        if (q.isEmpty()) return 0

        val full = paragraphs.indexOfFirst { it.contains(q, ignoreCase = true) }
        if (full >= 0) return full

        val firstToken = q.split(Regex("\\s+")).firstOrNull() ?: return 0
        val token = paragraphs.indexOfFirst { it.contains(firstToken, ignoreCase = true) }
        return if (token >= 0) token else 0
    }
}
