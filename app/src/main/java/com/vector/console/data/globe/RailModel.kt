package com.vector.console.data.globe

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The schedule as published by KAIStream, and the maths that turns it into dots.
 *
 * Nothing in this file touches Android or the network. The timetable is a table
 * of seconds-since-midnight WIB and cumulative kilometres; the only thing that
 * moves a train is the device clock. That is why positions are recomputed on every
 * tick from an in-memory parsed schedule and never stored: no network traffic is
 * needed to advance a train, and a stale schedule still draws honestly.
 */

/** A call at a station. Either edge may be absent: origin has no arrival, terminus no departure. */
data class RailStop(
    val code: String,
    val arrSec: Int?,
    val depSec: Int?,
    val distKm: Double
)

/** One scheduled run. `startSec`/`endSec` are the first departure and last arrival. */
data class RailTrip(
    val id: String,
    val number: String,
    val name: String,
    val category: String,
    val originCode: String,
    val destCode: String,
    val startSec: Int,
    val endSec: Int,
    val totalDistKm: Double,
    val stops: List<RailStop>
)

/** A row of the bundled join table. */
data class RailStation(val code: String, val name: String, val lat: Double, val lon: Double)

/** One active service disruption, in Indonesian as served. */
data class RailAlert(
    val id: String,
    val title: String,
    val description: String,
    val location: String,
    val category: String,
    val severity: String,
    val timestampWib: String,
    val active: Boolean,
    val source: String
)

/** Metadata used for the snapshot stamp, not for positions. */
data class RailMeta(
    val snapshotDate: String,
    /** Short card stamp, e.g. `GAPEKA 2026`, derived from the full decree string. */
    val decree: String,
    val decreeFull: String,
    val generatedAt: String
)

data class RailTimetable(val trips: List<RailTrip>, val meta: RailMeta, val parseMillis: Long)

/** Where a train is, derived from the clock and the join table. */
data class RailPosition(
    val lat: Double,
    val lon: Double,
    val fromName: String,
    val toName: String,
    val state: String,
    val distKm: Double,
    val progress: Double
)

data class RailPositions(val contacts: List<Contact>, val droppedJoins: Int)

object RailModel {

    /** KAIStream publishes local time; the source declares UTC+7 in metadata.json. */
    const val WIB_OFFSET_SECONDS = 7 * 3600

    /**
     * Seconds since midnight, WIB. Converted once at the boundary rather than per
     * train — 2,490 trains times a timezone lookup per tick is a needless cost.
     */
    fun wibSeconds(atMillis: Long): Int {
        val local = atMillis / 1000L + WIB_OFFSET_SECONDS
        return Math.floorMod(local, 86_400L).toInt()
    }

    /** `20:30:00` in the same clock the timetable uses. */
    fun wibClock(seconds: Int): String {
        val s = Math.floorMod(seconds, 86_400)
        return "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    }

    /**
     * Position at [seconds]. Null means the run is not in its window, or a station
     * code could not be joined — a missing join is a dropped leg, never a made-up
     * point at 0,0.
     */
    fun positionAt(trip: RailTrip, stations: Map<String, RailStation>, seconds: Int): RailPosition? {
        if (seconds < trip.startSec || seconds > trip.endSec) return null
        val stops = trip.stops
        if (stops.size < 2) return null

        for (i in stops.indices) {
            val stop = stops[i]
            val arrival = stop.arrSec
            val departure = stop.depSec

            // A dwell is a stop, not a gap: the train is at the station for the
            // whole arr..dep window, and interpolation would slide it down the line.
            if (arrival != null && departure != null && seconds in arrival..departure) {
                val station = stations[stop.code] ?: return null
                return RailPosition(
                    lat = station.lat,
                    lon = station.lon,
                    fromName = station.name,
                    toName = station.name,
                    state = "DWELL · ${station.name}",
                    distKm = stop.distKm,
                    progress = fraction(stop.distKm, trip.totalDistKm)
                )
            }
            if (i == stops.lastIndex) break

            val next = stops[i + 1]
            val fromSec = departure ?: continue
            val toSec = next.arrSec ?: continue
            if (seconds < fromSec || seconds > toSec) continue

            val from = stations[stop.code] ?: return null
            val to = stations[next.code] ?: return null
            val f = if (toSec == fromSec) 0.0 else
                ((seconds - fromSec).toDouble() / (toSec - fromSec)).coerceIn(0.0, 1.0)
            val dist = stop.distKm + f * (next.distKm - stop.distKm)
            return RailPosition(
                lat = from.lat + f * (to.lat - from.lat),
                lon = from.lon + f * (to.lon - from.lon),
                fromName = from.name,
                toName = to.name,
                state = if (f <= 0.0) "DEPARTING ${from.name}" else if (f >= 1.0) "ARRIVING ${to.name}" else "EN ROUTE",
                distKm = dist,
                progress = fraction(dist, trip.totalDistKm)
            )
        }
        return null
    }

    private fun fraction(distance: Double, total: Double): Double =
        if (total > 0.0) (distance / total).coerceIn(0.0, 1.0) else 0.0

    /**
     * Every train inside its window at [atMillis], nearest-first ordering left to
     * the caller. The cap is a drawing limit, not a data limit: the note says so.
     */
    fun positions(
        timetable: RailTimetable,
        stations: Map<String, RailStation>,
        atMillis: Long,
        cap: Int = 400,
        decree: String = "GAPEKA 2026"
    ): RailPositions {
        val seconds = wibSeconds(atMillis)
        val clock = wibClock(seconds)
        val out = ArrayList<Contact>(minOf(cap, timetable.trips.size))
        var dropped = 0
        for (trip in timetable.trips) {
            if (out.size >= cap) break
            if (seconds < trip.startSec || seconds > trip.endSec) continue
            val pos = positionAt(trip, stations, seconds)
            if (pos == null) {
                dropped++
                continue
            }
            out += Contact(
                id = "rail:${trip.id}",
                layer = LayerId.RAIL,
                lat = pos.lat,
                lon = pos.lon,
                title = "${trip.number} · ${trip.name}",
                subtitle = "${pos.state} · ${pos.progress.times(100).roundToInt()}%",
                fields = listOf(
                    Field("TRAIN", trip.number),
                    Field("SERVICE", trip.name),
                    Field("CATEGORY", trip.category.uppercase()),
                    Field("SECTOR", "${pos.fromName} → ${pos.toName}"),
                    Field("STATE", pos.state),
                    Field("DISTANCE", "${fmt(pos.distKm, 1)} / ${fmt(trip.totalDistKm, 1)} KM"),
                    Field("PROGRESS", "${pos.progress.times(100).roundToInt()}%"),
                    Field("LOCAL CLOCK", "$clock WIB"),
                    Field("SCHEDULE", decree)
                )
            )
        }
        return RailPositions(out, dropped)
    }

    // ------------------------------------------------------------ compact cache

    private const val MAGIC = 0x4F565253 // "OVSR"
    private const val VERSION = 1

    /**
     * Flat little-endian form of the schedule, keyed by the upstream snapshot date.
     *
     * The hand-written JSON reader has to allocate a map for every stop — about
     * 25,000 maps for the 2.3 MB file, measured at 3.5 s on LivinPixel. A train
     * moves on the clock, so paying that twice per process is indefensible. The
     * compact form is produced once per snapshot (or built alongside the bundled
     * asset) and decoded in tens of milliseconds.
     *
     * Layout, all little-endian: magic, version, key string, trip count, then per
     * trip six strings, two ints, one double, stop count, and per stop one string,
     * two ints (negative means null) and one double. Strings are an int byte count
     * followed by raw UTF-8, so the writer and reader need no shared runtime.
     */
    fun encodeTimetable(trips: List<RailTrip>, key: String): ByteArray {
        val size = 8 + stringSize(key) + 4 + trips.sumOf { trip ->
            stringSize(trip.id) + stringSize(trip.number) + stringSize(trip.name) +
                stringSize(trip.category) + stringSize(trip.originCode) + stringSize(trip.destCode) +
                8 + 8 + 4 + trip.stops.sumOf { stop -> stringSize(stop.code) + 8 + 8 + 4 }
        }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC)
        buf.putInt(VERSION)
        putString(buf, key)
        buf.putInt(trips.size)
        for (trip in trips) {
            putString(buf, trip.id)
            putString(buf, trip.number)
            putString(buf, trip.name)
            putString(buf, trip.category)
            putString(buf, trip.originCode)
            putString(buf, trip.destCode)
            buf.putInt(trip.startSec)
            buf.putInt(trip.endSec)
            buf.putDouble(trip.totalDistKm)
            buf.putInt(trip.stops.size)
            for (stop in trip.stops) {
                putString(buf, stop.code)
                buf.putInt(stop.arrSec ?: -1)
                buf.putInt(stop.depSec ?: -1)
                buf.putDouble(stop.distKm)
            }
        }
        val out = ByteArray(buf.position())
        buf.flip()
        buf.get(out)
        return out
    }

    /** Null means the bytes are not a cache, or belong to another snapshot. */
    fun decodeTimetable(bytes: ByteArray?, key: String): List<RailTrip>? = runCatching {
        if (bytes == null || bytes.size < 16) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.int != MAGIC || buf.int != VERSION) return null
        if (getString(buf) != key) return null
        val count = buf.int
        if (count < 0 || count > 100_000) return null
        val trips = ArrayList<RailTrip>(count)
        repeat(count) {
            val id = getString(buf)
            val number = getString(buf)
            val name = getString(buf)
            val category = getString(buf)
            val origin = getString(buf)
            val dest = getString(buf)
            val start = buf.int
            val end = buf.int
            val total = buf.double
            val stopCount = buf.int
            if (stopCount < 0 || stopCount > 5000) throw IllegalArgumentException("bad stop count")
            val stops = ArrayList<RailStop>(stopCount)
            repeat(stopCount) {
                val code = getString(buf)
                val arr = buf.int
                val dep = buf.int
                val dist = buf.double
                stops += RailStop(code, arr.takeIf { it >= 0 }, dep.takeIf { it >= 0 }, dist)
            }
            trips += RailTrip(id, number, name, category, origin, dest, start, end, total, stops)
        }
        trips
    }.getOrNull()

    private fun stringSize(value: String): Int = 4 + value.toByteArray(Charsets.UTF_8).size

    private fun putString(buf: ByteBuffer, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        buf.putInt(bytes.size)
        buf.put(bytes)
    }

    private fun getString(buf: ByteBuffer): String {
        val length = buf.int
        if (length < 0 || length > buf.remaining()) throw IllegalArgumentException("bad string length")
        val bytes = ByteArray(length)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ parsers

    fun parseStations(text: String): Map<String, RailStation> {
        val root = Json.parse(text) ?: return emptyMap()
        val array = root as? Json.Arr ?: return emptyMap()
        val out = LinkedHashMap<String, RailStation>(array.items.size)
        for (item in array.items) {
            val code = item.str("c") ?: continue
            val name = item.str("n") ?: continue
            val lat = item.num("y") ?: continue
            val lon = item.num("x") ?: continue
            if (code.isBlank() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            out[code] = RailStation(code, name, lat, lon)
        }
        return out
    }

    fun parseTrips(text: String): List<RailTrip> {
        val root = Json.parse(text) ?: return emptyList()
        val array = root as? Json.Arr ?: return emptyList()
        val out = ArrayList<RailTrip>(array.items.size)
        for (item in array.items) {
            val id = item.str("id") ?: continue
            val number = item.str("number") ?: continue
            val name = item.str("name") ?: continue
            val start = item.num("startSec") ?: continue
            val end = item.num("endSec") ?: continue
            val total = item.num("totalDistKm") ?: 0.0
            val stopNodes = item.arr("stops")?.items ?: continue
            val stops = ArrayList<RailStop>(stopNodes.size)
            for (node in stopNodes) {
                val code = node.str("stationCode") ?: continue
                val arr = node.num("arrSec")?.toInt()
                val dep = node.num("depSec")?.toInt()
                val dist = node.num("distKm") ?: continue
                stops += RailStop(code, arr, dep, dist)
            }
            if (stops.size < 2) continue
            out += RailTrip(
                id = id,
                number = number,
                name = name,
                category = item.str("category") ?: "unknown",
                originCode = item.str("originCode") ?: stops.first().code,
                destCode = item.str("destCode") ?: stops.last().code,
                startSec = start.toInt(),
                endSec = end.toInt(),
                totalDistKm = total,
                stops = stops
            )
        }
        return out
    }

    fun parseAlerts(text: String, activeOnly: Boolean = true): List<RailAlert> {
        val root = Json.parse(text) ?: return emptyList()
        val array = root as? Json.Arr ?: return emptyList()
        val out = ArrayList<RailAlert>(array.items.size)
        for (item in array.items) {
            val active = item.bool("active") ?: false
            if (activeOnly && !active) continue
            val id = item.str("id") ?: continue
            val title = item.str("title") ?: continue
            out += RailAlert(
                id = id,
                title = title,
                description = item.str("description") ?: "",
                location = item.str("location") ?: "",
                category = item.str("category") ?: "",
                severity = item.str("severity") ?: "info",
                timestampWib = item.str("timestampWib") ?: "",
                active = active,
                source = item.str("source") ?: ""
            )
        }
        return out
    }

    fun parseMeta(text: String): RailMeta {
        val root = Json.parse(text)
        val full = root?.str("officialDjkaDecree") ?: ""
        val year = Regex("TAHUN (\\d{4})").find(full)?.groupValues?.get(1)
            ?: root?.str("scheduleSnapshotDate")?.take(4).orEmpty()
        return RailMeta(
            snapshotDate = root?.str("scheduleSnapshotDate") ?: "",
            decree = if (year.length == 4) "GAPEKA $year" else "GAPEKA",
            decreeFull = full,
            generatedAt = root?.str("generatedAt") ?: ""
        )
    }

    /** Locale-independent fixed point, shared with the other globe parsers. */
    private fun fmt(value: Double, decimals: Int): String =
        String.format(java.util.Locale.US, "%.${decimals}f", value)
}
