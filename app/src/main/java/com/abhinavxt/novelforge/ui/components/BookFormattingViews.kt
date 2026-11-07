package com.abhinavxt.novelforge.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.abhinavxt.novelforge.data.epub.Footnote

/** Space below each picture, matching the space below a paragraph. */
val IMAGE_SPACING = 16.dp

/**
 * How big to draw [image], in pixels, within [maxWidthPx] x [maxHeightPx].
 *
 * A book's pixel sizes are treated as CSS pixels (about one dp), so a small
 * ornament stays small while a full-page illustration fills the width.
 * The aspect ratio is always kept.
 */
fun imageDisplaySize(image: ReaderImage, maxWidthPx: Float, maxHeightPx: Float, density: Density): Pair<Float, Float> {
    val ratio = image.heightPx.toFloat() / image.widthPx
    var width = minOf(maxWidthPx, image.widthPx * density.density)
    var height = width * ratio
    if (height > maxHeightPx) {
        height = maxHeightPx
        width = height / ratio
    }
    return width to height
}

/**
 * A picture from the book, centred. [maxHeightPx] defaults to most of the
 * screen so a tall illustration can be seen whole while scrolling.
 */
@Composable
fun BookImage(image: ReaderImage, modifier: Modifier = Modifier, maxHeightPx: Float? = null) {
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val (w, h) = imageDisplaySize(
            image,
            maxWidthPx = constraints.maxWidth.toFloat(),
            maxHeightPx = maxHeightPx ?: (screenHeightPx * 0.8f),
            density = density
        )
        AsyncImage(
            model = image.file,
            contentDescription = image.alt,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })
        )
    }
}

/** The text of a footnote, opened by tapping its reference. */
@Composable
fun FootnoteDialog(
    note: Footnote,
    textColor: Color,
    containerColor: Color,
    fontSize: Int,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = containerColor,
        title = { Text("Note", color = textColor) },
        text = {
            Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(
                        text = note.text,
                        color = textColor,
                        fontSize = (fontSize * 0.9f).coerceAtLeast(13f).sp,
                        lineHeight = (fontSize * 0.9f * 1.5f).coerceAtLeast(18f).sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
