package com.vector.console.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * Single HTTP boundary for the whole app. Every outbound call goes through here so
 * timeouts, user agent and error mapping stay identical across modules.
 */
internal object Net {

    const val USER_AGENT = "OVERSIGHT/1.0 (personal intelligence console)"

    class Response(val status: Int, val body: String)

    suspend fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        timeoutMs: Int = 20_000
    ): Response = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            Response(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        accept: String = "application/json"
    ): String {
        val response = request(url, headers = headers + ("Accept" to accept))
        if (response.status !in 200..299) throw mapError(response.status, response.body)
        return response.body
    }

    suspend fun getOrNull(url: String, headers: Map<String, String> = emptyMap()): Response =
        request(url, headers = headers, timeoutMs = 15_000)

    /**
     * GET with an explicit gzip opt-in and a manual decode.
     *
     * Android's `HttpURLConnection` negotiates gzip on its own and decodes it
     * transparently, but only when the app does not set the header itself. A source
     * that *requires* the header — Digitraffic answers 406 without it — leaves no
     * choice: ask outright, then unwrap the stream here because setting the header
     * also switches off the platform's decode.
     */
    suspend fun getGzip(
        url: String,
        headers: Map<String, String> = emptyMap(),
        accept: String = "application/json"
    ): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw mapError(status, error)
            }
            val wrapped = connection.contentEncoding?.contains("gzip", ignoreCase = true) == true
            val stream = connection.inputStream.let { if (wrapped) GZIPInputStream(it) else it }
            stream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun postMultipart(
        url: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        headers: Map<String, String> = emptyMap()
    ): String {
        val boundary = "----vector${UUID.randomUUID().toString().replace("-", "")}"
        val head = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"").append(fieldName)
            append("\"; filename=\"").append(fileName).append("\"\r\n")
            append("Content-Type: ").append(mimeType).append("\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val payload = ByteArray(head.size + bytes.size + tail.size)
        head.copyInto(payload, 0)
        bytes.copyInto(payload, head.size)
        tail.copyInto(payload, head.size + bytes.size)

        val response = request(
            url = url,
            method = "POST",
            headers = headers + ("Accept" to "application/json"),
            body = payload,
            contentType = "multipart/form-data; boundary=$boundary",
            timeoutMs = 45_000
        )
        if (response.status !in 200..299) throw mapError(response.status, response.body)
        return response.body
    }

    private fun mapError(status: Int, body: String): IOException = IOException(
        when (status) {
            401, 403 -> "Credentials rejected (HTTP $status). Check the API key."
            404 -> "Data not found."
            429 -> "Batas permintaan tercapai. Coba lagi nanti."
            in 500..599 -> "Source is having issues (HTTP $status)."
            else -> "Request failed (HTTP $status). ${body.take(120)}".trim()
        }
    )
}
