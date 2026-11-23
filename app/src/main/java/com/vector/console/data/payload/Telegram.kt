package com.vector.console.data.payload

import com.vector.console.data.Net
import com.vector.console.data.globe.Json
import com.vector.console.data.globe.arr
import com.vector.console.data.globe.bool
import com.vector.console.data.globe.numOrString
import com.vector.console.data.globe.obj
import com.vector.console.data.globe.str
import java.io.IOException

/**
 * What a bot token turns out to be, and any chat the bot has already been messaged from.
 *
 * [chatId] is discovered rather than typed. A bot cannot open a conversation — the
 * recipient has to send `/start` first — so the honest flow is: verify the token, tap
 * START in Telegram, verify again, and the id lands here on its own.
 */
data class TelegramProbe(
    val username: String,
    val displayName: String,
    val chatId: String? = null,
    val chatLabel: String? = null,
    /** Set when the token is good but the chat could not be read (webhook set, no updates). */
    val note: String? = null
) {
    val ready: Boolean get() = chatId != null

    fun summary(): String = when {
        !ready -> note ?: "No chat yet — send /start to @$username, then verify again."
        else -> "@$username · chat ${chatLabel ?: chatId}"
    }
}

/**
 * Bot API v0 probes. Nothing here uploads: the document leg lives in the template's
 * `Beacon.sendDocument`, and this class only answers "is this token real, and where
 * should it report".
 */
object Telegram {

    private const val BASE = "https://api.telegram.org/bot"

    suspend fun probe(token: String): TelegramProbe {
        val clean = token.trim()
        require(clean.isNotEmpty()) { "Bot token is empty." }

        val me = Net.get("$BASE$clean/getMe")
        val identity = readMe(me) ?: throw IOException("Token rejected: getMe returned no bot.")

        // A webhook consumes updates, so this call answers 409 instead of a list.
        // That is not a token problem and must not be reported as one.
        val updates = runCatching { Net.get("$BASE$clean/getUpdates?limit=100&timeout=0") }
        val chat = updates.getOrNull()?.let { readChat(it) }
        val note = when {
            chat != null -> null
            updates.isFailure -> "Token is valid. Updates are held by a webhook, so the chat id " +
                "must be pasted by hand."
            else -> null
        }

        return TelegramProbe(
            username = identity.first,
            displayName = identity.second,
            chatId = chat?.first,
            chatLabel = chat?.second,
            note = note
        )
    }

    /** `@name` and display name from a `getMe` body, or null when the call failed. */
    fun readMe(body: String): Pair<String, String>? {
        val root = Json.parse(body) ?: return null
        if (root.bool("ok") != true) return null
        val bot = root.obj("result") ?: return null
        val username = bot.str("username") ?: return null
        return username to (bot.str("first_name") ?: username)
    }

    /**
     * First chat id in a `getUpdates` body.
     *
     * The id is a JSON number, and a bot id is around 1e9 while a channel id is around
     * -1e12, so it is formatted as a whole number: `Double.toString()` would hand back
     * `1.2345678E7`, which the Bot API rejects.
     */
    fun readChat(body: String): Pair<String, String>? {
        val root = Json.parse(body) ?: return null
        if (root.bool("ok") != true) return null
        val updates = root.arr("result") ?: return null
        for (update in updates.items) {
            val message = update.obj("message")
                ?: update.obj("edited_message")
                ?: update.obj("channel_post")
                ?: update.obj("my_chat_member")
                ?: continue
            val chat = message.obj("chat") ?: continue
            val numeric = chat.numOrString("id") ?: continue
            val id = "%.0f".format(numeric)
            val label = chat.str("title")
                ?: chat.str("username")?.let { "@$it" }
                ?: listOfNotNull(chat.str("first_name"), chat.str("last_name"))
                    .joinToString(" ")
                    .trim()
                    .ifBlank { id }
            return id to label
        }
        return null
    }
}
