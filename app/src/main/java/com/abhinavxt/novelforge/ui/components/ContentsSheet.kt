package com.abhinavxt.novelforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhinavxt.novelforge.ui.viewmodel.ReaderContents
import com.abhinavxt.novelforge.ui.viewmodel.ReaderContentsEntry

/**
 * The book's contents, opened from the reader's top bar. Starts scrolled to
 * [currentIndex], which is marked; picking an entry calls [onSelect].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentsSheet(
    contents: ReaderContents,
    currentIndex: Int,
    onSelect: (ReaderContentsEntry) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // A couple of entries above the current one stay visible for context.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).dp

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            text = if (contents.fromBook) "Contents" else "Chapters",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .padding(bottom = 16.dp)
        ) {
            itemsIndexed(contents.entries) { index, entry ->
                val isCurrent = index == currentIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isCurrent) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                            else Modifier
                        )
                        .semantics { selected = isCurrent }
                        .clickable { onSelect(entry) }
                        .padding(start = (24 + entry.depth * 16).dp, end = 24.dp, top = 12.dp, bottom = 12.dp)
                ) {
                    Text(
                        text = entry.title,
                        fontSize = if (entry.depth == 0) 15.sp else 14.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
