package com.vector.console.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Where a position actually exists. See `docs/LOCATION-SURFACE.md`. */
enum class Surface {
    /** On the handset. Needs code on it, and usually a permission. */
    ON_DEVICE,
    /** The operator's core network. Needs lawful process or SS7. Not an API. */
    CARRIER,
    /** An account or a crowd-sourced beacon network. Needs credentials or a tag. */
    PLATFORM,
    /** Anyone with a receiver in range. Needs proximity and radio hardware. */
    RF
}

/**
 * One way a position can be obtained.
 *
 * [cadenceSec] is the realistic sustained cadence, not the maximum. [keyless] means no
 * credential of any kind. [needsCooperation] means the target handset must be running
 * something that reports — the difference between tracking and observing.
 */
data class LocationChannel(
    val key: String,
    val label: String,
    val surface: Surface,
    val precisionM: Int,
    val cadenceSec: Int,
    val keyless: Boolean,
    val needsHardware: Boolean,
    val permission: String?,
    val needsCooperation: Boolean,
    val note: String,
    /** False for channels that identify a place or a motion but never a coordinate. */
    val yieldsCoordinate: Boolean = true
) {
    /** Coarse enough that it is a city, not a position. */
    val cityGrade: Boolean get() = precisionM >= 5_000

    /** Sustained sub-minute reporting is what makes a track rather than a sample. */
    val continuous: Boolean get() = cadenceSec in 1..60
}

/**
 * The full surface, ranked by what it costs to obtain.
 *
 * Ordering matters: everything reachable keylessly and permissionlessly is listed before
 * anything that needs a credential, a court order, or a radio truck.
 */
object LocationSurface {

    const val PERM_FINE = Manifest.permission.ACCESS_FINE_LOCATION
    const val PERM_MEDIA = Manifest.permission.READ_MEDIA_IMAGES

    /** API 31 permission; referenced as a literal so minSdk 26 stays clean. */
    const val PERM_BLE = "android.permission.BLUETOOTH_SCAN"

    val CHANNELS: List<LocationChannel> = listOf(
        LocationChannel(
            key = "IP",
            label = "IP address",
            surface = Surface.ON_DEVICE,
            precisionM = 25_000,
            cadenceSec = 30,
            keyless = true,
            needsHardware = false,
            permission = null,
            needsCooperation = true,
            note = "Survives location being off. Defeated by any VPN. City grade."
        ),
        LocationChannel(
            key = "IMU",
            label = "Motion sensors",
            surface = Surface.ON_DEVICE,
            precisionM = 5,
            cadenceSec = 1,
            keyless = true,
            needsHardware = true,
            permission = null,
            needsCooperation = true,
            note = "No permission required. Cannot produce a coordinate alone, but classifies " +
                "movement and reconstructs a path from one anchor fix.",
            yieldsCoordinate = false
        ),
        LocationChannel(
            key = "WIFI",
            label = "Wi-Fi fingerprint",
            surface = Surface.ON_DEVICE,
            precisionM = 30,
            cadenceSec = 15,
            keyless = true,
            needsHardware = true,
            permission = PERM_FINE,
            needsCooperation = true,
            note = "Best indoor channel. Needs the system location toggle, not just the grant."
        ),
        LocationChannel(
            key = "CELL",
            label = "Cell sector",
            surface = Surface.ON_DEVICE,
            precisionM = 800,
            cadenceSec = 30,
            keyless = true,
            needsHardware = true,
            permission = PERM_FINE,
            needsCooperation = true,
            note = "Cheapest fix per byte. radioType belongs on each tower, not the envelope."
        ),
        LocationChannel(
            key = "GNSS",
            label = "Satellite",
            surface = Surface.ON_DEVICE,
            precisionM = 5,
            cadenceSec = 1,
            keyless = true,
            needsHardware = true,
            permission = PERM_FINE,
            needsCooperation = true,
            note = "Only channel that yields velocity and heading for free. Blind indoors."
        ),
        LocationChannel(
            key = "EXIF",
            label = "Photo metadata",
            surface = Surface.ON_DEVICE,
            precisionM = 10,
            cadenceSec = 0,
            keyless = true,
            needsHardware = false,
            permission = PERM_MEDIA,
            needsCooperation = true,
            note = "A timestamped position history the device already recorded. Zero polling."
        ),
        LocationChannel(
            key = "KNOWN_NETS",
            label = "Saved networks",
            surface = Surface.ON_DEVICE,
            precisionM = 0,
            cadenceSec = 0,
            keyless = true,
            needsHardware = true,
            permission = PERM_FINE,
            needsCooperation = true,
            note = "Identifies places, not coordinates. Answers 'at home' without a fix.",
            yieldsCoordinate = false
        ),
        LocationChannel(
            key = "BLE",
            label = "BLE beacon",
            surface = Surface.ON_DEVICE,
            precisionM = 3,
            cadenceSec = 5,
            keyless = true,
            needsHardware = true,
            permission = PERM_BLE,
            needsCooperation = true,
            note = "Indoor only. Eddystone-EID rotates and is unreadable without the key."
        ),
        LocationChannel(
            key = "RTT",
            label = "Wi-Fi RTT ranging",
            surface = Surface.ON_DEVICE,
            precisionM = 2,
            cadenceSec = 1,
            keyless = true,
            needsHardware = true,
            permission = PERM_FINE,
            needsCooperation = true,
            note = "Needs FTM-capable APs and silicon. Accurate where it exists, rare."
        ),
        LocationChannel(
            key = "FINDMY",
            label = "Crowd-sourced tag network",
            surface = Surface.PLATFORM,
            precisionM = 50,
            cadenceSec = 300,
            keyless = false,
            needsHardware = true,
            permission = null,
            needsCooperation = false,
            note = "Strangers' handsets do the reporting. The only way to track a thing that " +
                "runs no code of yours."
        ),
        LocationChannel(
            key = "ACCOUNT",
            label = "Account location history",
            surface = Surface.PLATFORM,
            precisionM = 30,
            cadenceSec = 600,
            keyless = false,
            needsHardware = false,
            permission = null,
            needsCooperation = false,
            note = "Google Timeline / Apple Significant Locations. Needs the account."
        ),
        LocationChannel(
            key = "MDM",
            label = "Management agent",
            surface = Surface.PLATFORM,
            precisionM = 10,
            cadenceSec = 60,
            keyless = false,
            needsHardware = false,
            permission = null,
            needsCooperation = true,
            note = "Enrolment, and the user accepting it. Highest-quality persistent path " +
                "that is not carrier-side."
        ),
        LocationChannel(
            key = "ADS_B",
            label = "ADS-B receiver network",
            surface = Surface.RF,
            precisionM = 50,
            cadenceSec = 15,
            keyless = true,
            needsHardware = false,
            permission = null,
            needsCooperation = true,
            note = "Cooperative broadcast. Aircraft, not handsets. Wired into TrackFeeds."
        ),
        LocationChannel(
            key = "AIS",
            label = "AIS receiver network",
            surface = Surface.RF,
            precisionM = 100,
            cadenceSec = 60,
            keyless = false,
            needsHardware = false,
            permission = null,
            needsCooperation = true,
            note = "Ships. AISStream needs a key; coverage is coastal."
        ),
        LocationChannel(
            key = "HLR",
            label = "HLR / HSS lookup",
            surface = Surface.CARRIER,
            precisionM = 5_000,
            cadenceSec = 0,
            keyless = false,
            needsHardware = false,
            permission = null,
            needsCooperation = false,
            note = "Which MSC the subscriber is attached to. One shot, not a track.",
            yieldsCoordinate = false
        ),
        LocationChannel(
            key = "ATI",
            label = "Any Time Interrogation",
            surface = Surface.CARRIER,
            precisionM = 300,
            cadenceSec = 60,
            keyless = false,
            needsHardware = false,
            permission = null,
            needsCooperation = false,
            note = "Forces a paging cycle, returns cell id and timing advance. SS7 access."
        ),
        LocationChannel(
            key = "IMSI_CATCHER",
            label = "IMSI catcher",
            surface = Surface.CARRIER,
            precisionM = 500,
            cadenceSec = 5,
            keyless = false,
            needsHardware = true,
            permission = null,
            needsCooperation = false,
            note = "Radio hardware, not software. Sees every handset in range."
        )
    )

    fun byKey(key: String): LocationChannel? = CHANNELS.firstOrNull { it.key == key }

    /**
     * Channels obtainable with no credential at all.
     *
     * Still requires hardware and permission where the channel says so — "keyless" is
     * about accounts, not about the handset cooperating.
     */
    fun keyless(): List<LocationChannel> = CHANNELS.filter { it.keyless }

    /**
     * The honest filter. Channels that report on a target without that target running
     * anything of yours: carrier-side observation and platform beacon networks only.
     */
    fun withoutCooperation(): List<LocationChannel> = CHANNELS.filter { !it.needsCooperation }

    /**
     * Channels a track can be built from: a real coordinate, sub-minute cadence, not a city.
     *
     * Deliberately ignores [LocationChannel.needsCooperation] — tracking your own handset is
     * this app cooperating with itself. Use [withoutCooperation] for the other question.
     */
    fun trackable(): List<LocationChannel> = CHANNELS.filter {
        it.continuous && it.yieldsCoordinate && !it.cityGrade && it.surface != Surface.CARRIER
    }

    /** Everything reachable from an ordinary app with the listed permissions granted. */
    fun reachableFromApp(grantedPermissions: Set<String>): List<LocationChannel> = CHANNELS.filter {
        it.surface == Surface.ON_DEVICE &&
            (it.permission == null || it.permission in grantedPermissions)
    }

    /** Sorted best-precision first, for a ledger. City-grade channels sink to the bottom. */
    fun ranked(): List<LocationChannel> = CHANNELS.sortedWith(
        compareBy({ it.cityGrade }, { if (it.precisionM == 0) Int.MAX_VALUE else it.precisionM })
    )
}

/** Which channels this specific handset can actually run right now. */
object SurfaceAudit {

    data class Row(val channel: LocationChannel, val live: Boolean, val reason: String)

    @SuppressLint("InlinedApi") // hasSystemFeature takes a plain string; no API floor.
    fun audit(context: Context): List<Row> = LocationSurface.ranked().map { channel ->
        when {
            channel.surface != Surface.ON_DEVICE ->
                Row(channel, false, "not reachable from this app")

            channel.permission != null && !granted(context, channel.permission) ->
                Row(channel, false, "permission not granted")

            channel.needsHardware && !hasHardware(context, channel.key) ->
                Row(channel, false, "hardware absent")

            channel.key == "WIFI" && !locationToggleOn(context) ->
                Row(channel, false, "system location off")

            channel.key == "KNOWN_NETS" ->
                Row(channel, true, "identifies places only")

            else -> Row(channel, true, "ready")
        }
    }

    fun liveKeys(context: Context): List<String> = audit(context).filter { it.live }.map {
        it.channel.key
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("InlinedApi")
    private fun hasHardware(context: Context, key: String): Boolean {
        val pm = context.packageManager
        return when (key) {
            "WIFI", "KNOWN_NETS" -> pm.hasSystemFeature(PackageManager.FEATURE_WIFI)
            "RTT" -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
            "BLE" -> pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
            "GNSS" -> pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
            "CELL" -> pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
            "IMU" -> context.getSystemService(android.hardware.SensorManager::class.java)
                ?.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR) != null
            else -> true
        }
    }

    /** The scan list returns nothing with the master location toggle off. */
    @Suppress("DEPRECATION") // isProviderEnabled is the only pre-28 way to read the toggle.
    private fun locationToggleOn(context: Context): Boolean {
        val manager = context.getSystemService(android.location.LocationManager::class.java)
            ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.isLocationEnabled
            } else {
                manager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            }
        }.getOrDefault(false)
    }
}
