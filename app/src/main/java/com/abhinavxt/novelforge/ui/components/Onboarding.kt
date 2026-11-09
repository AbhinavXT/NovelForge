package com.abhinavxt.novelforge.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abhinavxt.novelforge.AppConfig
import com.abhinavxt.novelforge.data.Onboarding
import com.abhinavxt.novelforge.data.source.plugin.PluginManager
import com.abhinavxt.novelforge.ui.viewmodel.PluginsViewModel
import kotlinx.coroutines.flow.map

/** First-launch screen: pick sources to install, import a book, restore a backup, or skip. */
@Composable
fun WelcomeScreen(onFinish: (Onboarding.Action?) -> Unit) {
    val vm: PluginsViewModel = viewModel()
    val available by vm.available.collectAsState()
    val busy by vm.busy.collectAsState()
    val official = available.filter { it.repo == PluginManager.OFFICIAL_REPO }
    val repoLoaded by remember {
        PluginManager.available.map { all -> all.any { it.repo == PluginManager.OFFICIAL_REPO } }
    }.collectAsState(initial = false)
    // Loaded and nothing left to offer: every source is installed already.
    val offerSources = AppConfig.ONLINE_SOURCES_ENABLED && !(repoLoaded && official.isEmpty())
    // The first-party sources that used to ship in the APK are the recommended ones.
    var checked by rememberSaveable { mutableStateOf(PluginManager.OFFICIAL_ONLY.keys.toSet()) }
    var installing by rememberSaveable { mutableStateOf(emptySet<String>()) }

    var showGuide by rememberSaveable { mutableStateOf(false) }
    BackHandler { onFinish(null) }
    if (showGuide) {
        BackHandler { showGuide = false }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            com.abhinavxt.novelforge.ui.screens.GuideScreen(onBackClick = { showGuide = false })
        }
        return
    }
    LaunchedEffect(installing, busy) {
        if (installing.isNotEmpty() && installing.none { it in busy }) onFinish(null)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Welcome to NovelForge", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Read web novels and your own books, offline and aloud.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (offerSources) {
                Text("Add sources", style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    if (official.isEmpty()) item {
                        if (PluginsViewModel.REFRESHING in busy) {
                            CircularProgressIndicator(Modifier.padding(16.dp))
                        } else {
                            Text(
                                "Couldn't reach the plugin repository. You can add sources later in Settings > Source plugins.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    items(official, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth()
                                .toggleable(
                                    value = p.id in checked,
                                    enabled = installing.isEmpty(),
                                    role = Role.Checkbox,
                                    onValueChange = { on -> checked = if (on) checked + p.id else checked - p.id },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = p.id in checked, onCheckedChange = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${p.lang} · ${p.site}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                val toInstall = official.filter { it.id in checked }
                Button(
                    onClick = {
                        installing = toInstall.map { it.id }.toSet()
                        toInstall.forEach(vm::install)
                    },
                    enabled = toInstall.isNotEmpty() && installing.isEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (installing.isEmpty()) "Install ${toInstall.size} and start" else "Installing…")
                }
            } else {
                Column(Modifier.weight(1f)) {}
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { onFinish(Onboarding.Action.IMPORT_EPUB) },
                    enabled = installing.isEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Import a book") }
                OutlinedButton(
                    onClick = { onFinish(Onboarding.Action.RESTORE_BACKUP) },
                    enabled = installing.isEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Restore backup") }
            }
            Row(Modifier.align(Alignment.CenterHorizontally)) {
                TextButton(onClick = { showGuide = true }, enabled = installing.isEmpty()) { Text("How it works") }
                TextButton(onClick = { onFinish(null) }, enabled = installing.isEmpty()) { Text("Skip") }
            }
        }
    }
}
