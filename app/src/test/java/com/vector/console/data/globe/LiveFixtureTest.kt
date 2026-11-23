package com.vector.console.data.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recorded payloads, fetched live on 2026-11-22 with the app's own User-Agent.
 *
 * The synthetic cases in the other test classes cover the shapes I predicted; this
 * one covers what the services actually sent. It caught two things the prediction
 * missed: the military feed names the aircraft type `type` rather than `t`, and 41
 * of its 70 contacts carry no position at all.
 */
class LiveFixtureTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/globe/$name")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("missing fixture $name")

    @Test
    fun `live military feed parses and drops the unpositioned majority`() {
        val contacts = GlobeFeeds.parseAircraft(fixture("adsb-mil.json"))
        // 70 in the payload, 41 without lat/lon.
        assertTrue("expected the positioned minority", contacts.size in 20..40)
        assertTrue(contacts.all { it.layer == LayerId.AIRCRAFT })
        assertEquals(contacts.size, contacts.map { it.id }.toSet().size)
        assertTrue(contacts.all { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 })
        // At least one surface contact and one airborne one.
        assertTrue(contacts.any { field(it, "ALT") == "GROUND" })
        assertTrue(contacts.any { field(it, "ALT").endsWith(" FT") })
        assertTrue(contacts.all { it.title.isNotBlank() })
    }

    @Test
    fun `live quake feed parses every feature and reads newest first`() {
        val contacts = GlobeFeeds.parseQuakes(fixture("usgs-day.geojson"))
        assertEquals(40, contacts.size)
        assertTrue(contacts.all { it.layer == LayerId.QUAKES })
        assertTrue(contacts.all { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 })
        assertTrue(contacts.all { it.title.startsWith("M ") })
        // The upstream orders newest-first; the ledger must not reorder it.
        val stamps = contacts.map { field(it, "TIME") }
        assertEquals(stamps.sortedDescending(), stamps)
        // Severity tracks the magnitude exactly, never the significance score.
        for (c in contacts) {
            val mag = field(c, "MAGNITUDE")
            val severe = mag != "--" && mag.toDouble() >= 5.0
            assertEquals(c.title, severe, c.severity)
        }
    }

    @Test
    fun `live element sets parse and propagate`() {
        val records = TleStore.parseTle(fixture("celestrak-stations.tle"))
        assertTrue("expected the crewed stations", records.size >= 8)
        assertTrue(records.all { it.name.isNotBlank() })
        assertTrue(records.all { it.line1.startsWith("1 ") && it.line2.startsWith("2 ") })

        val contacts = GlobeFeeds.propagateAll(records, 1784462400000L)
        // Two of the thirteen decay under backward propagation from this epoch —
        // satellite.js reports error 6 for exactly the same two, so this count is a
        // cross-check on the port, not a tolerance.
        assertEquals(records.size - 2, contacts.size)
        assertTrue("decayed records must be dropped", contacts.none { it.title == "SZ-21 MODULE" })
        assertTrue(contacts.all { it.lat in -90.0..90.0 })
        assertTrue(contacts.all { field(it, "CATALOG").isNotBlank() })
        assertTrue(contacts.any { it.title.contains("ISS") })
        // Stations are all LEO: nothing above 2,000 km.
        assertTrue(contacts.all {
            field(it, "ALTITUDE").dropLast(3).toDouble() < 2000.0
        })
    }

    private fun field(contact: Contact, label: String): String =
        contact.fields.first { it.label == label }.value
}
