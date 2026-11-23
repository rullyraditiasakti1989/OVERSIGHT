package com.vector.console.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Two families, closed set. Mono carries every label, index, metric and status;
 * sans carries prose and the heavy display headings. Nothing else is allowed in.
 */
val DossierMono = FontFamily.Monospace
val DossierSans = FontFamily.SansSerif

/** Kept for call sites that still name the old serif role; it resolves to sans. */
val DossierSerif = FontFamily.SansSerif

val OversightTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = DossierSans,
        fontWeight = FontWeight.Black,
        fontSize = 40.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.03).sp
    ),
    displayMedium = TextStyle(
        fontFamily = DossierSans,
        fontWeight = FontWeight.Black,
        fontSize = 30.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.02).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = DossierSans,
        fontWeight = FontWeight.Black,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.02).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = DossierSans,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.01).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = DossierMono,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.06.sp
    ),
    titleLarge = TextStyle(
        fontFamily = DossierSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = DossierSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.5.sp,
        lineHeight = 21.sp
    ),
    titleSmall = TextStyle(
        fontFamily = DossierMono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.10.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = DossierSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = DossierSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = DossierSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 19.sp
    ),
    labelLarge = TextStyle(
        fontFamily = DossierMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.12.sp
    ),
    labelMedium = TextStyle(
        fontFamily = DossierMono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.14.sp
    ),
    labelSmall = TextStyle(
        fontFamily = DossierMono,
        fontWeight = FontWeight.Medium,
        fontSize = 9.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.16.sp
    )
)