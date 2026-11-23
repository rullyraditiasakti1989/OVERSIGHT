package com.vector.console.data.payload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ZIP layer is load-bearing for both repacking and the media polyglot, and its tolerance for
 * prepended data is the single behaviour the polyglot depends on. These lock it down.
 */
class ZipArchiveTest {

    private fun sample(): ZipArchive.Archive = ZipArchive.Archive(emptyList())
        .with("AndroidManifest.xml", "manifest-bytes".toByteArray(), compress = false)
        .with("classes.dex", ByteArray(2048) { (it % 251).toByte() }, compress = true)
        .with("assets/oversight_payload.json", "{\"v\":1}".toByteArray(), compress = true)

    @Test
    fun `round trip preserves names and content`() {
        val layout = ZipArchive.write(sample())
        val read = ZipArchive.read(layout.bytes)

        assertEquals(3, read.entries.size)
        assertEquals(
            listOf("AndroidManifest.xml", "classes.dex", "assets/oversight_payload.json"),
            read.entries.map { it.name }
        )
        assertEquals("manifest-bytes", String(read.find("AndroidManifest.xml")!!.payload))
    }

    @Test
    fun `deflated entries survive a round trip`() {
        val original = ByteArray(4096) { (it % 97).toByte() }
        val archive = ZipArchive.Archive(emptyList())
            .with("blob.bin", original, compress = true)

        val entry = ZipArchive.read(ZipArchive.write(archive).bytes).find("blob.bin")!!
        assertEquals(8, entry.method)
        assertTrue("deflation should shrink a repetitive buffer", entry.payload.size < original.size)
        assertEquals(original.size.toLong(), entry.uncompressedSize)
    }

    @Test
    fun `layout offsets are self-consistent`() {
        val layout = ZipArchive.write(sample())
        // Central directory must sit immediately before the EOCD.
        assertEquals(layout.bytes.size - 22, layout.eocdOffset)
        assertEquals(layout.centralDirOffset + layout.centralDirSize, layout.eocdOffset)
    }

    @Test
    fun `prepended data does not break reading`() {
        // This is the polyglot mechanism: a JPEG in front of a ZIP.
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(500) { 0x41 }
        val layout = ZipArchive.write(sample(), prefix = jpeg)
        val read = ZipArchive.read(layout.bytes)

        assertEquals(3, read.entries.size)
        assertEquals("manifest-bytes", String(read.find("AndroidManifest.xml")!!.payload))
    }

    @Test
    fun `stored entries are four byte aligned`() {
        val archive = ZipArchive.Archive(emptyList())
            .with("a.bin", ByteArray(3) { 1 }, compress = false)
            .with("bb.bin", ByteArray(5) { 2 }, compress = false)
        val bytes = ZipArchive.write(archive).bytes

        // Walk local headers and confirm each stored payload starts on a 4-byte boundary.
        var p = 0
        var checked = 0
        // Local file header signature is 0x04034b50, so bytes are 50 4B 03 04. The central
        // directory starts 50 4B 01 02 — checking only the first two bytes would walk straight
        // into it and misread every central record as a local one.
        while (p + 30 <= bytes.size &&
            bytes[p] == 0x50.toByte() && bytes[p + 1] == 0x4B.toByte() &&
            bytes[p + 2] == 0x03.toByte() && bytes[p + 3] == 0x04.toByte()
        ) {
            val method = (bytes[p + 8].toInt() and 0xFF) or ((bytes[p + 9].toInt() and 0xFF) shl 8)
            val nameLen = (bytes[p + 26].toInt() and 0xFF) or
                ((bytes[p + 27].toInt() and 0xFF) shl 8)
            val extraLen = (bytes[p + 28].toInt() and 0xFF) or
                ((bytes[p + 29].toInt() and 0xFF) shl 8)
            val compSize = (bytes[p + 18].toInt() and 0xFF) or
                ((bytes[p + 19].toInt() and 0xFF) shl 8) or
                ((bytes[p + 20].toInt() and 0xFF) shl 16) or
                ((bytes[p + 21].toInt() and 0xFF) shl 24)
            if (method == 0) {
                val dataStart = p + 30 + nameLen + extraLen
                assertEquals("stored entry not aligned at $dataStart", 0, dataStart % 4)
                checked++
            }
            p += 30 + nameLen + extraLen + compSize
        }
        assertEquals(2, checked)
    }

    @Test
    fun `with replaces in place and appends when absent`() {
        val archive = sample()
            .with("classes.dex", "replaced".toByteArray(), compress = false)
            .with("new.txt", "added".toByteArray(), compress = false)

        assertEquals(4, archive.entries.size)
        assertEquals("classes.dex", archive.entries[1].name)
        assertEquals("replaced", String(archive.find("classes.dex")!!.payload))
        assertEquals("new.txt", archive.entries.last().name)
    }

    @Test
    fun `without removes every named entry`() {
        val trimmed = sample().without(listOf("classes.dex", "nope.txt"))
        assertEquals(2, trimmed.entries.size)
        assertNull(trimmed.find("classes.dex"))
    }

    @Test
    fun `stripSigningBlock is a no-op on an unsigned archive`() {
        val layout = ZipArchive.write(sample())
        val stripped = ZipArchive.stripSigningBlock(layout.bytes)
        assertEquals(layout.bytes.size, stripped.size)
        assertEquals(3, ZipArchive.read(stripped).entries.size)
    }

    @Test
    fun `crc of identical content is stable`() {
        val a = ZipArchive.crcOf("hello".toByteArray())
        val b = ZipArchive.crcOf("hello".toByteArray())
        assertEquals(a, b)
        assertTrue(ZipArchive.crcOf("hellp".toByteArray()) != a)
    }

    @Test
    fun `reading garbage throws rather than returning empty`() {
        val error = runCatching { ZipArchive.read(ByteArray(100)) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is IllegalArgumentException)
    }
}
