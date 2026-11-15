package com.abhinavxt.novelforge.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.ui.theme.LocalEinkMode

/**
 * A thin reading-progress bar. Drawn by hand rather than with
 * LinearProgressIndicator so it never animates (the e-ink panel ghosts on
 * motion) and so the e-ink colours can be exact: black fill on a white
 * track, which stays readable over any cover. A tinted bar washes out to
 * grey on that display.
 */
@Composable
fun BookProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp
) {
    val eink = LocalEinkMode.current
    val track = if (eink) Color.White else Color.Black.copy(alpha = 0.3f)
    val fill = if (eink) Color.Black else MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(track)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(fill)
        )
    }
}

/** The bar for a cover's bottom edge, clipped to the cover's rounded corners. */
@Composable
fun CoverProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val eink = LocalEinkMode.current
    BookProgressBar(
        fraction = fraction,
        height = if (eink) 5.dp else 3.dp,
        modifier = modifier.clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
    )
}

/** Small check mark shown on a finished book in place of a full bar. */
@Composable
fun FinishedBadge(modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val eink = LocalEinkMode.current
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = if (eink) Color.White else MaterialTheme.colorScheme.primary,
        contentColor = if (eink) Color.Black else MaterialTheme.colorScheme.onPrimary,
        border = if (eink) BorderStroke(1.5.dp, Color.Black) else null
    ) {
        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Finished",
                modifier = Modifier.size(size * 0.65f)
            )
        }
    }
}
