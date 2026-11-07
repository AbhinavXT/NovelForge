package com.abhinavxt.novelforge.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import com.abhinavxt.novelforge.data.epub.BlockMark
import com.abhinavxt.novelforge.data.epub.BlockStyle
import com.abhinavxt.novelforge.data.epub.ChapterFormatting
import com.abhinavxt.novelforge.data.epub.ChapterImage
import com.abhinavxt.novelforge.data.epub.Footnote
import com.abhinavxt.novelforge.data.epub.InlineStyle
import com.abhinavxt.novelforge.data.epub.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The reader's side of stored formatting: matching it to text, and laying out pages. */
class ReaderFormattingTest {

    private val dir = File("/books/n1")
    private val paragraphs = listOf("Hello world.", "Second one here.", "Third.")
    private val anySize: (File) -> Pair<Int, Int>? = { 100 to 50 }

    @Test
    fun `nothing stored means plain text`() {
        assertNull(ReaderFormatting.build(null, paragraphs, dir, anySize))
        assertNull(ReaderFormatting.build(ChapterFormatting(), paragraphs, dir, anySize))
    }

    @Test
    fun `spans are clipped to the text and stray paragraphs dropped`() {
        val f = ReaderFormatting.build(
            ChapterFormatting(
                spans = listOf(
                    StyleSpan(0, 6, 99, InlineStyle.ITALIC),   // runs past the end
                    StyleSpan(1, 5, 5, InlineStyle.BOLD),      // empty
                    StyleSpan(7, 0, 3, InlineStyle.BOLD)       // no such paragraph
                )
            ),
            paragraphs, dir, anySize
        )!!
        assertEquals(listOf(StyleSpan(0, 6, 12, InlineStyle.ITALIC)), f[0]!!.spans)
        assertNull(f[1])
        assertNull(f[7])
    }

    @Test
    fun `a heading wins over centring and quoting`() {
        val f = ReaderFormatting.build(
            ChapterFormatting(
                blocks = listOf(
                    BlockMark(0, BlockStyle.QUOTE), BlockMark(0, BlockStyle.HEADING), BlockMark(0, BlockStyle.CENTER),
                    BlockMark(2, BlockStyle.QUOTE)
                )
            ),
            paragraphs, dir, anySize
        )!!
        assertEquals(BlockStyle.HEADING, f[0]!!.block)
        assertEquals(BlockStyle.QUOTE, f[2]!!.block)
    }

    @Test
    fun `images go before their paragraph, or after the last one`() {
        val f = ReaderFormatting.build(
            ChapterFormatting(
                images = listOf(
                    ChapterImage(0, "0.jpg", "Map"),
                    ChapterImage(3, "1.png"),
                    ChapterImage(1, "missing.png"),
                    ChapterImage(1, "../other/2.png")
                )
            ),
            paragraphs, dir,
            sizeOf = { if (it.name == "missing.png") null else 640 to 480 }
        )!!
        assertEquals(listOf(ReaderImage(File(dir, "0.jpg"), "Map", 640, 480)), f[0]!!.imagesBefore)
        assertNull(f[1])
        assertEquals(listOf(ReaderImage(File(dir, "1.png"), null, 640, 480)), f[2]!!.imagesAfter)
    }

    @Test
    fun `without a pictures folder images are skipped but styles kept`() {
        val f = ReaderFormatting.build(
            ChapterFormatting(
                spans = listOf(StyleSpan(2, 0, 5, InlineStyle.BOLD)),
                images = listOf(ChapterImage(0, "0.jpg"))
            ),
            paragraphs, null, anySize
        )!!
        assertNull(f[0])
        assertEquals(1, f[2]!!.spans.size)
    }

    @Test
    fun `formatting never changes the text and notes become links`() {
        var opened: Footnote? = null
        val note = Footnote(0, 11, 12, "A note.")
        val format = ParagraphFormat(
            spans = listOf(StyleSpan(0, 0, 5, InlineStyle.ITALIC)),
            notes = listOf(note)
        )
        val text = buildAnnotatedString {
            append(paragraphs[0])
            addBookFormatting(format, Color.Blue) { opened = it }
        }
        assertEquals(paragraphs[0], text.text)
        assertEquals(1, text.spanStyles.size)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(1, links.size)
        assertEquals(11, links[0].start)
        (links[0].item as LinkAnnotation.Clickable).linkInteractionListener!!.onClick(links[0].item)
        assertEquals(note, opened)
    }

    @Test
    fun `sentence ranges match the old TTS split`() {
        val p = "One. Two!  Three? Four"
        val old = p.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        old.forEachIndexed { i, sentence ->
            val r = sentenceRange(p, i)!!
            assertEquals(sentence, p.substring(r.first, r.last + 1))
        }
        assertNull(sentenceRange(p, old.size))
        assertNull(sentenceRange(p, -1))
    }

    @Test
    fun `pictures keep their own spacing when paragraph spacing is zero`() {
        val image = ReaderImage(File(dir, "0.jpg"), null, 100, 100)
        // 40 + (50 + 20) overflows 100px; counting paragraph spacing would fit it.
        val pages = paginateParagraphs(
            paragraphs = listOf("a"),
            measureParagraph = { _, _ -> 40f },
            imagesBefore = { emptyList() },
            imagesAfter = { listOf(image) },
            imageSize = { 50f to 50f },
            maxHeightPx = 100f,
            maxWidthPx = 300f,
            paragraphSpacingPx = 0f,
            imageSpacingPx = 20f
        )
        assertEquals(2, pages.size)
    }

    @Test
    fun `pages keep pictures with their paragraph and save positions by paragraph`() {
        val image = ReaderImage(File(dir, "0.jpg"), null, 100, 100)
        // Page holds 100px; each paragraph is 40 + 10 spacing, the picture 80 + 10.
        val pages = paginateParagraphs(
            paragraphs = listOf("a", "a", "a", "a"),  // duplicates: indexOf would be wrong
            measureParagraph = { _, _ -> 40f },
            imagesBefore = { if (it == 2) listOf(image) else emptyList() },
            imagesAfter = { if (it == 3) listOf(image) else emptyList() },
            imageSize = { 80f to 80f },
            maxHeightPx = 100f,
            maxWidthPx = 300f,
            paragraphSpacingPx = 10f,
            imageSpacingPx = 10f
        )
        assertEquals(
            listOf(
                listOf(PageItem.Paragraph(0), PageItem.Paragraph(1)),
                listOf(PageItem.Picture(image, 80f to 80f)),
                listOf(PageItem.Paragraph(2), PageItem.Paragraph(3)),
                listOf(PageItem.Picture(image, 80f to 80f))
            ),
            pages
        )
        assertEquals(listOf(0, 2, 2, 3), pages.indices.map { firstParagraphOf(pages, it) })
        assertEquals(2, findPageForParagraph(pages, 3))
        assertEquals(0, findPageForParagraph(pages, 1))
    }

    @Test
    fun `pictures keep their shape within the page`() {
        val density = androidx.compose.ui.unit.Density(2f)
        // Small ornament: 50px wide -> 100 screen px, not stretched.
        assertEquals(100f to 50f, imageDisplaySize(ReaderImage(File("x"), null, 50, 25), 1000f, 1000f, density))
        // Wide picture fills the width.
        assertEquals(1000f to 500f, imageDisplaySize(ReaderImage(File("x"), null, 2000, 1000), 1000f, 1000f, density))
        // Tall picture is capped by height.
        assertEquals(250f to 1000f, imageDisplaySize(ReaderImage(File("x"), null, 1000, 4000), 1000f, 1000f, density))
    }

    @Test
    fun `a body set entirely in blockquotes isn't indented, a real quote is`() {
        val text = listOf("A long paragraph of the story itself.", "Another long paragraph of the story.", "a list item")
        val allQuoted = ReaderFormatting.build(
            ChapterFormatting(blocks = listOf(BlockMark(0, BlockStyle.QUOTE), BlockMark(1, BlockStyle.QUOTE), BlockMark(2, BlockStyle.CENTER))),
            text, dir, anySize
        )!!
        assertNull(allQuoted[0])
        assertEquals(BlockStyle.CENTER, allQuoted[2]!!.block)

        val oneQuote = ReaderFormatting.build(
            ChapterFormatting(blocks = listOf(BlockMark(2, BlockStyle.QUOTE))), text, dir, anySize
        )!!
        assertEquals(BlockStyle.QUOTE, oneQuote[2]!!.block)
    }
}
