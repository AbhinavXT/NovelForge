package com.abhinavxt.novelforge.data.epub

import android.content.Context
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream
import com.abhinavxt.novelforge.util.Logger

/**
 * Data class representing a parsed EPUB book
 */
data class EpubBook(
    val title: String,
    val author: String,
    val description: String,
    val coverImage: ByteArray?, // Cover image bytes (can be saved to file)
    val chapters: List<EpubChapter>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EpubBook
        return title == other.title && author == other.author
    }

    override fun hashCode(): Int {
        return title.hashCode() * 31 + author.hashCode()
    }
}

/**
 * Data class representing a single chapter in an EPUB
 */
data class EpubChapter(
    val title: String,
    val content: String, // Plain text content (HTML stripped)
    val order: Int
)

/**
 * Parser for EPUB files
 * EPUB is essentially a ZIP file containing:
 * - META-INF/container.xml (points to content.opf)
 * - content.opf (metadata + spine/chapter order)
 * - Chapter files (XHTML)
 * - Images (optional cover)
 */
class EpubParser(private val context: Context) {

    companion object {
        private const val TAG = "EpubParser"
    }

    /**
     * Parse an EPUB file from a URI (from file picker)
     */
    fun parse(uri: Uri): EpubBook? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                parseEpub(inputStream)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error parsing EPUB", e)
            null
        }
    }

    /**
     * Parse EPUB from InputStream
     */
    private fun parseEpub(inputStream: InputStream): EpubBook? {
        val zipEntries = mutableMapOf<String, ByteArray>()

        // Read all ZIP entries into memory
        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    zipEntries[entry.name] = zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }

        // Step 1: Find the root file path from container.xml
        val containerXml = zipEntries["META-INF/container.xml"]
            ?: return null.also { Logger.e(TAG, "No container.xml found") }

        val rootFilePath = parseContainerXml(containerXml.decodeToString())
            ?: return null.also { Logger.e(TAG, "Could not find rootfile in container.xml") }

        // Step 2: Parse the OPF file (content.opf or similar)
        val opfContent = zipEntries[rootFilePath]
            ?: return null.also { Logger.e(TAG, "OPF file not found: $rootFilePath") }

        val opfDir = rootFilePath.substringBeforeLast("/", "")
        val opfData = parseOpfFile(opfContent.decodeToString(), opfDir)

        // Step 3: Extract cover image if available
        val coverImage = opfData.coverPath?.let { resolveEntry(zipEntries, opfDir, it) }

        // Step 4: Parse chapters in spine order
        val chapters = mutableListOf<EpubChapter>()
        opfData.spineItems.forEachIndexed { index, spineItem ->
            val chapterContent = resolveEntry(zipEntries, opfDir, spineItem.href)

            if (chapterContent != null) {
                val htmlContent = chapterContent.decodeToString()
                val headingTitle = extractChapterTitle(htmlContent)
                val plainText = extractTextFromHtml(htmlContent)

                // Threshold lowered from 100. It exists to skip title pages and
                // image-only front matter, but it used to be measured against
                // text that INCLUDED the <head><title> and the heading -- often
                // 60+ characters of chrome. Now that those are stripped it
                // measures actual prose, so the old number would have started
                // discarding genuinely short chapters.
                if (plainText.length > 40) {
                    chapters.add(
                        EpubChapter(
                            title = headingTitle
                                ?: spineItem.title
                                ?: "Chapter ${chapters.size + 1}",
                            content = plainText,
                            order = chapters.size + 1
                        )
                    )
                }
            }
        }

        return EpubBook(
            title = opfData.title ?: "Unknown Title",
            author = opfData.author ?: "Unknown Author",
            description = opfData.description
                ?.let { com.abhinavxt.novelforge.util.HtmlText.stripHtml(it) }
                ?: "",
            coverImage = coverImage,
            chapters = chapters
        )
    }

    /**
     * Resolve a manifest href against the ZIP entry names.
     *
     * A plain map lookup is not enough for real-world EPUBs:
     *  - hrefs are URI references, so spaces and non-ASCII are percent-encoded
     *    ("Chapter%201.xhtml") while the ZIP entry name is literal
     *    ("Chapter 1.xhtml"). The lookup missed, chapterContent came back null,
     *    and the chapter was DROPPED with no log line -- a silently short book.
     *  - hrefs may carry a fragment ("ch1.xhtml#start").
     *  - some producers write paths that do not resolve cleanly against opfDir.
     *
     * Tries, in order: opf-relative, root-relative, percent-decoded variants of
     * both, then a filename-only match as a last resort. The final fallback can
     * in principle collide if two directories hold the same filename, which is
     * rare in EPUBs and strictly better than silently losing the chapter.
     */
    private fun resolveEntry(
        zipEntries: Map<String, ByteArray>,
        opfDir: String,
        href: String
    ): ByteArray? {
        val clean = href.substringBefore('#')
        val candidates = LinkedHashSet<String>()
        fun add(path: String) {
            candidates.add(path)
            candidates.add(percentDecode(path))
        }
        if (opfDir.isNotEmpty()) add("$opfDir/$clean")
        add(clean)

        for (candidate in candidates) {
            zipEntries[candidate]?.let { return it }
        }

        val fileName = percentDecode(clean).substringAfterLast('/')
        if (fileName.isNotEmpty()) {
            zipEntries.entries
                .firstOrNull { it.key.substringAfterLast('/') == fileName }
                ?.let { return it.value }
        }
        return null
    }

    /**
     * Percent-decode a URI path. Deliberately NOT URLDecoder, which decodes '+'
     * as a space -- correct for query strings, wrong for file paths, and EPUB
     * filenames do contain '+'. Decodes to bytes first so multi-byte UTF-8
     * sequences round-trip.
     */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        return try {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                val hex = if (c == '%' && i + 3 <= value.length) {
                    value.substring(i + 1, i + 3).toIntOrNull(16)
                } else null
                if (hex != null) {
                    out.write(hex)
                    i += 3
                } else {
                    out.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            out.toString("UTF-8")
        } catch (e: Exception) {
            value
        }
    }

    /**
     * Parse container.xml to find the path to the OPF file
     */
    private fun parseContainerXml(xml: String): String? {
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(xml.reader())

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    return parser.getAttributeValue(null, "full-path")
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error parsing container.xml", e)
        }
        return null
    }

    /**
     * Data holder for OPF parsing results
     */
    private data class OpfData(
        val title: String?,
        val author: String?,
        val description: String?,
        val coverPath: String?,
        val spineItems: List<SpineItem>
    )

    private data class SpineItem(
        val id: String,
        val href: String,
        val title: String?
    )

    /**
     * Parse the OPF file to extract metadata and chapter order
     */
    private fun parseOpfFile(xml: String, opfDir: String): OpfData {
        var title: String? = null
        var author: String? = null
        var description: String? = null
        // Three sources, in descending authority. <metadata> is parsed before
        // <manifest>, so the old single coverId variable let the filename guess
        // OVERWRITE an explicit declaration -- an EPUB with a real cover plus
        // any other image named "cover-something" picked the wrong one.
        var coverIdFromProperties: String? = null   // EPUB 3: properties="cover-image"
        var coverIdFromMeta: String? = null         // EPUB 2: <meta name="cover">
        var coverIdByGuess: String? = null          // filename/id heuristic
        val manifest = mutableMapOf<String, String>() // id -> href
        val spineIds = mutableListOf<String>()

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(xml.reader())

            var eventType = parser.eventType
            var currentTag = ""
            var inMetadata = false

            // Which metadata field we are currently inside, plus how deep we
            // are within it. Held from the opening tag to its matching close so
            // that TEXT arriving across several events is ACCUMULATED.
            //
            // The previous version assigned currentTag on every START_TAG and
            // never reset it, then took the first TEXT event only. Two ways
            // that lost data:
            //   <dc:description>A tale of <b>swords</b> and honour.</dc:description>
            //     -> stored "A tale of"  (currentTag became "b"; the rest was
            //        attributed to the wrong tag and dropped)
            //   <dc:title>Tom &amp; Jerry</dc:title>
            //     -> stored "Tom"        (entity split the text into events;
            //        only the first was kept)
            // The first case is unconditional. The second depends on whether
            // the parser coalesces text runs; accumulating handles both.
            var capturing: String? = null
            var captureDepth = 0
            val buffer = StringBuilder()

            fun fieldOf(tag: String): String? = when {
                tag == "title" || tag.endsWith(":title") -> "title"
                tag == "creator" || tag.endsWith(":creator") -> "creator"
                tag == "description" || tag.endsWith(":description") -> "description"
                else -> null
            }

            // Only the FIRST occurrence of each field is taken, matching the
            // previous behaviour for EPUBs that declare dc:title more than once.
            fun stillWanted(field: String): Boolean = when (field) {
                "title" -> title == null
                "creator" -> author == null
                "description" -> description == null
                else -> false
            }

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name ?: ""

                        if (capturing != null) {
                            // A nested element inside the field we are reading
                            // (markup in a description). Keep accumulating.
                            captureDepth++
                        } else if (inMetadata) {
                            val field = fieldOf(currentTag)
                            if (field != null && stillWanted(field)) {
                                capturing = field
                                captureDepth = 0
                                buffer.setLength(0)
                            }
                        }

                        when (currentTag) {
                            "metadata" -> inMetadata = true
                            "item" -> {
                                val id = parser.getAttributeValue(null, "id")
                                val href = parser.getAttributeValue(null, "href")
                                val mediaType = parser.getAttributeValue(null, "media-type")
                                val properties = parser.getAttributeValue(null, "properties")

                                if (id != null && href != null) {
                                    manifest[id] = href

                                    if (mediaType?.startsWith("image/") == true) {
                                        // EPUB 3 declares the cover explicitly.
                                        if (properties?.contains("cover-image") == true) {
                                            coverIdFromProperties = id
                                        } else if (id.contains("cover", ignoreCase = true) ||
                                            href.contains("cover", ignoreCase = true)
                                        ) {
                                            coverIdByGuess = id
                                        }
                                    }
                                }
                            }
                            "itemref" -> {
                                val idref = parser.getAttributeValue(null, "idref")
                                if (idref != null) {
                                    spineIds.add(idref)
                                }
                            }
                            "meta" -> {
                                val name = parser.getAttributeValue(null, "name")
                                val content = parser.getAttributeValue(null, "content")
                                if (name == "cover" && content != null) {
                                    coverIdFromMeta = content
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (capturing != null) buffer.append(parser.text ?: "")
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "metadata") {
                            inMetadata = false
                        }
                        if (capturing != null) {
                            if (captureDepth > 0) {
                                captureDepth--
                            } else {
                                val value = buffer.toString().trim()
                                if (value.isNotEmpty()) {
                                    when (capturing) {
                                        "title" -> title = value
                                        "creator" -> author = value
                                        "description" -> description = value
                                    }
                                }
                                capturing = null
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error parsing OPF", e)
        }

        // Build spine items from manifest
        val spineItems = spineIds.mapNotNull { id ->
            manifest[id]?.let { href ->
                SpineItem(id = id, href = href, title = null)
            }
        }

        // Get cover path, explicit declarations winning over the guess.
        val coverId = coverIdFromProperties ?: coverIdFromMeta ?: coverIdByGuess
        val coverPath = coverId?.let { manifest[it] }

        return OpfData(
            title = title,
            author = author,
            description = description,
            coverPath = coverPath,
            spineItems = spineItems
        )
    }

    private val HEAD_BLOCK = Regex("<head[^>]*>.*?</head>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val HEADING_BLOCK = Regex("<(h[1-6])[^>]*>(.*?)</\\1\\s*>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TITLE_TAG = Regex("<title[^>]*>(.*?)</title>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /**
     * Best-effort chapter title from the chapter document itself.
     *
     * The spine gives no titles (SpineItem.title is always null) and neither
     * toc.ncx nor the EPUB 3 nav document is parsed, so without this every
     * imported chapter is named "Chapter N". The first heading, falling back to
     * <head><title>, recovers the real name for the overwhelming majority of
     * real-world EPUBs at a fraction of the cost of a full TOC parser.
     */
    private fun extractChapterTitle(html: String): String? {
        val heading = HEADING_BLOCK.find(html)?.groupValues?.get(2)
        val fromTitleTag = TITLE_TAG.find(html)?.groupValues?.get(1)
        return sequenceOf(heading, fromTitleTag)
            .filterNotNull()
            .map { stripTagsAndEntities(it) }
            .firstOrNull { it.isNotBlank() && it.length <= 120 }
    }

    private fun stripTagsAndEntities(fragment: String): String =
        fragment.replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Extract plain text from HTML content
     * Strips all tags and normalizes whitespace
     */
    private fun extractTextFromHtml(html: String): String {
        // Remove <head> BEFORE stripping tags. Tag removal keeps element TEXT,
        // and <head> contains <title> -- so the chapter title was landing at the
        // top of the body text of EVERY imported chapter. Combined with the
        // usual <h1> repeating it, each chapter opened with its own name twice.
        // On a NovelForge export re-imported, the duplicates accumulated with
        // each pass.
        val withoutHead = html.replace(HEAD_BLOCK, "")

        // Drop the leading heading too, but only the one we promoted to the
        // chapter title -- otherwise the reader shows the title in the app bar
        // and again as the first paragraph.
        val body = if (extractChapterTitle(html) != null) {
            HEADING_BLOCK.replaceFirst(withoutHead, "")
        } else {
            withoutHead
        }

        return body
            // Remove scripts and styles
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            // Convert common block elements to newlines
            .replace(Regex("</(p|div|br|h[1-6]|li|tr)\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            // Remove all remaining HTML tags
            .replace(Regex("<[^>]+>"), "")
            // Decode common HTML entities
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace(Regex("&#(\\d+);")) { matchResult ->
                // appendCodePoint, not toChar(): Char is 16-bit, so the old
                // version silently truncated anything above U+FFFF. Same defect
                // that was in HtmlText.stripHtml -- this copy affects CHAPTER
                // BODY TEXT, not just the description.
                matchResult.groupValues[1].toIntOrNull()
                    ?.takeIf { it in 1..0x10FFFF }
                    ?.let { cp -> StringBuilder().appendCodePoint(cp).toString() }
                    ?: ""
            }
            // Normalize whitespace
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n\n")
            .trim()
    }
}