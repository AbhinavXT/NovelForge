package com.abhinavxt.novelreader.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatLineSpacing
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhinavxt.novelreader.data.model.PageTransition
import com.abhinavxt.novelreader.data.model.ReaderFont
import com.abhinavxt.novelreader.data.model.ReaderSettings
import com.abhinavxt.novelreader.data.model.ReaderTheme
import com.abhinavxt.novelreader.data.model.ReadingMode
import com.abhinavxt.novelreader.ui.screens.getThemeColors
import com.abhinavxt.novelreader.ui.screens.toFontFamily

/**
 * Unified quick-settings bottom sheet for the reader.
 *
 * Uses LazyColumn internally so nested scrolling works correctly
 * with ModalBottomSheet — all fonts (including Accessibility and
 * Monospace categories) are reachable by scrolling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSettingsSheet(
    settings: ReaderSettings,
    onSettingsChanged: (ReaderSettings) -> Unit,
    onNavigateToPronunciation: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // LazyColumn instead of scrollable Column — plays nicely with
        // ModalBottomSheet's own drag/scroll gesture handling.
        // This fixes the bug where bottom font categories were unreachable.
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // ── Section 1: Reading Mode Toggle ──────────────────
            item {
                SectionHeader(title = "Reading Mode")
                ReadingModeToggle(
                    currentMode = settings.readingMode,
                    onModeChanged = { mode ->
                        onSettingsChanged(settings.copy(readingMode = mode))
                    }
                )
                SectionDivider()
            }

            // ── Section 2: Typography Controls ──────────────────
            item {
                TypographySection(
                    fontSize = settings.fontSize,
                    lineSpacing = settings.lineSpacing,
                    horizontalMargin = settings.horizontalMargin,
                    onFontSizeChanged = { size ->
                        onSettingsChanged(settings.copy(fontSize = size))
                    },
                    onLineSpacingChanged = { spacing ->
                        onSettingsChanged(settings.copy(lineSpacing = spacing))
                    },
                    onMarginChanged = { margin ->
                        onSettingsChanged(settings.copy(horizontalMargin = margin))
                    }
                )
                SectionDivider()
            }

            // ── Section 3: Theme Grid ───────────────────────────
            item {
                SectionHeader(title = "Theme")
                ThemeGrid(
                    currentTheme = settings.theme,
                    onThemeSelected = { theme ->
                        onSettingsChanged(settings.copy(theme = theme))
                    }
                )
                SectionDivider()
            }

            // ── Section 4: Font List (each font is its own item) ─
            item {
                SectionHeader(title = "Font")
            }

            // Emit each font category + its fonts as individual items
            // so they all participate in the LazyColumn scroll
            val fontsByCategory = ReaderFont.entries.groupBy { it.category }
            fontsByCategory.forEach { (category, fonts) ->
                item(key = "font_cat_${category.name}") {
                    Text(
                        text = category.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                items(
                    items = fonts,
                    key = { "font_${it.name}" }
                ) { font ->
                    FontRow(
                        font = font,
                        isSelected = font == settings.font,
                        onClick = { onSettingsChanged(settings.copy(font = font)) }
                    )
                }

                item(key = "font_spacer_${category.name}") {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // ── Section 5: Page Transition (only in paged mode) ─
            if (settings.readingMode == ReadingMode.PAGED) {
                item {
                    SectionDivider()
                    SectionHeader(title = "Page Turn Animation")
                    PageTransitionPicker(
                        currentTransition = settings.pageTransition,
                        onTransitionSelected = { transition ->
                            onSettingsChanged(settings.copy(pageTransition = transition))
                        }
                    )
                }
            }

            // ── Section 6: Extras ───────────────────────────────
            item {
                SectionDivider()
                SectionHeader(title = "More")

                SettingToggle(
                    label = "Keep Screen On",
                    description = "Prevent screen dimming while reading",
                    checked = settings.keepScreenOn,
                    onCheckedChange = { enabled ->
                        onSettingsChanged(settings.copy(keepScreenOn = enabled))
                    }
                )

                SettingToggle(
                    label = "Volume Key Navigation",
                    description = "Use volume buttons to turn pages/scroll",
                    checked = settings.volumeKeyNavigation,
                    onCheckedChange = { enabled ->
                        onSettingsChanged(settings.copy(volumeKeyNavigation = enabled))
                    }
                )

                // Pronunciation Dictionary link
                if (onNavigateToPronunciation != null) {
                    Surface(
                        onClick = onNavigateToPronunciation,
                        color = Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Pronunciation Dictionary",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Manage TTS word replacements",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Bottom spacing for safe area
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Sub-composables
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ReadingModeToggle(
    currentMode: ReadingMode,
    onModeChanged: (ReadingMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ReadingMode.entries.forEach { mode ->
            val isSelected = mode == currentMode
            Surface(
                onClick = { onModeChanged(mode) },
                color = if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    Color.Transparent,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .padding(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (mode == ReadingMode.SCROLL)
                            Icons.Default.ViewDay
                        else
                            Icons.Default.AutoStories,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = mode.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TypographySection(
    fontSize: Int,
    lineSpacing: Float,
    horizontalMargin: Int,
    onFontSizeChanged: (Int) -> Unit,
    onLineSpacingChanged: (Float) -> Unit,
    onMarginChanged: (Int) -> Unit
) {
    // Font Size ± row
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.FormatSize,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Font Size", style = MaterialTheme.typography.bodyMedium)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (fontSize > 12) onFontSizeChanged(fontSize - 1) },
                enabled = fontSize > 12,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Decrease", modifier = Modifier.size(18.dp))
            }
            Text(
                text = "$fontSize",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(32.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(
                onClick = { if (fontSize < 32) onFontSizeChanged(fontSize + 1) },
                enabled = fontSize < 32,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Increase", modifier = Modifier.size(18.dp))
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    CompactSlider(
        icon = Icons.Default.FormatLineSpacing,
        label = "Line Spacing",
        value = lineSpacing,
        valueDisplay = String.format("%.1fx", lineSpacing),
        onValueChange = onLineSpacingChanged,
        valueRange = 1.2f..2.4f,
        steps = 5
    )

    Spacer(modifier = Modifier.height(4.dp))

    CompactSlider(
        icon = Icons.Default.SwapHoriz,
        label = "Margins",
        value = horizontalMargin.toFloat(),
        valueDisplay = "${horizontalMargin}dp",
        onValueChange = { onMarginChanged(it.toInt()) },
        valueRange = 8f..40f,
        steps = 7
    )
}

@Composable
private fun CompactSlider(
    icon: ImageVector,
    label: String,
    value: Float,
    valueDisplay: String,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
            Text(valueDisplay, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onValueChange,
            valueRange = valueRange, steps = steps, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ThemeGrid(
    currentTheme: ReaderTheme,
    onThemeSelected: (ReaderTheme) -> Unit
) {
    val themes = ReaderTheme.entries
    val dayThemes = themes.filter { !it.isDark }
    val darkThemes = themes.filter { it.isDark }

    Text("Light", style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(bottom = 6.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(bottom = 12.dp)
    ) {
        dayThemes.forEach { theme ->
            ThemeCircle(theme, theme == currentTheme) { onThemeSelected(theme) }
        }
    }

    Text("Dark", style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(bottom = 6.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        darkThemes.take(4).forEach { theme ->
            ThemeCircle(theme, theme == currentTheme) { onThemeSelected(theme) }
        }
    }
    if (darkThemes.size > 4) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            darkThemes.drop(4).forEach { theme ->
                ThemeCircle(theme, theme == currentTheme) { onThemeSelected(theme) }
            }
        }
    }
}

@Composable
private fun ThemeCircle(
    theme: ReaderTheme,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val themeColors = getThemeColors(theme)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(themeColors.background)
                .then(
                    if (isSelected)
                        Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    else
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = themeColors.text, fontSize = 16.sp, fontFamily = FontFamily.Serif)
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            theme.displayName.take(6),
            style = MaterialTheme.typography.labelSmall, fontSize = 8.sp,
            color = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
            maxLines = 1
        )
    }
}

@Composable
private fun FontRow(
    font: ReaderFont,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clickable { onClick() },
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else
            Color.Transparent,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = font.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = font.toFontFamily(),
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "The quick brown fox jumps over the lazy dog",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = font.toFontFamily(),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
            }
            if (isSelected) {
                Icon(
                    Icons.Default.Check, contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun PageTransitionPicker(
    currentTransition: PageTransition,
    onTransitionSelected: (PageTransition) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PageTransition.entries.forEach { transition ->
            val isSelected = transition == currentTransition
            Surface(
                onClick = { onTransitionSelected(transition) },
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = transition.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(vertical = 10.dp),
                    textAlign = TextAlign.Center,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingToggle(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}