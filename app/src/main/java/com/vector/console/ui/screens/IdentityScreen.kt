package com.vector.console.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vector.console.data.BreachHit
import com.vector.console.data.CredentialHit
import com.vector.console.data.IdentityIntel
import com.vector.console.data.LeakReport
import com.vector.console.data.NikProfile
import com.vector.console.data.PhoneProfile
import com.vector.console.data.PhoneX
import com.vector.console.data.PresenceResult
import com.vector.console.data.PresenceVerdict
import com.vector.console.data.StealerReport
import com.vector.console.data.Store
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
import com.vector.console.ui.components.SegmentedTabs
import com.vector.console.ui.components.Tag
import com.vector.console.ui.components.VectorTextField
import com.vector.console.ui.theme.Alert
import com.vector.console.ui.theme.Bone
import com.vector.console.ui.theme.BoneDim
import com.vector.console.ui.theme.Green
import com.vector.console.ui.theme.HairlineSoft
import com.vector.console.ui.theme.Muted
import com.vector.console.ui.theme.MutedDeep
import com.vector.console.ui.theme.Seal
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

private val identityTabs = listOf("NIK", "Phone", "Email")

/** Status word for a result set. Colour is a second cue, never the only one. */
private fun verdictColour(hit: Boolean): Color = if (hit) Alert else Green

/** One keyless verdict. UNKNOWN is a real answer, not an unstyled absent. */
@Composable
private fun PresenceVerdictRow(result: PresenceResult) {
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = result.channel, style = MaterialTheme.typography.titleSmall, color = Bone)
            Spacer(Modifier.height(3.dp))
            Text(text = result.detail, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Spacer(Modifier.width(10.dp))
        Tag(
            text = when (result.verdict) {
                PresenceVerdict.PRESENT -> "PRESENT"
                PresenceVerdict.ABSENT -> "ABSENT"
                PresenceVerdict.UNKNOWN -> "UNKNOWN"
            },
            color = if (result.verdict == PresenceVerdict.UNKNOWN) {
                MutedDeep
            } else {
                verdictColour(!result.registered)
            }
        )
    }
}

@Composable
fun IdentityScreen(store: Store) {
    var tab by remember { mutableStateOf(0) }
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        ScreenHeader(
            kicker = "File 02 · Identity",
            title = "Identity",
            subtitle = "NIK decodes on the device. Phone and email sweep the open indexes."
        )
        Spacer(Modifier.height(20.dp))
        SegmentedTabs(options = identityTabs, selected = tab, onSelect = { tab = it })
        Spacer(Modifier.height(20.dp))

        when (tab) {
            0 -> NikPanel()
            1 -> PhonePanel()
            2 -> EmailPanel(store)
        }
        Spacer(Modifier.height(28.dp))
    }
}

// --------------------------------------------------------------------- NIK

@Composable
private fun NikPanel() {
    var input by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf<NikProfile?>(null) }
    var masked by remember { mutableStateOf(true) }
    var leak by remember { mutableStateOf<LeakReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    SectionLabel(label = "Decode", trailing = "LOCAL · NO NETWORK")
    Spacer(Modifier.height(10.dp))

    PanelCard {
        FieldLabel("16 digit NIK")
        Spacer(Modifier.height(6.dp))
        VectorTextField(
            value = input,
            onValueChange = {
                input = it.filter { ch -> ch.isDigit() }.take(16)
                profile = null
            },
            placeholder = "3273xxxxxxxxxxxx"
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${input.length} / 16",
                style = MaterialTheme.typography.labelSmall,
                color = if (input.length == 16) Green else MutedDeep,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (profile?.valid == true) "STRUCTURE VALID" else "",
                style = MaterialTheme.typography.labelSmall,
                color = Green
            )
        }
        Spacer(Modifier.height(12.dp))
        GoldButton(
            label = "Decode",
            enabled = input.length == 16,
            onClick = { profile = IdentityIntel.parseNik(input) }
        )
    }

    profile?.let { item ->
        Spacer(Modifier.height(12.dp))
        PanelCard(header = "Decoded record", trailing = if (item.valid) "VALID" else "CHECK") {
            DataRow("NIK", if (masked) IdentityIntel.maskNik(item.nik) else item.nik, Bone)
            DataRow("Province", item.province ?: "Unknown code ${item.provinceCode}")
            DataRow("Regency", item.regency ?: "Code ${item.regencyCode}")
            DataRow("District", "Code ${item.districtCode}")
            DataRow("Born", item.birthDate)
            DataRow("Age", "${item.age} years")
            DataRow("Gender", item.gender, BoneDim)
            DataRow("Sequence", item.sequence)
            DataRow("Postal area", item.postalArea)

            if (item.issues.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                DividerLine(color = Seal)
                Spacer(Modifier.height(10.dp))
                item.issues.forEach { issue ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .width(5.dp)
                                .height(5.dp)
                                .background(Seal)
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = issue,
                            style = MaterialTheme.typography.bodySmall,
                            color = Alert
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            DividerLine()
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineActionButton(
                    label = if (masked) "Reveal" else "Mask",
                    onClick = { masked = !masked },
                    modifier = Modifier.weight(1f)
                )
                OutlineActionButton(
                    label = "Copy",
                    onClick = { copy(context, "NIK", item.nik) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Decoded offline. Structural validity is not proof of registration.",
                style = MaterialTheme.typography.labelSmall,
                color = MutedDeep
            )
        }
    }

    if (profile != null) {
        Spacer(Modifier.height(24.dp))
        SectionLabel(label = "Public check", trailing = "NIK")
        Spacer(Modifier.height(10.dp))
        PanelCard {
            Text(
                text = "The official registries have no keyless API, so they open in the browser. " +
                    "HIBP only tells you whether this NIK ever surfaced in a breach.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Spacer(Modifier.height(12.dp))
            GoldButton(
                label = if (loading) "Checking" else "Check breach index",
                enabled = input.length == 16 && !loading,
                onClick = {
                    scope.launch {
                        loading = true
                        error = null
                        leak = null
                        runCatching { IdentityIntel.leakCheck(input) }
                            .onSuccess { leak = it }
                            .onFailure { error = it.message }
                        loading = false
                    }
                }
            )
            if (loading) {
                Spacer(Modifier.height(8.dp))
                LoadingLine(label = "MATCHING INDEX")
            }
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Alert)
            }
            leak?.let { report -> LeakBlock(report, "this NIK") }
        }

        Spacer(Modifier.height(12.dp))
        PanelCard(header = "Registry links") {
            IdentityIntel.registryLinks(input).forEachIndexed { index, link ->
                if (index > 0) DividerLine()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = link.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BoneDim,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlineActionButton(
                        label = "Open",
                        onClick = { open(context, link.url) }
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    SectionLabel(label = "Field layout")
    Spacer(Modifier.height(10.dp))
    PanelCard {
        DataRow("1-2", "Province (BPS code)")
        DataRow("3-4", "Regency / city")
        DataRow("5-6", "District")
        DataRow("7-12", "DDMMYY · +40 for female")
        DataRow("13-16", "Issue sequence")
    }
}

// ------------------------------------------------------------------- phone

@Composable
private fun PhonePanel() {
    var input by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf<PhoneProfile?>(null) }
    var results by remember { mutableStateOf<List<PresenceResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var leak by remember { mutableStateOf<LeakReport?>(null) }
    var breachLoading by remember { mutableStateOf(false) }
    var stealerUser by remember { mutableStateOf<StealerReport?>(null) }
    var handleResult by remember { mutableStateOf<PresenceResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    SectionLabel(label = "Number", trailing = "ID · E.164")
    Spacer(Modifier.height(10.dp))

    PanelCard {
        FieldLabel("Phone number")
        Spacer(Modifier.height(6.dp))
        VectorTextField(
            value = input,
            onValueChange = { input = it; profile = null; results = emptyList() },
            placeholder = "0812xxxxxxx or +62812xxxxxxx"
        )
        Spacer(Modifier.height(12.dp))
        GoldButton(
            label = "Parse number",
            enabled = input.isNotBlank(),
            onClick = {
                error = null
                results = emptyList()
                profile = IdentityIntel.parsePhone(input)
            }
        )
    }

    profile?.let { item ->
        Spacer(Modifier.height(12.dp))
        PanelCard(header = "Operator profile", trailing = if (item.valid) "VALID" else "CHECK") {
            DataRow("E.164", item.e164, Bone)
            DataRow("National", item.national)
            DataRow("Country", item.countryCode)
            DataRow("Operator", item.operator ?: "Unmapped", BoneDim)
            DataRow("Line type", item.lineType)
            item.note?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }

        Spacer(Modifier.height(12.dp))
        PanelCard(header = "Open channels") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineActionButton(
                    label = "WhatsApp",
                    onClick = { open(context, "https://wa.me/${item.e164.removePrefix("+")}") },
                    modifier = Modifier.weight(1f)
                )
                OutlineActionButton(
                    label = "Telegram",
                    onClick = { open(context, "https://t.me/+${item.e164.removePrefix("+")}") },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineActionButton(
                    label = "GetContact",
                    onClick = {
                        if (!open(context, "getcontact://")) {
                            open(context, "https://play.google.com/store/apps/details?id=com.getcontact.getcontact")
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                OutlineActionButton(
                    label = "Truecaller",
                    onClick = {
                        open(context, "https://www.truecaller.com/search/id/${item.e164.removePrefix("+")}")
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "GetContact has no public API, so that button opens the app. Truecaller opens its " +
                    "public web page.",
                style = MaterialTheme.typography.labelSmall,
                color = MutedDeep
            )
        }

        Spacer(Modifier.height(12.dp))
        PanelCard(header = "Presence probe") {
            GoldButton(
                label = if (loading) "Probing" else "Probe WhatsApp and Telegram",
                enabled = !loading,
                onClick = {
                    scope.launch {
                        loading = true
                        error = null
                        val collected = mutableListOf<PresenceResult>()
                        runCatching { IdentityIntel.whatsapp(item) }
                            .onSuccess { collected += it }
                            .onFailure { error = it.message }
                        runCatching { IdentityIntel.telegramByPhone(item) }
                            .onSuccess { collected += it }
                            .onFailure { error = "Telegram: ${it.message}" }
                        results = collected
                        loading = false
                    }
                }
            )
            if (loading) {
                Spacer(Modifier.height(8.dp))
                LoadingLine(label = "KNOCKING CHANNELS")
            }
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Alert)
            }
            if (results.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                DividerLine()
                Spacer(Modifier.height(10.dp))
                results.forEachIndexed { index, result ->
                    if (index > 0) {
                        Spacer(Modifier.height(10.dp))
                        DividerLine()
                        Spacer(Modifier.height(10.dp))
                    }
                    PresenceVerdictRow(result)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Neither service confirms registration on a keyless public page. A generic " +
                        "answer reads UNKNOWN, never PRESENT. No community name labels.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedDeep
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel(label = "Breach sweep", trailing = "PUBLIC INDEX")
        Spacer(Modifier.height(10.dp))
        PanelCard {
            GoldButton(
                label = if (breachLoading) "Checking" else "Check this number",
                enabled = !breachLoading,
                onClick = {
                    val e164 = item.e164
                    scope.launch {
                        breachLoading = true
                        error = null
                        leak = null
                        runCatching { IdentityIntel.leakCheck(e164) }
                            .onSuccess { leak = it }
                            .onFailure { error = it.message }
                        breachLoading = false
                    }
                }
            )
            if (breachLoading) {
                Spacer(Modifier.height(8.dp))
                LoadingLine(label = "MATCHING INDEX")
            }
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Alert)
            }
            leak?.let { report -> LeakBlock(report, "this number") }
        }

        Spacer(Modifier.height(12.dp))
        PanelCard(header = "Breach sources") {
            IdentityIntel.breachLinks(item.e164).forEachIndexed { index, link ->
                if (index > 0) DividerLine()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = link.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BoneDim,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlineActionButton(label = "Open", onClick = { open(context, link.url) })
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel(label = "Paid modules", trailing = "PHONE-X")
        Spacer(Modifier.height(10.dp))
        DeepDiveCard(profile = item)
    }

    Spacer(Modifier.height(24.dp))
    SectionLabel(label = "Handle", trailing = "TELEGRAM")
    Spacer(Modifier.height(10.dp))

    PanelCard {
        FieldLabel("Username")
        Spacer(Modifier.height(6.dp))
        VectorTextField(
            value = handle,
            onValueChange = { handle = it; handleResult = null; stealerUser = null },
            placeholder = "@username"
        )
        Spacer(Modifier.height(12.dp))
        GoldButton(
            label = "Check username",
            enabled = handle.isNotBlank() && !loading,
            onClick = {
                scope.launch {
                    loading = true
                    error = null
                    runCatching { IdentityIntel.telegramByUsername(handle) }
                        .onSuccess { handleResult = it }
                        .onFailure { error = it.message }
                    runCatching { IdentityIntel.stealerLog(handle.removePrefix("@"), byEmail = false) }
                        .onSuccess { stealerUser = it }
                        .onFailure { error = "Stealer index: ${it.message}" }
                    loading = false
                }
            }
        )
        if (loading) {
            Spacer(Modifier.height(8.dp))
            LoadingLine(label = "READING PUBLIC PROFILE")
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Alert)
        }
        handleResult?.let { result ->
            Spacer(Modifier.height(12.dp))
            DividerLine()
            Spacer(Modifier.height(10.dp))
            PresenceVerdictRow(result)
        }
        stealerUser?.let { report ->
            Spacer(Modifier.height(12.dp))
            DividerLine()
            Spacer(Modifier.height(10.dp))
            if (report.infected) {
                Text(
                    text = "Present in ${report.count} infostealer logs",
                    style = MaterialTheme.typography.titleSmall,
                    color = Alert
                )
                Spacer(Modifier.height(8.dp))
                report.machines.take(3).forEach { machine ->
                    Text(
                        text = listOfNotNull(
                            machine.machine.takeIf { it.isNotBlank() },
                            machine.date.takeIf { it.isNotBlank() }
                        ).joinToString(" · ").ifBlank { "unnamed machine" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedDeep,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            } else {
                Text(
                    text = "Not present in the infostealer index.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Green
                )
            }
        }
    }
}

// ------------------------------------------------------------------- email

@Composable
private fun EmailPanel(store: Store) {
    var email by remember { mutableStateOf("") }
    var reports by remember { mutableStateOf<List<LeakReport>>(emptyList()) }
    var combos by remember { mutableStateOf<Pair<Long, List<CredentialHit>>?>(null) }
    var hibp by remember { mutableStateOf<List<BreachHit>?>(null) }
    var stealer by remember { mutableStateOf<StealerReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    SectionLabel(label = "Address", trailing = "4 INDEXES")
    Spacer(Modifier.height(10.dp))

    PanelCard {
        FieldLabel("Email")
        Spacer(Modifier.height(6.dp))
        VectorTextField(
            value = email,
            onValueChange = { email = it; reports = emptyList(); combos = null; hibp = null; stealer = null },
            placeholder = "name@example.com"
        )
        Spacer(Modifier.height(12.dp))
        GoldButton(
            label = if (loading) "Sweeping" else "Sweep all sources",
            enabled = email.contains("@") && !loading,
            onClick = {
                scope.launch {
                    loading = true
                    error = null
                    reports = emptyList()
                    combos = null
                    hibp = null
                    stealer = null
                    val collected = mutableListOf<LeakReport>()
                    runCatching { IdentityIntel.leakCheck(email) }
                        .onSuccess { collected += it }
                    runCatching { IdentityIntel.xposedOrNot(email) }
                        .onSuccess { collected += it }
                    reports = collected
                    runCatching { IdentityIntel.combLookup(email) }
                        .onSuccess { combos = it }
                    runCatching { IdentityIntel.stealerLog(email, byEmail = true) }
                        .onSuccess { stealer = it }
                    if (store.key("hibp").isNotBlank()) {
                        runCatching { IdentityIntel.breachesFor(email, store.key("hibp")) }
                            .onSuccess { hibp = it }
                    }
                    if (collected.isEmpty() && combos == null) {
                        error = "Every keyless source failed to answer."
                    }
                    loading = false
                }
            }
        )
        if (loading) {
            Spacer(Modifier.height(8.dp))
            LoadingLine(label = "MATCHING THREE INDEXES")
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Alert)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "LeakCheck, XposedOrNot, and COMBO need no key. HIBP joins only when its key is set " +
                "in Settings.",
            style = MaterialTheme.typography.labelSmall,
            color = MutedDeep
        )
    }

    reports.forEach { report ->
        Spacer(Modifier.height(12.dp))
        LeakReportCard(report)
    }

    stealer?.let { report -> StealerCard(report) }
    combos?.let { (total, hits) -> ComboCard(total, hits) }
    hibp?.let { list -> HibpCard(list) }
}

// ------------------------------------------------------------------- cards

/** Shared leak body: count, exposed field tags, dated sources. */
@Composable
private fun LeakBlock(report: LeakReport, subject: String) {
    Spacer(Modifier.height(12.dp))
    DividerLine()
    Spacer(Modifier.height(10.dp))
    if (!report.exposed) {
        Text(
            text = "$subject does not appear in the ${report.provider} index.",
            style = MaterialTheme.typography.bodySmall,
            color = Green
        )
        return
    }
    Text(
        text = "${report.found} records · ${report.sources.size} sources",
        style = MaterialTheme.typography.titleSmall,
        color = Bone
    )
    if (report.fields.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            report.fields.take(8).forEach { Tag(text = it, color = BoneDim) }
        }
    }
    if (report.sources.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        report.sources.take(12).forEach { source ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = Bone,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = source.date.ifBlank { "—" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedDeep
                )
            }
        }
    }
}

@Composable
private fun LeakReportCard(report: LeakReport) {
    PanelCard(
        header = report.provider,
        trailing = if (report.exposed) "${report.found} RECORDS" else "CLEAR"
    ) {
        Text(
            text = report.identifier,
            style = MaterialTheme.typography.bodySmall,
            color = BoneDim
        )
        LeakBlock(report, "This identifier")
    }
}

@Composable
private fun StealerCard(report: StealerReport) {
    Spacer(Modifier.height(12.dp))
    PanelCard(
        header = "Stealer log",
        trailing = if (report.infected) "${report.count} MACHINES" else "CLEAR"
    ) {
        if (!report.infected) {
            EmptyState(
                "Nothing found",
                report.note ?: "This address does not appear in the Cavalier infostealer index."
            )
            return@PanelCard
        }
        Text(
            text = "This address was stored on a machine hit by an infostealer. The credentials below " +
                "were lifted from that machine, not from a database breach.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
        report.machines.take(5).forEachIndexed { index, machine ->
            Spacer(Modifier.height(12.dp))
            if (index > 0) DividerLine()
            Spacer(Modifier.height(10.dp))
            Text(
                text = machine.machine.ifBlank { "unnamed machine" },
                style = MaterialTheme.typography.titleSmall,
                color = Bone
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = listOfNotNull(
                    machine.malware.takeIf { it.isNotBlank() },
                    machine.date.takeIf { it.isNotBlank() },
                    machine.antivirus.takeIf { it.isNotBlank() }?.let { "AV: $it" }
                ).joinToString(" · ").ifBlank { "no detail available" },
                style = MaterialTheme.typography.labelSmall,
                color = MutedDeep
            )
        }
        if (report.credentials.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            DividerLine()
            Spacer(Modifier.height(12.dp))
            FieldLabel("Credentials")
            Spacer(Modifier.height(8.dp))
            report.credentials.take(12).forEach { hit ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = hit.account.take(48),
                        style = MaterialTheme.typography.labelSmall,
                        color = BoneDim,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = hit.secret.take(24),
                        style = MaterialTheme.typography.labelSmall,
                        color = Alert
                    )
                }
            }
        }
    }
}

@Composable
private fun ComboCard(total: Long, hits: List<CredentialHit>) {
    Spacer(Modifier.height(12.dp))
    PanelCard(
        header = "COMBO corpus",
        trailing = if (total == 0L) "CLEAR" else "$total ROWS"
    ) {
        if (hits.isEmpty()) {
            EmptyState("Nothing found", "This address does not appear in the public COMBO corpus.")
            return@PanelCard
        }
        Text(
            text = "Plaintext credentials circulating in the public corpus. Change the password on every " +
                "account that uses this address.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
        Spacer(Modifier.height(12.dp))
        DividerLine()
        hits.take(15).forEach { hit ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = hit.secret,
                    style = MaterialTheme.typography.bodySmall,
                    color = Bone,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "PLAINTEXT",
                    style = MaterialTheme.typography.labelSmall,
                    color = Alert
                )
            }
            DividerLine()
        }
    }
}

@Composable
private fun HibpCard(list: List<BreachHit>) {
    Spacer(Modifier.height(12.dp))
    PanelCard(
        header = "Have I Been Pwned",
        trailing = if (list.isEmpty()) "CLEAR" else "${list.size} INCIDENTS"
    ) {
        if (list.isEmpty()) {
            EmptyState("Nothing found", "This address does not appear in the HIBP index.")
            return@PanelCard
        }
        list.forEachIndexed { index, hit ->
            if (index > 0) {
                Spacer(Modifier.height(10.dp))
                DividerLine()
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(hit.title, style = MaterialTheme.typography.titleMedium, color = Bone)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "${hit.domain.ifBlank { "no domain" }} · ${hit.breachDate}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedDeep
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${hit.pwnCount}",
                        style = MaterialTheme.typography.titleSmall,
                        color = BoneDim
                    )
                    Text(
                        text = "ACCOUNTS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedDeep
                    )
                }
            }
            if (hit.dataClasses.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    hit.dataClasses.take(5).forEach { Tag(text = it, color = Muted) }
                }
            }
        }
    }
}

// ----------------------------------------------------------------- helpers

private fun copy(context: Context, label: String, value: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun open(context: Context, url: String): Boolean =
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.isSuccess

/**
 * Runs the paid provider modules of phone-x.net against one number and renders
 * whatever answers. Modules fire in parallel and merge per module, so a single
 * 429 costs one card rather than the whole dossier.
 */
@Composable
private fun DeepDiveCard(profile: PhoneProfile) {
    var results by remember { mutableStateOf<Map<String, JSONObject?>>(emptyMap()) }
    var failures by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    PanelCard {
        Text(
            text = "Eight paid phone-x.net modules through your own account pool: HLR, WhatsApp, " +
                "Telegram, GetContact, e-wallet, breach, HUMINT, public evidence. Empty accounts are " +
                "parked and swapped automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
        if (!PhoneX.configured) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "No account in the pool. Add one in Settings.",
                style = MaterialTheme.typography.labelSmall,
                color = Alert
            )
        }
        balance?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "ACCOUNT BALANCE · $it TOKENS",
                style = MaterialTheme.typography.labelSmall,
                color = MutedDeep
            )
        }
        Spacer(Modifier.height(12.dp))
        GoldButton(
            label = if (busy) "Running" else "Run deep dive",
            enabled = PhoneX.configured && !busy,
            onClick = {
                scope.launch {
                    busy = true
                    note = null
                    results = emptyMap()
                    failures = emptyMap()
                    balance = runCatching { PhoneX.balance() }.getOrNull()
                    // Upstream validates /^\+\d{7,15}$/, so E.164 with the plus.
                    val number = profile.e164
                    val collected = mutableMapOf<String, JSONObject?>()
                    val errors = mutableMapOf<String, String>()
                    coroutineScope {
                        val jobs = PhoneX.MODULES.map { spec ->
                            spec.id to async {
                                runCatching { PhoneX.probe(spec.id, number) }
                            }
                        }
                        jobs.forEach { (id, deferred) ->
                            val outcome = deferred.await()
                            outcome.onSuccess { collected[id] = it }
                            outcome.onFailure { errors[id] = it.message ?: "failed" }
                        }
                    }
                    results = collected
                    failures = errors
                    if (collected.isEmpty()) {
                        note = errors.values.firstOrNull() ?: "No module answered."
                    }
                    busy = false
                }
            }
        )
        if (busy) {
            Spacer(Modifier.height(8.dp))
            LoadingLine(label = "SWEEPING SEVEN MODULES")
        }
        note?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Alert)
        }
    }

    PhoneX.MODULES.forEach { spec ->
        val body = results[spec.id]
        val failure = failures[spec.id]
        if (body == null && failure == null) return@forEach
        Spacer(Modifier.height(10.dp))
        val active = PhoneX.isActive(spec.id, body)
        PanelCard(
            header = spec.label,
            trailing = when {
                failure == null && active -> "PRESENT"
                failure == null -> "EMPTY"
                failure.contains("429") -> "LIMITED"
                else -> "FAILED"
            }
        ) {
            if (failure != null) {
                Text(failure, style = MaterialTheme.typography.bodySmall, color = Alert)
                return@PanelCard
            }
            val payload = body!!
            when (spec.id) {
                PhoneX.MODULE_HLR -> {
                    val hlr = payload.optJSONObject("hlr") ?: payload
                    DataRow("Carrier", hlr.optString("carrier").ifBlank { "—" }, Bone)
                    DataRow("Country", hlr.optString("country").ifBlank { "—" })
                    DataRow("Status", hlr.optString("status").ifBlank { "—" })
                    hlr.optString("mcc").takeIf { it.isNotBlank() }?.let {
                        DataRow("MCC / MNC", "$it / ${hlr.optString("mnc")}")
                    }
                }
                PhoneX.MODULE_WHATSAPP -> {
                    val wa = payload.optJSONObject("wa") ?: payload
                    val connected = wa.optBoolean("connected")
                    DataRow("Registered", if (connected) "YES" else "NO", verdictColour(!connected))
                    wa.optString("waId").takeIf { it.isNotBlank() }?.let { DataRow("WA ID", it) }
                }
                PhoneX.MODULE_TELEGRAM -> {
                    val tg = payload.optJSONObject("tg") ?: payload
                    val registered = tg.optBoolean("registered")
                    DataRow("Registered", if (registered) "YES" else "NO", verdictColour(!registered))
                    tg.optString("username").takeIf { it.isNotBlank() }?.let { DataRow("Username", "@$it", Bone) }
                    tg.optString("name").takeIf { it.isNotBlank() }?.let { DataRow("Name", it) }
                }
                PhoneX.MODULE_GETCONTACT -> {
                    val gc = payload.optJSONObject("gc") ?: payload
                    DataRow("Name", gc.optString("name").ifBlank { "—" }, Bone)
                    gc.optString("carrier").takeIf { it.isNotBlank() }?.let { DataRow("Carrier", it) }
                }
                PhoneX.MODULE_EWALLET -> {
                    val wallets = PhoneX.walletNames(payload)
                    if (wallets.isEmpty()) {
                        Text(
                            "No e-wallet detected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            wallets.take(6).forEach { Tag(text = it, color = Green) }
                        }
                    }
                }
                PhoneX.MODULE_BREACH -> {
                    val breach = payload.optJSONObject("breach") ?: payload
                    val sources = breach.optJSONArray("sourcesDetail")
                    val found = breach.optBoolean("found")
                    DataRow("In index", if (found) "YES" else "NO", verdictColour(found))
                    if (sources != null && sources.length() > 0) {
                        Spacer(Modifier.height(10.dp))
                        DividerLine()
                        Spacer(Modifier.height(10.dp))
                        for (i in 0 until minOf(sources.length(), 8)) {
                            val item = sources.optJSONObject(i) ?: continue
                            if (!item.optBoolean("found")) continue
                            Text(
                                text = item.optString("name").ifBlank { "source" },
                                style = MaterialTheme.typography.bodySmall,
                                color = BoneDim,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
                PhoneX.MODULE_HUMINT -> {
                    val humint = payload.optJSONObject("humint") ?: payload
                    val records = humint.optJSONArray("records")
                    DataRow("Records", "${records?.length() ?: 0}", Bone)
                    if (records != null) {
                        for (i in 0 until minOf(records.length(), 3)) {
                            val item = records.optJSONObject(i) ?: continue
                            Spacer(Modifier.height(10.dp))
                            DividerLine()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = item.optString("raw").take(280),
                                style = MaterialTheme.typography.bodySmall,
                                color = BoneDim
                            )
                        }
                    }
                }
                PhoneX.MODULE_EVIDENCE -> {
                    val records = payload.optJSONArray("records")
                    DataRow("Traces", "${records?.length() ?: 0}", Bone)
                }
            }
        }
    }
}