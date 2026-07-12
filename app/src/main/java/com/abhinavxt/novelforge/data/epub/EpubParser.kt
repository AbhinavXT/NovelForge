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
        val coverImage = opfData.coverPath?.let { coverPath ->
            val fullPath = if (opfDir.isNotEmpty()) "$opfDir/$coverPath" else coverPath
            zipEntries[fullPath] ?: zipEntries[coverPath]
        }

        // Step 4: Parse chapters in spine order
        val chapters = mutableListOf<EpubChapter>()
        opfData.spineItems.forEachIndexed { index, spineItem ->
            val chapterPath = if (opfDir.isNotEmpty()) "$opfDir/${spineItem.href}" else spineItem.href
            val chapterContent = zipEntries[chapterPath] ?: zipEntries[spineItem.href]

            if (chapterContent != null) {
                val htmlContent = chapterContent.decodeToString()
                val plainText = extractTextFromHtml(htmlContent)

                // Skip empty chapters or very short ones (likely just titles/images)
                if (plainText.length > 100) {
                    chapters.add(
                        EpubChapter(
                            title = spineItem.title ?: "Chapter ${chapters.size + 1}",
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
        var coverId: String? = null
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

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name ?: ""

                        when (currentTag) {
                            "metadata" -> inMetadata = true
                            "item" -> {
                                val id = parser.getAttributeValue(null, "id")
                                val href = parser.getAttributeValue(null, "href")
                                val mediaType = parser.getAttributeValue(null, "media-type")

                                if (id != null && href != null) {
                                    manifest[id] = href

                                    // Check if this is the cover image
                                    if (mediaType?.startsWith("image/") == true &&
                                        (id.contains("cover", ignoreCase = true) ||
                                                href.contains("cover", ignoreCase = true))) {
                                        coverId = id
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
                                    coverId = content
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        if (inMetadata && text.isNotEmpty()) {
                            when {
                                currentTag == "title" || currentTag.endsWith(":title") ->
                                    if (title == null) title = text
                                currentTag == "creator" || currentTag.endsWith(":creator") ->
                                    if (author == null) author = text
                                currentTag == "description" || currentTag.endsWith(":description") ->
                                    if (description == null) description = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "metadata") {
                            inMetadata = false
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

        // Get cover path
        val coverPath = coverId?.let { manifest[it] }

        return OpfData(
            title = title,
            author = author,
            description = description,
            coverPath = coverPath,
            spineItems = spineItems
        )
    }

    /**
     * Extract plain text from HTML content
     * Strips all tags and normalizes whitespace
     */
    private fun extractTextFromHtml(html: String): String {
        return html
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
                val code = matchResult.groupValues[1].toIntOrNull()
                if (code != null) code.toChar().toString() else ""
            }
            // Normalize whitespace
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n\n")
            .trim()
    }
}