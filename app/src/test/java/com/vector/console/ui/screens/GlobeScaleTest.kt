package com.vector.console.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scale bar is the only control on the globe that claims a ground distance,
 * so it is the only one that can be wrong without looking wrong.
 *
 * `scaleMetres` picks a round distance whose bar lands in a readable band. These
 * tests pin the two properties that matter: the number is always round, and it
 * always corresponds to a bar the eye can actually measure. They do not pin the
 * exact ladder — that can be tuned without the control becoming wrong.
 */
class GlobeScaleTest {

    /** A round ground distance: 1, 1.5, 2, 3, 5 or 7 times a power of ten. */
    private fun isRound(metres: Int): Boolean {
        var value = metres
        while (value % 10 == 0) value /= 10
        return value in setOf(1, 15, 2, 3, 5, 7)
    }

    @Test
    fun `every zoom in the globe range yields a round distance`() {
        var zoom = 0.0
        while (zoom <= 12.0) {
            val metres = scaleMetres(zoom)
            assertTrue("zoom $zoom gave $metres m, which is not a round distance", isRound(metres))
            zoom += 0.1
        }
    }

    @Test
    fun `distance shrinks monotonically as the camera descends`() {
        var previous = Int.MAX_VALUE
        var zoom = 0.0
        while (zoom <= 12.0) {
            val metres = scaleMetres(zoom)
            assertTrue("zoom $zoom gave $metres m after $previous m", metres <= previous)
            previous = metres
            zoom += 0.25
        }
    }

    @Test
    fun `the bar stays within a measurable band`() {
        // 60 dp nominal, and the ladder's 1.5x spacing bounds the overshoot.
        var zoom = 0.0
        while (zoom <= 12.0) {
            val perPixel = 156543.0 / Math.pow(2.0, zoom)
            val barPx = scaleMetres(zoom) / perPixel
            assertTrue("zoom $zoom bar is ${barPx}px", barPx >= 55.0)
            assertTrue("zoom $zoom bar is ${barPx}px", barPx <= 105.0)
            zoom += 0.1
        }
    }

    @Test
    fun `out of range zooms are clamped rather than extrapolated`() {
        assertEquals(scaleMetres(0.0), scaleMetres(-40.0))
        assertEquals(scaleMetres(22.0), scaleMetres(90.0))
    }

    @Test
    fun `the whole earth view is labelled in thousands of kilometres`() {
        // A globe view covers 40,000 km across ~350 px, so a 60 px bar is
        // thousands of km. Anything smaller is a bar 0.6 px long.
        assertTrue(scaleMetres(0.0) >= 5_000_000)
        assertEquals(5_000_000, scaleMetres(1.6))
    }

    @Test
    fun `a city view is labelled in kilometres`() {
        val metres = scaleMetres(10.0)
        assertTrue("city scale gave $metres m", metres in 1_000..20_000)
    }
}
