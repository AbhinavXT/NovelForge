package com.abhinavxt.novelforge.data.tts

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.abhinavxt.novelforge.NovelReaderApplication
import com.abhinavxt.novelforge.util.Logger
import java.util.concurrent.atomic.AtomicInteger

/**
 * Holds the export progress notification in the foreground so Android doesn't
 * kill a long audio export or M4B build once the app is backgrounded. The work
 * itself still runs in [AudioExporter] / [M4BAudiobookBuilder]; the exporter
 * keeps updating the same notification id. Separate from TTSForegroundService
 * (mediaPlayback).
 */
class ExportForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as NovelReaderApplication
        if (intent?.action == ACTION_CANCEL) {
            app.audioExporter.cancel()
            app.m4bBuilder.cancel()
            return START_NOT_STICKY
        }
        try {
            val notification = app.audioExporter.buildExportNotification("Audio export", "Working in the background", null)
            if (Build.VERSION.SDK_INT >= 35) {
                startForeground(AudioExporter.EXPORT_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(AudioExporter.EXPORT_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(AudioExporter.EXPORT_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Includes ForegroundServiceStartNotAllowedException (API 31+); the export carries on without it.
            Logger.e(TAG, "Could not enter foreground", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // API 35 caps mediaProcessing at ~6h; the system crashes the app if we don't stop.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    companion object {
        private const val TAG = "ExportFgService"
        const val ACTION_CANCEL = "com.abhinavxt.novelforge.action.CANCEL_EXPORT"
        private val active = AtomicInteger(0)

        /** Call when an export or M4B build is launched; pair with [end]. */
        fun begin(context: Context) {
            if (active.getAndIncrement() != 0) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, ExportForegroundService::class.java))
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException when started from the background.
                Logger.e(TAG, "Could not start foreground service", e)
            }
        }

        /** Stops the service once the last running export / build has finished. */
        fun end(context: Context) {
            if (active.decrementAndGet() > 0) return
            active.set(0)
            context.stopService(Intent(context, ExportForegroundService::class.java))
        }
    }
}
