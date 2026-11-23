package com.vector.console.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CctvTaxonomyTest {

    private val assetText by lazy {
        File("src/main/assets/cctv_indonesia.json").readText()
    }

    @Test
    fun `AC-001 every camera has a canonical leaf and counts sum to asset size`() {
        val result = CctvTaxonomy.validateAsset(assetText)
        assertEquals(22036, result.cameraCount)
        assertEquals(22036, result.uniqueCameraIds)
        assertEquals(0, result.missingLeaf)
        assertEquals(result.cameraCount, result.leafCountSum)
    }

    @Test
    fun `AC-002 taxonomy rows are enum-bounded and merged operators share one leaf`() {
        val rows = CctvTaxonomy.rows
        assertEquals(659, rows.size)
        assertTrue(rows.any { it.operatorId == "atcsindonesia-other" })
        assertTrue(rows.any {
            it.displayLabel == "ATCS Indonesia · Other Feeds" && it.province == "Nasional"
        })
        assertTrue(rows.any { it.operatorId == "atcsindonesia-proxy" })
        assertTrue(rows.any {
            it.displayLabel == "ATCS Indonesia · Proxied Feeds" && it.province == "Jawa Barat"
        })
        assertTrue(CctvTaxonomy.provinces.contains("DI Yogyakarta"))
        assertTrue(CctvTaxonomy.provinces.contains("Nasional"))
        assertEquals("jm", CctvTaxonomy.operatorIdFor("Jasa Marga"))
        assertEquals("jm", CctvTaxonomy.operatorIdFor("BPJT · JM"))
        assertEquals("mms", CctvTaxonomy.operatorIdFor("BPJT · MAN"))
        assertEquals("wbw", CctvTaxonomy.operatorIdFor("BPJT · WBW"))
        assertTrue(CctvTaxonomy.operatorIdFor("BPJT · HK") != CctvTaxonomy.operatorIdFor("HK Opt2"))
    }

    @Test
    fun `AC-003 canonical breadcrumbs are defined for every live category`() {
        assertEquals(
            "Indonesia / Jawa Barat / Dishub Bandung",
            CctvTaxonomy.breadcrumb("Dishub Bandung")
        )
        assertEquals(
            "Indonesia / Nasional / Jasa Marga",
            CctvTaxonomy.breadcrumb("BPJT · JM")
        )
        assertEquals(
            "Indonesia / Jawa Timur / CCTV Malang",
            CctvTaxonomy.breadcrumb("CCTV Malang")
        )
        assertEquals(
            "International / Singapore / LTA Singapore",
            CctvTaxonomy.breadcrumb("Singapore")
        )
        assertEquals(
            "International / California / Caltrans",
            CctvTaxonomy.breadcrumb("California")
        )
        assertEquals(
            "International / Ireland / TII Ireland",
            CctvTaxonomy.breadcrumb("Ireland")
        )
    }

    @Test
    fun `Unmapped is a reachable canonical leaf`() {
        val row = CctvTaxonomy.rows.first { it.province == "Unmapped" }
        assertNotNull(row.source)
        assertTrue(CctvTaxonomy.validateAsset(assetText).unmappedCount > 0)
    }

    @Test
    fun `AC-011 no camera feed uses cleartext HTTP`() {
        val cleartext = Regex("\"url\":\"http://[^\"]+\"").findAll(assetText).count()
        assertEquals(0, cleartext)
    }

    @Test
    fun `AC-012 availability flags reflect the probed health audit`() {
        val available = Regex("\"available\":true").findAll(assetText).count()
        val explicitly = Regex("\"available\":false").findAll(assetText).count()
        assertEquals(6841, available)
        assertEquals(22036 - 6841, explicitly)
    }

    @Test
    fun `AC-007 duration is clamped and scale zero bypasses animation`() {
        assertEquals(180L, CctvTaxonomy.scaledDuration(180L, 1f))
        assertEquals(90L, CctvTaxonomy.scaledDuration(180L, 0.5f))
        assertEquals(0L, CctvTaxonomy.scaledDuration(180L, 0f))
        assertEquals(180L, CctvTaxonomy.scaledDuration(180L, 2f))
        assertEquals(0L, CctvTaxonomy.scaledDuration(180L, -1f))
    }
}
