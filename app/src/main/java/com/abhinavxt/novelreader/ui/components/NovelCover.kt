package com.abhinavxt.novelreader.ui.components

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

/**
 * Reusable novel cover image with:
 *  - Rounded corners (8dp) and elevation shadow
 *  - Smooth crossfade (300ms) instead of pop-in
 *  - Gradient placeholder when no cover URL exists
 *  - Optional reading progress bar overlay at the bottom
 *
 * Use this everywhere a cover is shown: HomeScreen, LibraryScreen,
 * NovelDetailScreen, ContinueReadingCard, SearchResults.
 *
 * @param coverUrl  URL string or local file path (starts with "/"). Null = placeholder.
 * @param width     Cover width. Default 100dp.
 * @param height    Cover height. Default 140dp.
 * @param progress  Reading progress 0f..1f. If null, no progress bar shown.
 */
@Composable
fun NovelCover(
    coverUrl: String?,
    modifier: Modifier = Modifier,
    width: Dp = 100.dp,
    height: Dp = 140.dp,
    progress: Float? = null
) {
    // Resolve URL vs local file path
    val imageModel = remember(coverUrl) {
        when {
            coverUrl == null -> null
            coverUrl.startsWith("/") -> File(coverUrl)
            else -> coverUrl
        }
    }

    Card(
        modifier = modifier.size(width, height),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box {
            if (imageModel != null) {
                // Add Referer header for sources that require it (e.g. Primordial Translation).
                // Extracts origin from URL — mimics browser default Referrer-Policy.
                val referer = if (coverUrl != null && !coverUrl.startsWith("/")) {
                    try {
                        val uri = android.net.Uri.parse(coverUrl)
                        "${uri.scheme}://${uri.host}"
                    } catch (_: Exception) { null }
                } else null

                android.util.Log.d("NovelCover", "Loading cover: url=$coverUrl, referer=$referer, imageModel=$imageModel")

                val request = ImageRequest.Builder(LocalContext.current)
                    .data(imageModel)
                    .crossfade(300)
                    .listener(
                        onStart = { android.util.Log.d("NovelCover", "Coil START: $coverUrl") },
                        onSuccess = { _, _ -> android.util.Log.d("NovelCover", "Coil SUCCESS: $coverUrl") },
                        onError = { _, result -> android.util.Log.e("NovelCover", "Coil ERROR: $coverUrl — ${result.throwable}") }
                    )

                if (referer != null) {
                    request.addHeader("Referer", referer)
                }

                AsyncImage(
                    model = request.build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Gradient placeholder — more polished than flat grey
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.colorScheme.surface
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Reading progress overlay — thin bar at bottom of cover
            if (progress != null && progress > 0f) {
                val animatedProgress by animateFloatAsState(
                    targetValue = progress.coerceIn(0f, 1f),
                    animationSpec = tween(durationMillis = 600, easing = EaseOutCubic),
                    label = "coverProgress"
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Black.copy(alpha = 0.3f)
                )
            }
        }
    }
}