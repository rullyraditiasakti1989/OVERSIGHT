package com.vector.console.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** What is moving. The schema is identical for all four — only the poller differs. */
enum class TrackKind { ICAO24, MMSI, MOVE, PHONE }

data class TrackTarget(
    val id: String,
    val kind: TrackKind,
    val label: String,
    val meta: String = "",
    val active: Boolean = true,
    val cadenceSec: Int = 15,
    val createdAt: Long = System.currentTimeMillis()
)

data class TrackFix(
    val targetId: String,
    val at: Long,
    val lat: Double,
    val lon: Double,
    val altM: Double? = null,
    val speedMs: Double? = null,
    val heading: Double? = null,
    val source: String = "adsb.lol",
    val accuracyM: Double? = null
)

/** A hole in the record. Missing is not the same as stopped. */
data class TrackGap(val from: Long, val to: Long) {
    val seconds: Long get() = ((to - from) / 1000).coerceAtLeast(0)
}

data class TrackSummary(
    val targetId: String,
    val points: Int,
    val firstAt: Long,
    val lastAt: Long,
    val lastLat: Double,
    val lastLon: Double,
    val travelledM: Double,
    val gaps: List<TrackGap>
) {
    val spanSeconds: Long get() = ((lastAt - firstAt) / 1000).coerceAtLeast(0)
    val coveragePct: Int
        get() {
            if (spanSeconds <= 0L) return 0
            val missing = gaps.sumOf { it.seconds }
            return (((spanSeconds - missing).toDouble() / spanSeconds) * 100).toInt().coerceIn(0, 100)
        }
}

/**
 * Time-series store for moving targets.
 *
 * No free upstream keeps history: OpenSky returns 403 "You cannot access historical
 * tracks" for anonymous callers, and adsb.lol keeps only a live snapshot. The track
 * is therefore ours to accumulate — this is where it lives.
 */
class TrackStore(context: Context) {

    private val helper = object : SQLiteOpenHelper(
        context.applicationContext, "oversight_tracks.db", null, 1
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE targets (
                    id TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    label TEXT NOT NULL,
                    meta TEXT NOT NULL DEFAULT '',
                    active INTEGER NOT NULL DEFAULT 1,
                    cadence_sec INTEGER NOT NULL DEFAULT 15,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE points (
                    target_id TEXT NOT NULL,
                    t INTEGER NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    alt REAL,
                    spd REAL,
                    hdg REAL,
                    src TEXT NOT NULL,
                    acc REAL,
                    PRIMARY KEY (target_id, t)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_points_target_t ON points(target_id, t)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS points")
            db.execSQL("DROP TABLE IF EXISTS targets")
            onCreate(db)
        }
    }

    fun upsertTarget(target: TrackTarget) {
        val values = ContentValues().apply {
            put("id", target.id)
            put("kind", target.kind.name)
            put("label", target.label)
            put("meta", target.meta)
            put("active", if (target.active) 1 else 0)
            put("cadence_sec", target.cadenceSec)
            put("created_at", target.createdAt)
        }
        helper.writableDatabase.insertWithOnConflict(
            "targets", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun setActive(id: String, active: Boolean) {
        helper.writableDatabase.execSQL(
            "UPDATE targets SET active = ? WHERE id = ?",
            arrayOf(if (active) 1 else 0, id)
        )
    }

    fun deleteTarget(id: String) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("points", "target_id = ?", arrayOf(id))
            db.delete("targets", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun targets(): List<TrackTarget> = helper.readableDatabase.rawQuery(
        "SELECT id, kind, label, meta, active, cadence_sec, created_at FROM targets ORDER BY created_at DESC",
        null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    TrackTarget(
                        id = cursor.getString(0),
                        kind = runCatching { TrackKind.valueOf(cursor.getString(1)) }
                            .getOrDefault(TrackKind.ICAO24),
                        label = cursor.getString(2),
                        meta = cursor.getString(3),
                        active = cursor.getInt(4) == 1,
                        cadenceSec = cursor.getInt(5),
                        createdAt = cursor.getLong(6)
                    )
                )
            }
        }
    }

    fun activeTargets(): List<TrackTarget> = targets().filter { it.active }

    /** Bulk append in one transaction. Identical timestamps collapse — re-polling is free. */
    fun append(fixes: List<TrackFix>): Int {
        if (fixes.isEmpty()) return 0
        val db = helper.writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            fixes.forEach { fix ->
                val values = ContentValues().apply {
                    put("target_id", fix.targetId)
                    put("t", fix.at)
                    put("lat", fix.lat)
                    put("lon", fix.lon)
                    fix.altM?.let { put("alt", it) }
                    fix.speedMs?.let { put("spd", it) }
                    fix.heading?.let { put("hdg", it) }
                    put("src", fix.source)
                    fix.accuracyM?.let { put("acc", it) }
                }
                if (db.insertWithOnConflict(
                        "points", null, values, SQLiteDatabase.CONFLICT_IGNORE
                    ) != -1L
                ) inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    fun points(targetId: String, sinceMs: Long = 0L, limit: Int = 5_000): List<TrackFix> =
        helper.readableDatabase.rawQuery(
            """
            SELECT t, lat, lon, alt, spd, hdg, src, acc FROM points
            WHERE target_id = ? AND t >= ?
            ORDER BY t ASC LIMIT ?
            """.trimIndent(),
            arrayOf(targetId, sinceMs.toString(), limit.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        TrackFix(
                            targetId = targetId,
                            at = cursor.getLong(0),
                            lat = cursor.getDouble(1),
                            lon = cursor.getDouble(2),
                            altM = cursor.getDouble(3).takeUnless { cursor.isNull(3) },
                            speedMs = cursor.getDouble(4).takeUnless { cursor.isNull(4) },
                            heading = cursor.getDouble(5).takeUnless { cursor.isNull(5) },
                            source = cursor.getString(6),
                            accuracyM = cursor.getDouble(7).takeUnless { cursor.isNull(7) }
                        )
                    )
                }
            }
        }

    fun lastFix(targetId: String): TrackFix? = helper.readableDatabase.rawQuery(
        "SELECT t, lat, lon, alt, spd, hdg, src, acc FROM points WHERE target_id = ? ORDER BY t DESC LIMIT 1",
        arrayOf(targetId)
    ).use { cursor ->
        if (!cursor.moveToFirst()) null
        else TrackFix(
            targetId = targetId,
            at = cursor.getLong(0),
            lat = cursor.getDouble(1),
            lon = cursor.getDouble(2),
            altM = cursor.getDouble(3).takeUnless { cursor.isNull(3) },
            speedMs = cursor.getDouble(4).takeUnless { cursor.isNull(4) },
            heading = cursor.getDouble(5).takeUnless { cursor.isNull(5) },
            source = cursor.getString(6),
            accuracyM = cursor.getDouble(7).takeUnless { cursor.isNull(7) }
        )
    }

    /** Consecutive fixes further apart than [thresholdSec] are a gap, not a movement. */
    fun gaps(targetId: String, thresholdSec: Long = 90L): List<TrackGap> {
        val stamps = helper.readableDatabase.rawQuery(
            "SELECT t FROM points WHERE target_id = ? ORDER BY t ASC",
            arrayOf(targetId)
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        val out = mutableListOf<TrackGap>()
        for (i in 1 until stamps.size) {
            val delta = (stamps[i] - stamps[i - 1]) / 1000
            if (delta > thresholdSec) out += TrackGap(stamps[i - 1], stamps[i])
        }
        return out
    }

    fun summary(targetId: String, thresholdSec: Long = 90L): TrackSummary? {
        val pts = points(targetId)
        if (pts.isEmpty()) return null
        var travelled = 0.0
        for (i in 1 until pts.size) {
            travelled += haversine(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
        }
        val last = pts.last()
        return TrackSummary(
            targetId = targetId,
            points = pts.size,
            firstAt = pts.first().at,
            lastAt = last.at,
            lastLat = last.lat,
            lastLon = last.lon,
            travelledM = travelled,
            gaps = gaps(targetId, thresholdSec)
        )
    }

    fun pointCount(targetId: String): Int = helper.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM points WHERE target_id = ?", arrayOf(targetId)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Retention. A 15 s cadence over 30 days is ~172k rows per target. */
    fun prune(olderThanMs: Long): Int = helper.writableDatabase.delete(
        "points", "t < ?", arrayOf(olderThanMs.toString())
    )

    fun totalPoints(): Int = helper.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM points", null
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    companion object {
        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}
