package com.vector.console.data

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.vector.console.MainActivity
import com.vector.console.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the track alive while the app is not on screen.
 *
 * A foreground service is the only mechanism Android honours for a sub-minute
 * network cadence. Doze still defers network after the device has been idle for
 * a while; expect the effective cadence to stretch at night, which is exactly
 * what [TrackStore.gaps] is for.
 */
class TrackService : Service() {

    companion object {
        const val ACTION_START = "com.vector.console.TRACK_START"
        const val ACTION_STOP = "com.vector.console.TRACK_STOP"

        private const val CHANNEL_ID = "oversight_track"
        private const val NOTIFICATION_ID = 4401

        /** 15 s cadence over 30 days is ~172k rows per target. Trim past this. */
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        fun start(context: Context) {
            // minSdk is 26, so startForegroundService is always available.
            context.startForegroundService(
                Intent(context, TrackService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TrackService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private lateinit var store: TrackStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate() {
        super.onCreate()
        store = TrackStore(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(0, 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
        if (loop == null) {
            scope.launch {
                store.prune(System.currentTimeMillis() - RETENTION_MS)
            }
            startLoop()
        }
        return START_STICKY
    }

    private fun startLoop() {
        loop = scope.launch {
            while (isActive) {
                val targets = store.activeTargets()
                var written = 0

                val hexes = targets.filter { it.kind == TrackKind.ICAO24 }.map { it.id }
                if (hexes.isNotEmpty()) {
                    val fixes = runCatching { TrackFeeds.pollAdsb(hexes) }
                        .getOrDefault(emptyList())
                    written += store.append(fixes)
                }

                // Self-tracking runs off the radio fingerprint, not GPS: no satellite
                // lock, works indoors, and a few hundred bytes per fix.
                if (targets.any { it.kind == TrackKind.MOVE }) {
                    val fix = runCatching { DeviceLocation.fix(this@TrackService) }.getOrNull()
                    if (fix != null) {
                        written += store.append(
                            listOf(
                                TrackFix(
                                    targetId = "self",
                                    at = System.currentTimeMillis(),
                                    lat = fix.lat,
                                    lon = fix.lon,
                                    source = "radio:${fix.method}",
                                    accuracyM = fix.accuracyM
                                )
                            )
                        )
                    }
                }

                notify(written, targets.size)
                val cadence = targets.minOfOrNull { it.cadenceSec } ?: 15
                delay(cadence.coerceIn(10, 300) * 1000L)
            }
        }
    }

    private fun notify(written: Int, targets: Int) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(written, targets))
    }

    private fun buildNotification(written: Int, targets: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = if (targets == 0) {
            "No targets armed."
        } else {
            "$targets ARMED · $written NEW · ${clock.format(Date())} UTC"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle("OVERSIGHT · TRACKING")
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Target tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the position record running while OVERSIGHT is in the background."
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        loop?.cancel()
        loop = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
