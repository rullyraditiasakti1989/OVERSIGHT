package com.vector.console.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The surface catalogue is the module a future `TrackKind` gets checked against, so the
 * filters have to mean exactly what they say. These lock the semantics down.
 */
class LocationSurfaceTest {

    @Test
    fun `every channel key is unique`() {
        val keys = LocationSurface.CHANNELS.map { it.key }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `byKey finds and misses cleanly`() {
        assertNotNull(LocationSurface.byKey("WIFI"))
        assertEquals(Surface.ON_DEVICE, LocationSurface.byKey("WIFI")!!.surface)
        assertNull(LocationSurface.byKey("WIFI2"))
    }

    @Test
    fun `keyless excludes everything that needs an account`() {
        val keyless = LocationSurface.keyless().map { it.key }
        assertTrue("WIFI", "WIFI" in keyless)
        assertTrue("CELL", "CELL" in keyless)
        assertTrue("GNSS", "GNSS" in keyless)
        assertFalse("FINDMY", "FINDMY" in keyless)
        assertFalse("HLR", "HLR" in keyless)
        assertFalse("AIS", "AIS" in keyless)
    }

    @Test
    fun `withoutCooperation is carrier and platform only`() {
        val keys = LocationSurface.withoutCooperation().map { it.key }
        assertTrue("HLR", "HLR" in keys)
        assertTrue("ATI", "ATI" in keys)
        assertTrue("FINDMY", "FINDMY" in keys)
        // Nothing on the handset reports without something running on it.
        assertFalse("WIFI", "WIFI" in keys)
        assertFalse("GNSS", "GNSS" in keys)
        assertFalse("IMU", "IMU" in keys)
    }

    @Test
    fun `no on-device channel reports without cooperation`() {
        // This is the load-bearing claim in the doc: an app cannot see another handset.
        val offenders = LocationSurface.CHANNELS
            .filter { it.surface == Surface.ON_DEVICE && !it.needsCooperation }
        assertTrue("unexpected: $offenders", offenders.isEmpty())
    }

    @Test
    fun `trackable needs a coordinate, sub-minute cadence, and not a city`() {
        val keys = LocationSurface.trackable().map { it.key }
        assertTrue("WIFI", "WIFI" in keys)
        assertTrue("GNSS", "GNSS" in keys)
        assertTrue("CELL", "CELL" in keys)
        // No coordinate of its own.
        assertFalse("IMU", "IMU" in keys)
        assertFalse("KNOWN_NETS", "KNOWN_NETS" in keys)
        // Too coarse.
        assertFalse("IP", "IP" in keys)
        // Not an API.
        assertFalse("HLR", "HLR" in keys)
    }

    @Test
    fun `IP is city grade and IMU is not`() {
        assertTrue(LocationSurface.byKey("IP")!!.cityGrade)
        assertFalse(LocationSurface.byKey("IMU")!!.cityGrade)
        // precisionM 0 means "no coordinate", which must not read as infinitely precise.
        assertFalse(LocationSurface.byKey("KNOWN_NETS")!!.cityGrade)
    }

    @Test
    fun `reachableFromApp honours granted permissions`() {
        val none = LocationSurface.reachableFromApp(emptySet()).map { it.key }
        // No permission required at all.
        assertTrue("IP", "IP" in none)
        assertTrue("IMU", "IMU" in none)
        assertFalse("WIFI", "WIFI" in none)
        assertFalse("GNSS", "GNSS" in none)

        val fine = LocationSurface.reachableFromApp(
            setOf(LocationSurface.PERM_FINE)
        ).map { it.key }
        assertTrue("WIFI", "WIFI" in fine)
        assertTrue("CELL", "CELL" in fine)
        assertTrue("GNSS", "GNSS" in fine)
        assertFalse("EXIF", "EXIF" in fine) // media permission is separate
        assertFalse("BLE", "BLE" in fine) // BLUETOOTH_SCAN is separate

        val media = LocationSurface.reachableFromApp(
            setOf(LocationSurface.PERM_MEDIA)
        ).map { it.key }
        assertTrue("EXIF", "EXIF" in media)
    }

    @Test
    fun `reachableFromApp never leaks carrier or platform channels`() {
        val all = LocationSurface.reachableFromApp(
            setOf(LocationSurface.PERM_FINE, LocationSurface.PERM_MEDIA, LocationSurface.PERM_BLE)
        )
        assertTrue(all.all { it.surface == Surface.ON_DEVICE })
        assertFalse(all.any { it.key == "ATI" || it.key == "FINDMY" || it.key == "HLR" })
    }

    @Test
    fun `ranked puts the most precise channel first and city grade last`() {
        val ranked = LocationSurface.ranked()
        assertEquals("RTT", ranked.first().key) // 2 m
        assertEquals("IP", ranked.last().key) // 25 km
        // Every city-grade channel sits after every non-city-grade one.
        val firstCityGrade = ranked.indexOfFirst { it.cityGrade }
        assertTrue(ranked.drop(firstCityGrade).all { it.cityGrade })
        assertTrue(ranked.take(firstCityGrade).none { it.cityGrade })
    }

    @Test
    fun `every channel explains itself`() {
        val mute = LocationSurface.CHANNELS.filter { it.note.isBlank() }
        assertTrue("channel with no note: $mute", mute.isEmpty())
    }
}
