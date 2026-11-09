package com.abhinavxt.novelforge.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One-time tips, each shown once next to the control it explains. */
enum class Tip(val title: String, val text: String) {
    CODEX("Character codex", "Who's who in this book, without spoilers past your chapter."),
    DOWNLOAD("Read offline", "Download chapters to read them without a connection."),
    AUDIO_EXPORT("Audiobook export", "Turn chapters into audio files. Once all are done, join them into one M4B audiobook."),
    READING_STATS("Reading stats", "Your reading time, streaks and daily goal."),
    CHARACTER_VOICES("Character voices", "Give each character their own voice when dialogue is read aloud."),
    PLUGINS("Plugins", "Add more sources from the official repository, or install your own."),
}

/** First-launch welcome and tip state. Not part of NovelForge backups. */
object Onboarding {

    private const val PREFS = "onboarding"
    private const val KEY_WELCOME_DONE = "welcome_done"
    private const val KEY_SEEN_TIPS = "seen_tips"

    /** What the welcome screen asked a tab to open once it shows. */
    enum class Action { IMPORT_EPUB, RESTORE_BACKUP }

    private lateinit var prefs: SharedPreferences

    private val _welcomeDone = MutableStateFlow(true)
    val welcomeDone: StateFlow<Boolean> = _welcomeDone.asStateFlow()

    private val _seenTips = MutableStateFlow<Set<String>>(emptySet())
    val seenTips: StateFlow<Set<String>> = _seenTips.asStateFlow()

    /** The tip on screen now; others wait so only one shows at a time. */
    private val _activeTip = MutableStateFlow<Tip?>(null)
    val activeTip: StateFlow<Tip?> = _activeTip.asStateFlow()

    val pendingAction = MutableStateFlow<Action?>(null)

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_WELCOME_DONE)) {
            // People updating from an older version already know the app.
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            prefs.edit().putBoolean(KEY_WELCOME_DONE, info.firstInstallTime != info.lastUpdateTime).apply()
        }
        _welcomeDone.value = prefs.getBoolean(KEY_WELCOME_DONE, true)
        _seenTips.value = prefs.getStringSet(KEY_SEEN_TIPS, emptySet()).orEmpty().toSet()
    }

    fun finishWelcome(action: Action? = null) {
        prefs.edit().putBoolean(KEY_WELCOME_DONE, true).apply()
        pendingAction.value = action
        _welcomeDone.value = true
    }

    /** True when [tip] may show now: not seen yet and no other tip is up. */
    fun claim(tip: Tip): Boolean =
        tip.name !in _seenTips.value && _activeTip.compareAndSet(null, tip)

    /** Ends [tip]'s turn; [seen] keeps it from showing again. */
    fun release(tip: Tip, seen: Boolean = true) {
        if (seen) {
            _seenTips.value = _seenTips.value + tip.name
            prefs.edit().putStringSet(KEY_SEEN_TIPS, _seenTips.value).apply()
        }
        _activeTip.compareAndSet(tip, null)
    }

    fun resetTips() {
        _seenTips.value = emptySet()
        prefs.edit().remove(KEY_SEEN_TIPS).apply()
    }
}
