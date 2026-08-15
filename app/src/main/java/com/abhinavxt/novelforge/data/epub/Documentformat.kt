package com.abhinavxt.novelforge.data.epub

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Which importer handles a picked file.
 *
 * Detection deliberately checks the FILE EXTENSION before the MIME type.
 * Android's content providers are unreliable here: Google Drive commonly
 * reports `application/octet-stream` for .epub, some file managers report
 * `text/plain` for .md, and .md has no registered MIME type at all, so
 * `text/markdown` is far from guaranteed. The extension is what the user
 * actually sees and is the more dependable signal.
 */
enum class DocumentFormat {
    EPUB,
    TEXT,
    MARKDOWN,

    /**
     * Recognised but deliberately not supported.
     *
     * PDF text extraction needs a third-party library — the platform's
     * PdfRenderer only rasterises pages to bitmaps — and the usual choice
     * (PdfBox-Android) has been unmaintained since January 2023 with known
     * CVEs in its dependencies. Parsing untrusted binary input with an
     * unpatched parser was not worth it, especially since PDF has no concept
     * of a paragraph and produces visibly worse output than .txt or .md.
     *
     * Kept as its own case rather than folded into UNSUPPORTED so the user
     * gets an explanation and a workaround instead of a shrug.
     */
    PDF,

    UNSUPPORTED;

    companion object {

        /** MIME types to hand the system file picker. */
        val PICKER_MIME_TYPES = arrayOf(
            "application/epub+zip",
            "text/plain",
            "text/markdown",
            // Providers that cannot classify a file fall back to this. Without
            // it, .epub files on Google Drive are greyed out and unpickable.
            "application/octet-stream"
        )

        private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")
        private val TEXT_EXTENSIONS = setOf("txt", "text", "log")

        fun detect(context: Context, uri: Uri): DocumentFormat {
            val name = displayName(context, uri).lowercase()
            when (name.substringAfterLast('.', "")) {
                "epub" -> return EPUB
                in MARKDOWN_EXTENSIONS -> return MARKDOWN
                in TEXT_EXTENSIONS -> return TEXT
                "pdf" -> return PDF
                else -> Unit
            }

            // Extension missing or unrecognised — fall back to the MIME type.
            return when (val mime = context.contentResolver.getType(uri)) {
                "application/epub+zip", "application/x-epub" -> EPUB
                "text/markdown", "text/x-markdown" -> MARKDOWN
                "application/pdf" -> PDF
                null -> UNSUPPORTED
                else -> if (mime.startsWith("text/")) TEXT else UNSUPPORTED
            }
        }

        /**
         * Human-readable file name, used both for detection and as the fallback
         * book title. Falls back to the last path segment when the provider
         * exposes no DISPLAY_NAME column.
         */
        fun displayName(context: Context, uri: Uri): String {
            runCatching {
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                        }
                    }
                }
            }
            return uri.lastPathSegment?.substringAfterLast('/') ?: "Imported Document"
        }
    }
}