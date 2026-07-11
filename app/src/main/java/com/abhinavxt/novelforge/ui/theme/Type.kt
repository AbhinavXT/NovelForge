package com.abhinavxt.novelforge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.abhinavxt.novelforge.R
import com.abhinavxt.novelforge.data.AppFont

// ── App-wide font families ──────────────────────────────────────
// Outfit: geometric sans-serif for headings — clean, modern, distinctive.
// Literata: the Google Play Books font — optimized for screen reading.
// These replace FontFamily.Default (Roboto) which has no personality.
val HeadingFont = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold),
)

val BodyFont = FontFamily(
    Font(R.font.literata_regular, FontWeight.Normal),
)

// ── Material3 Typography ────────────────────────────────────────
// Tighter letter-spacing on headings = more premium feel.
// Generous line-height on body = comfortable reading.
val Typography = Typography(
    // Display — splash/hero text (rarely used, but defined for completeness)
    displayLarge = TextStyle(
        fontFamily = HeadingFont,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.5).sp
    ),

    // Headline — screen titles like "Good evening", section headers
    headlineLarge = TextStyle(
        fontFamily = HeadingFont,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = HeadingFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = HeadingFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),

    // Title — card headers, top bar titles, section labels
    titleLarge = TextStyle(
        fontFamily = HeadingFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = HeadingFont,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily = HeadingFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),

    // Body — descriptions, paragraphs, card content
    bodyLarge = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.3.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp
    ),
    bodySmall = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp
    ),

    // Label — buttons, chips, badges, metadata
    labelLarge = TextStyle(
        fontFamily = HeadingFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = HeadingFont,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = HeadingFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3.sp
    ),
)

// ── App-wide font selection (Settings → Appearance → Font) ──────
//
// AppFont.DEFAULT keeps the Outfit/Literata pairing above. Any other
// choice applies one family to every text style. buildTypography()
// copies the tuned Typography val so sizes, weights, line heights and
// letter spacing are preserved — only the families change.

fun AppFont.toFontFamily(): FontFamily = when (this) {
    // DEFAULT is resolved in buildTypography (heading/body pair);
    // returning BodyFont here keeps callers like preview rows sane.
    AppFont.DEFAULT -> BodyFont
    AppFont.LITERATA -> FontFamily(Font(R.font.literata_regular, FontWeight.Normal))
    AppFont.LORA -> FontFamily(Font(R.font.lora_regular, FontWeight.Normal))
    AppFont.MERRIWEATHER -> FontFamily(Font(R.font.merriweather_regular, FontWeight.Normal))
    AppFont.CRIMSON_TEXT -> FontFamily(Font(R.font.crimson_text_regular, FontWeight.Normal))
    AppFont.SOURCE_SANS -> FontFamily(Font(R.font.source_sans_regular, FontWeight.Normal))
    AppFont.NOTO_SANS -> FontFamily(Font(R.font.noto_sans_regular, FontWeight.Normal))
    AppFont.OPEN_DYSLEXIC -> FontFamily(Font(R.font.open_dyslexic_regular, FontWeight.Normal))
    AppFont.JETBRAINS_MONO -> FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.Normal))
    AppFont.DANCING_SCRIPT -> FontFamily(Font(R.font.dancing_script_regular, FontWeight.Normal))
}

fun buildTypography(appFont: AppFont): Typography {
    if (appFont == AppFont.DEFAULT) return Typography

    val family = appFont.toFontFamily()
    val base = Typography
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
}
