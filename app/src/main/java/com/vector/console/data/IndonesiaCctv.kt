package com.vector.console.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class IndonesiaCamera(
    val id: String,
    val name: String,
    val city: String,
    val latitude: Double?,
    val longitude: Double?,
    val url: String,
    val source: String,
    val available: Boolean = true
) {
    val hls: Boolean get() = url.contains(".m3u8", ignoreCase = true)
}

/**
 * Bundled catalog of public Indonesian cameras. The JSON asset is generated from
 * public dashboards and public camera lists; it ships with the app so the list is
 * available offline even though the streams themselves need a connection.
 */
object IndonesiaCctv {

    private const val ASSET = "cctv_indonesia.json"

    @Volatile
    private var cached: List<IndonesiaCamera>? = null

    suspend fun load(context: Context): List<IndonesiaCamera> = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val array = JSONArray(text)
        val parsed = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("url", "")
                if (url.isBlank()) continue
                add(
                    IndonesiaCamera(
                        id = item.optString("id", "id-$i"),
                        name = item.optString("name", "Camera").ifBlank { "Camera" },
                        city = item.optString("city", ""),
                        latitude = item.optDouble("lat").takeUnless { it.isNaN() },
                        longitude = item.optDouble("lng").takeUnless { it.isNaN() },
                        url = url,
                        source = item.optString("source", "Publik").ifBlank { "Publik" },
                        available = item.optBoolean("available", true)
                    )
                )
            }
        }
        cached = parsed
        parsed
    }
}
