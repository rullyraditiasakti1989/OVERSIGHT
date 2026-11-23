package com.vector.console.data

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Quake(
    val magnitude: Double,
    val place: String,
    val time: Long,
    val longitude: Double,
    val latitude: Double,
    val depthKm: Double,
    val url: String
)

data class DomainReport(
    val domain: String,
    val registrar: String?,
    val createdAt: String?,
    val expiresAt: String?,
    val updatedAt: String?,
    val statuses: List<String>,
    val nameservers: List<String>,
    val dnssec: Boolean?
)

data class IpReport(
    val ip: String,
    val country: String?,
    val countryCode: String?,
    val region: String?,
    val city: String?,
    val isp: String?,
    val org: String?,
    val asn: String?,
    val timezone: String?,
    val latitude: Double?,
    val longitude: Double?
)

data class Breach(
    val name: String,
    val title: String,
    val domain: String,
    val breachDate: String,
    val pwnCount: Long,
    val dataClasses: List<String>,
    val description: String,
    val verified: Boolean
)

object PublicIntel {

    suspend fun earthquakes(): List<Quake> = withContext(Dispatchers.IO) {
        val json = get("https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_day.geojson")
        val features = JSONObject(json).getJSONArray("features")
        buildList {
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val props = feature.getJSONObject("properties")
                val geometry = feature.optJSONObject("geometry") ?: continue
                val coords = geometry.optJSONArray("coordinates") ?: continue
                val magnitude = if (props.isNull("mag")) null else props.optDouble("mag")
                if (magnitude == null || magnitude.isNaN()) continue
                add(
                    Quake(
                        magnitude = magnitude,
                        place = props.optString("place", "Location unknown"),
                        time = props.optLong("time"),
                        longitude = coords.optDouble(0),
                        latitude = coords.optDouble(1),
                        depthKm = coords.optDouble(2),
                        url = props.optString("url")
                    )
                )
            }
        }.sortedByDescending { it.time }
    }

    suspend fun domain(raw: String): DomainReport = withContext(Dispatchers.IO) {
        val domain = normalizeDomain(raw)
            ?: throw IllegalArgumentException("Invalid domain format.")
        val json = get(
            "https://rdap.org/domain/$domain",
            mapOf("Accept" to "application/rdap+json")
        )
        val root = JSONObject(json)

        val statuses = root.optJSONArray("status").toStringList()
        var registrar: String? = null
        val entities = root.optJSONArray("entities")
        if (entities != null) {
            for (i in 0 until entities.length()) {
                val entity = entities.optJSONObject(i) ?: continue
                val roles = entity.optJSONArray("roles").toStringList()
                if (roles.any { it.equals("registrar", ignoreCase = true) }) {
                    registrar = vcardName(entity.optJSONArray("vcardArray"))
                    if (registrar != null) break
                }
            }
        }

        val events = root.optJSONArray("events")
        fun event(action: String): String? {
            if (events == null) return null
            for (i in 0 until events.length()) {
                val item = events.optJSONObject(i) ?: continue
                if (item.optString("eventAction").equals(action, ignoreCase = true)) {
                    return item.optString("eventDate").takeIf { it.isNotBlank() }
                }
            }
            return null
        }

        val nameservers = root.optJSONArray("nameservers").toStringList().mapNotNull { rawNs ->
            runCatching { JSONObject(rawNs).optString("ldhName") }.getOrNull()
                ?.takeIf { it.isNotBlank() }
        }

        DomainReport(
            domain = root.optString("ldhName", domain).lowercase(),
            registrar = registrar,
            createdAt = event("registration"),
            expiresAt = event("expiration"),
            updatedAt = event("last changed"),
            statuses = statuses,
            nameservers = nameservers,
            dnssec = root.optJSONObject("secureDNS")?.optBoolean("delegationSigned")
        )
    }

    suspend fun ip(raw: String): IpReport = withContext(Dispatchers.IO) {
        val ip = normalizeIp(raw) ?: throw IllegalArgumentException("Invalid IP format.")
        val json = get("https://ipwho.is/$ip")
        val root = JSONObject(json)
        if (!root.optBoolean("success", false)) {
            throw IOException(root.optString("message", "IP data unavailable."))
        }
        val connection = root.optJSONObject("connection")
        val timezone = root.optJSONObject("timezone")
        IpReport(
            ip = root.optString("ip", ip),
            country = root.optString("country").takeIf { it.isNotBlank() },
            countryCode = root.optString("country_code").takeIf { it.isNotBlank() },
            region = root.optString("region").takeIf { it.isNotBlank() },
            city = root.optString("city").takeIf { it.isNotBlank() },
            isp = connection?.optString("isp")?.takeIf { it.isNotBlank() },
            org = connection?.optString("org")?.takeIf { it.isNotBlank() },
            asn = connection?.optString("asn")?.takeIf { it.isNotBlank() },
            timezone = timezone?.optString("id")?.takeIf { it.isNotBlank() },
            latitude = root.optDouble("latitude").takeUnless { it.isNaN() },
            longitude = root.optDouble("longitude").takeUnless { it.isNaN() }
        )
    }

    suspend fun breaches(): List<Breach> = withContext(Dispatchers.IO) {
        val json = get("https://haveibeenpwned.com/api/v3/breaches")
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                if (item.optBoolean("IsSensitive", false)) continue
                add(
                    Breach(
                        name = item.optString("Name"),
                        title = item.optString("Title"),
                        domain = item.optString("Domain"),
                        breachDate = item.optString("BreachDate"),
                        pwnCount = item.optLong("PwnCount"),
                        dataClasses = item.optJSONArray("DataClasses").toStringList(),
                        description = cleanHtml(item.optString("Description")),
                        verified = item.optBoolean("IsVerified", false)
                    )
                )
            }
        }.sortedByDescending { it.breachDate }
    }

    fun normalizeDomain(raw: String): String? {
        var value = raw.trim().lowercase()
        value = value.removePrefix("https://").removePrefix("http://")
        value = value.substringBefore("/").substringBefore("?").substringBefore("#")
        if (value.startsWith("www.")) value = value.removePrefix("www.")
        val pattern = Regex("^(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
        return value.takeIf { pattern.matches(it) }
    }

    fun normalizeIp(raw: String): String? {
        val value = raw.trim()
        if (value.contains(":")) {
            return value.takeIf { it.matches(Regex("^[0-9a-fA-F:]{2,45}$")) }
        }
        val parts = value.split(".")
        if (parts.size == 4 && parts.all { part ->
                part.toIntOrNull()?.let { it in 0..255 } == true
            }
        ) return value
        return null
    }

    private suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        Net.get(url, headers)

    private fun vcardName(vcard: JSONArray?): String? {
        if (vcard == null || vcard.length() < 2) return null
        val properties = vcard.optJSONArray(1) ?: return null
        for (i in 0 until properties.length()) {
            val entry = properties.optJSONArray(i) ?: continue
            if (entry.optString(0).equals("fn", ignoreCase = true)) {
                return entry.optString(3).takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    private fun cleanHtml(value: String): String {
        if (value.isBlank()) return ""
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun formatDate(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        return runCatching {
            Instant.parse(iso)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.forLanguageTag("id-ID")))
        }.getOrElse { iso.take(10) }
    }

    fun formatTime(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd MMM · HH:mm", Locale.forLanguageTag("id-ID")))

    fun compactNumber(value: Long): String = when {
        value >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", value / 1_000_000_000.0)
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }
}
