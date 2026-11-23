package com.vector.console.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gap semantics are the whole point of a track store: a hole in the record is
 * not the same as a target that stopped. These lock the arithmetic down.
 */
class TrackGapTest {

    private fun summary(stamps: List<Long>, coords: List<Pair<Double, Double>>): TrackSummary {
        var travelled = 0.0
        for (i in 1 until coords.size) {
            travelled += TrackStore.haversine(
                coords[i - 1].first, coords[i - 1].second,
                coords[i].first, coords[i].second
            )
        }
        val gaps = mutableListOf<TrackGap>()
        for (i in 1 until stamps.size) {
            val delta = (stamps[i] - stamps[i - 1]) / 1000
            if (delta > 90) gaps += TrackGap(stamps[i - 1], stamps[i])
        }
        return TrackSummary(
            targetId = "8a0821",
            points = stamps.size,
            firstAt = stamps.first(),
            lastAt = stamps.last(),
            lastLat = coords.last().first,
            lastLon = coords.last().second,
            travelledM = travelled,
            gaps = gaps
        )
    }

    @Test
    fun `steady cadence has no gaps`() {
        val base = 1_700_000_000_000L
        val stamps = (0 until 10).map { base + it * 15_000L }
        val coords = stamps.map { -6.2 to 106.8 }
        val s = summary(stamps, coords)
        assertTrue(s.gaps.isEmpty())
        assertEquals(100, s.coveragePct)
    }

    @Test
    fun `silence longer than threshold is one gap`() {
        val base = 1_700_000_000_000L
        val stamps = listOf(
            base,
            base + 15_000,
            base + 30_000,
            base + 30_000 + 600_000, // 10 min silence
            base + 30_000 + 615_000
        )
        val coords = stamps.map { -6.2 to 106.8 }
        val s = summary(stamps, coords)
        assertEquals(1, s.gaps.size)
        assertEquals(600, s.gaps.single().seconds)
        assertTrue(s.coveragePct < 100)
    }

    @Test
    fun `gap threshold boundary is exclusive`() {
        val base = 1_700_000_000_000L
        val stamps = listOf(base, base + 90_000) // exactly 90 s
        val s = summary(stamps, stamps.map { -6.2 to 106.8 })
        assertTrue("90 s at a 90 s threshold is not a gap", s.gaps.isEmpty())

        val stamps2 = listOf(base, base + 91_000)
        val s2 = summary(stamps2, stamps2.map { -6.2 to 106.8 })
        assertEquals(1, s2.gaps.size)
    }

    @Test
    fun `haversine matches a known Jakarta to Surabaya leg`() {
        // Soekarno-Hatta to Juanda, great-circle ~ 690 km.
        val d = TrackStore.haversine(-6.1256, 106.6559, -7.3798, 112.7870)
        assertTrue("expected 660-720 km, got ${d / 1000}", d in 660_000.0..720_000.0)
    }

    @Test
    fun `haversine of identical points is zero`() {
        assertEquals(0.0, TrackStore.haversine(-6.2, 106.8, -6.2, 106.8), 1e-9)
    }

    @Test
    fun `antimeridian leg does not explode`() {
        val d = TrackStore.haversine(-2.5, 179.9, -2.5, -179.9)
        assertTrue("expected under 30 km, got ${d / 1000}", d in 0.0..30_000.0)
    }
}
