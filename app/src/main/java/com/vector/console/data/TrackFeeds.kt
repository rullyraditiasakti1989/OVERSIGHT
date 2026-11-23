package com.vector.console.data

import org.json.JSONArray
import org.json.JSONObject

/** Static identity for an airframe. Resolved once, cached by the caller. */
data class AircraftMeta(
    val hex: String,
    val registration: String,
    val manufacturer: String,
    val typeCode: String,
    val typeName: String,
    val owner: String
) {
    fun line(): String = listOf(registration, owner, typeCode)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

/**
 * Position sources for tracked targets.
 *
 * Three upstreams, all keyless, all verified live on 2026-11-22:
 *  - adsb.lol  — batches up to 40 hexes per request, returns only what is visible
 *  - OpenSky   — single icao24 filter, and the current-flight track path
 *  - hexdb.io  — static airframe enrichment
 *
 * Nothing here stores history. Upstreams hand back a snapshot; [TrackStore] keeps it.
 */
object TrackFeeds {

    private const val ADSB = "https://api.adsb.lol/v2"
    private const val OPENSKY = "https://opensky-network.org/api"
    private const val HEXDB = "https://hexdb.io/api/v1"

    private const val KNOTS_TO_MS = 0.514444
    private const val FEET_TO_M = 0.3048

    /** adsb.lol rejects oversized query strings; 40 verified working. */
    private const val BATCH = 40

    /**
     * One request per 40 targets. Targets outside coverage simply come back absent —
     * that is a gap, and the caller must record it as one rather than as a stop.
     */
    suspend fun pollAdsb(hexes: List<String>): List<TrackFix> {
        val clean = hexes.map { it.trim().lowercase() }.filter { it.length == 6 }.distinct()
        if (clean.isEmpty()) return emptyList()
        val out = mutableListOf<TrackFix>()
        clean.chunked(BATCH).forEach { chunk ->
            val response = Net.getOrNull("$ADSB/hex/${chunk.joinToString(",")}")
            if (response.status != 200) return@forEach
            runCatching {
                val root = JSONObject(response.body)
                val serverNow = root.optLong("now", System.currentTimeMillis())
                val aircraft = root.optJSONArray("ac") ?: JSONArray()
                for (i in 0 until aircraft.length()) {
                    parseAdsb(aircraft.optJSONObject(i) ?: continue, serverNow)?.let(out::add)
                }
            }
        }
        return out
    }

    private fun parseAdsb(ac: JSONObject, serverNow: Long): TrackFix? {
        val hex = ac.optString("hex").trim().lowercase()
        if (hex.length != 6) return null
        val lat = ac.optDouble("lat", Double.NaN)
        val lon = ac.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null

        // alt_baro is a number in feet, or the literal string "ground".
        val altFt = (ac.opt("alt_baro") as? Number)?.toDouble()
        val gsKt = ac.optDouble("gs", Double.NaN).takeUnless { it.isNaN() }
        val track = ac.optDouble("track", Double.NaN).takeUnless { it.isNaN() }

        // seen_pos is seconds since the position was measured, not since the reply.
        val seen = ac.optDouble("seen_pos", 0.0).takeUnless { it.isNaN() } ?: 0.0
        val at = serverNow - (seen * 1000).toLong()

        return TrackFix(
            targetId = hex,
            at = at,
            lat = lat,
            lon = lon,
            altM = altFt?.times(FEET_TO_M),
            speedMs = gsKt?.times(KNOTS_TO_MS),
            heading = track,
            source = "adsb.lol"
        )
    }

    /** OpenSky single-airframe snapshot. Returns null when outside coverage. */
    suspend fun pollOpenSky(icao24: String): TrackFix? {
        val hex = icao24.trim().lowercase()
        if (hex.length != 6) return null
        val response = Net.getOrNull("$OPENSKY/states/all?icao24=$hex")
        if (response.status != 200) return null
        return runCatching {
            val states = JSONObject(response.body).optJSONArray("states") ?: return null
            if (states.length() == 0) return null
            val s = states.optJSONArray(0) ?: return null
            val lon = s.optDouble(5, Double.NaN)
            val lat = s.optDouble(6, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return null
            if (s.optBoolean(8, false)) return null // on ground, no position worth plotting
            TrackFix(
                targetId = hex,
                at = (s.optDouble(3, 0.0) * 1000).toLong()
                    .takeIf { it > 0 } ?: System.currentTimeMillis(),
                lat = lat,
                lon = lon,
                altM = s.optDouble(7, Double.NaN).takeUnless { it.isNaN() },
                speedMs = s.optDouble(9, Double.NaN).takeUnless { it.isNaN() },
                heading = s.optDouble(10, Double.NaN).takeUnless { it.isNaN() },
                source = "opensky"
            )
        }.getOrNull()
    }

    /**
     * Backfill from the flight currently in progress.
     *
     * `time=0` is the only window anonymous callers get — anything older is
     * 403 "You cannot access historical tracks". A 13-minute flight yields ~44
     * points, which is a real track without waiting for the poller to build one.
     */
    suspend fun seedOpenSkyTrack(icao24: String): List<TrackFix> {
        val hex = icao24.trim().lowercase()
        if (hex.length != 6) return emptyList()
        val response = Net.getOrNull("$OPENSKY/tracks/all?icao24=$hex&time=0")
        if (response.status != 200) return emptyList()
        return runCatching {
            val path = JSONObject(response.body).optJSONArray("path") ?: return emptyList()
            buildList {
                for (i in 0 until path.length()) {
                    val p = path.optJSONArray(i) ?: continue
                    if (p.length() < 5) continue
                    val lat = p.optDouble(1, Double.NaN)
                    val lon = p.optDouble(2, Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) continue
                    add(
                        TrackFix(
                            targetId = hex,
                            at = (p.optDouble(0, 0.0) * 1000).toLong(),
                            lat = lat,
                            lon = lon,
                            altM = p.optDouble(3, Double.NaN).takeUnless { it.isNaN() },
                            heading = p.optDouble(4, Double.NaN).takeUnless { it.isNaN() },
                            source = "opensky.track"
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Static airframe identity. Keyless, one call per hex, cache the result. */
    suspend fun enrich(hex: String): AircraftMeta? {
        val clean = hex.trim().lowercase()
        if (clean.length != 6) return null
        val response = Net.getOrNull("$HEXDB/aircraft/$clean")
        if (response.status != 200) return null
        return runCatching {
            val o = JSONObject(response.body)
            val registration = o.optString("Registration").trim()
            if (registration.isBlank()) return null
            AircraftMeta(
                hex = clean,
                registration = registration,
                manufacturer = o.optString("Manufacturer").trim(),
                typeCode = o.optString("ICAOTypeCode").trim(),
                typeName = o.optString("Type").trim(),
                owner = o.optString("RegisteredOwners").trim()
            )
        }.getOrNull()
    }

    /**
     * adsb.lol reports ground traffic with `alt_baro: "ground"` and no altitude.
     * Anything below this is treated as not airborne when filtering a ledger.
     */
    fun isAirborne(fix: TrackFix): Boolean = (fix.altM ?: 0.0) > 50.0
}
