package com.vector.console.data.globe

/**
 * GeoJSON for the renderer.
 *
 * Hand-built rather than via `org.json` for the same reason the parsers are:
 * the output has to be assertable on the JVM test classpath. A contact that
 * cannot be drawn (a non-finite coordinate) is skipped instead of emitting
 * `NaN`, which is not valid JSON and makes MapLibre throw while it parses.
 */
object GlobeGeoJson {

    /** Property keys the renderer reads. */
    const val P_ID = "__id"
    const val P_LAYER = "__layer"
    const val P_SEVERE = "__sev"
    const val P_TITLE = "__title"

    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

    /**
     * A point FeatureCollection for one layer.
     *
     * GeoJSON is longitude-first. Getting that backwards puts Indonesia in the
     * Atlantic and looks plausible enough on a globe to survive a casual check.
     */
    fun featureCollection(contacts: List<Contact>): String {
        if (contacts.isEmpty()) return EMPTY
        val out = StringBuilder(contacts.size * 160)
        out.append("""{"type":"FeatureCollection","features":[""")
        var first = true
        for (contact in contacts) {
            if (!contact.lat.isFinite() || !contact.lon.isFinite()) continue
            if (contact.lat < -90.0 || contact.lat > 90.0) continue
            if (contact.lon < -180.0 || contact.lon > 180.0) continue
            if (!first) out.append(',')
            first = false
            out.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[""")
            out.append(num(contact.lon)).append(',').append(num(contact.lat))
            out.append("]},\"properties\":{")
            out.append(quote(P_ID)).append(':').append(quote(contact.id)).append(',')
            out.append(quote(P_LAYER)).append(':').append(quote(contact.layer.key)).append(',')
            out.append(quote(P_SEVERE)).append(':').append(contact.severity).append(',')
            out.append(quote(P_TITLE)).append(':').append(quote(contact.title))
            out.append("}}")
        }
        out.append("]}")
        return if (first) EMPTY else out.toString()
    }

    /**
     * Five decimal places is 1.1 m — past the resolution of every source here and
     * well past what a phone screen can show, while keeping the payload small
     * enough that 800 satellites cross the bridge in one message.
     */
    private fun num(value: Double): String {
        val rounded = Math.round(value * 100_000.0) / 100_000.0
        val text = rounded.toString()
        // `1.0E-5` is legal JSON but pointless for a coordinate; force plain
        // notation for small magnitudes.
        return if (text.contains('E') || text.contains('e')) {
            String.format(java.util.Locale.US, "%.5f", rounded)
        } else {
            text
        }
    }

    /** JSON string escaping, including the control range. */
    private fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (c in value) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append(String.format(java.util.Locale.US, "\\u%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }
}
