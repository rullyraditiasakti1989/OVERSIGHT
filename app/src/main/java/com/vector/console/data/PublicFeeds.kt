package com.vector.console.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class HazardEvent(
    val id: String,
    val title: String,
    val category: String,
    val date: String,
    val latitude: Double?,
    val longitude: Double?,
    val url: String
)

data class FlightTrack(
    val icao24: String,
    val callsign: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Double?,
    val heading: Double?
)

data class KpReading(val value: Double, val time: String?)

data class SolarFlare(val className: String, val flux: Double?, val time: String)

data class SpaceWeather(
    val kp: KpReading?,
    val flares: List<SolarFlare>
)

data class KevEntry(
    val cveId: String,
    val vendor: String,
    val product: String,
    val name: String,
    val dateAdded: String,
    val dueDate: String,
    val action: String
)

data class CveEntry(
    val id: String,
    val published: String,
    val score: Double?,
    val summary: String
)

/**
 * Keyless public feeds — the same open sources OSIRIS aggregates, pulled straight
 * from the device. No backend, no account, no API key. Each feed is isolated so a
 * dead upstream never blocks the others.
 */
object PublicFeeds {

    private const val LIMIT = 40

    private const val EONET_URL =
        "https://eonet.gsfc.nasa.gov/api/v3/events?status=open&limit=50"
    private const val OPENSKY_URL =
        "https://opensky-network.org/api/states/all"
    private const val NOAA_KP_URL =
        "https://services.swpc.noaa.gov/json/planetary_k_index_1m.json"
    private const val NOAA_FLARE_URL =
        "https://services.swpc.noaa.gov/json/goes/primary/xray-flares-7-day.json"
    private const val KEV_URL =
        "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json"
    private const val NVD_URL =
        "https://services.nvd.nist.gov/rest/json/cves/2.0?resultsPerPage=20"
    private const val TOR_URL =
        "https://check.torproject.org/torbulkexitlist"

    suspend fun hazards(): List<HazardEvent> = withContext(Dispatchers.IO) {
        val root = JSONObject(Net.get(EONET_URL))
        val events = root.optJSONArray("events") ?: return@withContext emptyList()
        buildList {
            val count = minOf(events.length(), LIMIT)
            for (i in 0 until count) {
                val item = events.optJSONObject(i) ?: continue
                val geometry = item.optJSONArray("geometry") ?: continue
                val point = geometry.optJSONObject(0) ?: continue
                val coords = point.optJSONArray("coordinates") ?: continue
                val lon = coords.optDouble(0).takeUnless { it.isNaN() }
                val lat = coords.optDouble(1).takeUnless { it.isNaN() }
                if (lat == null || lon == null) continue
                val category = item.optJSONArray("categories")
                    ?.optJSONObject(0)?.optString("title").orEmpty()
                    .ifBlank { "BENCANA" }
                val source = item.optJSONArray("sources")
                    ?.optJSONObject(0)?.optString("url").orEmpty()
                add(
                    HazardEvent(
                        id = item.optString("id"),
                        title = item.optString("title", "Unnamed event"),
                        category = category,
                        date = point.optString("date", "—"),
                        latitude = lat,
                        longitude = lon,
                        url = source
                    )
                )
            }
        }
    }

    suspend fun flights(): List<FlightTrack> = withContext(Dispatchers.IO) {
        val root = JSONObject(Net.get(OPENSKY_URL))
        val states = root.optJSONArray("states") ?: return@withContext emptyList()
        buildList {
            val count = minOf(states.length(), LIMIT)
            for (i in 0 until count) {
                val state = states.optJSONArray(i) ?: continue
                val lon = state.optDouble(5).takeUnless { it.isNaN() } ?: continue
                val lat = state.optDouble(6).takeUnless { it.isNaN() } ?: continue
                add(
                    FlightTrack(
                        icao24 = state.optString(0, "—"),
                        callsign = state.optString(1, "").trim().ifBlank { "TANPA PANGGILAN" },
                        country = state.optString(2, "").ifBlank { "Unknown" },
                        latitude = lat,
                        longitude = lon,
                        altitudeMeters = state.optDouble(7).takeUnless { it.isNaN() },
                        speedMetersPerSecond = state.optDouble(9).takeUnless { it.isNaN() },
                        heading = state.optDouble(10).takeUnless { it.isNaN() }
                    )
                )
            }
        }
    }

    suspend fun spaceWeather(): SpaceWeather = withContext(Dispatchers.IO) {
        val kp = runCatching {
            val array = JSONArray(Net.get(NOAA_KP_URL))
            if (array.length() == 0) return@runCatching null
            val last = array.optJSONObject(array.length() - 1) ?: return@runCatching null
            val value = last.optDouble("kp_index").takeUnless { it.isNaN() }
                ?: last.optDouble("estimated_kp").takeUnless { it.isNaN() }
                ?: return@runCatching null
            KpReading(value, last.optString("time_tag", "").ifBlank { null })
        }.getOrNull()

        val flares = runCatching {
            val array = JSONArray(Net.get(NOAA_FLARE_URL))
            buildList {
                val start = maxOf(0, array.length() - 6)
                for (i in start until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val name = item.optString("class", "").ifBlank { item.optString("classType") }
                    if (name.isBlank()) continue
                    add(
                        SolarFlare(
                            className = name,
                            flux = item.optDouble("flux").takeUnless { it.isNaN() },
                            time = item.optString("time_tag", "—")
                        )
                    )
                }
            }.reversed()
        }.getOrDefault(emptyList())

        if (kp == null && flares.isEmpty()) {
            throw IOException("NOAA space-weather feed is unavailable.")
        }
        SpaceWeather(kp = kp, flares = flares)
    }

    suspend fun kev(): List<KevEntry> = withContext(Dispatchers.IO) {
        val root = JSONObject(Net.get(KEV_URL))
        val list = root.optJSONArray("vulnerabilities") ?: return@withContext emptyList()
        buildList {
            val count = minOf(list.length(), LIMIT)
            for (i in 0 until count) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optString("cveID", "")
                if (id.isBlank()) continue
                add(
                    KevEntry(
                        cveId = id,
                        vendor = item.optString("vendorProject", "—"),
                        product = item.optString("product", "—"),
                        name = item.optString("vulnerabilityName", "—"),
                        dateAdded = item.optString("dateAdded", "—"),
                        dueDate = item.optString("dueDate", "—"),
                        action = item.optString("requiredAction", "—")
                    )
                )
            }
        }
    }

    suspend fun cves(): List<CveEntry> = withContext(Dispatchers.IO) {
        val root = JSONObject(Net.get(NVD_URL))
        val list = root.optJSONArray("vulnerabilities") ?: return@withContext emptyList()
        buildList {
            val count = minOf(list.length(), 20)
            for (i in 0 until count) {
                val wrapper = list.optJSONObject(i) ?: continue
                val cve = wrapper.optJSONObject("cve") ?: continue
                val descriptions = cve.optJSONArray("descriptions")
                var summary = ""
                if (descriptions != null) {
                    for (j in 0 until descriptions.length()) {
                        val desc = descriptions.optJSONObject(j) ?: continue
                        if (desc.optString("lang").equals("en", ignoreCase = true)) {
                            summary = desc.optString("value", "")
                            break
                        }
                    }
                }
                val score = runCatching {
                    val metrics = cve.optJSONObject("metrics")
                    val v31 = metrics?.optJSONArray("cvssMetricV31")?.optJSONObject(0)
                    val v30 = metrics?.optJSONArray("cvssMetricV30")?.optJSONObject(0)
                    val data = v31?.optJSONObject("cvssData") ?: v30?.optJSONObject("cvssData")
                    data?.optDouble("baseScore")?.takeUnless { it.isNaN() }
                }.getOrNull()
                add(
                    CveEntry(
                        id = cve.optString("id", "—"),
                        published = cve.optString("published", "—").take(10),
                        score = score,
                        summary = summary.ifBlank { "Summary unavailable." }
                    )
                )
            }
        }
    }

    suspend fun torExits(): List<String> = withContext(Dispatchers.IO) {
        val body = Net.get(TOR_URL, accept = "text/plain")
        body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .take(LIMIT)
            .toList()
    }
}
