package com.vector.console.data.globe

/** The layers the plate can carry. [index] matches the rail order. */
enum class LayerId(val key: String, val label: String, val index: Int) {
    AIRCRAFT("aircraft", "AIRCRAFT", 0),
    SATELLITES("satellites", "SATELLITES", 1),
    QUAKES("quakes", "QUAKES", 2),
    FIRES("fires", "FIRES", 3),
    CAMERAS("cameras", "CAMERAS", 4),
    VESSELS("vessels", "VESSELS", 5),
    RAIL("rail", "RAIL", 6);

    /** FIRMS and AIS accept an optional key; both also work without one. */
    val optionalKey: Boolean get() = this == FIRES || this == VESSELS
}

/** One field on the selection card. Order is fixed per layer by the fetcher. */
data class Field(val label: String, val value: String)

/**
 * A ledger row that does not have a position on the sphere.
 *
 * Rail alerts are the reason a train is not where the timetable says it is, so
 * they belong above the trains in the ledger — but they carry no coordinates and
 * must never be folded into the position model. This is the seam that keeps them
 * in the list and off the plate.
 */
data class LedgerAlert(
    val id: String,
    val title: String,
    val detail: String,
    val location: String,
    val severity: String,
    val timestampWib: String,
    val source: String
) {
    /** Only `warning` and `emergency` earn the seal ink; `info` stays neutral. */
    val marked: Boolean get() = severity.equals("warning", true) || severity.equals("emergency", true) ||
        severity.equals("critical", true)
}

/**
 * A single thing on the plate.
 *
 * [title] is the card header, [subtitle] the stamp line under it. [severity] is
 * only ever set by quakes, where M5.0+ earns the one permitted extra use of the
 * seal colour.
 */
data class Contact(
    val id: String,
    val layer: LayerId,
    val lat: Double,
    val lon: Double,
    val title: String,
    val subtitle: String,
    val fields: List<Field> = emptyList(),
    val severity: Boolean = false
)

/**
 * One layer's worth of contacts plus how fresh they are.
 *
 * [stale] is true when the network call failed and the disk cache answered, so
 * the screen can say so instead of pretending the data is live.
 */
data class LayerSnapshot(
    val layer: LayerId,
    val contacts: List<Contact>,
    val fetchedAtMillis: Long,
    val stale: Boolean,
    val note: String? = null,
    /** Bytes read from the source. Zero for bundled/local sources. */
    val bytes: Long = 0L,
    /** Positionless ledger rows, currently only active rail alerts. */
    val alerts: List<LedgerAlert> = emptyList()
) {
    val count: Int get() = contacts.size

    /**
     * Keeps the last good contacts when this snapshot came back stale.
     *
     * Every fetcher catches its own network error and reports an empty, stale
     * snapshot — correct for the feed, wrong for the plate. Dropping 18 aircraft
     * to `00` because one request timed out makes the layer look like the sky
     * emptied, when the honest reading is "this is what we last saw, and it may
     * be old". The stamp says CACHED; the contacts stay.
     *
     * A stale snapshot that still carries contacts is left alone: a partially
     * decoded response is newer than what is on the plate.
     */
    fun withPrevious(previous: LayerSnapshot?): LayerSnapshot {
        if (!stale) return this
        if (contacts.isNotEmpty()) return this
        val held = previous?.contacts.orEmpty()
        if (held.isEmpty()) return this
        val heldAlerts = if (alerts.isEmpty()) previous?.alerts.orEmpty() else alerts
        return copy(contacts = held, alerts = heldAlerts, note = note ?: previous?.note)
    }
}

/** A lat/lon box, [west] may exceed 180 when it wraps the antimeridian. */
data class Bbox(val south: Double, val west: Double, val north: Double, val east: Double) {
    val wraps: Boolean get() = east < west || east > 180.0
}

/** Everything the plate is currently drawing. */
data class GlobeSnapshot(
    val layers: Map<LayerId, LayerSnapshot>,
    val enabled: Set<LayerId>
) {
    fun of(layer: LayerId): LayerSnapshot? = layers[layer]
    fun contacts(layer: LayerId): List<Contact> = layers[layer]?.contacts.orEmpty()
    val total: Int get() = layers.values.sumOf { it.count }

    companion object {
        val EMPTY = GlobeSnapshot(emptyMap(), emptySet())
    }
}
