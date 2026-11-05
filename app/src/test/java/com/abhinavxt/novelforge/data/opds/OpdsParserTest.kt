package com.abhinavxt.novelforge.data.opds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures follow the shapes Project Gutenberg, Calibre-Web and Standard Ebooks serve. */
class OpdsParserTest {

    private fun feed(body: String, feedLinks: String = "") = """<?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
          <id>urn:test</id><title>Test Catalog</title>
          $feedLinks
          $body
        </feed>"""

    @Test
    fun `navigation entries open their subsection`() {
        val xml = feed(
            """<entry><id>pop</id><title>Popular</title><content type="text">Our most popular books.</content>
               <link type="application/atom+xml;profile=opds-catalog" rel="subsection" href="/ebooks/search.opds/?sort_order=downloads"/>
               <link type="image/png" rel="http://opds-spec.org/image/thumbnail" href="data:image/png;base64,AAAA"/></entry>""",
            """<link rel="next" type="application/atom+xml;profile=opds-catalog" href="/ebooks/search.opds/?start_index=26"/>
               <link rel="search" type="application/opensearchdescription+xml" href="https://www.gutenberg.org/catalog/osd-books.xml"/>"""
        )
        val f = OpdsParser.parseFeed(xml, "https://www.gutenberg.org/ebooks.opds/")
        assertEquals("Test Catalog", f.title)
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?start_index=26", f.nextUrl)
        assertEquals("https://www.gutenberg.org/catalog/osd-books.xml", f.openSearchUrl)
        val e = f.entries.single()
        assertFalse(e.isBook)
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads", e.navigationUrl)
        assertEquals("Our most popular books.", e.summary)
        assertEquals("inline data: icons are not covers", "", e.coverUrl)
    }

    @Test
    fun `a book offers its free EPUBs, best first, and names formats it can't import`() {
        val xml = feed(
            """<entry><id>urn:1342</id><title>Pride and Prejudice</title>
               <author><name>Austen, Jane</name></author>
               <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>This edition has images.</p></div></content>
               <link type="application/epub+zip" rel="http://opds-spec.org/acquisition" title="EPUB (older e-readers, no images)" length="609138" href="https://www.gutenberg.org/ebooks/1342.epub.noimages"/>
               <link type="application/epub+zip" rel="http://opds-spec.org/acquisition" title="EPUB (older e-readers)" length="24876678" href="https://www.gutenberg.org/ebooks/1342.epub.images"/>
               <link type="application/epub+zip" rel="http://opds-spec.org/acquisition" title="EPUB3 (E-readers incl. Send-to-Kindle)" length="24864457" href="https://www.gutenberg.org/ebooks/1342.epub3.images"/>
               <link type="application/x-mobipocket-ebook" rel="http://opds-spec.org/acquisition" href="https://www.gutenberg.org/ebooks/1342.kindle.images"/>
               <link type="application/epub+zip" rel="http://opds-spec.org/acquisition/buy" href="https://shop.example/1342.epub"/>
               <link type="image/jpeg" rel="http://opds-spec.org/image/thumbnail" href="/cache/epub/1342/pg1342.cover.small.jpg"/>
               <link type="image/jpeg" rel="http://opds-spec.org/image" href="/cache/epub/1342/pg1342.cover.medium.jpg"/>
               <link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/author/68.opds"/></entry>"""
        )
        val e = OpdsParser.parseFeed(xml, "https://www.gutenberg.org/ebooks/1342.opds").entries.single()
        assertTrue(e.isBook)
        assertEquals(listOf("Austen, Jane"), e.authors)
        assertEquals("This edition has images.", e.summary)
        assertEquals("https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg", e.coverUrl)
        assertNull("related links aren't somewhere the book goes", e.navigationUrl)
        assertEquals(
            listOf("1342.epub3.images", "1342.epub.images", "1342.epub.noimages"),
            e.acquisitions.map { it.url.substringAfterLast('/') }
        )
        assertEquals(24864457L, e.acquisitions.first().length)
        assertEquals(listOf("Kindle"), e.otherFormats)
    }

    @Test
    fun `escaped html summaries and open-access links (Calibre-Web style)`() {
        val xml = feed(
            """<entry><id>urn:uuid:1</id><title>Dune</title><author><name>Frank Herbert</name></author>
               <summary type="html">&lt;p&gt;A &lt;b&gt;desert&lt;/b&gt; planet.&lt;/p&gt;</summary>
               <link rel="http://opds-spec.org/acquisition/open-access" type="application/epub+zip; charset=binary" href="/opds/download/1/epub/"/>
               <link rel="http://opds-spec.org/acquisition" type="application/pdf" href="/opds/download/1/pdf/"/></entry>""",
            """<link rel="search" type="application/atom+xml" href="/opds/search/{searchTerms}"/>"""
        )
        val f = OpdsParser.parseFeed(xml, "https://books.example.org/opds")
        assertEquals("https://books.example.org/opds/search/{searchTerms}", f.searchTemplate)
        val e = f.entries.single()
        assertEquals("A desert planet.", e.summary)
        assertEquals("https://books.example.org/opds/download/1/epub/", e.acquisitions.single().url)
        assertEquals(listOf("PDF"), e.otherFormats)
    }

    @Test
    fun `opensearch description gives the atom template, and filling it drops optional params`() {
        val osd = """<?xml version="1.0" encoding="UTF-8"?>
            <OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
              <Url type="text/html" template="http://www.gutenberg.org/ebooks/search/?query={searchTerms}"/>
              <Url type="application/atom+xml" template="http://m.gutenberg.org/ebooks/search.opds/?query={searchTerms}&amp;page={startPage?}"/>
            </OpenSearchDescription>"""
        val template = OpdsParser.parseOpenSearch(osd, "https://www.gutenberg.org/catalog/osd-books.xml")
        assertEquals("http://m.gutenberg.org/ebooks/search.opds/?query={searchTerms}&page={startPage?}", template)
        assertEquals(
            "http://m.gutenberg.org/ebooks/search.opds/?query=pride%20%26%20prejudice&page=",
            OpdsParser.fillTemplate(template!!, " pride & prejudice ")
        )
    }

    @Test
    fun `an html page is not a feed`() {
        assertTrue(OpdsParser.looksLikeFeed(feed("")))
        assertFalse(OpdsParser.looksLikeFeed("<!doctype html><html><head><title>Join the Patrons Circle</title></head></html>"))
    }
}
