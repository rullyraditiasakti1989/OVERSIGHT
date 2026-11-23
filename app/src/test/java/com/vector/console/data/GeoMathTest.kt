package com.vector.console.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeoMathTest {

    @Test
    fun `jitter is deterministic for the same key`() {
        val a = GeoMath.jitter(-6.7, 108.5, "zone-1")
        val b = GeoMath.jitter(-6.7, 108.5, "zone-1")
        assertEquals(a.first, b.first, 1e-12)
        assertEquals(a.second, b.second, 1e-12)
    }

    @Test
    fun `jitter differs across keys and stays within 60 metres`() {
        val base = GeoMath.jitter(-6.7, 108.5, "zone-1")
        val other = GeoMath.jitter(-6.7, 108.5, "zone-2")
        assertTrue(abs(base.first - other.first) > 1e-9 || abs(base.second - other.second) > 1e-9)

        repeat(200) { i ->
            val (lat, lon) = GeoMath.jitter(-6.7, 108.5, "zone-$i")
            val metres = GeoMath.distanceMeters(-6.7, 108.5, lat, lon)
            assertTrue("distance $metres out of range", metres <= 60.5)
        }
    }

    @Test
    fun `distance is zero for identical points and grows for far points`() {
        assertEquals(0.0, GeoMath.distanceMeters(-6.7, 108.5, -6.7, 108.5), 1e-9)
        assertTrue(GeoMath.distanceMeters(-6.7, 108.5, -6.8, 108.5) > 10_000)
    }
}
