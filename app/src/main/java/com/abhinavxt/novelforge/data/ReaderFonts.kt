package com.abhinavxt.novelforge.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import com.abhinavxt.novelforge.data.epub.DocumentFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A font that can't be added. The message is shown as is. */
class ReaderFontException(message: String) : Exception(message)

object ReaderFonts {
    private val SIGNATURES = listOf(
        byteArrayOf(0, 1, 0, 0),   // TrueType
        "OTTO".toByteArray(),      // OpenType with CFF outlines
        "true".toByteArray(),      // Apple TrueType
        "ttcf".toByteArray(),      // TrueType collection
    )

    /** Whether [header], a file's first 4 bytes, starts a font Android can load. */
    fun isFontHeader(header: ByteArray): Boolean = SIGNATURES.any { it.contentEquals(header) }

    /** A safe file name for a provider's display name, which may hold anything. */
    fun fileName(displayName: String): String {
        val base = displayName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
            .replace(Regex("[^\\p{L}\\p{N} _-]"), "_").trim().take(100).ifEmpty { "Font" }
        return "$base.font"
    }
}

/**
 * The imported reader fonts, in filesDir/fonts. That folder is left out of
 * Android's cloud backup: one CJK font can use up its 25 MB quota.
 */
class ReaderFontManager(private val context: Context) {

    private val root = File(context.filesDir, "fonts")

    private val _fonts = MutableStateFlow<List<File>>(emptyList())
    val fonts: StateFlow<List<File>> = _fonts.asStateFlow()

    suspend fun loadCache() = withContext(Dispatchers.IO) {
        val files = root.listFiles().orEmpty()
        files.filter { it.name.startsWith(IMPORT_PREFIX) }.forEach { it.delete() }
        _fonts.value = files.filterNot { it.name.startsWith(IMPORT_PREFIX) }.sortedBy { it.name.lowercase() }
    }

    /**
     * Copy the picked font in, checking that it really loads: drawing a
     * broken font would crash the reader.
     * @return the saved font
     * @throws ReaderFontException with a message to show
     */
    suspend fun import(uri: Uri): File = withContext(Dispatchers.IO) {
        root.mkdirs()
        val font = File(root, ReaderFonts.fileName(DocumentFormat.displayName(context, uri)))
        if (font.exists()) throw ReaderFontException("\"${font.nameWithoutExtension}\" is already added.")
        val staging = File(root, IMPORT_PREFIX + UUID.randomUUID())
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw ReaderFontException("Couldn't open that file.")
            input.buffered().use { stream ->
                val header = ByteArray(4)
                // Checked first so picking a huge non-font file doesn't copy it.
                if (stream.read(header) != 4 || !ReaderFonts.isFontHeader(header)) throw notAFont()
                staging.outputStream().use { out ->
                    out.write(header)
                    stream.copyTo(out)
                }
            }
            // Older Androids throw on a bad font, newer ones return the default.
            val typeface = runCatching { Typeface.createFromFile(staging) }.getOrNull()
            if (typeface == null || typeface == Typeface.DEFAULT) throw notAFont()
            if (!staging.renameTo(font)) throw ReaderFontException("Couldn't save that font.")
            _fonts.update { list -> (list + font).sortedBy { it.name.lowercase() } }
            font
        } finally {
            staging.delete()
        }
    }

    suspend fun delete(font: File) = withContext(Dispatchers.IO) {
        _fonts.update { list -> list - font }
        font.delete()
    }

    private fun notAFont() = ReaderFontException("That isn't a TTF or OTF font.")

    private companion object {
        const val IMPORT_PREFIX = ".import-"
    }
}
