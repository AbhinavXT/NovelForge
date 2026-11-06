package com.abhinavxt.novelforge.data

import org.jsoup.parser.Parser
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** A dictionary that can't be imported; the message is shown to the user. */
class StarDictException(message: String) : Exception(message)

/**
 * One StarDict dictionary, unpacked into [dir] by [unpack] and [finish].
 * The word index is memory-mapped on first lookup, so a large dictionary
 * costs one int per word on the heap.
 */
class StarDict(val dir: File) {

    data class Info(
        val name: String,
        val wordCount: Long,
        val idxFileSize: Long,
        val offsetBits: Int,
        val sameTypeSequence: String?
    )

    data class Entry(val word: String, val phonetic: String?, val text: String)

    val info: Info = parseInfo(File(dir, IFO).readText())

    private val offsetBytes = info.offsetBits / 8
    private val words by lazy { WordIndex(File(dir, IDX), offsetBytes + 4) }
    private val synonyms by lazy { File(dir, SYN).takeIf { it.exists() }?.let { WordIndex(it, 4) } }

    /**
     * Entries for [word]. The file sorts ignoring ASCII case only, so the
     * lowercase and capitalised forms cover "ÉTÉ" and sentence-start words.
     */
    fun lookup(word: String): List<Entry> {
        val lower = word.lowercase()
        val hits = LinkedHashSet<Int>()
        listOf(word, lower, lower.replaceFirstChar { it.uppercase() }).distinct().forEach { form ->
            val key = form.toByteArray()
            words.find(key).forEach { hits += it }
            synonyms?.let { syn -> syn.find(key).forEach { hits += syn.trailer(it).getInt(0) } }
        }
        return hits.filter { it in 0 until words.size }.take(MAX_ENTRIES).mapNotNull(::read)
    }

    private fun read(index: Int): Entry? {
        val trailer = words.trailer(index)
        val offset = if (offsetBytes == 8) trailer.getLong(0) else trailer.getInt(0).toLong() and 0xFFFFFFFFL
        val size = trailer.getInt(offsetBytes)
        val data = RandomAccessFile(File(dir, DICT), "r").use { file ->
            if (offset < 0 || size !in 0..MAX_ENTRY_BYTES || offset + size > file.length()) {
                throw StarDictException("Entry outside the dictionary file")
            }
            ByteArray(size).also { file.seek(offset); file.readFully(it) }
        }
        return parseEntry(words.word(index), data, info.sameTypeSequence)
    }

    /** A sorted .idx or .syn file: word, NUL, then [trailerSize] bytes. */
    private class WordIndex(file: File, private val trailerSize: Int) {
        private val buffer: ByteBuffer = RandomAccessFile(file, "r").use {
            it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
        }
        private val starts: IntArray // one extra at the end, so entry i ends at starts[i + 1]
        val size: Int

        init {
            var offsets = IntArray(1024)
            var count = 0
            var position = 0
            val end = buffer.limit()
            while (position < end) {
                if (count + 2 > offsets.size) offsets = offsets.copyOf(offsets.size * 2)
                offsets[count++] = position
                while (position < end && buffer.get(position) != 0.toByte()) position++
                position += 1 + trailerSize
                if (position > end) throw StarDictException(DAMAGED)
            }
            offsets[count] = position
            starts = offsets
            size = count
        }

        private fun wordEnd(index: Int) = starts[index + 1] - trailerSize - 1

        fun word(index: Int): String {
            val bytes = ByteArray(wordEnd(index) - starts[index])
            for (i in bytes.indices) bytes[i] = buffer.get(starts[index] + i)
            return String(bytes)
        }

        fun trailer(index: Int): ByteBuffer =
            buffer.duplicate().apply { position(wordEnd(index) + 1) }.slice()

        /** Every entry equal to [key] ignoring ASCII case, which the file keeps adjacent. */
        fun find(key: ByteArray): IntRange {
            var low = 0
            var high = size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (compare(middle, key) < 0) low = middle + 1 else high = middle
            }
            var last = low
            while (last < size && compare(last, key) == 0) last++
            return low until last
        }

        private fun compare(index: Int, key: ByteArray): Int {
            val end = wordEnd(index)
            var a = starts[index]
            var b = 0
            while (a < end && b < key.size) {
                val difference = fold(buffer.get(a)) - fold(key[b])
                if (difference != 0) return difference
                a++
                b++
            }
            return (end - a) - (key.size - b)
        }

        private fun fold(byte: Byte): Int {
            val value = byte.toInt() and 0xFF
            return if (value in 'A'.code..'Z'.code) value + 32 else value
        }
    }

    companion object {
        private const val IFO = "dict.ifo"
        private const val IDX = "dict.idx"
        private const val DICT = "dict.dict"
        private const val SYN = "dict.syn"

        private const val MAX_ENTRIES = 5
        private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
        private const val MAX_FILE_BYTES = Int.MAX_VALUE.toLong() // what one mapping can hold
        private const val UNBOUNDED = Long.MAX_VALUE

        private const val DAMAGED = "This dictionary looks damaged."
        private const val PICK_ALL =
            "Pick the .ifo, .idx and .dict files together, or one .zip or .tar.gz holding them."

        // Compressed members are staged as .gz and expanded by finish().
        private val MEMBERS = listOf(
            ".ifo" to IFO,
            ".idx" to IDX, ".idx.gz" to "$IDX.gz",
            ".dict" to DICT, ".dict.dz" to "$DICT.gz",
            ".syn" to SYN, ".syn.dz" to "$SYN.gz"
        )
        private val UNSUPPORTED_ARCHIVES = listOf(".tar.bz2", ".tbz2", ".tar.xz", ".txz", ".7z", ".rar")

        private val SCRIPTS = Regex("(?is)<(style|script)\\b.*?</\\1\\s*>")
        private val BREAKS = Regex("(?i)<br\\s*/?>|</(p|div|li|tr|h[1-6])\\s*>")
        private val TAGS = Regex("<[^>]*>")
        private val WHITESPACE = Regex("\\s+")
        private val LINE_EDGE_SPACE = Regex("[ \\t]*\\n[ \\t]*")
        private val BLANK_LINES = Regex("\\n{2,}")

        /** A selection as a headword: no surrounding punctuation, straight apostrophes. */
        fun cleanWord(selection: String): String =
            selection.trim { it.isWhitespace() || it.category.code[0] == 'P' }.replace('’', '\'')

        /**
         * Stage one picked file in [dir]: an archive, or a loose dictionary
         * file. Only known names are kept, written under fixed names, so an
         * archive's paths never reach the file system.
         */
        fun unpack(fileName: String, input: InputStream, dir: File) {
            val name = fileName.lowercase()
            when {
                UNSUPPORTED_ARCHIVES.any(name::endsWith) -> throw StarDictException(
                    "Extract this archive first, then pick its .ifo, .idx and .dict files together."
                )
                name.endsWith(".zip") -> {
                    val zip = ZipInputStream(input)
                    generateSequence { zip.nextEntry }
                        .filterNot { it.isDirectory }
                        .forEach { store(it.name, zip, dir) }
                }
                name.endsWith(".tar.gz") || name.endsWith(".tgz") -> unpackTar(GZIPInputStream(input), dir)
                name.endsWith(".tar") -> unpackTar(input, dir)
                else -> store(fileName, input, dir)
            }
        }

        /** Expand what [unpack] staged in [dir] and check it opens. */
        fun finish(dir: File): StarDict {
            listOf(IDX, DICT, SYN).forEach { target ->
                val compressed = File(dir, "$target.gz")
                if (!compressed.exists()) return@forEach
                // dictzip (.dz) is gzip with an extra header field.
                if (!File(dir, target).exists()) {
                    GZIPInputStream(compressed.inputStream().buffered()).use { gzip ->
                        File(dir, target).outputStream().use { copy(gzip, it) }
                    }
                }
                compressed.delete()
            }
            if (listOf(IFO, IDX, DICT).any { !File(dir, it).exists() }) throw StarDictException(PICK_ALL)

            val dictionary = StarDict(dir)
            if (File(dir, IDX).length() != dictionary.info.idxFileSize) throw StarDictException(DAMAGED)
            if (dictionary.words.size == 0) throw StarDictException(DAMAGED)
            dictionary.synonyms
            return dictionary
        }

        // shortcut: no GNU/pax long names, so a member path over 100 bytes is skipped; fine for dictionaries.
        private fun unpackTar(input: InputStream, dir: File) {
            val header = ByteArray(512)
            while (input.fill(header) == header.size && header[0] != 0.toByte()) {
                val name = String(header, 0, 100, Charsets.ISO_8859_1).substringBefore('\u0000')
                val size = String(header, 124, 12, Charsets.ISO_8859_1).trim { it <= ' ' }
                    .ifEmpty { "0" }.toLongOrNull(8) ?: throw StarDictException(DAMAGED)
                val type = header[156].toInt().toChar()
                if (type == '0' || type == '\u0000') store(name, input, dir, size) else copy(input, null, size)
                copy(input, null, (512 - size % 512) % 512)
            }
        }

        private fun store(path: String, input: InputStream, dir: File, size: Long = UNBOUNDED) {
            val name = path.substringAfterLast('/').substringAfterLast('\\').lowercase()
            val staged = MEMBERS.firstOrNull { name.endsWith(it.first) }?.second
            if (staged == null || name.startsWith("._")) {
                if (size != UNBOUNDED) copy(input, null, size)
                return
            }
            val file = File(dir, staged)
            if (file.exists()) throw StarDictException("Add one dictionary at a time.")
            file.outputStream().use { copy(input, it, size) }
        }

        private fun copy(from: InputStream, to: OutputStream?, size: Long = UNBOUNDED) {
            val buffer = ByteArray(64 * 1024)
            var left = size
            while (left > 0) {
                val read = from.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (read < 0) {
                    if (size != UNBOUNDED) throw EOFException()
                    return
                }
                to?.write(buffer, 0, read)
                left -= read
                if (to != null && size - left > MAX_FILE_BYTES) {
                    throw StarDictException("This dictionary is too large.")
                }
            }
        }

        private fun InputStream.fill(buffer: ByteArray): Int {
            var filled = 0
            while (filled < buffer.size) {
                val read = read(buffer, filled, buffer.size - filled)
                if (read < 0) break
                filled += read
            }
            return filled
        }

        internal fun parseInfo(text: String): Info {
            val lines = text.removePrefix("﻿").lines()
            if (lines.first().trim() != "StarDict's dict ifo file") throw StarDictException(DAMAGED)
            val fields = lines.drop(1).filter { '=' in it }.associate {
                it.substringBefore('=').trim() to it.substringAfter('=').trim()
            }
            val offsetBits = fields["idxoffsetbits"]?.toIntOrNull() ?: 32
            if (offsetBits != 32 && offsetBits != 64) throw StarDictException(DAMAGED)
            return Info(
                name = fields["bookname"]?.takeIf { it.isNotEmpty() } ?: throw StarDictException(DAMAGED),
                wordCount = fields["wordcount"]?.toLongOrNull() ?: throw StarDictException(DAMAGED),
                idxFileSize = fields["idxfilesize"]?.toLongOrNull() ?: throw StarDictException(DAMAGED),
                offsetBits = offsetBits,
                sameTypeSequence = fields["sametypesequence"]?.takeIf { it.isNotEmpty() }
            )
        }

        /**
         * Decode an entry. With [sequence] the field types are given once and
         * the last field runs to the end of [data]; without it each field
         * starts with its type. Lowercase types are NUL-terminated text,
         * uppercase ones are sized binary, which we skip.
         */
        internal fun parseEntry(word: String, data: ByteArray, sequence: String?): Entry? {
            var phonetic: String? = null
            val parts = mutableListOf<String>()
            var position = 0
            var field = 0
            while (position < data.size && (sequence == null || field < sequence.length)) {
                val type = if (sequence != null) sequence[field] else (data[position++].toInt() and 0xFF).toChar()
                field++
                val last = sequence != null && field == sequence.length
                val binary = type.isUpperCase()
                if (binary && !last) {
                    if (data.size - position < 4) break
                    val size = ByteBuffer.wrap(data).getInt(position)
                    position += 4
                    if (size < 0 || size > data.size - position) break
                    position += size
                    continue
                }
                var end = data.size
                if (!last) {
                    end = position
                    while (end < data.size && data[end] != 0.toByte()) end++
                }
                if (!binary) {
                    val text = String(data, position, end - position)
                    when (type) {
                        't', 'y' -> phonetic = phonetic ?: text.trim().takeIf { it.isNotEmpty() }
                        'm', 'l', 'w' -> parts += text.trim()
                        'h' -> parts += stripMarkup(text.replace(WHITESPACE, " "))
                        'g', 'x', 'k' -> parts += stripMarkup(text)
                    }
                }
                position = end + 1
            }
            val text = parts.filter { it.isNotEmpty() }.joinToString("\n")
            return if (text.isEmpty()) null else Entry(word, phonetic, text)
        }

        /** Newlines count in Pango and XDXF; for HTML, collapse the source's whitespace first. */
        internal fun stripMarkup(markup: String): String {
            val text = markup.replace(SCRIPTS, "").replace(BREAKS, "\n").replace(TAGS, "")
            return Parser.unescapeEntities(text, false)
                .replace(LINE_EDGE_SPACE, "\n")
                .replace(BLANK_LINES, "\n")
                .trim()
        }
    }
}
