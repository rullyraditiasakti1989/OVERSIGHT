package com.vector.console.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.vector.console.data.CctvCamera
import com.vector.console.data.IndonesiaCctv
import com.vector.console.ui.components.LoadingLine
import com.vector.console.ui.components.OutlineActionButton
import com.vector.console.ui.components.PanelCard
import com.vector.console.ui.components.ScreenHeader
import com.vector.console.ui.components.SectionLabel
import com.vector.console.ui.theme.Alert
import com.vector.console.ui.theme.Bone
import com.vector.console.ui.theme.Green
import com.vector.console.ui.theme.Hairline
import com.vector.console.ui.theme.Muted
import com.vector.console.ui.theme.MutedDeep
import com.vector.console.ui.theme.enter

@UnstableApi
@Composable
fun AreaCctvScreen(area: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var cameras by remember { mutableStateOf<List<CctvCamera>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<CctvCamera?>(null) }
    var streamError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(area) {
        loading = true
        val tokens = area.lowercase()
            .split(Regex("\\s+"))
            .filter { it !in setOf("kota", "kabupaten", "kab", "kab.", "kota.", "daerah") }
        val all = IndonesiaCctv.load(context)
        cameras = all.filter { c ->
            val hay = listOf(c.city, c.source, c.name).joinToString(" ").lowercase()
            tokens.all { hay.contains(it) }
        }.map { c ->
            val isImage = listOf(".jpg", ".jpeg", ".png").any { c.url.contains(it, true) }
            CctvCamera(
                id = c.id,
                name = c.name,
                country = "Indonesia",
                city = c.city,
                latitude = c.latitude,
                longitude = c.longitude,
                imageUrl = if (isImage) c.url else null,
                streamUrl = if (isImage) null else c.url,
                externalUrl = null,
                source = c.source,
                available = c.available
            )
        }
        loading = false
    }

    val total = cameras.size
    val live = cameras.count { it.available }
    val dead = total - live
    var limit by remember { mutableIntStateOf(24) }
    val visible = cameras.filter { it.available }.take(limit)

    val camera = selected
    if (camera != null) {
        DetailView(
            camera = camera,
            tick = 0,
            streamError = streamError,
            onStreamError = { streamError = it },
            onBack = { selected = null },
            onRefresh = { streamError = null },
            onOpenExternal = { link ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        OutlineActionButton(label = "Back", onClick = onBack)
        Spacer(Modifier.height(16.dp))
        ScreenHeader(
            kicker = "Area search",
            title = area,
            subtitle = "Public cameras matching this area, live ones first."
        )
        Spacer(Modifier.height(20.dp))

        // Counts sit on one ruled strip rather than three separate cards.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Hairline)
        ) {
            AreaMetric("TOTAL", total.toString(), Bone, Modifier.weight(1f))
            AreaMetric("LIVE", live.toString(), Green, Modifier.weight(1f))
            AreaMetric("OFFLINE", dead.toString(), Alert, Modifier.weight(1f))
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel(label = "Cameras in area", trailing = "$total FOUND")
        Spacer(Modifier.height(10.dp))

        if (loading) {
            LoadingLine(label = "READING AREA INDEX")
            Spacer(Modifier.height(12.dp))
        } else if (total == 0) {
            PanelCard {
                Text(
                    text = "No cameras found for \"$area\". Try the city or regency name without the " +
                        "KOTA / KABUPATEN prefix.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
        } else {
            Text(
                text = "Offline cameras are counted above and hidden from the grid.",
                style = MaterialTheme.typography.labelSmall,
                color = MutedDeep
            )
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.enter(key = area)
            ) {
                itemsIndexed(visible, key = { _, c -> c.id }) { index, item ->
                    CameraTile(
                        camera = item,
                        tick = 0,
                        onClick = { selected = item },
                        showPreview = index < 8
                    )
                }
                if (visible.size < live) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Spacer(Modifier.height(4.dp))
                            OutlineActionButton(
                                label = "Show more · ${live - visible.size} remaining",
                                onClick = { limit += 24 }
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun AreaMetric(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = color
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MutedDeep
        )
    }
}