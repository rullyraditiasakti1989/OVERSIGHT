package com.vector.console.data.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The snapshot types, the satellite join, and the credits surface. */
class GlobeLayerTest {

    private val iss = TleRecord(
        "ISS (ZARYA)",
        "1 25544U 98067A   08264.51782528 -.00002182  00000-0 -11606-4 0  2927",
        "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.72125391563537"
    )
    private val geo = TleRecord(
        "GEO-TEST",
        "1 28884U 05041A   26200.50000000 -.00000200  00000-0  00000+0 0  9993",
        "2 28884   0.0200  95.0000 0003000 100.0000 260.0000  1.00270000 10000"
    )

    @Test
    fun `snapshot totals and accessors agree`() {
        val snapshot = GlobeSnapshot(
            layers = mapOf(
                LayerId.AIRCRAFT to LayerSnapshot(
                    LayerId.AIRCRAFT,
                    listOf(contact("aircraft:a", LayerId.AIRCRAFT), contact("aircraft:b", LayerId.AIRCRAFT)),
                    fetchedAtMillis = 1L,
                    stale = false
                ),
                LayerId.QUAKES to LayerSnapshot(
                    LayerId.QUAKES, listOf(contact("quakes:q", LayerId.QUAKES)),
                    fetchedAtMillis = 1L, stale = true
                )
            ),
            enabled = setOf(LayerId.AIRCRAFT)
        )
        assertEquals(3, snapshot.total)
        assertEquals(2, snapshot.of(LayerId.AIRCRAFT)?.count)
        assertEquals(1, snapshot.contacts(LayerId.QUAKES).size)
        assertTrue(snapshot.of(LayerId.QUAKES)!!.stale)
        assertTrue(snapshot.contacts(LayerId.FIRES).isEmpty())
        assertEquals(0, GlobeSnapshot.EMPTY.total)
    }

    @Test
    fun `layer ids are ordered and only two accept an optional key`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), LayerId.entries.map { it.index })
        assertEquals(
            listOf(LayerId.FIRES, LayerId.VESSELS),
            LayerId.entries.filter { it.optionalKey }
        )
    }

    @Test
    fun `propagation places a satellite and fills the card`() {
        val at = 1784462400000L
        val contacts = GlobeFeeds.propagateAll(listOf(iss, geo), at)
        assertEquals(2, contacts.size)

        val issContact = contacts.first { it.title == "ISS (ZARYA)" }
        assertEquals("satellites:25544", issContact.id)
        assertEquals(LayerId.SATELLITES, issContact.layer)
        assertTrue("latitude in range", issContact.lat in -90.0..90.0)
        assertTrue("longitude in range", issContact.lon in -180.0..180.0)
        // A 51.6 degree inclination LEO cannot exceed 51.7 degrees of latitude.
        assertTrue("LEO latitude bound", kotlin.math.abs(issContact.lat) <= 51.7)
        assertEquals("SGP4", field(issContact, "MODEL"))
        assertEquals("25544", field(issContact, "CATALOG"))
        assertTrue(field(issContact, "ALTITUDE").endsWith(" KM"))

        val geoContact = contacts.first { it.title == "GEO-TEST" }
        assertEquals("SDP4", field(geoContact, "MODEL"))
        // Geostationary: 35,786 km up and essentially on the equator.
        assertTrue("GEO altitude", kotlin.math.abs(field(geoContact, "ALTITUDE").dropLast(3).toDouble() - 35786.0) < 60.0)
        assertTrue("GEO latitude", kotlin.math.abs(geoContact.lat) < 1.0)
    }

    @Test
    fun `a broken record is skipped without shifting the others`() {
        val broken = TleRecord("BROKEN", iss.line1, "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.72125XXX 10000")
        val contacts = GlobeFeeds.propagateAll(listOf(broken, iss), 1784462400000L)
        assertEquals(1, contacts.size)
        assertEquals("ISS (ZARYA)", contacts[0].title)
    }

    @Test
    fun `an empty catalogue yields no contacts`() {
        assertTrue(GlobeFeeds.propagateAll(emptyList(), 1784462400000L).isEmpty())
    }

    @Test
    fun `vessels parse positions, skip null geometry, and read nav status`() {
        val body = """
            {"type":"FeatureCollection","features":[
              {"mmsi":231930000,"type":"Feature","geometry":{"type":"Point",
               "coordinates":[21.431432,61.131403]},
               "properties":{"mmsi":231930000,"sog":0.0,"cog":36.0,"navStat":5}},
              {"mmsi":230000001,"type":"Feature","geometry":null,
               "properties":{"mmsi":230000001}},
              {"mmsi":230000002,"type":"Feature","geometry":{"type":"Point",
               "coordinates":[24.9,60.17]},
               "properties":{"mmsi":230000002,"sog":11.4,"cog":8.0,"navStat":0}}
            ]}
        """.trimIndent()
        val contacts = GlobeFeeds.parseVessels(body)
        assertEquals(2, contacts.size)
        assertEquals("vessels:231930000", contacts[0].id)
        assertEquals("MMSI 231930000", contacts[0].title)
        assertTrue(contacts[0].fields.any { it.value == "MOORED" })
        assertTrue(contacts[1].subtitle.contains("11.4"))
        assertTrue(contacts[1].fields.any { it.value == "UNDER WAY · ENGINE" })
        assertEquals(LayerId.VESSELS, contacts[0].layer)
    }

    @Test
    fun `vessels with no key still answer with a snapshot`() {
        // Keyless by design now: the fetcher takes no key at all, so this test guards
        // the signature every caller depends on rather than a network path.
        val snapshot = com.vector.console.data.globe.LayerSnapshot(
            layer = LayerId.VESSELS,
            contacts = GlobeFeeds.parseVessels("""{"features":[]}"""),
            fetchedAtMillis = 42L,
            stale = false,
            note = "no vessels reported"
        )
        assertEquals(0, snapshot.count)
        assertEquals(42L, snapshot.fetchedAtMillis)
        assertNotNull(snapshot.note)
    }

    @Test
    fun `credits cover every layer source and keep the canvas line`() {
        assertTrue(GlobeCredits.CANVAS.contains("Esri"))
        assertTrue(GlobeCredits.CANVAS.contains("Maxar"))
        val names = GlobeCredits.ALL.map { it.source }
        for (required in listOf(
            "adsb.lol", "CelesTrak", "USGS Earthquake Hazards", "NASA FIRMS",
            "Digitraffic AIS", "KAIStream", "MapLibre GL JS"
        )) {
            assertTrue("missing credit for $required", names.contains(required))
        }
        assertEquals(2, GlobeCredits.REQUIRES_KEY.size)
    }

    @Test
    fun `json reader refuses truncated documents`() {
        assertNull(Json.parse("""{"a":1"""))
        assertNull(Json.parse(""))
        assertNull(Json.parse("[] trailing"))
        val parsed = Json.parse("""{"a":[1,2.5,"x",true,null]}""")
        assertNotNull(parsed)
        assertEquals(5, parsed!!.arr("a")!!.items.size)
        assertEquals(2.5, parsed.arr("a")!!.items[1].let { (it as Json.Num).value }, 1e-12)
        assertEquals("x", (parsed.arr("a")!!.items[2] as Json.Str).value)
    }

    @Test
    fun `json reader handles escapes and unicode`() {
        val parsed = Json.parse("""{"s":"a\"b\\c\/d\n\u00e9"}""")
        assertEquals("a\"b\\c/d\n\u00e9", parsed.str("s"))
    }

    @Test
    fun `json numeric accessor accepts a quoted number`() {
        val parsed = Json.parse("""{"a":"12.5","b":12.5,"c":"nope"}""")
        assertEquals(12.5, parsed.numOrString("a")!!, 1e-12)
        assertEquals(12.5, parsed.numOrString("b")!!, 1e-12)
        assertNull(parsed.numOrString("c"))
    }

    @Test
    fun `a failed refresh keeps the last good contacts`() {
        val previous = LayerSnapshot(
            LayerId.AIRCRAFT,
            listOf(contact("aircraft:a", LayerId.AIRCRAFT)),
            fetchedAtMillis = 1_000L,
            stale = false
        )
        // What every fetcher returns on a network error.
        val failed = LayerSnapshot(LayerId.AIRCRAFT, emptyList(), 2_000L, stale = true, note = "timeout")

        val merged = failed.withPrevious(previous)
        assertEquals(1, merged.count)
        assertEquals("aircraft:a", merged.contacts.single().id)
        assertTrue("still marked stale", merged.stale)
        assertEquals("timeout", merged.note)
        assertEquals("the stamp stays honest", 2_000L, merged.fetchedAtMillis)
    }

    @Test
    fun `carry forward does not overwrite fresher data`() {
        val previous = LayerSnapshot(
            LayerId.QUAKES,
            listOf(contact("quakes:old", LayerId.QUAKES)),
            fetchedAtMillis = 1_000L,
            stale = false
        )
        val fresh = LayerSnapshot(
            LayerId.QUAKES,
            listOf(contact("quakes:new", LayerId.QUAKES)),
            fetchedAtMillis = 2_000L,
            stale = false
        )
        assertEquals("quakes:new", fresh.withPrevious(previous).contacts.single().id)

        // A stale snapshot that still carries contacts is newer than the plate.
        val partial = LayerSnapshot(
            LayerId.QUAKES,
            listOf(contact("quakes:partial", LayerId.QUAKES)),
            fetchedAtMillis = 3_000L,
            stale = true
        )
        assertEquals("quakes:partial", partial.withPrevious(previous).contacts.single().id)
    }

    @Test
    fun `carry forward with nothing held stays empty`() {
        val failed = LayerSnapshot(LayerId.FIRES, emptyList(), 5L, stale = true, note = "no key")
        assertTrue(failed.withPrevious(null).contacts.isEmpty())
        assertTrue(failed.withPrevious(LayerSnapshot(LayerId.FIRES, emptyList(), 4L, true)).contacts.isEmpty())
        assertEquals("no key", failed.withPrevious(null).note)
    }

    private fun contact(id: String, layer: LayerId) =
        Contact(id = id, layer = layer, lat = 0.0, lon = 0.0, title = id, subtitle = "")

    private fun field(contact: Contact, label: String): String =
        contact.fields.first { it.label == label }.value
}
