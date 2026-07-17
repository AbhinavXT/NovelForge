package com.abhinavxt.novelforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.data.source.Source
import com.abhinavxt.novelforge.data.source.health.HealthStatus
import com.abhinavxt.novelforge.data.source.health.SourceHealth

/**
 * Bottom-sheet source picker (replaces the old 36-chip LazyRow).
 *
 * Sections when the filter is empty: Pinned -> Recent -> All (A-Z, with
 * effectively-down sources dimmed and sorted last). Typing collapses to a
 * single filtered list. Each row shows a health dot from SourceHealthWorker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcePickerSheet(
    sources: List<Source>,
    selectedSourceId: String?,
    pinnedIds: Set<String>,
    recentIds: List<String>,
    health: Map<String, SourceHealth>,
    onSelect: (Source) -> Unit,
    onTogglePin: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var filter by remember { mutableStateOf("") }

    // remember(inputs) — recomputed only when sources/pins/recents/filter change.
    val sections: List<Pair<String, List<Source>>> =
        remember(sources, pinnedIds, recentIds, health, filter) {
            buildSections(sources, pinnedIds, recentIds, health, filter)
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text("Filter sources...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                singleLine = true,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 8.dp, bottom = 16.dp
                ),
            ) {
                sections.forEach { (title, groupSources) ->
                    if (title.isNotEmpty() && groupSources.isNotEmpty()) {
                        item(key = "header_$title") {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(
                                    start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp
                                )
                            )
                        }
                    }
                    items(groupSources, key = { "${title}_${it.id}" }) { source ->
                        SourceRow(
                            source = source,
                            selected = source.id == selectedSourceId,
                            pinned = source.id in pinnedIds,
                            health = health[source.id],
                            onClick = { onSelect(source) },
                            onTogglePin = { onTogglePin(source.id) },
                        )
                    }
                }
                if (sections.all { it.second.isEmpty() }) {
                    item(key = "empty") {
                        Text(
                            text = "No sources match \"$filter\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun buildSections(
    sources: List<Source>,
    pinnedIds: Set<String>,
    recentIds: List<String>,
    health: Map<String, SourceHealth>,
    filter: String,
): List<Pair<String, List<Source>>> {
    val byId = sources.associateBy { it.id }

    if (filter.isNotBlank()) {
        val filtered = sources
            .filter { it.name.contains(filter.trim(), ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
        return listOf("" to filtered)
    }

    val pinned = pinnedIds.mapNotNull { byId[it] }.sortedBy { it.name.lowercase() }
    // Recents exclude pinned (no point showing a source twice near the top).
    val recent = recentIds.mapNotNull { byId[it] }.filter { it.id !in pinnedIds }

    // All: A-Z, but effectively-down sources sink to the bottom.
    val all = sources.sortedWith(
        compareBy(
            { health[it.id]?.isEffectivelyDown == true },
            { it.name.lowercase() },
        )
    )

    return listOf(
        "Pinned" to pinned,
        "Recent" to recent,
        "All sources" to all,
    )
}

@Composable
private fun SourceRow(
    source: Source,
    selected: Boolean,
    pinned: Boolean,
    health: SourceHealth?,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val effectivelyDown = health?.isEffectivelyDown == true

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (selected) Modifier.background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                ) else Modifier
            )
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
            .alpha(if (effectivelyDown) 0.45f else 1f)
    ) {
        HealthDot(health)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitleFor(source, health),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onTogglePin) {
            Icon(
                imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = if (pinned) "Unpin" else "Pin",
                tint = if (pinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun HealthDot(health: SourceHealth?) {
    val color = when {
        health == null || health.status == HealthStatus.UNKNOWN ->
            MaterialTheme.colorScheme.outlineVariant
        health.isEffectivelyDown -> MaterialTheme.colorScheme.error
        health.status == HealthStatus.DOWN -> Color(0xFFFFB300)   // 1 failure: amber
        health.status == HealthStatus.CLOUDFLARE -> Color(0xFFFFB300)
        else -> Color(0xFF4CAF50)                                  // UP: green
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}

private fun subtitleFor(source: Source, health: SourceHealth?): String {
    val host = source.baseUrl
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')
    val checked = health?.takeIf { it.checkedAt > 0 }?.let {
        " · checked ${agoText(it.checkedAt)}"
    } ?: ""
    val state = when {
        health == null || health.status == HealthStatus.UNKNOWN -> ""
        health.isEffectivelyDown -> " · down"
        health.status == HealthStatus.DOWN -> " · unstable"
        health.status == HealthStatus.CLOUDFLARE -> " · cloudflare"
        else -> ""
    }
    return host + state + checked
}

private fun agoText(timestamp: Long): String {
    val mins = ((System.currentTimeMillis() - timestamp) / 60_000L).coerceAtLeast(0)
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}