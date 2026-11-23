package com.vector.console.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each rule is a combination, not a permission. These tests exist to keep the combinations
 * from decaying into single-permission noise, which is how every mobile antivirus ends up
 * flagging screen readers.
 */
class ImplantScanTest {

    private val store = "com.android.vending"

    private fun facts(
        pkg: String = "com.example.app",
        label: String = "Example",
        isSystem: Boolean = false,
        hasLauncher: Boolean = true,
        installer: String? = store,
        permissions: Set<String> = emptySet(),
        accessibility: Boolean = false,
        notificationListener: Boolean = false,
        admin: Boolean = false
    ) = PackageFacts(
        pkg = pkg,
        label = label,
        isSystem = isSystem,
        hasLauncher = hasLauncher,
        installer = installer,
        permissions = permissions,
        hasAccessibilityService = accessibility,
        hasNotificationListener = notificationListener,
        isDeviceAdmin = admin
    )

    private val audio = android.Manifest.permission.RECORD_AUDIO
    private val sms = android.Manifest.permission.READ_SMS
    private val net = android.Manifest.permission.INTERNET
    private val boot = android.Manifest.permission.RECEIVE_BOOT_COMPLETED
    private val overlay = android.Manifest.permission.SYSTEM_ALERT_WINDOW

    @Test
    fun `a normal store app with a launcher is clean`() {
        val result = ImplantScan.evaluate(listOf(facts(permissions = setOf(net))))
        assertEquals(Verdict.CLEAN, result.verdict)
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun `accessibility alone is not a finding`() {
        // Screen readers, switch access, password managers. All legitimate.
        val result = ImplantScan.evaluate(
            listOf(facts(permissions = setOf(net), accessibility = true))
        )
        assertTrue(result.findings.none { it.rule == Rule.ACCESSIBILITY_EXFIL })
    }

    @Test
    fun `accessibility plus audio is the spyware triad`() {
        val result = ImplantScan.evaluate(
            listOf(facts(permissions = setOf(net, audio), accessibility = true))
        )
        val hit = result.findings.single { it.rule == Rule.ACCESSIBILITY_EXFIL }
        assertTrue(hit.evidence.contains("RECORD_AUDIO"))
        assertEquals(Verdict.REVIEW, result.verdict) // 35 alone, plus headless? no launcher is true
    }

    @Test
    fun `accessibility plus sms is also the triad`() {
        val result = ImplantScan.evaluate(
            listOf(facts(permissions = setOf(net, sms), accessibility = true))
        )
        assertTrue(result.findings.any { it.rule == Rule.ACCESSIBILITY_EXFIL })
    }

    @Test
    fun `headless app with network is flagged`() {
        val result = ImplantScan.evaluate(
            listOf(facts(hasLauncher = false, permissions = setOf(net)))
        )
        assertTrue(result.findings.any { it.rule == Rule.HEADLESS_NETWORK })
    }

    @Test
    fun `overlay without launcher outranks plain headless`() {
        val result = ImplantScan.evaluate(
            listOf(facts(hasLauncher = false, permissions = setOf(net, overlay)))
        )
        val rules = result.findings.map { it.rule }
        assertTrue(Rule.OVERLAY_HEADLESS in rules)
        assertTrue(
            result.findings.indexOfFirst { it.rule == Rule.OVERLAY_HEADLESS } <
                result.findings.indexOfFirst { it.rule == Rule.HEADLESS_NETWORK }
        )
    }

    @Test
    fun `device admin is always a finding`() {
        val result = ImplantScan.evaluate(listOf(facts(admin = true)))
        assertTrue(result.findings.any { it.rule == Rule.DEVICE_ADMIN })
    }

    @Test
    fun `notification listener with network reads OTPs`() {
        val result = ImplantScan.evaluate(
            listOf(facts(permissions = setOf(net), notificationListener = true))
        )
        assertTrue(result.findings.any { it.rule == Rule.NOTIFICATION_EXFIL })
    }

    @Test
    fun `sideloaded sensitive app is flagged, sideloaded harmless app is not`() {
        val bad = ImplantScan.evaluate(
            listOf(facts(installer = null, permissions = setOf(net, audio)))
        )
        assertTrue(bad.findings.any { it.rule == Rule.SIDELOAD_SENSITIVE })

        val fine = ImplantScan.evaluate(
            listOf(facts(installer = null, permissions = setOf(net)))
        )
        assertTrue(fine.findings.none { it.rule == Rule.SIDELOAD_SENSITIVE })
    }

    @Test
    fun `system packages are excluded`() {
        val result = ImplantScan.evaluate(
            listOf(facts(isSystem = true, hasLauncher = false, permissions = setOf(net, audio)))
        )
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun `own package is excluded`() {
        val result = ImplantScan.evaluate(
            listOf(facts(pkg = "com.vector.console", hasLauncher = false, admin = true)),
            ownPackage = "com.vector.console"
        )
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun `a full commodity stealer reaches SUSPECT`() {
        val stealer = facts(
            pkg = "com.free.flashlight",
            label = "Flashlight",
            hasLauncher = false,
            installer = null,
            permissions = setOf(net, audio, sms, boot, overlay),
            accessibility = true,
            notificationListener = true,
            admin = true
        )
        val result = ImplantScan.evaluate(listOf(stealer))
        assertEquals(Verdict.SUSPECT, result.verdict)
        // Every rule should have fired.
        assertEquals(Rule.values().size, result.findings.size)
        assertEquals(131, result.score) // 35+25+20+15+12+10+8+6
    }

    @Test
    fun `score is the sum of distinct rule weights`() {
        val result = ImplantScan.evaluate(
            listOf(facts(hasLauncher = false, permissions = setOf(net, boot)))
        )
        val expected = result.findings.sumOf { it.rule.weight }
        assertEquals(expected, result.score)
        assertTrue(result.score > 0)
    }

    @Test
    fun `findings are ordered strongest first`() {
        val result = ImplantScan.evaluate(
            listOf(
                facts(pkg = "com.a", hasLauncher = false, permissions = setOf(net)),
                facts(pkg = "com.b", admin = true, permissions = setOf(net, audio), accessibility = true)
            )
        )
        val weights = result.findings.map { it.rule.weight }
        assertEquals(weights.sortedDescending(), weights)
    }

    @Test
    fun `byPackage groups and sorts by total weight`() {
        val result = ImplantScan.evaluate(
            listOf(
                facts(pkg = "com.weak", hasLauncher = false, permissions = setOf(net, boot)),
                facts(pkg = "com.strong", admin = true, permissions = setOf(net, audio), accessibility = true)
            )
        )
        val grouped = result.byPackage()
        assertEquals("com.strong", grouped.first().first)
        assertEquals(2, grouped.size)
    }

    @Test
    fun `empty device scans clean`() {
        val result = ImplantScan.evaluate(emptyList())
        assertEquals(Verdict.CLEAN, result.verdict)
        assertEquals(0, result.scanned)
        assertFalse(result.verdict == Verdict.SUSPECT)
    }

    @Test
    fun `review threshold sits at fifteen`() {
        // DEVICE_ADMIN alone is 15 → REVIEW.
        assertEquals(Verdict.REVIEW, ImplantScan.evaluate(listOf(facts(admin = true))).verdict)
        // HEADLESS_NETWORK alone is 12 → still CLEAN.
        assertEquals(
            Verdict.CLEAN,
            ImplantScan.evaluate(listOf(facts(hasLauncher = false, permissions = setOf(net)))).verdict
        )
    }

    @Test
    fun `known stores list covers the common installers`() {
        assertTrue("com.android.vending" in ImplantScan.KNOWN_STORES)
        assertTrue("com.android.packageinstaller" in ImplantScan.KNOWN_STORES)
        assertFalse("com.example.unknown" in ImplantScan.KNOWN_STORES)
    }
}
