package com.vector.console.data.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * USGS GeoJSON. The interesting cases are all in `properties`: `mag` is null for
 * unsolved events, `tsunami` is 0/1 rather than a boolean, and the coordinate
 * order is longitude-first.
 */
class QuakeParseTest {

    private fun feature(
        id: String = "us7000abcd",
        mag: String = "5.4",
        lon: String = "95.12",
        lat: String = "3.21",
        depth: String = "35.0",
        place: String = "10 km SW of Banda Aceh, Indonesia",
        tsunami: String = "0",
        time: String = "1784462400000"
    ) = """
        {"type":"Feature","id":"$id","properties":{"mag":$mag,"place":"$place",
        "time":$time,"tsunami":$tsunami,"alert":null,"sig":451,"magType":"mww",
        "status":"reviewed"},"geometry":{"type":"Point","coordinates":[$lon,$lat,$depth]}}
    """.trimIndent()

    @Test
    fun `record maps magnitude, depth and place`() {
        val body = """{"type":"FeatureCollection","features":[${feature()}]}"""
        val c = GlobeFeeds.parseQuakes(body).single()
        assertEquals("quakes:us7000abcd", c.id)
        assertEquals("M 5.4", c.title)
        assertEquals("10 km SW of Banda Aceh, Indonesia", c.subtitle)
        assertEquals(3.21, c.lat, 1e-9)
        assertEquals(95.12, c.lon, 1e-9)
        assertEquals("5.4", field(c, "MAGNITUDE"))
        assertEquals("35.0 KM", field(c, "DEPTH"))
        assertEquals("NO", field(c, "TSUNAMI"))
        assertEquals("REVIEWED", field(c, "STATUS"))
    }

    @Test
    fun `null magnitude is kept with a dash and not marked severe`() {
        val body = """{"features":[${feature(mag = "null")}]}"""
        val c = GlobeFeeds.parseQuakes(body).single()
        assertEquals("M --", c.title)
        assertEquals("--", field(c, "MAGNITUDE"))
        assertFalse(c.severity)
    }

    @Test
    fun `magnitude five and above carries severity`() {
        val body = """{"features":[${feature(mag = "5.0")}]}"""
        assertTrue(GlobeFeeds.parseQuakes(body).single().severity)
    }

    @Test
    fun `magnitude below five does not carry severity`() {
        val body = """{"features":[${feature(mag = "4.9")}]}"""
        assertFalse(GlobeFeeds.parseQuakes(body).single().severity)
    }

    @Test
    fun `tsunami flag is read from the numeric field`() {
        val body = """{"features":[${feature(tsunami = "1")}]}"""
        assertEquals("FLAGGED", field(GlobeFeeds.parseQuakes(body).single(), "TSUNAMI"))
    }

    @Test
    fun `feature without geometry is dropped`() {
        val body = """
            {"features":[{"id":"x","properties":{"mag":2.1}}]}
        """.trimIndent()
        assertTrue(GlobeFeeds.parseQuakes(body).isEmpty())
    }

    @Test
    fun `feature with short coordinates is dropped`() {
        val body = """
            {"features":[{"id":"x","properties":{"mag":2.1},
            "geometry":{"coordinates":[95.0]}}]}
        """.trimIndent()
        assertTrue(GlobeFeeds.parseQuakes(body).isEmpty())
    }

    @Test
    fun `missing place falls back rather than leaving a blank row`() {
        val body = """{"features":[${feature(place = "")}]}"""
        assertEquals("location unresolved", GlobeFeeds.parseQuakes(body).single().subtitle)
    }

    @Test
    fun `malformed document is empty`() {
        assertTrue(GlobeFeeds.parseQuakes("").isEmpty())
        assertTrue(GlobeFeeds.parseQuakes("""{"features":[{"id""").isEmpty())
    }

    private fun field(contact: Contact, label: String): String =
        contact.fields.first { it.label == label }.value
}
