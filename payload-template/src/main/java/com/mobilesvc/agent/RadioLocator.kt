package com.mobilesvc.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Position without satellite lock.
 *
 * Two paths, chosen by config:
 *  - RADIO — Wi-Fi BSSIDs plus cell sectors resolved through beaconDB. Works indoors, costs a
 *    few hundred bytes, ~30 m in a city.
 *  - GNSS — the platform provider. 5 m, but the first fix can take 30 s indoors and it will not
 *    resolve at all inside a building.
 *
 * Both need `ACCESS_FINE_LOCATION`. The radio path additionally needs the system location
 * toggle on: with it off, `scanResults` returns an empty list and `allCellInfo` returns null,
 * silently. That is why [capability] exists — a silent empty list looks identical to "no APs
 * nearby", and reading it as a position would be a lie.
 */
@Suppress("DEPRECATION")
class RadioLocator(private val context: Context) {

    data class Fix(
        val lat: Double,
        val lon: Double,
        val accuracyM: Double,
        val method: String,
        val at: Long = System.currentTimeMillis()
    )

    /**
     * Why the last [radioFix] attempt returned null.
     *
     * A null fix has several very different causes — no radio data, a rejected request, or a
     * resolver that only knows the caller's IP address — and the report is the only place an
     * operator can see them. Without this the collector says "NO FIX" and stops there, which is
     * indistinguishable from a broken build.
     */
    var lastReason: String = ""
        private set

    fun hasPermission(): Boolean = context.checkSelfPermission(
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    /** Human-readable availability, so a failed fix is diagnosable rather than mysterious. */
    fun capability(): String {
        if (!hasPermission()) return "NO PERMISSION"
        val wifi = wifiScan().size
        val cells = cellScan().size
        return when {
            wifi == 0 && cells == 0 -> "NO SIGNALS (location toggle off?)"
            cells == 0 -> "$wifi AP"
            wifi == 0 -> "$cells CELL"
            else -> "$wifi AP / $cells CELL"
        }
    }

    @SuppressLint("MissingPermission")
    fun wifiScan(): List<Pair<String, Int>> {
        if (!hasPermission()) return emptyList()
        val manager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()
        return runCatching {
            manager.scanResults
                .mapNotNull { result ->
                    val bssid = result.BSSID ?: return@mapNotNull null
                    if (bssid == "02:00:00:00:00:00") return@mapNotNull null
                    bssid.lowercase() to result.level
                }
                .distinctBy { it.first }
                .sortedByDescending { it.second }
                .take(20)
        }.getOrDefault(emptyList())
    }

    @SuppressLint("MissingPermission")
    fun cellScan(): List<JSONObject> {
        if (!hasPermission()) return emptyList()
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return emptyList()
        val cells = runCatching { manager.allCellInfo }.getOrNull() ?: return emptyList()

        return cells.mapNotNull { info ->
            runCatching {
                when (info) {
                    is CellInfoLte -> {
                        val id = info.cellIdentity
                        val mcc = mcc(id.mccString, id.mcc) ?: return@mapNotNull null
                        val mnc = mnc(id.mncString, id.mnc) ?: return@mapNotNull null
                        tower("lte", mcc, mnc, id.tac, id.ci.toLong(), info.cellSignalStrength.dbm)
                    }
                    is CellInfoGsm -> {
                        val id = info.cellIdentity
                        val mcc = mcc(id.mccString, id.mcc) ?: return@mapNotNull null
                        val mnc = mnc(id.mncString, id.mnc) ?: return@mapNotNull null
                        tower("gsm", mcc, mnc, id.lac, id.cid.toLong(), info.cellSignalStrength.dbm)
                    }
                    is CellInfoWcdma -> {
                        val id = info.cellIdentity
                        val mcc = mcc(id.mccString, id.mcc) ?: return@mapNotNull null
                        val mnc = mnc(id.mncString, id.mnc) ?: return@mapNotNull null
                        tower("wcdma", mcc, mnc, id.lac, id.cid.toLong(), info.cellSignalStrength.dbm)
                    }
                    else -> null // NR identity needs API 29 types; skip rather than guess
                }
            }.getOrNull()
        }
    }

    private fun tower(
        radio: String, mcc: Int, mnc: Int, area: Int, cell: Long, dbm: Int
    ): JSONObject = JSONObject().apply {
        // radioType belongs on the tower object; on the envelope beaconDB returns 400.
        put("radioType", radio)
        put("mobileCountryCode", mcc)
        put("mobileNetworkCode", mnc)
        put("locationAreaCode", area)
        put("cellId", cell)
        put("signalStrength", dbm)
    }

    private fun mcc(str: String?, raw: Int): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) str?.toIntOrNull()
        else raw.takeIf { it != Int.MAX_VALUE }

    private fun mnc(str: String?, raw: Int): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) str?.toIntOrNull()
        else raw.takeIf { it != Int.MAX_VALUE }

    /** Resolves the current radio environment to a coordinate. Null means "no fix", never "zero". */
    fun radioFix(): Fix? {
        val wifi = wifiScan()
        val cells = cellScan()
        if (wifi.isEmpty() && cells.isEmpty()) {
            lastReason = "no radio data (location toggle off?)"
            return null
        }

        val payload = JSONObject().apply {
            put("considerIp", false)
            if (wifi.isNotEmpty()) {
                put("wifiAccessPoints", JSONArray().apply {
                    wifi.forEach { (bssid, level) ->
                        put(JSONObject().apply {
                            put("macAddress", bssid)
                            put("signalStrength", level)
                        })
                    }
                })
            }
            if (cells.isNotEmpty()) put("cellTowers", JSONArray(cells))
        }

        val response = Net.request(
            url = "https://api.beacondb.net/v1/geolocate",
            method = "POST",
            headers = mapOf("Accept" to "application/json"),
            body = payload.toString().toByteArray(Charsets.UTF_8),
            contentType = "application/json",
            timeoutMs = 12_000
        )
        if (response.status != 200) {
            lastReason = "beaconDB HTTP ${response.status}"
            return null
        }

        return runCatching {
            val root = JSONObject(response.body)
            val location = root.optJSONObject("location")
            if (location == null) {
                lastReason = "beaconDB returned no location"
                return null
            }
            val lat = location.optDouble("lat", Double.NaN)
            val lon = location.optDouble("lng", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) {
                lastReason = "beaconDB returned non-numeric coordinates"
                return null
            }
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                lastReason = "beaconDB returned out-of-range coordinates"
                return null
            }
            val accuracy = root.optDouble("accuracy", 0.0)
            // 25 km is a city, not a position. Refuse it rather than report a lie — but say which
            // fallback produced it, because "ipf" means the radio data was not recognised and the
            // coordinate is the carrier's, not the device's.
            if (accuracy <= 0.0 || accuracy > 5_000.0) {
                val fallback = root.optString("fallback", "")
                lastReason = "beaconDB accuracy ${accuracy.toInt()} m" +
                    if (fallback.isBlank()) "" else " (fallback=$fallback, not radio)"
                return null
            }
            lastReason = ""
            Fix(lat, lon, accuracy, "radio")
        }.getOrElse { error ->
            lastReason = "beaconDB parse failed: ${error.javaClass.simpleName}"
            null
        }
    }

    /** Last known platform fix. Cheap, but stale — the age is returned so the caller can judge. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Fix? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        return runCatching {
            val location = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: return null
            val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
            if (age > 600_000) return null // older than 10 min is not a position, it is history
            Fix(location.latitude, location.longitude, location.accuracy.toDouble(), "gnss")
        }.getOrNull()
    }

    fun hybridFix(): Fix? = lastKnown() ?: radioFix()
}
