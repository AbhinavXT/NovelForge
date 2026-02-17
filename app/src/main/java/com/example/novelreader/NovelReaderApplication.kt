package com.example.novelreader

import android.app.Application
import com.example.novelreader.data.BackupManager
import com.example.novelreader.data.DownloadManager
import com.example.novelreader.data.NovelRepository
import com.example.novelreader.data.TTSManager
import com.example.novelreader.data.database.AppDatabase
import com.example.novelreader.util.Logger

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