package com.abhinavxt.novelreader.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.abhinavxt.novelreader.data.NovelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports a novel's downloaded chapters as a standard EPUB 3 file
 * (with EPUB 2 NCX for older readers) and opens the system share
 * sheet — archival / portability counterpart to EPUB import.
 *
 * Follows the AnnotationExporter pattern: write to cacheDir/exports,
 * share via the app's FileProvider.
 *
 * Memory: chapter content is read from Room ONE CHAPTER AT A TIME
 * and streamed straight into the zip — a 2,000-chapter novel never
 * has more than one chapter's text in memory.
 *
 * EPUB specifics honored here:
 *  - `mimetype` is the FIRST entry, STORED (uncompressed), so byte
 *    30 of the file spells "mimetype…" as readers expect.
 *  - content.opf carries the EPUB3-required dcterms:modified meta.
 *  - All user text is XML-escaped; chapters render as one <p> per
 *    paragraph using the same ParagraphSplitter as the reader.
 */
object EpubExporter {

    sealed class ExportResult {
        object Shared : ExportResult()
        /** Novel has no downloaded chapters — nothing to export. */
        object Empty : ExportResult()
        data class Error(val message: String) : ExportResult()
    }

    suspend fun exportAndShare(
        context: Context,
        repository: NovelRepository,
        novelId: String,
        novelTitle: String
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            val novel = repository.getNovelById(novelId)
                ?: return@withContext ExportResult.Error("Novel not found")

            val downloaded = repository.getChaptersOnce(novelId)
                .filter { it.isDownloaded }
                .sortedBy { it.number }
            if (downloaded.isEmpty()) return@withContext ExportResult.Empty

            // Cover: only local files (EPUB-imported or cached covers).
            // Remote URLs are skipped — export must work offline.
            val coverFile = novel.coverUrl
                ?.takeIf { it.startsWith("/") }
                ?.let { File(it) }
                ?.takeIf { it.exists() && it.length() > 0 }

            val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val safeTitle = novelTitle
                .replace(Regex("[^A-Za-z0-9 _-]"), "")
                .trim()
                .take(60)
                .ifEmpty { "novel" }
                .replace(" ", "_")
            val epubFile = File(exportsDir, "$safeTitle.epub")

            ZipOutputStream(epubFile.outputStream().buffered()).use { zip ->
                writeMimetype(zip)
                writeText(zip, "META-INF/container.xml", CONTAINER_XML)
                writeText(zip, "OEBPS/style.css", STYLE_CSS)

                // Cover: image-only, declared via the cover-image manifest
                // property + legacy meta. Deliberately NO cover.xhtml spine
                // page — our own EpubImporter walks the spine, and a cover
                // page would round-trip as a phantom "chapter 0".
                if (coverFile != null) {
                    zip.putNextEntry(ZipEntry("OEBPS/cover.jpg"))
                    FileInputStream(coverFile).use { it.copyTo(zip) }
                    zip.closeEntry()
                }

                downloaded.forEachIndexed { idx, chapter ->
                    // One chapter's text in memory at a time.
                    val content = repository.getDownloadedChapterContent(chapter.id) ?: ""
                    writeText(
                        zip,
                        "OEBPS/${chapterFileName(idx)}",
                        chapterXhtml(chapter.title, content)
                    )
                }

                writeText(zip, "OEBPS/nav.xhtml", navXhtml(novel.title, downloaded.map { it.title }))
                writeText(zip, "OEBPS/toc.ncx", tocNcx(novelId, novel.title, downloaded.map { it.title }))
                writeText(
                    zip, "OEBPS/content.opf",
                    contentOpf(
                        novelId = novelId,
                        title = novel.title,
                        author = novel.author,
                        description = novel.description,
                        chapterCount = downloaded.size,
                        hasCover = coverFile != null
                    )
                )
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                epubFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/epub+zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "$novelTitle.epub")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(shareIntent, "Export EPUB").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            ExportResult.Shared
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("EpubExporter", "Export failed", e)
            ExportResult.Error(e.message ?: "Export failed")
        }
    }

    // ── Zip plumbing ─────────────────────────────────────────────

    /**
     * The mimetype entry must be first and STORED. STORED entries
     * need size + CRC set up front — ZipOutputStream can't infer
     * them without compression.
     */
    private fun writeMimetype(zip: ZipOutputStream) {
        val bytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        val entry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeText(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun chapterFileName(index: Int) = "chapter_%04d.xhtml".format(index + 1)

    private fun escapeXml(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    // ── Documents ────────────────────────────────────────────────

    private const val CONTAINER_XML = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

    private const val STYLE_CSS = """body { margin: 5%; line-height: 1.6; }
h1 { font-size: 1.3em; margin-bottom: 1em; }
p { text-indent: 1.2em; margin: 0 0 0.6em 0; text-align: justify; }
.cover { text-align: center; margin: 0; }
.cover img { max-width: 100%; max-height: 100%; }
"""

    private fun chapterXhtml(title: String, content: String): String {
        val paragraphs = ParagraphSplitter.split(content)
        val body = if (paragraphs.isEmpty()) {
            "<p></p>"
        } else {
            paragraphs.joinToString("\n") { "<p>${escapeXml(it)}</p>" }
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
  <title>${escapeXml(title)}</title>
  <link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
  <h1>${escapeXml(title)}</h1>
$body
</body>
</html>
"""
    }

    private fun navXhtml(title: String, chapterTitles: List<String>): String {
        val listItems = chapterTitles.mapIndexed { idx, t ->
            """      <li><a href="${chapterFileName(idx)}">${escapeXml(t)}</a></li>"""
        }.joinToString("\n")
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head>
  <title>${escapeXml(title)}</title>
  <link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>${escapeXml(title)}</h1>
    <ol>
$listItems
    </ol>
  </nav>
</body>
</html>
"""
    }

    private fun tocNcx(novelId: String, title: String, chapterTitles: List<String>): String {
        val navPoints = chapterTitles.mapIndexed { idx, t ->
            """    <navPoint id="np_${idx + 1}" playOrder="${idx + 1}">
      <navLabel><text>${escapeXml(t)}</text></navLabel>
      <content src="${chapterFileName(idx)}"/>
    </navPoint>"""
        }.joinToString("\n")
        return """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="${bookUuid(novelId)}"/>
    <meta name="dtb:depth" content="1"/>
    <meta name="dtb:totalPageCount" content="0"/>
    <meta name="dtb:maxPageNumber" content="0"/>
  </head>
  <docTitle><text>${escapeXml(title)}</text></docTitle>
  <navMap>
$navPoints
  </navMap>
</ncx>
"""
    }

    private fun contentOpf(
        novelId: String,
        title: String,
        author: String,
        description: String,
        chapterCount: Int,
        hasCover: Boolean
    ): String {
        val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

        val coverManifest = if (hasCover) {
            """    <item id="cover-image" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>""" + "\n"
        } else ""
        val coverMeta = if (hasCover) """    <meta name="cover" content="cover-image"/>""" + "\n" else ""

        val chapterManifest = (1..chapterCount).joinToString("\n") { i ->
            """    <item id="ch$i" href="${chapterFileName(i - 1)}" media-type="application/xhtml+xml"/>"""
        }
        val chapterSpine = (1..chapterCount).joinToString("\n") { i ->
            """    <itemref idref="ch$i"/>"""
        }

        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="book-id">urn:uuid:${bookUuid(novelId)}</dc:identifier>
    <dc:title>${escapeXml(title)}</dc:title>
    <dc:creator>${escapeXml(author.ifBlank { "Unknown" })}</dc:creator>
    <dc:language>en</dc:language>
    <dc:description>${escapeXml(description)}</dc:description>
    <meta property="dcterms:modified">$modified</meta>
$coverMeta  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="css" href="style.css" media-type="text/css"/>
$coverManifest$chapterManifest
  </manifest>
  <spine toc="ncx">
$chapterSpine
  </spine>
</package>
"""
    }

    /**
     * Stable identifier: the same novel always exports with the same
     * UUID (derived from its id), so a re-export is recognizably the
     * same book by readers that track identifiers.
     */
    private fun bookUuid(novelId: String): UUID =
        UUID.nameUUIDFromBytes("novelforge:$novelId".toByteArray(Charsets.UTF_8))
}
