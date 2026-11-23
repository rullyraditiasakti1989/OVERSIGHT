package com.vector.console.data.globe

import android.content.Context
import com.vector.console.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One element set as published. [name] is empty when the feed omits it. */
data class TleRecord(val name: String, val line1: String, val line2: String)

/**
 * CelesTrak element sets on disk.
 *
 * TTL is six hours because that is the cadence the catalogue itself updates at.
 * A failed fetch never empties the plate: the last good file is served and
 * flagged stale, which is the only honest way to render a satellite layer when
 * the upstream is rate-limiting.
 */
object TleStore {

    const val TTL_MS = 6 * 60 * 60 * 1000L

    private const val BASE = "https://celestrak.org/NORAD/elements/gp.php"

    /** Groups worth the bytes on a phone. `active` is 2.7 MB; `starlink` is worse. */
    val GROUPS = listOf("stations", "visual", "gps-ops", "galileo", "geo", "resource", "weather", "science")

    private val locks = ConcurrentHashMap<String, Mutex>()

    data class Result(
        val records: List<TleRecord>,
        val fetchedAtMillis: Long,
        val stale: Boolean,
        val note: String? = null
    )

    /**
     * Reads a group, refreshing it when the disk copy is older than [ttlMs].
     *
     * Single-flight per group: two layers asking for the same group at once make
     * one request, not two, because CelesTrak rate-limits aggressively.
     */
    suspend fun records(
        context: Context,
        group: String,
        now: Long = System.currentTimeMillis(),
        ttlMs: Long = TTL_MS
    ): Result = withContext(Dispatchers.IO) {
        val lock = locks.getOrPut(group) { Mutex() }
        lock.withLock {
            val file = cacheFile(context, group)
            val age = if (file.exists()) now - file.lastModified() else Long.MAX_VALUE
            if (age in 0 until ttlMs) {
                val cached = parseTle(readText(file))
                if (cached.isNotEmpty()) {
                    return@withLock Result(cached, file.lastModified(), stale = false)
                }
            }
            try {
                val body = Net.get("$BASE?GROUP=$group&FORMAT=tle")
                val parsed = parseTle(body)
                if (parsed.isEmpty()) {
                    throw IllegalStateException("empty element set")
                }
                writeAtomically(file, body)
                Result(parsed, now, stale = false)
            } catch (e: Exception) {
                val fallback = if (file.exists()) parseTle(readText(file)) else emptyList()
                Result(
                    records = fallback,
                    fetchedAtMillis = if (file.exists()) file.lastModified() else 0L,
                    stale = fallback.isNotEmpty(),
                    note = e.message ?: "element set unavailable"
                )
            }
        }
    }

    /** Drops every cached group. Used by the Settings refresh control. */
    fun clear(context: Context) {
        val dir = File(context.filesDir, "globe")
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun cacheFile(context: Context, group: String): File {
        val dir = File(context.filesDir, "globe")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "tle-$group.txt")
    }

    private fun readText(file: File): String =
        runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")

    /** Write to a sibling then rename, so a kill mid-write cannot leave a torn file. */
    private fun writeAtomically(file: File, body: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(body, Charsets.UTF_8)
        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) {
            file.writeText(body, Charsets.UTF_8)
            temp.delete()
        }
    }

    /**
     * Parses a TLE block.
     *
     * Handles the three shapes CelesTrak and friends actually emit: named
     * three-line records, bare two-line records, `\r\n` endings, and a trailing
     * blank line. Anything that is not a well-formed pair is skipped rather than
     * shifting every following record by one line.
     */
    fun parseTle(text: String): List<TleRecord> {
        if (text.isBlank()) return emptyList()
        val lines = text.split("\r\n", "\n", "\r")
        val out = ArrayList<TleRecord>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd()
            when {
                line.startsWith("1 ") && i + 1 < lines.size && lines[i + 1].trimEnd().startsWith("2 ") -> {
                    out.add(TleRecord("", line.trim(), lines[i + 1].trim()))
                    i += 2
                }
                line.isNotBlank() &&
                    !line.startsWith("1 ") &&
                    i + 2 < lines.size &&
                    lines[i + 1].trimEnd().startsWith("1 ") &&
                    lines[i + 2].trimEnd().startsWith("2 ") -> {
                    out.add(TleRecord(line.trim(), lines[i + 1].trim(), lines[i + 2].trim()))
                    i += 3
                }
                else -> i += 1
            }
        }
        return out
    }
}
