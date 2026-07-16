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
    // Reds & oranges
    0xFFE53935L, // red
    0xFFC48B8BL, // dusty rose
    0xFFF4511EL, // orange red
    0xFFBF5B3FL, // terracotta
    0xFFFF7043L, // deep orange
    0xFF6D4C41L, // brown
    // Yellows & greens
    0xFFFFB300L, // amber
    0xFFFDD835L, // yellow
    0xFF9E9D24L, // olive
    0xFFC0CA33L, // lime
    0xFF87A96BL, // sage
    0xFF7CB342L, // light green
    0xFF2E7D32L, // green
    0xFF00C853L, // spring green
    // Teals & blues
    0xFF00897BL, // teal
    0xFF00ACC1L, // cyan
    0xFF039BE5L, // sky blue
    0xFF1E88E5L, // blue
    0xFF5B84A8L, // steel blue
    0xFF3949ABL, // indigo
    // Purples & pinks
    0xFF5E35B1L, // deep purple
    0xFF9C8ACBL, // lavender
    0xFF6650A4L, // purple (default)
    0xFF8E24AAL, // violet
    0xFFC2189CL, // magenta
    0xFFD81B60L, // pink
    0xFF880E4FL, // wine
    // Neutrals
    0xFF546E7AL, // blue grey
    0xFF37474FL, // charcoal
)

// ── Custom app-theme text swatches ───────────────────────────────
// Mirrors the reader's custom text picker: warm inks for light
// backgrounds, muted lights for dark. The "Auto" option in the
// picker maps to ThemePreferences.CUSTOM_APP_TEXT_AUTO instead of
// a value from this list.
val appTextSwatches = listOf(
    // Inks for light backgrounds
    0xFF000000L, 0xFF2D2A26L, 0xFF3B3630L, 0xFF5F4B32L, 0xFF37474FL,
    0xFF263238L, 0xFF1B4332L, 0xFF1E3A5FL, 0xFF4A2C2AL, 0xFF3E2C41L,
    // Muted lights for dark backgrounds
    0xFFBFC7CFL, 0xFFC5C1B8L, 0xFFD8D4CCL, 0xFFE6E1D6L, 0xFFECEFF1L,
    0xFFFFFFFFL, 0xFFE8D5B0L, 0xFFA8C5A0L, 0xFFA9BCD4L, 0xFFC9ADA7L,
)

// ── Custom app-theme background swatches ─────────────────────────
// Same curated range as the reader's custom background picker:
// warm papers, cool greys, true darks. Text/on-colors are derived
// from luminance in customAppColorScheme().
val appBackgroundSwatches = listOf(
    // Whites & warm papers
    0xFFFFFFFFL, 0xFFF5F0E8L, 0xFFFBF0D9L, 0xFFFDF6E3L, 0xFFF0EAD6L,
    0xFFF2E2C9L, 0xFFEDE7D9L,
    // Cool & tinted lights (eye-care green, lavender, blush)
    0xFFE8EDF2L, 0xFFDCE3EAL, 0xFFE3EDCDL, 0xFFC9D1C8L, 0xFFEAE4F2L,
    0xFFF7E6E3L,
    // Dark greys & true darks
    0xFF3A3A3AL, 0xFF2B2B2BL, 0xFF1E2127L, 0xFF16181DL, 0xFF101216L,
    0xFF0B0D10L, 0xFF000000L,
    // Tinted darks (navy, forest, violet, nord)
    0xFF0D1B2AL, 0xFF1B2621L, 0xFF241B2FL, 0xFF2E3440L,
)