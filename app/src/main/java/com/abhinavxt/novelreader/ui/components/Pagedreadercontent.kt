package com.abhinavxt.novelreader.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhinavxt.novelreader.data.model.PageTransition
import com.abhinavxt.novelreader.data.model.ReaderSettings
import com.abhinavxt.novelreader.NovelReaderApplication
import com.abhinavxt.novelreader.VolumeKeyEvent
import com.abhinavxt.novelreader.ui.screens.ThemeColors
import com.abhinavxt.novelreader.ui.screens.toFontFamily
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Paged reader — displays chapter content as horizontal pages.
 *
 * HOW PAGINATION WORKS:
 * Unlike a web view that can measure rendered text height, Compose doesn't
 * give us a "how many lines fit on screen" API directly. So we use the
 * TextMeasurer to pre-calculate how many paragraphs fit on each page:
 *
 *  1. Measure the available content height (screen height minus top/bottom bars minus padding)
 *  2. For each paragraph, measure its rendered height using TextMeasurer
 *  3. Greedily fill pages: keep adding paragraphs until the next one would overflow
 *  4. Store the result as a List<List<String>> (pages → paragraphs)
 *
 * This recalculates whenever font size, line spacing, margins, or screen
 * dimensions change. The pager state tracks the current page index, and
 * we map that back to paragraph indices for position saving.
 *
 * PAGE TRANSITIONS:
 * Applied via graphicsLayer on each page inside the HorizontalPager.
 * - SLIDE: default pager behavior (no custom transform needed)
 * - FADE: alpha transition based on page offset
 * - CURL: rotation + perspective transform to simulate a page curl
 * - NONE: instant snap, no animation
 *
 * TAP ZONES:
 * Left 30% → previous page, Right 30% → next page, Center 40% → toggle immersive
 */
@Composable
fun PagedReaderContent(
    paragraphs: List<String>,
    settings: ReaderSettings,
    colors: ThemeColors,
    savedParagraphIndex: Int,
    onParagraphIndexChanged: (Int) -> Unit,
    onToggleImmersive: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val textMeasurer = rememberTextMeasurer()
    val coroutineScope = rememberCoroutineScope()

    // Calculate the available content area dimensions in pixels
    // Subtract estimated padding: top bar ~56dp, bottom bar ~48dp, content padding 16dp each side
    val screenHeightDp = configuration.screenHeightDp
    val contentHeightDp = screenHeightDp - 120  // Rough bar + padding space
    val contentWidthDp = configuration.screenWidthDp - (settings.horizontalMargin * 2)

    val contentHeightPx = with(density) { contentHeightDp.dp.toPx() }
    val contentWidthPx = with(density) { contentWidthDp.dp.toPx() }

    // Build the text style used for measurement — must match rendering exactly
    val textStyle = remember(settings.fontSize, settings.lineSpacing, settings.font) {
        TextStyle(
            fontSize = settings.fontSize.sp,
            lineHeight = (settings.fontSize * settings.lineSpacing).sp,
            fontFamily = settings.font.toFontFamily()
        )
    }

    // ── Pagination: split paragraphs into pages ─────────────────
    // Recalculates when any display parameter changes.
    val pages = remember(
        paragraphs, settings.fontSize, settings.lineSpacing,
        settings.font, settings.horizontalMargin,
        contentHeightPx, contentWidthPx
    ) {
        paginateParagraphs(
            paragraphs = paragraphs,
            textMeasurer = textMeasurer,
            textStyle = textStyle,
            maxHeightPx = contentHeightPx,
            maxWidthPx = contentWidthPx,
            paragraphSpacingPx = with(density) { 16.dp.toPx() }
        )
    }

    // Find which page contains the saved paragraph index
    val initialPage = remember(pages, savedParagraphIndex) {
        findPageForParagraph(pages, savedParagraphIndex)
    }

    // Guard: if no pages were generated, show a fallback
    if (pages.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No content to display",
                color = colors.secondaryText,
                fontSize = 14.sp
            )
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, pages.size - 1),
        pageCount = { pages.size }
    )

    // Track page changes → map back to paragraph index for position saving
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { pageIndex ->
            if (pageIndex in pages.indices) {
                val firstParagraphOnPage = pages[pageIndex].firstOrNull()
                val paragraphIndex = paragraphs.indexOf(firstParagraphOnPage)
                if (paragraphIndex >= 0) {
                    onParagraphIndexChanged(paragraphIndex)
                }
            }
        }
    }

    // ── Volume key navigation (paged mode) ──────────────────────
    // When enabled, volume-down goes to next page, volume-up to previous.
    // At the last/first page, navigates to the next/previous chapter.
    if (settings.volumeKeyNavigation) {
        val context = LocalContext.current
        val app = context.applicationContext as? NovelReaderApplication
        if (app != null) {
            LaunchedEffect(Unit) {
                app.volumeKeyEvents.collect { event ->
                    when (event) {
                        VolumeKeyEvent.DOWN -> {
                            if (pagerState.currentPage < pages.size - 1) {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            } else if (canGoNext) {
                                onNextChapter()
                            }
                        }
                        VolumeKeyEvent.UP -> {
                            if (pagerState.currentPage > 0) {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            } else if (canGoPrevious) {
                                onPreviousChapter()
                            }
                        }
                    }
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // beyondViewportPageCount keeps adjacent pages composed for smooth transitions
            beyondViewportPageCount = 1
        ) { pageIndex ->
            val pageOffset = (pagerState.currentPage - pageIndex) +
                    pagerState.currentPageOffsetFraction

            // Apply page transition animation based on user preference
            val pageModifier = when (settings.pageTransition) {
                PageTransition.FADE -> Modifier
                    .fillMaxSize()
                    .alpha(1f - abs(pageOffset).coerceIn(0f, 1f))

                PageTransition.CURL -> Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Simulate a page curl: rotate around the Y axis with perspective
                        val rotation = pageOffset * -30f
                        rotationY = rotation
                        cameraDistance = 12f * density.density
                        // Slight scale-down for depth
                        val scale = 1f - abs(pageOffset) * 0.1f
                        scaleX = scale.coerceIn(0.85f, 1f)
                        scaleY = scale.coerceIn(0.85f, 1f)
                    }

                PageTransition.NONE -> Modifier.fillMaxSize()

                // SLIDE: default pager behavior, no custom transform
                PageTransition.SLIDE -> Modifier.fillMaxSize()
            }

            // ── Render the page content ─────────────────────────
            // Tap handling is integrated into each page's Box via
            // detectTapGestures — left 30% prev, right 30% next,
            // center 40% toggles immersive. Because this is on the
            // page content itself (not an overlay), HorizontalPager's
            // swipe gesture works naturally alongside taps.
            Box(
                modifier = pageModifier
                    .background(colors.background)
                    .padding(horizontal = settings.horizontalMargin.dp, vertical = 16.dp)
                    .pointerInput(pagerState.currentPage) {
                        detectTapGestures { offset ->
                            val screenWidth = size.width.toFloat()
                            val tapX = offset.x
                            when {
                                // Left 30% — previous page or previous chapter
                                tapX < screenWidth * 0.3f -> {
                                    if (pagerState.currentPage > 0) {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                        }
                                    } else if (canGoPrevious) {
                                        onPreviousChapter()
                                    }
                                }
                                // Right 30% — next page or next chapter
                                tapX > screenWidth * 0.7f -> {
                                    if (pagerState.currentPage < pages.size - 1) {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        }
                                    } else if (canGoNext) {
                                        onNextChapter()
                                    }
                                }
                                // Center 40% — toggle immersive mode
                                else -> {
                                    onToggleImmersive()
                                }
                            }
                        }
                    }
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Render each paragraph on this page
                    val pageParagraphs = pages.getOrNull(pageIndex) ?: emptyList()
                    pageParagraphs.forEach { paragraph ->
                        Text(
                            text = paragraph,
                            color = colors.text,
                            fontSize = settings.fontSize.sp,
                            fontFamily = settings.font.toFontFamily(),
                            lineHeight = (settings.fontSize * settings.lineSpacing).sp,
                            textAlign = TextAlign.Justify,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }
                }

                // ── Page number indicator at bottom ─────────────
                Text(
                    text = "${pageIndex + 1} / ${pages.size}",
                    color = colors.secondaryText.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Pagination logic
// ═══════════════════════════════════════════════════════════════

/**
 * Split a list of paragraphs into pages that fit within [maxHeightPx].
 *
 * Uses TextMeasurer to calculate the rendered height of each paragraph,
 * then greedily fills pages. A paragraph is never split across pages
 * (this is a deliberate simplification — splitting mid-paragraph requires
 * much more complex layout logic and isn't worth it for novel content
 * where paragraphs are typically short).
 *
 * If a single paragraph is taller than the page, it gets its own page
 * (will overflow, but better than being invisible).
 */
private fun paginateParagraphs(
    paragraphs: List<String>,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    maxHeightPx: Float,
    maxWidthPx: Float,
    paragraphSpacingPx: Float
): List<List<String>> {
    if (paragraphs.isEmpty()) return emptyList()

    val pages = mutableListOf<List<String>>()
    var currentPage = mutableListOf<String>()
    var currentHeight = 0f

    val constraints = Constraints(
        maxWidth = maxWidthPx.toInt().coerceAtLeast(100)
    )

    for (paragraph in paragraphs) {
        // Measure the paragraph's rendered height
        val measurement = textMeasurer.measure(
            text = paragraph,
            style = textStyle,
            constraints = constraints
        )
        val paragraphHeight = measurement.size.height.toFloat() + paragraphSpacingPx

        // Check if adding this paragraph would overflow the page
        if (currentPage.isNotEmpty() && currentHeight + paragraphHeight > maxHeightPx) {
            // Save current page and start a new one
            pages.add(currentPage.toList())
            currentPage = mutableListOf()
            currentHeight = 0f
        }

        currentPage.add(paragraph)
        currentHeight += paragraphHeight
    }

    // Don't forget the last page
    if (currentPage.isNotEmpty()) {
        pages.add(currentPage.toList())
    }

    return pages
}

/**
 * Find which page a given paragraph index falls on.
 * Returns 0 if not found (safe default for the pager).
 */
private fun findPageForParagraph(
    pages: List<List<String>>,
    targetParagraphIndex: Int
): Int {
    var paragraphCounter = 0
    for ((pageIndex, page) in pages.withIndex()) {
        if (targetParagraphIndex < paragraphCounter + page.size) {
            return pageIndex
        }
        paragraphCounter += page.size
    }
    return 0
}