package com.vector.console.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class MalangCamera(
    val id: String,
    val name: String,
    val streamId: String,
    val address: String,
    val district: String,
    val latitude: Double?,
    val longitude: Double?
) {
    val streamUrl: String get() = "$ORIGIN/cctv-stream/streams/$streamId.m3u8"

    companion object {
        const val ORIGIN = "https://cctv.malangkota.go.id"
    }
}

/**
 * Malang city CCTV. The public map sets session cookies that the camera API and
 * every HLS request require, together with a desktop UA and the map page as
 * Referer. A session lasts about 50 minutes; loading the catalog opens a fresh
 * one and the player reuses its headers for the playlist and all segments.
 */
object MalangCctv {

    private const val MAP_PAGE = "${MalangCamera.ORIGIN}/sebaran-cctv"
    private const val CAMERAS_URL = "${MalangCamera.ORIGIN}/api/v2/get-cameras"
    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    @Volatile
    private var sessionCookie: String? = null

    /** Headers the HLS data source must send for both playlist and segments. */
    fun streamHeaders(): Map<String, String> {
        val cookie = sessionCookie ?: return emptyMap()
        return mapOf(
            "Cookie" to cookie,
            "User-Agent" to USER_AGENT,
            "Referer" to MAP_PAGE
        )
    }

    suspend fun load(): List<MalangCamera> = withContext(Dispatchers.IO) {
        val cookie = openSession()
        sessionCookie = cookie
        fetchCameras(cookie)
    }

    private fun openSession(): String {
        val connection = URL(MAP_PAGE).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        connection.setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
        try {
            val status = connection.responseCode
            val cookies = connection.headerFields
                .filterKeys { it?.equals("Set-Cookie", ignoreCase = true) == true }
                .values
                .flatten()
                .map { it.split(";", limit = 2)[0].trim() }
                .filter { it.isNotBlank() }
                .joinToString("; ")
            connection.inputStream?.use { it.readBytes() }
            if (status !in 200..299 || !cookies.contains("NANCY_TOKEN")) {
                throw IOException(
                    "Malang CCTV session rejected (HTTP $status). " +
                        "The server may be blocking this network."
                )
            }
            return cookies
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchCameras(cookie: String): List<MalangCamera> {
        val payload = "m_kecamatan_id=" + URLEncoder.encode("99", "UTF-8")
        val connection = URL(CAMERAS_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(payload.toByteArray(Charsets.UTF_8).size)
        connection.setRequestProperty("Cookie", cookie)
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Referer", MAP_PAGE)
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status == 403) throw IOException("Malang CCTV session expired. Reload the list.")
            if (status !in 200..299) throw IOException("Camera list failed (HTTP $status).")

            val root = JSONObject(body)
            if (root.optJSONObject("msg_main")?.optBoolean("status") != true) {
                throw IOException("The Malang CCTV server refused the listing request.")
            }
            val rows = root.optJSONObject("msg_detail")?.optJSONArray("list_data")
                ?: return emptyList()

            return buildList {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val id = row.optString("id", "")
                    val streamId = row.optString("stream_id", "")
                    if (id.isBlank() || !streamId.matches(Regex("^\\d+$"))) continue
                    add(
                        MalangCamera(
                            id = id,
                            name = row.optString("name", "Camera").trim().ifBlank { "Camera" },
                            streamId = streamId,
                            address = row.optString("address", "").trim(),
                            district = row.optString("nama_kecamatan", "").trim(),
                            latitude = row.optString("latitude", "").toDoubleOrNull(),
                            longitude = row.optString("longitude", "").toDoubleOrNull()
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
