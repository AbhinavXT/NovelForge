package com.abhinavxt.novelforge.ui.viewmodel

import com.abhinavxt.novelforge.data.epub.TocEntry
import com.abhinavxt.novelforge.data.model.Chapter

/** One line of the reader's contents sheet, resolved to a chapter it can open. */
data class ReaderContentsEntry(
    val title: String,
    val depth: Int,
    val chapterId: String,
    val chapterUrl: String,
    val paragraph: Int
)

/**
 * What the contents sheet lists: the book's own table of contents when it
 * has one ([fromBook]), otherwise its chapters.
 */
data class ReaderContents(
    val entries: List<ReaderContentsEntry>,
    val fromBook: Boolean,
    /** Every chapter id in reading order, to place chapters the contents skip. */
    val chapterOrder: List<String> = entries.map { it.chapterId }.distinct()
) {

    /**
     * The entry the reader is in: the last one at or before [paragraph] of
     * [chapterId], or, when the chapter has no entry of its own (a part of
     * the book the contents skip), the last entry before that chapter.
     */
    fun currentIndex(chapterId: String, paragraph: Int): Int {
        val inChapter = entries.indices.filter { entries[it].chapterId == chapterId }
        if (inChapter.isNotEmpty()) {
            return inChapter.lastOrNull { entries[it].paragraph <= paragraph } ?: inChapter.first()
        }
        val position = chapterOrder.indexOf(chapterId)
        if (position < 0) return -1
        val earlier = chapterOrder.subList(0, position).toSet()
        return entries.indexOfLast { it.chapterId in earlier }
    }

    companion object {
        val EMPTY = ReaderContents(emptyList(), fromBook = false)

        /**
         * @param toc the book's stored table of contents (chapter numbers)
         * @param chapters the novel's chapters, in reading order
         */
        fun build(toc: List<TocEntry>, chapters: List<Chapter>): ReaderContents {
            val byNumber = chapters.associateBy { it.number }
            val fromToc = toc.mapNotNull { entry ->
                val chapter = byNumber[entry.chapter] ?: return@mapNotNull null
                ReaderContentsEntry(
                    title = entry.title.ifBlank { chapter.title },
                    depth = entry.depth.coerceIn(0, MAX_DEPTH),
                    chapterId = chapter.id,
                    chapterUrl = chapter.url,
                    paragraph = entry.paragraph.coerceAtLeast(0)
                )
            }
            val order = chapters.map { it.id }
            if (fromToc.isNotEmpty()) return ReaderContents(fromToc, fromBook = true, chapterOrder = order)
            return ReaderContents(
                chapters.map { ReaderContentsEntry(it.title, 0, it.id, it.url, 0) },
                fromBook = false,
                chapterOrder = order
            )
        }

        /** Deeper nesting than this is drawn at this depth, so titles keep room. */
        const val MAX_DEPTH = 3
    }
}
