package com.vector.console.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vector.console.data.GeoStore
import com.vector.console.data.PublicIntel
import com.vector.console.data.Store
import com.vector.console.ui.Screen
import com.vector.console.ui.components.BracketEye
import com.vector.console.ui.components.DividerLine
import com.vector.console.ui.components.IndexRow
import com.vector.console.ui.components.MetricCaption
import com.vector.console.ui.components.PanelCard
import com.vector.console.ui.components.SectionLabel
import com.vector.console.ui.theme.Bone
import com.vector.console.ui.theme.Green
import com.vector.console.ui.theme.Hairline
import com.vector.console.ui.theme.HairlineSoft
import com.vector.console.ui.theme.Muted
import com.vector.console.ui.theme.MutedDeep
import com.vector.console.ui.theme.Seal

private data class FileEntry(val index: String, val label: String, val detail: String, val screen: Screen)

private val files = listOf(
    FileEntry("01", "Intel", "Quakes, domain, IP, breaches", Screen.INTEL),
    FileEntry("02", "Identity", "NIK, phone, presence", Screen.IDENTITY),
    FileEntry("03", "Face", "Detect, crop, reverse", Screen.FACE),
    FileEntry("04", "Geofence", "Silent zones, local log", Screen.GEO),
    FileEntry("05", "Cases", "Case files and notes", Screen.CASES),
    FileEntry("06", "CCTV", "Indonesian public cameras", Screen.CCTV),
    FileEntry("07", "Audit", "Privacy posture", Screen.SETTINGS),
    FileEntry("08", "Payload", "Build, sign, carry", Screen.PAYLOAD),
    FileEntry("09", "Globe", "Aircraft, orbit, quakes", Screen.GLOBE)
)

@Composable
fun HomeScreen(
    store: Store,
    geoStore: GeoStore,
    onNavigate: (Screen) -> Unit
) {
    val scroll = rememberScrollState()
    val activeZones = geoStore.zones.count { it.active }
    val events = geoStore.events.take(5)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 20.dp)
    ) {
        // ---- masthead ------------------------------------------------------
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.width(2.dp).height(10.dp).background(Seal))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "FILE 00 · HOME",
                        style = MaterialTheme.typography.labelMedium,
                        color = Seal
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Duty\nboard",
                    style = MaterialTheme.typography.displayLarge,
                    color = Bone
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Public intelligence, identity checks, and location watch on one board.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
            }
            Spacer(Modifier.width(14.dp))
            Box(
                modifier = Modifier
                    .size(74.dp)
                    .border(1.dp, Hairline),
                contentAlignment = Alignment.Center
            ) {
                BracketEye(size = 40.dp, color = Bone)
            }
        }

        Spacer(Modifier.height(22.dp))

        // ---- figures -------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Hairline),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetricCaption(
                value = store.auditScore.toString(),
                label = "Score /100",
                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                color = Bone
            )
            Box(Modifier.width(1.dp).height(38.dp).background(HairlineSoft))
            MetricCaption(
                value = store.cases.size.toString(),
                label = "Cases",
                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                color = Bone
            )
            Box(Modifier.width(1.dp).height(38.dp).background(HairlineSoft))
            MetricCaption(
                value = activeZones.toString(),
                label = "Zones",
                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                color = if (activeZones > 0) Green else Bone
            )
        }

        Spacer(Modifier.height(26.dp))
        SectionLabel(label = "File index", trailing = "${files.size} ENTRIES")
        Spacer(Modifier.height(2.dp))

        files.forEach { entry ->
            IndexRow(
                index = entry.index,
                title = entry.label,
                detail = entry.detail,
                onClick = { onNavigate(entry.screen) }
            )
        }

        Spacer(Modifier.height(26.dp))
        SectionLabel(label = "Activity log", trailing = "${geoStore.events.size} RECORDS")
        Spacer(Modifier.height(12.dp))

        PanelCard {
            if (events.isEmpty()) {
                Text(
                    text = "No transitions yet. The log fills when this device enters or leaves a registered zone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedDeep
                )
            } else {
                events.forEachIndexed { index, event ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(6.dp)
                                .background(if (event.transition == "ENTER") Green else Seal)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = event.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = Bone
                            )
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = event.transition,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (event.transition == "ENTER") Green else Seal
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = PublicIntel.formatTime(event.at),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MutedDeep
                                )
                            }
                        }
                    }
                    if (index != events.lastIndex) {
                        Spacer(Modifier.height(10.dp))
                        DividerLine()
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ---- posture -------------------------------------------------------
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.width(2.dp).height(38.dp).background(Seal))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "MODULE DATA STAYS ON THIS DEVICE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Seal
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "No server, no account, no sync. Outbound queries only reach the public sources you trigger yourself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedDeep
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}