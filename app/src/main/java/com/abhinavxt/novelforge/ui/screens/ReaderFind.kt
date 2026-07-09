package com.abhinavxt.novelforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhinavxt.novelforge.util.MatchHighlight

/**
 * One occurrence of the find query in the loaded reader text.
 *
 * [globalIndex] is the LazyColumn item index of the containing
 * paragraph (segment startIndex + paragraph offset — divider items
 * already baked into startIndex), [paragraphIndex] is the offset
 * within the segment (what paged mode's findPageForParagraph wants),
 * and [range] is the character span inside that paragraph.
 */
internal data class FindMatch(
    val chapterId: String,
    val globalIndex: Int,
    val paragraphIndex: Int,
    val range: IntRange
)

/**
 * Scans reader segments for the query, in reading order. Scroll mode
 * passes the whole stitched window ("find in what's on screen");
 * paged mode passes just the anchor segment.
 */
internal fun computeFindMatches(
    segments: List<ReaderSegment>,
    query: String
): List<FindMatch> {
    if (query.length < MatchHighlight.MIN_QUERY_LENGTH) return emptyList()
    val matches = mutableListOf<FindMatch>()
    segments.forEach { seg ->
        seg.paragraphs.forEachIndexed { pIdx, paragraph ->
            MatchHighlight.findOccurrences(paragraph, query).forEach { range ->
                matches += FindMatch(
                    chapterId = seg.chapterId,
                    globalIndex = seg.startIndex + pIdx,
                    paragraphIndex = pIdx,
                    range = range
                )
            }
        }
    }
    return matches
}

/**
 * The find bar that replaces ReaderTopBar while find is active:
 * [✕] [ query field ] 3/17 [▲] [▼]
 *
 * Uses the reader theme's colors (not MaterialTheme) so it blends
 * with the chrome on Paper/AMOLED/custom themes. IME "search" and ▼
 * both advance to the next match.
 */
@Composable
internal fun ReaderFindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    activeIndex: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    backgroundColor: Color,
    contentColor: Color
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close find",
                tint = contentColor
            )
        }

        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .padding(vertical = 14.dp),
            singleLine = true,
            textStyle = TextStyle(color = contentColor, fontSize = 15.sp),
            cursorBrush = SolidColor(contentColor),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
            decorationBox = { innerTextField ->
                if (query.isEmpty()) {
                    Text(
                        text = "Find in chapter…",
                        color = contentColor.copy(alpha = 0.4f),
                        fontSize = 15.sp
                    )
                }
                innerTextField()
            }
        )

        Text(
            text = when {
                query.length < MatchHighlight.MIN_QUERY_LENGTH -> ""
                matchCount == 0 -> "0/0"
                else -> "${activeIndex + 1}/$matchCount"
            },
            color = contentColor.copy(alpha = 0.6f),
            fontSize = 12.sp
        )

        IconButton(onClick = onPrev, enabled = matchCount > 0) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "Previous match",
                tint = if (matchCount > 0) contentColor else contentColor.copy(alpha = 0.3f)
            )
        }
        IconButton(onClick = onNext, enabled = matchCount > 0) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Next match",
                tint = if (matchCount > 0) contentColor else contentColor.copy(alpha = 0.3f)
            )
        }
    }
}
