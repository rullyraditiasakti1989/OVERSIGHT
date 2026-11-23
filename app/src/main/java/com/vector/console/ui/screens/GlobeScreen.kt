package com.vector.console.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.vector.console.data.Store
import com.vector.console.data.globe.Contact
import com.vector.console.data.globe.GlobeFeeds
import com.vector.console.data.globe.GlobeGeoJson
import com.vector.console.data.globe.GlobeSnapshot
import com.vector.console.data.globe.LayerId
import com.vector.console.data.globe.LedgerAlert
import com.vector.console.data.globe.RailFeeds
import com.vector.console.data.globe.LayerSnapshot
import com.vector.console.ui.components.ChoiceChip
import com.vector.console.ui.components.DividerLine
import com.vector.console.ui.components.EmptyState
import com.vector.console.ui.components.GlobeCanvas
import com.vector.console.ui.components.GlobeEvent
import com.vector.console.ui.components.GlobeHandle
import com.vector.console.ui.components.KeyValueRow
import com.vector.console.ui.components.LoadingLine
import com.vector.console.ui.components.OutlineActionButton
import com.vector.console.ui.components.ScreenHeader
import com.vector.console.ui.components.frameTicks
import com.vector.console.ui.components.rememberGlobeHandle
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * File 09 · Globe.
 *
 * The chrome is the point. The globe is a view; the plates over it are the
 * instrument, and every one of them reads a value the renderer actually sent.
 *
 * Layout note: the well is **not** inside a scrolling column. A WebGL surface
 * that shares a scroll container with text swallows the drag gesture.
 *
 * The tree is deliberately **identical** in both orientations. An earlier
 * version branched portrait/landscape into a column and a row, which recreated
 * the `AndroidView`, reloaded the page, threw away the camera and left the GL
 * surface at the old size. Only the header, rail and ledger drop in landscape —
 * each behind its own `if`, which Compose keeps as an empty group at the same
 * call site, so the well below never shifts.
 */
@Composable
fun GlobeScreen(
    store: Store,
    expanded: Boolean = false,
    onToggleExpand: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val handle = rememberGlobeHandle()
    val session = remember(store) { GlobeSession(context, store) }

    var readyMs by remember { mutableStateOf<Long?>(null) }
    var fault by remember { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf(Camera(0.0, 0.0, 0.0, 0.0)) }

    // Sends are fire-and-forget: the page drops messages that arrive before it
    // loads. These caches keep the last thing actually handed to the WebView so
    // a 60 s tick does not re-encode 20,000 cameras that have not changed, and
    // are cleared on `ready` so a fresh page gets the whole set again.
    val pushed = remember { mutableMapOf<LayerId, LayerSnapshot>() }
    val pushedVisible = remember { mutableMapOf<LayerId, Boolean>() }
    var epoch by remember { mutableStateOf(0) }

    val onEvent: (GlobeEvent) -> Unit = { event ->
        when (event) {
            is GlobeEvent.Ready -> {
                readyMs = event.ms
                pushed.clear()
                pushedVisible.clear()
                epoch += 1
            }
            is GlobeEvent.Camera -> {
                camera = Camera(event.lat, event.lon, event.zoom, event.bearing)
                // The aircraft feed is a radius query, so its centre follows the
                // camera. Stored rather than read at fetch time so a drag does not
                // race the coroutine.
                session.anchor = event.lat to event.lon
            }
            is GlobeEvent.Failed -> fault = event.message
            GlobeEvent.Cleared -> session.selected = null
            is GlobeEvent.Picked -> {
                val layer = event.layer.layerId()
                session.selected = if (layer == null) {
                    null
                } else {
                    session.snapshot.contacts(layer).firstOrNull { it.id == event.id }
                }
            }
        }
    }

    // Refresh only while the screen is resumed. A globe that polls in the
    // background is a battery bug with a nice view. Keyed on the session tick and
    // the stored cadence so RETRY and a settings change both take effect at once.
    LaunchedEffect(session.tick, store.globeCadenceMs) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                session.refresh()
                delay(store.globeCadenceMs)
            }
        }
    }

    // Push only what changed. Disabled layers still get their (empty or cached)
    // source so toggling is a visibility flip, not a re-fetch.
    LaunchedEffect(session.snapshot, epoch) {
        for (layer in LayerId.entries) {
            val snap = session.snapshot.of(layer)
            if (snap != null && pushed[layer] !== snap) {
                handle.features(
                    id = layer.key,
                    geojson = GlobeGeoJson.featureCollection(snap.contacts)
                )
                pushed[layer] = snap
            }
            val visible = layer in session.enabled
            if (pushedVisible[layer] != visible) {
                handle.layerVisible(layer.key, visible)
                pushedVisible[layer] = visible
            }
        }
    }

    LaunchedEffect(session.selected) {
        val selected = session.selected
        if (selected == null) handle.focus(null) else handle.focus(selected.id, selected.lat, selected.lon)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Portrait expanded drops the same chrome landscape drops; the well is
        // the whole window. StatusLine stays outside this gate so COLLAPSE is
        // always reachable.
        val short = maxHeight < 320.dp || expanded

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = if (short) 6.dp else 16.dp)
        ) {
            if (!short) {
                ScreenHeader(
                    kicker = "File 09 · Globe",
                    title = "Globe",
                    subtitle = "Live tracks from open sources, drawn on one plate."
                )
                Spacer(Modifier.height(16.dp))
            }

            GlobeWell(
                handle = handle,
                fault = fault,
                camera = camera,
                snapshot = session.snapshot,
                active = session.active,
                onEvent = onEvent,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (short) 1f else 1.15f)
            )

            Spacer(Modifier.height(if (short) 4.dp else 10.dp))

            StatusLine(
                readyMs = readyMs,
                fault = fault,
                camera = camera,
                snapshot = session.snapshot,
                enabled = session.enabled,
                expanded = expanded,
                onToggleExpand = onToggleExpand,
                onRetry = { session.retry() }
            )

            if (!short) {
                Spacer(Modifier.height(10.dp))
                LayerRail(
                    snapshot = session.snapshot,
                    enabled = session.enabled,
                    active = session.active,
                    onToggle = { layer -> session.toggle(layer) }
                )
                Spacer(Modifier.height(12.dp))

                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val selected = session.selected
                    val active = session.active
                    val snap = session.snapshot.of(active)
                    when {
                        selected != null -> SelectionCard(
                            contact = selected,
                            onClose = { session.selected = null },
                            modifier = Modifier.fillMaxSize()
                        )
                        active !in session.enabled -> EmptyState(
                            title = "Layer off",
                            body = "Tap the chip again to draw ${active.label.lowercase()} on the plate."
                        )
                        session.loading.contains(active) && snap == null -> LoadingLine(
                            label = "ACQUIRING ${active.label}"
                        )
                        snap == null || (snap.contacts.isEmpty() && snap.alerts.isEmpty()) -> EmptyState(
                            title = "No contacts",
                            body = snap?.note ?: "Nothing on this layer right now."
                        )
                        else -> Ledger(
                            layer = active,
                            contacts = snap.contacts,
                            alerts = snap.alerts,
                            note = snap.note,
                            stale = snap.stale,
                            onSelect = { session.selected = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                CreditLine()
            }
        }
    }
}

/**
 * Compose state for one visit to the screen.
 *
 * Deliberately not a ViewModel: it holds a `Context` for the TLE disk cache and
 * lives exactly as long as the composable, which is the same lifetime the old
 * `remember`-scoped state had. Nothing here survives process death, and nothing
 * here is meant to — F4 stores the enabled mask, not the contacts.
 */
private class GlobeSession(private val context: Context, private val store: Store) {

    var layers by mutableStateOf<Map<LayerId, LayerSnapshot>>(emptyMap())
        private set
    var loading by mutableStateOf<Set<LayerId>>(emptySet())
        private set
    /** Read straight from Store so a change in Settings is visible on return. */
    val enabled: Set<LayerId> get() = store.globeLayers
    var active by mutableStateOf(LayerId.AIRCRAFT)
    var selected by mutableStateOf<Contact?>(null)

    /** Camera centre for the radius queries. Ordered lat to lon, never lon to lat. */
    var anchor by mutableStateOf(-6.2 to 106.8)

    val snapshot by derivedStateOf { GlobeSnapshot(layers, enabled) }

    /**
     * Tapping the active, enabled layer turns it off; anything else selects and
     * enables it. One control, one meaning: the chip is the layer.
     */
    fun toggle(layer: LayerId) {
        if (layer == active && layer in enabled) {
            store.setGlobeLayer(layer, false)
        } else {
            store.setGlobeLayer(layer, true)
            active = layer
            selected = null
            if (layers[layer] == null) {
                // First sight of this layer: fetch it now rather than at the next
                // tick. `pending` alone is not enough — the loop is asleep in its
                // 60 s delay, and nothing drains the queue until it wakes. Bump
                // the restart key so the effect begins a fresh cycle immediately.
                pending = layer
                tick += 1
            }
        }
    }

    private var pending by mutableStateOf<LayerId?>(null)

    /** Set by [toggle] and drained by the next [refresh]. */
    private fun drainPending(): Set<LayerId> {
        val extra = pending ?: return emptySet()
        pending = null
        return setOf(extra)
    }

    suspend fun refresh(now: Long = System.currentTimeMillis()) = coroutineScope {
        val targets = (enabled + drainPending()).filter { it !in loading }
        if (targets.isEmpty()) return@coroutineScope
        loading = loading + targets
        for (layer in targets) {
            launch {
                val previous = layers[layer]
                val fresh = try {
                    fetch(layer, now)
                } catch (cancelled: CancellationException) {
                    // A cancelled fetch is the screen going away, not a source
                    // failure. Catching it would mark the layer stale on the way
                    // out and, worse, write Compose state from a dead scope.
                    throw cancelled
                } catch (error: Exception) {
                    LayerSnapshot(
                        layer = layer,
                        contacts = emptyList(),
                        fetchedAtMillis = now,
                        stale = true,
                        note = error.message ?: "source unreachable"
                    )
                }
                layers = layers + (layer to fresh.withPrevious(previous))
                loading = loading - layer
                store.recordGlobeFetch(layers.getValue(layer))
            }
        }
    }

    /** Forces the next [refresh] to re-fetch every enabled layer, not just the missing ones. */
    fun retry() {
        layers = layers.filterKeys { it !in enabled }
        // The refresh loop sleeps up to a full cadence between ticks, so a retry
        // that only dropped the cache would look dead for a minute. Bumping this
        // restarts the effect, which fetches immediately.
        tick += 1
    }

    /** Restart key for the refresh loop. */
    var tick by mutableStateOf(0)

    private suspend fun fetch(layer: LayerId, now: Long): LayerSnapshot = when (layer) {
        LayerId.AIRCRAFT -> GlobeFeeds.aircraft(
            lat = anchor.first,
            lon = anchor.second,
            radiusNm = 150.0,
            now = now
        )
        LayerId.SATELLITES -> GlobeFeeds.satellites(context, atMillis = now)
        LayerId.QUAKES -> GlobeFeeds.quakes(now = now)
        LayerId.CAMERAS -> GlobeFeeds.cameras(context, now = now)
        LayerId.FIRES -> GlobeFeeds.fires(
            key = store.key("firms_key"),
            lat = anchor.first,
            lon = anchor.second,
            now = now
        )
        LayerId.VESSELS -> GlobeFeeds.vessels(now = now)
        LayerId.RAIL -> RailFeeds.rail(context, now = now)
    }
}

/** The renderer keys layers by [LayerId.key]; a pick message comes back with it. */
private fun String.layerId(): LayerId? = LayerId.entries.firstOrNull { it.key == this }

/**
 * The framed plate. `clipToBounds` is not decoration: a WebView surface that has
 * not caught up with a bounds change paints outside its frame, and without the
 * clip it lands on top of the text below it.
 */
@Composable
private fun GlobeWell(
    handle: GlobeHandle,
    fault: String?,
    camera: Camera,
    snapshot: GlobeSnapshot,
    active: LayerId,
    onEvent: (GlobeEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(PanelDeep)
            .border(1.dp, Hairline)
            .frameTicks(ControlLine, 18.dp)
            .clipToBounds()
    ) {
        if (fault == null) {
            GlobeCanvas(
                handle = handle,
                modifier = Modifier.fillMaxSize().padding(1.dp),
                onEvent = onEvent
            )
        } else {
            // No sphere: the plate is the product, the globe is the view.
            Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(title = "Sphere unavailable", body = fault)
            }
        }

        CoordinatePlate(
            camera = camera,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
        )
        LiveStamp(
            // The basemap stamp counts every contact; a schedule stamp that says
            // 2,046 while the ledger lists 230 trains is a lying number.
            count = if (active == LayerId.RAIL) snapshot.contacts(LayerId.RAIL).size else snapshot.total,
            active = active,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
        )
        ScaleBar(
            zoom = camera.zoom,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
        )
    }
}

@Composable
private fun CreditLine() {
    Text(
        text = "Imagery © Esri, Maxar, Earthstar Geographics · MapLibre GL JS (BSD-3-Clause)",
        style = MaterialTheme.typography.labelSmall,
        color = MutedDeep,
        modifier = Modifier.fillMaxWidth()
    )
}

private data class Camera(val lat: Double, val lon: Double, val zoom: Double, val bearing: Double)

/** Top-left: where the camera is looking, in four decimal places. */
@Composable
private fun CoordinatePlate(camera: Camera, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(Ink.copy(alpha = 0.72f)).padding(horizontal = 6.dp, vertical = 4.dp)) {
        Text(
            text = "LAT ${formatLat(camera.lat)}",
            style = MaterialTheme.typography.labelSmall,
            color = Bone
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "LON ${formatLon(camera.lon)}",
            style = MaterialTheme.typography.labelSmall,
            color = Bone
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Z ${camera.zoom.toString().padStart(5, '0')}",
            style = MaterialTheme.typography.labelSmall,
            color = Muted
        )
    }
}

/**
 * Top-right. Seal appears here and on an M5.0+ quake, and nowhere else — a third
 * use is drift.
 */
@Composable
private fun LiveStamp(count: Int, active: LayerId, modifier: Modifier = Modifier) {
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val format = SimpleDateFormat("HH:mm", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        while (true) {
            clock = format.format(Date())
            delay(1000L - (System.currentTimeMillis() % 1000L))
        }
    }
    Column(
        modifier = modifier
            .background(Ink.copy(alpha = 0.72f))
            .border(1.dp, Seal.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.End
    ) {
        Text(
            // The basemap is live imagery; the rail schedule is not. One plate,
            // two truth claims, and the active layer decides which one is shown.
            text = if (active == LayerId.RAIL) "TIMETABLE" else "LIVE RASTER",
            style = MaterialTheme.typography.labelSmall,
            color = Seal
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "$clock UTC · ${"%03d".format(count)}",
            style = MaterialTheme.typography.labelSmall,
            color = Muted
        )
    }
}

/**
 * Bottom-left scale bar: three alternating segments, length derived from zoom.
 * At zoom z one pixel is roughly 156543 / 2^z metres at the equator, so a bar
 * drawn at a round ground distance is the only honest version of this control.
 */
@Composable
private fun ScaleBar(zoom: Double, modifier: Modifier = Modifier) {
    val metres = scaleMetres(zoom)
    Row(
        modifier = modifier.background(Ink.copy(alpha = 0.72f)).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row {
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(3.dp)
                        .background(if (index % 2 == 0) Bone else ControlLine)
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (metres >= 1000) "${metres / 1000} KM" else "$metres M",
            style = MaterialTheme.typography.labelSmall,
            color = BoneDim
        )
    }
}

/**
 * A round ground distance for the scale bar, chosen so the bar is about 60 dp.
 *
 * Two things this has to get right, both of which it got wrong first:
 *
 *  - **The ladder has to reach the whole-earth view.** At zoom 0 a 60 dp bar
 *    covers about 9,400 km; a ladder that stopped at 100 km drew a bar 0.6 px
 *    long and read `100 KM` over an entire planet.
 *  - **The steps have to be close together.** A 1 / 2.5 / 5 ladder leaves the
 *    bar anywhere between 60 and 150 px. Six steps per decade (1, 1.5, 2, 3, 5,
 *    7) keeps it between 60 and 100.
 *
 * The result is always `1, 1.5, 2, 3, 5 or 7` times a power of ten — a number a
 * person can read off a map without doing arithmetic.
 */
internal fun scaleMetres(zoom: Double): Int {
    // Ground metres per CSS pixel at the equator. 156543 is the standard
    // Web Mercator constant (circumference / 256 px tile at zoom 0).
    val perPixel = 156543.0 / Math.pow(2.0, zoom.coerceIn(0.0, 22.0))
    val target = perPixel * 60.0

    var decade = 1
    while (decade * 10 <= target && decade < 100_000_000) decade *= 10
    // Start one decade below the target so the first step that fits is found.
    decade /= 10
    if (decade < 1) decade = 1

    for (multiplier in LADDER) {
        val candidate = decade * multiplier
        if (candidate >= target) return candidate
    }
    return decade * 10
}

/** Six steps per decade, so consecutive candidates never differ by 2x. */
private val LADDER = intArrayOf(10, 15, 20, 30, 50, 70, 100)

private fun formatLat(value: Double): String =
    String.format(Locale.US, "%.4f %s", Math.abs(value), if (value < 0) "S" else "N")

private fun formatLon(value: Double): String =
    String.format(Locale.US, "%.4f %s", Math.abs(value), if (value < 0) "W" else "E")

@Composable
private fun StatusLine(
    readyMs: Long?,
    fault: String?,
    camera: Camera,
    snapshot: GlobeSnapshot,
    enabled: Set<LayerId>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onRetry: () -> Unit
) {
    val stale = enabled.mapNotNull { snapshot.of(it) }.filter { it.stale }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(10.dp)
                .background(if (fault == null) Bone else Seal)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = when {
                fault != null -> "SPHERE UNAVAILABLE · PLATE ONLY"
                readyMs != null -> "SPHERE READY · ${readyMs} MS · BEARING ${camera.bearing.toInt()}°"
                else -> "ACQUIRING RASTER"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (fault != null) Seal else Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.weight(1f))
        if (stale.isNotEmpty()) {
            Text(
                text = "CACHED · " + stale.joinToString(", ") { it.layer.label },
                style = MaterialTheme.typography.labelSmall,
                color = MutedDeep,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(4f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
        }
        StatusKey(
            label = if (stale.isEmpty()) "" else "RETRY",
            color = Seal,
            onClick = onRetry
        )
        if (stale.isNotEmpty()) Spacer(Modifier.width(6.dp))
        StatusKey(
            label = if (expanded) "COLLAPSE" else "EXPAND",
            color = BoneDim,
            onClick = onToggleExpand
        )
    }
}

/** A 1dp-outlined mono key for the status strip. No 46dp button fits here. */
@Composable
private fun StatusKey(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    if (label.isEmpty()) return
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * One chip per layer with zero-padded counts. An unfetched layer reads
 * `--` rather than `000`: "no data yet" and "no contacts" are different facts.
 */
@Composable
private fun LayerRail(
    snapshot: GlobeSnapshot,
    enabled: Set<LayerId>,
    active: LayerId,
    onToggle: (LayerId) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DividerLine(color = HairlineSoft)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (layer in LayerId.entries) {
                val snap = snapshot.of(layer)
                val count = if (snap == null) "--" else "%02d".format(snap.count)
                ChoiceChip(
                    label = "${layer.label} $count",
                    selected = layer == active && layer in enabled,
                    onClick = { onToggle(layer) }
                )
            }
        }
        DividerLine(color = HairlineSoft)
    }
}

/**
 * The active layer as indexed rows in one ruled card.
 *
 * The ledger is the real product — the globe is the view — so it is a real
 * scrolling list, not a summary that points at the map.
 */
@Composable
private fun Ledger(
    layer: LayerId,
    contacts: List<Contact>,
    alerts: List<LedgerAlert>,
    note: String?,
    stale: Boolean,
    onSelect: (Contact) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .border(1.dp, Hairline)
            .background(Panel)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PanelRaised)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(2.dp).height(10.dp).background(Bone))
            Spacer(Modifier.width(8.dp))
            Text(
                text = layer.label,
                style = MaterialTheme.typography.labelMedium,
                color = Bone
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = ledgerStamp(layer, contacts.size, alerts.size, note, stale),
                style = MaterialTheme.typography.labelSmall,
                color = MutedDeep,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DividerLine(color = Hairline)
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (alerts.isNotEmpty()) {
                item(key = "rail-alerts-head") {
                    Text(
                        text = "SERVICE ALERTS · ${alerts.size} ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Seal,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                    DividerLine(color = HairlineSoft)
                }
            }
            itemsIndexed(alerts, key = { _, item -> "alert:" + item.id }) { index, alert ->
                AlertRow(index = index, alert = alert)
                if (index != alerts.lastIndex || contacts.isNotEmpty()) DividerLine(color = HairlineSoft)
            }
            itemsIndexed(contacts, key = { _, item -> "train:" + item.id }) { index, contact ->
                LedgerRow(index = index, contact = contact, onClick = { onSelect(contact) })
                if (index != contacts.lastIndex) DividerLine(color = HairlineSoft)
            }
        }
    }
}

/**
 * `TIMETABLE + ALERTS n` is the stamp the rail phase was deferred with. It is a
 * rule now: a schedule layer must never borrow the word LIVE from the basemap.
 */
private fun ledgerStamp(
    layer: LayerId,
    count: Int,
    alerts: Int,
    note: String?,
    stale: Boolean
): String {
    if (layer != LayerId.RAIL) return buildString {
        append("%d CONTACTS".format(count))
        if (stale) append(" · CACHED")
    }
    val parts = ArrayList<String>(4)
    parts += if (alerts > 0) "TIMETABLE + ALERTS $alerts" else "TIMETABLE"
    parts += "%d RUNS".format(count)
    if (!note.isNullOrBlank()) parts += note
    if (stale) parts += "CACHED"
    return parts.joinToString(" · ")
}

@Composable
private fun AlertRow(index: Int, alert: LedgerAlert) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "A%02d".format(index + 1),
            style = MaterialTheme.typography.labelSmall,
            color = MutedDeep,
            modifier = Modifier.width(30.dp)
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(30.dp)
                .background(if (alert.marked) Seal else ControlLine)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = alert.title,
                style = MaterialTheme.typography.bodyMedium,
                color = BoneDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append(alert.severity.uppercase())
                    if (alert.location.isNotBlank()) append(" · ${alert.location}")
                    if (alert.timestampWib.isNotBlank()) append(" · ${alert.timestampWib} WIB")
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (alert.marked) Seal else Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (alert.detail.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = alert.detail.replace('\n', ' '),
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedDeep,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LedgerRow(index: Int, contact: Contact, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "%02d".format(index + 1),
            style = MaterialTheme.typography.labelSmall,
            color = MutedDeep,
            modifier = Modifier.width(26.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(22.dp)
                .background(if (contact.severity) Seal else ControlLine)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.title,
                style = MaterialTheme.typography.bodyMedium,
                color = BoneDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (contact.subtitle.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = contact.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * One contact, field order fixed by the fetcher so two aircraft never present
 * their numbers in a different order.
 */
@Composable
private fun SelectionCard(
    contact: Contact,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(contact.id) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .border(1.dp, Hairline)
            .background(Panel)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PanelRaised)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(10.dp)
                    .background(if (contact.severity) Seal else Bone)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = contact.title,
                style = MaterialTheme.typography.labelMedium,
                color = Bone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = contact.layer.label,
                style = MaterialTheme.typography.labelSmall,
                color = MutedDeep
            )
        }
        DividerLine(color = Hairline)

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            item {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    for (field in contact.fields) {
                        KeyValueRow(label = field.label, value = field.value)
                    }
                    KeyValueRow(
                        label = "POSITION",
                        value = "${formatLat(contact.lat)} / ${formatLon(contact.lon)}"
                    )
                }
            }
        }

        DividerLine(color = Hairline)
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlineActionButton(
                label = if (copied) "Copied" else "Copy coord",
                onClick = {
                    clipboard.setText(
                        AnnotatedString(
                            String.format(Locale.US, "%.5f, %.5f", contact.lat, contact.lon)
                        )
                    )
                    copied = true
                },
                modifier = Modifier.weight(1f)
            )
            OutlineActionButton(
                label = "Close",
                onClick = onClose,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
