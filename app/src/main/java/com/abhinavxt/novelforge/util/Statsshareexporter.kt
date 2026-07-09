package com.abhinavxt.novelforge.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
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
 * Renders a Compose UI offscreen at a fixed pixel size and writes the
 * result to a PNG file ready for sharing via ACTION_SEND.
 *
 * ── Why this version looks different from the first one ──
 *
 * The original version added a ComposeView to the WindowManager at an
 * offscreen coordinate. That worked in isolation but crashes on many
 * devices with BadTokenException because TYPE_APPLICATION_PANEL requires
 * an existing window token — which isn't reliably available from a
 * non-Activity context, and even from an Activity context the token
 * is sometimes null during configuration changes or early composition.
 *
 * This version uses a completely different technique:
 *   1. Unwrap the Activity from the Context (walks ContextWrappers).
 *   2. Attach a tiny (1x1) FrameLayout to the Activity's decorView as a
 *      normal child. Decor already has all the view-tree owners attached
 *      (lifecycle, saved state, view-model store) so ComposeView works
 *      out of the box.
 *   3. Inside that 1x1 container, place the real ComposeView at 1080x1920
 *      and clip it. The container stays at 1x1 so the user sees nothing;
 *      the ComposeView renders to its full target size because we measure
 *      + layout it manually at 1080x1920.
 *   4. Draw the ComposeView to a bitmap.
 *   5. Remove the container from decorView.
 *
 * No WindowManager calls, no permissions, no token dependencies. The
 * only requirement is that we're called from an Activity context.
 *
 * ── Why we lock LocalDensity (the v2 fix) ──
 *
 * Compose converts dp → px and sp → px using the device's display
 * density. The render canvas is fixed at 1080×1920 PIXELS, but the
 * content was designed assuming a "standard" xxhdpi screen (density
 * 3.0). On a device with density 3.5 or higher (common on flagship
 * phones), `padding(horizontal = 56.dp)` alone consumes
 * 56 × 3.5 × 2 = 392 px of horizontal space, leaving only ~688 px of
 * content area on a 1080 px canvas — too narrow for the 112sp hero
 * number "262K" + the "words" sidekick to fit on one line. The result
 * was the hero wrapping onto two lines and the "words" label squeezed
 * to a single character per line.
 *
 * The fix: override LocalDensity for the share content with a fixed
 * density of 3.0 + fontScale 1.0. Now the rendered card is byte-for-
 * byte identical regardless of which device the user is on, and the
 * 1080×1920 canvas always has the same effective dp dimensions
 * (360×640 dp). This is a standard pattern for offscreen rendering.
 */
object StatsShareExporter {

    private const val TAG = "StatsShareExporter"

    // 9:16 story size — IG/WhatsApp stories are natively 1080x1920.
    private const val OUTPUT_WIDTH_PX = 1080
    private const val OUTPUT_HEIGHT_PX = 1920

    // Locked density for rendering. Picked 3.0 because:
    //  - Canvas is 1080x1920 px → at density 3.0 that's 360x640 dp,
    //    which is the canonical xxhdpi phone size the share card was
    //    designed against.
    //  - fontScale 1.0 so users with system font scaling cranked up
    //    don't see broken cards (text overflowing the canvas).
    private const val RENDER_DENSITY = 3.0f
    private const val RENDER_FONT_SCALE = 1.0f

    private const val FILE_PREFIX = "stats-share-"

    /**
     * Render [content] offscreen at 1080x1920, save it as a PNG in the
     * app's cache directory, and return the resulting [File].
     *
     * Returns null on failure — callers should surface this as a toast
     * or snackbar. Check logcat for the actual cause (tagged "[TAG]").
     */
    suspend fun renderAndSave(
        context: Context,
        content: @Composable () -> Unit
    ): File? {
        // Step 1: get the host Activity. Without one we can't render.
        val activity = context.findActivity()
        if (activity == null) {
            Logger.e(TAG, "No Activity found from context — cannot render")
            return null
        }

        // Step 2: render on the main thread.
        val bitmap = try {
            renderToBitmap(activity, content)
        } catch (e: Exception) {
            Logger.e(TAG, "renderToBitmap threw", e)
            null
        } ?: return null

        // Step 3: compress + write on IO.
        return try {
            withContext(Dispatchers.IO) {
                val outFile = File(
                    activity.cacheDir,
                    "$FILE_PREFIX${System.currentTimeMillis()}.png"
                )
                FileOutputStream(outFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    fos.flush()
                }
                bitmap.recycle()
                outFile
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Write-to-cache failed", e)
            bitmap.recycle()
            null
        }
    }

    /**
     * Core render primitive. Attaches a ComposeView to decorView, forces
     * layout at 1080x1920, waits two frames for composition, draws to a
     * bitmap, and detaches.
     */
    private suspend fun renderToBitmap(
        activity: Activity,
        content: @Composable () -> Unit
    ): Bitmap? {
        // decorView is the root of the Activity's view hierarchy. It's
        // guaranteed to have ViewTreeLifecycleOwner and friends wired up
        // because that's how modern Activities bootstrap Compose.
        val decor = activity.window?.decorView as? ViewGroup ?: run {
            Logger.e(TAG, "Activity has no decor view")
            return null
        }

        // The outer 1x1 container. It's added to decorView as a normal
        // child so the user could theoretically see it — but at 1x1 with
        // transparent background, positioned in the top-left corner, it's
        // invisible in practice. We don't use visibility=GONE because
        // GONE views don't draw — we need ours to draw into the bitmap.
        val hostContainer = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
            setBackgroundColor(AndroidColor.TRANSPARENT)
            // Clip children so the full-size ComposeView inside doesn't
            // bleed into the actual UI.
            clipChildren = true
            clipToPadding = true
        }

        // The actual ComposeView. Sized to 1080x1920 via explicit layout
        // params, placed inside the 1x1 host. clipChildren on the host
        // keeps any draw calls contained.
        val composeView = ComposeView(activity).apply {
            // DisposeOnDetachedFromWindow releases composition resources
            // the moment we detach from decorView. Important: don't use
            // DisposeOnViewTreeLifecycleDestroyed here because the lifecycle
            // we're attached to (the Activity's) outlives our render and
            // we'd leak the composition until Activity destruction.
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindow
            )
            layoutParams = ViewGroup.LayoutParams(
                OUTPUT_WIDTH_PX,
                OUTPUT_HEIGHT_PX
            )
            setContent {
                // ── THE KEY FIX ────────────────────────────────────────
                // Override LocalDensity so dp/sp → px conversion uses a
                // fixed scale rather than the device's actual density.
                // Without this, a device with density 3.5 would render
                // the same dp values 17% larger than a density-3.0 device,
                // overflowing the 1080-px-wide canvas. With this, the
                // share card renders identically on every device.
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = RENDER_DENSITY,
                        fontScale = RENDER_FONT_SCALE
                    )
                ) {
                    content()
                }
            }
        }

        hostContainer.addView(composeView)

        return try {
            // Add our host to decor. From this moment onward, composeView
            // is in the view tree and composition will begin on the next frame.
            decor.addView(hostContainer)

            // Force measure+layout of the ComposeView at the exact output
            // dimensions. The host is 1x1 but we measure the inner view at
            // 1080x1920 explicitly — View.measure+layout bypasses the parent's
            // size constraint for our purposes.
            composeView.measure(
                View.MeasureSpec.makeMeasureSpec(
                    OUTPUT_WIDTH_PX, View.MeasureSpec.EXACTLY
                ),
                View.MeasureSpec.makeMeasureSpec(
                    OUTPUT_HEIGHT_PX, View.MeasureSpec.EXACTLY
                )
            )
            composeView.layout(0, 0, OUTPUT_WIDTH_PX, OUTPUT_HEIGHT_PX)

            // Wait for Compose's initial composition + measurement to land
            // in the draw pass. Empirically, one frame (16ms @ 60Hz) is
            // usually enough, but cold launches where fonts are loading
            // need more. 100ms is a safe upper bound; not noticeable as
            // UI latency since the share intent already has its own
            // transition animation.
            delay(100)

            // Re-layout after composition settles. Compose may have
            // requested a layout during composition that hasn't been
            // applied yet — this second pass flushes it.
            if (composeView.isLayoutRequested) {
                composeView.measure(
                    View.MeasureSpec.makeMeasureSpec(
                        OUTPUT_WIDTH_PX, View.MeasureSpec.EXACTLY
                    ),
                    View.MeasureSpec.makeMeasureSpec(
                        OUTPUT_HEIGHT_PX, View.MeasureSpec.EXACTLY
                    )
                )
                composeView.layout(0, 0, OUTPUT_WIDTH_PX, OUTPUT_HEIGHT_PX)
            }

            // Sanity-check the dimensions. If width or height is zero
            // something went wrong with measure/layout and
            // Bitmap.createBitmap would crash.
            if (composeView.width <= 0 || composeView.height <= 0) {
                Logger.e(TAG, "ComposeView measured to ${composeView.width}x" +
                        "${composeView.height} — aborting")
                return null
            }

            // Allocate output bitmap. ARGB_8888 is required for gradient
            // smoothness; RGB_565 bands visibly on dark gradients.
            val bitmap = Bitmap.createBitmap(
                composeView.width,
                composeView.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            composeView.draw(canvas)

            bitmap
        } catch (e: Exception) {
            Logger.e(TAG, "renderToBitmap inner failure", e)
            null
        } finally {
            // Always detach, even on exception paths. Leaking hostContainer
            // would leak the Activity until process death.
            try {
                decor.removeView(hostContainer)
            } catch (e: Exception) {
                Logger.e(TAG, "Cleanup removeView failed (harmless)", e)
            }
        }
    }

    /**
     * Fire an ACTION_SEND chooser for a rendered image file.
     */
    fun share(
        context: Context,
        file: File,
        authority: String = "${context.packageName}.fileprovider",
        shareText: String? = null
    ) {
        try {
            val uri = FileProvider.getUriForFile(context, authority, file)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                if (!shareText.isNullOrBlank()) {
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                // Receivers need the grant to actually read our cache file.
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(intent, "Share reading stats")
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

            context.startActivity(chooser)
        } catch (e: Exception) {
            // Most common failure here: FileProvider authority mismatch
            // (check AndroidManifest.xml <provider android:authorities=...>).
            Logger.e(TAG, "share() failed — usually means FileProvider " +
                    "authority doesn't match '$authority'", e)
        }
    }

    /**
     * Walk through ContextWrapper layers to find the underlying Activity.
     * Needed because Compose sometimes hands us a ContextWrapper (e.g.
     * when a theme overlay is applied) rather than the Activity itself.
     */
    private fun Context.findActivity(): Activity? {
        var ctx = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}