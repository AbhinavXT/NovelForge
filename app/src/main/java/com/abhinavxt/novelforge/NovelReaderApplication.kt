package com.abhinavxt.novelforge

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.abhinavxt.novelforge.data.BackupManager
import com.abhinavxt.novelforge.data.ChapterPrefetcher
import com.abhinavxt.novelforge.data.DownloadManager
import com.abhinavxt.novelforge.data.NovelRepository
import com.abhinavxt.novelforge.data.PronunciationManager
import com.abhinavxt.novelforge.data.ReadingStatsTracker
import com.abhinavxt.novelforge.data.TTSManager
import com.abhinavxt.novelforge.data.ThemePreferences
import com.abhinavxt.novelforge.data.UpdateChecker
import com.abhinavxt.novelforge.data.database.AppDatabase
import com.abhinavxt.novelforge.data.tts.M4BAudiobookBuilder
import com.abhinavxt.novelforge.util.Logger
import com.abhinavxt.novelforge.util.NetworkMonitor
import com.abhinavxt.novelforge.widget.WidgetStateRepository
import com.abhinavxt.novelforge.worker.AutoBackupWorker
import com.abhinavxt.novelforge.worker.UpdateCheckerWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient


/**
 * Volume key events emitted by MainActivity.onKeyDown().
 * The ReaderScreen collects this flow and scrolls/pages accordingly
 * when volumeKeyNavigation is enabled in reader settings.
 */
enum class VolumeKeyEvent {
    UP,   // Volume up pressed — scroll up / previous page
    DOWN  // Volume down pressed — scroll down / next page
}

class NovelReaderApplication : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Database instance - created lazily
    private val database by lazy { AppDatabase.getDatabase(this) }

    // ── Widget cache ────────────────────────────────────────────
    // Owned here so the repository can push updates to it. Lazy so it
    // isn't created unless something touches it; in practice both the
    // repository's callback and the widget itself will, on first read.
    val widgetStateRepository by lazy { WidgetStateRepository(this) }

    // ── Repository ──────────────────────────────────────────────
    // The onProgressSaved callback is the widget integration hook. Every
    // successful saveReadingProgress (or removeFromLibrary) will fire
    // this, which rebuilds the widget cache from the current DB state.
    //
    // Wrapped in a try/catch inside the repository itself so widget-update
    // failures never break chapter reading. The callback itself also
    // handles its own errors internally — we're defensive in both places
    // because widget reliability shouldn't block reading reliability.
    val repository: NovelRepository by lazy {
        NovelRepository(
            database = database,
            onProgressSaved = { _ ->
                // The param is the novelId that just changed — we don't
                // actually need it here because rebuildFromRepository
                // always reads the most-recent progress from the DB.
                // Kept in the callback signature for future callers that
                // might want to short-circuit when the changed novel
                // isn't the widget's current one.
                widgetStateRepository.rebuildFromRepository(repository)
            }
        )
    }

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

    // Chapter pre-fetcher — caches upcoming chapters for instant navigation
    val chapterPrefetcher by lazy { ChapterPrefetcher(repository) }

    // M4B audiobook builder — encodes WAV chapters into chaptered M4B
    val m4bBuilder by lazy { M4BAudiobookBuilder(this) }

    // ── Volume key event bus ────────────────────────────────────
    // MainActivity posts here on volume key press; ReaderScreen collects.
    // SharedFlow with replay=0 means events are only received by active collectors.
    private val _volumeKeyEvents = MutableSharedFlow<VolumeKeyEvent>(extraBufferCapacity = 1)
    val volumeKeyEvents: SharedFlow<VolumeKeyEvent> = _volumeKeyEvents.asSharedFlow()

    val updateChecker by lazy { UpdateChecker(this) }

    val networkMonitor by lazy { NetworkMonitor(this) }

    /** Called by MainActivity.onKeyDown when on the reader screen. */
    fun emitVolumeKey(event: VolumeKeyEvent) {
        _volumeKeyEvents.tryEmit(event)
    }

    override fun onCreate() {
        super.onCreate()

        // Give the QuickNovel-ported source layer an application context
        // (needed by the Cloudflare WebView resolver).
        com.abhinavxt.novelforge.data.source.nf.NfBridge.init(this)

        // Load pronunciation cache so substitutions work immediately
        appScope.launch {
            pronunciationManager.loadCache()
        }

        // Wire pronunciation manager into TTS and backup
        ttsManager.pronunciationManager = pronunciationManager
        backupManager.pronunciationManager = pronunciationManager
        backupManager.readingStatsTracker = readingStatsTracker

        appScope.launch {
            updateChecker.check(force = false)
        }

        // Restore update checker schedule if it was enabled
        val prefs = getSharedPreferences(UpdateCheckerWorker.PREFS_NAME, MODE_PRIVATE)
        val enabled = prefs.getBoolean(UpdateCheckerWorker.PREF_ENABLED, false)
        if (enabled) {
            val interval = prefs.getLong(UpdateCheckerWorker.PREF_INTERVAL_HOURS, 12)
            UpdateCheckerWorker.schedule(this, true, interval)
        }

        // Restore auto-backup schedule if it was enabled (Phase 7)
        AutoBackupWorker.schedule(this, AutoBackupWorker.isEnabled(this))

        // Source health check: preload persisted results (off-main) so the
        // source picker has badges immediately, then keep probing daily.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.abhinavxt.novelforge.data.source.health.SourceHealthStore.load(this@NovelReaderApplication)
        }
        com.abhinavxt.novelforge.worker.SourceHealthWorker.schedule(this, true)

        networkMonitor.start()

        // ── Widget cache refresh on process start ───────────────
        // Handles the case where the app was killed, the user hadn't
        // opened the reader since, and a new chapter was pulled by the
        // background UpdateCheckerWorker. Without this, the widget
        // could be stale for days until the user next reads something.
        // Cheap: reads one row from Room + one pref write if state
        // actually changed.
        appScope.launch {
            try {
                widgetStateRepository.rebuildFromRepository(repository)
            } catch (e: Exception) {
                Logger.e("NovelReaderApplication", "widget warmup failed", e)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        ttsManager.shutdown()
        chapterPrefetcher.clear()
    }

    /**
     * Global Coil ImageLoader with Referer header interceptor.
     * Fixes cover loading for sources that reject requests without Referer.
     */
    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.header("Referer") == null) {
                    val url = request.url
                    val referer = "${url.scheme}://${url.host}"
                    chain.proceed(
                        request.newBuilder()
                            .header("Referer", referer)
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .crossfade(true)
            .build()
    }
}