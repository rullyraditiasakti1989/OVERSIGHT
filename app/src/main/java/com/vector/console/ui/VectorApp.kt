package com.vector.console.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vector.console.data.GeoStore
import com.vector.console.data.PhoneX
import com.vector.console.data.Store
import com.vector.console.ui.components.ClassificationBar
import com.vector.console.ui.components.VectorTopBar
import com.vector.console.ui.components.frameTicks
import com.vector.console.ui.screens.AreaCctvScreen
import com.vector.console.ui.screens.CasesScreen
import com.vector.console.ui.screens.CctvScreen
import com.vector.console.ui.screens.FaceScreen
import com.vector.console.ui.screens.GeoScreen
import com.vector.console.ui.screens.GlobeScreen
import com.vector.console.ui.screens.HomeScreen
import com.vector.console.ui.screens.IdentityScreen
import com.vector.console.ui.screens.IntelScreen
import com.vector.console.ui.screens.PayloadScreen
import com.vector.console.ui.screens.SettingsScreen
import com.vector.console.ui.theme.Bone
import com.vector.console.ui.theme.HairlineSoft
import com.vector.console.ui.theme.Ink
import com.vector.console.ui.theme.MutedDeep
import com.vector.console.ui.theme.Panel
import com.vector.console.ui.theme.PanelDeep
import com.vector.console.ui.theme.PanelRaised
import com.vector.console.ui.theme.enter
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class Screen(val label: String, val index: String) {
    HOME("HOME", "00"),
    INTEL("INTEL", "01"),
    IDENTITY("IDENT", "02"),
    FACE("FACE", "03"),
    GEO("GEO", "04"),
    CASES("CASE", "05"),
    CCTV("CCTV", "06"),
    SETTINGS("SET", "07"),
    PAYLOAD("LOAD", "08"),
    GLOBE("GLOBE", "09")
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun VectorApp() {
    val context = LocalContext.current
    val store = remember { Store(context) }
    val geoStore = remember { GeoStore(context) }
    SideEffect { PhoneX.attach(store) }
    var screenIndex by rememberSaveable { mutableIntStateOf(0) }
    var area by rememberSaveable { mutableStateOf<String?>(null) }
    var globeExpanded by rememberSaveable { mutableStateOf(false) }
    val screen = Screen.entries[screenIndex]

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Ink).systemBarsPadding()) {
        // A map screen earns full-bleed in landscape: the plate needs the short
        // side, and 200dp of chrome above a 411dp-tall window leaves a strip,
        // not a globe. The rail stays — it is navigation, not decoration.
        // EXPAND gives portrait the same treatment without faking a rotation.
        val plate = screen == Screen.GLOBE && (maxWidth > maxHeight || globeExpanded)

        Column(modifier = Modifier.fillMaxSize()) {
            // Wrapped in one stable slot each. Removing a composable call
            // outright shifts every following slot, which recreates the globe
            // screen on rotation and reloads the page; a wrapper keeps the
            // siblings' positions fixed.
            ChromeSlot(visible = !plate) {
                ClassificationBar()
                VectorTopBar(onSettings = { screenIndex = Screen.SETTINGS.ordinal })
            }
            DossierRail(selected = screenIndex, onSelect = { screenIndex = it })

            Box(
                modifier = Modifier
                    .weight(1f)
                    .frameTicks()
                    .enter(key = area ?: screen)
            ) {
                val currentArea = area
                if (currentArea != null) {
                    AreaCctvScreen(area = currentArea, onBack = { area = null })
                } else when (screen) {
                    Screen.HOME -> HomeScreen(
                        store = store,
                        geoStore = geoStore,
                        onNavigate = { screenIndex = it.ordinal }
                    )
                    Screen.IDENTITY -> IdentityScreen(store = store)
                    Screen.FACE -> FaceScreen(store = store)
                    Screen.CCTV -> CctvScreen(onOpenArea = { area = it })
                    Screen.CASES -> CasesScreen(store = store)
                    Screen.GEO -> GeoScreen(geoStore = geoStore)
                    Screen.INTEL -> IntelScreen(store = store)
                    Screen.PAYLOAD -> PayloadScreen(store = store)
                    Screen.GLOBE -> GlobeScreen(
                        store = store,
                        expanded = globeExpanded,
                        onToggleExpand = { globeExpanded = !globeExpanded }
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        store = store,
                        onBack = { screenIndex = Screen.HOME.ordinal }
                    )
                }
            }

            TelemetrySlot(visible = !plate, area = area)
        }
    }
}

/**
 * A slot that can be emptied without disturbing its siblings.
 *
 * Compose identifies composables by call-site position. Dropping a call
 * (`if (x) Foo()`) removes its group and shifts every following sibling, which
 * destroys their state — for the globe that meant a reloaded WebView and a lost
 * camera on every rotation. Wrapping the conditional in a composable of its own
 * keeps the sibling positions stable and only the wrapper's contents change.
 */
@Composable
private fun ChromeSlot(visible: Boolean, content: @Composable () -> Unit) {
    if (visible) content()
}

@Composable
private fun TelemetrySlot(visible: Boolean, area: String?) {
    if (visible) TelemetryStrip(area = area)
}

/**
 * File index rail. Ruled cells, no gaps, no rounding. The active cell inverts to
 * bone with ink text — the selected state is carried by inversion, not by hue.
 */
@Composable
private fun DossierRail(selected: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(HairlineSoft))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .height(56.dp)
        ) {
            Screen.entries.forEachIndexed { index, item ->
                RailCell(
                    item = item,
                    active = index == selected,
                    onClick = { onSelect(index) }
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(HairlineSoft))
    }
}

@Composable
private fun RailCell(item: Screen, active: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val background = when {
        active -> Bone
        pressed -> PanelRaised
        else -> PanelDeep
    }
    Column(
        modifier = Modifier
            .width(84.dp)
            .height(56.dp)
            .background(background)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .semantics { contentDescription = "Open ${item.label}" }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = item.index,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                active -> Ink
                pressed -> Bone
                else -> MutedDeep
            }
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                active -> Ink
                pressed -> Bone
                else -> MutedDeep
            }
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    when {
                        active -> Ink
                        pressed -> Bone
                        else -> HairlineSoft
                    }
                )
        )
    }
    Box(modifier = Modifier.width(1.dp).height(56.dp).background(HairlineSoft))
}

/**
 * Bottom telemetry: a live UTC clock on the left, the standing posture on the
 * right. The file code already sits in the content kicker, so repeating it here
 * would be a second label for one fact. The clock is held inside this composable
 * so the per-second tick recomposes the strip only.
 */
@Composable
private fun TelemetryStrip(area: String?) {
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val format = SimpleDateFormat("HH:mm:ss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        while (true) {
            clock = format.format(Date())
            delay(1000L - (System.currentTimeMillis() % 1000L))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(Panel)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(2.dp).height(9.dp).background(Bone.copy(alpha = 0.5f)))
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (area != null) "$clock UTC · AREA" else "$clock UTC",
            style = MaterialTheme.typography.labelSmall,
            color = MutedDeep
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "LOCAL ONLY · NO SYNC",
            style = MaterialTheme.typography.labelSmall,
            color = MutedDeep
        )
    }
}