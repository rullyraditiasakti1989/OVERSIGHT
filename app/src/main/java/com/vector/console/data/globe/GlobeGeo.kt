package com.vector.console.data.globe

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Small coordinate helpers shared by the fetchers and the renderer.
 *
 * Pure functions, no Android — the globe's maths is pinned by `GlobeGeoTest`
 * rather than checked by eye on a 1080 px plate.
 */
object GlobeGeo {

    /** One nautical mile is one arcminute of latitude, so 60 NM to the degree. */
    const val NM_PER_DEGREE = 60.0

    /** The aircraft API is queried by a box snapped to this grid. */
    const val BBOX_STEP = 0.25

    fun nmToDegrees(nm: Double): Double = nm / NM_PER_DEGREE

    fun degreesToNm(degrees: Double): Double = degrees * NM_PER_DEGREE

    /**
     * Snaps a coordinate down to the nearest [BBOX_STEP].
     *
     * Two separate functions rather than one "outward" helper: in the southern
     * hemisphere the upper edge of a box is negative, and a sign-based rule rounds
     * it inward — which silently clips the contacts nearest the north edge.
     */
    fun snapDown(value: Double, step: Double = BBOX_STEP): Double {
        if (step <= 0.0) return value
        return floor(value / step) * step
    }

    /** Snaps a coordinate up to the nearest [BBOX_STEP]. See [snapDown]. */
    fun snapUp(value: Double, step: Double = BBOX_STEP): Double {
        if (step <= 0.0) return value
        return ceil(value / step) * step
    }

    /**
     * Box around a point covering [radiusNm], snapped outward.
     *
     * Latitude is clamped to ±90. Longitude is deliberately allowed to exceed
     * ±180: [Bbox.wraps] then reports that the caller must query both sides of
     * the antimeridian rather than silently losing half the box.
     */
    fun bboxFor(lat: Double, lon: Double, radiusNm: Double): Bbox {
        val dLat = nmToDegrees(radiusNm)
        // A degree of longitude shrinks with latitude; widen the box so the
        // circle is covered at the corners instead of only on the meridian.
        val cosLat = cos(Math.toRadians(lat.coerceIn(-89.0, 89.0)))
        val dLon = if (abs(cosLat) < 1e-6) 180.0 else nmToDegrees(radiusNm) / cosLat

        val south = snapDown(max(-90.0, lat - dLat))
        val north = snapUp(min(90.0, lat + dLat))
        val west = snapDown(lon - dLon)
        val east = snapUp(lon + dLon)
        return Bbox(south, west, north, east)
    }

    /** Normalises a longitude into (-180, 180]. */
    fun wrapLon(lon: Double): Double {
        var value = lon
        while (value <= -180.0) value += 360.0
        while (value > 180.0) value -= 360.0
        return value
    }

    /** True when [lon] falls inside [bbox], honouring an antimeridian wrap. */
    fun contains(bbox: Bbox, lat: Double, lon: Double): Boolean {
        if (lat < bbox.south || lat > bbox.north) return false
        val w = wrapLon(bbox.west)
        val e = wrapLon(bbox.east)
        val l = wrapLon(lon)
        return if (w <= e) l in w..e else l >= w || l <= e
    }

    /** `51.5074 N` / `0.1278 W`, the format the coordinate plate uses. */
    fun formatLat(lat: Double): String =
        "${"%.4f".format(abs(lat))} ${if (lat >= 0) "N" else "S"}"

    fun formatLon(lon: Double): String =
        "${"%.4f".format(abs(lon))} ${if (lon >= 0) "E" else "W"}"

    /**
     * Metres per pixel at a latitude and integer zoom, used by the scale bar.
     * 156543.03392 is the WGS-84 equatorial circumference over 256 px at z0.
     */
    fun metresPerPixel(lat: Double, zoom: Double): Double =
        156543.03392 * cos(Math.toRadians(lat)) / Math.pow(2.0, zoom)

    /** Two-letter callsign-friendly short id for a coordinate pair. */
    fun shortId(prefix: String, lat: Double, lon: Double): String =
        prefix + ":" + "%.3f".format(lat) + "," + "%.3f".format(lon)
}
