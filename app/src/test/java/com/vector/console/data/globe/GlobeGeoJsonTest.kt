package com.vector.console.data.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge payload. If this emits `NaN` MapLibre throws inside `setData` and the
 * whole globe stops drawing, so the coordinate gate is the point of these cases.
 */
class GlobeGeoJsonTest {

    @Test
    fun `empty layer is a valid empty collection`() {
        val text = GlobeGeoJson.featureCollection(emptyList())
        assertEquals("""{"type":"FeatureCollection","features":[]}""", text)
        assertNotNull(Json.parse(text))
    }

    @Test
    fun `coordinates are longitude first`() {
        val text = GlobeGeoJson.featureCollection(
            listOf(contact("a", lat = -6.2088, lon = 106.8456))
        )
        val feature = Json.parse(text)!!.arr("features")!!.items.single()
        val coords = feature.obj("geometry")!!.arr("coordinates")!!.items
        assertEquals(106.8456, (coords[0] as Json.Num).value, 1e-5)
        assertEquals(-6.2088, (coords[1] as Json.Num).value, 1e-5)
    }

    @Test
    fun `properties carry id, layer and severity`() {
        val text = GlobeGeoJson.featureCollection(
            listOf(contact("quakes:x", layer = LayerId.QUAKES, severity = true))
        )
        val props = Json.parse(text)!!.arr("features")!!.items.single().obj("properties")!!
        assertEquals("quakes:x", props.str("__id"))
        assertEquals("quakes", props.str("__layer"))
        assertEquals(true, props.bool("__sev"))
    }

    @Test
    fun `non finite coordinates are skipped, not emitted as NaN`() {
        val text = GlobeGeoJson.featureCollection(
            listOf(
                contact("nan", lat = Double.NaN, lon = 0.0),
                contact("inf", lat = 0.0, lon = Double.POSITIVE_INFINITY),
                contact("ok", lat = 1.0, lon = 2.0)
            )
        )
        val features = Json.parse(text)!!.arr("features")!!.items
        assertEquals(1, features.size)
        assertEquals("ok", features[0].obj("properties")!!.str("__id"))
        assertTrue("no NaN in the payload", !text.contains("NaN"))
        assertTrue("no Infinity in the payload", !text.contains("Infinity"))
    }

    @Test
    fun `out of range coordinates are skipped`() {
        val text = GlobeGeoJson.featureCollection(
            listOf(contact("lat", lat = 91.0, lon = 0.0), contact("lon", lat = 0.0, lon = 181.0))
        )
        assertTrue(Json.parse(text)!!.arr("features")!!.items.isEmpty())
    }

    @Test
    fun `quotes and control characters are escaped`() {
        val title = "A\"B\\C\nD\tE"
        val text = GlobeGeoJson.featureCollection(listOf(contact("id", title = title)))
        val props = Json.parse(text)!!.arr("features")!!.items.single().obj("properties")!!
        assertEquals(title, props.str("__title"))
    }

    @Test
    fun `tiny magnitudes stay in plain notation`() {
        val text = GlobeGeoJson.featureCollection(listOf(contact("a", lat = 0.00001, lon = 0.00002)))
        // Exponent notation is legal JSON but MapLibre's parser is stricter about
        // it inside coordinates; plain decimal is the safe form.
        assertTrue("no exponent in coordinates", !text.contains("E-"))
        val coords = Json.parse(text)!!.arr("features")!!.items.single()
            .obj("geometry")!!.arr("coordinates")!!.items
        assertEquals(0.00002, (coords[0] as Json.Num).value, 1e-9)
    }

    @Test
    fun `a full satellite layer stays under a megabyte`() {
        val contacts = (1..GlobeFeeds.SATELLITE_CAP).map {
            contact("satellites:$it", lat = -6.2 + it * 0.001, lon = 106.8 + it * 0.001)
        }
        val text = GlobeGeoJson.featureCollection(contacts)
        assertTrue("payload was ${text.length} bytes", text.length < 1_000_000)
        assertEquals(
            GlobeFeeds.SATELLITE_CAP,
            Json.parse(text)!!.arr("features")!!.items.size
        )
    }

    private fun contact(
        id: String,
        layer: LayerId = LayerId.AIRCRAFT,
        lat: Double = 0.0,
        lon: Double = 0.0,
        title: String = id,
        severity: Boolean = false
    ) = Contact(
        id = id,
        layer = layer,
        lat = lat,
        lon = lon,
        title = title,
        subtitle = "",
        severity = severity
    )
}
