package com.abhinavxt.novelforge.data.epub

import com.abhinavxt.novelforge.util.ParagraphSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TextDocumentParser is pure heuristics over arbitrary user files, so its
 * behaviour is defined by examples. All assertions verified against the real
 * implementation.
 */
class TextDocumentParserTest {

    // ── Markdown ─────────────────────────────────────────────────────────

    private val markdownBook = """---
title: The Sword Path
author: Abhinav
description: A cultivation tale.
---

# The Sword Path

## Chapter 1: Awakening

Li Wei opened his eyes. The **morning** light was harsh.

He stood up slowly.

## Chapter 2: The Elder

Elder Chen was waiting at the [gate](http://example.com).

## Chapter 3: Departure

They left before dawn. It was a long walk and nobody spoke.
"""

    @Test
    fun `reads yaml front matter`() {
        val book = TextDocumentParser.parse(markdownBook, "sword.md", markdown = true)
        assertEquals("The Sword Path", book.title)
        assertEquals("Abhinav", book.author)
        assertEquals("A cultivation tale.", book.description)
    }

    @Test
    fun `splits on the shallowest repeated heading level`() {
        // One '#' and three '##'. Splitting on '#' would yield a single
        // chapter containing the whole book.
        val book = TextDocumentParser.parse(markdownBook, "sword.md", markdown = true)
        assertEquals(3, book.chapters.size)
        assertEquals("Chapter 1: Awakening", book.chapters[0].title)
        assertEquals(listOf(1, 2, 3), book.chapters.map { it.order })
    }

    @Test
    fun `book title is not echoed into the first chapter body`() {
        // The lone '#' heading is promoted to the book title, so it must not
        // also survive as body text -- the same duplication that affected
        // EPUB imports via the untouched <head><title>.
        val book = TextDocumentParser.parse(markdownBook, "sword.md", markdown = true)
        assertFalse(book.chapters[0].content.contains("The Sword Path"))
    }

    @Test
    fun `title comes from a lone heading when there is no front matter`() {
        val md = "# My Book\n\n## One\n\nBody text long enough to be a real chapter here.\n\n" +
                "## Two\n\nMore body text long enough to be a real chapter here.\n"
        assertEquals("My Book", TextDocumentParser.parse(md, "x.md", true).title)
    }

    @Test
    fun `inline markdown formatting is removed`() {
        val book = TextDocumentParser.parse(markdownBook, "sword.md", markdown = true)
        assertTrue(book.chapters[0].content.contains("morning light"))
        assertFalse(book.chapters[0].content.contains("**"))
        assertTrue(book.chapters[1].content.contains("gate"))
        assertFalse(book.chapters[1].content.contains("example.com"))
    }

    @Test
    fun `text before the first heading is kept not discarded`() {
        val md = "A dedication to my cat.\n\n## One\n\nChapter one body, long enough to survive.\n\n" +
                "## Two\n\nChapter two body, long enough to survive.\n"
        val book = TextDocumentParser.parse(md, "d.md", true)
        assertTrue(book.chapters[0].content.contains("dedication to my cat"))
        assertEquals(2, book.chapters.size)
    }

    // ── Plain text ───────────────────────────────────────────────────────

    private val plainBook = """CHAPTER ONE

It was a bright cold day in April, and the clocks
were striking thirteen. Winston Smith slipped
quickly through the glass doors.

CHAPTER TWO

The hallway smelt of boiled cabbage and old rag mats.
At one end a coloured poster had been tacked up.

Prologue

This one comes last despite its name, and it runs long
enough to stand as a chapter in its own right.
"""

    @Test
    fun `splits plain text on chapter heading lines`() {
        val book = TextDocumentParser.parse(plainBook, "1984.txt", markdown = false)
        assertEquals(3, book.chapters.size)
        assertEquals("CHAPTER ONE", book.chapters[0].title)
    }

    @Test
    fun `hard wrapped lines are joined into paragraphs`() {
        // ParagraphSplitter treats EVERY newline as a paragraph break, so a
        // 70-column .txt would otherwise become hundreds of one-line
        // paragraphs and wreck bookmark indexes.
        val book = TextDocumentParser.parse(plainBook, "1984.txt", markdown = false)
        assertTrue(
            book.chapters[0].content.startsWith(
                "It was a bright cold day in April, and the clocks were striking thirteen."
            )
        )
        assertEquals(1, ParagraphSplitter.split(book.chapters[0].content).size)
    }

    @Test
    fun `falls back to the file name for the title`() {
        assertEquals("1984", TextDocumentParser.parse(plainBook, "1984.txt", false).title)
        assertEquals("Big Novel", TextDocumentParser.parse("hi there", "big_novel.txt", false).title)
    }

    @Test
    fun `splits on thematic breaks when there are no headings`() {
        val text = "First part, long enough to survive the merge threshold.\n\n* * *\n\n" +
                "Second part, long enough to survive the merge threshold.\n\n* * *\n\n" +
                "Third part, long enough to survive the merge threshold.\n"
        val book = TextDocumentParser.parse(text, "story.txt", false)
        assertEquals(3, book.chapters.size)
        assertEquals("Part 1", book.chapters[0].title)
        assertFalse(book.chapters[0].content.contains("*"))
    }

    @Test
    fun `splits on setext underlined headings`() {
        val text = "Opening\n=======\n\nOpening body text long enough to survive merging.\n\n" +
                "Closing\n=======\n\nClosing body text long enough to survive merging.\n"
        val book = TextDocumentParser.parse(text, "s.txt", false)
        assertEquals(2, book.chapters.size)
        assertEquals("Opening", book.chapters[0].title)
    }

    // ── Fallbacks and edge cases ─────────────────────────────────────────

    @Test
    fun `structureless short document becomes one chapter`() {
        val book = TextDocumentParser.parse("Just a short note with no structure.", "note.txt", false)
        assertEquals(1, book.chapters.size)
        assertEquals("Chapter 1", book.chapters[0].title)
    }

    @Test
    fun `structureless long document is split at paragraph boundaries`() {
        val long = (1..900).joinToString("\n\n") {
            "Paragraph number $it with filler text to add length to the document."
        }
        val book = TextDocumentParser.parse(long, "big.txt", false)
        assertTrue(book.chapters.size > 1)
        assertEquals("Part 1", book.chapters[0].title)
        // No paragraph may be cut in half by the size split.
        assertTrue(book.chapters.all { it.content.trim().endsWith(".") })
    }

    @Test
    fun `empty and whitespace-only files produce no chapters`() {
        assertEquals(0, TextDocumentParser.parse("", "e.txt", false).chapters.size)
        assertEquals(0, TextDocumentParser.parse("  \n\n \n", "e.txt", false).chapters.size)
    }

    @Test
    fun `strips byte order mark and handles CRLF`() {
        val bom = TextDocumentParser.parse("\uFEFFHello there friend.", "e.txt", false)
        assertFalse(bom.chapters[0].content.startsWith("\uFEFF"))
        assertEquals(1, TextDocumentParser.parse("line one\r\nline two\r\n", "e.txt", false).chapters.size)
    }

    @Test
    fun `unterminated front matter does not swallow the document`() {
        val book = TextDocumentParser.parse("---\ntitle: x\n\nbody text here", "e.md", true)
        assertTrue(book.chapters.isNotEmpty())
    }

    @Test
    fun `under-length trailing block merges into the previous chapter`() {
        // A heading whose body is shorter than MIN_CHAPTER_CHARS (40) is
        // treated as a stray fragment, not a chapter — otherwise a heading
        // immediately followed by another emits a near-empty entry. The text
        // is folded into the previous chapter rather than dropped.
        val text = "CHAPTER ONE\n\nA body long enough to stand on its own as a real chapter.\n\n" +
                "CHAPTER TWO\n\nToo short.\n"
        val book = TextDocumentParser.parse(text, "x.txt", false)
        assertEquals(1, book.chapters.size)
        assertTrue(book.chapters[0].content.contains("Too short."))
        assertTrue(book.chapters[0].content.contains("CHAPTER TWO"))
    }
}