package com.vector.console.data.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coordinate maths shared by the fetchers and the renderer. Pure, so it is pinned
 * here rather than eyeballed on a 1080 px plate.
 */
class GlobeGeoTest {

    @Test
    fun `nautical miles and degrees round trip`() {
        assertEquals(1.0, GlobeGeo.nmToDegrees(60.0), 1e-12)
        assertEquals(60.0, GlobeGeo.degreesToNm(1.0), 1e-12)
        assertEquals(2.5, GlobeGeo.nmToDegrees(150.0), 1e-12)
    }

    @Test
    fun `snap down and up never round inward`() {
        assertEquals(51.25, GlobeGeo.snapDown(51.31), 1e-9)
        assertEquals(51.50, GlobeGeo.snapUp(51.31), 1e-9)
        // Southern hemisphere: the north edge is negative and must still go up.
        assertEquals(-32.25, GlobeGeo.snapDown(-32.10), 1e-9)
        assertEquals(-32.00, GlobeGeo.snapUp(-32.10), 1e-9)
    }

    @Test
    fun `box around a point covers the radius at the corners`() {
        // 120 NM at the equator is 2 degrees of latitude and longitude.
        val box = GlobeGeo.bboxFor(0.0, 0.0, 120.0)
        assertEquals(-2.0, box.south, 1e-9)
        assertEquals(2.0, box.north, 1e-9)
        assertEquals(-2.0, box.west, 1e-9)
        assertEquals(2.0, box.east, 1e-9)
        assertFalse(box.wraps)
    }

    @Test
    fun `box widens in longitude as latitude rises`() {
        val equator = GlobeGeo.bboxFor(0.0, 0.0, 120.0)
        val high = GlobeGeo.bboxFor(60.0, 0.0, 120.0)
        val equatorWidth = equator.east - equator.west
        val highWidth = high.east - high.west
        // cos(60°) = 0.5, so the box is roughly twice as wide.
        assertTrue("expected a wider box at 60N", highWidth > equatorWidth * 1.9)
        assertTrue("expected under 2.1x", highWidth < equatorWidth * 2.1)
    }

    @Test
    fun `box edges land on the quarter degree grid`() {
        val box = GlobeGeo.bboxFor(51.31, -0.13, 100.0)
        for (value in listOf(box.south, box.west, box.north, box.east)) {
            assertEquals(0.0, value * 4.0 - Math.round(value * 4.0).toDouble(), 1e-9)
        }
    }

    @Test
    fun `antimeridian wrap is reported and honoured`() {
        val box = GlobeGeo.bboxFor(0.0, 179.5, 120.0)
        assertTrue(box.wraps)
        assertTrue(GlobeGeo.contains(box, 0.0, 179.9))
        assertTrue(GlobeGeo.contains(box, 0.0, -179.9))
        assertFalse(GlobeGeo.contains(box, 0.0, 0.0))
    }

    @Test
    fun `contains respects the latitude band`() {
        val box = Bbox(south = -10.0, west = -10.0, north = 10.0, east = 10.0)
        assertTrue(GlobeGeo.contains(box, 0.0, 0.0))
        assertFalse(GlobeGeo.contains(box, 11.0, 0.0))
        assertFalse(GlobeGeo.contains(box, 0.0, 11.0))
    }

    @Test
    fun `longitude wraps into the signed range`() {
        assertEquals(-179.0, GlobeGeo.wrapLon(181.0), 1e-9)
        assertEquals(179.0, GlobeGeo.wrapLon(-181.0), 1e-9)
        assertEquals(0.0, GlobeGeo.wrapLon(360.0), 1e-9)
        assertEquals(180.0, GlobeGeo.wrapLon(180.0), 1e-9)
    }

    @Test
    fun `latitude and longitude format with hemisphere letters`() {
        assertEquals("51.5074 N", GlobeGeo.formatLat(51.5074))
        assertEquals("6.2088 S", GlobeGeo.formatLat(-6.2088))
        assertEquals("0.1278 W", GlobeGeo.formatLon(-0.1278))
        assertEquals("106.8456 E", GlobeGeo.formatLon(106.8456))
    }

    @Test
    fun `metres per pixel halves with each zoom level`() {
        val z0 = GlobeGeo.metresPerPixel(0.0, 0.0)
        val z1 = GlobeGeo.metresPerPixel(0.0, 1.0)
        assertEquals(156543.03392, z0, 1e-4)
        assertEquals(z0 / 2.0, z1, 1e-6)
        // At 60N the ground resolution is half the equatorial value.
        assertEquals(z0 / 2.0, GlobeGeo.metresPerPixel(60.0, 0.0), 1e-3)
    }

    @Test
    fun `short id is stable and rounded to three decimals`() {
        val a = GlobeGeo.shortId("ac", 51.50741, -0.12781)
        val b = GlobeGeo.shortId("ac", 51.50744, -0.12784)
        assertEquals(a, b)
        assertEquals("ac:51.507,-0.128", a)
    }

    @Test
    fun `haversine distance is sane on known pairs`() {
        // London to New York is about 3,000 NM.
        val d = GlobeFeeds.distanceNm(51.5074, -0.1278, 40.7128, -74.0060)
        assertEquals(3000.0, d, 40.0)
        assertEquals(0.0, GlobeFeeds.distanceNm(0.0, 0.0, 0.0, 0.0), 1e-9)
    }

    @Test
    fun `nearest sorts by distance and respects the limit`() {
        val contacts = listOf(
            contact("far", 40.0, 40.0),
            contact("near", 0.5, 0.5),
            contact("mid", 10.0, 10.0)
        )
        val sorted = GlobeFeeds.nearest(contacts, 0.0, 0.0, limit = 2)
        assertEquals(2, sorted.size)
        assertEquals("near", sorted[0].title)
        assertEquals("mid", sorted[1].title)
    }

    private fun contact(title: String, lat: Double, lon: Double) = Contact(
        id = title,
        layer = LayerId.AIRCRAFT,
        lat = lat,
        lon = lon,
        title = title,
        subtitle = ""
    )
}
