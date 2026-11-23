package com.vector.console.data.payload

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Minimal ZIP reader/writer.
 *
 * `java.util.zip` cannot do what this needs: [ZipOutputStream] gives no control over the byte
 * layout, and APK Signature Scheme v2 requires knowing exactly where each region starts. The
 * signing digest is computed over three specific byte ranges, so the writer has to hand those
 * offsets back rather than just emit a stream.
 *
 * Two behaviours matter beyond the spec:
 *  - **STORED entries are 4-byte aligned** by padding the extra field, which is what
 *    `zipalign -p 4` does and what the loader prefers for memory-mapped assets.
 *  - **Prepended data is tolerated on read.** A ZIP reader locates the central directory from
 *    the EOCD and computes a delta when the offsets do not match the file, which is how
 *    self-extracting archives and JPEG/APK polyglots work. [read] preserves that delta so a
 *    round trip through [write] does not shift every entry.
 */
object ZipArchive {

    private const val EOCD_SIG = 0x06054b50
    private const val CEN_SIG = 0x02014b50
    private const val LOC_SIG = 0x04034b50

    const val METHOD_STORED = 0
    const val METHOD_DEFLATE = 8

    /** APK signing block IDs that must be stripped before re-signing. */
    private const val SIG_BLOCK_MAGIC = "APK Sig Block 42"

    data class Entry(
        val name: String,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        /** Raw bytes exactly as they appear in the file: deflated if method is 8. */
        val payload: ByteArray,
        val dosTime: Int = 0,
        val dosDate: Int = 0x21 // 1980-01-01, deterministic
    )

    data class Archive(val entries: List<Entry>) {

        fun find(name: String): Entry? = entries.firstOrNull { it.name == name }

        fun without(names: Collection<String>): Archive =
            Archive(entries.filterNot { it.name in names })

        /** Replaces an entry in place, keeping the original ordering. Appends if absent. */
        fun with(name: String, content: ByteArray, compress: Boolean = true): Archive {
            val fresh = Entry(
                name = name,
                method = if (compress) METHOD_DEFLATE else METHOD_STORED,
                crc = crcOf(content),
                compressedSize = 0, // filled by write()
                uncompressedSize = content.size.toLong(),
                payload = if (compress) deflate(content) else content
            )
            val index = entries.indexOfFirst { it.name == name }
            val out = entries.toMutableList()
            if (index >= 0) out[index] = fresh else out += fresh
            return Archive(out)
        }
    }

    /** Byte ranges of the finished archive, needed by [ApkSignerV2]. */
    data class Layout(
        val bytes: ByteArray,
        /** Offset where the central directory begins. */
        val centralDirOffset: Int,
        val centralDirSize: Int,
        val eocdOffset: Int
    )

    // ---------------------------------------------------------------- read

    fun read(bytes: ByteArray): Archive {
        val eocd = findEocd(bytes)
            ?: throw IllegalArgumentException("No ZIP end-of-central-directory record.")

        val entryCount = u16(bytes, eocd + 10)
        var cenOffset = u32(bytes, eocd + 16)
        val cenSize = u32(bytes, eocd + 12)

        // Prepended data shifts every stored offset; recover the delta once.
        val delta = eocd - cenSize - cenOffset
        if (delta != 0 && delta > 0) cenOffset += delta

        val entries = ArrayList<Entry>(entryCount)
        var p = cenOffset
        repeat(entryCount) {
            if (p + 46 > bytes.size || u32(bytes, p) != CEN_SIG) {
                throw IllegalArgumentException("Corrupt central directory at $p.")
            }
            val method = u16(bytes, p + 10)
            val dosTime = u16(bytes, p + 12)
            val dosDate = u16(bytes, p + 14)
            val crc = u32(bytes, p + 16).toLong() and 0xFFFFFFFFL
            val compSize = u32(bytes, p + 20).toLong() and 0xFFFFFFFFL
            val uncompSize = u32(bytes, p + 24).toLong() and 0xFFFFFFFFL
            val nameLen = u16(bytes, p + 28)
            val extraLen = u16(bytes, p + 30)
            val commentLen = u16(bytes, p + 32)
            val localOffset = u32(bytes, p + 42) + delta

            val name = String(bytes, p + 46, nameLen, Charsets.UTF_8)

            val locNameLen = u16(bytes, localOffset + 26)
            val locExtraLen = u16(bytes, localOffset + 28)
            val dataStart = localOffset + 30 + locNameLen + locExtraLen

            val payload = bytes.copyOfRange(dataStart, dataStart + compSize.toInt())
            entries += Entry(
                name = name,
                method = method,
                crc = crc,
                compressedSize = compSize,
                uncompressedSize = uncompSize,
                payload = payload,
                dosTime = dosTime,
                dosDate = dosDate
            )
            p += 46 + nameLen + extraLen + commentLen
        }
        return Archive(entries)
    }

    private fun findEocd(bytes: ByteArray): Int? {
        // The comment field is at most 64 KiB, so the record lives in the last 64 KiB + 22.
        val floor = (bytes.size - 65_557).coerceAtLeast(0)
        for (i in bytes.size - 22 downTo floor) {
            if (u32(bytes, i) == EOCD_SIG) return i
        }
        return null
    }

    // --------------------------------------------------------------- write

    /**
     * Emits a complete ZIP. [prefix] is written before the first local header and is what makes
     * a polyglot: the prefix bytes are outside every declared range, so the archive still reads
     * correctly while the file also parses as a JPEG or MP4.
     */
    fun write(archive: Archive, prefix: ByteArray = ByteArray(0)): Layout {
        val out = ByteArrayOutputStream(estimateSize(archive) + prefix.size)
        out.write(prefix)

        val central = ByteArrayOutputStream()
        val offsets = IntArray(archive.entries.size)

        archive.entries.forEachIndexed { index, entry ->
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            val payload = if (entry.method == METHOD_DEFLATE && entry.payload.isEmpty() &&
                entry.uncompressedSize > 0
            ) {
                throw IllegalArgumentException("Deflated entry ${entry.name} has no payload.")
            } else {
                entry.payload
            }

            offsets[index] = out.size()

            // Alignment: a STORED entry's data must start on a 4-byte boundary. An extra field
            // needs a 4-byte header, so padding is added in multiples of four — which preserves
            // the alignment it was computed to achieve.
            var extra = ByteArray(0)
            if (entry.method == METHOD_STORED) {
                val dataStart = out.size() + 30 + nameBytes.size
                val pad = (4 - (dataStart % 4)) % 4
                if (pad > 0) {
                    val total = pad + 4
                    extra = ByteArray(total)
                    extra[0] = 0x00 // header id 0x0000 = unused
                    extra[1] = 0x00
                    extra[2] = ((total - 4) and 0xFF).toByte()
                    extra[3] = ((total - 4) shr 8).toByte()
                }
            }

            out.write(u32(LOC_SIG.toLong()))
            out.write(u16(20)) // version needed
            out.write(u16(0)) // flags
            out.write(u16(entry.method))
            out.write(u16(entry.dosTime))
            out.write(u16(entry.dosDate))
            out.write(u32(entry.crc))
            out.write(u32(payload.size.toLong()))
            out.write(u32(entry.uncompressedSize))
            out.write(u16(nameBytes.size))
            out.write(u16(extra.size))
            out.write(nameBytes)
            out.write(extra)
            out.write(payload)

            central.write(u32(CEN_SIG.toLong()))
            central.write(u16(20)) // version made by
            central.write(u16(20)) // version needed
            central.write(u16(0)) // flags
            central.write(u16(entry.method))
            central.write(u16(entry.dosTime))
            central.write(u16(entry.dosDate))
            central.write(u32(entry.crc))
            central.write(u32(payload.size.toLong()))
            central.write(u32(entry.uncompressedSize))
            central.write(u16(nameBytes.size))
            central.write(u16(extra.size))
            central.write(u16(0)) // comment length
            central.write(u16(0)) // disk number
            central.write(u16(0)) // internal attrs
            central.write(u32(0)) // external attrs
            central.write(u32(offsets[index].toLong() - prefix.size))
            central.write(nameBytes)
            central.write(extra)
        }

        val cenOffset = out.size()
        val cenBytes = central.toByteArray()
        out.write(cenBytes)

        val eocdOffset = out.size()
        out.write(u32(EOCD_SIG.toLong()))
        out.write(u16(0)) // disk
        out.write(u16(0)) // disk with CD
        out.write(u16(archive.entries.size))
        out.write(u16(archive.entries.size))
        out.write(u32(cenBytes.size.toLong()))
        // Offsets are relative to the archive start, so the prefix is excluded here. A reader
        // that does not know about the prefix recovers the delta from this same mismatch.
        out.write(u32((cenOffset - prefix.size).toLong()))
        out.write(u16(0)) // comment length

        return Layout(
            bytes = out.toByteArray(),
            centralDirOffset = cenOffset,
            centralDirSize = cenBytes.size,
            eocdOffset = eocdOffset
        )
    }

    // ------------------------------------------------------------- helpers

    fun crcOf(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream(bytes.size / 2 + 64)
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun estimateSize(archive: Archive): Int =
        archive.entries.sumOf { it.payload.size + it.name.length + 80 } + 128

    /**
     * Removes the APK Signing Block if present. Re-signing an already-signed APK without this
     * produces a file with two blocks, which the verifier rejects outright.
     */
    fun stripSigningBlock(bytes: ByteArray): ByteArray {
        val eocd = findEocd(bytes) ?: return bytes
        val cenSize = u32(bytes, eocd + 12)
        var cenOffset = u32(bytes, eocd + 16)
        val delta = eocd - cenSize - cenOffset
        if (delta > 0) cenOffset += delta

        val blockEnd = cenOffset
        if (blockEnd < 32) return bytes

        // Magic sits in the 16 bytes immediately before the central directory.
        val magic = String(bytes, blockEnd - 16, 16, Charsets.ISO_8859_1)
        if (magic != SIG_BLOCK_MAGIC) return bytes

        val blockSize = u64(bytes, blockEnd - 24)
        val blockStart = (blockEnd - blockSize - 8).toInt()
        if (blockStart < 0) return bytes

        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, blockStart)
        val tail = bytes.copyOfRange(blockEnd, bytes.size)
        // Patch the EOCD's central-directory offset, which has moved.
        val newCenOffset = (blockStart - delta).toLong()
        writeU32(tail, tail.size - 22 + 16, newCenOffset)
        out.write(tail)
        return out.toByteArray()
    }

    // ------------------------------------------------------------- binary

    private fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun u64(b: ByteArray, at: Int): Long =
        (u32(b, at).toLong() and 0xFFFFFFFFL) or ((u32(b, at + 4).toLong() and 0xFFFFFFFFL) shl 32)

    private fun writeU32(b: ByteArray, at: Int, value: Long) {
        b[at] = (value and 0xFF).toByte()
        b[at + 1] = ((value shr 8) and 0xFF).toByte()
        b[at + 2] = ((value shr 16) and 0xFF).toByte()
        b[at + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun u16(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun u32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )
}
