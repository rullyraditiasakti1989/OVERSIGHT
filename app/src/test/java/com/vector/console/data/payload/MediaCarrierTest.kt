package com.vector.console.data.payload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The carrier is the part operators get wrong, so every claim it makes is checked here:
 * does the file still open, does the blob come back out byte for byte, does a bad input fail
 * loudly instead of producing a broken file.
 */
class MediaCarrierTest {

    /** Minimal but structurally valid JPEG: SOI, one APP0, one APP1/Exif, EOI. */
    private fun jpeg(extraApp1: Boolean = true): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // SOI
        // APP0 / JFIF
        out.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10))
        out.write("JFIF\u0000".toByteArray(Charsets.ISO_8859_1))
        out.write(ByteArray(9))
        if (extraApp1) {
            // A decoy Exif segment that injectUserComment must strip.
            val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) +
                "II".toByteArray(Charsets.ISO_8859_1) +
                byteArrayOf(0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            out.write(0xFF)
            out.write(0xE1)
            val len = payload.size + 2
            out.write(byteArrayOf((len shr 8).toByte(), (len and 0xFF).toByte()))
            out.write(payload)
        }
        // SOS then a token scan payload then EOI
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08))
        out.write(ByteArray(16) { 0x11 })
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun archiveBytes(): ByteArray = ZipArchive.write(
        ZipArchive.Archive(emptyList())
            .with("AndroidManifest.xml", "m".toByteArray(), compress = false)
            .with("classes.dex", ByteArray(1024) { it.toByte() }, compress = true)
    ).bytes

    @Test
    fun `sniff identifies the three cover types`() {
        assertEquals(MediaCarrier.Kind.JPEG, MediaCarrier.sniff(jpeg()))
        assertEquals(
            MediaCarrier.Kind.PNG,
            MediaCarrier.sniff(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        )
        val mp4 = ByteArray(16).also {
            "ftyp".toByteArray().copyInto(it, 4)
        }
        assertEquals(MediaCarrier.Kind.MP4, MediaCarrier.sniff(mp4))
        assertEquals(MediaCarrier.Kind.UNKNOWN, MediaCarrier.sniff(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `appended archive is still a readable zip`() {
        val cover = jpeg()
        val carrier = MediaCarrier.appendArchive(cover, archiveBytes())

        assertEquals(cover.size + archiveBytes().size, carrier.size)

        // The image header is untouched.
        assertEquals(0xFF.toByte(), carrier[0])
        assertEquals(0xD8.toByte(), carrier[1])

        // The archive region reads correctly despite the prepended cover.
        val blob = carrier.copyOfRange(cover.size, carrier.size)
        val read = ZipArchive.read(blob)
        assertEquals(2, read.entries.size)
        assertNotNull(read.find("AndroidManifest.xml"))
    }

    @Test
    fun `verify reports a healthy carrier`() {
        val cover = jpeg()
        val carrier = MediaCarrier.appendArchive(cover, archiveBytes())
        val report = MediaCarrier.verify(carrier, cover.size)

        assertEquals(MediaCarrier.Kind.JPEG, report.kind)
        assertTrue(report.stillValidMedia)
        assertTrue(report.blobRecoverable)
        assertEquals(cover.size, report.coverBytes)
        assertEquals(archiveBytes().size, report.blobBytes)
    }

    @Test
    fun `verify flags a damaged cover header`() {
        val cover = jpeg()
        val carrier = MediaCarrier.appendArchive(cover, archiveBytes())
        carrier[1] = 0x00 // destroy SOI
        val report = MediaCarrier.verify(carrier, cover.size)
        assertFalse(report.stillValidMedia)
        assertTrue(report.note.contains("header"))
    }

    @Test
    fun `verify flags a non-zip blob`() {
        val cover = jpeg()
        val carrier = cover + ByteArray(64) { 0x7A }
        val report = MediaCarrier.verify(carrier, cover.size)
        assertFalse(report.blobRecoverable)
    }

    @Test
    fun `appending to an unknown cover is refused`() {
        val error = runCatching {
            MediaCarrier.appendArchive(byteArrayOf(1, 2, 3, 4), archiveBytes())
        }.exceptionOrNull()
        assertNotNull(error)
    }

    @Test
    fun `exif user comment round trips`() {
        val blob = "config-token-abc123".toByteArray()
        val injected = MediaCarrier.injectUserComment(jpeg(), blob)

        assertTrue(MediaCarrier.sniff(injected) == MediaCarrier.Kind.JPEG)
        val extracted = MediaCarrier.readUserComment(injected)
        assertNotNull(extracted)
        assertEquals(String(blob), String(extracted!!))
    }

    @Test
    fun `exif injection replaces an existing segment rather than adding one`() {
        val injected = MediaCarrier.injectUserComment(jpeg(), "x".toByteArray())
        var app1Count = 0
        var p = 2
        while (p + 4 <= injected.size) {
            val marker = injected[p + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            if (marker == 0xE1) app1Count++
            val len = ((injected[p + 2].toInt() and 0xFF) shl 8) or
                (injected[p + 3].toInt() and 0xFF)
            p += 2 + len
        }
        assertEquals(1, app1Count)
    }

    @Test
    fun `user comment survives both small and large blobs`() {
        // The 8-byte charset designator means the value is always an offset, never inline —
        // this guards the branch that decides which it is.
        val tiny = MediaCarrier.injectUserComment(jpeg(), "abc".toByteArray())
        assertEquals("abc", String(MediaCarrier.readUserComment(tiny)!!))

        val larger = MediaCarrier.injectUserComment(jpeg(), "abcdefghij".toByteArray())
        assertEquals("abcdefghij", String(MediaCarrier.readUserComment(larger)!!))
    }

    @Test
    fun `readUserComment on a clean jpeg returns null`() {
        assertNull(MediaCarrier.readUserComment(jpeg(extraApp1 = false)))
        assertNull(MediaCarrier.readUserComment(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `spoofedName strips path characters and applies the extension`() {
        assertEquals("IMG_2026.jpg", MediaCarrier.spoofedName("IMG_2026", "jpg"))
        assertEquals("a_b_c.apk", MediaCarrier.spoofedName("a/b:c", "apk"))
        assertEquals("IMG_2026.jpg", MediaCarrier.spoofedName("   ", "jpg"))
        assertEquals("photo.jpeg", MediaCarrier.spoofedName("photo", ".jpeg"))
    }
}
