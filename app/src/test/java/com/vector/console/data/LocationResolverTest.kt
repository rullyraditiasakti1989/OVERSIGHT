package com.vector.console.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * beaconDB returns a Google-shaped payload. These lock the two behaviours that
 * matter: an IP-only fallback is not a position, and garbage never yields a fix.
 */
class LocationResolverTest {

    private val goodWifi = """
        {"location":{"lat":-6.2375,"lng":106.696},"accuracy":32.0}
    """.trimIndent()

    private val ipFallback = """
        {"location":{"lat":-6.2375,"lng":106.696},"accuracy":25000.0,"fallback":"ipf"}
    """.trimIndent()

    @Test
    fun `radio backed fix parses`() {
        val fix = LocationResolver.parse(goodWifi, radioBacked = true)
        assertEquals(-6.2375, fix!!.lat, 1e-6)
        assertEquals(106.696, fix.lon, 1e-6)
        assertEquals(32.0, fix.accuracyM, 1e-6)
        assertEquals("RADIO", fix.method)
    }

    @Test
    fun `ip fallback is rejected when not radio backed`() {
        assertNull(LocationResolver.parse(ipFallback, radioBacked = false))
    }

    @Test
    fun `ip fallback is rejected even when radio backed if upstream says ipf`() {
        // Nothing in the request should turn a 25 km guess into a track point.
        val fix = LocationResolver.parse(ipFallback, radioBacked = true)
        assertEquals("IP", fix!!.method)
        assertFalse(LocationResolver.isUsable(fix))
    }

    @Test
    fun `out of range coordinates are refused`() {
        assertNull(LocationResolver.parse("""{"location":{"lat":999,"lng":0}}""", true))
        assertNull(LocationResolver.parse("""{"location":{"lat":0,"lng":-200}}""", true))
    }

    @Test
    fun `malformed body yields null not a throw`() {
        assertNull(LocationResolver.parse("not json", true))
        assertNull(LocationResolver.parse("{}", true))
        assertNull(LocationResolver.parse("""{"error":{"code":404}}""", true))
    }

    @Test
    fun `bssid normalisation drops junk and keeps strongest`() {
        val input = listOf(
            WifiObservation("AA-BB-CC-DD-EE-FF", -80),
            WifiObservation("aa:bb:cc:dd:ee:ff", -55),
            WifiObservation("garbage", -40),
            WifiObservation("11:22:33:44:55:66", -70),
        )
        val out = LocationResolver.normaliseBssids(input)
        assertEquals(2, out.size)
        assertEquals("aa:bb:cc:dd:ee:ff", out.first().bssid)
        assertEquals(-55, out.first().signalDbm)
    }

    @Test
    fun `bssid list is capped at twenty`() {
        val many = (0 until 40).map {
            WifiObservation("aa:bb:cc:dd:ee:" + it.toString(16).padStart(2, '0'), -50 - it)
        }
        assertEquals(20, LocationResolver.normaliseBssids(many).size)
    }

    @Test
    fun `usable rejects coarse and zero accuracy`() {
        assertTrue(LocationResolver.isUsable(ResolvedFix(-6.2, 106.8, 45.0, "RADIO")))
        assertFalse(LocationResolver.isUsable(ResolvedFix(-6.2, 106.8, 0.0, "RADIO")))
        assertFalse(LocationResolver.isUsable(ResolvedFix(-6.2, 106.8, 25_000.0, "IP")))
        assertFalse(LocationResolver.isUsable(null))
    }
}
