package com.abhinavxt.novelforge.data.epub

/**
 * Converts plain text and Markdown into the same [EpubBook] shape that
 * [EpubParser] produces, so imported .txt/.md files flow through the existing
 * [EpubImporter] path unchanged.
 *
 * Deliberately NOT "convert to EPUB, then parse the EPUB". Writing a ZIP only
 * to immediately unzip it costs I/O and a temp file for no gain -- EpubBook is
 * already the common representation both paths converge on.
 *
 * Everything here is pure (String in, EpubBook out) with no Android imports,
 * so it is unit-testable on the JVM.
 */
object TextDocumentParser {

    /** Below this, a "chapter" is treated as a stray fragment and merged back. */
    private const val MIN_CHAPTER_CHARS = 40

    /** A markerless document longer than this gets split into synthetic parts. */
    private const val SIZE_SPLIT_THRESHOLD = 30_000

    /** Target size for those synthetic parts. */
    private const val SIZE_SPLIT_TARGET = 15_000

    /** Longer than this and a line is prose, not a heading. */
    private const val MAX_HEADING_CHARS = 120

    // ── Line classifiers ─────────────────────────────────────────────────

    /** Markdown ATX heading: leading #s, then space, then text. */
    private val ATX_HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")

    /**
     * "Chapter 12", "CHAPTER XIV", "Ch. 3", "Part Two", "Prologue", "Epilogue".
     * Anchored to the whole line so a mid-paragraph mention never splits.
     */
    private val CHAPTER_LINE = Regex(
        "^\\s*(?:(?:chapter|chapitre|ch\\.?|part|book|volume|vol\\.?|act)\\s*" +
                "[0-9]+|" +
                "(?:chapter|part|book|volume|act)\\s+" +
                "(?:[ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten|" +
                "eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|" +
                "eighteen|nineteen|twenty|thirty|forty|fifty)|" +
                "prologue|epilogue|foreword|afterword|interlude|prelude)" +
                "\\b.*$",
        RegexOption.IGNORE_CASE
    )

    /** Thematic break: ***, ---, ___, * * *, and friends. */
    private val SEPARATOR_LINE = Regex("^\\s*(?:([*\\-_=~#])\\s*){3,}$")

    /** Setext underline: === or --- directly under a text line. */
    private val SETEXT_UNDERLINE = Regex("^\\s*(={3,}|-{3,})\\s*$")

    // ── Entry point ──────────────────────────────────────────────────────

    /**
     * @param raw     full file contents
     * @param fileName used for the fallback title, e.g. "my-novel.md"
     * @param markdown true to apply Markdown-specific handling (front matter,
     *                 ATX headings, inline formatting removal)
     */
    fun parse(raw: String, fileName: String, markdown: Boolean): EpubBook {
        val normalised = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\uFEFF", "")          // BOM, common in Windows-authored .txt

        val (frontMatter, bodyText) =
            if (markdown) extractFrontMatter(normalised) else emptyMap<String, String>() to normalised

        val lines = bodyText.split("\n")
        val blocks = detectChapters(lines, markdown)

        val chapters = blocks.mapIndexedNotNull { index, block ->
            val content = cleanBody(block.lines.joinToString("\n"), markdown)
            if (content.isBlank()) null
            else EpubChapter(
                title = block.title ?: "Chapter ${index + 1}",
                content = content,
                order = 0   // renumbered below, after nulls are dropped
            )
        }.mapIndexed { index, chapter -> chapter.copy(order = index + 1) }

        val titleFromHeading = blocks.firstOrNull()?.documentTitle
        return EpubBook(
            title = frontMatter["title"]
                ?: titleFromHeading
                ?: titleFromFileName(fileName),
            author = frontMatter["author"] ?: "Unknown Author",
            description = frontMatter["description"] ?: "",
            coverImage = null,
            chapters = chapters
        )
    }

    // ── Front matter ─────────────────────────────────────────────────────

    /**
     * Minimal YAML front matter reader: only the flat `key: value` pairs this
     * importer cares about. A real YAML parser would be a dependency and a
     * parsing-surface risk for a feature that needs three keys.
     */
    private fun extractFrontMatter(text: String): Pair<Map<String, String>, String> {
        if (!text.startsWith("---")) return emptyMap<String, String>() to text
        val lines = text.split("\n")
        if (lines.firstOrNull()?.trim() != "---") return emptyMap<String, String>() to text

        val closing = lines.drop(1).indexOfFirst { it.trim() == "---" || it.trim() == "..." }
        if (closing < 0) return emptyMap<String, String>() to text

        val map = mutableMapOf<String, String>()
        for (line in lines.subList(1, closing + 1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim().trim('"', '\'')
            if (key.isNotEmpty() && value.isNotEmpty()) map[key] = value
        }
        return map to lines.drop(closing + 2).joinToString("\n")
    }

    // ── Chapter detection ────────────────────────────────────────────────

    private data class Block(
        val title: String?,
        val lines: List<String>,
        val documentTitle: String? = null
    )

    /**
     * Strategies in descending confidence. The first that yields at least two
     * chapters wins; a document that matches none becomes a single chapter,
     * size-split if it is very long.
     */
    private fun detectChapters(lines: List<String>, markdown: Boolean): List<Block> {
        if (markdown) {
            splitByAtxHeadings(lines)?.let { return it }
        }
        splitBySetextHeadings(lines)?.let { return it }
        splitByChapterLines(lines)?.let { return it }
        splitBySeparators(lines)?.let { return it }
        return sizeSplit(lines)
    }

    /**
     * Splits on the SHALLOWEST heading level that appears more than once.
     *
     * A document with one `#` title and many `##` chapters must split on `##`,
     * not `#` -- otherwise the whole book is one chapter. The lone `#` becomes
     * the book title instead.
     */
    private fun splitByAtxHeadings(lines: List<String>): List<Block>? {
        val headings = lines.mapIndexedNotNull { i, line ->
            ATX_HEADING.matchEntire(line)?.let { m ->
                Triple(i, m.groupValues[1].length, m.groupValues[2].trim())
            }
        }.filter { it.third.isNotBlank() }
        if (headings.isEmpty()) return null

        val levelCounts = headings.groupingBy { it.second }.eachCount()
        val splitLevel = levelCounts.keys.sorted().firstOrNull { levelCounts.getValue(it) >= 2 }
            ?: return null

        // A single shallower heading above the split level is the book title.
        val documentTitle = headings
            .firstOrNull { it.second < splitLevel && levelCounts.getValue(it.second) == 1 }
            ?.third

        val marks = headings.filter { it.second == splitLevel }

        // Heading lines ABOVE the split level (the book title, a part label)
        // sit in the preamble region and would otherwise be prepended to
        // chapter one as body text -- so the title would appear both as the
        // book title and as the first line of the first chapter.
        val headingLines = headings.map { it.first }.toSet()

        return buildBlocks(lines, marks.map { it.first to it.third }, documentTitle, headingLines)
    }

    /** `Title` followed by a line of === or --- (Markdown setext style). */
    private fun splitBySetextHeadings(lines: List<String>): List<Block>? {
        val marks = mutableListOf<Pair<Int, String>>()
        for (i in 1 until lines.size) {
            if (SETEXT_UNDERLINE.matches(lines[i])) {
                val text = lines[i - 1].trim()
                if (text.isNotBlank() && text.length <= MAX_HEADING_CHARS &&
                    !SEPARATOR_LINE.matches(lines[i - 1])
                ) {
                    marks += (i - 1) to text
                }
            }
        }
        return if (marks.size >= 2) buildBlocks(lines, marks, null) else null
    }

    /** Lines that look like "Chapter 12" / "Prologue" on their own. */
    private fun splitByChapterLines(lines: List<String>): List<Block>? {
        val marks = lines.mapIndexedNotNull { i, line ->
            val trimmed = line.trim()
            if (trimmed.length <= MAX_HEADING_CHARS && CHAPTER_LINE.matches(trimmed)) {
                i to trimmed
            } else null
        }
        return if (marks.size >= 2) buildBlocks(lines, marks, null) else null
    }

    /**
     * Thematic breaks. Unlike the other strategies the separator is not a title,
     * so parts are numbered. Requires 2+ breaks to avoid splitting a document
     * that merely ends with a rule.
     */
    private fun splitBySeparators(lines: List<String>): List<Block>? {
        val marks = lines.mapIndexedNotNull { i, line ->
            if (SEPARATOR_LINE.matches(line)) i else null
        }
        if (marks.size < 2) return null

        val blocks = mutableListOf<Block>()
        var start = 0
        for (mark in marks) {
            if (mark > start) blocks += Block(null, lines.subList(start, mark))
            start = mark + 1
        }
        if (start < lines.size) blocks += Block(null, lines.subList(start, lines.size))
        return blocks
            .filter { it.lines.any { l -> l.isNotBlank() } }
            .takeIf { it.size >= 2 }
            ?.mapIndexed { i, b -> b.copy(title = "Part ${i + 1}") }
    }

    /**
     * No structure found. Keep as one chapter unless it is long enough that a
     * single chapter would be unpleasant to navigate, in which case break on
     * blank lines near the target size so no paragraph is ever cut in half.
     */
    private fun sizeSplit(lines: List<String>): List<Block> {
        val totalChars = lines.sumOf { it.length + 1 }
        if (totalChars <= SIZE_SPLIT_THRESHOLD) return listOf(Block(null, lines))

        val blocks = mutableListOf<Block>()
        var current = mutableListOf<String>()
        var size = 0
        for (line in lines) {
            current += line
            size += line.length + 1
            if (size >= SIZE_SPLIT_TARGET && line.isBlank()) {
                blocks += Block(null, current)
                current = mutableListOf()
                size = 0
            }
        }
        if (current.any { it.isNotBlank() }) blocks += Block(null, current)
        return blocks.mapIndexed { i, b -> b.copy(title = "Part ${i + 1}") }
    }

    /**
     * Turn heading positions into blocks. Text before the first heading is
     * front matter (dedication, epigraph) and is prepended to chapter one
     * rather than dropped -- losing an author's preface without warning would
     * be worse than a slightly untidy first chapter.
     */
    private fun buildBlocks(
        lines: List<String>,
        marks: List<Pair<Int, String>>,
        documentTitle: String?,
        excludeLines: Set<Int> = emptySet()
    ): List<Block> {
        val blocks = mutableListOf<Block>()
        val preamble = lines.subList(0, marks.first().first)
            .filterIndexed { i, _ -> i !in excludeLines }
            .dropLastWhile { it.isBlank() }

        marks.forEachIndexed { index, (lineIndex, title) ->
            val end = if (index + 1 < marks.size) marks[index + 1].first else lines.size
            var body = lines.subList(lineIndex + 1, end).toMutableList()
            if (index == 0 && preamble.any { it.isNotBlank() }) {
                body = (preamble + listOf("") + body).toMutableList()
            }
            blocks += Block(title, body)
        }

        // Merge runaway fragments (a heading immediately followed by another)
        // into the previous chapter instead of emitting near-empty entries.
        val merged = mutableListOf<Block>()
        for (block in blocks) {
            val text = block.lines.joinToString("\n").trim()
            if (text.length < MIN_CHAPTER_CHARS && merged.isNotEmpty()) {
                val prev = merged.removeAt(merged.size - 1)
                merged += prev.copy(
                    lines = prev.lines + listOf("", block.title.orEmpty()) + block.lines
                )
            } else {
                merged += block
            }
        }
        return merged.mapIndexed { i, b ->
            if (i == 0) b.copy(documentTitle = documentTitle) else b
        }
    }

    // ── Body cleanup ─────────────────────────────────────────────────────

    private val MD_IMAGE = Regex("!\\[[^\\]]*]\\([^)]*\\)")
    private val MD_LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val MD_EMPHASIS = Regex("(\\*{1,3}|_{1,3})(?=\\S)(.+?)(?<=\\S)\\1")
    private val MD_CODE = Regex("`([^`]*)`")
    private val MD_BLOCKQUOTE = Regex("^\\s{0,3}>\\s?", RegexOption.MULTILINE)
    private val MD_LIST_MARKER = Regex("^\\s{0,3}(?:[*+\\-]|\\d+[.)])\\s+", RegexOption.MULTILINE)
    private val MD_ATX_INLINE = Regex("^\\s{0,3}#{1,6}\\s+", RegexOption.MULTILINE)

    /**
     * Collapse to the plain paragraphs the reader expects.
     *
     * The output is fed to ParagraphSplitter, which treats every newline as a
     * paragraph break -- so hard-wrapped source (very common in .txt) must have
     * its intra-paragraph newlines joined, or a 70-column file becomes hundreds
     * of one-line paragraphs.
     */
    private fun cleanBody(text: String, markdown: Boolean): String {
        var out = text
        if (markdown) {
            out = out
                .replace(MD_IMAGE, "")
                .replace(MD_LINK, "$1")
                .replace(MD_CODE, "$1")
                .replace(MD_EMPHASIS, "$2")
                .replace(MD_BLOCKQUOTE, "")
                .replace(MD_LIST_MARKER, "")
                .replace(MD_ATX_INLINE, "")
        }
        out = out.lines().joinToString("\n") { it.trim() }
        // Drop leftover thematic breaks so they do not become paragraphs.
        out = out.lines().filterNot { SEPARATOR_LINE.matches(it) }.joinToString("\n")
        // Join hard wraps: a single newline between two non-blank lines is a
        // wrap, not a paragraph break. Two or more newlines is a real break.
        out = Regex("(?<=\\S)\\n(?=\\S)").replace(out, " ")
        out = Regex("\\n{2,}").replace(out, "\n\n")
        return out.trim()
    }

    private fun titleFromFileName(fileName: String): String =
        fileName.substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .ifBlank { "Imported Document" }
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}