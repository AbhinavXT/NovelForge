package com.abhinavxt.novelforge.widget

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.action.actionStartActivity
import android.content.Intent
import android.content.ComponentName
import java.io.File

/**
 * Continue-reading widget.
 *
 * ── Sizing ──
 * Android Home screens put widgets on a grid (typically 4 columns, 4-5
 * rows). A single cell is ~70dp square. We support three sizes:
 *   - Small (2x1):  cover thumbnail + "Continue" button. Minimal footprint.
 *   - Medium (4x1): cover + title + chapter + button. Balanced.
 *   - Large (4x2):  cover + title + chapter + progress bar + button.
 *                   The "full" version with the motivational progress ring.
 *
 * Glance's SizeMode.Responsive picks the right layout based on the user's
 * chosen widget size at placement time.
 *
 * ── Tap behavior ──
 * The whole widget is tappable — it deep-links into the reader screen
 * with (novelId, chapterId, chapterUrl, novelUrl). MainActivity unpacks
 * these extras and navigates on launch. If the widget is in empty state,
 * tapping opens the app's main launcher intent (user lands on Home).
 */
class ContinueReadingWidget : GlanceAppWidget() {

    // Using Preferences state — matches what our WidgetStateRepository writes.
    // Glance will load the same DataStore file on demand.
    override val stateDefinition: GlanceStateDefinition<*> =
        PreferencesGlanceStateDefinition

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            SMALL_SIZE,
            MEDIUM_SIZE,
            LARGE_SIZE
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Pull our custom widget state repository — it reads the same
        // DataStore Glance's PreferencesGlanceStateDefinition does, but
        // parses it into our sealed WidgetState type.
        val repo = WidgetStateRepository(context)
        val state = repo.getStateOnce()

        provideContent {
            GlanceTheme {
                WidgetContent(state = state)
            }
        }
    }

    @Composable
    private fun WidgetContent(state: WidgetStateRepository.WidgetState) {
        when (state) {
            is WidgetStateRepository.WidgetState.Empty -> EmptyStateLayout()
            is WidgetStateRepository.WidgetState.HasNovel -> ReadingLayout(state)
        }
    }

    // ─── Empty state ────────────────────────────────────────────────

    /**
     * What we show when the user has no reading history. Single tappable
     * prompt that opens the app.
     */
    @Composable
    private fun EmptyStateLayout() {
        val context = LocalContext.current
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(16.dp)
                .clickable(actionStartActivity(launchAppIntent(context))),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "NOVELFORGE",
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                )
                Spacer(GlanceModifier.height(6.dp))
                Text(
                    text = "Tap to start reading",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                )
            }
        }
    }

    // ─── Reading layout (three responsive variants) ─────────────────

    @Composable
    private fun ReadingLayout(state: WidgetStateRepository.WidgetState.HasNovel) {
        val context = LocalContext.current
        val size = androidx.glance.LocalSize.current

        val clickAction = actionStartActivity(resumeReadingIntent(context, state))

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .clickable(clickAction)
        ) {
            when {
                // Small: just cover + a tiny continue label. Single row.
                size.width < MEDIUM_SIZE.width -> SmallLayout(state)

                // Large: cover + text + progress bar. Two-row-ish.
                size.height >= LARGE_SIZE.height -> LargeLayout(state)

                // Medium: default middle ground.
                else -> MediumLayout(state)
            }
        }
    }

    @Composable
    private fun SmallLayout(state: WidgetStateRepository.WidgetState.HasNovel) {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverBlock(state = state, size = 56.dp)
            Spacer(GlanceModifier.width(10.dp))
            Column(
                modifier = GlanceModifier.fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CONTINUE",
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                )
                Text(
                    text = "Ch. ${state.chapterNumber}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                    maxLines = 1
                )
            }
        }
    }

    @Composable
    private fun MediumLayout(state: WidgetStateRepository.WidgetState.HasNovel) {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverBlock(state = state, size = 72.dp)
            Spacer(GlanceModifier.width(12.dp))
            Column(
                modifier = GlanceModifier.defaultWeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CONTINUE READING",
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                )
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    text = state.novelTitle,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    ),
                    maxLines = 1
                )
                Text(
                    text = "Ch. ${state.chapterNumber} · ${state.chapterTitle}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp
                    ),
                    maxLines = 1
                )
            }
        }
    }

    @Composable
    private fun LargeLayout(state: WidgetStateRepository.WidgetState.HasNovel) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Top row: cover + title/chapter info
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                CoverBlock(state = state, size = 80.dp)
                Spacer(GlanceModifier.width(12.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = "CONTINUE READING",
                        style = TextStyle(
                            color = GlanceTheme.colors.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        text = state.novelTitle,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        ),
                        maxLines = 2
                    )
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        text = "Ch. ${state.chapterNumber} · ${state.chapterTitle}",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 12.sp
                        ),
                        maxLines = 1
                    )
                }
            }

            Spacer(GlanceModifier.height(10.dp))

            // Progress bar — the motivational "you're X% through" element.
            // LinearProgressIndicator is one of Glance's composables that
            // maps cleanly to RemoteViews ProgressBar.
            LinearProgressIndicator(
                progress = state.progress,
                modifier = GlanceModifier.fillMaxWidth(),
                color = GlanceTheme.colors.primary,
                backgroundColor = GlanceTheme.colors.surfaceVariant
            )
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = "${state.chapterNumber} / ${state.totalChapters} chapters",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp
                )
            )
        }
    }

    /**
     * Cover image block — either the loaded bitmap or a gradient
     * placeholder with the first letter of the novel's title.
     */
    @Composable
    private fun CoverBlock(
        state: WidgetStateRepository.WidgetState.HasNovel,
        size: androidx.compose.ui.unit.Dp
    ) {
        val bitmap = remember(state.coverPath) {
            state.coverPath?.let { path ->
                try {
                    val f = File(path)
                    if (f.exists()) BitmapFactory.decodeFile(path) else null
                } catch (e: Exception) {
                    null
                }
            }
        }

        // Image ratio: 2:3 (portrait book cover).
        val coverWidth = size
        val coverHeight = size * 1.4f

        if (bitmap != null) {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = state.novelTitle,
                modifier = GlanceModifier
                    .size(coverWidth, coverHeight)
                    .cornerRadius(8.dp)
            )
        } else {
            // Gradient fallback — still glanceable, still pretty.
            // Glance can't draw gradients directly, so we use a solid
            // accent color as background and overlay the first letter.
            Box(
                modifier = GlanceModifier
                    .size(coverWidth, coverHeight)
                    .background(GlanceTheme.colors.primary)
                    .cornerRadius(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.novelTitle.take(1).uppercase(),
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    )
                )
            }
        }
    }

    companion object {
        // Responsive size breakpoints. Widgets that land smaller than SMALL
        // will still get the SMALL layout (Glance picks closest).
        private val SMALL_SIZE = androidx.compose.ui.unit.DpSize(140.dp, 70.dp)
        private val MEDIUM_SIZE = androidx.compose.ui.unit.DpSize(240.dp, 70.dp)
        private val LARGE_SIZE = androidx.compose.ui.unit.DpSize(240.dp, 160.dp)

        // ─── Intents ────────────────────────────────────────────────
        //
        // Both intents target MainActivity. The reading intent adds extras
        // that MainActivity's existing deep-link handling parses.
        //
        // IMPORTANT: Both intents use the same ComponentName so Android
        // can recognize that tapping the widget should bring an existing
        // MainActivity to the foreground rather than creating a new one.

        /** Intent that just launches the app — used for empty state. */
        fun launchAppIntent(context: Context): Intent {
            return Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(
                    context.packageName,
                    "com.abhinavxt.novelforge.MainActivity"
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }

        /** Intent that opens the reader at the widget's novel + chapter. */
        fun resumeReadingIntent(
            context: Context,
            state: WidgetStateRepository.WidgetState.HasNovel
        ): Intent {
            return Intent(Intent.ACTION_VIEW).apply {
                component = ComponentName(
                    context.packageName,
                    "com.abhinavxt.novelforge.MainActivity"
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // Extras read by MainActivity's deep-link handler.
                putExtra(EXTRA_NOVEL_ID, state.novelId)
                putExtra(EXTRA_NOVEL_URL, state.novelUrl)
                putExtra(EXTRA_CHAPTER_ID, state.chapterId)
                putExtra(EXTRA_CHAPTER_URL, state.chapterUrl)
            }
        }

        // Extra keys — MainActivity reads these in its deep-link LaunchedEffect.
        const val EXTRA_NOVEL_ID = "com.abhinavxt.novelforge.widget.NOVEL_ID"
        const val EXTRA_NOVEL_URL = "com.abhinavxt.novelforge.widget.NOVEL_URL"
        const val EXTRA_CHAPTER_ID = "com.abhinavxt.novelforge.widget.CHAPTER_ID"
        const val EXTRA_CHAPTER_URL = "com.abhinavxt.novelforge.widget.CHAPTER_URL"
    }
}

/**
 * The AppWidgetProvider-style receiver that Android instantiates when the
 * widget is placed on the home screen. Glance provides a one-line
 * receiver base class; we just plug in our widget.
 */
class ContinueReadingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContinueReadingWidget()
}