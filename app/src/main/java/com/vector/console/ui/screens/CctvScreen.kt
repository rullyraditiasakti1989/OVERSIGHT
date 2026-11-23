package com.vector.console.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vector.console.data.CctvCamera
import com.vector.console.data.CctvCatalog
import com.vector.console.data.CctvTaxonomy
import com.vector.console.data.IndonesiaCctv
import com.vector.console.data.MalangCctv
import com.vector.console.ui.components.EmptyState
import com.vector.console.ui.components.GoldButton
import com.vector.console.ui.components.HlsPreview
import com.vector.console.ui.components.LoadingLine
import com.vector.console.ui.components.OutlineActionButton
import com.vector.console.ui.components.PanelCard
import com.vector.console.ui.components.ScreenHeader
import com.vector.console.ui.components.SectionLabel
import com.vector.console.ui.components.Tag
import com.vector.console.ui.components.VectorTextField
import com.vector.console.ui.theme.Alert
import com.vector.console.ui.theme.Blue
import com.vector.console.ui.theme.Bone
import com.vector.console.ui.theme.BoneDim
import com.vector.console.ui.theme.ControlLine
import com.vector.console.ui.theme.Green
import com.vector.console.ui.theme.HairlineSoft
import com.vector.console.ui.theme.Ink
import com.vector.console.ui.theme.Muted
import com.vector.console.ui.theme.MutedDeep
import com.vector.console.ui.theme.PanelDeep
import com.vector.console.ui.theme.enter
import kotlinx.coroutines.delay

/** Internal catalog keys. The old flat chip row is retired in favour of the taxonomy filter. */
private enum class CctvSource {
    INDONESIA, MALANG, SINGAPURA, CALIFORNIA, IRLANDIA
}

/** Android blocks cleartext; try the TLS variant of any listed HTTP feed first. */
private fun secureUrl(url: String?): String? =
    url?.takeIf { it.startsWith("http://", ignoreCase = true) }
        ?.replaceFirst("http://", "https://", ignoreCase = true)
        ?: url

private fun withTick(url: String?, tick: Int): String? {
    val safe = secureUrl(url) ?: return null
    return if (safe.contains("?")) "$safe&v=$tick" else "$safe?v=$tick"
}

@SuppressLint("UnsafeOptInUsageError") // ExoPlayer HLS API is marked unstable upstream.
@Composable
fun CctvScreen(onOpenArea: (String) -> Unit = {}) {
    val context = LocalContext.current
    var region by remember { mutableStateOf("Indonesia") }
    var province by remember { mutableStateOf<String?>(null) }
    var operator by remember { mutableStateOf<String?>(null) }
    val source = when {
        region == "International" && province == "Singapore" -> CctvSource.SINGAPURA
        region == "International" && province == "California" -> CctvSource.CALIFORNIA
        region == "International" && province == "Ireland" -> CctvSource.IRLANDIA
        operator == "cctv-malang" -> CctvSource.MALANG
        else -> CctvSource.INDONESIA
    }
    var cameras by remember { mutableStateOf<List<CctvCamera>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<CctvCamera?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var limit by remember { mutableIntStateOf(40) }
    var staggerDone by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val chipScroll = rememberScrollState()

    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) staggerDone = true
    }

    LaunchedEffect(source) {
        limit = 40
        loading = true
        error = null
        cameras = emptyList()
        runCatching {
            when (source) {
                CctvSource.INDONESIA -> IndonesiaCctv.load(context).map { camera ->
                    val isImage = listOf(".jpg", ".jpeg", ".png").any {
                        camera.url.contains(it, ignoreCase = true)
                    }
                    CctvCamera(
                        id = camera.id,
                        name = camera.name,
                        country = "Indonesia",
                        city = camera.city,
                        latitude = camera.latitude,
                        longitude = camera.longitude,
                        imageUrl = if (isImage) camera.url else null,
                        streamUrl = if (isImage) null else camera.url,
                        externalUrl = null,
                        source = camera.source,
                        available = camera.available
                    )
                }
                CctvSource.MALANG -> MalangCctv.load().map { camera ->
                    CctvCamera(
                        id = camera.id,
                        name = camera.name,
                        country = "Indonesia",
                        city = camera.district.ifBlank { "Malang" },
                        latitude = camera.latitude,
                        longitude = camera.longitude,
                        imageUrl = null,
                        streamUrl = camera.streamUrl,
                        externalUrl = null,
                        source = "CCTV Malang",
                        hlsHeaders = MalangCctv.streamHeaders()
                    )
                }
                CctvSource.SINGAPURA -> CctvCatalog.singapore().map { it.copy(source = "Singapore") }
                CctvSource.CALIFORNIA -> CctvCatalog.caltrans().map { it.copy(source = "California") }
                CctvSource.IRLANDIA -> CctvCatalog.ireland().map { it.copy(source = "Ireland") }
            }
        }.onSuccess { cameras = it }
            .onFailure { error = it.message }
        loading = false
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            tick++
        }
    }

    val filtered = remember(query, cameras, region, province, operator) {
        cameras.filter { camera ->
            val row = CctvTaxonomy.rows.firstOrNull { it.source == camera.source }
                ?: CctvTaxonomy.rows.firstOrNull { it.displayLabel == camera.source }
            val tierOk = if (row == null) {
                region == "Indonesia" && province == null && operator == null
            } else {
                (province == null || province == row.province) &&
                    (operator == null || operator == row.operatorId) &&
                    (region == "International" || region == row.region)
            }
            val queryOk = query.isBlank() || camera.name.contains(query, true) ||
                camera.city.contains(query, true) || camera.country.contains(query, true) ||
                camera.source.contains(query, true)
            tierOk && queryOk && camera.available
        }
    }

    val camera = selected
    var streamError by remember(camera?.id) { mutableStateOf<String?>(null) }
    if (camera != null) {
        DetailView(
            camera = camera,
            tick = tick,
            streamError = streamError,
            onStreamError = { streamError = it },
            onBack = { selected = null },
            onRefresh = { streamError = null; tick++ },
            onOpenExternal = { link ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                }
            }
        )
        return
    }

    val provinceOptions = remember(region) {
        listOf<String?>(null) + CctvTaxonomy.rows
            .filter { it.region == region }
            .map { it.province }
            .distinct()
            .sorted()
    }
    val operatorOptions = remember(region, province) {
        listOf<Pair<String, String>?>(
            null
        ) + CctvTaxonomy.rows
            .filter { it.region == region && (province == null || it.province == province) }
            .map { it.operatorId to it.displayLabel }
            .distinct()
            .sortedBy { it.second }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        ScreenHeader(
            kicker = "Public cameras",
            title = "CCTV",
            subtitle = "Snapshot and public stream feeds. No server, no API key."
        )
        Spacer(Modifier.height(14.dp))

        // Filters stay folded once a region is chosen — the wall is the screen,
        // the filter is a drawer you open when the view is wrong.
        val summary = listOfNotNull(
            region,
            province,
            operator?.let { op ->
                CctvTaxonomy.rows.firstOrNull { it.operatorId == op }?.displayLabel
            }
        ).joinToString(" / ")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = summary,
                style = MaterialTheme.typography.titleSmall,
                color = BoneDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            OutlineActionButton(
                label = if (filtersOpen) "Close filter" else "Filter",
                onClick = { filtersOpen = !filtersOpen }
            )
        }

        if (filtersOpen) {
            Spacer(Modifier.height(10.dp))
            PanelCard {
                FilterChips(
                    label = "REGION",
                    options = listOf("Indonesia", "International"),
                    selected = region,
                    scroll = chipScroll
                ) { value ->
                    region = value
                    province = null
                    operator = null
                }
                Spacer(Modifier.height(10.dp))
                FilterChips(
                    label = "PROVINCE",
                    options = provinceOptions.map { it ?: "All" },
                    selected = province ?: "All",
                    scroll = chipScroll
                ) { value ->
                    province = value.takeIf { it != "All" }
                    operator = null
                }
                Spacer(Modifier.height(10.dp))
                FilterChips(
                    label = "OPERATOR",
                    options = operatorOptions.map { it?.second ?: "All" },
                    selected = operatorOptions.firstOrNull { it?.first == operator }?.second ?: "All",
                    scroll = chipScroll
                ) { value ->
                    operator = operatorOptions.firstOrNull { it?.second == value }?.first
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        VectorTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Filter by name, city, or source"
        )
        if (query.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            GoldButton(
                label = "Open area page",
                onClick = { onOpenArea(query.trim()) }
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionLabel(label = "CAMERA WALL", trailing = "${filtered.size} CAMERAS")
        Spacer(Modifier.height(8.dp))

        if (loading) LoadingLine(label = "FETCHING CAMERA CATALOG")
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Alert)
        }
        if (!loading && filtered.isEmpty() && error == null) {
            EmptyState("Empty catalog", "Source returned no cameras. Try another source.")
        }

        LazyVerticalGrid(
            state = gridState,
            modifier = Modifier.weight(1f),
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(filtered.take(limit), key = { _, item -> item.id }) { index, item ->
                CameraTile(
                    camera = item,
                    tick = tick,
                    onClick = { selected = item },
                    showPreview = index < 8,
                    modifier = if (index < 6 && !staggerDone) {
                        Modifier.enter(key = item.id)
                    } else {
                        Modifier
                    }
                )
            }
            if (filtered.size > limit) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Spacer(Modifier.height(4.dp))
                        OutlineActionButton(
                            label = "Show 40 more · ${filtered.size - limit} remaining",
                            onClick = { limit += 40 }
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun CameraRow(camera: CctvCamera, tick: Int, onClick: () -> Unit) {
    PanelCard(padding = 10.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val model = withTick(camera.imageUrl, tick)
                ?: camera.streamUrl
                ?: camera.externalUrl
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(0.dp))
                    .background(PanelDeep)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(model)
                        .crossfade(true)
                        .build(),
                    contentDescription = camera.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (!camera.snapshot) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(5.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (camera.streamUrl?.contains(".m3u8", true) == true) {
                                    Green
                                } else {
                                    Blue
                                }
                            )
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = camera.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Bone,
                    maxLines = 2
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(
                        camera.city.takeIf { it.isNotBlank() },
                        camera.country.takeIf { it.isNotBlank() }
                    ).joinToString(" · ").ifBlank { "Location unknown" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedDeep
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = camera.source.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = Muted
                )
            }
            if (!camera.snapshot) {
                Tag(
                    text = if (camera.streamUrl?.contains(".m3u8", true) == true) "LIVE HLS" else "EXTERNAL",
                    color = Blue
                )
            }
        }
    }
}

@Composable
private fun FilterChips(
    label: String,
    options: List<String>,
    selected: String,
    scroll: ScrollState,
    onSelect: (String) -> Unit
) {
    Text(text = label, style = MaterialTheme.typography.labelSmall, color = MutedDeep)
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { value ->
            val active = value == selected
            Box(
                modifier = Modifier
                    .border(
                        1.dp,
                        if (active) Bone else ControlLine,
                        MaterialTheme.shapes.extraSmall
                    )
                    .background(
                        if (active) Bone else PanelDeep,
                        MaterialTheme.shapes.extraSmall
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(
                    text = value.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) Ink else Muted
                )
            }
        }
    }
}

@UnstableApi
@Composable
internal fun CameraTile(
    camera: CctvCamera,
    tick: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showPreview: Boolean = false
) {
    PanelCard(modifier = modifier, padding = 8.dp) {
        Column(modifier = Modifier.clickable(onClick = onClick)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(0.dp))
                    .background(PanelDeep)
            ) {
                val streamUrl = secureUrl(camera.streamUrl)
                val isHls = streamUrl?.contains(".m3u8", true) == true
                when {
                    camera.imageUrl != null -> AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(withTick(camera.imageUrl, tick))
                            .crossfade(true)
                            .build(),
                        contentDescription = camera.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    isHls && showPreview -> HlsPreview(
                        url = streamUrl,
                        headers = camera.hlsHeaders,
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(streamUrl ?: camera.externalUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = camera.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (!camera.snapshot) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (camera.streamUrl?.contains(".m3u8", true) == true) {
                                    Green
                                } else {
                                    Blue
                                }
                            )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = camera.name,
                style = MaterialTheme.typography.bodyMedium,
                color = Bone,
                maxLines = 2
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = camera.city.ifBlank { camera.country },
                style = MaterialTheme.typography.labelSmall,
                color = MutedDeep,
                maxLines = 1
            )
            Spacer(Modifier.height(8.dp))
            Tag(
                text = if (camera.snapshot) "SNAPSHOT" else "LIVE HLS",
                color = if (camera.snapshot) Blue else Green
            )
        }
    }
}

@UnstableApi
@Composable
internal fun DetailView(
    camera: CctvCamera,
    tick: Int,
    streamError: String?,
    onStreamError: (String?) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenExternal: (String) -> Unit
) {
    val hlsUrl = secureUrl(camera.streamUrl)?.takeIf { it.contains(".m3u8", true) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        OutlineActionButton(label = "Back", onClick = onBack)
        Spacer(Modifier.height(12.dp))
        ScreenHeader(
            kicker = camera.source,
            title = camera.name,
            subtitle = listOfNotNull(
                camera.city.takeIf { it.isNotBlank() },
                camera.country.takeIf { it.isNotBlank() }
            ).joinToString(" · ").ifBlank { "Location unknown" }
        )
        Spacer(Modifier.height(14.dp))

        PanelCard(padding = 8.dp) {
            val snapshotUrl = withTick(camera.imageUrl, tick)
            when {
                hlsUrl != null -> HlsPlayer(
                    url = hlsUrl,
                    headers = camera.hlsHeaders,
                    reloadKey = tick,
                    onError = onStreamError
                )
                snapshotUrl != null -> AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(snapshotUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = camera.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .background(PanelDeep)
                )
                else -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .background(PanelDeep),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "EXTERNAL STREAM",
                        style = MaterialTheme.typography.labelMedium,
                        color = Muted
                    )
                }
            }
        }

        streamError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Alert)
        }

        Spacer(Modifier.height(10.dp))
        if (camera.latitude != null && camera.longitude != null) {
            PanelCard {
                SectionLabel(label = "Coordinates")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = String.format(
                        java.util.Locale.US, "%.5f, %.5f", camera.latitude, camera.longitude
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = BoneDim
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoldButton(
                label = if (hlsUrl != null) "Reload stream" else "Reload image",
                onClick = onRefresh,
                modifier = Modifier.weight(1f)
            )
        }
        val link = secureUrl(camera.streamUrl) ?: secureUrl(camera.externalUrl)
        if (link != null) {
            Spacer(Modifier.height(8.dp))
            OutlineActionButton(
                label = "Open external stream",
                onClick = { onOpenExternal(link) }
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (hlsUrl != null) {
                "Live HLS stream. Reload if interrupted."
            } else {
                "Snapshot refreshes automatically every 60 seconds."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MutedDeep
        )
        Spacer(Modifier.height(20.dp))
    }
}

@UnstableApi
@Composable
private fun HlsPlayer(
    url: String,
    headers: Map<String, String> = emptyMap(),
    reloadKey: Int = 0,
    onError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val player = remember(url, headers, reloadKey) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
        val source = HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(url))
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(source)
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    onError(error.message ?: "Stream failed to load.")
                }
            })
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { target ->
            PlayerView(target).apply {
                this.player = player
                useController = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(PanelDeep)
    )
}
