package com.vector.console.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Reads the radio fingerprint of the device this app is installed on.
 *
 * Everything here needs `ACCESS_FINE_LOCATION` at runtime — Android treats a Wi-Fi
 * scan list and a cell list as location data, because they are. `getScanResults()`
 * additionally returns nothing when the system location toggle is off, even with the
 * permission granted.
 *
 * This is the self-tracking path. It cannot see another person's handset.
 */
@Suppress("DEPRECATION") // mcc/mnc ints are the only fields available below API 28.
object DeviceLocation {

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // hasLocationPermission() is checked below before the platform call is reached.
    @SuppressLint("MissingPermission")
    fun collectWifi(context: Context): List<WifiObservation> {
        if (!hasLocationPermission(context)) return emptyList()
        val manager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
        return runCatching {
            manager.scanResults
                .filter { it.BSSID != null && it.BSSID != "02:00:00:00:00:00" }
                .map { WifiObservation(bssid = it.BSSID, signalDbm = it.level, ssid = it.SSID.orEmpty()) }
        }.getOrDefault(emptyList())
    }

    @SuppressLint("MissingPermission")
    fun collectCells(context: Context): List<CellObservation> {
        if (!hasLocationPermission(context)) return emptyList()
        val manager = context.applicationContext
            .getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return emptyList()
        val cells = runCatching { manager.allCellInfo }.getOrNull() ?: return emptyList()

        return cells.mapNotNull { info ->
            runCatching { parseCell(info) }.getOrNull()
        }.filter { it.mcc in 1..999 && it.cellId > 0 }
    }

    /**
     * `CellInfo.getCellSignalStrength()` is API 30; each subclass carries its own
     * since API 17, so the cast decides both the signal and the identity layout.
     */
    private fun parseCell(info: CellInfo): CellObservation? = when (info) {
        is CellInfoLte -> {
            val id = info.cellIdentity
            // Branch the call site, not the arguments: mccString is API 28 and would
            // be evaluated unconditionally if passed in.
            val (mcc, mnc) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                modernMccMnc(id.mccString, id.mncString)
            } else {
                legacyMccMnc(id.mcc, id.mnc)
            } ?: return null
            CellObservation("lte", mcc, mnc, id.tac, id.ci.toLong(), info.cellSignalStrength.dbm)
        }
        is CellInfoGsm -> {
            val id = info.cellIdentity
            val (mcc, mnc) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                modernMccMnc(id.mccString, id.mncString)
            } else {
                legacyMccMnc(id.mcc, id.mnc)
            } ?: return null
            CellObservation("gsm", mcc, mnc, id.lac, id.cid.toLong(), info.cellSignalStrength.dbm)
        }
        is CellInfoWcdma -> {
            val id = info.cellIdentity
            val (mcc, mnc) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                modernMccMnc(id.mccString, id.mncString)
            } else {
                legacyMccMnc(id.mcc, id.mnc)
            } ?: return null
            CellObservation("wcdma", mcc, mnc, id.lac, id.cid.toLong(), info.cellSignalStrength.dbm)
        }
        // NR identity types are API 29+; skip rather than guess a sector.
        else -> null
    }

    private fun modernMccMnc(mccString: String?, mncString: String?): Pair<Int, Int>? {
        val mcc = mccString?.toIntOrNull() ?: return null
        val mnc = mncString?.toIntOrNull() ?: return null
        return mcc to mnc
    }

    private fun legacyMccMnc(mccInt: Int, mncInt: Int): Pair<Int, Int>? {
        if (mccInt == Int.MAX_VALUE || mncInt == Int.MAX_VALUE) return null
        return mccInt to mncInt
    }

    /**
     * One fix from the current radio environment.
     *
     * Wi-Fi is tried first because it is an order of magnitude more precise indoors,
     * where a phone spends most of its time. Cells cover the gaps between buildings.
     */
    suspend fun fix(context: Context): ResolvedFix? {
        val wifi = collectWifi(context)
        val cells = collectCells(context)
        if (wifi.isEmpty() && cells.isEmpty()) return null
        val resolved = LocationResolver.resolve(wifi = wifi, cells = cells, allowIpFallback = false)
        return resolved.takeIf { LocationResolver.isUsable(it) }
    }

    /** Which signals are actually available right now, for the settings screen. */
    fun capability(context: Context): String {
        if (!hasLocationPermission(context)) return "NO PERMISSION"
        val wifi = collectWifi(context).size
        val cells = collectCells(context).size
        return when {
            wifi == 0 && cells == 0 -> "NO SIGNALS"
            cells == 0 -> "$wifi AP"
            wifi == 0 -> "$cells CELL"
            else -> "$wifi AP · $cells CELL"
        }
    }
}
