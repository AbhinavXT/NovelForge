package com.abhinavxt.novelforge.data.epub

/**
 * How a chapter looked in its EPUB, kept beside its plain text.
 *
 * Chapter text stays plain (see [EpubParser]): search, the codex, TTS,
 * highlights and the archive fingerprint all read it, and archiving relies on
 * a re-parse giving back exactly the same characters. So formatting never goes
 * into the text. It is described here instead, by paragraph index (as
 * [com.abhinavxt.novelforge.util.ParagraphSplitter] splits the text) and
 * character offsets within that paragraph.
 */
data class ChapterFormatting(
    val spans: List<StyleSpan> = emptyList(),
    val blocks: List<BlockMark> = emptyList(),
    val images: List<ChapterImage> = emptyList(),
    val notes: List<Footnote> = emptyList()
) {
    val isEmpty: Boolean
        get() = spans.isEmpty() && blocks.isEmpty() && images.isEmpty() && notes.isEmpty()
}

enum class InlineStyle { ITALIC, BOLD, UNDERLINE, STRIKE, SUPERSCRIPT, SUBSCRIPT }

enum class BlockStyle { HEADING, QUOTE, CENTER }

/** [start] inclusive, [end] exclusive, both within paragraph [paragraph]. */
data class StyleSpan(val paragraph: Int, val start: Int, val end: Int, val style: InlineStyle)

data class BlockMark(val paragraph: Int, val style: BlockStyle)

/**
 * An image shown before paragraph [beforeParagraph]; a value equal to the
 * paragraph count puts it after the last one. [path] is the image's path in
 * the EPUB while parsing, and the stored file's name once imported.
 */
data class ChapterImage(val beforeParagraph: Int, val path: String, val alt: String? = null)

/** A footnote reference over [start, end) of [paragraph], and the note it points to. */
data class Footnote(val paragraph: Int, val start: Int, val end: Int, val text: String)

/** What a CSS class (or a style attribute) asks for, as far as the reader can show it. */
internal data class CssStyle(
    val inline: Set<InlineStyle> = emptySet(),
    val block: BlockStyle? = null
) {
    operator fun plus(other: CssStyle) = CssStyle(inline + other.inline, block ?: other.block)
}

/**
 * Works out a chapter's formatting without touching its text.
 *
 * The plain text comes from a chain of regexes in [EpubParser] that must keep
 * producing exactly what it always has. Rather than write a second extractor
 * and hope it agrees, this one inserts a marker character after each tag that
 * matters, runs the SAME chain, and reads back where the markers ended up.
 * Markers only ever go straight after a `>`, so the chain's `<[^>]+>` tag
 * stripping can't swallow one, and they aren't whitespace, entities or tags,
 * so nothing else touches them either.
 *
 * The chain does collapse whitespace, and a marker between two spaces stops
 * them collapsing. So the marked text, with its markers removed, is aligned
 * with the real text allowing for whitespace differences only. Anything
 * else (a marker lost, text that won't align) drops the formatting for that
 * chapter and leaves the text exactly as it was.
 */
internal object EpubMarkup {

    /** Private-use character; a chapter that already contains one gets no formatting. */
    const val MARK = '\uE000'

    sealed interface Event {
        data class StyleOn(val style: InlineStyle) : Event
        data class StyleOff(val style: InlineStyle) : Event
        data class BlockOn(val style: BlockStyle) : Event
        data class BlockOff(val style: BlockStyle) : Event
        data class Image(val path: String, val alt: String?) : Event
        data class NoteOn(val text: String) : Event
        data object NoteOff : Event
        data class Anchor(val id: String) : Event
    }

    /** What [markUp] needs to know about the rest of the book. */
    class Context(
        val classStyles: Map<String, CssStyle> = emptyMap(),
        /** An `<img src>` as written in the chapter -> its path in the EPUB, or null to skip it. */
        val resolveImage: (String) -> String? = { null },
        /**
         * A link's href, whether it is explicitly a note reference, and whether
         * its target lies later in the book than the link -> the note's text,
         * or null when the link isn't a footnote.
         */
        val resolveNote: (href: String, explicit: Boolean, linkOffset: Int) -> String? = { _, _, _ -> null },
        /** Element ids worth recording (table-of-contents targets). */
        val wantedAnchors: Set<String> = emptySet()
    )

    class Result(
        val formatting: ChapterFormatting,
        /** Element id -> index of the paragraph it starts. */
        val anchors: Map<String, Int>
    )

    private val TOKEN = Regex(
        "<!--.*?-->|<(/?)([A-Za-z][A-Za-z0-9:_.-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>",
        RegexOption.DOT_MATCHES_ALL
    )
    private val ATTR = Regex("([A-Za-z_:][A-Za-z0-9_:.-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")

    private val VOID = setOf(
        "img", "image", "br", "hr", "meta", "link", "input", "col", "area", "base", "wbr", "source", "embed", "param"
    )
    private val BLOCK_TAGS = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "center", "section", "li",
        "td", "th", "figure", "figcaption", "header", "footer", "aside", "article", "dd", "dt", "pre"
    )
    private val TAG_STYLES: Map<String, InlineStyle> = mapOf(
        "i" to InlineStyle.ITALIC, "em" to InlineStyle.ITALIC, "cite" to InlineStyle.ITALIC,
        "var" to InlineStyle.ITALIC, "dfn" to InlineStyle.ITALIC,
        "b" to InlineStyle.BOLD, "strong" to InlineStyle.BOLD,
        "u" to InlineStyle.UNDERLINE, "ins" to InlineStyle.UNDERLINE,
        "s" to InlineStyle.STRIKE, "strike" to InlineStyle.STRIKE, "del" to InlineStyle.STRIKE,
        "sup" to InlineStyle.SUPERSCRIPT, "sub" to InlineStyle.SUBSCRIPT
    )

    private class Frame(
        val name: String,
        val styles: List<InlineStyle>,
        val block: BlockStyle?,
        val note: Boolean
    )

    /**
     * [body] with a [MARK] after each tag that opens or closes something worth
     * keeping, and the events those marks stand for, in order.
     */
    fun markUp(body: String, context: Context): Pair<String, List<Event>> {
        val out = StringBuilder(body.length + 256)
        val events = mutableListOf<Event>()
        val stack = ArrayDeque<Frame>()
        var last = 0
        // Inside <script>/<style> the text chain deletes everything, marks
        // included, so nothing may be emitted there.
        var skipUntil: String? = null

        fun emit(event: Event) {
            out.append(MARK)
            events += event
        }

        fun close(frame: Frame) {
            if (frame.note) emit(Event.NoteOff)
            frame.styles.asReversed().forEach { emit(Event.StyleOff(it)) }
            frame.block?.let { emit(Event.BlockOff(it)) }
        }

        for (match in TOKEN.findAll(body)) {
            out.append(body, last, match.range.last + 1)
            last = match.range.last + 1
            val rawName = match.groupValues[2]
            if (rawName.isEmpty()) continue // a comment
            val name = rawName.substringAfterLast(':').lowercase()
            val closing = match.groupValues[1] == "/"

            if (skipUntil != null) {
                if (closing && name == skipUntil) skipUntil = null
                continue
            }
            if (closing) {
                if (stack.none { it.name == name }) continue
                while (stack.isNotEmpty()) {
                    val frame = stack.removeLast()
                    close(frame)
                    if (frame.name == name) break
                }
                continue
            }

            val attrText = match.groupValues[3]
            if (name == "script" || name == "style") {
                if (!attrText.trimEnd().endsWith("/")) skipUntil = name
                continue
            }
            val attrs = parseAttributes(attrText)

            attrs["id"]?.let { id -> if (id in context.wantedAnchors) emit(Event.Anchor(id)) }

            if (name == "img" || name == "image") {
                val src = attrs["src"] ?: attrs["xlink:href"] ?: attrs["href"]
                src?.let(context.resolveImage)?.let { emit(Event.Image(it, attrs["alt"]?.takeIf(String::isNotBlank))) }
                continue
            }
            if (name in VOID || attrText.trimEnd().endsWith("/")) continue

            var style = CssStyle(inline = setOfNotNull(TAG_STYLES[name]))
            attrs["class"]?.split(Regex("\\s+"))?.forEach { cls ->
                context.classStyles[cls]?.let { style += it }
            }
            attrs["style"]?.let { style += parseDeclarations(it) }

            val block = when {
                name.length == 2 && name[0] == 'h' && name[1] in '1'..'6' -> BlockStyle.HEADING
                name == "blockquote" -> BlockStyle.QUOTE
                name == "center" -> BlockStyle.CENTER
                name in BLOCK_TAGS -> style.block
                else -> null
            }

            var note = false
            if (name == "a") {
                val href = attrs["href"]
                val type = (attrs["epub:type"] ?: attrs["type"] ?: "") + " " + (attrs["role"] ?: "")
                if (href != null && "backlink" !in type) {
                    val explicit = "noteref" in type
                    val linkText = linkText(body, last)
                    if (explicit || looksLikeNoteLabel(linkText)) {
                        context.resolveNote(href, explicit, match.range.first)
                            ?.let { cleanNoteText(it, linkText) }
                            ?.takeIf { it.isNotBlank() }
                            ?.let { emit(Event.NoteOn(it)); note = true }
                    }
                }
            }

            block?.let { emit(Event.BlockOn(it)) }
            style.inline.forEach { emit(Event.StyleOn(it)) }
            stack.addLast(Frame(name, style.inline.toList(), block, note))
        }
        out.append(body, last, body.length)
        while (stack.isNotEmpty()) close(stack.removeLast())
        return out.toString() to events
    }

    /**
     * Places [events] in the real chapter text.
     *
     * @param marked the text chain's output for the marked-up body
     * @param canonical the text chain's output for the untouched body, which
     *   is what the chapter stores
     * @return null when the marks can't be placed with confidence
     */
    fun resolve(marked: String, canonical: String, events: List<Event>): Result? {
        if (events.isEmpty()) return Result(ChapterFormatting(), emptyMap())
        if (MARK in canonical) return null

        // Strip the marks, remembering where each one was.
        val plain = StringBuilder(marked.length)
        val markAt = IntArray(events.size)
        var count = 0
        for (c in marked) {
            if (c == MARK) {
                if (count == events.size) return null
                markAt[count++] = plain.length
            } else {
                plain.append(c)
            }
        }
        if (count != events.size) return null

        val offsets = align(plain, canonical, markAt) ?: return null
        val paragraphs = paragraphRanges(canonical)
        if (paragraphs.isEmpty()) return null

        val spans = mutableListOf<StyleSpan>()
        val blocks = mutableListOf<BlockMark>()
        val images = mutableListOf<ChapterImage>()
        val notes = mutableListOf<Footnote>()
        val anchors = mutableMapOf<String, Int>()

        val styleDepth = mutableMapOf<InlineStyle, Int>()
        val styleStart = mutableMapOf<InlineStyle, Int>()
        val blockDepth = mutableMapOf<BlockStyle, Int>()
        val blockStart = mutableMapOf<BlockStyle, Int>()
        var noteStart = -1
        var noteText: String? = null

        events.forEachIndexed { i, event ->
            val at = offsets[i]
            when (event) {
                is Event.StyleOn -> {
                    val depth = styleDepth.getOrDefault(event.style, 0)
                    if (depth == 0) styleStart[event.style] = at
                    styleDepth[event.style] = depth + 1
                }
                is Event.StyleOff -> {
                    val depth = styleDepth.getOrDefault(event.style, 0)
                    if (depth == 1) {
                        addSpans(paragraphs, styleStart.getValue(event.style), at, event.style, spans)
                    }
                    styleDepth[event.style] = (depth - 1).coerceAtLeast(0)
                }
                is Event.BlockOn -> {
                    val depth = blockDepth.getOrDefault(event.style, 0)
                    if (depth == 0) blockStart[event.style] = at
                    blockDepth[event.style] = depth + 1
                }
                is Event.BlockOff -> {
                    val depth = blockDepth.getOrDefault(event.style, 0)
                    if (depth == 1) {
                        val from = blockStart.getValue(event.style)
                        paragraphs.forEachIndexed { p, range ->
                            if (range.first in from until at) blocks += BlockMark(p, event.style)
                        }
                    }
                    blockDepth[event.style] = (depth - 1).coerceAtLeast(0)
                }
                is Event.Image -> {
                    images += ChapterImage(paragraphs.count { it.first < at }, event.path, event.alt)
                }
                is Event.NoteOn -> {
                    noteStart = at
                    noteText = event.text
                }
                Event.NoteOff -> {
                    val text = noteText
                    if (text != null && noteStart >= 0) {
                        val p = paragraphs.indexOfFirst { noteStart < it.last + 1 && it.first < at }
                        if (p >= 0) {
                            val range = paragraphs[p]
                            val s = maxOf(noteStart, range.first) - range.first
                            val e = minOf(at, range.last + 1) - range.first
                            if (e > s) notes += Footnote(p, s, e, text)
                        }
                    }
                    noteStart = -1
                    noteText = null
                }
                is Event.Anchor -> {
                    val p = paragraphs.indexOfFirst { it.last + 1 > at }
                    anchors.putIfAbsent(event.id, if (p >= 0) p else paragraphs.lastIndex)
                }
            }
        }

        return Result(
            ChapterFormatting(
                spans = mergeSpans(spans),
                blocks = blocks.distinct(),
                images = images,
                notes = notes
            ),
            anchors
        )
    }

    private fun addSpans(
        paragraphs: List<IntRange>,
        from: Int,
        to: Int,
        style: InlineStyle,
        into: MutableList<StyleSpan>
    ) {
        if (to <= from) return
        paragraphs.forEachIndexed { p, range ->
            val s = maxOf(from, range.first)
            val e = minOf(to, range.last + 1)
            if (e > s) into += StyleSpan(p, s - range.first, e - range.first, style)
        }
    }

    /** Joins touching spans of one style, which split tags like `<i>a</i><i>b</i>` leave behind. */
    private fun mergeSpans(spans: List<StyleSpan>): List<StyleSpan> {
        val merged = mutableListOf<StyleSpan>()
        spans.sortedWith(compareBy({ it.paragraph }, { it.style }, { it.start })).forEach { span ->
            val prev = merged.lastOrNull()
            if (prev != null && prev.paragraph == span.paragraph && prev.style == span.style && span.start <= prev.end) {
                merged[merged.lastIndex] = prev.copy(end = maxOf(prev.end, span.end))
            } else {
                merged += span
            }
        }
        return merged.sortedWith(compareBy({ it.paragraph }, { it.start }))
    }

    /**
     * Maps each position in [markAt] (offsets into [plain]) to an offset into
     * [canonical], where the two may differ only in whitespace.
     */
    internal fun align(plain: CharSequence, canonical: String, markAt: IntArray): IntArray? {
        val result = IntArray(markAt.size)
        var i = 0
        var j = 0
        var k = 0
        while (true) {
            while (k < markAt.size && markAt[k] == i) result[k++] = j
            if (i >= plain.length && j >= canonical.length) break
            val a = if (i < plain.length) plain[i] else null
            val b = if (j < canonical.length) canonical[j] else null
            when {
                a != null && a == b -> { i++; j++ }
                a != null && a.isWhitespace() -> i++
                b != null && b.isWhitespace() -> j++
                else -> return null
            }
        }
        return if (k == markAt.size) result else null
    }

    /**
     * Where each paragraph sits in [text], split exactly as ParagraphSplitter
     * splits it: on newlines, trimmed, empty pieces dropped.
     */
    internal fun paragraphRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var start = 0
        while (start <= text.length) {
            val nl = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            var a = start
            var b = nl
            while (a < b && text[a].isWhitespace()) a++
            while (b > a && text[b - 1].isWhitespace()) b--
            if (b > a) ranges += a until b
            start = nl + 1
        }
        return ranges
    }

    fun parseAttributes(text: String): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        for (m in ATTR.findAll(text)) {
            val value = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: ""
            attrs.putIfAbsent(m.groupValues[1].lowercase(), decodeEntities(value))
        }
        return attrs
    }

    private fun decodeEntities(value: String): String =
        if ('&' !in value) value
        else value.replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    /** Text of the link that starts at [from], up to its `</a>`. */
    private fun linkText(body: String, from: Int): String {
        val end = body.indexOf("</a", from, ignoreCase = true).let { if (it < 0) minOf(body.length, from + 200) else it }
        if (end - from > 400) return ""
        return body.substring(from, end).replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ").replace("&#160;", " ").trim()
    }

    private val NOTE_LABEL = Regex("^[\\[(]?\\s*(\\d{1,3}|[*\u2020\u2021\u00a7\u00b6]{1,3}|[a-zA-Z]|[ivxlcIVXLC]{1,5})\\s*[\\])]?\\.?$")

    /** "1", "[12]", "(iv)", "*", "†": what footnote references look like. */
    internal fun looksLikeNoteLabel(text: String): Boolean = text.length in 1..6 && NOTE_LABEL.matches(text)

    /** Drops the note's own label ("[1] ", "1. ") and back-link arrows from its text. */
    private fun cleanNoteText(text: String, label: String): String {
        var t = text.replace(Regex("[\u21a9\u21aa\u2191]\uFE0E?"), "").replace(Regex("\\s+"), " ").trim()
        val core = label.trim('[', ']', '(', ')', '.', ' ')
        if (core.isNotEmpty()) {
            t = t.replaceFirst(Regex("^[\\[(]?\\s*" + Regex.escape(core) + "\\s*[\\])]?\\.?\\s*"), "")
        }
        return t.take(MAX_NOTE_LENGTH).trim()
    }

    private const val MAX_NOTE_LENGTH = 4000

    // ── CSS ──────────────────────────────────────────────────────────

    private val CSS_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val CSS_RULE = Regex("([^{}]+)\\{([^{}]*)\\}")
    private val CLASS_SELECTOR = Regex("^[A-Za-z0-9]*\\.([A-Za-z0-9_-]+)$")

    /** Simple `.class` and `tag.class` rules from [css], mapped to what they ask for. */
    fun parseClassStyles(css: String): Map<String, CssStyle> {
        val result = mutableMapOf<String, CssStyle>()
        for (rule in CSS_RULE.findAll(CSS_COMMENT.replace(css, ""))) {
            val style = parseDeclarations(rule.groupValues[2])
            if (style.inline.isEmpty() && style.block == null) continue
            rule.groupValues[1].split(',').forEach { selector ->
                CLASS_SELECTOR.find(selector.trim())?.groupValues?.get(1)?.let { cls ->
                    result[cls] = result[cls]?.plus(style) ?: style
                }
            }
        }
        return result
    }

    private val DECLARATION = Regex("([A-Za-z-]+)\\s*:\\s*([^;]+)")

    fun parseDeclarations(text: String): CssStyle {
        val inline = mutableSetOf<InlineStyle>()
        var block: BlockStyle? = null
        for (m in DECLARATION.findAll(text)) {
            val value = m.groupValues[2].trim().lowercase().removeSuffix("!important").trim()
            when (m.groupValues[1].lowercase()) {
                "font-style" -> if (value == "italic" || value == "oblique") inline += InlineStyle.ITALIC
                "font-weight" -> if (value == "bold" || value == "bolder" || value.toIntOrNull()?.let { it >= 600 } == true) {
                    inline += InlineStyle.BOLD
                }
                "text-decoration", "text-decoration-line" -> {
                    if ("underline" in value) inline += InlineStyle.UNDERLINE
                    if ("line-through" in value) inline += InlineStyle.STRIKE
                }
                "vertical-align" -> when (value) {
                    "super" -> inline += InlineStyle.SUPERSCRIPT
                    "sub" -> inline += InlineStyle.SUBSCRIPT
                }
                "text-align" -> if (value == "center") block = BlockStyle.CENTER
            }
        }
        return CssStyle(inline, block)
    }
}
