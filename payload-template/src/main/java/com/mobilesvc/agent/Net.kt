package com.mobilesvc.agent

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Single outbound path. Everything the agent sends goes through here. */
object Net {

    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile"

    class Response(val status: Int, val body: String)

    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        timeoutMs: Int = 15_000
    ): Response {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
            }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            Response(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (error: Exception) {
            Response(-1, error.message.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    fun postJson(url: String, json: String): Response = request(
        url = url,
        method = "POST",
        headers = mapOf("Accept" to "application/json"),
        body = json.toByteArray(Charsets.UTF_8),
        contentType = "application/json"
    )

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Multipart upload, used for audio clips and captured files. */
    fun postMultipart(
        url: String,
        field: String,
        fileName: String,
        mime: String,
        bytes: ByteArray
    ): Response {
        val boundary = "----agent" + System.nanoTime().toString(16)
        val head = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"").append(field)
            append("\"; filename=\"").append(fileName).append("\"\r\n")
            append("Content-Type: ").append(mime).append("\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val payload = ByteArray(head.size + bytes.size + tail.size)
        head.copyInto(payload, 0)
        bytes.copyInto(payload, head.size)
        tail.copyInto(payload, head.size + bytes.size)

        return request(
            url = url,
            method = "POST",
            body = payload,
            contentType = "multipart/form-data; boundary=$boundary",
            timeoutMs = 60_000
        )
    }
}
