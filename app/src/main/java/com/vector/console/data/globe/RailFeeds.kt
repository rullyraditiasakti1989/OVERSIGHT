package com.vector.console.data.globe

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * The RAIL layer. Schedule-driven train positions plus the active-alert ledger.
 *
 * This is a timetable, not a tracking feed: KAI does not publish live positions.
 * Every dot is where a scheduled run *should* be at the device's local clock, and
 * the stamp says TIMETABLE so the plate never claims a GPS fix it does not have.
 */
object RailFeeds {

    /** Drawing cap. A few hundred runs in the morning peak is enough on a phone. */
    const val RAIL_CAP = 400

    /** Bundled join table and the compact form of the snapshot it was built from. */
    private const val STATIONS_ASSET = "rail/stations.json"
    private const val TIMETABLE_ASSET = "rail/timetable.ovs"

    private class Parsed(val sourceAt: Long, val timetable: RailTimetable, val source: String)

    @Volatile
    private var parsedCache: Parsed? = null

    @Volatile
    private var stationCache: Map<String, RailStation> = emptyMap()

    /** The bundled table is read once per process; it never changes under us. */
    @Synchronized
    fun stations(context: Context): Map<String, RailStation> {
        if (stationCache.isNotEmpty()) return stationCache
        val text = context.assets.open(STATIONS_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        stationCache = RailModel.parseStations(text)
        return stationCache
    }

    suspend fun rail(
        context: Context,
        now: Long = System.currentTimeMillis(),
        cap: Int = RAIL_CAP
    ): LayerSnapshot = withContext(Dispatchers.IO) {
        val timetableDoc = RailStore.document(context, RailStore.Doc.TIMETABLE, now)
        val alertsDoc = RailStore.document(context, RailStore.Doc.ALERTS, now)
        val metaDoc = RailStore.document(context, RailStore.Doc.METADATA, now)
        val stale = timetableDoc.stale || alertsDoc.stale || metaDoc.stale

        if (timetableDoc.text.isBlank()) {
            return@withContext LayerSnapshot(
                layer = LayerId.RAIL,
                contacts = emptyList(),
                fetchedAtMillis = now,
                stale = stale,
                note = timetableDoc.note ?: alertsDoc.note ?: metaDoc.note ?: "no schedule cached"
            )
        }

        val meta = RailModel.parseMeta(metaDoc.text)
        val timetable = parsed(context, timetableDoc, meta)
        val positions = RailModel.positions(
            timetable = timetable.timetable,
            stations = stations(context),
            atMillis = now,
            cap = cap,
            decree = meta.decree
        )
        val alerts = RailModel.parseAlerts(alertsDoc.text).map {
            LedgerAlert(
                id = it.id,
                title = it.title,
                detail = it.description,
                location = it.location,
                severity = it.severity,
                timestampWib = it.timestampWib,
                source = it.source
            )
        }
        LayerSnapshot(
            layer = LayerId.RAIL,
            contacts = positions.contacts,
            fetchedAtMillis = now,
            stale = stale,
            note = stamp(meta, positions, cap, timetable, stale),
            bytes = timetableDoc.bytes + alertsDoc.bytes + metaDoc.bytes,
            alerts = alerts
        )
    }

    /**
     * Loads the schedule once per cached file, not once per tick.
     *
     * Priority: in-memory object, compact file, bundled compact file, then the raw
     * 2.3 MB JSON. The last route measured 3.5 s on LivinPixel; the first three are
     * tens of milliseconds. A tick inside the twelve-hour TTL does none of them —
     * the clock moves the trains.
     */
    private fun parsed(context: Context, doc: RailStore.Document, meta: RailMeta): Parsed {
        val held = parsedCache
        if (held != null && held.sourceAt == doc.atMillis) return held
        val key = meta.snapshotDate.ifBlank { doc.atMillis.toString() }
        val started = System.nanoTime()

        var source = "DISK"
        var trips = RailModel.decodeTimetable(compactFile(context).readBytesOrNull(), key)
        if (trips == null) {
            source = "BUNDLED"
            trips = RailModel.decodeTimetable(assetBytes(context, TIMETABLE_ASSET), key)
        }
        if (trips == null) {
            source = "JSON"
            trips = RailModel.parseTrips(doc.text)
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000L

        if (source == "JSON" && trips.isNotEmpty()) {
            writeAtomically(
                compactFile(context),
                RailModel.encodeTimetable(trips, key)
            )
        }
        val result = Parsed(
            sourceAt = doc.atMillis,
            timetable = RailTimetable(trips.orEmpty(), meta, elapsed),
            source = source
        )
        parsedCache = result
        return result
    }

    /** The note is the honesty surface of the layer; every qualifier is explicit. */
    private fun stamp(
        meta: RailMeta,
        positions: RailPositions,
        cap: Int,
        parsed: Parsed,
        stale: Boolean
    ): String {
        val parts = ArrayList<String>(5)
        parts += if (snapshotDrift(meta.snapshotDate) > 1) {
            "SNAPSHOT ${meta.snapshotDate.ifBlank { "UNKNOWN" }}"
        } else {
            "SCHEDULE · ${meta.decree}"
        }
        if (positions.contacts.size >= cap) parts += "CAP $cap"
        if (positions.droppedJoins > 0) parts += "${positions.droppedJoins} UNJOINED"
        if (stale) parts += "CACHED"
        parts += "${parsed.source} ${parsed.timetable.parseMillis} MS"
        return parts.joinToString(" · ")
    }

    /** Whole days between the published snapshot and the WIB date on the device. */
    private fun snapshotDrift(snapshotDate: String, now: Long = System.currentTimeMillis()): Long {
        if (snapshotDate.isBlank()) return Long.MAX_VALUE
        val utc = TimeZone.getTimeZone("UTC")
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }
        val published = runCatching { format.parse(snapshotDate)?.time }.getOrNull() ?: return Long.MAX_VALUE
        val today = runCatching {
            format.parse(format.format(java.util.Date(now + RailModel.WIB_OFFSET_SECONDS * 1000L)))?.time
        }.getOrNull() ?: return Long.MAX_VALUE
        return Math.abs(today - published) / 86_400_000L
    }

    private fun compactFile(context: Context): File {
        val dir = File(context.filesDir, "rail")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "timetable.ovs")
    }

    private fun assetBytes(context: Context, name: String): ByteArray =
        runCatching { context.assets.open(name).use { it.readBytes() } }.getOrDefault(ByteArray(0))

    private fun File.readBytesOrNull(): ByteArray? =
        if (exists()) runCatching { readBytes() }.getOrNull() else null

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(bytes)
        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) {
            file.writeBytes(bytes)
            temp.delete()
        }
    }
}
