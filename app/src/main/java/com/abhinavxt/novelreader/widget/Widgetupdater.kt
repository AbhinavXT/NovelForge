package com.abhinavxt.novelreader.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.abhinavxt.novelreader.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Small helper that tells Glance "re-invoke provideGlance on all instances
 * of the continue-reading widget."
 *
 * ── When to call this ──
 *
 * Call [requestUpdate] immediately after any write to WidgetStateRepository.
 * The DataStore write alone won't repaint the widget — Android needs to be
 * told to re-render.
 *
 * Places we wire this up:
 *  - ReaderViewModel after saveReadingProgress           (chapter advanced)
 *  - NovelDetailViewModel after removeFromLibrary        (might clear widget)
 *  - LibraryViewModel after first library add            (might populate)
 *  - Application.onCreate                                (safety net)
 *
 * ── Fire-and-forget ──
 *
 * Widget updates are cosmetic — if one fails, it's fine, Android's
 * own refresh cycle (every ~30 min by default) will catch up. So we
 * use a detached CoroutineScope with SupervisorJob so failures don't
 * propagate to the caller's scope.
 */
object WidgetUpdater {

    private const val TAG = "WidgetUpdater"

    // Detached scope — we don't want widget-update failures to propagate
    // into the caller's scope (e.g. the reader's viewModelScope).
    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Ask every ContinueReadingWidget instance on the home screen to
     * re-render. Idempotent, cheap, safe to spam-call.
     */
    fun requestUpdate(context: Context) {
        updateScope.launch {
            try {
                ContinueReadingWidget().updateAll(context.applicationContext)
            } catch (e: Exception) {
                Logger.e(TAG, "updateAll failed", e)
            }
        }
    }
}