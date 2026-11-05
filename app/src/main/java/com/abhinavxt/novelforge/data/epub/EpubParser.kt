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
    val chapters: List<EpubChapter>,
    /** The book's own table of contents, mapped onto [chapters]. Empty when it has none. */
    val toc: List<TocEntry> = emptyList(),
    /** Bytes of every image a chapter's [ChapterFormatting] refers to, by path in the EPUB. */
    val images: Map<String, ByteArray> = emptyMap()
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
    val order: Int,
    /** Italics, images, footnotes and so on, described beside [content]; null when there are none. */
    val formatting: ChapterFormatting? = null,
    /**
     * The chapter's name in the book's own table of contents. Kept apart
     * from [title], which books archived before fingerprints existed are
     * still matched against on restore.
     */
    val tocTitle: String? = null
) {
    /** What the library shows for a newly imported chapter. */
    val displayTitle: String get() = tocTitle ?: title
}

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
    internal fun parseEpub(inputStream: InputStream): EpubBook? {
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

        // Formatting and the table of contents are extras: whatever goes
        // wrong with them, the chapters below must come out exactly as they
        // always have, because archived books are checked against them.
        val extras = try {
            BookExtras(zipEntries, opfDir, opfData)
        } catch (e: Exception) {
            Logger.e(TAG, "Could not read styles or contents; importing text only", e)
            null
        }

        // Step 4: Parse chapters in spine order
        val chapters = mutableListOf<EpubChapter>()
        opfData.spineItems.forEachIndexed { index, spineItem ->
            val chapterKey = resolveEntryKey(zipEntries, opfDir, spineItem.href)
            val chapterContent = chapterKey?.let { zipEntries[it] }

            if (chapterContent != null) {
                val htmlContent = chapterContent.decodeToString()
                val headingTitle = extractChapterTitle(htmlContent)
                val body = prepareBody(htmlContent)
                val plainText = bodyToText(body)

                // Threshold lowered from 100. It exists to skip title pages and
                // image-only front matter, but it used to be measured against
                // text that INCLUDED the <head><title> and the heading -- often
                // 60+ characters of chrome. Now that those are stripped it
                // measures actual prose, so the old number would have started
                // discarding genuinely short chapters.
                if (plainText.length > 40) {
                    val order = chapters.size + 1
                    val formatting = extras?.formatChapter(index, chapterKey, body, plainText, order)
                    chapters.add(
                        EpubChapter(
                            title = headingTitle
                                ?: spineItem.title
                                ?: "Chapter $order",
                            content = plainText,
                            order = order,
                            formatting = formatting,
                            tocTitle = extras?.tocTitle(chapterKey)
                        )
                    )
                } else {
                    extras?.skipChapter(index, chapterKey, body)
                }
            }
        }
        val finished = extras?.finish(chapters)

        return EpubBook(
            title = opfData.title ?: "Unknown Title",
            author = opfData.author ?: "Unknown Author",
            description = opfData.description
                ?.let { com.abhinavxt.novelforge.util.HtmlText.stripHtml(it) }
                ?: "",
            coverImage = coverImage,
            chapters = finished?.chapters ?: chapters,
            toc = finished?.toc.orEmpty(),
            images = finished?.images.orEmpty()
        )
    }

    /**
     * Everything beyond the plain text: stylesheets, images, footnotes and the
     * table of contents. Built once per book; [formatChapter] runs for each
     * kept chapter in order and [finish] once at the end.
     */
    private inner class BookExtras(
        private val zipEntries: Map<String, ByteArray>,
        opfDir: String,
        opfData: OpfData
    ) {
        private val coverKey = opfData.coverPath?.let { resolvePath(zipEntries, opfDir, it) }
        private val spineKeys = opfData.spineItems.map { resolveEntryKey(zipEntries, opfDir, it.href) }
        private val spineIndexOf: Map<String, Int> = buildMap {
            spineKeys.forEachIndexed { i, key -> if (key != null) putIfAbsent(key, i) }
        }

        private val classStyles: Map<String, CssStyle> = buildMap {
            opfData.manifest.filter { it.mediaType == "text/css" }.forEach { item ->
                val css = resolvePath(zipEntries, opfDir, item.href)?.let { zipEntries[it] } ?: return@forEach
                EpubMarkup.parseClassStyles(css.decodeToString()).forEach { (cls, style) ->
                    put(cls, get(cls)?.plus(style) ?: style)
                }
            }
        }

        private val rawToc: List<EpubToc.RawEntry> = readToc(opfData, opfDir)
        private val anchorsWanted: Map<String, Set<String>> = rawToc
            .filter { it.fragment != null }
            .groupBy({ it.path }, { percentDecode(it.fragment!!) })
            .mapValues { it.value.toSet() }

        private val images = mutableMapOf<String, ByteArray>()
        private val noteDocuments = mutableMapOf<String, org.jsoup.nodes.Document?>()

        /** Where each spine item ended up. */
        private val placed = mutableMapOf<Int, Placement>()
        /** Spine indexes of items too short to be chapters, waiting for the next chapter. */
        private val skipped = mutableListOf<Int>()
        /** Images from skipped items, shown at the top of the next chapter. */
        private val carriedImages = mutableListOf<ChapterImage>()

        fun tocTitle(chapterKey: String): String? = rawToc
            .firstOrNull { it.path == chapterKey && it.fragment == null }
            ?.title?.takeIf { it.length <= 120 }

        fun formatChapter(spineIndex: Int, key: String, body: String, text: String, order: Int): ChapterFormatting? {
            skipped.forEach { placed[it] = Placement(order, emptyMap(), kept = false) }
            skipped.clear()

            val result = try {
                markChapter(spineIndex, key, body, text)
            } catch (e: Exception) {
                Logger.e(TAG, "Formatting for $key could not be read; showing it plain", e)
                null
            }
            placed[spineIndex] = Placement(order, result?.anchors ?: emptyMap(), kept = true)

            var formatting = result?.formatting ?: ChapterFormatting()
            if (carriedImages.isNotEmpty()) {
                formatting = formatting.copy(images = carriedImages.toList() + formatting.images)
                carriedImages.clear()
            }
            return formatting.takeUnless { it.isEmpty }
        }

        /** An item with too little text to be a chapter: keep its pictures for the next one. */
        fun skipChapter(spineIndex: Int, key: String, body: String) {
            skipped += spineIndex
            try {
                val (_, events) = EpubMarkup.markUp(body, EpubMarkup.Context(resolveImage = { imageKey(key, it) }))
                events.filterIsInstance<EpubMarkup.Event.Image>()
                    .forEach { carriedImages += ChapterImage(0, it.path, it.alt) }
            } catch (e: Exception) {
                Logger.e(TAG, "Images in $key could not be read", e)
            }
        }

        private fun markChapter(spineIndex: Int, key: String, body: String, text: String): EpubMarkup.Result? {
            val context = EpubMarkup.Context(
                classStyles = classStyles,
                resolveImage = { imageKey(key, it) },
                resolveNote = { href, explicit, linkOffset -> noteText(spineIndex, key, body, href, explicit, linkOffset) },
                wantedAnchors = anchorsWanted[key].orEmpty()
            )
            val (marked, events) = EpubMarkup.markUp(body, context)
            if (events.isEmpty()) return null
            return EpubMarkup.resolve(bodyToText(marked), text, events)
        }

        private fun imageKey(chapterKey: String, src: String): String? {
            val key = resolvePath(zipEntries, chapterKey.substringBeforeLast('/', ""), src) ?: return null
            if (key == coverKey) return null
            val bytes = zipEntries[key] ?: return null
            images[key] = bytes
            return key
        }

        private fun noteText(
            spineIndex: Int,
            chapterKey: String,
            body: String,
            href: String,
            explicit: Boolean,
            linkOffset: Int
        ): String? {
            val fragment = href.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let(::percentDecode)
                ?: return null
            val target = if (href.startsWith("#")) chapterKey
            else resolvePath(zipEntries, chapterKey.substringBeforeLast('/', ""), href) ?: return null

            // A link to something EARLIER is a note's link back to its
            // reference, not a reference: the two look identical otherwise.
            if (!explicit) {
                val later = if (target == chapterKey) {
                    val idAt = Regex("\\bid\\s*=\\s*[\"']" + Regex.escape(fragment) + "[\"']").find(body)?.range?.first
                    idAt != null && idAt > linkOffset
                } else {
                    spineIndexOf[target]?.let { it > spineIndex } ?: true
                }
                if (!later) return null
            }

            val doc = noteDocuments.getOrPut(target) {
                zipEntries[target]?.let { org.jsoup.Jsoup.parse(it.decodeToString()) }
            } ?: return null
            var element: org.jsoup.nodes.Element = doc.getElementById(fragment) ?: return null
            // Targets are often an empty anchor or a bare label inside the
            // note's paragraph; climb to the block that holds the note.
            while (element.normalName() !in NOTE_BLOCKS || element.text().length < 3) {
                element = element.parent() ?: break
                if (element.normalName() == "body") return null
            }
            return element.text().takeIf { it.isNotBlank() }
        }

        private fun readToc(opfData: OpfData, opfDir: String): List<EpubToc.RawEntry> {
            fun read(item: ManifestItem?, parse: (String, (String) -> String?) -> List<EpubToc.RawEntry>): List<EpubToc.RawEntry> {
                val key = item?.let { resolvePath(zipEntries, opfDir, it.href) } ?: return emptyList()
                val bytes = zipEntries[key] ?: return emptyList()
                val dir = key.substringBeforeLast('/', "")
                return parse(bytes.decodeToString()) { href -> resolvePath(zipEntries, dir, href) }
            }
            val nav = opfData.manifest.firstOrNull { it.properties.split(' ').contains("nav") }
            val fromNav = try { read(nav, EpubToc::parseNav) } catch (e: Exception) { emptyList() }
            if (fromNav.isNotEmpty()) return fromNav
            val ncx = opfData.manifest.firstOrNull { it.id == opfData.spineToc }
                ?: opfData.manifest.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            return try { read(ncx, EpubToc::parseNcx) } catch (e: Exception) { emptyList() }
        }

        fun finish(chapters: List<EpubChapter>): Finished {
            var result = chapters
            // Pictures after the last chapter (an illustrated back page)
            // go at the end of it rather than nowhere.
            if (carriedImages.isNotEmpty() && result.isNotEmpty()) {
                val last = result.last()
                val count = com.abhinavxt.novelforge.util.ParagraphSplitter.split(last.content).size
                val previous = last.formatting ?: ChapterFormatting()
                val formatting = previous.copy(images = previous.images + carriedImages.map { it.copy(beforeParagraph = count) })
                result = result.dropLast(1) + last.copy(formatting = formatting)
                carriedImages.clear()
            }

            val toc = rawToc.mapIndexedNotNull { i, entry ->
                val spineIndex = spineIndexOf[entry.path] ?: return@mapIndexedNotNull null
                val place = placed[spineIndex] ?: return@mapIndexedNotNull null
                // A skipped item (a part title page) only earns a line when
                // it heads a group of entries.
                if (!place.kept && (rawToc.getOrNull(i + 1)?.depth ?: -1) <= entry.depth) return@mapIndexedNotNull null
                val paragraph = entry.fragment?.let { place.anchors[percentDecode(it)] } ?: 0
                TocEntry(entry.title, entry.depth, place.chapter, paragraph)
            }

            val used = result.mapNotNull { it.formatting }.flatMap { it.images }.map { it.path }.toSet()
            return Finished(result, toc, images.filterKeys { it in used })
        }
    }

    /** A spine item's chapter: its own when [kept], else the next one's. */
    private class Placement(val chapter: Int, val anchors: Map<String, Int>, val kept: Boolean)

    private class Finished(
        val chapters: List<EpubChapter>,
        val toc: List<TocEntry>,
        val images: Map<String, ByteArray>
    )

    /**
     * An href relative to [baseDir] -> its entry name in the ZIP, resolving
     * `..` and percent-encoding. Used for images, notes and the contents; the
     * chapters themselves keep going through [resolveEntryKey], unchanged.
     */
    private fun resolvePath(zipEntries: Map<String, ByteArray>, baseDir: String, href: String): String? {
        val clean = href.substringBefore('#').substringBefore('?').trim()
        if (clean.isEmpty() || clean.contains("://") || clean.startsWith("data:")) return null
        for (raw in linkedSetOf(clean, percentDecode(clean))) {
            val joined = if (raw.startsWith("/") || baseDir.isEmpty()) raw.removePrefix("/") else "$baseDir/$raw"
            val normalized = normalizePath(joined)
            if (normalized in zipEntries) return normalized
        }
        val fileName = percentDecode(clean).substringAfterLast('/')
        if (fileName.isEmpty()) return null
        return zipEntries.keys.firstOrNull { it.substringAfterLast('/') == fileName }
    }

    private fun normalizePath(path: String): String {
        val parts = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
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
    ): ByteArray? = resolveEntryKey(zipEntries, opfDir, href)?.let { zipEntries[it] }

    /** The ZIP entry name [resolveEntry] finds. */
    private fun resolveEntryKey(
        zipEntries: Map<String, ByteArray>,
        opfDir: String,
        href: String
    ): String? {
        val clean = href.substringBefore('#')
        val candidates = LinkedHashSet<String>()
        fun add(path: String) {
            candidates.add(path)
            candidates.add(percentDecode(path))
        }
        if (opfDir.isNotEmpty()) add("$opfDir/$clean")
        add(clean)

        for (candidate in candidates) {
            if (zipEntries.containsKey(candidate)) return candidate
        }

        val fileName = percentDecode(clean).substringAfterLast('/')
        if (fileName.isNotEmpty()) {
            zipEntries.keys
                .firstOrNull { it.substringAfterLast('/') == fileName }
                ?.let { return it }
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
        val spineItems: List<SpineItem>,
        val manifest: List<ManifestItem> = emptyList(),
        /** The spine's `toc` attribute: the manifest id of the NCX. */
        val spineToc: String? = null
    )

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String
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
        val manifestItems = mutableListOf<ManifestItem>()
        var spineToc: String? = null
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
                                    manifestItems += ManifestItem(id, href, mediaType.orEmpty(), properties.orEmpty())

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
                            "spine" -> spineToc = parser.getAttributeValue(null, "toc")
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
            spineItems = spineItems,
            manifest = manifestItems,
            spineToc = spineToc
        )
    }

    /** Elements that can hold a footnote's text. */
    private val NOTE_BLOCKS = setOf("p", "li", "div", "aside", "section", "dd", "td", "blockquote", "footer")

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
     * Chapter text is [prepareBody] then [bodyToText]: tags stripped,
     * entities decoded, whitespace normalised.
     *
     * Archived books are verified against this output, so it must keep
     * returning exactly the same text for the same file. It is two steps
     * only so that [EpubMarkup] can run the second on a marked-up body;
     * together they do what the single function always did.
     *
     * This step: the chapter document without its <head> and the heading
     * taken as its title.
     */
    private fun prepareBody(html: String): String {
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
        return if (extractChapterTitle(html) != null) {
            HEADING_BLOCK.replaceFirst(withoutHead, "")
        } else {
            withoutHead
        }
    }

    /** Tags stripped, entities decoded, whitespace normalised. */
    private fun bodyToText(body: String): String {
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