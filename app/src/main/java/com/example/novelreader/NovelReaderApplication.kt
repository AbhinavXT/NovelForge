package com.example.novelreader

import android.app.Application
import com.example.novelreader.data.DownloadManager
import com.example.novelreader.data.NovelRepository
import com.example.novelreader.data.TTSManager
import com.example.novelreader.data.database.AppDatabase

class NovelReaderApplication : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getDatabase(this)
    }

    val repository: NovelRepository by lazy {
        NovelRepository(database)
    }

    val downloadManager: DownloadManager by lazy {
        DownloadManager(database)
    }

    val ttsManager: TTSManager by lazy {
        TTSManager(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        ttsManager.shutdown()
    }
}