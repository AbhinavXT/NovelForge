package com.abhinavxt.novelforge.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.data.RecapData
import com.abhinavxt.novelforge.data.ai.AssistantResult
import com.abhinavxt.novelforge.data.ai.ReaderAssistant
import com.abhinavxt.novelforge.ui.theme.LocalEinkMode
import kotlinx.coroutines.launch

/**
 * "Previously in…": where you stopped, who's been around, and what you highlighted. With no [recap]
 * (opened from the book's page) it holds only the AI summary. [assistant] is null unless an AI
 * engine is set up, and then the summary button is left out altogether. [position] is the chapter
 * and paragraph the reader has reached, read when the button is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecapSheet(
    recap: RecapData?,
    title: String,
    novelId: String,
    assistant: ReaderAssistant?,
    position: suspend () -> Pair<Int, Int?>?,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 32.dp)
        ) {
            Text(
                when {
                    recap == null -> "The story so far"
                    title.isBlank() -> "Previously"
                    else -> "Previously in $title"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (recap == null && title.isNotBlank()) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (recap != null) {
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
            }
            if (assistant != null) StorySoFar(assistant, novelId, position)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(if (recap == null) "Close" else "Continue reading")
            }
        }
    }
}

@Composable
private fun Section(label: String) {
    Spacer(Modifier.height(20.dp))
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(6.dp))
}

/** The summary button, then what came of it. Nothing is sent until the reader taps. */
@Composable
private fun StorySoFar(
    assistant: ReaderAssistant,
    novelId: String,
    position: suspend () -> Pair<Int, Int?>?
) {
    val scope = rememberCoroutineScope()
    var reply by remember { mutableStateOf<AiReply?>(null) }
    fun run() {
        reply = AiReply.Loading
        scope.launch {
            val at = position()
            reply = if (at == null) AiReply.Problem(NO_TEXT)
            else assistant.storySoFar(novelId, at.first, at.second).toReply(NO_TEXT)
        }
    }
    Spacer(Modifier.height(20.dp))
    val shown = reply
    if (shown == null) {
        OutlinedButton(onClick = ::run, modifier = Modifier.fillMaxWidth()) { Text("The story so far") }
    } else {
        Text("The story so far", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        AiReplyView(shown, onRetry = ::run)
    }
}

private const val NO_TEXT = "None of the chapters you've read are saved on this device, so there's nothing to summarise."

/** What an AI helper's panel shows. */
sealed interface AiReply {
    data object Loading : AiReply
    data class Answer(val text: String) : AiReply
    data class Problem(val message: String) : AiReply
}

fun AssistantResult.toReply(noText: String): AiReply = when (this) {
    is AssistantResult.Success -> AiReply.Answer(text)
    AssistantResult.NotConfigured -> AiReply.Problem("Set up an AI engine in Settings → Translation first.")
    AssistantResult.NoText -> AiReply.Problem(noText)
    is AssistantResult.Failed -> AiReply.Problem(message)
}

/** A progress state, the selectable answer with its provenance note, or the problem with a retry. */
@Composable
fun AiReplyView(reply: AiReply, onRetry: () -> Unit) {
    when (reply) {
        AiReply.Loading -> Column {
            // A spinner is an animation; e-ink gets words.
            if (LocalEinkMode.current) Text("Reading the chapters…")
            else CircularProgressIndicator(Modifier.padding(vertical = 8.dp))
        }
        is AiReply.Answer -> Column {
            SelectionContainer { Text(reply.text) }
            Spacer(Modifier.height(8.dp))
            Text(
                AI_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        is AiReply.Problem -> Column {
            Text(reply.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) { Text("Try again") }
        }
    }
}

const val AI_NOTE = "Written by your AI provider from the chapters you've read"
