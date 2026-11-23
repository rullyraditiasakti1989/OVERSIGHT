package com.vector.console.data.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NASA FIRMS area CSV. Column order has changed twice in the service's life and an
 * invalid key returns plain text with no header, so the parser reads the header and
 * refuses anything it cannot index.
 */
class FireParseTest {

    private val header =
        "latitude,longitude,bright_ti4,scan,track,acq_date,acq_time,satellite," +
            "instrument,confidence,version,bright_ti5,frp,daynight"

    @Test
    fun `record maps brightness, frp and acquisition`() {
        val body = header + "\n" +
            "-2.15,113.42,331.2,0.4,0.4,2026-11-21,0142,N,VIIRS,n,2.0NRT,295.1,12.4,D"
        val c = GlobeFeeds.parseFires(body).single()
        assertEquals(LayerId.FIRES, c.layer)
        assertEquals(-2.15, c.lat, 1e-9)
        assertEquals(113.42, c.lon, 1e-9)
        assertEquals("331 K", c.title)
        assertEquals("N · 2026-11-21", c.subtitle)
        assertEquals("331.2 K", field(c, "BRIGHTNESS"))
        assertEquals("12.4 MW", field(c, "FRP"))
        assertEquals("n", field(c, "CONFIDENCE"))
    }

    @Test
    fun `high fire radiative power marks the contact`() {
        val body = header + "\n-2.15,113.42,331.2,0.4,0.4,2026-11-21,0142,N,VIIRS,n,2.0NRT,295.1,88.0,D"
        assertTrue(GlobeFeeds.parseFires(body).single().severity)
    }

    @Test
    fun `low fire radiative power does not mark the contact`() {
        val body = header + "\n-2.15,113.42,331.2,0.4,0.4,2026-11-21,0142,N,VIIRS,n,2.0NRT,295.1,3.0,D"
        assertEquals(false, GlobeFeeds.parseFires(body).single().severity)
    }

    @Test
    fun `column order is read from the header, not assumed`() {
        val reordered =
            "frp,longitude,latitude,acq_date,acq_time,satellite,bright_ti4,confidence\n" +
                "9.9,110.0,-7.0,2026-11-21,0300,T,300.0,h"
        val c = GlobeFeeds.parseFires(reordered).single()
        assertEquals(-7.0, c.lat, 1e-9)
        assertEquals(110.0, c.lon, 1e-9)
        assertEquals("9.9 MW", field(c, "FRP"))
        assertEquals("T", field(c, "SATELLITE"))
    }

    @Test
    fun `plain text error response parses to nothing`() {
        assertTrue(GlobeFeeds.parseFires("Invalid MAP_KEY.").isEmpty())
        assertTrue(GlobeFeeds.parseFires("").isEmpty())
    }

    @Test
    fun `rows with unparseable coordinates are skipped`() {
        val body = header + "\n" +
            "nope,113.42,331.2,0.4,0.4,2026-11-21,0142,N,VIIRS,n,2.0NRT,295.1,12.4,D\n" +
            "-2.15,113.42,331.2,0.4,0.4,2026-11-21,0142,N,VIIRS,n,2.0NRT,295.1,12.4,D"
        assertEquals(1, GlobeFeeds.parseFires(body).size)
    }

    @Test
    fun `missing frp renders as a dash`() {
        val body = "latitude,longitude,bright_ti4,acq_date\n-2.15,113.42,331.2,2026-11-21"
        val c = GlobeFeeds.parseFires(body).single()
        assertEquals("--", field(c, "FRP"))
        assertEquals(false, c.severity)
    }

    private fun field(contact: Contact, label: String): String =
        contact.fields.first { it.label == label }.value
}
