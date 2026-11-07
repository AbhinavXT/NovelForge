package com.abhinavxt.novelforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StarDictTest {

    @get:Rule val temp = TemporaryFolder()

    /** The files of a dictionary with [entries], sorted the way StarDict sorts them. */
    private fun build(
        entries: Map<String, ByteArray>,
        sequence: String? = "m",
        offsetBits: Int = 32,
        synonyms: Map<String, String> = emptyMap(),
        name: String = "Test Dict"
    ): Map<String, ByteArray> {
        val order = compareBy<String> { it.lowercase() }.thenBy { it }
        val words = entries.keys.sortedWith(order)
        val dict = ByteArrayOutputStream()
        val idx = ByteArrayOutputStream()
        words.forEach { word ->
            val data = entries.getValue(word)
            idx.write(word.toByteArray() + 0)
            val trailer = ByteBuffer.allocate(offsetBits / 8 + 4)
            if (offsetBits == 64) trailer.putLong(dict.size().toLong()) else trailer.putInt(dict.size())
            idx.write(trailer.putInt(data.size).array())
            dict.write(data)
        }
        val ifo = buildString {
            appendLine("StarDict's dict ifo file")
            appendLine("version=3.0.0")
            appendLine("bookname=$name")
            appendLine("wordcount=${words.size}")
            appendLine("idxfilesize=${idx.size()}")
            if (offsetBits == 64) appendLine("idxoffsetbits=64")
            sequence?.let { appendLine("sametypesequence=$it") }
        }
        val files = mutableMapOf(
            "test.ifo" to ifo.toByteArray(),
            "test.idx" to idx.toByteArray(),
            "test.dict" to dict.toByteArray()
        )
        if (synonyms.isNotEmpty()) {
            val syn = ByteArrayOutputStream()
            synonyms.keys.sortedWith(order).forEach { word ->
                syn.write(word.toByteArray() + 0)
                syn.write(ByteBuffer.allocate(4).putInt(words.indexOf(synonyms.getValue(word))).array())
            }
            files["test.syn"] = syn.toByteArray()
        }
        return files
    }

    private fun install(files: Map<String, ByteArray>): StarDict {
        val dir = temp.newFolder()
        files.forEach { (name, bytes) -> StarDict.unpack(name, ByteArrayInputStream(bytes), dir) }
        return StarDict.finish(dir)
    }

    private fun plain(vararg entries: Pair<String, String>) =
        entries.associate { it.first to it.second.toByteArray() }

    private fun gzip(bytes: ByteArray) =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    /** gzip with the FEXTRA header field that dictzip adds. */
    private fun dictzip(bytes: ByteArray): ByteArray {
        val plain = gzip(bytes)
        plain[3] = 4
        val extra = byteArrayOf(6, 0, 'R'.code.toByte(), 'A'.code.toByte(), 2, 0, 1, 0)
        return plain.copyOfRange(0, 10) + extra + plain.copyOfRange(10, plain.size)
    }

    private fun zip(files: Map<String, ByteArray>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun tar(files: Map<String, ByteArray>) = ByteArrayOutputStream().also { out ->
        files.forEach { (name, bytes) ->
            val header = ByteArray(512)
            name.toByteArray().copyInto(header)
            "%011o".format(bytes.size).toByteArray().copyInto(header, 124)
            header[156] = '0'.code.toByte()
            out.write(header)
            out.write(bytes)
            out.write(ByteArray((512 - bytes.size % 512) % 512))
        }
        out.write(ByteArray(1024))
    }.toByteArray()

    private val sample = build(plain("apple" to "a fruit", "Zebra" to "striped", "été" to "summer"))

    // ── Lookup ───────────────────────────────────────────────────────

    @Test
    fun `finds words at any position and any case`() {
        val dictionary = install(sample)
        assertEquals("Test Dict", dictionary.info.name)
        assertEquals(3, dictionary.info.wordCount)
        assertEquals("a fruit", dictionary.lookup("apple").single().text)
        assertEquals("a fruit", dictionary.lookup("APPLE").single().text)
        assertEquals("Zebra", dictionary.lookup("zebra").single().word)
        assertEquals("summer", dictionary.lookup("ÉTÉ").single().text)
        assertTrue(dictionary.lookup("appl").isEmpty())
        assertTrue(dictionary.lookup("apples").isEmpty())
    }

    @Test
    fun `every word of a larger dictionary is reachable`() {
        val words = (0 until 3000).associate { "word$it" to "meaning $it".toByteArray() }
        val dictionary = install(build(words))
        words.keys.forEach { assertEquals(it, dictionary.lookup(it).single().word) }
    }

    @Test
    fun `words differing only in case both come back once`() {
        val dictionary = install(build(plain("March" to "month", "march" to "walk")))
        assertEquals(setOf("month", "walk"), dictionary.lookup("march").map { it.text }.toSet())
        assertEquals(2, dictionary.lookup("MARCH").size)
    }

    @Test
    fun `64-bit offsets and synonyms resolve to the entry`() {
        val dictionary = install(
            build(plain("go" to "move", "run" to "sprint"), offsetBits = 64, synonyms = mapOf("ran" to "run"))
        )
        assertEquals("sprint", dictionary.lookup("run").single().text)
        val viaSynonym = dictionary.lookup("Ran").single()
        assertEquals("run", viaSynonym.word)
        assertEquals("sprint", viaSynonym.text)
    }

    @Test
    fun `selections are cleaned without eating combining vowel signs`() {
        assertEquals("word", StarDict.cleanWord("  “word,” "))
        assertEquals("don't", StarDict.cleanWord("don’t"))
        assertEquals("well-known", StarDict.cleanWord("(well-known)"))
        assertEquals("किताबों", StarDict.cleanWord("किताबों।"))
        assertEquals("", StarDict.cleanWord("…"))
    }

    // ── Entry formats ────────────────────────────────────────────────

    @Test
    fun `a type sequence splits fields and the last runs to the end`() {
        val data = "həˈləʊ\u0000a greeting".toByteArray()
        val entry = StarDict.parseEntry("hello", data, "tm")!!
        assertEquals("həˈləʊ", entry.phonetic)
        assertEquals("a greeting", entry.text)
    }

    @Test
    fun `without a sequence each field carries its type, and binary is skipped`() {
        val image = byteArrayOf('P'.code.toByte()) + ByteBuffer.allocate(4).putInt(3).array() + byteArrayOf(1, 0, 2)
        val data = image + "m".toByteArray() + "plain <kept>".toByteArray() + 0 + "h<b>bold</b>".toByteArray() + 0
        assertEquals("plain <kept>\nbold", StarDict.parseEntry("x", data, null)!!.text)
    }

    @Test
    fun `a binary size past the end stops instead of crashing`() {
        val data = "mtext".toByteArray() + 0 + 'P'.code.toByte() + ByteBuffer.allocate(4).putInt(999).array()
        assertEquals("text", StarDict.parseEntry("x", data, null)!!.text)
    }

    @Test
    fun `an entry with no text is not a hit`() {
        assertNull(StarDict.parseEntry("x", byteArrayOf(1, 2), "W"))
        assertNull(StarDict.parseEntry("x", "/ipa/".toByteArray(), "t"))
    }

    @Test
    fun `markup becomes readable text`() {
        assertEquals(
            "fish & chips\nsecond line",
            StarDict.stripMarkup("<style>b{color:red}</style><b>fish &amp; chips</b><br/>second <i>line</i>")
        )
        assertEquals("one\ntwo", StarDict.stripMarkup("<p>one</p>\n<p></p>\n<p>two</p>"))
    }

    @Test
    fun `source indentation in HTML goes, but newlines in other markup stay`() {
        val html = "<div><ol><li><div>house</div></li></ol><div>\n      <a href=\"#x\">H&#xE4;user</a>\n    </div></div>"
        assertEquals("house\nHäuser", StarDict.parseEntry("Haus", html.toByteArray(), "h")!!.text)
        assertEquals("one\ntwo", StarDict.parseEntry("x", "<b>one</b>\ntwo".toByteArray(), "g")!!.text)
        assertEquals("one two", StarDict.parseEntry("x", "<b>one</b>\ntwo".toByteArray(), "h")!!.text)
    }

    // ── Import ───────────────────────────────────────────────────────

    private fun assertImports(fileName: String, bytes: ByteArray) {
        val dir = temp.newFolder()
        StarDict.unpack(fileName, ByteArrayInputStream(bytes), dir)
        assertEquals("a fruit", StarDict.finish(dir).lookup("apple").single().text)
        assertEquals(setOf("dict.ifo", "dict.idx", "dict.dict"), dir.list()!!.toSet())
    }

    private val compressed = mapOf(
        "folder/test.ifo" to sample.getValue("test.ifo"),
        "folder/test.idx.gz" to gzip(sample.getValue("test.idx")),
        "folder/test.dict.dz" to dictzip(sample.getValue("test.dict")),
        "folder/README" to "not a dictionary file, and an odd length".toByteArray()
    )

    @Test
    fun `imports a zip with compressed members in a folder`() = assertImports("Test.ZIP", zip(compressed))

    @Test
    fun `imports a tar gz, stopping each member at its size`() {
        assertImports("test.tar.gz", gzip(tar(compressed)))
        assertImports("test.tgz", gzip(tar(compressed)))
        assertImports("test.tar", tar(compressed))
    }

    @Test
    fun `archive paths cannot escape the import folder`() {
        val parent = temp.newFolder()
        val dir = File(parent, "staging").apply { mkdirs() }
        val hostile = sample.mapKeys { "../../${it.key}" } + ("../evil.txt" to byteArrayOf(1))
        StarDict.unpack("test.zip", ByteArrayInputStream(zip(hostile)), dir)
        assertEquals(listOf("staging"), parent.list()!!.toList())
        assertEquals("a fruit", StarDict.finish(dir).lookup("apple").single().text)
    }

    @Test
    fun `macOS resource forks in an archive are ignored`() {
        val withForks = sample + ("__MACOSX/._test.ifo" to byteArrayOf(0, 5, 22))
        assertImports("test.zip", zip(withForks))
    }

    private fun message(block: () -> Unit) = assertThrows(StarDictException::class.java, block).message!!

    @Test
    fun `missing files say what to pick`() {
        val dir = temp.newFolder()
        StarDict.unpack("test.ifo", ByteArrayInputStream(sample.getValue("test.ifo")), dir)
        assertTrue(message { StarDict.finish(dir) }.contains(".idx"))
    }

    @Test
    fun `archives we can't open say to extract them first`() {
        val failure = message { StarDict.unpack("d.tar.bz2", ByteArrayInputStream(ByteArray(0)), temp.newFolder()) }
        assertTrue(failure.startsWith("Extract"))
    }

    @Test
    fun `two dictionaries in one archive are refused`() {
        val two = sample + ("other/test.ifo" to sample.getValue("test.ifo"))
        val failure = message { StarDict.unpack("d.zip", ByteArrayInputStream(zip(two)), temp.newFolder()) }
        assertTrue(failure.contains("one dictionary"))
    }

    @Test
    fun `damaged dictionaries are refused`() {
        val truncatedIdx = sample + ("test.idx" to sample.getValue("test.idx").dropLast(3).toByteArray())
        assertThrows(StarDictException::class.java) { install(truncatedIdx) }
        val notIfo = sample + ("test.ifo" to "StarDict's storage ifo file\nbookname=x".toByteArray())
        assertThrows(StarDictException::class.java) { install(notIfo) }
        val noName = sample + ("test.ifo" to "StarDict's dict ifo file\nwordcount=1\nidxfilesize=1".toByteArray())
        assertThrows(StarDictException::class.java) { install(noName) }
    }

    @Test
    fun `an entry pointing outside the data file fails its lookup, not the app`() {
        val dictionary = install(sample + ("test.dict" to byteArrayOf(1)))
        assertThrows(StarDictException::class.java) { dictionary.lookup("zebra") }
    }

    @Test
    fun `info tolerates a byte order mark and defaults to 32-bit offsets`() {
        val info = StarDict.parseInfo("﻿StarDict's dict ifo file\nbookname=B = C\nwordcount=2\nidxfilesize=9\n")
        assertEquals("B = C", info.name)
        assertEquals(32, info.offsetBits)
        assertNull(info.sameTypeSequence)
        assertFalse(info.wordCount == 0L)
    }
}
