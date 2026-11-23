package com.vector.console.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.vector.console.data.GeoController
import com.vector.console.data.GeoEvent
import com.vector.console.data.GeoMath
import com.vector.console.data.GeoStore
import com.vector.console.data.GeoZone
import com.vector.console.ui.components.ChoiceChip
import com.vector.console.ui.components.DataRow
import com.vector.console.ui.components.DividerLine
import com.vector.console.ui.components.EmptyState
import com.vector.console.ui.components.FieldLabel
import com.vector.console.ui.components.GoldButton
import com.vector.console.ui.components.LoadingLine
import com.vector.console.ui.components.OutlineActionButton
import com.vector.console.ui.components.PanelCard
import com.vector.console.ui.components.ScreenHeader
import com.vector.console.ui.components.SectionLabel
import com.vector.console.ui.components.StatusLine
import com.vector.console.ui.components.Tag
import com.vector.console.ui.components.VectorTextField
import com.vector.console.ui.theme.Alert
import com.vector.console.ui.theme.Bone
import com.vector.console.ui.theme.BoneDim
import com.vector.console.ui.theme.Green
import com.vector.console.ui.theme.HairlineSoft
import com.vector.console.ui.theme.Ink
import com.vector.console.ui.theme.Muted
import com.vector.console.ui.theme.MutedDeep
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun GeoScreen(geoStore: GeoStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    var showForm by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("200") }
    var onEnter by remember { mutableStateOf(true) }
    var onExit by remember { mutableStateOf(true) }
    var dwell by remember { mutableStateOf(false) }
    var covert by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    var myLat by remember { mutableDoubleStateOf(Double.NaN) }
    var myLon by remember { mutableDoubleStateOf(Double.NaN) }

    val controller = remember { GeoController(context) }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!fine) error = "Location permission denied."
    }

    fun requestLocation() {
        scope.launch {
            val granted = android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == granted || androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == granted
            if (!hasLocation) {
                error = "Location permission not granted."
                return@launch
            }
            runCatching {
                val client = LocationServices.getFusedLocationProviderClient(context)
                val cts = CancellationTokenSource()
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await()
            }.onSuccess { location ->
                if (location != null) {
                    myLat = location.latitude
                    myLon = location.longitude
                } else {
                    error = "Current location is unavailable."
                }
            }.onFailure { error = it.message }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        ScreenHeader(
            kicker = "File 04 · Geofence",
            title = "Geofence",
            subtitle = "Zones live on the device. Transitions are logged silently, with no notification."
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel(
            label = "Permission",
            trailing = if (controller.hasPermission()) "GRANTED" else "MISSING"
        )
        Spacer(Modifier.height(10.dp))

        PanelCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineActionButton(
                    label = "Request permission",
                    onClick = {
                        permLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                OutlineActionButton(
                    label = "My location",
                    onClick = { requestLocation() },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))
            DividerLine()
            Spacer(Modifier.height(8.dp))
            StatusLine(
                text = when {
                    controller.hasBackgroundPermission() -> "Background active — zones fire while the app is closed."
                    controller.hasPermission() -> "Foreground only — zones fire while the app is running."
                    else -> "Permission not granted."
                },
                color = if (controller.hasBackgroundPermission()) Green else Alert
            )
            if (!myLat.isNaN()) {
                DataRow(
                    "Position",
                    String.format(Locale.US, "%.5f, %.5f", myLat, myLon),
                    Bone
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoldButton(
                    label = "Sync zones",
                    enabled = controller.hasPermission() && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            status = null
                            runCatching { controller.sync(geoStore.zones, geoStore.covertMaster) }
                                .onSuccess { n ->
                                    status = "$n active zones registered with Play services."
                                }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                OutlineActionButton(
                    label = "Remove all",
                    enabled = controller.hasPermission() && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            runCatching { controller.removeAll() }
                                .onSuccess { status = "All zones removed." }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                LoadingLine(label = "SYNCING GEOFENCES")
            }
            status?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Green)
            }
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Alert)
            }
        }

        Spacer(Modifier.height(12.dp))
        PanelCard(header = "Covert mode", trailing = if (geoStore.covertMaster) "ON" else "OFF") {
            Text(
                text = "Centre jitter ±60 m, radius +30%, 5-minute wakeup. No transition notification is " +
                    "posted while this is on.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(26.dp)
                        .background(if (geoStore.covertMaster) Alert else HairlineSoft)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (geoStore.covertMaster) "MASKED · JITTER ACTIVE" else "STANDARD · PRECISE",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (geoStore.covertMaster) Alert else MutedDeep,
                    modifier = Modifier.weight(1f)
                )
                OutlineActionButton(
                    label = if (geoStore.covertMaster) "Turn off" else "Turn on",
                    onClick = { geoStore.toggleCovertMaster() }
                )
            }
        }

        if (geoStore.zones.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionLabel(label = "Radar", trailing = "${geoStore.zones.count { it.active }} ACTIVE")
            Spacer(Modifier.height(10.dp))
            PanelCard(padding = 0.dp) {
                RadarCanvas(
                    zones = geoStore.zones,
                    myLat = myLat,
                    myLon = myLon,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                        .border(0.dp, HairlineSoft)
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel(label = "Zones", trailing = "${geoStore.zones.size} REGISTERED")
        Spacer(Modifier.height(10.dp))

        PanelCard {
            if (geoStore.zones.isEmpty()) {
                EmptyState(
                    "No zones yet",
                    "Add coordinates and a radius. Active zones are logged on enter, exit, or dwell."
                )
            }
            geoStore.zones.forEachIndexed { index, zone ->
                ZoneRow(
                    zone = zone,
                    onToggle = { geoStore.toggleZone(zone.id) },
                    onDelete = { geoStore.deleteZone(zone.id) }
                )
                if (index != geoStore.zones.lastIndex) DividerLine()
            }
            Spacer(Modifier.height(12.dp))
            GoldButton(
                label = if (showForm) "Close form" else "Add zone",
                onClick = { showForm = !showForm }
            )
        }

        if (showForm) {
            Spacer(Modifier.height(12.dp))
            PanelCard(header = "New zone") {
                FieldLabel("Label")
                Spacer(Modifier.height(6.dp))
                VectorTextField(value = label, onValueChange = { label = it }, placeholder = "Home, office, checkpoint")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        FieldLabel("Latitude")
                        Spacer(Modifier.height(6.dp))
                        VectorTextField(value = lat, onValueChange = { lat = it }, placeholder = "-6.2000")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        FieldLabel("Longitude")
                        Spacer(Modifier.height(6.dp))
                        VectorTextField(value = lon, onValueChange = { lon = it }, placeholder = "106.8167")
                    }
                }
                Spacer(Modifier.height(10.dp))
                FieldLabel("Radius · metres")
                Spacer(Modifier.height(6.dp))
                VectorTextField(
                    value = radius,
                    onValueChange = { radius = it.filter { ch -> ch.isDigit() } },
                    placeholder = "200"
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceChip(label = "Enter", selected = onEnter, onClick = { onEnter = !onEnter })
                    ChoiceChip(label = "Exit", selected = onExit, onClick = { onExit = !onExit })
                    ChoiceChip(label = "Dwell", selected = dwell, onClick = { dwell = !dwell })
                    ChoiceChip(label = "Covert", selected = covert, onClick = { covert = !covert })
                }
                Spacer(Modifier.height(14.dp))
                GoldButton(
                    label = "Save zone",
                    enabled = label.isNotBlank() &&
                        lat.toDoubleOrNull() != null &&
                        lon.toDoubleOrNull() != null,
                    onClick = {
                        val zone = GeoZone(
                            label = label.trim(),
                            latitude = lat.toDouble(),
                            longitude = lon.toDouble(),
                            radiusMeters = (radius.toIntOrNull() ?: 200).coerceIn(50, 5000).toFloat(),
                            onEnter = onEnter,
                            onExit = onExit,
                            onDwell = dwell,
                            covert = covert
                        )
                        geoStore.saveZone(zone)
                        label = ""
                        lat = ""
                        lon = ""
                        radius = "200"
                        covert = false
                        showForm = false
                    }
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel(label = "Transition log", trailing = "${geoStore.events.size} RECORDS")
        Spacer(Modifier.height(10.dp))

        PanelCard {
            if (geoStore.events.isEmpty()) {
                EmptyState("Empty log", "Transitions are logged automatically for active zones.")
            }
            geoStore.events.take(40).forEachIndexed { index, event ->
                EventRow(event)
                if (index != geoStore.events.take(40).lastIndex) DividerLine()
            }
            if (geoStore.events.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                OutlineActionButton(
                    label = "Clear log",
                    onClick = { geoStore.clearEvents() }
                )
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ZoneRow(zone: GeoZone, onToggle: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .width(2.dp)
                .height(26.dp)
                .background(if (zone.active) Green else HairlineSoft)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(zone.label, style = MaterialTheme.typography.titleMedium, color = Bone)
            Spacer(Modifier.height(3.dp))
            Text(
                text = String.format(
                    Locale.US,
                    "%.4f, %.4f · %dm · %s",
                    zone.latitude,
                    zone.longitude,
                    zone.radiusMeters.toInt(),
                    listOfNotNull(
                        "enter".takeIf { zone.onEnter },
                        "exit".takeIf { zone.onExit },
                        "dwell".takeIf { zone.onDwell }
                    ).joinToString(", ").ifBlank { "no trigger" }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MutedDeep
            )
        }
        if (zone.covert) {
            Tag(text = "MASKED", color = Alert)
            Spacer(Modifier.width(6.dp))
        }
        Tag(
            text = if (zone.active) "ACTIVE" else "PAUSED",
            color = if (zone.active) Green else Muted
        )
        Spacer(Modifier.width(8.dp))
        OutlineActionButton(label = if (zone.active) "Pause" else "Resume", onClick = onToggle)
        Spacer(Modifier.width(6.dp))
        OutlineActionButton(label = "Delete", onClick = onDelete)
    }
}

@Composable
private fun EventRow(event: GeoEvent) {
    val color = when (event.transition) {
        "ENTER" -> Green
        "EXIT" -> Alert
        "DWELL" -> BoneDim
        else -> Muted
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(event.label, style = MaterialTheme.typography.titleSmall, color = BoneDim)
            Spacer(Modifier.height(3.dp))
            Text(
                text = String.format(
                    Locale.US,
                    "%s · %s",
                    event.transition,
                    SimpleDateFormat("dd MMM · HH:mm:ss", Locale.US).format(Date(event.at))
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MutedDeep
            )
            if (event.latitude != null && event.longitude != null) {
                Text(
                    text = String.format(Locale.US, "%.5f, %.5f", event.latitude, event.longitude),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MutedDeep
                )
            }
        }
        Tag(text = event.transition, color = color)
    }
}

@Composable
private fun RadarCanvas(
    zones: List<GeoZone>,
    myLat: Double,
    myLon: Double,
    modifier: Modifier
) {
    Canvas(modifier = modifier.background(Ink)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val maxRadius = min(w, h) * 0.4f

        for (i in 1..3) {
            drawCircle(
                color = HairlineSoft,
                radius = maxRadius * i / 3f,
                center = androidx.compose.ui.geometry.Offset(cx, cy),
                style = Stroke(width = 1f)
            )
        }
        drawLine(
            color = HairlineSoft,
            start = androidx.compose.ui.geometry.Offset(cx, 0f),
            end = androidx.compose.ui.geometry.Offset(cx, h),
            strokeWidth = 1f
        )
        drawLine(
            color = HairlineSoft,
            start = androidx.compose.ui.geometry.Offset(0f, cy),
            end = androidx.compose.ui.geometry.Offset(w, cy),
            strokeWidth = 1f
        )

        if (myLat.isNaN() || myLon.isNaN()) {
            drawContext.canvas.nativeCanvas.drawText(
                "WAITING FOR POSITION",
                cx,
                cy,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#7A8380")
                    textSize = 22f
                    textAlign = android.graphics.Paint.Align.CENTER
                }
            )
            return@Canvas
        }

        val scale = (maxRadius / 500f).coerceAtLeast(0.2f) // 500 m fills the outer ring
        zones.filter { it.active }.forEach { zone ->
            val dist = GeoMath.distanceMeters(myLat, myLon, zone.latitude, zone.longitude).toFloat()
            val bearing = GeoMath.bearingDegrees(myLat, myLon, zone.latitude, zone.longitude)
            val r = (dist * scale).coerceAtMost(maxRadius * 1.1f)
            val rad = Math.toRadians(bearing)
            val zx = cx + (r * sin(rad)).toFloat()
            val zy = cy - (r * cos(rad)).toFloat()
            val zoneRadiusPx = (zone.radiusMeters * scale).coerceIn(6f, maxRadius)

            drawCircle(
                color = Bone.copy(alpha = 0.14f),
                radius = zoneRadiusPx,
                center = androidx.compose.ui.geometry.Offset(zx, zy)
            )
            drawCircle(
                color = Bone,
                radius = 3.5f,
                center = androidx.compose.ui.geometry.Offset(zx, zy)
            )
            drawContext.canvas.nativeCanvas.drawText(
                zone.label.take(10).uppercase(),
                zx,
                zy - 10f,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#F3F5F4")
                    textSize = 20f
                    textAlign = android.graphics.Paint.Align.CENTER
                }
            )
        }

        // self marker
        drawCircle(color = Green, radius = 5f, center = androidx.compose.ui.geometry.Offset(cx, cy))
        drawCircle(
            color = Green.copy(alpha = 0.22f),
            radius = 13f,
            center = androidx.compose.ui.geometry.Offset(cx, cy)
        )
    }
}