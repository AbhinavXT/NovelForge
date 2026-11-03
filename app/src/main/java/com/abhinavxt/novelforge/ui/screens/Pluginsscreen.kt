package com.abhinavxt.novelforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.abhinavxt.novelforge.data.source.plugin.InstalledPlugin
import com.abhinavxt.novelforge.ui.viewmodel.PluginsViewModel

/**
 * Settings > Source plugins. Manage plugin repositories, install plugins
 * from them (or from a local .js file), update and remove installed ones.
 * The plugin format is documented in docs/PLUGINS.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(
    onBackClick: () -> Unit,
    viewModel: PluginsViewModel = viewModel(),
) {
    val repos by viewModel.repos.collectAsState()
    val installed by viewModel.installed.collectAsState()
    val available by viewModel.available.collectAsState()
    val updates by viewModel.updates.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val query by viewModel.query.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    var showAddRepo by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf<InstalledPlugin?>(null) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.installFromFile(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Source plugins") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { pickFile.launch(arrayOf("*/*")) },
                        enabled = PluginsViewModel.FROM_FILE !in busy,
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = "Install from file")
                    }
                    if (PluginsViewModel.REFRESHING in busy) {
                        CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = viewModel::refresh, enabled = repos.isNotEmpty()) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh repositories")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            // ── Installed ──────────────────────────────────────────
            item { SectionTitle("Installed") }
            if (installed.isEmpty()) {
                item { Hint("No plugins installed yet. Add a repository below, or install a .js file with the file button above.") }
            }
            items(installed, key = { "i:" + it.id }) { plugin ->
                val update = updates[plugin.id]
                PluginRow(
                    name = plugin.name,
                    detail = "v${plugin.version} · ${plugin.lang} · ${plugin.site}",
                    iconUrl = plugin.iconUrl,
                    busy = plugin.id in busy,
                ) {
                    if (update != null) {
                        FilledTonalButton(onClick = { viewModel.install(update) }) { Text("Update") }
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = { confirmUninstall = plugin }) {
                        Icon(Icons.Default.Delete, contentDescription = "Uninstall ${plugin.name}")
                    }
                }
            }

            // ── Repositories ───────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { SectionTitle("Repositories") }
                    TextButton(onClick = { showAddRepo = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }
            }
            if (repos.isEmpty()) {
                item { Hint("A repository is a URL to an index.json listing plugins.") }
            }
            items(repos, key = { "r:$it" }) { repo ->
                ListItem(
                    headlineContent = { Text(repo, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.removeRepo(repo) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove repository")
                        }
                    },
                )
            }

            // ── Available ──────────────────────────────────────────
            if (repos.isNotEmpty()) {
                item { SectionTitle("Available") }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        placeholder = { Text("Filter by name or language") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (available.isEmpty() && PluginsViewModel.REFRESHING !in busy) {
                    item { Hint(if (query.isBlank()) "Nothing new to install." else "No plugins match.") }
                }
                items(available, key = { "a:" + it.id }) { entry ->
                    PluginRow(
                        name = entry.name,
                        detail = "v${entry.version} · ${entry.lang} · ${entry.site}",
                        iconUrl = entry.iconUrl,
                        busy = entry.id in busy,
                    ) {
                        OutlinedButton(onClick = { viewModel.install(entry) }) { Text("Install") }
                    }
                }
            }
        }
    }

    if (showAddRepo) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddRepo = false },
            title = { Text("Add repository") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Plugins run code from the repository on your device. Only add repositories you trust.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        placeholder = { Text("https://…/index.json") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addRepo(url)
                        showAddRepo = false
                    },
                    enabled = url.isNotBlank(),
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddRepo = false }) { Text("Cancel") } },
        )
    }

    confirmUninstall?.let { plugin ->
        AlertDialog(
            onDismissRequest = { confirmUninstall = null },
            title = { Text("Remove ${plugin.name}?") },
            text = { Text("Novels from this source stay in your library, but they can't be refreshed or read online until you install it again.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstall(plugin)
                    confirmUninstall = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun PluginRow(
    name: String,
    detail: String,
    iconUrl: String?,
    busy: Boolean,
    actions: @Composable () -> Unit,
) {
    ListItem(
        leadingContent = {
            if (iconUrl != null) {
                AsyncImage(
                    model = iconUrl,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(40.dp))
            }
        },
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) { actions() }
            }
        },
    )
}
