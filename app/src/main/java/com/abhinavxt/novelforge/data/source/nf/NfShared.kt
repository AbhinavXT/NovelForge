package com.abhinavxt.novelforge.data.source.nf

// What remains of the QuickNovel compatibility layer (package: data.source.nf).
//
// Every source is now a JavaScript plugin (data/source/plugin). This package
// keeps only what plugins still rely on: the Cloudflare solver
// (NfCloudflare.kt, with the HTTP helpers in NfHttp.kt) and the chapter
// clean-up below, which JsPluginSource applies to plugin chapters.

import com.abhinavxt.novelforge.util.Logger
import org.jsoup.Jsoup

const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.93 Safari/537.36"

fun logError(throwable: Throwable) {
    Logger.e("NfSource", "Error: ${throwable.message}")
    throwable.printStackTrace()
}

/**
 * Removes author-note containers (when [stripAuthorNotes]), the chapter's own
 * title heading (when [chapterName]/[chapterIndex] are given) and
 * "Translator: … Editor: …" credit lines.
 */
fun stripHtml(
    txt: String,
    chapterName: String? = null,
    chapterIndex: Int? = null,
    stripAuthorNotes: Boolean
): String {
    val document = Jsoup.parse(txt)
    try {
        if (stripAuthorNotes) {
            document.select("div.qnauthornotecontainer").remove()
        }
        if (chapterName != null && chapterIndex != null) {
            for (a in document.allElements) {
                if (a != null && a.hasText() &&
                    (a.text() == chapterName || (a.tagName() == "h3" && a.text()
                        .startsWith("Chapter ${chapterIndex + 1}")))
                ) {
                    a.remove() // removes the chapter title heading
                    break
                }
            }
        }
    } catch (e: Exception) {
        logError(e)
    }

    return document.html()
        .replace("<p>.*<strong>Translator:.*?Editor:.*>".toRegex(), "")
        .replace("<.*?Translator:.*?Editor:.*?>".toRegex(), "")
}
