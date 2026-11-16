package com.abhinavxt.novelforge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.data.tts.CastVoice
import com.abhinavxt.novelforge.data.tts.CastVoices
import com.abhinavxt.novelforge.data.tts.Speakers
import com.abhinavxt.novelforge.data.tts.VoiceCast
import com.abhinavxt.novelforge.data.tts.VoiceCasting

/**
 * Who reads what in this book, with the voices of the active engine (a multi-speaker model's speakers,
 * other downloaded Piper voices, or installed system voices): the narrator, each character from the
 * codex, and anyone the app can't tell. Saved as it changes; used from the next play.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterVoicesSheet(
    novelId: String,
    voices: CastVoices,
    casting: VoiceCasting,
    onDismiss: () -> Unit
) {
    val speakers = voices.voices
    var cast by remember(novelId, voices.key) { mutableStateOf(casting.load(novelId, voices.key)) }
    // For engines whose narrator is just the chosen voice, that voice is the narrator in the cast.
    val narrator = if (voices.narratorPickable) cast.narrator else voices.narrator
    var characters by remember { mutableStateOf<List<String>?>(null) }
    var genders by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    LaunchedEffect(novelId) {
        characters = casting.characters(novelId)
        genders = casting.genders(novelId)
    }
    fun update(new: VoiceCast) {
        cast = new
        casting.save(novelId, voices.key, new)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("Character voices", style = MaterialTheme.typography.titleLarge)
            }
            if (voices.narratorPickable) {
                item {
                    VoiceRow("Narrator", speakers, cast.narrator, allowNone = false) { update(cast.copy(narrator = it ?: "0")) }
                }
            } else {
                item {
                    Text(
                        "The narrator keeps the voice you chose for reading; pick the voices of characters below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Give characters their own voices", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Quotes are read in the speaker's voice when the text says who's talking " +
                                "(\"said Lin\") or only one character is in the paragraph. Applies from the next time you press play.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = cast.enabled, onCheckedChange = { update(cast.copy(enabled = it)) })
                }
            }
            if (cast.enabled) {
                item {
                    VoiceRow("Anyone else", speakers, cast.otherDialogue, allowNone = true) { update(cast.copy(otherDialogue = it)) }
                }
                val list = characters
                when {
                    list == null -> Unit
                    list.isEmpty() -> item {
                        Text(
                            "No characters yet. Open the codex and scan the book, then come back.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        item {
                            OutlinedButton(
                                onClick = {
                                    update(cast.copy(characters = Speakers.autoCastVoices(list, genders, speakers, narrator)))
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Cast everyone automatically") }
                            if (genders.isEmpty()) {
                                Text(
                                    "Translated books know who's female or male from the glossary; here, fix any voice by hand.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(list, key = { it }) { name ->
                            VoiceRow(name, speakers, cast.characters[name], allowNone = true) { id ->
                                update(cast.copy(characters = if (id == null) cast.characters - name else cast.characters + (name to id)))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(label: String, speakers: List<CastVoice>, current: String?, allowNone: Boolean, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            TextButton(onClick = { open = true }) {
                Text(com.abhinavxt.novelforge.data.tts.Speakers.pickLabel(speakers, current), maxLines = 1)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (allowNone) DropdownMenuItem(text = { Text("Narrator's voice") }, onClick = { open = false; onPick(null) })
                speakers.forEach { speaker ->
                    DropdownMenuItem(text = { Text(speaker.label) }, onClick = { open = false; onPick(speaker.id) })
                }
            }
        }
    }
}
