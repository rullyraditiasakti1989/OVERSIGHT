package com.vector.console.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class CctvCamera(
    val id: String,
    val name: String,
    val country: String,
    val city: String,
    val latitude: Double?,
    val longitude: Double?,
    val imageUrl: String?,
    val streamUrl: String?,
    val externalUrl: String?,
    val source: String,
    val hlsHeaders: Map<String, String> = emptyMap(),
    val available: Boolean = true
) {
    val snapshot: Boolean get() = !imageUrl.isNullOrBlank()
    val openable: Boolean get() = !streamUrl.isNullOrBlank() || !externalUrl.isNullOrBlank()
}

/**
 * Public camera catalogs. Snapshot feeds (JPG) are the primary path — they need no
 * player, no proxy and no key. HLS/YouTube links are kept so the UI can hand them
 * to an external player. Every catalog is bounded and fails independently.
 */
object CctvCatalog {

    private const val MAX_CAMERAS = 300
    private const val SINGAPORE_URL = "https://api.data.gov.sg/v1/transport/traffic-images"
    private const val IRELAND_URL = "https://traffic.tii.ie/api/graphql"
    private const val OPEN_WEBCAMS_URL =
        "https://raw.githubusercontent.com/willytop8/Live-Environment-Streams/main/streams.geojson"

    private val irelandQuery = """
        query MapFeatures(${'$'}input: MapFeaturesArgs!) {
          mapFeaturesQuery(input: ${'$'}input) {
            mapFeatures {
              uri title __typename
              features { geometry }
              ... on Camera {
                active
                views(limit: 5) { category ... on CameraView { url } }
              }
            }
          }
        }
    """.trimIndent()

    suspend fun singapore(): List<CctvCamera> = withContext(Dispatchers.IO) {
        val root = JSONObject(Net.get(SINGAPORE_URL))
        val items = root.optJSONArray("items") ?: return@withContext emptyList()
        val cameras = items.optJSONObject(0)?.optJSONArray("cameras") ?: return@withContext emptyList()
        buildList {
            val seen = HashSet<String>()
            val limit = minOf(cameras.length(), MAX_CAMERAS)
            for (i in 0 until limit) {
                val item = cameras.optJSONObject(i) ?: continue
                val id = item.optString("camera_id", "")
                if (id.isBlank()) continue
                if (!seen.add(id)) continue
                val image = item.optString("image", "")
                if (image.isBlank()) continue
                val location = item.optJSONObject("location")
                add(
                    CctvCamera(
                        id = "sg-$id",
                        name = "LTA Camera $id",
                        country = "Singapore",
                        city = "Singapore",
                        latitude = location?.optDouble("latitude")?.takeUnless { it.isNaN() },
                        longitude = location?.optDouble("longitude")?.takeUnless { it.isNaN() },
                        imageUrl = image,
                        streamUrl = null,
                        externalUrl = null,
                        source = "data.gov.sg LTA"
                    )
                )
            }
        }
    }

    suspend fun caltrans(): List<CctvCamera> = withContext(Dispatchers.IO) {
        val result = ArrayList<CctvCamera>(MAX_CAMERAS)
        for (district in 1..12) {
            if (result.size >= MAX_CAMERAS) break
            val code = district.toString().padStart(2, '0')
            val url = "https://cwwp2.dot.ca.gov/data/d$district/cctv/cctvStatusD$code.json"
            val body = runCatching { Net.get(url) }.getOrNull() ?: continue
            val root = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val entries = root.optJSONArray("data") ?: continue
            for (i in 0 until entries.length()) {
                if (result.size >= MAX_CAMERAS) break
                val cam = entries.optJSONObject(i)?.optJSONObject("cctv") ?: continue
                if (!cam.optString("inService", "").equals("true", ignoreCase = true)) continue
                val location = cam.optJSONObject("location") ?: continue
                val image = cam.optJSONObject("imageData")
                    ?.optJSONObject("static")?.optString("currentImageURL", "").orEmpty()
                if (image.isBlank()) continue
                val lat = location.optString("latitude", "").toDoubleOrNull()
                val lon = location.optString("longitude", "").toDoubleOrNull()
                val name = location.optString("locationName", "").ifBlank {
                    "Caltrans D$district ${cam.optString("index", i.toString())}"
                }
                result += CctvCamera(
                    id = "ct-$district-${cam.optString("index", i.toString())}",
                    name = name,
                    country = "Amerika Serikat",
                    city = location.optString("nearbyPlace", "")
                        .ifBlank { location.optString("county", "") },
                    latitude = lat,
                    longitude = lon,
                    imageUrl = image,
                    streamUrl = cam.optJSONObject("imageData")
                        ?.optString("streamingVideoURL", "").takeIf { it?.isNotBlank() == true },
                    externalUrl = null,
                    source = "Caltrans D$district"
                )
            }
        }
        result
    }

    suspend fun ireland(): List<CctvCamera> = withContext(Dispatchers.IO) {
        val input = JSONObject().apply {
            put("north", 55.5)
            put("south", 51.3)
            put("east", -5.9)
            put("west", -10.7)
            put("zoom", 18)
            put("layerSlugs", JSONArray().put("normalCameras"))
        }
        val payload = JSONObject().apply {
            put("query", irelandQuery)
            put("variables", JSONObject().put("input", input))
        }.toString().toByteArray(Charsets.UTF_8)

        val response = Net.request(
            url = IRELAND_URL,
            method = "POST",
            body = payload,
            contentType = "application/json"
        )
        if (response.status !in 200..299) throw IOException("TII rejected the request (HTTP ${response.status}).")
        val root = JSONObject(response.body)
        val features = root.optJSONObject("data")
            ?.optJSONObject("mapFeaturesQuery")?.optJSONArray("mapFeatures")
            ?: return@withContext emptyList()

        buildList {
            val seen = HashSet<String>()
            val limit = minOf(features.length(), MAX_CAMERAS)
            for (i in 0 until limit) {
                val feature = features.optJSONObject(i) ?: continue
                if (feature.optString("__typename") != "Camera") continue
                if (feature.optBoolean("active", true).not()) continue
                val coords = feature.optJSONArray("features")
                    ?.optJSONObject(0)?.optJSONObject("geometry")?.optJSONArray("coordinates")
                    ?: continue
                if (coords.length() < 2) continue
                val lon = coords.optDouble(0)
                val lat = coords.optDouble(1)
                if (lat.isNaN() || lon.isNaN()) continue
                val views = feature.optJSONArray("views") ?: continue
                var image = ""
                for (j in 0 until views.length()) {
                    val view = views.optJSONObject(j) ?: continue
                    if (!view.optString("category").equals("IMAGE", ignoreCase = true)) continue
                    val url = view.optString("url", "")
                    if (url.isNotBlank()) {
                        image = url
                        break
                    }
                }
                if (image.isBlank()) continue
                val id = feature.optString("uri", "$lat,$lon").ifBlank { "$lat,$lon" }
                if (!seen.add(id)) continue
                add(
                    CctvCamera(
                        id = "ie-$id",
                        name = feature.optString("title", "TII Camera").ifBlank { "TII Camera" },
                        country = "Ireland",
                        city = "",
                        latitude = lat,
                        longitude = lon,
                        imageUrl = image,
                        streamUrl = null,
                        externalUrl = null,
                        source = "TII Ireland"
                    )
                )
            }
        }
    }

    suspend fun webcams(): List<CctvCamera> = withContext(Dispatchers.IO) {
        val root = JSONObject(Net.get(OPEN_WEBCAMS_URL))
        val features = root.optJSONArray("features") ?: return@withContext emptyList()
        buildList {
            val seen = HashSet<String>()
            val limit = minOf(features.length(), MAX_CAMERAS)
            for (i in 0 until limit) {
                val feature = features.optJSONObject(i) ?: continue
                val props = feature.optJSONObject("properties") ?: continue
                if (!props.optString("status").equals("active", ignoreCase = true)) continue
                if (props.optString("coordinates_quality") == "country_centroid") continue
                val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
                    ?: continue
                if (coords.length() < 2) continue
                val lon = coords.optDouble(0)
                val lat = coords.optDouble(1)
                if (lat.isNaN() || lon.isNaN()) continue
                val url = props.optString("url", "")
                if (url.isBlank()) continue
                if (!seen.add(url)) continue
                val name = props.optString("display_name", "")
                    .ifBlank { props.optString("name", "Webcam") }
                val kind = props.optString("url_type", "")
                val countryCode = props.optString("country_code", "")
                add(
                    CctvCamera(
                        id = "owc-$i",
                        name = name,
                        country = countryCode.ifBlank { "—" },
                        city = props.optString("city", "")
                            .ifBlank { props.optString("region", "") },
                        latitude = lat,
                        longitude = lon,
                        imageUrl = null,
                        streamUrl = url.takeIf { kind.equals("hls", true) },
                        externalUrl = url.takeIf {
                            kind.equals("youtube", true) || kind.equals("html_page", true)
                        },
                        source = props.optString("source_family", "Open Webcams")
                    )
                )
            }
        }
    }
}
