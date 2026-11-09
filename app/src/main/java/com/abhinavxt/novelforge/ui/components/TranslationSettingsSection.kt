package com.abhinavxt.novelforge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.abhinavxt.novelforge.data.translation.AiProvider
import com.abhinavxt.novelforge.data.translation.TranslationEngine
import com.abhinavxt.novelforge.data.translation.TranslationSettings
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/** Settings → Translation: which engine, the AI provider's model and key, and the language to translate into. */
@Composable
fun TranslationSettingsSection(settings: TranslationSettings) {
    val config by settings.config.collectAsState()
    // Every language the on-device engine knows; AI models handle all of them too.
    val languages = remember {
        TranslateLanguage.getAllLanguages()
            .map { it to Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }
            .sortedBy { it.second }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TranslationEngine.entries.forEach { engine ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = config.engine == engine, onClick = { settings.setEngine(engine) }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = config.engine == engine, onClick = { settings.setEngine(engine) })
                Column {
                    Text(engine.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when (engine) {
                            TranslationEngine.AI -> "Best quality, learns names as it goes. Chapter text is sent to the provider you pick, who charges you per use."
                            TranslationEngine.ON_DEVICE -> "Free and private: about 30 MB per language, downloaded once. Rougher on fiction, and it can't polish English that's already English, only apply the glossary."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (config.engine == TranslationEngine.AI) {
            Picker("Provider", config.provider.label, AiProvider.entries.map { it to it.label }) { settings.setProvider(it) }
            // Keyed on the provider: each one remembers its own model and key.
            var model by remember(config.provider) { mutableStateOf(config.model) }
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; settings.setModel(it) },
                label = { Text("Model") },
                placeholder = { Text(if (config.provider == AiProvider.CLAUDE) "claude-opus-5-5" else "The model name from your provider") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            var key by remember(config.provider) { mutableStateOf(config.apiKey) }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it; settings.setApiKey(it) },
                label = { Text(if (config.provider == AiProvider.CUSTOM) "API key (if it needs one)" else "API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            if (config.provider == AiProvider.CUSTOM) {
                var url by remember { mutableStateOf(config.customUrl) }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; settings.setCustomUrl(it) },
                    label = { Text("Address") },
                    placeholder = { Text("https://example.com/v1") },
                    supportingText = { Text("An OpenAI-compatible endpoint, over https.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                "The key stays on this phone and is left out of backups.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Picker("Translate into", config.targetLanguageName, languages) { settings.setTargetLanguage(it) }
    }
}

@Composable
private fun <T> Picker(label: String, current: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(current) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(value) })
                }
            }
        }
    }
}
