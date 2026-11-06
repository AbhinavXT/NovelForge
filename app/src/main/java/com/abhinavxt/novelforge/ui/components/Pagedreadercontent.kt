package com.abhinavxt.novelforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import coil.compose.AsyncImage
import com.abhinavxt.novelforge.data.epub.BlockStyle
import com.abhinavxt.novelforge.data.epub.Footnote
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhinavxt.novelforge.data.model.PageTransition
import com.abhinavxt.novelforge.data.model.ReaderSettings
import com.abhinavxt.novelforge.data.model.TapAction
import com.abhinavxt.novelforge.NovelReaderApplication
import com.abhinavxt.novelforge.VolumeKeyEvent
import com.abhinavxt.novelforge.ui.screens.ThemeColors
import com.abhinavxt.novelforge.ui.screens.toFontFamily
import com.abhinavxt.novelforge.util.MatchHighlight
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
 *  4. Store the result as pages of [PageItem]s (paragraph indexes and
 *     the book's pictures, each measured at the size it is drawn)
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
    /** The book's own formatting; null shows plain text. */
    formatting: ReaderFormatting? = null,
    onFootnoteClick: ((Footnote) -> Unit)? = null,
    settings: ReaderSettings,
    colors: ThemeColors,
    savedParagraphIndex: Int,
    // ── In-book find (owned by ReaderContent) ───────────────────
    // searchQuery highlights every occurrence on rendered pages;
    // activeSearchParagraph/Range mark the current match; a change
    // to searchJumpRequest (monotonic id, paragraphIndex) turns the
    // pager to the page containing that paragraph.
    searchQuery: String = "",
    activeSearchParagraph: Int = -1,
    activeSearchRange: IntRange? = null,
    searchJumpRequest: Pair<Int, Int>? = null,
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
            fontFamily = settings.font.toFontFamily(),
            textAlign = TextAlign.Justify
        )
    }
    val noteColor = MaterialTheme.colorScheme.primary
    val quoteIndentPx = with(density) { QUOTE_INDENT_DP.dp.toPx() }
    val imageSpacingPx = with(density) { IMAGE_SPACING.toPx() }

    // ── Pagination: split paragraphs into pages ─────────────────
    // Recalculates when any display parameter changes.
    val pages = remember(
        paragraphs, formatting, settings.fontSize, settings.lineSpacing,
        settings.font, settings.horizontalMargin,
        contentHeightPx, contentWidthPx
    ) {
        paginateParagraphs(
            paragraphs = paragraphs,
            measureParagraph = { index, widthPx ->
                val format = formatting?.get(index)
                textMeasurer.measure(
                    // Styles change widths (bold) and heights (superscript),
                    // so measure what is drawn. Links don't affect layout.
                    text = buildAnnotatedString {
                        append(paragraphs[index])
                        addBookFormatting(format, noteColor, null)
                    },
                    style = textStyle.forBlock(format?.block),
                    constraints = Constraints(
                        maxWidth = (widthPx - if (format?.block == BlockStyle.QUOTE) quoteIndentPx else 0f)
                            .toInt().coerceAtLeast(100)
                    )
                ).size.height.toFloat()
            },
            imagesBefore = { formatting?.get(it)?.imagesBefore.orEmpty() },
            imagesAfter = { formatting?.get(it)?.imagesAfter.orEmpty() },
            imageSize = { image -> imageDisplaySize(image, contentWidthPx, contentHeightPx - imageSpacingPx, density) },
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

    // ── In-book find support ────────────────────────────────────
    // Pages hold paragraph indexes, so a rendered paragraph knows its
    // chapter index (for active-match emphasis) without indexOf lookups,
    // which break on duplicate paragraph strings.
    // Turn to the page containing the requested match paragraph.
    // Keyed on the whole request pair — its monotonic id makes
    // repeat jumps to the same paragraph re-trigger.
    LaunchedEffect(searchJumpRequest) {
        searchJumpRequest?.let { (_, paragraphIndex) ->
            val target = findPageForParagraph(pages, paragraphIndex)
            pagerState.animateScrollToPage(target.coerceIn(0, pages.size - 1))
        }
    }

    // Track page changes → map back to paragraph index for position saving
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { pageIndex ->
            if (pageIndex in pages.indices) {
                firstParagraphOf(pages, pageIndex)?.let(onParagraphIndexChanged)
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
                    .pointerInput(pagerState.currentPage, settings.tapZoneLayout) {
                        detectTapGestures { offset ->
                            // Zone geometry from the user's layout —
                            // same resolver as scroll mode.
                            val action = settings.tapZoneLayout.resolve(
                                xFrac = offset.x / size.width,
                                yFrac = offset.y / size.height
                            )
                            when (action) {
                                // Previous page or previous chapter
                                TapAction.BACK -> {
                                    if (pagerState.currentPage > 0) {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                        }
                                    } else if (canGoPrevious) {
                                        onPreviousChapter()
                                    }
                                }
                                // Next page or next chapter
                                TapAction.FORWARD -> {
                                    if (pagerState.currentPage < pages.size - 1) {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        }
                                    } else if (canGoNext) {
                                        onNextChapter()
                                    }
                                }
                                // Toggle immersive mode
                                TapAction.MENU -> {
                                    onToggleImmersive()
                                }
                            }
                        }
                    }
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Render this page's paragraphs and pictures
                    pages.getOrNull(pageIndex).orEmpty().forEach { item ->
                        when (item) {
                            is PageItem.Picture -> {
                                val (w, h) = item.sizePx
                                AsyncImage(
                                    model = item.image.file,
                                    contentDescription = item.image.alt,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .padding(bottom = IMAGE_SPACING)
                                        .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                                )
                            }
                            is PageItem.Paragraph -> {
                                val index = item.index
                                val paragraph = paragraphs[index]
                                val format = formatting?.get(index)
                                // Book formatting first, then in-book find
                                // marks on top; the active match gets
                                // inverted emphasis only on the paragraph
                                // that actually holds it.
                                val annotated = buildAnnotatedString {
                                    append(paragraph)
                                    addBookFormatting(format, noteColor, onFootnoteClick)
                                    if (searchQuery.length >= MatchHighlight.MIN_QUERY_LENGTH) {
                                        with(MatchHighlight) {
                                            addSearchSpans(
                                                ranges = MatchHighlight.findOccurrences(paragraph, searchQuery),
                                                activeRange = if (index == activeSearchParagraph) activeSearchRange else null,
                                                textColor = colors.text,
                                                backgroundColor = colors.background
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = annotated,
                                    color = colors.text,
                                    style = textStyle.forBlock(format?.block),
                                    modifier = Modifier.padding(
                                        start = if (format?.block == BlockStyle.QUOTE) QUOTE_INDENT_DP.dp else 0.dp,
                                        bottom = 16.dp
                                    )
                                )
                            }
                        }
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

/** One thing on a page: a paragraph (by index) or one of the book's pictures. */
internal sealed interface PageItem {
    data class Paragraph(val index: Int) : PageItem
    /** [sizePx] is the (width, height) it is drawn at. */
    data class Picture(val image: ReaderImage, val sizePx: Pair<Float, Float>) : PageItem
}

/**
 * Split a chapter into pages that fit within [maxHeightPx].
 *
 * Greedily fills pages with measured items. A paragraph is never split
 * across pages (this is a deliberate simplification — splitting
 * mid-paragraph requires much more complex layout logic and isn't worth it
 * for novel content where paragraphs are typically short). Pictures are
 * items of their own, placed before (or, at the end, after) the paragraph
 * they belong to; [imageSize] already caps them to the page.
 *
 * If a single paragraph is taller than the page, it gets its own page
 * (will overflow, but better than being invisible).
 */
internal fun paginateParagraphs(
    paragraphs: List<String>,
    measureParagraph: (index: Int, maxWidthPx: Float) -> Float,
    imagesBefore: (Int) -> List<ReaderImage>,
    imagesAfter: (Int) -> List<ReaderImage>,
    imageSize: (ReaderImage) -> Pair<Float, Float>,
    maxHeightPx: Float,
    maxWidthPx: Float,
    paragraphSpacingPx: Float
): List<List<PageItem>> {
    if (paragraphs.isEmpty()) return emptyList()

    val pages = mutableListOf<List<PageItem>>()
    var currentPage = mutableListOf<PageItem>()
    var currentHeight = 0f

    fun place(item: PageItem, height: Float) {
        // Check if adding this item would overflow the page
        if (currentPage.isNotEmpty() && currentHeight + height > maxHeightPx) {
            // Save current page and start a new one
            pages.add(currentPage.toList())
            currentPage = mutableListOf()
            currentHeight = 0f
        }
        currentPage.add(item)
        currentHeight += height
    }

    fun placeImages(images: List<ReaderImage>) = images.forEach { image ->
        val size = imageSize(image)
        place(PageItem.Picture(image, size), size.second + paragraphSpacingPx)
    }

    for (index in paragraphs.indices) {
        placeImages(imagesBefore(index))
        place(PageItem.Paragraph(index), measureParagraph(index, maxWidthPx) + paragraphSpacingPx)
        placeImages(imagesAfter(index))
    }

    // Don't forget the last page
    if (currentPage.isNotEmpty()) {
        pages.add(currentPage.toList())
    }

    return pages
}

/**
 * The paragraph a page stands for when saving the reading position: its
 * first paragraph, or for a page of pictures only, the paragraph after them.
 */
internal fun firstParagraphOf(pages: List<List<PageItem>>, pageIndex: Int): Int? {
    for (page in pageIndex until pages.size) {
        pages[page].firstNotNullOfOrNull { (it as? PageItem.Paragraph)?.index }?.let { return it }
    }
    // Pictures after the last paragraph.
    for (page in pageIndex downTo 0) {
        pages[page].lastOrNull { it is PageItem.Paragraph }?.let { return (it as PageItem.Paragraph).index }
    }
    return null
}

/**
 * Find which page a given paragraph index falls on.
 * Returns 0 if not found (safe default for the pager).
 */
internal fun findPageForParagraph(
    pages: List<List<PageItem>>,
    targetParagraphIndex: Int
): Int {
    for ((pageIndex, page) in pages.withIndex()) {
        if (page.any { it is PageItem.Paragraph && it.index >= targetParagraphIndex }) {
            return pageIndex
        }
    }
    return 0
}
