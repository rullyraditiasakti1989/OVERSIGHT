package com.vector.console.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The keyless presence probes must never grade a generic page as a hit.
 *
 * Both upstreams answer HTTP 200 with a full page for numbers they do not know;
 * the distinction lives in one marker in the body. These are the bodies measured
 * for `081234567890` on 2026-11-22.
 */
class PresenceVerdictTest {

    @Test
    fun `whatsapp rejects invalid numbers but a clean deep link is unknown`() {
        assertEquals(PresenceVerdict.ABSENT, IdentityIntel.whatsappVerdict(200, "Phone number shared via url is invalid"))
        assertEquals(PresenceVerdict.ABSENT, IdentityIntel.whatsappVerdict(200, "{\"error\":\"invalid_number\"}"))
        assertEquals(PresenceVerdict.UNKNOWN, IdentityIntel.whatsappVerdict(200, "<html>deep link page</html>"))
        assertEquals(PresenceVerdict.UNKNOWN, IdentityIntel.whatsappVerdict(503, "unavailable"))
    }

    @Test
    fun `a generic telegram contact page is not a registered profile`() {
        // The measured body for an unregistered Indonesian number. The old code
        // only rejected the exact string "Telegram: Contact" and graded this PRESENT.
        val generic = "og:title\" content=\"Join group chat on Telegram\""
        assertEquals(PresenceVerdict.UNKNOWN, IdentityIntel.telegramPhoneVerdict(200, generic))
        assertEquals(
            PresenceVerdict.UNKNOWN,
            IdentityIntel.telegramPhoneVerdict(200, "Telegram: Contact")
        )
        assertEquals(PresenceVerdict.UNKNOWN, IdentityIntel.telegramPhoneVerdict(404, null))
        assertEquals(PresenceVerdict.PRESENT, IdentityIntel.telegramPhoneVerdict(200, "Example Name"))
    }

    @Test
    fun `the result carries the verdict without changing the legacy boolean`() {
        val hit = PresenceResult("TELEGRAM", "@example_bot", true, "public", PresenceVerdict.PRESENT)
        val unknown = PresenceResult("WHATSAPP", "+62812", false, "generic")
        assertEquals(PresenceVerdict.PRESENT, hit.verdict)
        assertEquals(PresenceVerdict.ABSENT, unknown.verdict)
        assertEquals(true, hit.registered)
    }
}
