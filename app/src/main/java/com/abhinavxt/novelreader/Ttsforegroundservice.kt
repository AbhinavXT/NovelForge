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
 * Foreground Service to keep TTS callbacks working when app is in background.
 *
 * Integrates [MediaSession] so hardware media buttons (earphones, Bluetooth,
 * lock screen controls) can pause/resume/stop TTS playback.
 */
class TTSForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "tts_playback_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "TTSFgService"

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
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification(context, title, progress))
        }

        private fun createNotification(context: Context, title: String, progress: String): Notification {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Pause/Resume action
            val pauseIntent = Intent(context, TTSForegroundService::class.java).apply {
                action = "TOGGLE_TTS"
            }
            val pausePendingIntent = PendingIntent.getService(
                context, 2, pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Stop action
            val stopIntent = Intent(context, TTSForegroundService::class.java).apply {
                action = "STOP_TTS"
            }
            val stopPendingIntent = PendingIntent.getService(
                context, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(progress)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_media_pause, "Pause", pausePendingIntent)
                .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .build()
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
        val app = application as? com.abhinavxt.novelreader.NovelReaderApplication

        when (intent?.action) {
            "STOP_TTS" -> {
                app?.ttsManager?.stop()
                updatePlaybackState(PlaybackState.STATE_STOPPED)
                stopSelf()
                return START_NOT_STICKY
            }
            "TOGGLE_TTS" -> {
                val ttsManager = app?.ttsManager
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
        }

        val title = intent?.getStringExtra("title") ?: "Reading..."
        startForeground(NOTIFICATION_ID, createNotification(this, title, "Playing..."))
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

    // ── MediaSession ─────────────────────────────────────────────

    private fun getTtsManager(): TTSManager? {
        return (application as? com.abhinavxt.novelreader.NovelReaderApplication)?.ttsManager
    }

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
                                // Single earphone button press — toggle play/pause
                                val ttsManager = getTtsManager()
                                if (ttsManager != null) {
                                    if (ttsManager.isPlaying()) {
                                        onPause()
                                    } else {
                                        onPlay()
                                    }
                                }
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                onPlay()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                onPause()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_STOP -> {
                                onStop()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                onSkipToNext()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                onSkipToPrevious()
                                return true
                            }
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

    // ── Notification channel ─────────────────────────────────────

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