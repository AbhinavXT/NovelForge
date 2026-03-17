package com.abhinavxt.novelreader

import android.app.Application
import com.abhinavxt.novelreader.data.BackupManager
import com.abhinavxt.novelreader.data.DownloadManager
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.data.TTSManager
import com.abhinavxt.novelreader.data.ThemePreferences
import com.abhinavxt.novelreader.data.database.AppDatabase
import com.abhinavxt.novelreader.util.Logger

class NovelReaderApplication : Application() {

    // Database instance - created lazily
    private val database by lazy { AppDatabase.getDatabase(this) }

    // Repository - depends on database
    val repository by lazy { NovelRepository(database) }

    // Download manager - depends on repository
    val downloadManager by lazy { DownloadManager(database) }

    // TTS manager
    val ttsManager by lazy { TTSManager(this) }

    // Backup manager - depends on repository
    val backupManager by lazy { BackupManager(this, repository) }

    // Theme preferences
    val themePreferences by lazy { ThemePreferences(this) }

    override fun onCreate() {
        super.onCreate()

        // Initialize Logger
//        Logger.init(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        ttsManager.shutdown()
    }

}