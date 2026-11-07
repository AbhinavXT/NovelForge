package com.abhinavxt.novelforge.data.epub

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds small EPUBs in memory for tests. */
object TestEpub {

    class Item(val id: String, val href: String, val mediaType: String, val body: ByteArray, val properties: String? = null)

    fun xhtml(body: String, title: String = "x") =
        """<?xml version="1.0" encoding="utf-8"?>
    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
    <head><title>$title</title><link rel="stylesheet" href="../Styles/style.css"/></head>
    <body>$body</body></html>"""

    fun chapter(id: String, body: String) =
        Item(id, "Text/$id.xhtml", "application/xhtml+xml", xhtml(body).toByteArray())

    fun build(
        spine: List<Item>,
        others: List<Item> = emptyList(),
        coverId: String? = null,
        ncxId: String? = null
    ): ByteArray {
        val all = spine + others
        val manifest = all.joinToString("\n") { item ->
            val props = item.properties?.let { " properties=\"$it\"" } ?: ""
            """<item id="${item.id}" href="${item.href}" media-type="${item.mediaType}"$props/>"""
        }
        val itemrefs = spine.joinToString("\n") { """<itemref idref="${it.id}"/>""" }
        val coverMeta = coverId?.let { """<meta name="cover" content="$it"/>""" } ?: ""
        val tocAttr = ncxId?.let { """ toc="$it"""" } ?: ""
        val opf = """<?xml version="1.0"?>
    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Test Book</dc:title><dc:creator>Someone</dc:creator>$coverMeta
    </metadata>
    <manifest>$manifest</manifest>
    <spine$tocAttr>$itemrefs</spine>
    </package>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray())
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
    <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray()
            )
            put("OEBPS/content.opf", opf.toByteArray())
            all.forEach { put("OEBPS/${it.href}", it.body) }
        }
        return out.toByteArray()
    }
}
