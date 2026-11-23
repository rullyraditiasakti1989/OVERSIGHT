package com.vector.console.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vector.console.data.Breach
import com.vector.console.data.DomainReport
import com.vector.console.data.IpReport
import com.vector.console.data.Osiris
import com.vector.console.data.PublicFeeds
import com.vector.console.data.PublicIntel
import com.vector.console.data.Quake
import com.vector.console.data.Store
import com.vector.console.ui.components.ChoiceChip
import com.vector.console.ui.components.DataRow
import com.vector.console.ui.components.DividerLine
import com.vector.console.ui.components.EmptyState
import com.vector.console.ui.components.FieldLabel
import com.vector.console.ui.components.GoldButton
import com.vector.console.ui.components.KeyValueRow
import com.vector.console.ui.components.LoadingLine
import com.vector.console.ui.components.PanelCard
import com.vector.console.ui.components.ScreenHeader
import com.vector.console.ui.components.SectionLabel
import com.vector.console.ui.components.SegmentedTabs
import com.vector.console.ui.components.Tag
import com.vector.console.ui.components.VectorTextField
import com.vector.console.ui.theme.Alert
import com.vector.console.ui.theme.Bone
import com.vector.console.ui.theme.BoneDim
import com.vector.console.ui.theme.Green
import com.vector.console.ui.theme.Muted
import com.vector.console.ui.theme.MutedDeep
import kotlinx.coroutines.launch

private val intelTabs = listOf("Quakes", "Domain", "IP", "Breaches", "Feeds")

@Composable
fun IntelScreen(store: Store) {
    var tab by remember { mutableStateOf(0) }
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        ScreenHeader(
            kicker = "File 01 · Intel",
            title = "Intel",
            subtitle = "Open public feeds, pulled straight from the device. No server, no key."
        )
        Spacer(Modifier.height(20.dp))
        SegmentedTabs(options = intelTabs, selected = tab, onSelect = { tab = it })
        Spacer(Modifier.height(20.dp))

        when (tab) {
            0 -> QuakePanel()
            1 -> DomainPanel()
            2 -> IpPanel()
            3 -> BreachPanel()
            4 -> FeedPanel(store = store)
        }
        Spacer(Modifier.height(28.dp))
    }
}

// ------------------------------------------------------------------ quakes

@Composable
private fun QuakePanel() {
    var quakes by remember { mutableStateOf<List<Quake>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loading = true
        error = null
        runCatching { PublicIntel.earthquakes() }
            .onSuccess { quakes = it }
            .onFailure { error = it.message }
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    SectionLabel(label = "Seismic events", trailing = "M2.5+ · 24H")
    Spacer(Modifier.height(10.dp))

    if (loading) {
        LoadingLine(label = "READING USGS FEED")
        Spacer(Modifier.height(10.dp))
    }
    error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = Alert)
        Spacer(Modifier.height(10.dp))
    }
    if (quakes.isEmpty() && !loading && error == null) {
        EmptyState("No events", "The USGS feed returned nothing significant.")
    }

    val shown = quakes.take(25)
    if (shown.isNotEmpty()) {
        PanelCard(padding = 0.dp) {
            shown.forEachIndexed { index, quake ->
                val severe = quake.magnitude >= 5.0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = String.format(java.util.Locale.US, "%.1f", quake.magnitude),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (severe) Alert else Bone,
                        modifier = Modifier.width(52.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = quake.place,
                            style = MaterialTheme.typography.bodyMedium,
                            color = BoneDim
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "${PublicIntel.formatTime(quake.time)} · " +
                                String.format(java.util.Locale.US, "%.0f KM DEEP", quake.depthKm),
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedDeep
                        )
                    }
                }
                if (index != shown.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp)
                            .height(1.dp)
                            .background(
                                com.vector.console.ui.theme.HairlineSoft
                            )
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    GoldButton(label = "Reload", onClick = { scope.launch { load() } })
}

// ------------------------------------------------------------------ domain

@Composable
private fun DomainPanel() {
    var input by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<DomainReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    SectionLabel(label = "Registrar record", trailing = "RDAP")
    Spacer(Modifier.height(10.dp))

    PanelCard {
        FieldLabel("Domain")
        Spacer(Modifier.height(6.dp))
        VectorTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = "example: kominfo.go.id"
        )
        Spacer(Modifier.height(12.dp))
        GoldButton(
            label = "Look up",
            enabled = input.isNotBlank() && !loading,
            onClick = {
                scope.launch {
                    loading = true
                    error = null
                    report = null
                    runCatching { PublicIntel.domain(input) }
                        .onSuccess { report = it }
                        .onFailure { error = it.message }
                    loading = false
                }
            }
        )
        if (loading) {
            Spacer(Modifier.height(8.dp))
            LoadingLine(label = "READING REGISTRY")
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Alert)
        }
    }

    report?.let { item ->
        Spacer(Modifier.height(12.dp))
        PanelCard(header = item.domain) {
            DataRow("Registrar", item.registrar ?: "—", Bone)
            DataRow("Created", PublicIntel.formatDate(item.createdAt))
            DataRow("Expires", PublicIntel.formatDate(item.expiresAt))
            DataRow("Updated", PublicIntel.formatDate(item.updatedAt))
            DataRow("DNSSEC", item.dnssec?.let { if (it) "SIGNED" else "UNSIGNED" } ?: "—")
            DataRow("Nameservers", item.nameservers.joinToString(", ").ifBlank { "—" })
            if (item.statuses.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                DividerLine()
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.statuses.take(4).forEach { Tag(text = it) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- ip

@Composable
private fun IpPanel() {
    var input by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<IpReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    SectionLabel(label = "Address trace", trailing = "GEOIP · ASN")
    Spacer(Modifier.height(10.dp))

    PanelCard {
        FieldLabel("IP address")
        Spacer(Modifier.height(6.dp))
        VectorTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = "example: 8.8.8.8"
        )
        Spacer(Modifier.height(12.dp))
        GoldButton(
            label = "Trace",
            enabled = input.isNotBlank() && !loading,
            onClick = {
                scope.launch {
                    loading = true
                    error = null
                    report = null
                    runCatching { PublicIntel.ip(input) }
                        .onSuccess { report = it }
                        .onFailure { error = it.message }
                    loading = false
                }
            }
        )
        if (loading) {
            Spacer(Modifier.height(8.dp))
            LoadingLine(label = "MAPPING ROUTE")
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Alert)
        }
    }

    report?.let { item ->
        Spacer(Modifier.height(12.dp))
        PanelCard(header = item.ip) {
            DataRow("Country", listOfNotNull(item.country, item.countryCode).joinToString(" · ").ifBlank { "—" }, Bone)
            DataRow("Region", listOfNotNull(item.region, item.city).joinToString(" · ").ifBlank { "—" })
            DataRow("ISP", item.isp ?: "—")
            DataRow("Organisation", item.org ?: "—")
            DataRow("ASN", item.asn ?: "—")
            DataRow("Time zone", item.timezone ?: "—")
            DataRow(
                "Coordinates",
                if (item.latitude != null && item.longitude != null) {
                    String.format(java.util.Locale.US, "%.4f, %.4f", item.latitude, item.longitude)
                } else "—"
            )
        }
    }
}

// ----------------------------------------------------------------- breaches

@Composable
private fun BreachPanel() {
    var breaches by remember { mutableStateOf<List<Breach>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        loading = true
        runCatching { PublicIntel.breaches() }
            .onSuccess { breaches = it }
            .onFailure { error = it.message }
        loading = false
    }

    val filtered = remember(query, breaches) {
        if (query.isBlank()) breaches
        else breaches.filter {
            it.title.contains(query, true) || it.domain.contains(query, true) ||
                it.dataClasses.any { tag -> tag.contains(query, true) }
        }
    }

    SectionLabel(label = "Breach index", trailing = "${breaches.size} INCIDENTS")
    Spacer(Modifier.height(10.dp))

    PanelCard {
        VectorTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Filter: service or data type"
        )
        if (loading) {
            Spacer(Modifier.height(8.dp))
            LoadingLine(label = "PULLING HIBP INDEX")
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Alert)
        }
        Spacer(Modifier.height(10.dp))
        GoldButton(
            label = "Reload index",
            enabled = !loading,
            onClick = {
                scope.launch {
                    loading = true
                    error = null
                    runCatching { PublicIntel.breaches() }
                        .onSuccess { breaches = it }
                        .onFailure { error = it.message }
                    loading = false
                }
            }
        )
    }

    if (!loading && filtered.isEmpty() && breaches.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        EmptyState("No match", "No incident matches that filter.")
    }
    if (breaches.isEmpty() && !loading && error == null) {
        Spacer(Modifier.height(12.dp))
        EmptyState("Empty index", "HIBP returned nothing. Try again later.")
    }

    val shown = filtered.take(30)
    if (shown.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        PanelCard(padding = 0.dp) {
            shown.forEachIndexed { index, breach ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 13.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = (index + 1).toString().padStart(2, '0'),
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedDeep,
                            modifier = Modifier.width(28.dp).padding(top = 4.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = breach.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = Bone
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "${breach.domain.ifBlank { "no domain" }} · ${breach.breachDate}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedDeep
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = PublicIntel.compactNumber(breach.pwnCount),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (breach.verified) Bone else Muted
                            )
                            Text(
                                text = if (breach.verified) "VERIFIED" else "UNVERIFIED",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (breach.verified) Green else MutedDeep
                            )
                        }
                    }
                    if (breach.dataClasses.isNotEmpty()) {
                        Spacer(Modifier.height(9.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            breach.dataClasses.take(4).forEach { Tag(text = it, color = Muted) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = breach.description.take(190),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                }
                if (index != shown.lastIndex) DividerLine()
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Text(
        text = "Public index only. To check one specific address, open the Identity module.",
        style = MaterialTheme.typography.labelSmall,
        color = MutedDeep
    )
}

// -------------------------------------------------------------------- feeds

private enum class FeedKind(val label: String) {
    BENCANA("Hazards"),
    PENERBANGAN("Flights"),
    ANTARIKSA("Space wx"),
    KEV("KEV"),
    CVE("CVE"),
    TOR("Tor exits"),
    OSIRIS("OSIRIS")
}

private data class FeedRow(
    val title: String,
    val subtitle: String,
    val fields: List<Pair<String, String>>,
    val coordinate: String? = null
)

private fun Double?.formatOrDash(pattern: String): String =
    this?.let { String.format(java.util.Locale.US, pattern, it) } ?: "—"

private suspend fun loadFeed(
    kind: FeedKind,
    base: String?,
    token: String
): List<FeedRow> = when (kind) {
    FeedKind.BENCANA -> PublicFeeds.hazards().map { event ->
        FeedRow(
            title = event.title,
            subtitle = event.category,
            fields = listOf(
                "TIME" to event.date,
                "SOURCE" to event.url.ifBlank { "—" }
            ),
            coordinate = String.format(
                java.util.Locale.US, "%.4f, %.4f", event.latitude, event.longitude
            )
        )
    }
    FeedKind.PENERBANGAN -> PublicFeeds.flights().map { flight ->
        FeedRow(
            title = flight.callsign,
            subtitle = flight.country,
            fields = listOf(
                "ICAO24" to flight.icao24,
                "ALTITUDE" to flight.altitudeMeters.formatOrDash("%.0f m"),
                "SPEED" to flight.speedMetersPerSecond.formatOrDash("%.0f m/s"),
                "HEADING" to flight.heading.formatOrDash("%.0f°")
            ),
            coordinate = String.format(
                java.util.Locale.US, "%.4f, %.4f", flight.latitude, flight.longitude
            )
        )
    }
    FeedKind.ANTARIKSA -> {
        val weather = PublicFeeds.spaceWeather()
        val flares = weather.flares.take(5).joinToString(", ") { it.className }
        listOf(
            FeedRow(
                title = "SPACE WEATHER",
                subtitle = "NOAA SWPC",
                fields = listOf(
                    "KP INDEX" to weather.kp?.value.formatOrDash("%.2f"),
                    "KP TIME" to (weather.kp?.time ?: "—"),
                    "FLARES 7D" to flares.ifBlank { "—" }
                )
            )
        )
    }
    FeedKind.KEV -> PublicFeeds.kev().map { entry ->
        FeedRow(
            title = entry.cveId,
            subtitle = entry.name,
            fields = listOf(
                "VENDOR" to entry.vendor,
                "PRODUCT" to entry.product,
                "ADDED" to entry.dateAdded,
                "DUE" to entry.dueDate,
                "ACTION" to entry.action
            )
        )
    }
    FeedKind.CVE -> PublicFeeds.cves().map { entry ->
        FeedRow(
            title = entry.id,
            subtitle = entry.published,
            fields = listOf(
                "SCORE" to entry.score.formatOrDash("%.1f"),
                "SUMMARY" to entry.summary.take(180)
            )
        )
    }
    FeedKind.TOR -> PublicFeeds.torExits().mapIndexed { index, ip ->
        FeedRow(title = ip, subtitle = "TOR EXIT NODE ${index + 1}", fields = emptyList())
    }
    FeedKind.OSIRIS -> {
        val endpoint = base ?: throw java.io.IOException("OSIRIS URL is not set in Settings.")
        Osiris.fetch(endpoint, "/api/cyber-intel", token, "OSIRIS").items.map { item ->
            val lat = item.latitude
            val lon = item.longitude
            FeedRow(
                title = item.title,
                subtitle = item.subtitle,
                fields = item.fields,
                coordinate = if (lat != null && lon != null) {
                    String.format(java.util.Locale.US, "%.4f, %.4f", lat, lon)
                } else null
            )
        }
    }
}

@Composable
private fun FeedPanel(store: Store) {
    val baseRaw = store.key("osiris_base")
    val base = remember(baseRaw) { Osiris.normalizeBase(baseRaw) }
    var selected by remember { mutableStateOf(FeedKind.BENCANA) }
    var rows by remember { mutableStateOf<List<FeedRow>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val chipScroll = rememberScrollState()

    LaunchedEffect(selected, refresh, base) {
        if (selected == FeedKind.OSIRIS && base == null) {
            rows = emptyList()
            error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        rows = emptyList()
        runCatching { loadFeed(selected, base, store.key("osiris_token")) }
            .onSuccess { rows = it }
            .onFailure { error = it.message }
        loading = false
    }

    SectionLabel(label = "Open feeds", trailing = "${rows.size} ROWS")
    Spacer(Modifier.height(10.dp))

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(chipScroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FeedKind.entries.forEach { kind ->
            ChoiceChip(
                label = kind.label,
                selected = kind == selected,
                onClick = { selected = kind; error = null }
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    if (selected == FeedKind.OSIRIS && base == null) {
        PanelCard(header = "OSIRIS bridge", trailing = "NOT SET") {
            Text(
                text = "Set your own OSIRIS instance URL in Settings to read this feed. The other " +
                    "six run without it.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    if (loading) {
        LoadingLine(label = "FETCHING PUBLIC FEED")
        Spacer(Modifier.height(10.dp))
    }
    error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = Alert)
        Spacer(Modifier.height(10.dp))
    }
    if (!loading && rows.isEmpty() && error == null && !(selected == FeedKind.OSIRIS && base == null)) {
        EmptyState("Empty feed", "Source returned no data. Try reloading.")
        Spacer(Modifier.height(10.dp))
    }

    val shown = rows.take(40)
    if (shown.isNotEmpty()) {
        PanelCard(padding = 0.dp) {
            shown.forEachIndexed { index, row ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 13.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = (index + 1).toString().padStart(2, '0'),
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedDeep,
                            modifier = Modifier.width(28.dp).padding(top = 4.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = row.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = Bone
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = row.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedDeep
                            )
                        }
                    }
                    if (row.fields.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        row.fields.forEach { (key, value) -> KeyValueRow(label = key, value = value) }
                    }
                    row.coordinate?.let {
                        Spacer(Modifier.height(8.dp))
                        Tag(text = it, color = Green)
                    }
                }
                if (index != shown.lastIndex) DividerLine()
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    GoldButton(label = "Reload", onClick = { refresh++ })
}