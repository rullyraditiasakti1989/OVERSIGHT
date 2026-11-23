package com.vector.console.data.globe

import android.content.Context
import com.vector.console.data.IndonesiaCctv
import com.vector.console.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The airborne, seismic and marine layer fetchers. Rail lives in [RailFeeds].
 *
 * Every parser here is pure and unit-tested, because the upstreams are remote and
 * occasionally truncate: `properties.mag` arrives as `null`, `alt_baro` arrives as
 * the string `"ground"`, a TLE block arrives with a trailing blank line. None of
 * that may throw and none of it may silently shift a row.
 *
 * All network access goes through [Net] so the User-Agent stays descriptive —
 * CelesTrak and adsb.lol both throttle anonymous traffic.
 */
object GlobeFeeds {

    // adsb.lol caps the radius query at 250 NM.
    const val AIRCRAFT_MAX_NM = 250.0

    // Eight groups is roughly 1,400 element sets. Drawing more than this on a phone
    // buys nothing: most are below the horizon.
    const val SATELLITE_CAP = 800

    private const val ADSB_POINT = "https://api.adsb.lol/v2/lat/%s/lon/%s/dist/%s"
    private const val ADSB_MIL = "https://api.adsb.lol/v2/mil"
    private const val USGS_DAY =
        "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_day.geojson"
    private const val FIRMS_CSV =
        "https://firms.modaps.eosdis.nasa.gov/api/area/csv/%s/%s/%s/%d"

    // The keyless MODIS export. Same detections as the area API, whole planet, 24 h.
    private const val MODIS_GLOBAL_CSV =
        "https://firms.modaps.eosdis.nasa.gov/data/active_fire/modis-c6.1/csv/MODIS_C6_1_Global_24h.csv"

    // Digitraffic AIS positions. Requires `Accept-Encoding: gzip` — see Net.getGzip.
    private const val DIGITRAFFIC_AIS = "https://meri.digitraffic.fi/api/ais/v1/locations"

    /** Baltic AIS usually reports a few thousand hulls; the plate does not need all of them. */
    const val VESSEL_CAP = 800

    /** The global CSV is ~870 KB; ten minutes is the polite floor for re-downloading it. */
    private const val GLOBAL_FIRES_TTL_MS = 10 * 60 * 1000L

    /**
     * Last downloaded global file: arrival millis, parsed contacts, byte length.
     *
     * Process-local on purpose. The source is already a 24 h snapshot, so nothing
     * here is worth surviving a cold start, and a stale serialised planet in
     * SharedPreferences would be the largest thing the app owns.
     */
    private class GlobalFires(val atMillis: Long, val contacts: List<Contact>, val bytes: Long)

    @Volatile
    private var globalFiresCache: GlobalFires? = null

    // ------------------------------------------------------------------ aircraft

    /**
     * Aircraft within [radiusNm] of a point, or the whole military picture when
     * [radiusNm] is null.
     *
     * The radius form is the useful one on a phone: it returns tens of contacts
     * instead of the global feed.
     */
    suspend fun aircraft(
        lat: Double? = null,
        lon: Double? = null,
        radiusNm: Double = 120.0,
        now: Long = System.currentTimeMillis()
    ): LayerSnapshot = withContext(Dispatchers.IO) {
        val url = if (lat == null || lon == null) {
            ADSB_MIL
        } else {
            ADSB_POINT.format(
                fmt(lat, 4),
                fmt(lon, 4),
                radiusNm.coerceIn(5.0, AIRCRAFT_MAX_NM).roundToInt()
            )
        }
        try {
            val body = Net.get(url, accept = "application/json")
            val contacts = parseAircraft(body)
            LayerSnapshot(
                layer = LayerId.AIRCRAFT,
                contacts = contacts,
                fetchedAtMillis = now,
                stale = false,
                bytes = body.length.toLong(),
                note = if (contacts.isEmpty()) "no aircraft in range" else null
            )
        } catch (e: Exception) {
            LayerSnapshot(LayerId.AIRCRAFT, emptyList(), now, stale = true, note = reason(e))
        }
    }

    /**
     * Parses an adsb.lol `v2` payload.
     *
     * Contacts without a position are dropped: a callsign with no latitude cannot
     * be drawn, and placing it at 0,0 puts a phantom contact in the Gulf of Guinea.
     */
    fun parseAircraft(text: String): List<Contact> {
        val root = Json.parse(text) ?: return emptyList()
        val array = root.arr("ac") ?: return emptyList()
        val out = ArrayList<Contact>(array.items.size)
        for (item in array.items) {
            val lat = item.num("lat") ?: continue
            val lon = item.num("lon") ?: continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue

            val hex = item.str("hex")?.trim()?.lowercase().orEmpty()
            val callsign = item.str("flight")?.trim().orEmpty()
            val id = if (hex.isNotEmpty()) hex else GlobeGeo.shortId("ac", lat, lon)
            val title = when {
                callsign.isNotEmpty() -> callsign
                hex.isNotEmpty() -> hex.uppercase()
                else -> "UNKNOWN"
            }

            // alt_baro is a number, the literal string "ground", or absent.
            val baro = item.num("alt_baro")
            val onGround = (item.str("alt_baro") ?: "").equals("ground", ignoreCase = true)
            val altGeom = item.num("alt_geom")
            val altitude = when {
                onGround -> "GROUND"
                baro != null -> "${baro.roundToInt()} FT"
                altGeom != null -> "${altGeom.roundToInt()} FT (GEOM)"
                else -> "--"
            }

            val gs = item.num("gs")
            val track = item.num("track")
            val squawk = item.str("squawk")?.trim().orEmpty()
            // The military feed publishes `type`; the point feed has used `t`.
            val type = (item.str("type") ?: item.str("t"))?.trim().orEmpty()
            val reg = item.str("r")?.trim().orEmpty()
            val category = item.str("category")?.trim().orEmpty()

            val fields = buildList {
                add(Field("TYPE", type.ifEmpty { category.ifEmpty { "--" } }))
                add(Field("REG", reg.ifEmpty { "--" }))
                add(Field("ALT", altitude))
                add(Field("GS", if (gs != null) "${gs.roundToInt()} KT" else "--"))
                add(Field("HDG", if (track != null) "${track.roundToInt()}°" else "--"))
                add(Field("SQUAWK", squawk.ifEmpty { "--" }))
                add(Field("HEX", hex.uppercase().ifEmpty { "--" }))
            }

            val subtitle = listOfNotNull(
                type.ifEmpty { null },
                altitude,
                if (gs != null) "${gs.roundToInt()} KT" else null
            ).joinToString(" · ")

            out.add(
                Contact(
                    id = "aircraft:$id",
                    layer = LayerId.AIRCRAFT,
                    lat = lat,
                    lon = lon,
                    title = title,
                    subtitle = subtitle,
                    fields = fields
                )
            )
        }
        return out
    }

    // -------------------------------------------------------------------- quakes

    /**
     * USGS `all_day` feed. [LayerSnapshot.stale] is set when the network fails, and
     * the snapshot is empty rather than fabricated.
     */
    suspend fun quakes(now: Long = System.currentTimeMillis()): LayerSnapshot =
        withContext(Dispatchers.IO) {
            try {
                val body = Net.get(USGS_DAY, accept = "application/geo+json")
                val contacts = parseQuakes(body)
                LayerSnapshot(
                    layer = LayerId.QUAKES,
                    contacts = contacts,
                    fetchedAtMillis = now,
                    stale = false,
                    bytes = body.length.toLong(),
                    note = if (contacts.isEmpty()) "no events in the last 24 h" else null
                )
            } catch (e: Exception) {
                LayerSnapshot(LayerId.QUAKES, emptyList(), now, stale = true, note = reason(e))
            }
        }

    /**
     * Parses USGS GeoJSON.
     *
     * `properties.mag` is `null` for many micro-events and for quarry blasts; those
     * are kept with `--` rather than dropped, because "something moved here" is the
     * useful signal even when the magnitude has not been solved.
     */
    fun parseQuakes(text: String): List<Contact> {
        val root = Json.parse(text) ?: return emptyList()
        val features = root.arr("features") ?: return emptyList()
        val out = ArrayList<Contact>(features.items.size)
        for (feature in features.items) {
            val coords = feature.obj("geometry")?.arr("coordinates")?.items ?: continue
            // USGS order is [longitude, latitude, depth_km].
            val lon = (coords.getOrNull(0) as? Json.Num)?.value ?: continue
            val lat = (coords.getOrNull(1) as? Json.Num)?.value ?: continue
            val depth = (coords.getOrNull(2) as? Json.Num)?.value
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue

            val props = feature.obj("properties")
            val mag = props.num("mag")
            val place = props.str("place")?.trim().orEmpty()
            val time = props.num("time")?.toLong() ?: 0L
            val tsunami = props.num("tsunami")?.let { it >= 1.0 } ?: false
            val alert = props.str("alert")?.trim().orEmpty()
            val sig = props.num("sig")
            val magType = props.str("magType")?.trim().orEmpty()
            val status = props.str("status")?.trim().orEmpty()
            val eventId = feature.str("id")?.trim().orEmpty()

            val id = if (eventId.isNotEmpty()) eventId else GlobeGeo.shortId("eq", lat, lon)
            val title = if (mag != null) "M ${"%.1f".format(mag)}" else "M --"
            val subtitle = place.ifEmpty { "location unresolved" }

            val fields = buildList {
                add(Field("MAGNITUDE", if (mag != null) "%.1f".format(mag) else "--"))
                add(Field("DEPTH", if (depth != null) "${"%.1f".format(depth)} KM" else "--"))
                add(Field("TIME", if (time > 0) isoMinute(time) else "--"))
                add(Field("TSUNAMI", if (tsunami) "FLAGGED" else "NO"))
                add(Field("ALERT", alert.ifEmpty { "--" }.uppercase()))
                add(Field("SIGNIFICANCE", if (sig != null) sig.roundToInt().toString() else "--"))
                add(Field("MAG TYPE", magType.ifEmpty { "--" }))
                add(Field("STATUS", status.ifEmpty { "--" }.uppercase()))
            }

            out.add(
                Contact(
                    id = "quakes:$id",
                    layer = LayerId.QUAKES,
                    lat = lat,
                    lon = lon,
                    title = title,
                    subtitle = subtitle,
                    fields = fields,
                    // The one permitted extra use of the seal colour.
                    severity = mag != null && mag >= 5.0
                )
            )
        }
        // Newest first: the ledger reads as a log, not a scatter.
        out.sortByDescending { it.fields.firstOrNull { f -> f.label == "TIME" }?.value.orEmpty() }
        return out
    }

    // ---------------------------------------------------------------- satellites

    /**
     * Propagates the cached element sets to [atMillis].
     *
     * A record that fails to propagate is skipped, never placed at 0,0 — a decayed
     * satellite drawn over the Gulf of Guinea is worse than one missing.
     */
    suspend fun satellites(
        context: Context,
        atMillis: Long = System.currentTimeMillis()
    ): LayerSnapshot = withContext(Dispatchers.IO) {
        val results = coroutineScope {
            TleStore.GROUPS.map { group -> async { TleStore.records(context, group) } }
                .map { it.await() }
        }
        val records = LinkedHashMap<String, TleRecord>()
        var stale = false
        var note: String? = null
        for (result in results) {
            if (result.stale) {
                stale = true
                note = result.note ?: "element set served from cache"
            }
            for (record in result.records) {
                // Duplicates across groups are normal; first wins.
                records.putIfAbsent(record.line1.substring(2, 7), record)
            }
        }
        val contacts = propagateAll(records.values.toList(), atMillis)
        LayerSnapshot(
            layer = LayerId.SATELLITES,
            contacts = contacts,
            fetchedAtMillis = atMillis,
            stale = stale,
            bytes = records.values.sumOf { (it.line1.length + it.line2.length).toLong() },
            note = note ?: if (contacts.isEmpty()) "no element sets" else null
        )
    }

    /** Pure half of [satellites], so the propagation path is testable without a Context. */
    fun propagateAll(records: List<TleRecord>, atMillis: Long): List<Contact> {
        val out = ArrayList<Contact>(minOf(records.size, SATELLITE_CAP))
        var drawn = 0
        for (record in records) {
            if (drawn >= SATELLITE_CAP) break
            val rec = Sgp4.twoline2satrec(record.line1, record.line2) ?: continue
            val geo = Sgp4.positionAt(rec, atMillis) ?: continue
            if (geo.latitude !in -90.0..90.0) continue
            drawn++

            val catnum = rec.satnum.trim()
            val name = record.name.ifEmpty { catnum }
            val period = rec.periodMinutes
            val altitudeKm = geo.height
            val inclination = Math.toDegrees(rec.inclo)

            out.add(
                Contact(
                    id = "satellites:$catnum",
                    layer = LayerId.SATELLITES,
                    lat = geo.latitude,
                    lon = GlobeGeo.wrapLon(geo.longitude),
                    title = name,
                    subtitle = "${altitudeKm.roundToInt()} KM · P ${period.roundToInt()} MIN",
                    fields = listOf(
                        Field("CATALOG", catnum),
                        Field("ALTITUDE", "${altitudeKm.roundToInt()} KM"),
                        Field("PERIOD", "${period.roundToInt()} MIN"),
                        Field("INCLINATION", "${"%.2f".format(inclination)}°"),
                        Field("EPOCH", epochLabel(rec)),
                        Field("MODEL", if (rec.method == 'd') "SDP4" else "SGP4")
                    )
                )
            )
        }
        return out
    }

    // -------------------------------------------------------------------- fires

    /**
     * NASA FIRMS thermal anomalies.
     *
     * With a `MAP_KEY` this is the area API: detections near the anchor, VIIRS NRT.
     * With no key it falls back to the keyless MODIS global export and filters it to
     * the same box, so the layer works out of the box at a coarser cadence and a
     * coarser instrument. See [globalFires] for the trade.
     */
    suspend fun fires(
        key: String?,
        lat: Double = 0.0,
        lon: Double = 0.0,
        radiusDeg: Double = 12.0,
        now: Long = System.currentTimeMillis()
    ): LayerSnapshot = withContext(Dispatchers.IO) {
        val box = listOf(
            GlobeGeo.wrapLon(lon - radiusDeg),
            (lat - radiusDeg).coerceIn(-90.0, 90.0),
            GlobeGeo.wrapLon(lon + radiusDeg),
            (lat + radiusDeg).coerceIn(-90.0, 90.0)
        ).joinToString(",") { fmt(it, 2) }

        if (key.isNullOrBlank()) return@withContext globalFires(lat, lon, radiusDeg, now)

        val url = FIRMS_CSV.format(key, "VIIRS_SNPP_NRT", box, 1)
        try {
            val body = Net.get(url, accept = "text/csv")
            val contacts = parseFires(body)
            LayerSnapshot(
                LayerId.FIRES, contacts, now, stale = false,
                bytes = body.length.toLong(),
                note = if (contacts.isEmpty()) "no thermal anomalies" else null
            )
        } catch (e: Exception) {
            LayerSnapshot(LayerId.FIRES, emptyList(), now, stale = true, note = reason(e))
        }
    }

    /**
     * The keyless path.
     *
     * The area API is keyed, so a blank `firms_key` used to mean a dead layer. NASA also
     * publishes the same detections as a plain 24 h global CSV with no key at all — near a
     * megabyte, and therefore useless to re-fetch every poll. It is cached for
     * [GLOBAL_FIRES_TTL_MS] and filtered to the view on each call, so the cost is one
     * download per ten minutes rather than one per chart refresh.
     *
     * The honest trade is printed in the note: the global file is the whole planet's last
     * 24 h, so a fire that started ten minutes ago is not in it yet.
     */
    private suspend fun globalFires(
        lat: Double,
        lon: Double,
        radiusDeg: Double,
        now: Long
    ): LayerSnapshot {
        val cached = globalFiresCache
        val fresh = cached != null && now - cached.atMillis < GLOBAL_FIRES_TTL_MS
        val box = viewport(lat, lon, radiusDeg)
        if (fresh) {
            val inView = cached!!.contacts.filter { c -> GlobeGeo.contains(box, c.lat, c.lon) }
            return LayerSnapshot(
                LayerId.FIRES,
                inView,
                now,
                stale = false,
                bytes = cached.bytes,
                note = if (inView.isEmpty()) "no thermal anomalies in view" else "MODIS global 24 h"
            )
        }
        val all = try {
            val body = Net.get(MODIS_GLOBAL_CSV, accept = "text/csv")
            val parsed = parseFires(body)
            val entry = GlobalFires(now, parsed, body.length.toLong())
            globalFiresCache = entry
            entry
        } catch (e: Exception) {
            // A failed refresh may still serve the previous file; that is exactly
            // what `stale` is for. With nothing cached there is nothing to serve.
            val held = cached
            return LayerSnapshot(
                LayerId.FIRES,
                held?.contacts.orEmpty().filter { c -> GlobeGeo.contains(box, c.lat, c.lon) },
                now,
                stale = true,
                bytes = held?.bytes ?: 0L,
                note = reason(e)
            )
        }
        val contacts = all.contacts.filter { c -> GlobeGeo.contains(box, c.lat, c.lon) }
        return LayerSnapshot(
            LayerId.FIRES,
            contacts,
            now,
            stale = false,
            bytes = all.bytes,
            note = if (contacts.isEmpty()) "no thermal anomalies in view" else "MODIS global 24 h"
        )
    }

    /** Same box the keyed URL would have asked for, as a value. */
    private fun viewport(lat: Double, lon: Double, radiusDeg: Double): Bbox = Bbox(
        south = (lat - radiusDeg).coerceIn(-90.0, 90.0),
        west = GlobeGeo.wrapLon(lon - radiusDeg),
        north = (lat + radiusDeg).coerceIn(-90.0, 90.0),
        east = GlobeGeo.wrapLon(lon + radiusDeg)
    )

    /**
     * Parses the FIRMS area CSV.
     *
     * The header is read rather than assumed: column order has changed twice in
     * the service's life, and an `invalid map key` response is plain text with no
     * header at all.
     */
    fun parseFires(text: String): List<Contact> {
        val lines = text.split("\r\n", "\n").filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val header = lines[0].split(",").map { it.trim().lowercase() }
        val latIdx = header.indexOf("latitude")
        val lonIdx = header.indexOf("longitude")
        if (latIdx < 0 || lonIdx < 0) return emptyList()
        val brightIdx = header.indexOf("bright_ti4")
        val frpIdx = header.indexOf("frp")
        val dateIdx = header.indexOf("acq_date")
        val timeIdx = header.indexOf("acq_time")
        val satIdx = header.indexOf("satellite")
        val confIdx = header.indexOf("confidence")

        val out = ArrayList<Contact>(lines.size - 1)
        for (i in 1 until lines.size) {
            val cols = lines[i].split(",")
            val lat = cols.getOrNull(latIdx)?.trim()?.toDoubleOrNull() ?: continue
            val lon = cols.getOrNull(lonIdx)?.trim()?.toDoubleOrNull() ?: continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            val bright = cols.getOrNull(brightIdx)?.trim()?.toDoubleOrNull()
            val frp = cols.getOrNull(frpIdx)?.trim()?.toDoubleOrNull()
            val date = cols.getOrNull(dateIdx)?.trim().orEmpty()
            val time = cols.getOrNull(timeIdx)?.trim().orEmpty()
            val sat = cols.getOrNull(satIdx)?.trim().orEmpty()
            val conf = cols.getOrNull(confIdx)?.trim().orEmpty()

            out.add(
                Contact(
                    id = "fires:" + GlobeGeo.shortId("f", lat, lon) + ":" + i,
                    layer = LayerId.FIRES,
                    lat = lat,
                    lon = lon,
                    title = if (bright != null) "${"%.0f".format(bright)} K" else "THERMAL",
                    subtitle = listOfNotNull(
                        sat.ifEmpty { null },
                        date.ifEmpty { null }
                    ).joinToString(" · "),
                    fields = listOf(
                        Field("BRIGHTNESS", if (bright != null) "${"%.1f".format(bright)} K" else "--"),
                        Field("FRP", if (frp != null) "${"%.1f".format(frp)} MW" else "--"),
                        Field("CONFIDENCE", conf.ifEmpty { "--" }),
                        Field("SATELLITE", sat.ifEmpty { "--" }),
                        Field("ACQUIRED", "$date $time".trim().ifEmpty { "--" })
                    ),
                    severity = frp != null && frp >= 50.0
                )
            )
        }
        return out
    }

    // ------------------------------------------------------------------ cameras

    /**
     * Our own Indonesian CCTV catalogue, which GEV does not carry. Cameras without
     * coordinates are counted but not drawn; the ledger says so in [LayerSnapshot.note].
     */
    suspend fun cameras(context: Context, now: Long = System.currentTimeMillis()): LayerSnapshot {
        val all = runCatching { IndonesiaCctv.load(context) }.getOrDefault(emptyList())
        val placed = all.filter { it.latitude != null && it.longitude != null }
        val contacts = placed.map { camera ->
            Contact(
                id = "cameras:${camera.id}",
                layer = LayerId.CAMERAS,
                lat = camera.latitude!!,
                lon = camera.longitude!!,
                title = camera.name,
                subtitle = listOf(camera.city, if (camera.hls) "HLS" else "STREAM")
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                fields = listOf(
                    Field("CITY", camera.city.ifBlank { "--" }),
                    Field("SOURCE", camera.source.ifBlank { "--" }),
                    Field("FORMAT", if (camera.hls) "HLS" else "HTTP"),
                    Field("STATUS", if (camera.available) "LISTED" else "OFFLINE")
                )
            )
        }
        val unplaced = all.size - placed.size
        return LayerSnapshot(
            layer = LayerId.CAMERAS,
            contacts = contacts,
            fetchedAtMillis = now,
            stale = false,
            bytes = all.sumOf { (it.name.length + it.city.length + it.source.length).toLong() },
            note = if (unplaced > 0) "$unplaced without coordinates" else null
        )
    }

    // ------------------------------------------------------------------ vessels

    /**
     * Vessel positions from Digitraffic — the Finnish Transport Agency's open AIS feed.
     *
     * Keyless, CC0, and a plain GeoJSON file, so it needs no websocket and no
     * subscription. AISStream stays available as an optional key for a future
     * websocket client, but a layer that draws nothing is not a layer, and this one
     * draws real hulls.
     *
     * Coverage is Finnish waters only. The stamp says `Baltic` so the empty middle of
     * the map is read as "out of coverage", not "no ships at sea".
     */
    suspend fun vessels(now: Long = System.currentTimeMillis()): LayerSnapshot =
        withContext(Dispatchers.IO) {
            try {
                val body = Net.getGzip(
                    DIGITRAFFIC_AIS,
                    headers = mapOf("Digitraffic-User" to "OVERSIGHT/1.0"),
                    accept = "application/json"
                )
                val contacts = parseVessels(body)
                LayerSnapshot(
                    layer = LayerId.VESSELS,
                    contacts = contacts,
                    fetchedAtMillis = now,
                    stale = false,
                    bytes = body.length.toLong(),
                    note = if (contacts.isEmpty()) "no vessels reported" else "Baltic · Finnish AIS"
                )
            } catch (e: Exception) {
                LayerSnapshot(LayerId.VESSELS, emptyList(), now, stale = true, note = reason(e))
            }
        }

    /**
     * Parses Digitraffic `locations` GeoJSON.
     *
     * Positionless features exist (`geometry: null` for a station without a fix), and
     * an MMSI is the only identifier the location feed carries — names live in a
     * separate, much larger document, so the card leads with the number.
     */
    fun parseVessels(text: String): List<Contact> {
        val root = Json.parse(text) ?: return emptyList()
        val features = root.arr("features") ?: return emptyList()
        val out = ArrayList<Contact>(minOf(features.items.size, VESSEL_CAP))
        for (feature in features.items) {
            if (out.size >= VESSEL_CAP) break
            val geometry = feature.obj("geometry") ?: continue
            val point = geometry.arr("coordinates") ?: continue
            if (point.items.size < 2) continue
            val lon = (point.items[0] as? Json.Num)?.value ?: continue
            val lat = (point.items[1] as? Json.Num)?.value ?: continue
            if (!lat.isFinite() || !lon.isFinite()) continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue

            val props = feature.obj("properties") ?: continue
            val mmsi = props.num("mmsi")?.let { "%.0f".format(it) } ?: continue
            val sog = props.num("sog")
            val cog = props.num("cog")
            val navStat = props.num("navStat")?.let { navLabel(it.toInt()) } ?: "--"

            out += Contact(
                id = "vessels:$mmsi",
                layer = LayerId.VESSELS,
                lat = lat,
                lon = lon,
                title = "MMSI $mmsi",
                subtitle = buildString {
                    append(if (sog == null) "SOG --" else "SOG ${fmt(sog, 1)} KN")
                    if (cog != null) append(" · COG ${fmt(cog, 0).padStart(3, '0')}°")
                },
                fields = listOf(
                    Field("MMSI", mmsi),
                    Field("SOG", if (sog == null) "--" else "${fmt(sog, 1)} KN"),
                    Field("COG", if (cog == null) "--" else "${fmt(cog, 0)}°"),
                    Field("NAV STATUS", navStat),
                    Field("SOURCE", "Digitraffic · Baltic")
                )
            )
        }
        return out
    }

    /** AIS navigational status. 15 is the standard "undefined", not a missing value. */
    private fun navLabel(status: Int): String = when (status) {
        0 -> "UNDER WAY · ENGINE"
        1 -> "AT ANCHOR"
        2 -> "NOT UNDER COMMAND"
        3 -> "RESTRICTED MANOEUVRABILITY"
        4 -> "CONSTRAINED BY DRAUGHT"
        5 -> "MOORED"
        6 -> "AGROUND"
        7 -> "FISHING"
        8 -> "UNDER WAY · SAIL"
        11 -> "TOWING ASTERN"
        12 -> "PUSHING AHEAD"
        14 -> "AIS-SART"
        else -> "UNDEFINED"
    }

    // ------------------------------------------------------------------ helpers

    /** Compact fixed-point formatting, locale-independent. */
    private fun fmt(value: Double, decimals: Int): String =
        String.format(java.util.Locale.US, "%.${decimals}f", value)

    private fun reason(e: Exception): String = e.message?.take(120) ?: "source unavailable"

    /** `2026-11-22T04:12Z`, the stamp the plate uses. */
    private fun isoMinute(millis: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(millis))
    }

    private fun epochLabel(rec: SatRec): String {
        val year = if (rec.epochyr < 57) rec.epochyr + 2000 else rec.epochyr + 1900
        return "$year·${"%.3f".format(rec.epochdays)}"
    }

    /**
     * Great-circle distance in nautical miles, used by the ledger's nearest-first
     * sort. Haversine on a spherical earth is accurate to 0.3% — far beyond what a
     * 1080 px plate can show.
     */
    fun distanceNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 3440.065 // Earth radius in nautical miles
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.atan2(sqrt(a), sqrt(1 - a))
    }

    /** Sorts a layer nearest-first to a reference point. */
    fun nearest(contacts: List<Contact>, lat: Double, lon: Double, limit: Int = 50): List<Contact> =
        contacts
            .map { it to abs(distanceNm(lat, lon, it.lat, it.lon)) }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
}
