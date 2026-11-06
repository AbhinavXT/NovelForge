package com.abhinavxt.novelforge.ui.components

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import com.abhinavxt.novelforge.data.TextReplacements
import com.abhinavxt.novelforge.data.epub.BlockStyle
import com.abhinavxt.novelforge.data.epub.ChapterFormatting
import com.abhinavxt.novelforge.data.epub.Footnote
import com.abhinavxt.novelforge.data.epub.InlineStyle
import com.abhinavxt.novelforge.data.epub.StyleSpan
import java.io.File

/** A stored picture, with its pixel size read once so layout never jumps. */
data class ReaderImage(val file: File, val alt: String?, val widthPx: Int, val heightPx: Int)

/** What one paragraph needs beyond its text. */
data class ParagraphFormat(
    val spans: List<StyleSpan> = emptyList(),
    val block: BlockStyle? = null,
    val notes: List<Footnote> = emptyList(),
    val imagesBefore: List<ReaderImage> = emptyList(),
    val imagesAfter: List<ReaderImage> = emptyList()
)

/**
 * A chapter's stored formatting, checked against the text it is shown with
 * and grouped by paragraph for the reader.
 *
 * The text is the source of truth: anything pointing outside it (a paragraph
 * that doesn't exist, offsets past the end) is clipped or dropped, so a
 * stale row can make a chapter look plainer but never break it.
 */
data class ReaderFormatting(private val byParagraph: Map<Int, ParagraphFormat>) {

    operator fun get(paragraph: Int): ParagraphFormat? = byParagraph[paragraph]

    /**
     * This formatting moved onto paragraphs shown with text replacements,
     * [shown] holding one entry per paragraph.
     */
    fun mapped(shown: List<TextReplacements.Mapped>): ReaderFormatting =
        ReaderFormatting(byParagraph.mapValues { (p, format) ->
            shown.getOrNull(p)?.let(format::mapped) ?: format
        })

    companion object {

        /**
         * @param imagesDir where the book's pictures are stored
         * @param sizeOf a picture -> its (width, height) in pixels, or null
         *   when it can't be shown (missing, or a format Android can't decode)
         */
        fun build(
            formatting: ChapterFormatting?,
            paragraphs: List<String>,
            imagesDir: File?,
            sizeOf: (File) -> Pair<Int, Int>? = ::decodeSize
        ): ReaderFormatting? {
            if (formatting == null || formatting.isEmpty || paragraphs.isEmpty()) return null
            val count = paragraphs.size
            fun inRange(p: Int) = p in 0 until count

            val spans = formatting.spans.mapNotNull { span ->
                if (!inRange(span.paragraph)) return@mapNotNull null
                val length = paragraphs[span.paragraph].length
                val start = span.start.coerceIn(0, length)
                val end = span.end.coerceIn(start, length)
                if (start < end) span.copy(start = start, end = end) else null
            }.groupBy { it.paragraph }

            val notes = formatting.notes.mapNotNull { note ->
                if (!inRange(note.paragraph) || note.text.isBlank()) return@mapNotNull null
                val length = paragraphs[note.paragraph].length
                val start = note.start.coerceIn(0, length)
                val end = note.end.coerceIn(start, length)
                if (start < end) note.copy(start = start, end = end) else null
            }.groupBy { it.paragraph }

            // One look per paragraph; a heading wins over centring, which
            // wins over a quote (a centred heading is still a heading).
            val blocks = formatting.blocks
                .filter { inRange(it.paragraph) }
                .groupBy { it.paragraph }
                .mapValues { (_, marks) -> marks.map { it.style }.minByOrNull { BLOCK_PRIORITY.indexOf(it) } }

            val before = mutableMapOf<Int, MutableList<ReaderImage>>()
            val after = mutableListOf<ReaderImage>()
            if (imagesDir != null) {
                formatting.images.forEach { image ->
                    val file = File(imagesDir, image.path)
                    // A stored name is a bare file name; anything else is not ours.
                    if (file.parentFile != imagesDir) return@forEach
                    val size = sizeOf(file) ?: return@forEach
                    val shown = ReaderImage(file, image.alt?.takeIf { it.isNotBlank() }, size.first, size.second)
                    if (image.beforeParagraph >= count) after += shown
                    else before.getOrPut(image.beforeParagraph.coerceAtLeast(0)) { mutableListOf() } += shown
                }
            }

            val indexes = spans.keys + notes.keys + blocks.keys + before.keys +
                (if (after.isNotEmpty()) setOf(count - 1) else emptySet())
            if (indexes.isEmpty()) return null
            return ReaderFormatting(indexes.associateWith { p ->
                ParagraphFormat(
                    spans = spans[p].orEmpty(),
                    block = blocks[p],
                    notes = notes[p].orEmpty(),
                    imagesBefore = before[p].orEmpty(),
                    imagesAfter = if (p == count - 1) after else emptyList()
                )
            })
        }

        private val BLOCK_PRIORITY = listOf(BlockStyle.HEADING, BlockStyle.CENTER, BlockStyle.QUOTE)

        private fun decodeSize(file: File): Pair<Int, Int>? {
            if (!file.isFile) return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            return if (options.outWidth > 0 && options.outHeight > 0) {
                options.outWidth to options.outHeight
            } else null
        }
    }
}

/**
 * The paragraph's spans and notes moved onto its replaced text. A span over
 * something removed entirely is dropped; one touching a replacement covers
 * all of it.
 */
fun ParagraphFormat.mapped(shown: TextReplacements.Mapped): ParagraphFormat {
    if (!shown.isChanged) return this
    return copy(
        spans = spans.mapNotNull { span ->
            shown.rangeToDisplay(span.start, span.end)?.let { span.copy(start = it.first, end = it.last + 1) }
        },
        notes = notes.mapNotNull { note ->
            shown.rangeToDisplay(note.start, note.end)?.let { note.copy(start = it.first, end = it.last + 1) }
        }
    )
}

/** Start indent for a block quote, in dp. */
const val QUOTE_INDENT_DP = 24

/** Headings are this much larger than body text. */
private const val HEADING_SCALE = 1.25f

/**
 * [base] adjusted for a paragraph's block look. Used both to measure pages
 * and to draw them, so the two always agree.
 */
fun TextStyle.forBlock(block: BlockStyle?): TextStyle = when (block) {
    BlockStyle.HEADING -> copy(
        fontWeight = FontWeight.Bold,
        fontSize = fontSize * HEADING_SCALE,
        lineHeight = lineHeight * HEADING_SCALE,
        textIndent = TextIndent.None,
        hyphens = Hyphens.None
    )
    BlockStyle.CENTER -> copy(textAlign = TextAlign.Center, textIndent = TextIndent.None)
    BlockStyle.QUOTE, null -> this
}

private fun InlineStyle.toSpanStyle(): SpanStyle = when (this) {
    InlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    InlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    InlineStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
    InlineStyle.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    InlineStyle.SUPERSCRIPT -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em)
    InlineStyle.SUBSCRIPT -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.75.em)
}

/**
 * Adds a paragraph's book formatting to a builder that already holds its
 * text. Footnote references become links that call [onNote]; the text
 * itself is never changed, so highlight and search offsets still line up.
 * Call this before adding highlights and search matches so those stay on top.
 */
fun AnnotatedString.Builder.addBookFormatting(
    format: ParagraphFormat?,
    noteColor: Color,
    onNote: ((Footnote) -> Unit)?
) {
    if (format == null) return
    // Underline and strike share one property, so a span with both needs
    // them combined or the later one would replace the earlier.
    format.spans.forEach { span ->
        addStyle(span.style.toSpanStyle(), span.start, span.end)
    }
    val decorated = format.spans.filter {
        it.style == InlineStyle.UNDERLINE || it.style == InlineStyle.STRIKE
    }
    decorated.forEach { a ->
        decorated.forEach { b ->
            if (a.style == InlineStyle.UNDERLINE && b.style == InlineStyle.STRIKE) {
                val start = maxOf(a.start, b.start)
                val end = minOf(a.end, b.end)
                if (start < end) {
                    addStyle(
                        SpanStyle(
                            textDecoration = TextDecoration.combine(
                                listOf(TextDecoration.Underline, TextDecoration.LineThrough)
                            )
                        ),
                        start, end
                    )
                }
            }
        }
    }
    format.notes.forEachIndexed { i, note ->
        val style = SpanStyle(color = noteColor, fontWeight = FontWeight.SemiBold)
        if (onNote != null) {
            addLink(
                LinkAnnotation.Clickable(
                    tag = "note_$i",
                    styles = TextLinkStyles(style = style),
                    linkInteractionListener = { onNote(note) }
                ),
                note.start, note.end
            )
        } else {
            addStyle(style, note.start, note.end)
        }
    }
}

/**
 * Where TTS sentence [index] sits in [paragraph], splitting the same way the
 * reader always has (after . ! or ? and whitespace). Null when out of range.
 */
fun sentenceRange(paragraph: String, index: Int): IntRange? {
    if (index < 0) return null
    var start = 0
    var i = 0
    for (gap in SENTENCE_GAP.findAll(paragraph)) {
        if (gap.range.first > start) {
            if (i == index) return start until gap.range.first
            i++
        }
        start = gap.range.last + 1
    }
    return if (i == index && start < paragraph.length) start until paragraph.length else null
}

private val SENTENCE_GAP = Regex("(?<=[.!?])\\s+")
