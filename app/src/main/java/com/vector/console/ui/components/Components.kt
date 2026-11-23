package com.vector.console.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vector.console.ui.theme.Bone
import com.vector.console.ui.theme.BoneDim
import com.vector.console.ui.theme.ControlLine
import com.vector.console.ui.theme.Hairline
import com.vector.console.ui.theme.HairlineSoft
import com.vector.console.ui.theme.Ink
import com.vector.console.ui.theme.Muted
import com.vector.console.ui.theme.MutedDeep
import com.vector.console.ui.theme.Panel
import com.vector.console.ui.theme.PanelDeep
import com.vector.console.ui.theme.PanelRaised
import com.vector.console.ui.theme.Seal

// ------------------------------------------------------------------ chrome

/**
 * The stamp at the top of every screen. Seal red is the only coloured ink in the
 * system, so it is spent here and nowhere else in the chrome.
 */
@Composable
fun ClassificationBar() {
    Column(modifier = Modifier.fillMaxWidth().background(Ink)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(Seal)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "OVERSIGHT // EYES ONLY",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.20.sp),
                color = Seal
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "NOFORN",
                style = MaterialTheme.typography.labelSmall,
                color = MutedDeep
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Seal.copy(alpha = 0.55f))
        )
    }
}

@Composable
fun VectorTopBar(onSettings: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(Ink)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BracketEye(size = 26.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "OVERSIGHT",
                    style = MaterialTheme.typography.displayMedium.copy(letterSpacing = 0.02.sp),
                    color = Bone
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "SITUATIONAL AWARENESS SYSTEM",
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 0.22.sp,
                        fontSize = 8.sp
                    ),
                    color = MutedDeep
                )
            }
            LiveChip()
            Spacer(Modifier.width(4.dp))
            SettingsKey(onSettings)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Bone)
        )
    }
}

/** The brand mark: two brackets holding a pupil. Drawn, not an icon font. */
@Composable
fun BracketEye(size: Dp = 24.dp, color: Color = Bone) {
    Box(modifier = Modifier.size(size)) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val stroke = w * 0.09f
            val pad = w * 0.06f
            // left bracket
            drawLine(color, Offset(pad + w * 0.16f, h * 0.18f), Offset(pad, h * 0.5f), stroke)
            drawLine(color, Offset(pad, h * 0.5f), Offset(pad + w * 0.16f, h * 0.82f), stroke)
            // right bracket
            drawLine(color, Offset(w - pad - w * 0.16f, h * 0.18f), Offset(w - pad, h * 0.5f), stroke)
            drawLine(color, Offset(w - pad, h * 0.5f), Offset(w - pad - w * 0.16f, h * 0.82f), stroke)
            // pupil
            drawCircle(color, radius = w * 0.09f, center = Offset(w * 0.5f, h * 0.5f))
        }
    }
}

/** Square status chip. Reads by inversion, so it survives greyscale. */
@Composable
fun LiveChip(label: String = "LIVE") {
    Row(
        modifier = Modifier
            .border(1.dp, ControlLine)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(5.dp).background(Seal))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.16.sp),
            color = BoneDim
        )
    }
}

@Composable
private fun SettingsKey(onSettings: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(46.dp)
            .background(if (pressed) PanelRaised else Ink)
            .clickable(interactionSource = source, indication = null, onClick = onSettings),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = "Settings",
            tint = if (pressed) Bone else Muted,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ------------------------------------------------------------- section marks

/**
 * Stamped section rule: bone tick, mono label, hairline running to the trailing
 * value. This is the device that replaces the old card-stack headers.
 */
@Composable
fun SectionLabel(
    label: String,
    modifier: Modifier = Modifier,
    trailing: String? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().height(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(2.dp).height(12.dp).background(Bone))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = BoneDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
                .height(1.dp)
                .background(HairlineSoft)
        )
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MutedDeep,
                maxLines = 1
            )
        }
    }
}

@Composable
fun FieldLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MutedDeep,
        modifier = modifier
    )
}

/**
 * Page head. `dense` collapses it to a single ruled line — used where the
 * screen is short enough that a two-line head would eat the content it heads.
 */
@Composable
fun ScreenHeader(
    kicker: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    dense: Boolean = false
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (dense) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.width(2.dp).height(10.dp).background(Seal))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = kicker.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Seal,
                    maxLines = 1
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Bone,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(2.dp).height(10.dp).background(Seal))
            Spacer(Modifier.width(8.dp))
            Text(
                text = kicker.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Seal
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = Bone
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
    }
}

// ------------------------------------------------------------------ surfaces

@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    header: String? = null,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Hairline)
            .background(Panel)
    ) {
        if (header != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PanelRaised)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.width(2.dp).height(10.dp).background(Bone))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = header.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Bone
                )
                if (trailing != null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = trailing,
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedDeep
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        }
        Column(modifier = Modifier.padding(padding), content = content)
    }
}

@Composable
fun DividerLine(modifier: Modifier = Modifier, color: Color = HairlineSoft) {
    Box(modifier = modifier.fillMaxWidth().height(1.dp).background(color))
}

/** Corner ticks — the one framing device, used on the content well. */
fun Modifier.frameTicks(color: Color = Hairline, length: Dp = 10.dp): Modifier = drawWithContent {
    drawContent()
    val l = length.toPx()
    val inset = 0f
    val c = color
    // top-left
    drawLine(c, Offset(inset, inset), Offset(inset + l, inset))
    drawLine(c, Offset(inset, inset), Offset(inset, inset + l))
    // top-right
    drawLine(c, Offset(size.width - inset, inset), Offset(size.width - inset - l, inset))
    drawLine(c, Offset(size.width - inset, inset), Offset(size.width - inset, inset + l))
    // bottom-left
    drawLine(c, Offset(inset, size.height - inset), Offset(inset + l, size.height - inset))
    drawLine(c, Offset(inset, size.height - inset), Offset(inset, size.height - inset - l))
    // bottom-right
    drawLine(c, Offset(size.width - inset, size.height - inset), Offset(size.width - inset - l, size.height - inset))
    drawLine(c, Offset(size.width - inset, size.height - inset), Offset(size.width - inset, size.height - inset - l))
}

// ------------------------------------------------------------------- inputs

@Composable
fun VectorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MutedDeep
            )
        },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Bone),
        singleLine = singleLine,
        minLines = minLines,
        shape = MaterialTheme.shapes.extraSmall,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Bone,
            unfocusedBorderColor = ControlLine,
            cursorColor = Bone,
            focusedContainerColor = PanelDeep,
            unfocusedContainerColor = PanelDeep
        )
    )
}

// ------------------------------------------------------------------ actions

/**
 * Primary key. Bone fill, ink text, mono caps. On touch-down the face sinks 2dp
 * and the offset block under it collapses — the control reads as a physical key.
 */
@Composable
fun GoldButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val sink by animateDpAsState(
        targetValue = if (pressed && enabled) 2.dp else 0.dp,
        animationSpec = tween(durationMillis = 60),
        label = "key-sink"
    )
    Box(modifier = modifier) {
        if (enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(y = 2.dp)
                    .background(Bone.copy(alpha = 0.26f))
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, sink.roundToPx()) }
                .height(48.dp)
                .background(if (enabled) Bone else Bone.copy(alpha = 0.12f))
                .clickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                )
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) Ink else MutedDeep,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Secondary key. Outline at control contrast; inverts to a filled well on press. */
@Composable
fun OutlineActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(46.dp)
            .background(if (pressed && enabled) Bone else Color.Transparent)
            .border(1.dp, if (enabled) ControlLine else Hairline)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = when {
                !enabled -> MutedDeep
                pressed -> Ink
                else -> BoneDim
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun Tag(
    text: String,
    color: Color = MutedDeep,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.45f))
            .background(color.copy(alpha = 0.06f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, letterSpacing = 0.12.sp),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// --------------------------------------------------------------------- data

@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MutedDeep,
            modifier = Modifier.width(96.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = BoneDim,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * A bordered toggle. Selected inverts to bone/ink; unselected is a bare keyline.
 * This is the only filter/chip control in the system.
 */
@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier = modifier
            .border(1.dp, if (selected) Bone else ControlLine)
            .background(
                when {
                    selected -> Bone
                    pressed -> PanelRaised
                    else -> Color.Transparent
                }
            )
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = when {
                selected -> Ink
                pressed -> Bone
                else -> Muted
            }
        )
    }
}

@Composable
fun SegmentedTabs(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, ControlLine)
    ) {
        options.forEachIndexed { index, option ->
            val active = index == selected
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = source,
                        indication = null,
                        onClick = { onSelect(index) }
                    )
                    .background(
                        when {
                            active -> Bone
                            pressed -> PanelRaised
                            else -> Color.Transparent
                        }
                    )
                    .padding(vertical = 10.dp, horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        active -> Ink
                        pressed -> Bone
                        else -> Muted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (index != options.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(if (active || index + 1 == selected) Bone else HairlineSoft)
                )
            }
        }
    }
}

// -------------------------------------------------------------------- states

/** In-flight state: one sweeping bone bar. No spinner, no idle animation. */
@Composable
fun LoadingLine(label: String = "CONTACTING PUBLIC SOURCE") {
    val transition = rememberInfiniteTransition(label = "sweep")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep-phase"
    )
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(HairlineSoft)
                .clipToBounds()
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val bar = maxWidth * 0.28f
                Box(
                    modifier = Modifier
                        .offset(x = (maxWidth - bar) * phase)
                        .width(bar)
                        .height(2.dp)
                        .background(Bone)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Muted
        )
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .border(1.dp, Hairline),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "—",
                style = MaterialTheme.typography.titleLarge,
                color = MutedDeep
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = BoneDim,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MutedDeep,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 320.dp)
        )
    }
}

// ----------------------------------------------------------------- modifiers

/** Faint plotting grid. Used behind live map/feed surfaces only. */
fun Modifier.tacticalGrid(): Modifier = drawBehind {
    val step = 32.dp.toPx()
    val color = HairlineSoft.copy(alpha = 0.55f)
    var x = 0f
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

/** Convenience for a stamped, uppercase metric caption. */
@Composable
fun MetricCaption(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = MaterialTheme.typography.headlineLarge,
    color: Color = Bone
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = valueStyle, color = color)
        Spacer(Modifier.height(4.dp))
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MutedDeep
        )
    }
}
