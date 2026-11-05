package com.abhinavxt.novelforge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.abhinavxt.novelforge.data.annas.AnnasFilters
import com.abhinavxt.novelforge.data.annas.ChallengeWebView
import com.abhinavxt.novelforge.ui.components.NovelCover
import com.abhinavxt.novelforge.ui.components.OfflineBanner
import com.abhinavxt.novelforge.ui.viewmodel.AnnasArchiveViewModel
import com.abhinavxt.novelforge.ui.viewmodel.AnnasArchiveViewModel.DownloadState
import com.abhinavxt.novelforge.util.NetworkMonitor
import kotlinx.coroutines.launch

/**
 * Search Anna's Archive for EPUBs and add them to the library.
 *
 * Adding queues a background download (see AnnasDownloadWorker), so the
 * user can keep searching or leave; each result shows its download's
 * progress, and a finished one opens on tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnasArchiveScreen(
    networkMonitor: NetworkMonitor,
    onBackClick: () -> Unit,
    onOpenNovel: (novelId: String) -> Unit,
    initialQuery: String = "",
    viewModel: AnnasArchiveViewModel = viewModel(
        factory = AnnasArchiveViewModel.Factory(LocalContext.current)
    )
) {
    val search by viewModel.search.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<AnnasBook?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // Arriving from the Search tab with a query: search it straight away,
    // once (rememberSaveable keeps it from re-running on rotation).
    var initialHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialQuery) {
        if (!initialHandled && initialQuery.isNotBlank()) {
            initialHandled = true
            viewModel.onQueryChange(initialQuery)
            viewModel.submitSearch()
        }
    }

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
        },
        snackbarHost = { SnackbarHost(snackbar) }
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

            FilterRow(filters = search.filters, onChange = viewModel::onFiltersChange)

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
                        "No EPUBs found for \"${search.query.trim()}\". " +
                                "Try fewer words, or loosen the filters."
                    )
                    !search.searched -> CenteredMessage(
                        "Search for a book. Results are EPUBs, which download in the " +
                                "background and join your library like a file you picked yourself."
                    )
                    else -> ResultList(
                        state = search,
                        downloads = downloads,
                        onBookClick = { book ->
                            val d = downloads[book.md5]
                            if (d is DownloadState.Imported) onOpenNovel(d.novelId) else selected = book
                        },
                        onLoadMore = viewModel::loadMore
                    )
                }
            }
        }
    }

    selected?.let { book ->
        BookDialog(
            book = book,
            download = downloads[book.md5],
            editions = remember(book, search.books) { viewModel.editionsOf(book) },
            downloads = downloads,
            onPickEdition = { selected = it },
            onAdd = {
                selected = null
                viewModel.download(book)
                scope.launch {
                    snackbar.showSnackbar("Downloading \"${book.title}\" in the background")
                }
            },
            onCancelDownload = {
                viewModel.cancelDownload(book.md5)
                selected = null
            },
            onOpen = { novelId ->
                selected = null
                onOpenNovel(novelId)
            },
            onDismiss = { selected = null }
        )
    }

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

// ── Filters ─────────────────────────────────────────────────────────────

@Composable
private fun FilterRow(filters: AnnasFilters, onChange: (AnnasFilters) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterMenu(AnnasFilters.LANGUAGES, filters.language) { onChange(filters.copy(language = it)) }
        FilterMenu(AnnasFilters.CONTENTS, filters.content) { onChange(filters.copy(content = it)) }
        FilterMenu(AnnasFilters.SORTS, filters.sort) { onChange(filters.copy(sort = it)) }
    }
}

/** A chip naming the current choice, opening the list of the others. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterMenu(options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == value }?.second ?: options.first().second
    Box {
        FilterChip(
            // Highlighted when it narrows or reorders the results.
            selected = value.isNotEmpty(),
            onClick = { open = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (optionValue, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        open = false
                        if (optionValue != value) onPick(optionValue)
                    }
                )
            }
        }
    }
}

// ── Results ─────────────────────────────────────────────────────────────

@Composable
private fun ResultList(
    state: AnnasArchiveViewModel.SearchState,
    downloads: Map<String, DownloadState>,
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
            BookRow(book = book, download = downloads[book.md5], onClick = { onBookClick(book) })
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
private fun BookRow(book: AnnasBook, download: DownloadState?, onClick: () -> Unit) {
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
            val facts = factsOf(book)
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    facts,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            download?.let {
                Spacer(Modifier.height(4.dp))
                DownloadLine(it)
            }
        }
    }
}

/** One line under a result saying where its download has got to. */
@Composable
private fun DownloadLine(state: DownloadState) {
    val (icon, text, isError) = when (state) {
        DownloadState.Queued -> Triple(Icons.Default.Schedule, "Waiting to download", false)
        is DownloadState.Running -> Triple(
            null,
            state.total?.let { "Downloading ${formatBytes(state.bytes)} of ${formatBytes(it)}" }
                ?: if (state.bytes > 0) "Downloading ${formatBytes(state.bytes)}" else "Downloading",
            false
        )
        is DownloadState.Imported -> Triple(Icons.Default.CheckCircle, "In your library · tap to open", false)
        is DownloadState.AlreadyInLibrary -> Triple(Icons.Default.CheckCircle, "Already in your library", false)
        is DownloadState.Failed -> Triple(Icons.Default.ErrorOutline, "Couldn't download · tap to retry", true)
    }
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        } else {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private fun factsOf(book: AnnasBook): String =
    listOf(book.format.uppercase(), book.size, book.language, book.publisher)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

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

// ── Book dialog ─────────────────────────────────────────────────────────

/**
 * Details of one result, its download, and the other editions of the same
 * book in the results, so picking the right edition takes one tap.
 */
@Composable
private fun BookDialog(
    book: AnnasBook,
    download: DownloadState?,
    editions: List<AnnasBook>,
    downloads: Map<String, DownloadState>,
    onPickEdition: (AnnasBook) -> Unit,
    onAdd: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (book.author.isNotBlank()) {
                    Text(book.author, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                }
                if (book.metadata.isNotBlank()) {
                    Text(
                        book.metadata,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (download != null) {
                    Spacer(Modifier.height(12.dp))
                    DownloadLine(download)
                    if (download is DownloadState.Failed) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            download.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (editions.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (editions.size == 1) "1 other edition" else "${editions.size} other editions",
                        style = MaterialTheme.typography.titleSmall
                    )
                    editions.forEach { edition ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPickEdition(edition) }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                factsOf(edition).ifBlank { edition.title },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (edition.title != book.title) {
                                Text(
                                    edition.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            downloads[edition.md5]?.let { DownloadLine(it) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (download) {
                null -> Button(onClick = onAdd) { Text("Add to library") }
                is DownloadState.Failed -> Button(onClick = onAdd) { Text("Retry") }
                DownloadState.Queued, is DownloadState.Running ->
                    TextButton(onClick = onCancelDownload) { Text("Cancel download") }
                is DownloadState.Imported -> Button(onClick = { onOpen(download.novelId) }) { Text("Open") }
                is DownloadState.AlreadyInLibrary -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

// ── Verification and settings ───────────────────────────────────────────

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

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
