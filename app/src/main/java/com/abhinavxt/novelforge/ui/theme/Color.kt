package com.abhinavxt.novelforge.ui.theme

import androidx.compose.ui.graphics.Color

// ── Default Purple (original) ────────────────────────────────────
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// ── Blue ─────────────────────────────────────────────────────────
val Blue80 = Color(0xFFAEC6FF)
val BlueGrey80 = Color(0xFFBFC6DC)
val BlueAccent80 = Color(0xFF8ECAFF)

val Blue40 = Color(0xFF3565B0)
val BlueGrey40 = Color(0xFF555F71)
val BlueAccent40 = Color(0xFF006494)

// ── Green ────────────────────────────────────────────────────────
val Green80 = Color(0xFFA6D4A2)
val GreenGrey80 = Color(0xFFB7CCB4)
val GreenAccent80 = Color(0xFF80D8B0)

val Green40 = Color(0xFF386A34)
val GreenGrey40 = Color(0xFF52634F)
val GreenAccent40 = Color(0xFF006C4C)

// ── Teal ─────────────────────────────────────────────────────────
val Teal80 = Color(0xFF80D8CF)
val TealGrey80 = Color(0xFFB2CCC8)
val TealAccent80 = Color(0xFF70EFDE)

val Teal40 = Color(0xFF006A63)
val TealGrey40 = Color(0xFF4A6360)
val TealAccent40 = Color(0xFF005048)

// ── Red ──────────────────────────────────────────────────────────
val Red80 = Color(0xFFFFB3AB)
val RedGrey80 = Color(0xFFDBC2BE)
val RedAccent80 = Color(0xFFFFB4A9)

val Red40 = Color(0xFF9C4040)
val RedGrey40 = Color(0xFF6B5955)
val RedAccent40 = Color(0xFF8C1D18)

// ── Orange ───────────────────────────────────────────────────────
val Orange80 = Color(0xFFFFB870)
val OrangeGrey80 = Color(0xFFD4C4B0)
val OrangeAccent80 = Color(0xFFFFDDB3)

val Orange40 = Color(0xFF8B5000)
val OrangeGrey40 = Color(0xFF6B5D4B)
val OrangeAccent40 = Color(0xFF6D3A00)

// ── Pink ─────────────────────────────────────────────────────────
val Pink80Accent = Color(0xFFFFB1C8)
val PinkGrey80 = Color(0xFFE3BDC6)
val PinkAccent80 = Color(0xFFFFB0CA)

val Pink40Accent = Color(0xFFA23B65)
val PinkGrey40 = Color(0xFF74565F)
val PinkAccent40 = Color(0xFF8E4958)

// ── Indigo ───────────────────────────────────────────────────────
val Indigo80 = Color(0xFFBEC2FF)
val IndigoGrey80 = Color(0xFFC5C4DD)
val IndigoAccent80 = Color(0xFFC7BFFF)

val Indigo40 = Color(0xFF4A54A9)
val IndigoGrey40 = Color(0xFF5C5D72)
val IndigoAccent40 = Color(0xFF5B53A8)

// ── Cyan ─────────────────────────────────────────────────────────
val Cyan80 = Color(0xFF4FD8EB)
val CyanGrey80 = Color(0xFFB1CBD0)
val CyanAccent80 = Color(0xFF82D3E0)

val Cyan40 = Color(0xFF006876)
val CyanGrey40 = Color(0xFF4A6267)
val CyanAccent40 = Color(0xFF00687A)

// ── Amber ────────────────────────────────────────────────────────
val Amber80 = Color(0xFFF2BF48)
val AmberGrey80 = Color(0xFFD5C5A1)
val AmberAccent80 = Color(0xFFDDC66E)

val Amber40 = Color(0xFF785A00)
val AmberGrey40 = Color(0xFF6B5D3F)
val AmberAccent40 = Color(0xFF6C5E10)

// ── Preview chip colors (used in the color picker UI) ────────────
val PreviewPurple = Color(0xFF6650A4)
val PreviewBlue = Color(0xFF3565B0)
val PreviewGreen = Color(0xFF386A34)
val PreviewTeal = Color(0xFF006A63)
val PreviewRed = Color(0xFF9C4040)
val PreviewOrange = Color(0xFF8B5000)
val PreviewPink = Color(0xFFA23B65)
val PreviewIndigo = Color(0xFF4A54A9)
val PreviewCyan = Color(0xFF006876)
val PreviewAmber = Color(0xFF785A00)

// ── Custom scheme seed swatches ──────────────────────────────────
// Curated vivid seeds spanning the hue wheel — same swatch-row idea
// as the reader's custom background/text pickers. Each generates a
// full light/dark scheme via customColorScheme() in Theme.kt.
val customSeedSwatches = listOf(
    0xFFE53935L, // red
    0xFFFF7043L, // deep orange
    0xFFFFB300L, // amber
    0xFF7CB342L, // light green
    0xFF2E7D32L, // green
    0xFF00897BL, // teal
    0xFF00ACC1L, // cyan
    0xFF1E88E5L, // blue
    0xFF3949ABL, // indigo
    0xFF6650A4L, // purple (default)
    0xFF8E24AAL, // violet
    0xFFD81B60L, // pink
    0xFF6D4C41L, // brown
    0xFF546E7AL, // blue grey
)

// ── Custom app-theme background swatches ─────────────────────────
// Same curated range as the reader's custom background picker:
// warm papers, cool greys, true darks. Text/on-colors are derived
// from luminance in customAppColorScheme().
val appBackgroundSwatches = listOf(
    0xFFFFFFFFL, 0xFFF5F0E8L, 0xFFFBF0D9L, 0xFFEDE7D9L, 0xFFE8EDF2L,
    0xFFDCE3EAL, 0xFFC9D1C8L, 0xFF3A3A3AL, 0xFF2B2B2BL, 0xFF1E2127L,
    0xFF16181DL, 0xFF101216L, 0xFF0B0D10L, 0xFF000000L,
)