package com.vector.console.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// Redaction room: paper on black, seal red as the only coloured ink.
private val OversightColors = darkColorScheme(
    primary = Bone,
    onPrimary = Ink,
    primaryContainer = GoldSoft,
    onPrimaryContainer = Bone,
    secondary = Green,
    onSecondary = Ink,
    tertiary = Blue,
    onTertiary = Ink,
    background = Ink,
    onBackground = Bone,
    surface = Panel,
    onSurface = Bone,
    surfaceVariant = PanelRaised,
    onSurfaceVariant = Muted,
    outline = ControlLine,
    outlineVariant = Hairline,
    error = Alert,
    onError = Ink
)

// Every corner is square. Ruled forms, not cards.
private val OversightShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp)
)

@Composable
fun OversightTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Ink.toArgb()
            window.navigationBarColor = Ink.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = OversightColors,
        typography = OversightTypography,
        shapes = OversightShapes,
        content = content
    )
}