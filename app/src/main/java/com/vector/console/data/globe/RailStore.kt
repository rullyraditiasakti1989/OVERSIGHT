package com.vector.console.data.globe

import android.content.Context
import com.vector.console.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * KAIStream's schedule files on disk.
 *
 * The upstream syncs daily at 00:05 WIB, so a twelve-hour TTL costs two downloads
 * a day and never serves a snapshot older than a day. A failed refresh serves the
 * last good file with [Document.stale] set, which is the same carry-forward rule
 * every other globe layer uses.
 *
 * A 2.3 MB timetable is not re-read from disk on every tick: [memory] holds the
 * last text keyed by the file's mtime, so the cadence loop makes no file access at
 * all until the cache actually expires.
 */
object RailStore {

    const val TTL_MS = 12L * 60 * 60 * 1000

    private const val BASE = "https://cdn.jsdelivr.net/gh/kaivyy/KAIStream@main/public/data/"

    enum class Doc(val file: String) {
        TIMETABLE("timetable.json"),
        ALERTS("alerts.json"),
        METADATA("metadata.json")
    }

    data class Document(
        val text: String,
        val atMillis: Long,
        val stale: Boolean,
        val bytes: Long,
        val note: String? = null
    )

    private class Mem(val sourceAt: Long, val text: String, val bytes: Long)

    private val memory = ConcurrentHashMap<String, Mem>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun document(
        context: Context,
        doc: Doc,
        now: Long = System.currentTimeMillis(),
        ttlMs: Long = TTL_MS
    ): Document = withContext(Dispatchers.IO) {
        val lock = locks.getOrPut(doc.name) { Mutex() }
        lock.withLock {
            val file = cacheFile(context, doc)
            val modified = if (file.exists()) file.lastModified() else 0L
            val age = if (modified > 0L) now - modified else Long.MAX_VALUE
            val held = memory[doc.name]
            if (age in 0 until ttlMs && held != null && held.sourceAt == modified) {
                return@withLock Document(held.text, modified, stale = false, held.bytes)
            }
            try {
                val body = Net.get(BASE + doc.file)
                if (body.isBlank()) throw IllegalStateException("empty ${doc.file}")
                writeAtomically(file, body)
                memory[doc.name] = Mem(modifiedOf(file), body, body.length.toLong())
                Document(body, modifiedOf(file), stale = false, body.length.toLong())
            } catch (e: Exception) {
                val fallback = if (file.exists()) readText(file) else ""
                val stale = fallback.isNotBlank()
                if (stale) memory[doc.name] = Mem(modified, fallback, fallback.length.toLong())
                Document(
                    text = fallback,
                    atMillis = modified,
                    stale = stale,
                    bytes = fallback.length.toLong(),
                    note = e.message ?: "${doc.file} unavailable"
                )
            }
        }
    }

    /** Drops cached schedule documents. The bundled station table is untouched. */
    fun clear(context: Context) {
        memory.clear()
        File(context.filesDir, "rail").listFiles()?.forEach { it.delete() }
    }

    private fun cacheFile(context: Context, doc: Doc): File {
        val dir = File(context.filesDir, "rail")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, doc.file)
    }

    private fun modifiedOf(file: File): Long = if (file.exists()) file.lastModified() else 0L

    private fun readText(file: File): String =
        runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")

    /** Sibling-then-rename: a kill mid-write cannot leave a torn 2.3 MB schedule. */
    private fun writeAtomically(file: File, body: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(body, Charsets.UTF_8)
        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) {
            file.writeText(body, Charsets.UTF_8)
            temp.delete()
        }
    }
}
