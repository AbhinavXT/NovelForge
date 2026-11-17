package com.abhinavxt.novelforge.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the quote card offscreen to a PNG and shares it. Same technique as
 * [StatsShareExporter] (a ComposeView parked in a 1x1 host on the decor view,
 * density locked so every device renders identical pixels), but the height
 * wraps the content instead of being fixed, so it measures with AT_MOST.
 * The PNG goes in the cache dir, which the existing FileProvider already exposes.
 */
object QuoteCardExporter {

    private const val TAG = "QuoteCardExporter"

    private const val OUTPUT_WIDTH_PX = 1080
    // Upper bound for the wrapped height; a 600-char quote is far below this.
    private const val MAX_HEIGHT_PX = 4000

    // 1080 px at density 3.0 is a 360 dp wide card, the size the card is designed for.
    private const val RENDER_DENSITY = 3.0f
    private const val RENDER_FONT_SCALE = 1.0f

    private const val FILE_PREFIX = "quote-card-"

    /** Returns the PNG, or null on failure (the cause is logged). */
    suspend fun renderAndSave(context: Context, content: @Composable () -> Unit): File? {
        val activity = context.findActivity()
        if (activity == null) {
            Logger.e(TAG, "No Activity found from context, cannot render")
            return null
        }

        val bitmap = try {
            renderToBitmap(activity, content)
        } catch (e: Exception) {
            Logger.e(TAG, "renderToBitmap threw", e)
            null
        } ?: return null

        return try {
            withContext(Dispatchers.IO) {
                val outFile = File(activity.cacheDir, "$FILE_PREFIX${System.currentTimeMillis()}.png")
                FileOutputStream(outFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    fos.flush()
                }
                outFile
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Write-to-cache failed", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun renderToBitmap(activity: Activity, content: @Composable () -> Unit): Bitmap? {
        val decor = activity.window?.decorView as? ViewGroup ?: run {
            Logger.e(TAG, "Activity has no decor view")
            return null
        }

        // 1x1 host so nothing visible appears; GONE would stop the child drawing.
        val host = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
            clipChildren = true
            clipToPadding = true
        }
        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            layoutParams = ViewGroup.LayoutParams(OUTPUT_WIDTH_PX, ViewGroup.LayoutParams.WRAP_CONTENT)
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(RENDER_DENSITY, RENDER_FONT_SCALE)
                ) { content() }
            }
        }
        host.addView(composeView)

        fun layoutAtWidth() {
            composeView.measure(
                View.MeasureSpec.makeMeasureSpec(OUTPUT_WIDTH_PX, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(MAX_HEIGHT_PX, View.MeasureSpec.AT_MOST)
            )
            composeView.layout(0, 0, composeView.measuredWidth, composeView.measuredHeight)
        }

        return try {
            decor.addView(host)
            // Composition starts on the next frame; the wrapped height is only known after it.
            delay(100)
            layoutAtWidth()
            delay(50)
            layoutAtWidth()

            if (composeView.width <= 0 || composeView.height <= 0) {
                Logger.e(TAG, "ComposeView measured to ${composeView.width}x${composeView.height}, aborting")
                return null
            }
            val bitmap = Bitmap.createBitmap(composeView.width, composeView.height, Bitmap.Config.ARGB_8888)
            composeView.draw(Canvas(bitmap))
            bitmap
        } catch (e: Exception) {
            Logger.e(TAG, "renderToBitmap inner failure", e)
            null
        } finally {
            // Leaving the host attached would leak the Activity.
            try {
                decor.removeView(host)
            } catch (e: Exception) {
                Logger.e(TAG, "Cleanup removeView failed (harmless)", e)
            }
        }
    }

    fun share(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share quote")
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Logger.e(TAG, "share() failed, usually a FileProvider authority mismatch", e)
        }
    }

    private fun Context.findActivity(): Activity? {
        var ctx = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
