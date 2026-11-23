package com.vector.console.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import com.vector.console.data.CctvTaxonomy

/** Reads the animator scale as a multiplier, not a boolean. */
@Composable
fun rememberReducedMotionScale(): Float {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f)
    }
}

/**
 * One orchestrated entrance per keyed content swap. No staggering, no ambient
 * motion, and no effect at all when the user asked the system for less motion.
 */
@Composable
fun Modifier.enter(key: Any? = null): Modifier {
    val scale = rememberReducedMotionScale()
    val duration = CctvTaxonomy.scaledDuration(180L, scale).toInt()
    val instant = duration == 0
    var shown by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) { shown = true }
    val alpha by animateFloatAsState(
        targetValue = if (shown || instant) 1f else 0f,
        animationSpec = tween(durationMillis = duration),
        label = "enter-alpha"
    )
    val lift by animateFloatAsState(
        targetValue = if (shown || instant) 0f else 12f,
        animationSpec = tween(durationMillis = duration),
        label = "enter-lift"
    )
    return this.graphicsLayer(alpha = alpha, translationY = if (instant) 0f else lift)
}
