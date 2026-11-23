package com.vector.console.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vector.console.ui.theme.Bone
import com.vector.console.ui.theme.BoneDim
import com.vector.console.ui.theme.Hairline
import com.vector.console.ui.theme.HairlineSoft
import com.vector.console.ui.theme.Muted
import com.vector.console.ui.theme.MutedDeep
import com.vector.console.ui.theme.Panel
import com.vector.console.ui.theme.PanelRaised

/**
 * One figure on a ruled form. The number is the element; the caption sits under
 * it as a stamped label. No fill, no shadow — the rule does the grouping.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    accent: Color = Bone
) {
    Column(
        modifier = modifier
            .background(Panel)
            .border(1.dp, Hairline)
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
            color = MutedDeep
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = accent
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
            color = Muted
        )
    }
}

/** Index entry. Presses to a raised well so the row answers the finger. */
@Composable
fun ActionTile(
    index: String,
    label: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Column(
        modifier = modifier
            .background(if (pressed) PanelRaised else Panel)
            .border(1.dp, if (pressed) Bone else Hairline)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(2.dp).height(10.dp).background(Bone))
            Spacer(Modifier.width(7.dp))
            Text(
                text = index,
                style = MaterialTheme.typography.labelSmall,
                color = if (pressed) Bone else MutedDeep
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.titleSmall,
            color = Bone
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
            color = MutedDeep
        )
    }
}

/** Two-keyline data row: label column, value column, nothing else. */
@Composable
fun DataRow(
    label: String,
    value: String,
    valueColor: Color = BoneDim,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
            color = MutedDeep,
            modifier = Modifier.width(104.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Status line: square mark, then the word. Colour is the second cue, never the first. */
@Composable
fun StatusLine(text: String, color: Color = Muted, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(6.dp)
                .height(6.dp)
                .background(color)
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = BoneDim
        )
    }
}

/** A ruled row with a leading index — the file-index idiom, reusable. */
@Composable
fun IndexRow(
    index: String,
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (pressed) PanelRaised else Color.Transparent)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .padding(horizontal = 2.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = index,
                style = MaterialTheme.typography.labelMedium,
                color = if (pressed) Bone else MutedDeep,
                modifier = Modifier.width(30.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Bone
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedDeep
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "→",
                style = MaterialTheme.typography.titleMedium,
                color = if (pressed) Bone else MutedDeep
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(HairlineSoft))
    }
}