package com.vector.console.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoZone(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val onEnter: Boolean = true,
    val onExit: Boolean = true,
    val onDwell: Boolean = false,
    val dwellSeconds: Int = 300,
    val active: Boolean = true,
    /** Covert: centre is jittered and radius widened so the fence is not a pin. */
    val covert: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun transitionMask(): Int {
        var mask = 0
        if (onEnter) mask = mask or Geofence.GEOFENCE_TRANSITION_ENTER
        if (onExit) mask = mask or Geofence.GEOFENCE_TRANSITION_EXIT
        if (onDwell) mask = mask or Geofence.GEOFENCE_TRANSITION_DWELL
        return if (mask == 0) Geofence.GEOFENCE_TRANSITION_ENTER else mask
    }
}

data class GeoEvent(
    val zoneId: String,
    val label: String,
    val transition: String,
    val at: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null
)

class GeoStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("vector_geowatch", Context.MODE_PRIVATE)

    var zones by mutableStateOf(loadZones())
        private set

    var events by mutableStateOf(loadEvents())
        private set

    /** Global covert switch: applies jitter to every zone, not just per-zone flags. */
    var covertMaster by mutableStateOf(prefs.getBoolean("covert_master", false))
        private set

    fun toggleCovertMaster() {
        covertMaster = !covertMaster
        prefs.edit().putBoolean("covert_master", covertMaster).apply()
    }

    fun saveZone(zone: GeoZone) {
        val next = zones.toMutableList()
        val index = next.indexOfFirst { it.id == zone.id }
        if (index >= 0) next[index] = zone else next.add(0, zone)
        zones = next
        persistZones()
    }

    fun deleteZone(id: String) {
        zones = zones.filterNot { it.id == id }
        persistZones()
        persistEvents()
    }

    fun toggleZone(id: String) {
        zones = zones.map { if (it.id == id) it.copy(active = !it.active) else it }
        persistZones()
    }

    fun recordEvent(event: GeoEvent) {
        events = (listOf(event) + events).take(500)
        persistEvents()
    }

    fun clearEvents() {
        events = emptyList()
        persistEvents()
    }

    fun labelFor(id: String): String = zones.firstOrNull { it.id == id }?.label ?: "Zone ${id.take(6)}"

    private fun loadZones(): List<GeoZone> {
        val raw = prefs.getString("zones", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        GeoZone(
                            id = item.optString("id", UUID.randomUUID().toString()),
                            label = item.optString("label", "Unnamed"),
                            latitude = item.optDouble("lat"),
                            longitude = item.optDouble("lon"),
                            radiusMeters = item.optDouble("radius", 200.0).toFloat(),
                            onEnter = item.optBoolean("onEnter", true),
                            onExit = item.optBoolean("onExit", true),
                            onDwell = item.optBoolean("onDwell", false),
                            dwellSeconds = item.optInt("dwell", 300),
                            active = item.optBoolean("active", true),
                            covert = item.optBoolean("covert", false),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistZones() {
        val array = JSONArray()
        zones.forEach { zone ->
            array.put(
                JSONObject().apply {
                    put("id", zone.id)
                    put("label", zone.label)
                    put("lat", zone.latitude)
                    put("lon", zone.longitude)
                    put("radius", zone.radiusMeters.toDouble())
                    put("onEnter", zone.onEnter)
                    put("onExit", zone.onExit)
                    put("onDwell", zone.onDwell)
                    put("dwell", zone.dwellSeconds)
                    put("active", zone.active)
                    put("covert", zone.covert)
                    put("createdAt", zone.createdAt)
                }
            )
        }
        prefs.edit().putString("zones", array.toString()).apply()
    }

    private fun loadEvents(): List<GeoEvent> {
        val raw = prefs.getString("events", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        GeoEvent(
                            zoneId = item.optString("zoneId"),
                            label = item.optString("label"),
                            transition = item.optString("transition"),
                            at = item.optLong("at"),
                            latitude = item.optDouble("lat").takeUnless { it.isNaN() },
                            longitude = item.optDouble("lon").takeUnless { it.isNaN() },
                            accuracy = item.optDouble("accuracy", Double.NaN).toFloat()
                                .takeUnless { it.isNaN() }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistEvents() {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject().apply {
                    put("zoneId", event.zoneId)
                    put("label", event.label)
                    put("transition", event.transition)
                    put("at", event.at)
                    put("lat", event.latitude ?: Double.NaN)
                    put("lon", event.longitude ?: Double.NaN)
                    put("accuracy", event.accuracy?.toDouble() ?: Double.NaN)
                }
            )
        }
        prefs.edit().putString("events", array.toString()).apply()
    }
}

/**
 * Silent geofence registration. No notification is posted on transition — the
 * receiver writes straight into the local event log.
 */
class GeoController(context: Context) {

    private val context = context.applicationContext
    private val client: GeofencingClient = LocationServices.getGeofencingClient(this.context)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBackgroundPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, GeoBroadcastReceiver::class.java)
            .setAction(GeoBroadcastReceiver.ACTION_GEOFENCE)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    @SuppressLint("MissingPermission") // hasPermission() is checked above before any geofencing call.
    suspend fun sync(zones: List<GeoZone>, covert: Boolean = false): Int {
        if (!hasPermission()) throw IllegalStateException("Fine location permission is not granted.")
        val active = zones.filter { it.active }
        removeAll()
        if (active.isEmpty()) return 0

        val geofences = active.map { zone ->
            val masked = covert || zone.covert
            val (lat, lon) = if (masked) {
                GeoMath.jitter(zone.latitude, zone.longitude, zone.id)
            } else {
                zone.latitude to zone.longitude
            }
            // Covert fences run wider so small GPS drift cannot reveal the true centre.
            val radius = if (masked) {
                (zone.radiusMeters * 1.3f).coerceIn(80f, 5000f)
            } else {
                zone.radiusMeters
            }
            val builder = Geofence.Builder()
                .setRequestId(zone.id)
                .setCircularRegion(lat, lon, radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(zone.transitionMask())
                // Covert trades latency for fewer wakeups, which leaves a smaller trail.
                .setNotificationResponsiveness(if (masked) 300_000 else 60_000)
            if (zone.onDwell) builder.setLoiteringDelay(zone.dwellSeconds * 1000)
            builder.build()
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()

        suspendCancellableCoroutine<Unit> { cont ->
            client.addGeofences(request, pendingIntent())
                .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                .addOnFailureListener { if (cont.isActive) cont.cancel(it) }
        }
        return geofences.size
    }

    suspend fun removeAll() {
        val intent = pendingIntent()
        suspendCancellableCoroutine<Unit> { cont ->
            client.removeGeofences(intent)
                .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                .addOnFailureListener { if (cont.isActive) cont.resume(Unit) }
        }
    }
}

class GeoBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_GEOFENCE = "com.vector.console.GEOFENCE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val store = GeoStore(context)
        val location = event.triggeringLocation
        val transition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
            else -> "UNKNOWN"
        }
        event.triggeringGeofences?.forEach { fence ->
            store.recordEvent(
                GeoEvent(
                    zoneId = fence.requestId,
                    label = store.labelFor(fence.requestId),
                    transition = transition,
                    at = System.currentTimeMillis(),
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    accuracy = location?.accuracy
                )
            )
        }
    }
}

class GeoBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val store = GeoStore(context)
        val controller = GeoController(context)
        if (!controller.hasPermission()) return
        val pending = goAsync()
        Thread {
            runCatching {
                kotlinx.coroutines.runBlocking { controller.sync(store.zones, store.covertMaster) }
            }
            pending.finish()
        }.start()
    }
}

object GeoMath {

    /** Great-circle distance in metres. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
    }

    fun fromLocation(location: Location): Pair<Double, Double> = location.latitude to location.longitude

    /**
     * Deterministic ±60 m offset derived from a stable key. Same zone always
     * lands on the same masked centre, so behaviour is reproducible while the
     * registered point no longer equals the real coordinate.
     */
    fun jitter(lat: Double, lon: Double, key: String, maxMeters: Double = 60.0): Pair<Double, Double> {
        var hash = 1125899906842597L
        for (ch in key) hash = 31 * hash + ch.code
        val bearing = ((hash and 0xFFFF).toDouble() / 65535.0) * 2 * Math.PI
        val distance = ((hash ushr 16 and 0xFFFF).toDouble() / 65535.0) * maxMeters
        val dLat = (distance * cos(bearing)) / 111_320.0
        val dLon = (distance * sin(bearing)) / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.1))
        return (lat + dLat) to (lon + dLon)
    }

    fun formatDistance(meters: Double): String = when {
        meters < 1_000 -> String.format(java.util.Locale.US, "%.0f m", meters)
        meters < 100_000 -> String.format(java.util.Locale.US, "%.1f km", meters / 1_000)
        else -> String.format(java.util.Locale.US, "%.0f km", meters / 1_000)
    }
}
