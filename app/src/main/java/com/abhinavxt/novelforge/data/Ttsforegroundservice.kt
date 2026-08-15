package com.abhinavxt.novelforge.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.abhinavxt.novelforge.MainActivity
import com.abhinavxt.novelforge.R
import com.abhinavxt.novelforge.data.tts.AudioFocusHelper
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Service for TTS playback.
 *
 * Shows a rich media-style notification with:
 *  - Novel title, chapter name, sentence progress
 *  - Previous / Pause / Next media controls
 *  - Hardware media button support (earphones, Bluetooth, lock screen)
 *  - Audio focus coordination with calls, assistant, other media
 */
class TTSForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "tts_playback_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "TTSFgService"

        private const val ACTION_TOGGLE = "TOGGLE_TTS"
        private const val ACTION_STOP = "STOP_TTS"
        private const val ACTION_PREV = "PREV_TTS"
        private const val ACTION_NEXT = "NEXT_TTS"
        private const val ACTION_REFRESH_META = "REFRESH_META_TTS"

        // Track latest notification state for rebuilds
        @Volatile var lastTitle: String = "Novel Forge"
            private set
        @Volatile var lastSubtitle: String = "Playing..."
            private set
        @Volatile var lastChapter: String = ""
            private set
        @Volatile var lastCoverUrl: String? = null
            private set

        /**
         * Decoded cover art, keyed by the URL it came from.
         *
         * updateNotification() fires once per SENTENCE, so decoding here would
         * mean a full image decode several times a minute for a picture that
         * only changes when the novel does. One entry is enough: only one book
         * plays at a time.
         */
        @Volatile private var artCache: Pair<String, android.graphics.Bitmap>? = null

        /**
         * Compat token for the platform MediaSession, published by the running
         * service. buildNotification is in the companion (it is called
         * statically from updateNotification) so it cannot read the instance
         * field directly.
         */
        @Volatile private var compatToken:
                android.support.v4.media.session.MediaSessionCompat.Token? = null

        fun cachedArt(url: String?): android.graphics.Bitmap? =
            artCache?.takeIf { it.first == url }?.second

        fun putArt(url: String, bitmap: android.graphics.Bitmap) {
            artCache = url to bitmap
        }

        fun start(
            context: Context,
            title: String = "Reading...",
            chapter: String = "",
            coverUrl: String? = null
        ) {
            lastTitle = title
            lastChapter = chapter
            lastCoverUrl = coverUrl
            val intent = Intent(context, TTSForegroundService::class.java).apply {
                putExtra("title", title)
                putExtra("chapter", chapter)
                putExtra("cover", coverUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Ask the running service to republish session metadata and reload
         * cover art. Sent as an intent because the caller (TTSManager) holds
         * no reference to the service instance.
         */
        fun refreshMetadata(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, TTSForegroundService::class.java)
                        .apply { action = ACTION_REFRESH_META }
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TTSForegroundService::class.java))
        }

        fun updateNotification(
            context: Context,
            title: String,
            progress: String,
            isPlaying: Boolean = true,
            chapter: String = lastChapter,
            coverUrl: String? = lastCoverUrl
        ) {
            lastTitle = title
            lastSubtitle = progress
            lastChapter = chapter
            lastCoverUrl = coverUrl
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, buildNotification(context, title, progress, isPlaying))
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to update notification: ${e.message}")
            }
        }

        private fun buildNotification(context: Context, title: String, subtitle: String, isPlaying: Boolean = true): Notification {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openPending = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val prevPending = actionPending(context, ACTION_PREV, 3)
            val togglePending = actionPending(context, ACTION_TOGGLE, 2)
            val nextPending = actionPending(context, ACTION_NEXT, 4)
            val stopPending = actionPending(context, ACTION_STOP, 1)

            // Media-notification convention: the TRACK goes in the title and
            // the ARTIST underneath. The chapter is the track here, the novel
            // is the artist -- the reverse of what this used to do.
            val headline = lastChapter.ifBlank { title }
            val byline = if (lastChapter.isBlank()) subtitle else "$title  •  $subtitle"

            val style = androidx.media.app.NotificationCompat.MediaStyle()
                // THE important line. From Android 13 the system discards the
                // app's notification layout for media and renders its own
                // player built from the MediaSession -- artwork, seek area,
                // output switcher. Without the token there is no session to
                // build from, so it fell back to a plain text notification.
                // targetSdk is 36, so this path is the normal one.
                .setShowActionsInCompactView(0, 1, 2)
                .setShowCancelButton(true)
                .setCancelButtonIntent(stopPending)
            compatToken?.let { style.setMediaSession(it) }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(headline)
                .setContentText(byline)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(cachedArt(lastCoverUrl))
                .setOngoing(true)
                .setContentIntent(openPending)
                .addAction(R.drawable.ic_media_prev, "Previous", prevPending)
                .addAction(
                    if (isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                    if (isPlaying) "Pause" else "Play",
                    togglePending
                )
                .addAction(R.drawable.ic_media_next, "Next", nextPending)
                .addAction(R.drawable.ic_media_close, "Stop", stopPending)
                .setStyle(style)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                // Rebuilt once per sentence. Without this the notification can
                // re-alert on every rebuild on some OEM skins.
                .setOnlyAlertOnce(true)
                // "3 minutes ago" on a player is noise.
                .setShowWhen(false)

            return builder.build()
        }

        private fun actionPending(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, TTSForegroundService::class.java).apply {
                this.action = action
            }
            return PendingIntent.getService(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    private val binder = LocalBinder()
    private var mediaSession: MediaSession? = null

    // ── NEW: audio focus coordinator ────────────────────────────
    // Lazy-initialized because TTSManager comes from the Application,
    // which isn't available in the constructor. Initialized on first
    // use from onStartCommand / onCreate.
    private var audioFocusHelper: AudioFocusHelper? = null

    // ── CPU wake lock ───────────────────────────────────────────
    // A foreground service keeps the PROCESS alive; it does not keep the CPU
    // awake. MediaPlayer has setWakeMode() for this; AudioTrack, which the
    // Sherpa engine writes to, has no equivalent -- so the device could
    // suspend in the gap between sentences while the next one was being
    // generated, stalling playback until something else woke the CPU.
    //
    // PARTIAL_WAKE_LOCK: CPU stays on, screen and keyboard do not.
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    // Cover art loading only. Cancelled in onDestroy.
    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    inner class LocalBinder : Binder() {
        fun getService(): TTSForegroundService = this@TTSForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        setupAudioFocus()
    }

    /**
     * Held only while actually playing, and always released in [onDestroy].
     *
     * The timeout is a safety net, not the intended lifetime: if this service
     * is ever killed in a way that skips onDestroy, an un-timed wake lock
     * would keep the CPU awake until reboot and drain the battery flat. Two
     * hours comfortably exceeds any single listening session while bounding
     * the worst case.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "NovelForge::TTSPlayback"
            ).apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L)
            }
            Logger.d(TAG, "Wake lock acquired")
        }.onFailure { Logger.e(TAG, "Wake lock acquire failed", it) }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }.onFailure { Logger.e(TAG, "Wake lock release failed", it) }
        wakeLock = null
    }

    /**
     * Initialize the audio focus helper. Pulls TTSManager from the
     * Application singleton — safe here because onCreate runs after
     * Application.onCreate.
     */
    private fun setupAudioFocus() {
        val ttsManager = getTtsManager()
        if (ttsManager != null) {
            audioFocusHelper = AudioFocusHelper(this, ttsManager)
        } else {
            Logger.w(TAG, "TTSManager not available; audio focus disabled")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ttsManager = getTtsManager()

        when (intent?.action) {
            ACTION_REFRESH_META -> {
                updateMetadata()
                lastCoverUrl?.takeIf { it.isNotBlank() }?.let { loadCoverArt(it) }
                return START_NOT_STICKY
            }

            ACTION_STOP -> {
                ttsManager?.stop()
                releaseWakeLock()
                // User-initiated stop. Release focus entirely — we're done.
                audioFocusHelper?.abandon()
                updatePlaybackState(PlaybackState.STATE_STOPPED)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                if (ttsManager != null) {
                    if (ttsManager.isPlaying()) {
                        // User-initiated pause. Release focus so other apps
                        // can have it while we're paused. If/when the user
                        // resumes, we re-request.
                        ttsManager.pause()
                        releaseWakeLock()
                        audioFocusHelper?.abandon()
                        updatePlaybackState(PlaybackState.STATE_PAUSED)
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(NOTIFICATION_ID, buildNotification(this, lastTitle, "Paused", false))
                    } else {
                        // User-initiated resume. Re-request focus — if we
                        // can't get it (rare), we still proceed because
                        // denying playback on tap would be worse UX than
                        // playing over whatever's happening.
                        audioFocusHelper?.request()
                        acquireWakeLock()
                        ttsManager.resume()
                        updatePlaybackState(PlaybackState.STATE_PLAYING)
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(NOTIFICATION_ID, buildNotification(this, lastTitle, lastSubtitle, true))
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_PREV -> {
                ttsManager?.skipToPrevious()
                return START_NOT_STICKY
            }
            ACTION_NEXT -> {
                ttsManager?.skipToNext()
                return START_NOT_STICKY
            }
        }

        val title = intent?.getStringExtra("title") ?: "Reading..."
        lastTitle = title
        startForeground(NOTIFICATION_ID, buildNotification(this, title, "Starting...", true))
        updatePlaybackState(PlaybackState.STATE_PLAYING)
        acquireWakeLock()
        updateMetadata()
        lastCoverUrl?.takeIf { it.isNotBlank() }?.let { loadCoverArt(it) }

        // Initial focus request on playback start. If TTSManager wasn't
        // available when setupAudioFocus ran, try again now.
        if (audioFocusHelper == null) setupAudioFocus()
        audioFocusHelper?.request()

        // NOT sticky. With START_STICKY the system restarts this service after
        // a process kill with a null Intent -- so it fell through to here and
        // called startForeground(), posting a "Reading..." notification for
        // playback that no longer exists and cannot be resumed. There is no
        // state to restore from a null Intent, so declining the restart is
        // both honest and less confusing.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWakeLock()

        // Release audio focus — if service dies while we hold focus,
        // the system thinks NovelForge is still a contender and won't
        // give full focus to the next app cleanly.
        audioFocusHelper?.abandon()
        audioFocusHelper = null

        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun getTtsManager(): TTSManager? {
        return (application as? com.abhinavxt.novelforge.NovelReaderApplication)?.ttsManager
    }

    // -- MediaSession --

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "NovelReaderTTS").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    Logger.d(TAG, "MediaSession: onPlay")
                    // Media-button play is user-initiated, same flow as
                    // the notification toggle button.
                    audioFocusHelper?.request()
                    getTtsManager()?.resume()
                    updatePlaybackState(PlaybackState.STATE_PLAYING)
                    refreshNotification(true)
                }

                override fun onPause() {
                    Logger.d(TAG, "MediaSession: onPause")
                    getTtsManager()?.pause()
                    audioFocusHelper?.abandon()
                    updatePlaybackState(PlaybackState.STATE_PAUSED)
                    refreshNotification(false)
                }

                override fun onStop() {
                    Logger.d(TAG, "MediaSession: onStop")
                    getTtsManager()?.stop()
                    audioFocusHelper?.abandon()
                    updatePlaybackState(PlaybackState.STATE_STOPPED)
                    stopSelf()
                }

                override fun onSkipToNext() {
                    Logger.d(TAG, "MediaSession: onSkipToNext")
                    getTtsManager()?.skipToNext()
                }

                override fun onSkipToPrevious() {
                    Logger.d(TAG, "MediaSession: onSkipToPrevious")
                    getTtsManager()?.skipToPrevious()
                }

                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonEvent.getParcelableExtra(
                            Intent.EXTRA_KEY_EVENT,
                            KeyEvent::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }

                    if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_HEADSETHOOK -> {
                                val mgr = getTtsManager()
                                if (mgr != null) {
                                    if (mgr.isPlaying()) onPause() else onPlay()
                                }
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> { onPlay(); return true }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> { onPause(); return true }
                            KeyEvent.KEYCODE_MEDIA_STOP -> { onStop(); return true }
                            KeyEvent.KEYCODE_MEDIA_NEXT -> { onSkipToNext(); return true }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { onSkipToPrevious(); return true }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })

            isActive = true
        }

        // Bridge the platform token to the AndroidX one MediaStyle needs.
        // Assigned OUT here rather than inside the apply block above, where
        // `sessionToken` would resolve to MediaSession's own read-only
        // property instead of the companion field.
        compatToken = mediaSession?.sessionToken?.let {
            android.support.v4.media.session.MediaSessionCompat.Token.fromToken(it)
        }

        updatePlaybackState(PlaybackState.STATE_PLAYING)
    }

    private fun refreshNotification(isPlaying: Boolean) {
        try {
            val subtitle = if (isPlaying) lastSubtitle else "Paused"
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(this, lastTitle, subtitle, isPlaying))
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to refresh notification: ${e.message}")
        }
    }

    /**
     * Publish metadata to the MediaSession.
     *
     * From Android 13 the system media player reads TITLE / ARTIST / ART from
     * here, not from the notification's own fields — so with no metadata set
     * the player rendered blank regardless of what the notification said.
     *
     * Cheap to call: the bitmap is cached and everything else is short
     * strings. Called when the chapter changes, not per sentence.
     */
    private fun updateMetadata() {
        val chapter = lastChapter.ifBlank { lastTitle }
        val art = cachedArt(lastCoverUrl)
        mediaSession?.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, chapter)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, lastTitle)
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, "NovelForge")
                .putString(
                    android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                    lastSubtitle
                )
                .apply {
                    if (art != null) {
                        putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                    }
                }
                // Deliberately NO METADATA_KEY_DURATION. Supplying one makes
                // the system draw a time scrubber, and TTS has no meaningful
                // millisecond duration — sentence count is not time, and
                // faking it would render as a wrong, un-seekable clock.
                // Progress stays in the subtitle where it is honest.
                .build()
        )
    }

    /**
     * Fetch the novel cover for use as album art.
     *
     * Coil rather than BitmapFactory: covers are either a local file path
     * (EPUB imports) or a remote URL (scraped sources), and Coil already
     * handles both plus disk caching. allowHardware(false) is required —
     * hardware bitmaps cannot cross the Binder boundary into a notification.
     */
    private fun loadCoverArt(url: String) {
        if (cachedArt(url) != null) return
        serviceScope.launch {
            runCatching {
                val request = coil.request.ImageRequest.Builder(this@TTSForegroundService)
                    .data(url)
                    .size(512)
                    .allowHardware(false)
                    .build()
                val result = coil.Coil.imageLoader(this@TTSForegroundService).execute(request)
                (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            }.onSuccess { bitmap ->
                if (bitmap != null) {
                    putArt(url, bitmap)
                    updateMetadata()
                    refreshNotification(isCurrentlyPlaying())
                    Logger.d(TAG, "Cover art loaded")
                }
            }.onFailure {
                Logger.w(TAG, "Cover art load failed: ${it.message}")
            }
        }
    }

    private fun isCurrentlyPlaying(): Boolean =
        getTtsManager()?.state?.value == com.abhinavxt.novelforge.data.TTSState.PLAYING

    private fun updatePlaybackState(state: Int) {
        val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS

        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .setActions(actions)
                .build()
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TTS Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when text-to-speech is playing"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}