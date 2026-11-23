package com.vector.console.data.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Kotlin SGP4 port against golden vectors generated from
 * satellite.js 6.0.1, which is itself Vallado's reference implementation.
 * The generator ran `twoline2satrec` + `sgp4` + `propagate` + `gstime` +
 * `eciToGeodetic`; nothing here is hand-computed.
 *
 * The four records cover every branch that matters:
 *  - LEO   near-earth, full drag terms (`isimp = 0`)
 *  - LOW   perigee under 220 km, simplified drag (`isimp = 1`)
 *  - GEO   deep space with one-day resonance (`method = d`, `irez = 1`)
 *  - MEO   deep space without resonance (`method = d`, `irez = 0`)
 *
 * If a refactor changes a number, this fails. Do not widen the deltas to make
 * it pass — a metre of drift means a term was dropped.
 */
class Sgp4Test {

    private val tsince = doubleArrayOf(0.0, 60.0, 360.0, 720.0, 1440.0, 4320.0)
    private val instants = longArrayOf(1784462400000L, 1784485800000L, 1784505600000L, 1784700900000L)
    private val instantJd = doubleArrayOf(
        2461241.00000000, 2461241.27083333, 2461241.50000000, 2461243.76041667
    )

    private val leoL1 = "1 25544U 98067A   08264.51782528 -.00002182  00000-0 -11606-4 0  2927"
    private val leoL2 = "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.72125391563537"
    private val lowL1 = "1 40001U 14001A   26200.50000000  .00120000  00000-0  50000-3 0  9993"
    private val lowL2 = "2 40001  97.4000 200.0000 0012000  90.0000 270.0000 16.20000000 10000"
    private val geoL1 = "1 28884U 05041A   26200.50000000 -.00000200  00000-0  00000+0 0  9993"
    private val geoL2 = "2 28884   0.0200  95.0000 0003000 100.0000 260.0000  1.00270000 10000"
    private val meoL1 = "1 24876U 97035A   26200.50000000  .00000000  00000-0  00000-0 0  9993"
    private val meoL2 = "2 24876  55.4000 120.0000 0050000  60.0000 300.0000  2.00563000 10000"

    private val leoEci = arrayOf(
        doubleArrayOf(4083.902464, -993.632000, 5243.603665),
        doubleArrayOf(-4133.794278, -4683.164697, -2526.661871),
        doubleArrayOf(2748.401545, -3564.892405, 4992.448309),
        doubleArrayOf(832.513329, -5440.636674, 3865.863539),
        doubleArrayOf(-3199.119302, -5925.838895, -104.283883),
        doubleArrayOf(4805.492242, 4530.664635, 1272.976917)
    )
    private val lowEci = arrayOf(
        doubleArrayOf(-6201.450857, -2261.354011, -30.440761),
        doubleArrayOf(3083.525644, 317.169557, -5840.104999),
        doubleArrayOf(-6002.937647, -1954.534566, 1906.485604),
        doubleArrayOf(-5245.749523, -1453.283133, 3711.537539),
        doubleArrayOf(-2257.070896, -11.699643, 6178.634668),
        doubleArrayOf(3765.767850, 874.543441, -5345.489453)
    )
    private val geoEci = arrayOf(
        doubleArrayOf(-3663.172004, 42007.466166, 6.096606),
        doubleArrayOf(-14437.467036, 39614.740069, 12.319682),
        doubleArrayOf(-41975.051508, -3861.506518, 24.928819),
        doubleArrayOf(4070.413863, -41965.753484, -6.424760),
        doubleArrayOf(-4379.005177, 41938.727229, 6.795444),
        doubleArrayOf(-5805.961420, 41764.769998, 9.117949)
    )
    private val meoEci = arrayOf(
        doubleArrayOf(-13133.941300, 23009.390896, -192.894588),
        doubleArrayOf(-17932.514712, 16143.507320, 10817.495874),
        doubleArrayOf(13549.718298, -22920.318488, -400.801809),
        doubleArrayOf(-13358.887361, 22876.986714, 199.182223),
        doubleArrayOf(-13579.698613, 22737.317569, 591.316976),
        doubleArrayOf(-14419.988725, 22106.885086, 2157.308514)
    )

    private val leoGeo = arrayOf(
        doubleArrayOf(21.44558397, -158.47833972, 388.31453267),
        doubleArrayOf(-39.20045404, 160.34780490, 409.22822108),
        doubleArrayOf(50.79698619, -68.68827247, 389.31520240),
        doubleArrayOf(2.91662756, -72.95359220, 389.89313466)
    )
    private val lowGeo = arrayOf(
        doubleArrayOf(-0.26594450, 82.73683382, 222.82106388),
        doubleArrayOf(40.91965167, 171.67085144, 219.07622956),
        doubleArrayOf(34.46153543, -102.30546593, 216.95196596),
        doubleArrayOf(-81.81952087, -126.67687188, 241.64842817)
    )
    private val geoGeo = arrayOf(
        doubleArrayOf(0.00829238, -22.31375947, 35788.74668840),
        doubleArrayOf(0.03253940, -22.28347416, 35773.99991035),
        doubleArrayOf(-0.00873959, -22.25033271, 35784.55712455),
        doubleArrayOf(-0.03664857, -22.30190323, 35799.29417397)
    )
    private val meoGeo = arrayOf(
        doubleArrayOf(-0.41781859, 2.42055524, 20116.57477103),
        doubleArrayOf(-13.13468174, 94.17161559, 20280.55008202),
        doubleArrayOf(0.43147313, -177.50778181, 20114.43045279),
        doubleArrayOf(-11.19626279, -86.04331492, 20276.01159984)
    )

    @Test
    fun `julian date from epoch millis matches the reference calendar`() {
        for (i in instants.indices) {
            assertEquals("instant $i", instantJd[i], Sgp4.jdayOfMillis(instants[i]), 1e-6)
        }
    }

    @Test
    fun `near earth record reproduces reference ECI`() {
        assertEci("LEO", leoL1, leoL2, leoEci, 'n', 0)
    }

    @Test
    fun `simplified drag record reproduces reference ECI`() {
        assertEci("LOW", lowL1, lowL2, lowEci, 'n', 0)
    }

    @Test
    fun `one day resonance record reproduces reference ECI`() {
        assertEci("GEO", geoL1, geoL2, geoEci, 'd', 1)
    }

    @Test
    fun `deep space record without resonance reproduces reference ECI`() {
        assertEci("MEO", meoL1, meoL2, meoEci, 'd', 0)
    }

    @Test
    fun `geodetic positions match the reference across all four records`() {
        assertGeodetic("LEO", leoL1, leoL2, leoGeo)
        assertGeodetic("LOW", lowL1, lowL2, lowGeo)
        assertGeodetic("GEO", geoL1, geoL2, geoGeo)
        assertGeodetic("MEO", meoL1, meoL2, meoGeo)
    }

    @Test
    fun `period is derived from mean motion`() {
        val rec = Sgp4.twoline2satrec(leoL1, leoL2)!!
        // 15.72 rev/day is 91.6 minutes.
        assertEquals(91.6, rec.periodMinutes, 0.1)
    }

    @Test
    fun `truncated lines are refused rather than thrown`() {
        assertNull(Sgp4.twoline2satrec("1 25544U", leoL2))
        assertNull(Sgp4.twoline2satrec(leoL1, "2 25544"))
        assertNull(Sgp4.twoline2satrec("", ""))
    }

    @Test
    fun `non numeric mean motion is refused`() {
        val broken = "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.72125XXX 10000"
        assertNull(Sgp4.twoline2satrec(leoL1, broken))
    }

    @Test
    fun `decayed record reports error and returns no position`() {
        val rec = Sgp4.twoline2satrec(lowL1, lowL2)!!
        // A year past epoch the drag model has dragged it under the surface.
        val state = Sgp4.propagate(rec, 525_600.0)
        assertNull(state)
        assertTrue("expected a non-zero error code", rec.error != 0)
    }

    private fun assertEci(
        label: String,
        l1: String,
        l2: String,
        expected: Array<DoubleArray>,
        method: Char,
        irez: Int
    ) {
        val rec = Sgp4.twoline2satrec(l1, l2)
        assertNotNull("$label did not parse", rec)
        assertEquals("$label method", method, rec!!.method)
        assertEquals("$label irez", irez, rec.irez)
        for (i in tsince.indices) {
            val state = Sgp4.propagate(rec, tsince[i])
            assertNotNull("$label at ${tsince[i]} min produced no state (error ${rec.error})", state)
            assertEquals("$label x at ${tsince[i]}", expected[i][0], state!!.position.x, 2e-3)
            assertEquals("$label y at ${tsince[i]}", expected[i][1], state.position.y, 2e-3)
            assertEquals("$label z at ${tsince[i]}", expected[i][2], state.position.z, 2e-3)
        }
    }

    private fun assertGeodetic(label: String, l1: String, l2: String, expected: Array<DoubleArray>) {
        val rec = Sgp4.twoline2satrec(l1, l2)!!
        for (i in instants.indices) {
            val pos = Sgp4.positionAt(rec, instants[i])
            assertNotNull("$label at instant $i produced no position (error ${rec.error})", pos)
            assertEquals("$label lat at instant $i", expected[i][0], pos!!.latitude, 1e-6)
            assertEquals("$label lon at instant $i", expected[i][1], pos.longitude, 1e-6)
            // 1e-3 km is a metre of altitude; the reference prints 8 dp.
            assertEquals("$label alt at instant $i", expected[i][2], pos.height, 1e-3)
        }
    }
}
