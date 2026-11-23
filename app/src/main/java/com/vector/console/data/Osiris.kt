package com.vector.console.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.Locale

data class OsirisFeed(val label: String, val path: String)

data class OsirisItem(
    val title: String,
    val subtitle: String,
    val fields: List<Pair<String, String>>,
    val latitude: Double? = null,
    val longitude: Double? = null
)

data class OsirisSnapshot(
    val label: String,
    val path: String,
    val items: List<OsirisItem>,
    val fetchedAt: Long = System.currentTimeMillis()
)

/**
 * Read-only bridge to a self-hosted OSIRIS instance. Every call reuses the app's
 * single HTTP boundary so timeouts, user agent and error mapping stay identical
 * to the existing public-intel modules.
 */
object Osiris {

    val presets: List<OsirisFeed> = listOf(
        OsirisFeed("STATUS", "/api/health"),
        OsirisFeed("STATISTIK", "/api/stats"),
        OsirisFeed("GEMPA", "/api/earthquakes"),
        OsirisFeed("PENERBANGAN", "/api/flights"),
        OsirisFeed("KEBAKARAN", "/api/fires"),
        OsirisFeed("CUACA ANTARIKSA", "/api/space-weather"),
        OsirisFeed("ANCAMAN", "/api/cyber-intel"),
        OsirisFeed("KEV", "/api/cyber-threats"),
        OsirisFeed("MALWARE", "/api/malware"),
        OsirisFeed("RANSOMWARE", "/api/ransomware"),
        OsirisFeed("BERITA", "/api/news"),
        OsirisFeed("KAPAL", "/api/maritime")
    )

    private const val MAX_ITEMS = 40
    private const val MAX_FIELDS = 6
    private const val MAX_CELL = 140

    private val titleKeys = listOf(
        "title", "name", "place", "domain", "country", "ip", "id",
        "flight", "vessel", "callSign", "registrar", "service", "event"
    )
    private val subtitleKeys = listOf(
        "subtitle", "region", "city", "operator", "type", "category",
        "status", "date", "time", "published", "breachDate", "countryCode"
    )
    private val latKeys = listOf("lat", "latitude")
    private val lonKeys = listOf("lon", "lng", "longitude")

    /** HTTPS only, no credentials, no loopback/private targets. Path is preserved for subpath hosting. */
    fun normalizeBase(raw: String): String? {
        val value = raw.trim().trimEnd('/')
        if (value.isEmpty()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.US) != "https") return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        if (uri.userInfo != null) return null
        if (host == "localhost" || host == "0.0.0.0" || host == "::1" ||
            host.endsWith(".local") || host.endsWith(".localhost") ||
            host == "127.0.0.1" || host.startsWith("127.") ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            host.startsWith("169.254.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)
        ) return null
        val path = uri.path?.trimEnd('/').orEmpty()
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "https://$host$port$path"
    }

    /** Accept only absolute /api/... routes with no query, fragment or backslash. */
    fun normalizePath(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        if (!value.startsWith("/")) value = "/$value"
        if (!value.startsWith("/api/")) return null
        if (value.any { it == '?' || it == '#' || it == '\\' || it.isWhitespace() }) return null
        return value
    }

    suspend fun ping(base: String, token: String): String = withContext(Dispatchers.IO) {
        val response = Net.getOrNull("$base/api/health", headers = authHeaders(token))
        when (response.status) {
            200 -> {
                val root = runCatching { JSONObject(response.body) }.getOrNull()
                val state = root?.optString("status")?.takeIf { it.isNotBlank() }
                    ?: root?.optString("state")?.takeIf { it.isNotBlank() }
                state?.uppercase(Locale.US) ?: "ACTIVE"
            }
            401, 403 -> throw IOException("OSIRIS access denied (HTTP ${response.status}). Check the token.")
            else -> throw IOException("OSIRIS did not respond (HTTP ${response.status}).")
        }
    }

    suspend fun fetch(base: String, path: String, token: String, label: String): OsirisSnapshot =
        withContext(Dispatchers.IO) {
            val response = Net.getOrNull("$base$path", headers = authHeaders(token))
            if (response.status !in 200..299) {
                throw IOException("Failed to fetch $path (HTTP ${response.status}).")
            }
            val rows = parseRows(response.body)
            if (rows.isEmpty()) throw IOException("Route $path returned no rows.")
            OsirisSnapshot(label = label, path = path, items = rows)
        }

    private fun authHeaders(token: String): Map<String, String> =
        if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${token.trim()}")

    private fun parseRows(body: String): List<OsirisItem> {
        val rootArray = runCatching { JSONArray(body) }.getOrNull()
        val rootObject = if (rootArray == null) runCatching { JSONObject(body) }.getOrNull() else null

        val source: JSONArray = when {
            rootArray != null -> rootArray
            rootObject != null -> {
                var array: JSONArray? = null
                for (key in keysOf(rootObject)) {
                    val value = rootObject.opt(key)
                    if (value is JSONArray) {
                        array = value
                        break
                    }
                }
                array ?: JSONArray().put(rootObject)
            }
            else -> return emptyList()
        }

        val limit = minOf(source.length(), MAX_ITEMS)
        val out = ArrayList<OsirisItem>(limit)
        for (i in 0 until limit) {
            val item = source.optJSONObject(i) ?: continue
            out += toItem(item)
        }
        return out
    }

    private fun toItem(obj: JSONObject): OsirisItem {
        val keys = keysOf(obj)
        val primary = findValue(keys, obj, titleKeys)
        val secondary = findValue(keys, obj, subtitleKeys)
        val consumed = setOfNotNull(
            primary?.first?.lowercase(Locale.US),
            secondary?.first?.lowercase(Locale.US)
        )

        val fields = ArrayList<Pair<String, String>>(MAX_FIELDS)
        for (key in keys) {
            if (fields.size >= MAX_FIELDS) break
            if (key.lowercase(Locale.US) in consumed) continue
            val value = scalar(obj.opt(key))?.take(MAX_CELL) ?: continue
            if (value.isBlank()) continue
            fields += prettyLabel(key) to value
        }

        return OsirisItem(
            title = primary?.second ?: "ENTRI",
            subtitle = secondary?.second ?: "—",
            fields = fields,
            latitude = coordinate(keys, obj, latKeys),
            longitude = coordinate(keys, obj, lonKeys)
        )
    }

    private fun findValue(
        keys: List<String>,
        obj: JSONObject,
        candidates: List<String>
    ): Pair<String, String>? {
        val wanted = candidates.map { it.lowercase(Locale.US) }.toSet()
        for (key in keys) {
            if (key.lowercase(Locale.US) !in wanted) continue
            val value = scalar(obj.opt(key))?.takeIf { it.isNotBlank() } ?: continue
            return key to value
        }
        return null
    }

    private fun coordinate(keys: List<String>, obj: JSONObject, candidates: List<String>): Double? {
        val wanted = candidates.map { it.lowercase(Locale.US) }.toSet()
        for (key in keys) {
            if (key.lowercase(Locale.US) !in wanted) continue
            return obj.optDouble(key).takeUnless { it.isNaN() }
        }
        return null
    }

    private fun keysOf(obj: JSONObject): List<String> = buildList {
        val iterator = obj.keys()
        while (iterator.hasNext()) add(iterator.next())
    }

    private fun prettyLabel(key: String): String =
        key.replace('_', ' ').replace('-', ' ').trim().uppercase(Locale.US)

    private fun scalar(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value.trim()
        is Number, is Boolean -> value.toString()
        is JSONArray -> buildList {
            for (i in 0 until minOf(value.length(), 3)) {
                scalar(value.opt(i))?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }.joinToString(", ").takeIf { it.isNotBlank() }
        is JSONObject -> buildList {
            for (key in keysOf(value).take(3)) {
                scalar(value.opt(key))?.takeIf { it.isNotBlank() }?.let { add("$key: $it") }
            }
        }.joinToString(" · ").takeIf { it.isNotBlank() }
        else -> value.toString()
    }
}
