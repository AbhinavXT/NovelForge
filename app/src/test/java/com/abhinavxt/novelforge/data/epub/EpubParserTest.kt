package com.abhinavxt.novelforge.data.epub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.abhinavxt.novelforge.util.ParagraphSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpubParserTest {

    private fun chapter(id: String, body: String) = TestEpub.chapter(id, body)
    private fun xhtml(body: String) = TestEpub.xhtml(body)
    private fun epub(spine: List<TestEpub.Item>, others: List<TestEpub.Item> = emptyList(), coverId: String? = null, ncxId: String? = null) =
        TestEpub.build(spine, others, coverId, ncxId)

    private val parser = EpubParser(ApplicationProvider.getApplicationContext())

    // ── Building test books ─────────────────────────────────────────

    private fun parse(bytes: ByteArray): EpubBook = parser.parseEpub(ByteArrayInputStream(bytes))!!

    private fun EpubChapter.paragraphs() = ParagraphSplitter.split(content)

    /** The text of [span] as it reads in [chapter]. */
    private fun EpubChapter.textOf(span: StyleSpan) = paragraphs()[span.paragraph].substring(span.start, span.end)

    private val css = TestEpub.Item(
        "css", "Styles/style.css", "text/css",
        """
        /* Calibre-style classes */
        .calibre3 { font-style: italic }
        span.bold1, .heavy { font-weight: 700; }
        .centered { text-align: center; }
        @media screen { .under { text-decoration: underline } }
        """.trimIndent().toByteArray()
    )

    // ── Text must not change ────────────────────────────────────────

    @Test
    fun `reads the book's language, dropping values that are not tags`() {
        val body = listOf(chapter("c1", "<p>Long enough to count as a chapter of this little book.</p>"))
        assertEquals("de-DE", parse(TestEpub.build(body, language = " de-DE ")).language)
        assertNull(parse(TestEpub.build(body, language = "English")).language)
        assertNull(parse(TestEpub.build(body, language = "und")).language)
        assertNull(parse(TestEpub.build(body)).language)
    }

    @Test
    fun `blockquotes of bare text are separate paragraphs`() {
        val bytes = epub(listOf(chapter("c1",
            "<blockquote>He spoke at some length about the case.</blockquote><blockquote>“I assume this is the glass.”</blockquote>"
        )))
        assertEquals(
            listOf("He spoke at some length about the case.", "“I assume this is the glass.”"),
            parse(bytes).chapters.single().paragraphs()
        )
        val old = EpubParser(ApplicationProvider.getApplicationContext(), blockquoteBreaks = false)
            .parseEpub(ByteArrayInputStream(bytes))!!
        assertEquals(
            "He spoke at some length about the case.“I assume this is the glass.”",
            old.chapters.single().content
        )
    }

    @Test
    fun `blockquotes holding paragraphs keep their exact text`() {
        val bytes = epub(listOf(chapter("c1",
            "<blockquote><p>First quoted line of the letter.</p></blockquote><p>Then the story carries on.</p>"
        )))
        val old = EpubParser(ApplicationProvider.getApplicationContext(), blockquoteBreaks = false)
            .parseEpub(ByteArrayInputStream(bytes))!!
        assertEquals(old.chapters.single().content, parse(bytes).chapters.single().content)
    }


    @Test
    fun `chapter text is exactly what the old extractor produced`() {
        val bodies = listOf(
            "<h1>One</h1><p>Plain <i>italic</i> and <b>bold</b>, then <em>more  </em> text.</p><p>Second paragraph &amp; entity &#8220;quoted&#8221;.</p>",
            "<h2 id=\"c2\">Two</h2><p>a <i> b </i> c   <span class=\"calibre3\">\nwrapped\n</span> end of the line here.</p><br/><blockquote><p>Quoted words that run on.</p></blockquote>",
            "<p>Scene one has enough words in it to count.</p><p><img src=\"../Images/a.png\" alt=\"A map\"/></p><p>After the picture we carry on.</p><script>var x = '<i>';</script><style>p{}</style>",
            "<p>Note here<a href=\"#fn1\" epub:type=\"noteref\">1</a> and a <a href=\"x.xhtml\">real link</a> with words.</p><aside id=\"fn1\" epub:type=\"footnote\"><p>1. The note text.</p></aside>",
            "<div><p>Odd < tags <i>x</i> and an <img alt=\"a>b\" src=\"../Images/a.png\"> quote.</p></div><p> </p><p>Last line in the chapter.</p>"
        )
        val spine = bodies.mapIndexed { i, b -> chapter("c$i", b) }
        val image = TestEpub.Item("img", "Images/a.png", "image/png", byteArrayOf(1, 2, 3))
        val book = parse(epub(spine, listOf(css, image)))

        assertEquals(bodies.size, book.chapters.size)
        book.chapters.forEachIndexed { i, ch ->
            assertEquals("chapter ${i + 1}", Legacy.extractTextFromHtml(xhtml(bodies[i])), ch.content)
        }
    }

    // ── Inline styles ───────────────────────────────────────────────

    @Test
    fun `italic and bold spans land on the right words`() {
        val book = parse(epub(listOf(chapter("c1",
            "<h1>Title</h1><p>She said <i>never</i> and meant <b>it</b>.</p><p>Then <strong><em>both</em></strong> at once in a line.</p>"
        ))))
        val ch = book.chapters.single()
        val f = ch.formatting!!
        val italics = f.spans.filter { it.style == InlineStyle.ITALIC }.map { ch.textOf(it) }
        val bold = f.spans.filter { it.style == InlineStyle.BOLD }.map { ch.textOf(it) }
        assertEquals(listOf("never", "both"), italics)
        assertEquals(listOf("it", "both"), bold)
        assertEquals(1, f.spans.first { ch.textOf(it) == "both" }.paragraph)
    }

    @Test
    fun `css classes and style attributes count as formatting`() {
        val book = parse(epub(listOf(chapter("c1",
            "<p>A <span class=\"calibre3\">classy</span> word, a <span class=\"bold1 under\">heavy</span> one, " +
                "and <span style=\"font-style: italic\">inline</span>.</p><p class=\"centered\">* * * centred break line</p>"
        )), listOf(css)))
        val ch = book.chapters.single()
        val f = ch.formatting!!
        assertEquals(listOf("classy", "inline"), f.spans.filter { it.style == InlineStyle.ITALIC }.map { ch.textOf(it) })
        assertEquals(listOf("heavy"), f.spans.filter { it.style == InlineStyle.BOLD }.map { ch.textOf(it) })
        assertEquals(listOf("heavy"), f.spans.filter { it.style == InlineStyle.UNDERLINE }.map { ch.textOf(it) })
        assertEquals(listOf(BlockMark(1, BlockStyle.CENTER)), f.blocks)
    }

    @Test
    fun `whitespace collapsing around tags does not shift offsets`() {
        val book = parse(epub(listOf(chapter("c1",
            "<p>start <i>  spaced out  </i>   finish of a fairly long line.</p><p><i>Whole</i>\n\n<i>para</i> two.</p>"
        ))))
        val ch = book.chapters.single()
        val italics = ch.formatting!!.spans.map { ch.textOf(it).trim() }
        assertEquals(listOf("spaced out", "Whole", "para"), italics)
    }

    @Test
    fun `a style that spans paragraphs is split per paragraph`() {
        val book = parse(epub(listOf(chapter("c1",
            "<div class=\"calibre3\"><p>First italic paragraph here.</p><p>Second italic one.</p></div><p>Plain at the end.</p>"
        )), listOf(css)))
        val ch = book.chapters.single()
        val spans = ch.formatting!!.spans
        assertEquals(listOf(0, 1), spans.map { it.paragraph })
        assertEquals(listOf("First italic paragraph here.", "Second italic one."), spans.map { ch.textOf(it) })
    }

    @Test
    fun `headings and quotes inside a chapter are marked`() {
        val book = parse(epub(listOf(chapter("c1",
            "<h1>Chapter title</h1><p>Opening paragraph of text.</p><h3>A section</h3><blockquote><p>Quoted line.</p></blockquote>"
        ))))
        val ch = book.chapters.single()
        assertEquals("Chapter title", ch.title)
        assertEquals(listOf("Opening paragraph of text.", "A section", "Quoted line."), ch.paragraphs())
        assertEquals(listOf(BlockMark(1, BlockStyle.HEADING), BlockMark(2, BlockStyle.QUOTE)), ch.formatting!!.blocks)
    }

    // ── Images ──────────────────────────────────────────────────────

    @Test
    fun `images are placed between paragraphs and their bytes kept`() {
        val image = TestEpub.Item("img", "Images/map.png", "image/png", byteArrayOf(9, 8, 7))
        val book = parse(epub(listOf(chapter("c1",
            "<p><img src=\"../Images/map.png\" alt=\"The map\"/></p><p>Text after the map, long enough.</p>" +
                "<p>More text <img src=\"../Images/map.png\"/> mid-paragraph.</p>"
        )), listOf(image)))
        val images = book.chapters.single().formatting!!.images
        assertEquals(listOf(0, 2), images.map { it.beforeParagraph })
        assertEquals("OEBPS/Images/map.png", images[0].path)
        assertEquals("The map", images[0].alt)
        assertTrue(book.images.getValue("OEBPS/Images/map.png").contentEquals(byteArrayOf(9, 8, 7)))
    }

    @Test
    fun `illustration pages become images at the top of the next chapter, the cover is left out`() {
        val cover = TestEpub.Item("cover", "Images/cover.jpg", "image/jpeg", byteArrayOf(1))
        val art = TestEpub.Item("art", "Images/art.jpg", "image/jpeg", byteArrayOf(2))
        val coverPage = chapter("coverpage", "<div><img src=\"../Images/cover.jpg\"/></div>")
        val artPage = chapter("artpage", "<div><svg><image xlink:href=\"../Images/art.jpg\"/></svg></div>")
        val ch1 = chapter("c1", "<h1>One</h1><p>The first chapter starts here and goes on.</p>")
        val book = parse(epub(listOf(coverPage, artPage, ch1), listOf(cover, art), coverId = "cover"))

        assertEquals(1, book.chapters.size)
        val images = book.chapters.single().formatting!!.images
        assertEquals(listOf(ChapterImage(0, "OEBPS/Images/art.jpg", null)), images)
        assertEquals(setOf("OEBPS/Images/art.jpg"), book.images.keys)
    }

    // ── Footnotes ───────────────────────────────────────────────────

    @Test
    fun `epub3 noteref picks up the aside's text`() {
        val book = parse(epub(listOf(chapter("c1",
            "<p>The sect master<a epub:type=\"noteref\" href=\"#n1\">1</a> arrived at noon today.</p>" +
                "<aside epub:type=\"footnote\" id=\"n1\"><p>1. Head of the whole sect.</p></aside>"
        ))))
        val ch = book.chapters.single()
        val note = ch.formatting!!.notes.single()
        assertEquals("1", ch.paragraphs()[note.paragraph].substring(note.start, note.end))
        assertEquals("Head of the whole sect.", note.text)
    }

    @Test
    fun `gutenberg footnotes in another file resolve, back links do not`() {
        val notes = chapter("notes",
            "<div class=\"footnote\"><p><a id=\"Footnote_1\" href=\"c1.xhtml#Anchor_1\">[1]</a> A bushel is eight gallons, or about thirty-five litres.</p></div>")
        val ch1 = chapter("c1",
            "<p>He sold a bushel<a id=\"Anchor_1\" href=\"notes.xhtml#Footnote_1\" class=\"fnanchor\">[1]</a> of wheat at the market in town that morning.</p>")
        val book = parse(epub(listOf(ch1, notes)))

        val first = book.chapters[0].formatting!!.notes.single()
        assertEquals("A bushel is eight gallons, or about thirty-five litres.", first.text)
        assertEquals("[1]", book.chapters[0].paragraphs()[0].substring(first.start, first.end))
        // The note's own "[1]" points back, so it isn't a note.
        assertNull(book.chapters[1].formatting?.notes?.takeIf { it.isNotEmpty() })
    }

    @Test
    fun `ordinary links are not footnotes`() {
        val book = parse(epub(listOf(
            chapter("c1", "<p>See <a href=\"c2.xhtml\">the next chapter</a> for more of the story.</p>"),
            chapter("c2", "<p>The next chapter has its own text in it.</p>")
        )))
        assertNull(book.chapters[0].formatting)
    }

    // ── Table of contents ───────────────────────────────────────────

    private val navDoc = TestEpub.Item(
        "nav", "nav.xhtml", "application/xhtml+xml",
        xhtml(
            """<nav epub:type="toc"><ol>
              <li><a href="Text/part1.xhtml">Part One</a><ol>
                <li><a href="Text/c1.xhtml">Chapter 1: Dawn</a></li>
                <li><a href="Text/c1.xhtml#later">A Later Scene</a></li>
                <li><a href="Text/c2.xhtml">Chapter 2: Dusk</a></li>
              </ol></li>
              <li><a href="Text/cover.xhtml">Cover</a></li>
            </ol></nav>"""
        ).toByteArray(),
        properties = "nav"
    )

    @Test
    fun `nav document gives titles, depths and paragraph targets`() {
        val book = parse(epub(listOf(
            chapter("part1", "<h1>Part One</h1>"),
            chapter("c1", "<h1>1</h1><p>First paragraph of the dawn.</p><p>Second.</p><h2 id=\"later\">Later</h2><p>Later on.</p>"),
            chapter("c2", "<h1>2</h1><p>Dusk falls on the city slowly, street by street.</p>")
        ), listOf(navDoc)))

        assertEquals(listOf("Chapter 1: Dawn", "Chapter 2: Dusk"), book.chapters.map { it.displayTitle })
        // The heading title stays what it always was, for restoring old archives.
        assertEquals(listOf("1", "2"), book.chapters.map { it.title })
        assertEquals(
            listOf(
                TocEntry("Part One", 0, 1, 0),
                TocEntry("Chapter 1: Dawn", 1, 1, 0),
                TocEntry("A Later Scene", 1, 1, 2),
                TocEntry("Chapter 2: Dusk", 1, 2, 0)
            ),
            book.toc
        )
    }

    @Test
    fun `ncx is used when there is no nav document`() {
        val ncx = TestEpub.Item(
            "ncx", "toc.ncx", "application/x-dtbncx+xml",
            """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
              <navPoint id="a"><navLabel><text>The Beginning</text></navLabel><content src="Text/c1.xhtml"/>
                <navPoint id="b"><navLabel><text>Inner</text></navLabel><content src="Text/c2.xhtml"/></navPoint>
              </navPoint></navMap></ncx>""".toByteArray()
        )
        val book = parse(epub(listOf(
            chapter("c1", "<p>Text of the beginning chapter goes right here.</p>"),
            chapter("c2", "<p>Text of the inner chapter goes right here, too.</p>")
        ), listOf(ncx), ncxId = "ncx"))
        assertEquals(listOf("The Beginning", "Inner"), book.chapters.map { it.displayTitle })
        assertEquals(listOf(0, 1), book.toc.map { it.depth })
    }

    @Test
    fun `contents pointing at heading-only files lead to the chapter after them`() {
        val ncx = TestEpub.Item(
            "ncx", "toc.ncx", "application/x-dtbncx+xml",
            """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
              <navPoint id="t"><navLabel><text>Title Page</text></navLabel><content src="Text/title.xhtml"/></navPoint>
              <navPoint id="i"><navLabel><text>Contents</text></navLabel><content src="Text/intro.xhtml"/></navPoint>
              <navPoint id="a"><navLabel><text>1: The First</text></navLabel><content src="Text/h1.xhtml"/></navPoint>
              <navPoint id="b"><navLabel><text>2: The Second</text></navLabel><content src="Text/h2.xhtml"/></navPoint>
              </navMap></ncx>""".toByteArray()
        )
        val book = parse(epub(listOf(
            chapter("title", "<blockquote>Book</blockquote>"),
            chapter("intro", "<p>The contents page, long enough to be kept as a chapter.</p>"),
            chapter("h1", "<blockquote><span>CHAPTER 1</span></blockquote>"),
            chapter("c1", "<p>Text of the first chapter goes right here, at length.</p>"),
            chapter("h2", "<blockquote><span>CHAPTER 2</span></blockquote>"),
            chapter("c2", "<p>Text of the second chapter goes right here, at length.</p>")
        ), listOf(ncx), ncxId = "ncx"))
        assertEquals(listOf("Contents", "1: The First", "2: The Second"), book.chapters.map { it.displayTitle })
        assertEquals(listOf("Contents" to 1, "1: The First" to 2, "2: The Second" to 3), book.toc.map { it.title to it.chapter })
    }

    @Test
    fun `a book without a contents list keeps heading titles`() {
        val book = parse(epub(listOf(chapter("c1", "<h1>From The Heading</h1><p>Body text that is comfortably long enough to keep.</p>"))))
        assertEquals("From The Heading", book.chapters.single().displayTitle)
        assertTrue(book.toc.isEmpty())
    }

    @Test
    fun `plain chapters carry no formatting`() {
        val book = parse(epub(listOf(chapter("c1", "<p>Nothing special in this paragraph at all.</p>"))))
        assertNull(book.chapters.single().formatting)
    }

    // ── Pieces ──────────────────────────────────────────────────────

    @Test
    fun `paragraph ranges agree with ParagraphSplitter`() {
        val text = "  one  \n\n two\n\n\n  \nthree four\n"
        val ranges = EpubMarkup.paragraphRanges(text)
        assertEquals(ParagraphSplitter.split(text), ranges.map { text.substring(it) })
    }

    @Test
    fun `note labels`() {
        listOf("1", "[12]", "(iv)", "*", "†", "a").forEach { assertTrue(it, EpubMarkup.looksLikeNoteLabel(it)) }
        listOf("", "next chapter", "1234", "Chapter 1").forEach { assertTrue(it, !EpubMarkup.looksLikeNoteLabel(it)) }
    }

    /** A frozen copy of the extractor as it was before formatting existed. */
    private object Legacy {
        private val HEAD_BLOCK = Regex("<head[^>]*>.*?</head>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val HEADING_BLOCK = Regex("<(h[1-6])[^>]*>(.*?)</\\1\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val TITLE_TAG = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        private fun extractChapterTitle(html: String): String? {
            val heading = HEADING_BLOCK.find(html)?.groupValues?.get(2)
            val fromTitleTag = TITLE_TAG.find(html)?.groupValues?.get(1)
            return sequenceOf(heading, fromTitleTag).filterNotNull().map { strip(it) }
                .firstOrNull { it.isNotBlank() && it.length <= 120 }
        }

        private fun strip(fragment: String): String =
            fragment.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace(Regex("\\s+"), " ").trim()

        fun extractTextFromHtml(html: String): String {
            val withoutHead = html.replace(HEAD_BLOCK, "")
            val body = if (extractChapterTitle(html) != null) HEADING_BLOCK.replaceFirst(withoutHead, "") else withoutHead
            return body
                .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("</(p|div|br|h[1-6]|li|tr)\\s*>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace(Regex("&#(\\d+);")) { m ->
                    m.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }
                        ?.let { cp -> StringBuilder().appendCodePoint(cp).toString() } ?: ""
                }
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n\\s*\\n+"), "\n\n")
                .trim()
        }
    }
}
