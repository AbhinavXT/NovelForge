package com.abhinavxt.novelforge.data.annas

import com.abhinavxt.novelforge.data.annas.AnnasArchiveParser.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnasArchiveParserTest {

    private val base = "https://annas-archive.gl/search?q=dune"
    private val md5a = "92651ea7d95073ba4c8d345285b6bf74"
    private val md5b = "036d99cfbc907d48d12eea194c6189b6"

    /** The current card: cover link, then a details block with separate links. */
    private fun currentCard(md5: String, title: String) = """
        <div class="flex pt-3 pb-3 border-b">
          <a href="/md5/$md5" class="custom-a"><img src="https://covers.example/$md5.jpg"></a>
          <div class="max-w-full">
            <div>
              <a href="/md5/$md5" class="js-vim-focus custom-a">$title<span class="hidden">score 0.9</span></a>
            </div>
            <div><a href="/search?q=Frank+Herbert"><span class="icon-[mdi--user-edit]"></span>Frank Herbert</a></div>
            <div><a href="/search?q=Ace"><span class="icon-[mdi--company]"></span>Ace, 2005</a></div>
            <div class="text-gray-800 font-semibold text-sm">English [en] · EPUB · 0.8MB · 2005 · 📘 Book (fiction) · <a href="#">save</a></div>
          </div>
        </div>
    """

    private fun page(body: String) =
        "<html><head><title>Search - Anna's Archive</title></head><body>$body</body></html>"

    @Test
    fun `current layout gives title, author, publisher, cover and file facts`() {
        val result = AnnasArchiveParser.parseSearch(page(currentCard(md5a, "Dune")), base)
        val book = (result as Page.Results).books.single()
        assertEquals(md5a, book.md5)
        assertEquals("Dune", book.title)
        assertEquals("Frank Herbert", book.author)
        assertEquals("Ace, 2005", book.publisher)
        assertEquals("https://covers.example/$md5a.jpg", book.coverUrl)
        assertEquals("en", book.language)
        assertEquals("epub", book.format)
        assertEquals("0.8MB", book.size)
        assertTrue(book.metadata.startsWith("English [en] · EPUB"))
        assertTrue("inline links are not part of the summary", "save" !in book.metadata)
    }

    @Test
    fun `legacy single-anchor layout is still read`() {
        val html = page(
            """
            <a href="/md5/$md5a" class="custom-a flex">
              <img src="/covers/a.jpg">
              <div>
                <div class="text-gray-500">English [en], .epub, 🚀/lgli/zlib, 0.6MB, dune.epub</div>
                <h3>Dune Messiah</h3>
                <div>Putnam</div>
                <div class="italic">Herbert, Frank</div>
              </div>
            </a>
            """
        )
        val book = (AnnasArchiveParser.parseSearch(html, base) as Page.Results).books.single()
        assertEquals("Dune Messiah", book.title)
        assertEquals("Herbert, Frank", book.author)
        assertEquals("Putnam", book.publisher)
        assertEquals("https://annas-archive.gl/covers/a.jpg", book.coverUrl)
        assertEquals("0.6MB", book.size)
        assertEquals("en", book.language)
    }

    @Test
    fun `cards hidden in comments are revealed, and duplicates kept once`() {
        val html = page(
            currentCard(md5a, "Dune") +
                    """<div class="js-scroll-hidden"><!--${currentCard(md5b, "Children of Dune")}--></div>""" +
                    """<div class="js-scroll-hidden"><!--${currentCard(md5a, "Dune")}--></div>"""
        )
        val books = (AnnasArchiveParser.parseSearch(html, base) as Page.Results).books
        assertEquals(listOf("Dune", "Children of Dune"), books.map { it.title })
    }

    @Test
    fun `a card without a cover has no cover url, not the page's own`() {
        val html = page(currentCard(md5a, "Dune").replace("https://covers.example/$md5a.jpg", ""))
        val book = (AnnasArchiveParser.parseSearch(html, base) as Page.Results).books.single()
        assertEquals("", book.coverUrl)
    }

    @Test
    fun `no results page is an empty result, not an error`() {
        val html = page("<div>No files found. Try fewer or different search terms.</div>")
        assertEquals(Page.Results(emptyList()), AnnasArchiveParser.parseSearch(html, base))
    }

    @Test
    fun `an unknown layout is reported, not taken for no results`() {
        assertEquals(Page.Unrecognized, AnnasArchiveParser.parseSearch(page("<div>hello</div>"), base))
    }

    @Test
    fun `ddos-guard check page is recognised`() {
        // As served by annas-archive.gl to a signed-out client.
        val html = """<!doctype html><html><head><title>DDoS-Guard</title>
            <script defer="defer" src="/.well-known/ddos-guard/js-challenge/index.js"></script>
            </head><body><h1>Checking your browser before accessing</h1></body></html>"""
        assertEquals(Page.Challenge, AnnasArchiveParser.parseSearch(html, base))
    }

    @Test
    fun `parked domain is recognised`() {
        // As served by the lapsed annas-archive.li.
        val html = """<html><head><script async src="https://assets.abovedomains.com/javascript/forsale.min.js?d=annas-archive.li"></script></head></html>"""
        assertEquals(Page.Parked, AnnasArchiveParser.parseSearch(html, base))
    }

    @Test
    fun `md5 is taken only from record links`() {
        assertEquals(md5a, AnnasArchiveParser.md5Of("/md5/${md5a.uppercase()}"))
        assertEquals(md5a, AnnasArchiveParser.md5Of("https://annas-archive.gl/md5/$md5a?r=x"))
        assertNull(AnnasArchiveParser.md5Of("/md5/$md5a/extra"))
        assertNull(AnnasArchiveParser.md5Of("/search?q=/md5/$md5a"))
    }

    @Test
    fun `mirror addresses are normalised to an origin`() {
        assertEquals("https://annas-archive.gl", AnnasArchivePrefs.normalizeBaseUrl("annas-archive.gl"))
        assertEquals("https://annas-archive.pk", AnnasArchivePrefs.normalizeBaseUrl(" https://annas-archive.pk/search?q=x "))
        assertEquals("http://10.0.2.2:8080", AnnasArchivePrefs.normalizeBaseUrl("http://10.0.2.2:8080/"))
        assertNull(AnnasArchivePrefs.normalizeBaseUrl(""))
        assertNull(AnnasArchivePrefs.normalizeBaseUrl("not a url"))
    }
}
