package com.abhinavxt.novelforge.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.abhinavxt.novelforge.data.AppFont
import com.abhinavxt.novelforge.ui.screens.getHighlightColor
import com.abhinavxt.novelforge.ui.theme.HeadingFont
import com.abhinavxt.novelforge.ui.theme.LocalEinkMode
import com.abhinavxt.novelforge.ui.theme.toFontFamily
import com.abhinavxt.novelforge.util.QuoteCardExporter
import kotlinx.coroutines.launch

/** The look of a shared quote card. */
enum class QuoteCardStyle(val label: String) {
    PAPER("Paper"),
    NIGHT("Night"),
    ACCENT("Accent")
}

/** A quote ready to draw: the (possibly truncated) text and the type size that fits it. */
data class QuoteFit(val text: String, val fontSizeSp: Int, val lineHeightSp: Int)

private const val MAX_QUOTE_CHARS = 600

/**
 * Picks the type size for a quote and truncates it if it is too long to ever fit.
 * The card has a fixed width and a height that wraps, so longer quotes step the
 * size down instead; past [MAX_QUOTE_CHARS] the text is cut at a word boundary.
 */
fun fitQuote(raw: String): QuoteFit {
    // Selections can carry line breaks and runs of spaces from the source markup.
    val text = raw.trim().replace(Regex("\\s+"), " ")
    val shown = if (text.length <= MAX_QUOTE_CHARS) text else truncateAtWord(text, MAX_QUOTE_CHARS)
    val size = when {
        shown.length <= 80 -> 34
        shown.length <= 160 -> 28
        shown.length <= 280 -> 23
        shown.length <= 420 -> 19
        else -> 16
    }
    return QuoteFit(shown, size, (size * 1.45f).toInt())
}

private fun truncateAtWord(text: String, max: Int): String {
    val window = text.substring(0, max)
    // If the cut landed exactly before a space the window already ends on a whole word.
    val cut = if (text[max] == ' ') window else window.substringBeforeLast(' ', window)
    return cut.trimEnd(' ', ',', ';', ':', '.', '-', '–', '—') + "…"
}

/** Author line is shown only when it carries information. */
fun quoteAuthorOrNull(author: String?): String? =
    author?.trim()?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }

private val PaperBackground = Color(0xFFF7F1E5)
private val PaperText = Color(0xFF2B2622)
private val NightBackground = Color(0xFF0F0F12)
private val NightText = Color(0xFFEDE9E1)

/**
 * The card itself. Width comes from the caller (1080 px when exported); height
 * wraps the text. Fixed colours rather than theme colours so a shared image
 * looks the same whatever theme the sender uses.
 */
@Composable
fun QuoteCard(
    quote: String,
    bookTitle: String,
    author: String?,
    highlightColor: String,
    style: QuoteCardStyle,
    modifier: Modifier = Modifier
) {
    val fit = remember(quote) { fitQuote(quote) }
    val (background, ink) = when (style) {
        QuoteCardStyle.PAPER -> PaperBackground to PaperText
        QuoteCardStyle.NIGHT -> NightBackground to NightText
        // The stored highlight colour is translucent; take its hue and mix it into paper.
        QuoteCardStyle.ACCENT -> lerp(
            PaperBackground, getHighlightColor(highlightColor).copy(alpha = 1f), 0.30f
        ) to PaperText
    }
    val serif = remember { AppFont.LITERATA.toFontFamily() }
    val authorName = quoteAuthorOrNull(author)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 36.dp, vertical = 40.dp)
    ) {
        Text(
            "“",
            color = ink.copy(alpha = 0.35f),
            fontFamily = serif,
            fontSize = 72.sp,
            lineHeight = 60.sp
        )
        Text(
            fit.text,
            color = ink,
            fontFamily = serif,
            fontSize = fit.fontSizeSp.sp,
            lineHeight = fit.lineHeightSp.sp
        )
        Spacer(Modifier.height(28.dp))
        Text(
            bookTitle,
            color = ink,
            fontFamily = serif,
            fontStyle = FontStyle.Italic,
            fontSize = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (authorName != null) {
            Text(
                authorName,
                color = ink.copy(alpha = 0.7f),
                fontFamily = serif,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "NovelForge",
            color = ink.copy(alpha = 0.5f),
            fontFamily = HeadingFont,
            fontSize = 12.sp,
            letterSpacing = 2.sp
        )
    }
}

/**
 * Bottom sheet with a live preview, the style chips and a Share button.
 * On e-ink there is no slide-in: a plain dialog appears instantly instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuoteCardSheet(
    quote: String,
    bookTitle: String,
    author: String?,
    highlightColor: String,
    onDismiss: () -> Unit
) {
    if (LocalEinkMode.current) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                QuoteCardSheetContent(quote, bookTitle, author, highlightColor, onDismiss)
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            QuoteCardSheetContent(quote, bookTitle, author, highlightColor, onDismiss)
        }
    }
}

@Composable
private fun QuoteCardSheetContent(
    quote: String,
    bookTitle: String,
    author: String?,
    highlightColor: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var style by rememberSaveable { mutableStateOf(QuoteCardStyle.PAPER) }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Long quotes make a tall card; scroll the preview rather than push the Share button off screen.
        Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            QuoteCard(quote, bookTitle, author, highlightColor, style)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuoteCardStyle.entries.forEach {
                FilterChip(selected = style == it, onClick = { style = it }, label = { Text(it.label) })
            }
        }
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                scope.launch {
                    val file = QuoteCardExporter.renderAndSave(context) {
                        QuoteCard(quote, bookTitle, author, highlightColor, style)
                    }
                    busy = false
                    if (file != null) {
                        QuoteCardExporter.share(context, file)
                        onDismiss()
                    } else {
                        Toast.makeText(context, "Couldn't create the image, try again", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        ) { Text("Share") }
    }
}
