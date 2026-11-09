package com.abhinavxt.novelforge.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** First-launch welcome state. Not part of NovelForge backups. */
object Onboarding {

    private const val PREFS = "onboarding"
    private const val KEY_WELCOME_DONE = "welcome_done"

    /** What the welcome screen asked a tab to open once it shows. */
    enum class Action { IMPORT_EPUB, RESTORE_BACKUP }

    private lateinit var prefs: SharedPreferences

    private val _welcomeDone = MutableStateFlow(true)
    val welcomeDone: StateFlow<Boolean> = _welcomeDone.asStateFlow()

    val pendingAction = MutableStateFlow<Action?>(null)

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_WELCOME_DONE)) {
            // People updating from an older version already know the app.
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            prefs.edit().putBoolean(KEY_WELCOME_DONE, info.firstInstallTime != info.lastUpdateTime).apply()
        }
        _welcomeDone.value = prefs.getBoolean(KEY_WELCOME_DONE, true)
        // Leftover from the 2.9.0 test builds' tips.
        prefs.edit().remove("seen_tips").apply()
    }

    fun finishWelcome(action: Action? = null) {
        prefs.edit().putBoolean(KEY_WELCOME_DONE, true).apply()
        pendingAction.value = action
        _welcomeDone.value = true
    }
}
