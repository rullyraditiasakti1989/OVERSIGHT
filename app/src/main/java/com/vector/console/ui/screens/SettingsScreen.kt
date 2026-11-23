package com.vector.console.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vector.console.data.PhoneX
import com.vector.console.data.SourceStat
import com.vector.console.data.Store
import com.vector.console.data.globe.GlobeCredits
import com.vector.console.data.globe.LayerId
import com.vector.console.ui.components.ChoiceChip
import com.vector.console.ui.components.DividerLine
import com.vector.console.ui.components.FieldLabel
import com.vector.console.ui.components.GoldButton
import com.vector.console.ui.components.LoadingLine
import com.vector.console.ui.components.OutlineActionButton
import com.vector.console.ui.components.PanelCard
import com.vector.console.ui.components.ScreenHeader
import com.vector.console.ui.components.SectionLabel
import com.vector.console.ui.components.VectorTextField
import com.vector.console.ui.theme.Alert
import com.vector.console.ui.theme.Bone
import com.vector.console.ui.theme.ControlLine
import com.vector.console.ui.theme.Green
import com.vector.console.ui.theme.HairlineSoft
import com.vector.console.ui.theme.Muted
import com.vector.console.ui.theme.MutedDeep
import com.vector.console.ui.theme.Seal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(store: Store, onBack: () -> Unit) {
    val context = LocalContext.current
    val scroll = rememberScrollState()

    var hibp by remember { mutableStateOf(store.key("hibp")) }
    var pool by remember { mutableStateOf(PhoneX.accounts()) }
    var accEmail by remember { mutableStateOf("") }
    var accPass by remember { mutableStateOf("") }
    var accCode by remember { mutableStateOf("") }
    var accNote by remember { mutableStateOf<String?>(null) }
    var accBusy by remember { mutableStateOf(false) }
    var otpSent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var faceEndpoint by remember { mutableStateOf(store.key("face_endpoint")) }
    var faceKey by remember { mutableStateOf(store.key("face_key")) }
    var osirisBase by remember { mutableStateOf(store.key("osiris_base")) }
    var osirisToken by remember { mutableStateOf(store.key("osiris_token")) }
    var firmsKey by remember { mutableStateOf(store.key("firms_key")) }
    var aisKey by remember { mutableStateOf(store.key("ais_key")) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        ScreenHeader(
            kicker = "File 07 · Audit",
            title = "Settings",
            subtitle = "Keys, providers, and account pool. Everything on this page is stored on the device."
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel(label = "Keys", trailing = "DEVICE ONLY")
        Spacer(Modifier.height(10.dp))

        PanelCard(header = "Have I Been Pwned") {
            Text(
                text = "Feeds the email module and the NIK / number breach check. Key comes from " +
                    "haveibeenpwned.com/API. Official registries (KPU, Dukcapil, AHU) stay in the browser.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Spacer(Modifier.height(12.dp))
            FieldLabel("API key")
            Spacer(Modifier.height(6.dp))
            VectorTextField(
                value = hibp,
                onValueChange = { hibp = it; saved = false },
                placeholder = "0000000000000000000000000000000000000000"
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineActionButton(
                    label = "Open site",
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://haveibeenpwned.com/API/Key"))
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                OutlineActionButton(
                    label = "Clear",
                    onClick = { hibp = ""; saved = false },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        PanelCard(header = "Face provider") {
            Text(
                text = "Multipart POST endpoint, field name 'image'. Optional — Yandex carries the " +
                    "reverse search when this is blank.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Spacer(Modifier.height(12.dp))
            FieldLabel("Endpoint")
            Spacer(Modifier.height(6.dp))
            VectorTextField(
                value = faceEndpoint,
                onValueChange = { faceEndpoint = it; saved = false },
                placeholder = "https://api.example.com/v1/search"
            )
            Spacer(Modifier.height(10.dp))
            FieldLabel("Bearer token")
            Spacer(Modifier.height(6.dp))
            VectorTextField(
                value = faceKey,
                onValueChange = { faceKey = it; saved = false },
                placeholder = "token"
            )
        }

        Spacer(Modifier.height(10.dp))

        PanelCard(header = "OSIRIS bridge", trailing = "OPTIONAL") {
            Text(
                text = "Only the OSIRIS chip on the feed tab reads this. The other six feeds run " +
                    "without it. Leave both fields blank to disable.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Spacer(Modifier.height(12.dp))
            FieldLabel("Base URL")
            Spacer(Modifier.height(6.dp))
            VectorTextField(
                value = osirisBase,
                onValueChange = { osirisBase = it; saved = false },
                placeholder = "https://osiris.example.com"
            )
            Spacer(Modifier.height(10.dp))
            FieldLabel("Access token")
            Spacer(Modifier.height(6.dp))
            VectorTextField(
                value = osirisToken,
                onValueChange = { osirisToken = it; saved = false },
                placeholder = "blank for public feeds"
            )
        }

        // ---- globe sources ------------------------------------------------
        Spacer(Modifier.height(26.dp))
        SectionLabel(label = "Globe sources", trailing = "DEVICE ONLY")
        Spacer(Modifier.height(10.dp))

        PanelCard(header = "Layers", trailing = "PERSISTED") {
            Text(
                text = "On means the source is polled while GLOBE is on screen and its dots are drawn. " +
                    "The plate keeps the last good contacts when a poll fails and marks them CACHED.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Spacer(Modifier.height(12.dp))
            LayerId.entries.forEachIndexed { index, layer ->
                if (index > 0) {
                    Spacer(Modifier.height(10.dp))
                    DividerLine()
                }
                Spacer(Modifier.height(10.dp))
                val on = layer in store.globeLayers
                val stat = store.globeStats[layer]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .width(2.dp)
                            .height(26.dp)
                            .background(if (on) Bone else ControlLine)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = layer.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (on) Bone else Muted
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = sourceStatus(stat),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (stat?.stale == true) Seal else MutedDeep,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    ChoiceChip(
                        label = if (on) "ON" else "OFF",
                        selected = on,
                        onClick = { store.setGlobeLayer(layer, !on) }
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        PanelCard(header = "Refresh cadence") {
            Text(
                text = "How often live layers are re-polled while the globe is on screen. " +
                    "A change takes effect without restarting the app.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (ms in CADENCE_CHOICES) {
                    ChoiceChip(
                        label = "${ms / 1000} S",
                        selected = store.globeCadenceMs == ms,
                        onClick = { store.setGlobeCadence(ms) }
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        PanelCard(header = "Optional keys", trailing = "KEYLESS BY DEFAULT") {
            Text(
                text = "Both layers work with no key. FIRES falls back to NASA's keyless MODIS " +
                    "global file, VESSELS reads Digitraffic. A FIRMS key narrows the query to " +
                    "the view and swaps the 24 h snapshot for VIIRS near-real-time.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Spacer(Modifier.height(12.dp))
            FieldLabel("NASA FIRMS · MAP_KEY")
            Spacer(Modifier.height(6.dp))
            VectorTextField(
                value = firmsKey,
                onValueChange = { firmsKey = it; saved = false },
                placeholder = "FIRMS map key"
            )
            Spacer(Modifier.height(10.dp))
            FieldLabel("AISStream · API key")
            Spacer(Modifier.height(6.dp))
            VectorTextField(
                value = aisKey,
                onValueChange = { aisKey = it; saved = false },
                placeholder = "stored for a future websocket client"
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Stored for a future AISStream websocket client. VESSELS currently reads " +
                    "Digitraffic's keyless Baltic feed, which needs no key at all.",
                style = MaterialTheme.typography.labelSmall,
                color = MutedDeep
            )
        }

        Spacer(Modifier.height(14.dp))
        GoldButton(
            label = if (saved) "Keys saved" else "Save keys",
            onClick = {
                store.setKey("hibp", hibp.trim())
                store.setKey("face_endpoint", faceEndpoint.trim())
                store.setKey("face_key", faceKey.trim())
                store.setKey("osiris_base", osirisBase.trim())
                store.setKey("osiris_token", osirisToken.trim())
                store.setKey("firms_key", firmsKey.trim())
                store.setKey("ais_key", aisKey.trim())
                pool = PhoneX.accounts()
                saved = true
            }
        )

        // ---- account pool -------------------------------------------------
        Spacer(Modifier.height(26.dp))
        SectionLabel(
            label = "Phone-X pool",
            trailing = "${pool.count { !it.blocked }} LIVE"
        )
        Spacer(Modifier.height(10.dp))

        PanelCard {
            Text(
                text = "Each account carries a token balance for the deep-dive modules. When one " +
                    "runs dry the pool rotates to the next on its own.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )

            if (pool.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                DividerLine()
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "No accounts registered. Modules that need a balance will report 402 until one is added.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedDeep
                )
            }

            pool.forEach { account ->
                Spacer(Modifier.height(12.dp))
                DividerLine()
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .width(2.dp)
                            .height(28.dp)
                            .background(if (account.blocked) Alert else Green)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = account.email,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (account.blocked) Muted else Bone,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = when {
                                account.blocked -> "PARKED · ${account.note.ifBlank { "no note" }}"
                                account.balance >= 0 -> "${account.balance} TOKENS"
                                account.token.isNotBlank() -> "SESSION READY"
                                else -> "NOT SIGNED IN"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (account.blocked) Alert else MutedDeep
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (account.blocked) {
                        OutlineActionButton(
                            label = "Reset",
                            onClick = { PhoneX.unblock(account.email); pool = PhoneX.accounts() }
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    OutlineActionButton(
                        label = "Remove",
                        onClick = { PhoneX.removeAccount(account.email); pool = PhoneX.accounts() }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            DividerLine()
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Add an account. Use a Gmail alias (name+2@gmail.com) so the code lands in " +
                    "the mailbox you already read.",
                style = MaterialTheme.typography.labelSmall,
                color = MutedDeep
            )
            Spacer(Modifier.height(10.dp))
            FieldLabel("Email")
            Spacer(Modifier.height(6.dp))
            VectorTextField(
                value = accEmail,
                onValueChange = { accEmail = it },
                placeholder = "name+2@gmail.com"
            )
            Spacer(Modifier.height(8.dp))
            FieldLabel("Password")
            Spacer(Modifier.height(6.dp))
            VectorTextField(
                value = accPass,
                onValueChange = { accPass = it },
                placeholder = "minimum 8 characters"
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineActionButton(
                    label = "Sign in existing",
                    onClick = {
                        if (accEmail.isBlank() || accPass.isBlank()) {
                            accNote = "Fill email and password first."
                        } else {
                            scope.launch {
                                accBusy = true
                                accNote = null
                                PhoneX.addAccount(accEmail, accPass)
                                runCatching { PhoneX.login(accEmail.trim().lowercase()) }
                                    .onSuccess {
                                        accNote = "Account stored and signed in."
                                        accEmail = ""
                                        accPass = ""
                                        pool = PhoneX.accounts()
                                    }
                                    .onFailure { accNote = it.message }
                                accBusy = false
                                pool = PhoneX.accounts()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                OutlineActionButton(
                    label = if (otpSent) "Resend code" else "Register new",
                    onClick = {
                        scope.launch {
                            accBusy = true
                            accNote = null
                            runCatching { PhoneX.requestOtp(accEmail, reset = false) }
                                .onSuccess { accNote = it; otpSent = true }
                                .onFailure { accNote = it.message }
                            accBusy = false
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            if (otpSent) {
                Spacer(Modifier.height(10.dp))
                FieldLabel("Verification code")
                Spacer(Modifier.height(6.dp))
                VectorTextField(
                    value = accCode,
                    onValueChange = { accCode = it.filter(Char::isDigit).take(6) },
                    placeholder = "000000"
                )
                Spacer(Modifier.height(10.dp))
                GoldButton(
                    label = if (accBusy) "Working" else "Verify and store",
                    enabled = accCode.length == 6 && accPass.length >= 8 && !accBusy,
                    onClick = {
                        scope.launch {
                            accBusy = true
                            accNote = null
                            runCatching { PhoneX.completeRegistration(accEmail, accCode, accPass) }
                                .onSuccess {
                                    accNote = it
                                    accEmail = ""
                                    accPass = ""
                                    accCode = ""
                                    otpSent = false
                                    pool = PhoneX.accounts()
                                }
                                .onFailure { accNote = it.message }
                            accBusy = false
                        }
                    }
                )
            }
            accNote?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            if (accBusy) LoadingLine(label = "CONTACTING PHONE-X")
        }

        // ---- globe credits ------------------------------------------------
        Spacer(Modifier.height(26.dp))
        SectionLabel(label = "Globe credits", trailing = "${GlobeCredits.ALL.size} SOURCES")
        Spacer(Modifier.height(10.dp))

        PanelCard {
            GlobeCredits.ALL.forEachIndexed { index, credit ->
                if (index > 0) {
                    Spacer(Modifier.height(10.dp))
                    DividerLine()
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = credit.source.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Bone
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = credit.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
                if (credit.url != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = credit.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedDeep
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            DividerLine()
            Spacer(Modifier.height(10.dp))
            Text(
                text = GlobeCredits.IMAGERY,
                style = MaterialTheme.typography.labelSmall,
                color = MutedDeep
            )
        }

        // ---- device -------------------------------------------------------
        Spacer(Modifier.height(26.dp))
        SectionLabel(label = "Device")
        Spacer(Modifier.height(10.dp))

        PanelCard(header = "Local data", trailing = "IRREVERSIBLE") {
            Text(
                text = "Wipes every case, audit answer, key, and zone log stored on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Spacer(Modifier.height(12.dp))
            OutlineActionButton(
                label = "Delete all data",
                onClick = { store.clearAll() }
            )
        }

        Spacer(Modifier.height(10.dp))

        PanelCard(header = "About") {
            Text(
                text = "OVERSIGHT",
                style = MaterialTheme.typography.titleMedium,
                color = Bone
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "PERSONAL INTELLIGENCE CONSOLE · v1.0 · SDK 35 · minSdk 26",
                style = MaterialTheme.typography.labelSmall,
                color = MutedDeep
            )
            Spacer(Modifier.height(10.dp))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(HairlineSoft))
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Local data never leaves the device. Outbound traffic is limited to the " +
                    "public sources you trigger, plus the OSIRIS bridge when a URL is set.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedDeep
            )
        }

        Spacer(Modifier.height(18.dp))
        OutlineActionButton(label = "Back", onClick = onBack)
        Spacer(Modifier.height(28.dp))
    }
}

private val CADENCE_CHOICES = longArrayOf(15_000L, 30_000L, 60_000L)

/** In-memory stats reset on restart, so "never fetched" is the honest first row. */
private fun sourceStatus(stat: SourceStat?): String {
    if (stat == null) return "NOT FETCHED"
    val clock = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(stat.atMillis))
    val size = when {
        stat.bytes <= 0 -> "LOCAL"
        stat.bytes < 1024 -> "${stat.bytes} B"
        stat.bytes < 1024 * 1024 -> "%.1f KB".format(stat.bytes / 1024.0)
        else -> "%.1f MB".format(stat.bytes / (1024.0 * 1024.0))
    }
    return "${if (stat.stale) "CACHED" else "LIVE"} · ${stat.count} · $size · $clock UTC"
}
