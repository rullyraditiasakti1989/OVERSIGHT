package com.vector.console.data.payload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recorded Bot API bodies. The shapes are copied from live responses, including the
 * numeric chat ids that are the reason [Telegram.readChat] formats instead of
 * stringifying a Double.
 */
class TelegramProbeTest {

    private val me =
        """{"ok":true,"result":{"id":1000000001,"is_bot":true,"first_name":"Example",""" +
            """"username":"example_bot","can_join_groups":true,""" +
            """"can_read_all_group_messages":false,"supports_inline_queries":false}}"""

    private val updates =
        """{"ok":true,"result":[{"update_id":780621047,"message":{"message_id":3,""" +
            """"from":{"id":8123344571,"is_bot":false,"first_name":"Contoh"},""" +
            """"chat":{"id":8123344571,"first_name":"Contoh","type":"private"},""" +
            """"date":1795312800,"text":"/start"}}]}"""

    private val channel =
        """{"ok":true,"result":[{"update_id":1,"channel_post":{"message_id":9,""" +
            """"chat":{"id":-1002233445566,"title":"OVERSIGHT feed","type":"channel"},""" +
            """"date":1795312800,"text":"boot"}}]}"""

    @Test
    fun `readMe returns username and display name`() {
        val identity = Telegram.readMe(me)
        assertEquals("example_bot", identity?.first)
        assertEquals("Example", identity?.second)
    }

    @Test
    fun `a rejected token has no identity`() {
        val body = """{"ok":false,"error_code":401,"description":"Unauthorized"}"""
        assertNull(Telegram.readMe(body))
        assertNull(Telegram.readChat(body))
    }

    @Test
    fun `readChat formats a large id without an exponent`() {
        val chat = Telegram.readChat(updates)
        assertEquals("8123344571", chat?.first)
        assertEquals("Contoh", chat?.second)
    }

    @Test
    fun `readChat keeps a negative channel id`() {
        val chat = Telegram.readChat(channel)
        assertEquals("-1002233445566", chat?.first)
        assertEquals("OVERSIGHT feed", chat?.second)
    }

    @Test
    fun `a webhook conflict is not a chat`() {
        val body =
            """{"ok":false,"error_code":409,"description":"Conflict: can't use getUpdates """ +
                """method while webhook is active"}"""
        assertNull(Telegram.readChat(body))
    }

    @Test
    fun `probe summary names the missing step when there is no chat`() {
        val probe = TelegramProbe("example_bot", "Example")
        assertTrue(!probe.ready)
        assertTrue(probe.summary().contains("/start"))
        assertTrue(
            TelegramProbe("b", "B", chatId = "1", chatLabel = "Contoh").summary()
                .contains("Contoh")
        )
    }
}
