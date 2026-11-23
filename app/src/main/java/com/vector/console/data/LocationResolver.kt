package com.vector.console.data

/** One observed Wi-Fi access point. BSSID is the only field the resolver needs. */
data class WifiObservation(val bssid: String, val signalDbm: Int, val ssid: String = "")

/** One observed cell. Area code plus cell id together identify the sector. */
data class CellObservation(
    val radioType: String,
    val mcc: Int,
    val mnc: Int,
    val areaCode: Int,
    val cellId: Long,
    val signalDbm: Int
)

data class ResolvedFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val method: String
)

/**
 * Network-side geolocation from radio fingerprints.
 *
 * No GPS, no satellite lock, works indoors, a few hundred bytes per fix. The trade
 * is accuracy and a third party: you are asking someone else's database where a MAC
 * address or a cell sector lives.
 *
 * beaconDB is the surviving keyless provider — verified live 2026-11-22, Google
 * Geolocation-compatible schema. Mozilla Location Service is gone (404); OpenCellID
 * and WiGLE both require keys; Google's own endpoint requires billing.
 *
 * Hand-rolled JSON on both sides on purpose: `org.json` is a stub under JVM unit
 * tests, so anything routed through it cannot be covered.
 */
object LocationResolver {

    private const val BEACONDB = "https://api.beacondb.net/v1/geolocate"

    /** MACs are lowercased and colon-normalised; duplicates collapse, strongest first. */
    fun normaliseBssids(observations: List<WifiObservation>): List<WifiObservation> =
        observations
            .map { it.copy(bssid = it.bssid.trim().lowercase().replace('-', ':')) }
            .filter { it.bssid.count { ch -> ch == ':' } == 5 }
            // Sort before de-duplicating so the strongest sighting of an AP survives.
            .sortedByDescending { it.signalDbm }
            .distinctBy { it.bssid }
            .take(20) // more than 20 buys nothing and inflates the request

    /** Exposed for tests. Values are hex, ints and booleans — nothing needs escaping. */
    fun buildRequest(
        wifi: List<WifiObservation>,
        cells: List<CellObservation>,
        allowIpFallback: Boolean
    ): String = buildString {
        append("{\"considerIp\":").append(allowIpFallback)
        if (wifi.isNotEmpty()) {
            append(",\"wifiAccessPoints\":[")
            wifi.forEachIndexed { index, ap ->
                if (index > 0) append(',')
                append("{\"macAddress\":\"").append(ap.bssid)
                append("\",\"signalStrength\":").append(ap.signalDbm).append('}')
            }
            append(']')
        }
        if (cells.isNotEmpty()) {
            append(",\"cellTowers\":[")
            cells.forEachIndexed { index, cell ->
                if (index > 0) append(',')
                // radioType lives on the tower, not the envelope — beaconDB 400s otherwise.
                append("{\"radioType\":\"").append(cell.radioType)
                append("\",\"mobileCountryCode\":").append(cell.mcc)
                append(",\"mobileNetworkCode\":").append(cell.mnc)
                append(",\"locationAreaCode\":").append(cell.areaCode)
                append(",\"cellId\":").append(cell.cellId)
                append(",\"signalStrength\":").append(cell.signalDbm).append('}')
            }
            append(']')
        }
        append('}')
    }

    /**
     * Wi-Fi first, cells second, IP last.
     *
     * Wi-Fi typically lands 20-60 m, cells 300 m-2 km, IP 25 km. The response carries
     * `fallback: "ipf"` when only the IP was used, which is the signal that the answer
     * is a city and must not become a track point.
     */
    suspend fun resolve(
        wifi: List<WifiObservation> = emptyList(),
        cells: List<CellObservation> = emptyList(),
        allowIpFallback: Boolean = false
    ): ResolvedFix? {
        val accessPoints = normaliseBssids(wifi)
        if (accessPoints.isEmpty() && cells.isEmpty() && !allowIpFallback) return null

        val response = Net.request(
            url = BEACONDB,
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = buildRequest(accessPoints, cells, allowIpFallback).toByteArray(Charsets.UTF_8),
            timeoutMs = 12_000
        )
        if (response.status != 200) return null
        return parse(response.body, accessPoints.isNotEmpty() || cells.isNotEmpty())
    }

    /** Exposed for tests. Rejects IP-only fallbacks unless [radioBacked] is true. */
    fun parse(body: String, radioBacked: Boolean): ResolvedFix? {
        val root = runCatching { MiniJson.parseObject(body) }.getOrNull() ?: return null
        val fallback = (root["fallback"] as? String).orEmpty()
        if (fallback == "ipf" && !radioBacked) return null
        val location = root["location"] as? Map<*, *> ?: return null
        val lat = (location["lat"] as? Number)?.toDouble() ?: return null
        val lon = (location["lng"] as? Number)?.toDouble() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return ResolvedFix(
            lat = lat,
            lon = lon,
            accuracyM = (root["accuracy"] as? Number)?.toDouble() ?: 0.0,
            method = when {
                fallback == "ipf" -> "IP"
                fallback.isNotBlank() -> fallback.uppercase()
                else -> "RADIO"
            }
        )
    }

    /** A fix coarser than this is a city, not a position worth a track point. */
    fun isUsable(fix: ResolvedFix?, maxAccuracyM: Double = 5_000.0): Boolean =
        fix != null && fix.accuracyM > 0 && fix.accuracyM <= maxAccuracyM
}

/**
 * Minimal JSON reader for the flat payloads this layer consumes.
 *
 * Deliberately not `org.json`: that class is a stub on the JVM test classpath, so
 * any parse routed through it cannot be tested. Only what a geolocation response
 * contains is supported — objects, arrays, strings, numbers, booleans, null.
 */
internal object MiniJson {

    fun parseObject(text: String): Map<*, *>? = runCatching {
        val reader = Reader(text.trim())
        val value = reader.value()
        reader.skipWhitespace()
        value as? Map<*, *>
    }.getOrNull()

    private class Reader(private val s: String) {
        private var i = 0

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWhitespace()
            if (i >= s.length) throw IllegalArgumentException("eof")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> num()
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException(word)
            i += word.length
            return result
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skipWhitespace()
                val key = str()
                skipWhitespace()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("colon")
                i++
                out[key] = value()
                skipWhitespace()
                if (i >= s.length) throw IllegalArgumentException("eof")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("separator")
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val out = mutableListOf<Any?>()
            skipWhitespace()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out += value()
                skipWhitespace()
                if (i >= s.length) throw IllegalArgumentException("eof")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("separator")
                }
            }
        }

        private fun str(): String {
            if (i >= s.length || s[i] != '"') throw IllegalArgumentException("quote")
            i++
            val out = StringBuilder()
            while (i < s.length) {
                when (val c = s[i++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (i >= s.length) throw IllegalArgumentException("escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalArgumentException("unicode")
                                out.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("escape")
                        }
                    }
                    else -> out.append(c)
                }
            }
            throw IllegalArgumentException("unterminated")
        }

        private fun num(): Double {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                    ((s[i] == '-' || s[i] == '+') && s[i - 1].let { it == 'e' || it == 'E' }))
            ) i++
            val token = s.substring(start, i)
            return token.toDoubleOrNull() ?: throw IllegalArgumentException("number")
        }
    }
}
