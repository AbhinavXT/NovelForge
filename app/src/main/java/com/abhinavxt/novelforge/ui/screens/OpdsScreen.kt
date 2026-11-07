package com.abhinavxt.novelforge.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelforge.data.opds.OpdsAcquisition
import com.abhinavxt.novelforge.data.opds.OpdsCatalog
import com.abhinavxt.novelforge.data.opds.OpdsCatalogStore
import com.abhinavxt.novelforge.data.opds.OpdsEntry
import com.abhinavxt.novelforge.ui.components.BookDownloadLine
import com.abhinavxt.novelforge.ui.components.NovelCover
import com.abhinavxt.novelforge.ui.components.OfflineBanner
import com.abhinavxt.novelforge.ui.components.formatBytes
import com.abhinavxt.novelforge.ui.viewmodel.OpdsViewModel
import com.abhinavxt.novelforge.util.NetworkMonitor
import com.abhinavxt.novelforge.worker.BookDownloadState
import kotlinx.coroutines.launch

/**
 * Book catalogs (OPDS): Project Gutenberg out of the box, plus any the user
 * adds, such as Standard Ebooks or their own Calibre-Web, Kavita or Komga
 * server. Books download in the background and import like picked files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpdsScreen(
    networkMonitor: NetworkMonitor,
    onBackClick: () -> Unit,
    onOpenNovel: (novelId: String) -> Unit,
    viewModel: OpdsViewModel = viewModel(factory = OpdsViewModel.Factory(LocalContext.current))
) {
    val catalogs by viewModel.catalogs.collectAsState()
    val stack by viewModel.stack.collectAsState()
    val searchable by viewModel.searchable.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val page = stack.lastOrNull()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<OpdsCatalog?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<OpdsEntry?>(null) }

    BackHandler(enabled = page != null) { viewModel.back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        page?.title ?: "Book catalogs",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.back()) onBackClick() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            if (page == null) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add catalog") }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OfflineBanner(monitor = networkMonitor)
            if (page == null) {
                CatalogList(
                    catalogs = catalogs,
                    onOpen = viewModel::openCatalog,
                    onEdit = { editing = it }
                )
            } else {
                FeedPage(
                    page = page,
                    canSearch = page.catalog.id in searchable,
                    downloads = downloads,
                    downloadOf = viewModel::downloadOf,
                    onSearch = viewModel::search,
                    onEntryClick = { entry ->
                        val done = viewModel.downloadOf(entry, downloads)?.second
                        when {
                            done is BookDownloadState.Imported -> onOpenNovel(done.novelId)
                            entry.isBook -> selected = entry
                            else -> viewModel.openEntry(entry)
                        }
                    },
                    onLoadMore = viewModel::loadMore,
                    onRetry = viewModel::retry
                )
            }
        }
    }

    selected?.let { entry ->
        BookDialog(
            entry = entry,
            download = viewModel.downloadOf(entry, downloads),
            onDownload = { acquisition ->
                selected = null
                viewModel.download(entry, acquisition)
                scope.launch { snackbar.showSnackbar("Downloading \"${entry.title}\" in the background") }
            },
            onCancel = { acquisition ->
                viewModel.cancelDownload(acquisition)
                selected = null
            },
            onOpen = { novelId ->
                selected = null
                onOpenNovel(novelId)
            },
            onDismiss = { selected = null }
        )
    }

    if (adding || editing != null) {
        CatalogDialog(
            catalog = editing,
            onSave = { name, url, user, pass -> viewModel.saveCatalog(editing?.id, name, url, user, pass) },
            onDelete = editing?.let { c -> { viewModel.deleteCatalog(c.id) } },
            onDismiss = {
                adding = false
                editing = null
            }
        )
    }
}

// ── Catalog list ────────────────────────────────────────────────────────

@Composable
private fun CatalogList(
    catalogs: List<OpdsCatalog>,
    onOpen: (OpdsCatalog) -> Unit,
    onEdit: (OpdsCatalog) -> Unit
) {
    if (catalogs.isEmpty()) {
        Message("No catalogs yet. Add one, such as Project Gutenberg or your own Calibre-Web server.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Text(
                "Catalogs list free or your own books. Adding one downloads it in the " +
                        "background and imports it like a file you picked yourself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
        items(catalogs, key = { it.id }) { catalog ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(catalog) }
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(catalog.name, style = MaterialTheme.typography.titleMedium)
                        if (catalog.needsLogin) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Lock, contentDescription = "Signs in",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        catalog.url.substringAfter("://"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { onEdit(catalog) }) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit ${catalog.name}")
                }
            }
            HorizontalDivider(Modifier.padding(start = 56.dp))
        }
    }
}

// ── Feed page ───────────────────────────────────────────────────────────

@Composable
private fun FeedPage(
    page: OpdsViewModel.Page,
    canSearch: Boolean,
    downloads: Map<String, BookDownloadState>,
    downloadOf: (OpdsEntry, Map<String, BookDownloadState>) -> Pair<OpdsAcquisition, BookDownloadState>?,
    onSearch: (String) -> Unit,
    onEntryClick: (OpdsEntry) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        if (canSearch) {
            var query by rememberSaveable(page.catalog.id) { mutableStateOf(page.query.orEmpty()) }
            val keyboard = LocalSoftwareKeyboardController.current
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search ${page.catalog.name}") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear") }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    onSearch(query)
                })
            )
        }

        when {
            page.loading -> Message("Loading…", spinner = true)
            page.error != null && page.entries.isEmpty() -> Message(page.error, action = "Retry" to onRetry)
            page.entries.isEmpty() -> Message(if (page.query != null) "Nothing found." else "This section is empty.")
            else -> {
                val listState = rememberLazyListState()
                val nearEnd by remember {
                    derivedStateOf {
                        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        last >= listState.layoutInfo.totalItemsCount - 4
                    }
                }
                LaunchedEffect(nearEnd, page.entries.size) { if (nearEnd && page.nextUrl != null) onLoadMore() }

                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(page.entries, key = { it.id + "\u0000" + it.title }) { entry ->
                        EntryRow(entry, downloadOf(entry, downloads)?.second) { onEntryClick(entry) }
                        HorizontalDivider(Modifier.padding(start = if (entry.isBook) 92.dp else 56.dp))
                    }
                    if (page.loadingMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                            }
                        }
                    } else if (page.error != null) {
                        item {
                            Text(
                                page.error,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: OpdsEntry, download: BookDownloadState?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = if (entry.isBook) Alignment.Top else Alignment.CenterVertically
    ) {
        if (entry.isBook) {
            NovelCover(coverUrl = entry.coverUrl.ifBlank { null }, width = 60.dp, height = 88.dp)
        } else {
            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val byline = entry.authors.joinToString(", ").ifBlank { entry.summary }
            if (byline.isNotBlank()) {
                Text(
                    byline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.isBook && entry.authors.isNotEmpty() && entry.summary.isNotBlank()) {
                // Often what tells editions apart ("This edition has images.").
                Text(
                    entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.isBook && entry.acquisitions.isEmpty()) {
                Text(
                    "Not available as EPUB (${entry.otherFormats.joinToString(", ")})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            download?.let {
                Spacer(Modifier.height(4.dp))
                BookDownloadLine(it)
            }
        }
        if (!entry.isBook) {
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Message(text: String, spinner: Boolean = false, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (spinner) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.let { (label, onClick) ->
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onClick) { Text(label) }
        }
    }
}

// ── Dialogs ─────────────────────────────────────────────────────────────

/** A book's details, with one button per EPUB the catalog offers (best first). */
@Composable
private fun BookDialog(
    entry: OpdsEntry,
    download: Pair<OpdsAcquisition, BookDownloadState>?,
    onDownload: (OpdsAcquisition) -> Unit,
    onCancel: (OpdsAcquisition) -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                if (entry.authors.isNotEmpty()) {
                    Text(entry.authors.joinToString(", "), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                }
                download?.let { (_, state) ->
                    Spacer(Modifier.height(12.dp))
                    BookDownloadLine(state)
                    if (state is BookDownloadState.Failed) {
                        Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                val active = download?.second.let { it is BookDownloadState.Queued || it is BookDownloadState.Running }
                if (entry.acquisitions.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "This book isn't offered as EPUB here" +
                                (entry.otherFormats.takeIf { it.isNotEmpty() }?.joinToString(", ", " (only ", ")") ?: "") +
                                ", which is what NovelForge imports.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (!active && download?.second !is BookDownloadState.Imported) {
                    Spacer(Modifier.height(16.dp))
                    entry.acquisitions.forEachIndexed { i, a ->
                        val label = buildString {
                            append(a.title.ifBlank { "EPUB" })
                            a.length?.let { append(" · ").append(formatBytes(it)) }
                        }
                        if (i == 0) {
                            Button(onClick = { onDownload(a) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Add · $label", maxLines = 2, textAlign = TextAlign.Center)
                            }
                        } else {
                            OutlinedButton(onClick = { onDownload(a) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Text(label, maxLines = 2, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                // Last: catalogs like Gutenberg write long summaries, and the
                // choice of file shouldn't sit below the fold.
                if (entry.summary.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(entry.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            when (val state = download?.second) {
                is BookDownloadState.Imported -> Button(onClick = { onOpen(state.novelId) }) { Text("Open") }
                BookDownloadState.Queued, is BookDownloadState.Running ->
                    TextButton(onClick = { onCancel(download.first) }) { Text("Cancel download") }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Add a catalog (with one-tap suggestions) or edit one. */
@Composable
private fun CatalogDialog(
    catalog: OpdsCatalog?,
    onSave: (name: String, url: String, username: String, password: String) -> String?,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(catalog?.name.orEmpty()) }
    var url by rememberSaveable { mutableStateOf(catalog?.url.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(catalog?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(catalog?.password.orEmpty()) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${catalog?.name}?") },
            text = { Text("Books you've already added stay in your library.") },
            confirmButton = {
                TextButton(onClick = { onDelete(); onDismiss() }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (catalog == null) "Add catalog" else "Edit catalog") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (catalog == null) {
                    Text("Suggestions", style = MaterialTheme.typography.labelLarge)
                    OpdsCatalogStore.SUGGESTIONS.forEach { s ->
                        Text(
                            s.name,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    name = s.name.takeUnless { s.url == "https://" }.orEmpty()
                                    url = s.url
                                    note = s.note
                                    error = null
                                }
                                .padding(vertical = 6.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it; error = null },
                    label = { Text("OPDS address") }, singleLine = true, isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("Username or email (if it signs in)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("Kept on this device only; left out of backups.") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                error = onSave(name, url, username, password)
                if (error == null) onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
