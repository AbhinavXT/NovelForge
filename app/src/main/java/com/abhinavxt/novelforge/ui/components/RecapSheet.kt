package com.abhinavxt.novelforge.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.data.RecapData

/** "Previously in…": where you stopped, who's been around, and what you highlighted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecapSheet(recap: RecapData, title: String, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 32.dp)
        ) {
            Text(
                if (title.isBlank()) "Previously" else "Previously in $title",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Last read ${recap.daysAway} days ago · chapter ${recap.chapterNumber}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            recap.stoppedAt?.let {
                Section("Where you stopped")
                Text("“$it”", fontStyle = FontStyle.Italic)
            }
            if (recap.characters.isNotEmpty()) {
                Section("Who's been around")
                Text(recap.characters.joinToString(" · "))
            }
            if (recap.highlights.isNotEmpty()) {
                Section("Your highlights")
                recap.highlights.forEach {
                    Text("“$it”", fontStyle = FontStyle.Italic, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Continue reading") }
        }
    }
}

@Composable
private fun Section(label: String) {
    Spacer(Modifier.height(20.dp))
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(6.dp))
}
