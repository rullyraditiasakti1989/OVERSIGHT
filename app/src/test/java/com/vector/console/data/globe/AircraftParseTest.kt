package com.vector.console.data.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * adsb.lol's `v2` payload is a flat `ac` array whose fields are optional in ways
 * the docs do not state: `alt_baro` is a number *or* the string `"ground"`,
 * `flight` is space-padded, and surface contacts arrive with no `lat`/`lon`.
 */
class AircraftParseTest {

    @Test
    fun `full record maps every field`() {
        val body = """
            {"ac":[{"hex":"a1b2c3","flight":"UAL123  ","alt_baro":34000,
            "gs":451.2,"track":87.4,"squawk":"1200","t":"B738","r":"N12345",
            "lat":51.5074,"lon":-0.1278,"category":"A3"}],"total":1}
        """.trimIndent()
        val contacts = GlobeFeeds.parseAircraft(body)
        assertEquals(1, contacts.size)
        val c = contacts[0]
        assertEquals("aircraft:a1b2c3", c.id)
        assertEquals(LayerId.AIRCRAFT, c.layer)
        assertEquals("UAL123", c.title)
        assertEquals(51.5074, c.lat, 1e-9)
        assertEquals(-0.1278, c.lon, 1e-9)
        assertEquals("B738", field(c, "TYPE"))
        assertEquals("N12345", field(c, "REG"))
        assertEquals("34000 FT", field(c, "ALT"))
        assertEquals("451 KT", field(c, "GS"))
        assertEquals("87°", field(c, "HDG"))
        assertEquals("1200", field(c, "SQUAWK"))
    }

    @Test
    fun `alt_baro ground string becomes a ground contact`() {
        val body = """{"ac":[{"hex":"abc123","alt_baro":"ground","lat":1.0,"lon":2.0}]}"""
        val c = GlobeFeeds.parseAircraft(body).single()
        assertEquals("GROUND", field(c, "ALT"))
        // No callsign: the hex stands in, upper-cased.
        assertEquals("ABC123", c.title)
    }

    @Test
    fun `contact without a position is dropped`() {
        val body = """{"ac":[{"hex":"abc123","flight":"NO POS"},{"hex":"def456","lat":3.0,"lon":4.0}]}"""
        val contacts = GlobeFeeds.parseAircraft(body)
        assertEquals(1, contacts.size)
        assertEquals("aircraft:def456", contacts[0].id)
    }

    @Test
    fun `out of range coordinates are dropped`() {
        val body = """{"ac":[{"hex":"bad","lat":95.0,"lon":10.0},{"hex":"bad2","lat":10.0,"lon":190.0}]}"""
        assertTrue(GlobeFeeds.parseAircraft(body).isEmpty())
    }

    @Test
    fun `alt_geom is labelled when baro is absent`() {
        val body = """{"ac":[{"hex":"abc123","alt_geom":12000,"lat":1.0,"lon":2.0}]}"""
        val c = GlobeFeeds.parseAircraft(body).single()
        assertEquals("12000 FT (GEOM)", field(c, "ALT"))
    }

    @Test
    fun `missing fields render as dashes rather than nulls`() {
        val body = """{"ac":[{"hex":"abc123","lat":1.0,"lon":2.0}]}"""
        val c = GlobeFeeds.parseAircraft(body).single()
        assertEquals("--", field(c, "TYPE"))
        assertEquals("--", field(c, "REG"))
        assertEquals("--", field(c, "GS"))
        assertEquals("--", field(c, "SQUAWK"))
    }

    @Test
    fun `empty ac array and malformed body are both empty`() {
        assertTrue(GlobeFeeds.parseAircraft("""{"ac":[],"total":0}""").isEmpty())
        assertTrue(GlobeFeeds.parseAircraft("""{"ac":[{"hex":"a"""").isEmpty())
        assertTrue(GlobeFeeds.parseAircraft("not json at all").isEmpty())
    }

    @Test
    fun `trailing spaces in flight are trimmed`() {
        val body = """{"ac":[{"hex":"abc123","flight":"  KLM88   ","lat":1.0,"lon":2.0}]}"""
        assertEquals("KLM88", GlobeFeeds.parseAircraft(body).single().title)
    }

    @Test
    fun `severity is never set for aircraft`() {
        val body = """{"ac":[{"hex":"abc123","lat":1.0,"lon":2.0}]}"""
        assertFalse(GlobeFeeds.parseAircraft(body).single().severity)
    }

    private fun field(contact: Contact, label: String): String =
        contact.fields.first { it.label == label }.value
}
