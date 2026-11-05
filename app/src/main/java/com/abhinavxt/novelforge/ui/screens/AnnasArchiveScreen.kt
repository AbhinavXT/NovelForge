package com.abhinavxt.novelforge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelforge.data.annas.AnnasBook
import com.abhinavxt.novelforge.data.annas.ChallengeWebView
import com.abhinavxt.novelforge.ui.components.NovelCover
import com.abhinavxt.novelforge.ui.components.OfflineBanner
import com.abhinavxt.novelforge.ui.viewmodel.AnnasArchiveViewModel
import com.abhinavxt.novelforge.ui.viewmodel.AnnasArchiveViewModel.DownloadState
import com.abhinavxt.novelforge.util.NetworkMonitor

/**
 * Search Anna's Archive for EPUBs and import one into the library.
 *
 * Tapping a result asks first: a download is real bandwidth, and the
 * dialog is where its progress and any failure are shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnasArchiveScreen(
    networkMonitor: NetworkMonitor,
    onBackClick: () -> Unit,
    onOpenNovel: (novelId: String) -> Unit,
    viewModel: AnnasArchiveViewModel = viewModel(
        factory = AnnasArchiveViewModel.Factory(LocalContext.current)
    )
) {
    val search by viewModel.search.collectAsState()
    val download by viewModel.download.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    var confirming by remember { mutableStateOf<AnnasBook?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Anna's Archive") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Anna's Archive settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OfflineBanner(monitor = networkMonitor)

            OutlinedTextField(
                value = search.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Title, author or ISBN") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (search.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    viewModel.submitSearch()
                })
            )

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    search.loading -> CenteredMessage(
                        "Searching… The first search can take a few seconds " +
                                "while the site checks the connection.",
                        spinner = true
                    )
                    search.error != null && search.books.isEmpty() -> CenteredMessage(
                        search.error!!,
                        action = "Retry" to viewModel::submitSearch
                    )
                    search.searched && search.books.isEmpty() -> CenteredMessage(
                        "No EPUBs found for \"${search.query.trim()}\"."
                    )
                    !search.searched -> CenteredMessage(
                        "Search for a book. Results are EPUBs, which import " +
                                "into your library like a file you picked yourself."
                    )
                    else -> ResultList(
                        state = search,
                        onBookClick = { confirming = it },
                        onLoadMore = viewModel::loadMore
                    )
                }
            }
        }
    }

    confirming?.let { book ->
        ConfirmDialog(
            book = book,
            onConfirm = {
                confirming = null
                viewModel.download(book)
            },
            onDismiss = { confirming = null }
        )
    }

    DownloadDialog(
        state = download,
        onCancel = viewModel::cancelDownload,
        onDismiss = viewModel::dismissDownload,
        onRetry = { book -> viewModel.download(book) },
        onOpen = { novelId ->
            viewModel.dismissDownload()
            onOpenNovel(novelId)
        }
    )

    search.verification?.let { v ->
        VerificationSheet(
            url = v.url,
            onPassed = viewModel::onVerified,
            onCancel = viewModel::onVerificationCancelled
        )
    }

    if (showSettings) {
        SettingsDialog(
            initialMirror = viewModel.mirror,
            initialKey = viewModel.secretKey,
            onSave = viewModel::saveSettings,
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun ResultList(
    state: AnnasArchiveViewModel.SearchState,
    onBookClick: (AnnasBook) -> Unit,
    onLoadMore: () -> Unit
) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, state.books.size) {
        if (nearEnd) onLoadMore()
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        items(state.books, key = { it.md5 }) { book ->
            BookRow(book = book, onClick = { onBookClick(book) })
            HorizontalDivider(modifier = Modifier.padding(start = 92.dp))
        }
        if (state.loadingMore) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        } else if (state.error != null) {
            item {
                Text(
                    state.error,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun BookRow(book: AnnasBook, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        NovelCover(coverUrl = book.coverUrl.ifBlank { null }, width = 60.dp, height = 88.dp)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                book.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (book.author.isNotBlank()) {
                Text(
                    book.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val facts = listOf(book.format.uppercase(), book.size, book.language, book.publisher)
                .filter { it.isNotBlank() }
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    facts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CenteredMessage(
    message: String,
    spinner: Boolean = false,
    action: Pair<String, () -> Unit>? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (spinner) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
        }
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        action?.let { (label, onClick) ->
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onClick) { Text(label) }
        }
    }
}

@Composable
private fun ConfirmDialog(book: AnnasBook, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Text(
                listOf(book.author, book.metadata).filter { it.isNotBlank() }.joinToString("\n\n")
                    .ifBlank { "Download this book and add it to your library?" }
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Add to library") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DownloadDialog(
    state: DownloadState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: (AnnasBook) -> Unit,
    onOpen: (String) -> Unit
) {
    when (state) {
        DownloadState.Idle -> Unit

        is DownloadState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading") },
            text = {
                Column {
                    Text(state.book.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(16.dp))
                    val total = state.total
                    if (total != null) {
                        LinearProgressIndicator(
                            progress = { (state.bytes.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        formatBytes(state.bytes) + (total?.let { " of ${formatBytes(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
        )

        is DownloadState.Importing -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Adding to library") },
            text = {
                Column {
                    Text(state.book.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {}
        )

        is DownloadState.Imported -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Added to library") },
            text = { Text(state.title) },
            confirmButton = { Button(onClick = { onOpen(state.novelId) }) { Text("Open") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Keep searching") } }
        )

        is DownloadState.AlreadyInLibrary -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Already in your library") },
            text = { Text("This file is already in your library as \"${state.title}\".") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )

        is DownloadState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Couldn't add this book") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = { onRetry(state.book) }) { Text("Retry") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
        )
    }
}

@Composable
private fun SettingsDialog(
    initialMirror: String,
    initialKey: String,
    onSave: (mirror: String, key: String) -> String?,
    onDismiss: () -> Unit
) {
    var mirror by rememberSaveable { mutableStateOf(initialMirror) }
    var key by rememberSaveable { mutableStateOf(initialKey) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Anna's Archive settings") },
        text = {
            Column {
                OutlinedTextField(
                    value = mirror,
                    onValueChange = { mirror = it; error = null },
                    label = { Text("Mirror") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = {
                        Text(error ?: "The site changes domains often. If search stops working, try another.")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Secret key (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = {
                        Text(
                            "For members. Skips the browser check and uses fast " +
                                    "downloads. Without it, files come from LibGen."
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                error = onSave(mirror, key)
                if (error == null) onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Anna's Archive's manual check, shown as the site serves it. NovelForge
 * doesn't answer it; the person does, and the page closes itself once
 * the site lets them through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerificationSheet(
    url: String,
    onPassed: (String) -> Unit,
    onCancel: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Quick check") },
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Clear, contentDescription = "Cancel")
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Text(
                    "Anna's Archive asks visitors to confirm they're a person. " +
                            "Complete the check below; your search continues when it " +
                            "passes, and you shouldn't be asked again for a while.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                var stop by remember { mutableStateOf<(() -> Unit)?>(null) }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        android.webkit.WebView(ctx).also { view ->
                            stop = ChallengeWebView.watch(view, reportManualCheck = false) { html ->
                                if (html != null) onPassed(html)
                            }
                            view.loadUrl(url)
                        }
                    },
                    onRelease = { view ->
                        stop?.invoke()
                        view.stopLoading()
                        view.destroy()
                    }
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
