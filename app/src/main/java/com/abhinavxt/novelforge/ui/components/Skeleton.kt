package com.abhinavxt.novelforge.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.ui.theme.LocalEinkMode

/**
 * Skeleton placeholders: grey blocks shaped like the content that is
 * loading, so the screen does not jump when the real thing arrives.
 *
 * One shimmer clock per [SkeletonContainer] drives every block inside it
 * (blocks read it in the draw phase, so a sweep never recomposes), instead
 * of 30 separate infinite transitions.
 */
private val LocalShimmer = compositionLocalOf<State<Float>> { mutableFloatStateOf(0f) }

/**
 * Wraps a skeleton layout. Collapses everything inside to ONE "Loading"
 * node for TalkBack, so it does not read dozens of empty blocks.
 */
@Composable
fun SkeletonContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // E-ink panels ghost on motion: no clock at all, blocks stay static.
    val eink = LocalEinkMode.current
    val progress: State<Float> = if (eink) {
        remember { mutableFloatStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1300, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "skeletonShimmer"
        )
    }
    CompositionLocalProvider(LocalShimmer provides progress) {
        Box(modifier = modifier.clearAndSetSemantics { contentDescription = "Loading" }) {
            content()
        }
    }
}

/** A rounded placeholder block. Use inside a [SkeletonContainer]. */
@Composable
fun Modifier.skeleton(shape: Shape = RoundedCornerShape(6.dp)): Modifier {
    val eink = LocalEinkMode.current
    val progress = LocalShimmer.current
    val base = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    val highlight = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    return this
        .clip(shape)
        .drawBehind {
            drawRect(base)
            if (eink) {
                // Flat block with a thin outline so it still reads as a box.
                val inset = 0.5.dp.toPx()
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - 2 * inset, size.height - 2 * inset),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx())
                )
            } else {
                // A soft band sliding left to right across the block.
                val band = size.width * 0.6f
                val x = -band + (size.width + band) * progress.value
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, highlight, Color.Transparent),
                        start = Offset(x, 0f),
                        end = Offset(x + band, 0f)
                    )
                )
            }
        }
}

/**
 * One line of text: a block of [blockHeight] centred in a box as tall as
 * the real line, so stacked lines keep the real layout's rhythm.
 */
@Composable
fun SkeletonLine(
    widthFraction: Float,
    lineHeight: Dp,
    modifier: Modifier = Modifier,
    blockHeight: Dp = lineHeight - 8.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(lineHeight),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(blockHeight).skeleton())
    }
}

/** Placeholder for a search result card (see SearchScreen's NovelListItem). */
@Composable
private fun SkeletonNovelRow() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Box(Modifier.size(60.dp, 80.dp).skeleton())
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // Mirrors the common row: a one-line title, author, source badge.
                // Most results have no description, and sizing for one made
                // every row shrink when the list arrived.
                SkeletonLine(0.7f, 24.dp)
                Spacer(Modifier.height(2.dp))
                SkeletonLine(0.4f, 20.dp)
                Spacer(Modifier.height(2.dp))
                SkeletonLine(0.25f, 16.dp, blockHeight = 10.dp)
            }
        }
    }
}

/** Result list placeholder: [rows] cards, same spacing as the real list. */
@Composable
fun SkeletonNovelList(rows: Int = 6, modifier: Modifier = Modifier) {
    SkeletonContainer(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(rows) { SkeletonNovelRow() }
        }
    }
}

/** Cover-grid placeholder for the catalog browser (same grid as Browsescreen). */
@Composable
fun SkeletonCoverGrid(cards: Int = 12, modifier: Modifier = Modifier) {
    SkeletonContainer(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 105.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize()
        ) {
            items((0 until cards).toList()) {
                Card {
                    Column {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).skeleton(RoundedCornerShape(0.dp)))
                        Column(Modifier.padding(6.dp)) {
                            SkeletonLine(1f, 16.dp, blockHeight = 10.dp)
                            SkeletonLine(0.6f, 16.dp, blockHeight = 10.dp)
                        }
                    }
                }
            }
        }
    }
}

/** OPDS entry placeholder (see OpdsScreen's EntryRow, book variant). */
@Composable
fun SkeletonOpdsList(rows: Int = 8, modifier: Modifier = Modifier) {
    SkeletonContainer(modifier = modifier.fillMaxSize()) {
        Column {
            repeat(rows) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Box(Modifier.size(60.dp, 88.dp).skeleton())
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        SkeletonLine(0.8f, 20.dp)
                        SkeletonLine(0.5f, 20.dp)
                    }
                }
                HorizontalDivider(Modifier.padding(start = 92.dp))
            }
        }
    }
}

/**
 * Novel detail placeholder: header (cover, title, author, two buttons),
 * a 4-line synopsis and 5 chapter rows. Sizes copy NovelDetailHeader and
 * ChapterListItem. The shared cover element is deliberately NOT tagged
 * here: the real header owns it, and a second one would clash.
 */
@Composable
fun SkeletonNovelDetail(modifier: Modifier = Modifier) {
    SkeletonContainer(modifier = modifier.fillMaxSize()) {
        Column {
            Column(Modifier.padding(top = 60.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.size(110.dp, 160.dp).skeleton(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f).padding(top = 24.dp)) {
                        SkeletonLine(0.9f, 28.dp, blockHeight = 18.dp)
                        SkeletonLine(0.6f, 28.dp, blockHeight = 18.dp)
                        Spacer(Modifier.height(4.dp))
                        SkeletonLine(0.45f, 20.dp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(2) { Box(Modifier.size(56.dp, 24.dp).skeleton()) }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    repeat(2) { Box(Modifier.weight(1f).height(40.dp).skeleton(RoundedCornerShape(20.dp))) }
                }
            }
            // Synopsis: heading + 4 body lines
            Column(Modifier.padding(16.dp)) {
                SkeletonLine(0.35f, 24.dp)
                Spacer(Modifier.height(8.dp))
                SkeletonLine(1f, 20.dp)
                SkeletonLine(1f, 20.dp)
                SkeletonLine(1f, 20.dp)
                SkeletonLine(0.6f, 20.dp)
            }
            repeat(5) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp).height(32.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(32.dp, 24.dp).skeleton())
                        Spacer(Modifier.width(12.dp))
                        SkeletonLine(0.7f, 20.dp)
                    }
                }
            }
        }
    }
}
