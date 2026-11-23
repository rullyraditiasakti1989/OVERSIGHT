package com.vector.console.data.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schedule maths, parsers and the stamp seam for F6 — RAIL. */
class RailLayerTest {

    private val stations: Map<String, RailStation> = RailModel.parseStations(
        """
        [
          {"c":"TPK","n":"Tanjung Priok","y":-6.10,"x":106.83},
          {"c":"KPB","n":"Kampung Bandan","y":-6.15,"x":106.90},
          {"c":"JAKK","n":"Jakarta Kota","y":-6.20,"x":106.80}
        ]
        """.trimIndent()
    )

    private val trip: RailTrip = RailTrip(
        id = "krl_2263",
        number = "2263",
        name = "Commuter Line Tanjungpriuk",
        category = "commuter",
        originCode = "TPK",
        destCode = "JAKK",
        startSec = 7200,
        endSec = 7400,
        totalDistKm = 8.09,
        stops = listOf(
            RailStop("TPK", null, 7200, 0.0),
            RailStop("KPB", 7260, 7300, 3.0),
            RailStop("JAKK", 7400, null, 8.09)
        )
    )

    private val timetable = RailTimetable(
        listOf(trip),
        RailModel.parseMeta(METADATA),
        parseMillis = 12L
    )

    /** Epoch millis whose WIB wall clock is [seconds]. */
    private fun at(seconds: Int): Long =
        (Math.floorMod(seconds - RailModel.WIB_OFFSET_SECONDS, 86_400).toLong()) * 1000L

    @Test
    fun `seconds convert once at the WIB boundary`() {
        assertEquals(20 * 3600 + 30 * 60, RailModel.wibSeconds(at(20 * 3600 + 30 * 60)))
        assertEquals("20:30:00", RailModel.wibClock(20 * 3600 + 30 * 60))
        assertEquals("01:00:00", RailModel.wibClock(25 * 3600))
        assertEquals("00:00:00", RailModel.wibClock(86_400))
    }

    @Test
    fun `a train between stops is interpolated in distance and coordinates`() {
        val pos = RailModel.positionAt(trip, stations, 7240)!!
        assertEquals("EN ROUTE", pos.state)
        assertEquals("Tanjung Priok", pos.fromName)
        assertEquals("Kampung Bandan", pos.toName)
        assertEquals(-6.13333, pos.lat, 1e-4)
        assertEquals(106.87667, pos.lon, 1e-4)
        assertEquals(2.0, pos.distKm, 1e-6)
        // 2.0 km into 8.09 km is 24.7% of the run.
        assertEquals(24, (pos.progress * 100).toInt())
    }

    @Test
    fun `a dwell holds the train at the station instead of sliding it down the line`() {
        val pos = RailModel.positionAt(trip, stations, 7275)!!
        assertEquals("DWELL · Kampung Bandan", pos.state)
        assertEquals(-6.15, pos.lat, 1e-9)
        assertEquals(106.90, pos.lon, 1e-9)
        assertEquals("Kampung Bandan", pos.fromName)
        assertEquals("Kampung Bandan", pos.toName)
    }

    @Test
    fun `origin and terminus null edges still resolve`() {
        val origin = RailModel.positionAt(trip, stations, 7200)!!
        assertEquals("Tanjung Priok", origin.fromName)
        assertEquals(0.0, origin.distKm, 1e-9)
        val terminus = RailModel.positionAt(trip, stations, 7400)!!
        assertEquals("Jakarta Kota", terminus.toName)
        assertEquals(8.09, terminus.distKm, 1e-9)
        assertEquals(1.0, terminus.progress, 1e-9)
    }

    @Test
    fun `a run outside its window is not drawn`() {
        assertNull(RailModel.positionAt(trip, stations, 7199))
        assertNull(RailModel.positionAt(trip, stations, 7401))
    }

    @Test
    fun `a missing station join drops the leg instead of inventing a point`() {
        val partial = stations - "KPB"
        assertNull(RailModel.positionAt(trip, partial, 7240))
        // The origin belongs to that same unjoinable leg, so it drops too. No
        // fallback point is invented: a broken join is a missing contact.
        assertNull(RailModel.positionAt(trip, partial, 7200))

        val result = RailModel.positions(timetable, partial, at(7240))
        assertTrue(result.contacts.isEmpty())
        assertEquals(1, result.droppedJoins)
    }

    @Test
    fun `positions build a card and cap the drawing count`() {
        val result = RailModel.positions(timetable, stations, at(7240))
        assertEquals(1, result.contacts.size)
        val contact = result.contacts.first()
        assertEquals(LayerId.RAIL, contact.layer)
        assertEquals("rail:krl_2263", contact.id)
        assertEquals("2263 · Commuter Line Tanjungpriuk", contact.title)
        assertEquals("COMMUTER", field(contact, "CATEGORY"))
        assertEquals("GAPEKA 2026", field(contact, "SCHEDULE"))
        assertTrue(field(contact, "LOCAL CLOCK").endsWith("WIB"))
        assertTrue(contact.subtitle.contains("EN ROUTE"))

        val capped = RailModel.positions(
            timetable.copy(trips = List(5) { trip.copy(id = "trip$it") }),
            stations, at(7240), cap = 2
        )
        assertEquals(2, capped.contacts.size)
    }

    @Test
    fun `compact timetable round-trips and rejects a different snapshot`() {
        val bytes = RailModel.encodeTimetable(listOf(trip), "2026-11-20")
        val decoded = RailModel.decodeTimetable(bytes, "2026-11-20")!!
        assertEquals(1, decoded.size)
        assertEquals(trip, decoded.first())
        assertNull(RailModel.decodeTimetable(bytes, "2026-11-21"))
        assertNull(RailModel.decodeTimetable(byteArrayOf(1, 2, 3), "2026-11-20"))
    }

    @Test
    fun `bundled stations parse from the trimmed three-key shape`() {
        assertEquals(3, stations.size)
        assertEquals("Jakarta Kota", stations.getValue("JAKK").name)
        assertEquals(106.8, stations.getValue("JAKK").lon, 1e-9)
    }

    @Test
    fun `upstream timetable shape parses and malformed rows are skipped`() {
        val trips = RailModel.parseTrips(
            """
            [
              {"id":"krl_2263","number":"2263","name":"Commuter Line Tanjungpriuk",
               "category":"commuter","operatingPattern":"daily","shapeId":"shp_x",
               "originCode":"TPK","destCode":"JAKK","startSec":73800,"endSec":74760,
               "totalDistKm":8.09,
               "stops":[{"stationCode":"TPK","arrSec":null,"depSec":73800,"distKm":0.0},
                        {"stationCode":"KPB","arrSec":74495,"depSec":74520,"distKm":6.4311},
                        {"stationCode":"JAKK","arrSec":74760,"depSec":null,"distKm":8.0852}],
               "passPoints":[[73800,0.0],[74760,8.0852]]},
              {"id":"broken","number":"0","name":"no stops","startSec":1,"endSec":2,
               "totalDistKm":0,"stops":[{"stationCode":"TPK","distKm":0.0}]}
            ]
            """.trimIndent()
        )
        assertEquals(1, trips.size)
        assertEquals(3, trips[0].stops.size)
        assertNull(trips[0].stops[0].arrSec)
        assertNull(trips[0].stops[2].depSec)
        assertEquals(20 * 3600 + 30 * 60, trips[0].startSec)
    }

    @Test
    fun `only active alerts reach the ledger and severity drives the seal`() {
        val all = RailModel.parseAlerts(ALERTS, activeOnly = false)
        assertEquals(2, all.size)
        val activeOnly = RailModel.parseAlerts(ALERTS, activeOnly = true)
        assertEquals(1, activeOnly.size)
        val alert = activeOnly.first()
        assertEquals("alert-1687", alert.id)
        assertEquals("warning", alert.severity)
        assertTrue(alert.description.contains("turbo"))

        assertTrue(ledger(alert.copy(severity = "warning")).marked)
        assertTrue(ledger(alert.copy(severity = "emergency")).marked)
        assertFalse(ledger(alert.copy(severity = "info")).marked)
    }

    @Test
    fun `metadata derives the stamp year from the published decree`() {
        val meta = RailModel.parseMeta(METADATA)
        assertEquals("GAPEKA 2026", meta.decree)
        assertEquals("2026-11-20", meta.snapshotDate)
        assertTrue(meta.decreeFull.contains("KP-DJKA"))
    }

    @Test
    fun `a stale snapshot carries alerts forward with the last contacts`() {
        val previous = LayerSnapshot(
            layer = LayerId.RAIL,
            contacts = listOf(resultContact()),
            fetchedAtMillis = 1L,
            stale = false,
            alerts = listOf(ledger(RailModel.parseAlerts(ALERTS).first()))
        )
        val fresh = LayerSnapshot(LayerId.RAIL, emptyList(), 2L, stale = true)
        val held = fresh.withPrevious(previous)
        assertEquals(1, held.count)
        assertEquals(1, held.alerts.size)
        assertEquals("alert-1687", held.alerts.first().id)
    }

    @Test
    fun `rail is keyed as a globe source and never claims a live position`() {
        assertEquals("rail", LayerId.RAIL.key)
        assertEquals(6, LayerId.RAIL.index)
        val result = RailModel.positions(timetable, stations, at(7240))
        val card = result.contacts.first()
        assertTrue(card.fields.any { it.label == "SCHEDULE" && it.value == "GAPEKA 2026" })
        assertFalse(card.title.contains("LIVE"))
        assertFalse(card.subtitle.contains("LIVE"))
    }

    private fun resultContact() = RailModel.positions(timetable, stations, at(7240)).contacts.first()

    private fun ledger(alert: RailAlert) = LedgerAlert(
        id = alert.id,
        title = alert.title,
        detail = alert.description,
        location = alert.location,
        severity = alert.severity,
        timestampWib = alert.timestampWib,
        source = alert.source
    )

    private fun field(contact: Contact, label: String): String =
        contact.fields.first { it.label == label }.value

    companion object {
        const val METADATA = """
            {"generatedAt":"2026-11-20T02:35:48.604Z",
             "scheduleSnapshotDate":"2026-11-20",
             "timezone":"Asia/Jakarta","utcOffsetHours":7,
             "coverage":"Pulau Jawa (6 Provinsi)",
             "officialDjkaDecree":"KP-DJKA 116 TAHUN 2026 (GAPEKA Jawa 2026), KP-DJKA 224 TAHUN 2024",
             "officialDecreeCatalog":[]}
        """

        const val ALERTS = """
            [
              {"id":"alert-1687","title":"KA 12 Turangga Gangguan Lokomotif",
               "description":"lokomotif dinas KA 12 Turangga terdapat kendala pada mesinnya berupa turbo tidak naik.",
               "location":"Stasiun Cicalengka - Nagreg, KM184+6",
               "category":"keterlambatan","severity":"warning",
               "timestampWib":"2026-11-20 18:18:18","active":true,
               "source":"Dzikri Raihan Akbar"},
              {"id":"alert-1688","title":"KA 23 Argo Bromo Anggrek",
               "description":"histori","location":"Gambir","category":"gangguan",
               "severity":"info","timestampWib":"2026-11-19 08:00:00","active":false,
               "source":"Dzikri Raihan Akbar"}
            ]
        """
    }
}
