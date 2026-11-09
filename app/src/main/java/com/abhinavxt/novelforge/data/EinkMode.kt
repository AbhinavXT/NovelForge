package com.abhinavxt.novelforge.data

import com.abhinavxt.novelforge.data.model.PageTransition
import com.abhinavxt.novelforge.data.model.ReaderTheme
import com.abhinavxt.novelforge.data.model.ReadingMode

/** The four settings E-ink mode changes. */
data class EinkLook(
    val appTheme: AppTheme,
    val readerTheme: ReaderTheme,
    val readingMode: ReadingMode,
    val pageTransition: PageTransition,
) {
    fun encode() = listOf(appTheme, readerTheme, readingMode, pageTransition).joinToString("|") { it.name }

    /**
     * The look to go back to when E-ink mode turns off: each setting returns to what it was before,
     * unless it was changed while E-ink mode was on, in which case the reader's choice stays.
     */
    fun restoredTo(before: EinkLook) = EinkLook(
        appTheme = if (appTheme == EINK.appTheme) before.appTheme else appTheme,
        readerTheme = if (readerTheme == EINK.readerTheme) before.readerTheme else readerTheme,
        readingMode = if (readingMode == EINK.readingMode) before.readingMode else readingMode,
        pageTransition = if (pageTransition == EINK.pageTransition) before.pageTransition else pageTransition,
    )

    companion object {
        /** Black on white, paged, pages that turn without a slide. */
        val EINK = EinkLook(AppTheme.EINK, ReaderTheme.EINK, ReadingMode.PAGED, PageTransition.NONE)

        fun decode(text: String?): EinkLook? = runCatching {
            val (app, reader, mode, turn) = text!!.split("|")
            EinkLook(AppTheme.valueOf(app), ReaderTheme.valueOf(reader), ReadingMode.valueOf(mode), PageTransition.valueOf(turn))
        }.getOrNull()
    }
}

/** Turns E-ink mode on or off, saving the look it replaces and putting it back afterwards. */
object EinkMode {
    suspend fun set(on: Boolean, prefs: ThemePreferences, repository: NovelRepository) {
        val reader = repository.getReaderSettings()
        val current = EinkLook(prefs.appTheme.value, reader.theme, reader.readingMode, reader.pageTransition)
        if (on) {
            if (!prefs.einkMode.value) prefs.preEinkLook = current.encode()
            apply(EinkLook.EINK, prefs, repository)
        } else {
            // Turned on before this was saved: nothing to go back to.
            EinkLook.decode(prefs.preEinkLook)?.let { apply(current.restoredTo(it), prefs, repository) }
            prefs.preEinkLook = null
        }
        prefs.setEinkMode(on)
    }

    private suspend fun apply(look: EinkLook, prefs: ThemePreferences, repository: NovelRepository) {
        prefs.setAppTheme(look.appTheme)
        repository.saveReaderSettings(
            repository.getReaderSettings().copy(
                theme = look.readerTheme,
                readingMode = look.readingMode,
                pageTransition = look.pageTransition
            )
        )
    }
}
