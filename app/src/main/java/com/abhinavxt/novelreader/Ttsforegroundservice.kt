package com.abhinavxt.novelreader.data

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
import com.abhinavxt.novelreader.MainActivity
import com.abhinavxt.novelreader.R
import com.abhinavxt.novelreader.util.Logger

/**
 * Foreground Service for TTS playback.
 *
 * Shows a rich media-style notification with:
 *  - Novel title, chapter name, sentence progress
 *  - Previous / Pause / Next media controls
 *  - Hardware media button support (earphones, Bluetooth, lock screen)
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

        fun start(context: Context, title: String = "Reading...") {
            val intent = Intent(context, TTSForegroundService::class.java).apply {
                putExtra("title", title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TTSForegroundService::class.java))
        }

        fun updateNotification(context: Context, title: String, progress: String) {
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, buildNotification(context, title, progress))
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to update notification: ${e.message}")
            }
        }

        private fun buildNotification(context: Context, title: String, subtitle: String): Notification {
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

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setSubText("Novel Reader")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setContentIntent(openPending)
                .addAction(android.R.drawable.ic_media_previous, "Prev", prevPending)
                .addAction(android.R.drawable.ic_media_pause, "Pause", togglePending)
                .addAction(android.R.drawable.ic_media_next, "Next", nextPending)
                .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
                .setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(0, 1, 2)
                )
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
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

    inner class LocalBinder : Binder() {
        fun getService(): TTSForegroundService = this@TTSForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ttsManager = getTtsManager()

        when (intent?.action) {
            ACTION_STOP -> {
                ttsManager?.stop()
                updatePlaybackState(PlaybackState.STATE_STOPPED)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                if (ttsManager != null) {
                    if (ttsManager.isPlaying()) {
                        ttsManager.pause()
                        updatePlaybackState(PlaybackState.STATE_PAUSED)
                    } else {
                        ttsManager.resume()
                        updatePlaybackState(PlaybackState.STATE_PLAYING)
                    }
                }
                return START_STICKY
            }
            ACTION_PREV -> {
                ttsManager?.skipToPrevious()
                return START_STICKY
            }
            ACTION_NEXT -> {
                ttsManager?.skipToNext()
                return START_STICKY
            }
        }

        val title = intent?.getStringExtra("title") ?: "Reading..."
        startForeground(NOTIFICATION_ID, buildNotification(this, title, "Starting..."))
        updatePlaybackState(PlaybackState.STATE_PLAYING)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun getTtsManager(): TTSManager? {
        return (application as? com.abhinavxt.novelreader.NovelReaderApplication)?.ttsManager
    }

    // -- MediaSession --

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "NovelReaderTTS").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    Logger.d(TAG, "MediaSession: onPlay")
                    getTtsManager()?.resume()
                    updatePlaybackState(PlaybackState.STATE_PLAYING)
                }

                override fun onPause() {
                    Logger.d(TAG, "MediaSession: onPause")
                    getTtsManager()?.pause()
                    updatePlaybackState(PlaybackState.STATE_PAUSED)
                }

                override fun onStop() {
                    Logger.d(TAG, "MediaSession: onStop")
                    getTtsManager()?.stop()
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

        updatePlaybackState(PlaybackState.STATE_PLAYING)
    }

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