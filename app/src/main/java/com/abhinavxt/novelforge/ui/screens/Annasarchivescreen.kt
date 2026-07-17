package com.abhinavxt.novelforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.abhinavxt.novelforge.data.annas.AnnasArchiveApi
import com.abhinavxt.novelforge.ui.viewmodel.AnnasArchiveViewModel
import com.abhinavxt.novelforge.ui.viewmodel.AnnasArchiveViewModel.BookState
import com.abhinavxt.novelforge.ui.viewmodel.AnnasArchiveViewModel.SearchState

/**
 * Anna's Archive: search -> pick -> download EPUB -> import as local novel.
 * The imported book behaves exactly like a file-picker EPUB import.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnasArchiveScreen(
    onBackClick: () -> Unit,
    onOpenNovel: (novelId: String) -> Unit,
    viewModel: AnnasArchiveViewModel = viewModel(
        factory = AnnasArchiveViewModel.provideFactory(
            LocalContext.current.applicationContext
        )
    )
) {
    val searchState by viewModel.searchState.collectAsState()
    val query by viewModel.query.collectAsState()
    val bookState by viewModel.bookState.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Anna's Archive") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.onQueryChange(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search books (EPUB)...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        viewModel.search()
                        keyboardController?.hide()
                    }
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            when (val state = searchState) {
                is SearchState.Idle -> CenterMessage(
                    title = "Search Anna's Archive",
                    subtitle = "Books are downloaded as EPUB and imported into your library"
                )

                is SearchState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                is SearchState.Error -> CenterMessage(
                    title = "Search failed",
                    subtitle = state.message
                )

                is SearchState.Results -> {
                    if (state.books.isEmpty()) {
                        CenterMessage(
                            title = "No results",
                            subtitle = "Try a different title or author"
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.books, key = { it.url }) { book ->
                                BookRow(book = book, onClick = { viewModel.openBook(book) })
                            }
                        }
                    }
                }
            }
        }
    }

    if (bookState !is BookState.Hidden) {
        BookSheet(
            state = bookState,
            onImport = { viewModel.importBook(it) },
            onRetry = { viewModel.retry(it) },
            onOpen = onOpenNovel,
            onDismiss = { viewModel.closeBook() },
        )
    }
}

@Composable
private fun CenterMessage(title: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun BookRow(book: AnnasArchiveApi.Book, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(8.dp)
        ) {
            AsyncImage(
                model = book.coverUrl,
                contentDescription = null,
                modifier = Modifier
                    .width(48.dp)
                    .height(68.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookSheet(
    state: BookState,
    onImport: (AnnasArchiveApi.BookDetail) -> Unit,
    onRetry: (BookState.Error) -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            when (state) {
                is BookState.Hidden -> Unit

                is BookState.LoadingDetail -> {
                    SheetHeader(title = state.book.title, coverUrl = state.book.coverUrl)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Loading details...")
                    }
                }

                is BookState.Detail -> {
                    DetailBody(state.detail)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { onImport(state.detail) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.detail.mirrors.isNotEmpty()
                    ) {
                        Text(
                            if (state.detail.mirrors.isEmpty()) "No mirrors available"
                            else "Download & import (${state.detail.mirrors.size} mirrors)"
                        )
                    }
                }

                is BookState.Downloading -> {
                    DetailBody(state.detail)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Downloading from ${state.mirrorLabel}...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val progress = state.progress
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                is BookState.Importing -> {
                    DetailBody(state.detail)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Importing EPUB...")
                    }
                }

                is BookState.Done -> {
                    DetailBody(state.detail)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Added to library (${state.chapterCount} chapters)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { onOpen(state.novelId) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open") }
                }

                is BookState.Error -> {
                    SheetHeader(
                        title = state.detail?.title ?: state.book.title,
                        coverUrl = state.detail?.coverUrl ?: state.book.coverUrl
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onRetry(state) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, coverUrl: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = coverUrl,
            contentDescription = null,
            modifier = Modifier
                .width(56.dp)
                .height(80.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DetailBody(detail: AnnasArchiveApi.BookDetail) {
    SheetHeader(title = detail.title, coverUrl = detail.coverUrl)
    detail.author?.let { author ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = author,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
    detail.synopsis?.takeIf { it.isNotBlank() }?.let { synopsis ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = synopsis,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis
        )
    }
}