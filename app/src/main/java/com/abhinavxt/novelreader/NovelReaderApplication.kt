package com.abhinavxt.novelreader

import android.app.Application
import com.abhinavxt.novelreader.data.BackupManager
import com.abhinavxt.novelreader.data.DownloadManager
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.PronunciationManager
import com.abhinavxt.novelreader.data.ReadingStatsTracker
import com.abhinavxt.novelreader.data.TTSManager
import com.abhinavxt.novelreader.data.ThemePreferences
import com.abhinavxt.novelreader.data.database.AppDatabase
import com.abhinavxt.novelreader.util.Logger
import com.abhinavxt.novelreader.worker.UpdateCheckerWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NovelReaderApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Database instance - created lazily
    private val database by lazy { AppDatabase.getDatabase(this) }

    // Repository - depends on database
    val repository by lazy { NovelRepository(database) }

    // Download manager - depends on database
    val downloadManager by lazy { DownloadManager(database) }

    // TTS manager
    val ttsManager by lazy { TTSManager(this) }

    // Backup manager - depends on repository
    val backupManager by lazy { BackupManager(this, repository) }

    // Theme preferences
    val themePreferences by lazy { ThemePreferences(this) }

    // Pronunciation manager
    val pronunciationManager by lazy { PronunciationManager(database.pronunciationDao()) }

    // Reading stats tracker
    val readingStatsTracker by lazy { ReadingStatsTracker(database.readingStatDao()) }

    override fun onCreate() {
        super.onCreate()

        // Load pronunciation cache so substitutions work immediately
        appScope.launch {
            pronunciationManager.loadCache()
        }

        // Wire pronunciation manager into TTS and backup
        ttsManager.pronunciationManager = pronunciationManager
        backupManager.pronunciationManager = pronunciationManager

        // Restore update checker schedule if it was enabled
        val prefs = getSharedPreferences(UpdateCheckerWorker.PREFS_NAME, MODE_PRIVATE)
        val enabled = prefs.getBoolean(UpdateCheckerWorker.PREF_ENABLED, false)
        if (enabled) {
            val interval = prefs.getLong(UpdateCheckerWorker.PREF_INTERVAL_HOURS, 12)
            UpdateCheckerWorker.schedule(this, true, interval)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        ttsManager.shutdown()
    }
}