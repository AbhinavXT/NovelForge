package com.abhinavxt.novelreader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.abhinavxt.novelreader.data.NovelRepository
import com.abhinavxt.novelreader.ui.components.OfflineBanner
import com.abhinavxt.novelreader.ui.viewmodel.ImportFromUrlViewModel
import com.abhinavxt.novelreader.util.NetworkMonitor

/**
 * Screen shown when the user shares a URL into NovelForge.
 *
 * States (rendered via when-on-uiState):
 *   - Resolving: normalizing URL + matching it to a source.
 *   - Fetching:  source matched, hitting the network for novel details.
 *   - Ready:     got the novel; shows a preview with "Add to library" CTA.
 *   - Unsupported: URL didn't match any known source.
 *   - Error:     network failure or novel not found at the URL.
 *
 * UX intent: one-screen flow, no modal-on-modal. The user shares from
 * their browser, sees a preview, taps Add. Adding jumps them into the
 * novel's detail screen so they can start reading immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportFromUrlScreen(
    sharedUrl: String,
    repository: NovelRepository,
    networkMonitor: NetworkMonitor,
    onBackClick: () -> Unit,
    onOpenNovel: (novelId: String, novelUrl: String) -> Unit,
    viewModel: ImportFromUrlViewModel = viewModel(
        factory = ImportFromUrlViewModel.Factory(repository)
    )
) {
    val state by viewModel.uiState.collectAsState()

    // Kick off resolution + fetch the moment we land on the screen.
    // Keyed on sharedUrl so pasting a different URL re-triggers (unlikely
    // but cheap to handle correctly).
    LaunchedEffect(sharedUrl) {
        viewModel.start(sharedUrl)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Novel") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        // ── Outer column ──
        // Applies innerPadding once, holds banner + state content.
        // No horizontal padding so the banner can span full width — the
        // inner state-content Box restores its own 24dp horizontal padding.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ★ Offline banner — this screen needs network to fetch novel
            //   details. If offline, the ViewModel will transition to
            //   UiState.Error anyway, but this banner tells the user WHY
            //   before they're staring at an error message.
            OfflineBanner(monitor = networkMonitor)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
            ) {
                when (val s = state) {
                    is ImportFromUrlViewModel.UiState.Resolving -> {
                        LoadingBlock("Reading the link…")
                    }
                    is ImportFromUrlViewModel.UiState.Fetching -> {
                        LoadingBlock("Getting novel details from ${s.sourceName}…")
                    }
                    is ImportFromUrlViewModel.UiState.Unsupported -> {
                        ErrorBlock(
                            title = "Source not supported",
                            message = "NovelForge doesn't support this website yet. " +
                                    "You can search for the novel manually if another " +
                                    "source has it.",
                            url = s.url,
                            onBack = onBackClick
                        )
                    }
                    is ImportFromUrlViewModel.UiState.Error -> {
                        ErrorBlock(
                            title = "Couldn't load",
                            message = s.message,
                            url = s.url,
                            onBack = onBackClick,
                            onRetry = { viewModel.retry() }
                        )
                    }
                    is ImportFromUrlViewModel.UiState.Ready -> {
                        ReadyBlock(
                            state = s,
                            onAdd = {
                                viewModel.confirmAdd()
                                onOpenNovel(s.novelId, s.canonicalUrl)
                            },
                            onCancel = onBackClick
                        )
                    }
                }
            }
        }
    }
}

// ─── Sub-composables for each state ─────────────────────────────────

@Composable
private fun LoadingBlock(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorBlock(
    title: String,
    message: String,
    url: String,
    onBack: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(16.dp))
        // Display the problematic URL in a small block, so if the user
        // is debugging a share issue they can see what we actually received.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(12.dp)
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text("Close") }
            if (onRetry != null) {
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun ReadyBlock(
    state: ImportFromUrlViewModel.UiState.Ready,
    onAdd: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))

        // Cover — large hero. AsyncImage uses Coil's caching and the
        // Referer interceptor.
        if (state.coverUrl.isNotBlank()) {
            AsyncImage(
                model = state.coverUrl,
                contentDescription = state.title,
                modifier = Modifier
                    .size(width = 180.dp, height = 260.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.height(20.dp))
        }

        Text(
            text = state.title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        if (state.author.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "by ${state.author}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))

        // Source + chapter-count chips. Informational, not interactive.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoChip(label = state.sourceName)
            if (state.chapterCount > 0) {
                InfoChip(label = "${state.chapterCount} chapters")
            }
            if (state.alreadyInLibrary) {
                InfoChip(
                    label = "In library",
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }
        }

        if (state.description.isNotBlank()) {
            Spacer(Modifier.height(20.dp))
            // Limited to ~5 lines — users confirming an add don't need
            // the full description, just enough to verify this is the
            // novel they meant to share.
            Text(
                text = state.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        Spacer(Modifier.height(32.dp))

        // Action row — primary action on the right (Material convention).
        // If the novel is already in library, the CTA changes to "Open"
        // rather than duplicating a library add.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.size(12.dp))
            Button(onClick = onAdd) {
                Text(if (state.alreadyInLibrary) "Open" else "Add to library")
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InfoChip(
    label: String,
    tint: androidx.compose.ui.graphics.Color =
        MaterialTheme.colorScheme.surfaceVariant
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = tint),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}