package com.byd.tripstats.util

import android.util.Log
import com.byd.tripstats.BuildConfig
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "LitterboxUploader"

/**
 * Uploads a small text payload to litterbox (catbox.moe's temporary host) and returns
 * the public download URL.
 *
 * Used by the vehicle compatibility probe: the head unit has no easy way to email an
 * attachment, so we POST the report, get back a short URL, and encode that URL into a
 * `mailto:` QR code the user scans with their phone. Litterbox files self-delete after
 * the chosen retention window (1h/12h/24h/72h), so nothing lingers indefinitely.
 *
 * Blocking network call — invoke from a background dispatcher. Throws on any failure so
 * the caller can surface a message; never returns a non-URL string.
 */
object LitterboxUploader {

    private const val ENDPOINT = "https://litterbox.catbox.moe/resources/internals/api.php"
    private const val CRLF = "\r\n"

    /** Valid retention windows accepted by the litterbox API. */
    val RETENTIONS = setOf("1h", "12h", "24h", "72h")

    /**
     * @param fileName  name (with extension) the host should keep; the extension drives
     *                  the suffix on the returned URL.
     * @param content   the text payload (the probe JSON).
     * @param retention one of [RETENTIONS]; defaults to 24h.
     * @return the public `https://litter.catbox.moe/…` URL.
     */
    fun upload(fileName: String, content: String, retention: String = "24h"): String {
        require(retention in RETENTIONS) { "Unsupported retention: $retention" }

        val boundary = "----BydTripStatsBoundary${System.currentTimeMillis()}"
        val bytes = buildMultipartBody(boundary, fileName, content, retention)

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            // catbox/litterbox reject some default library user agents.
            setRequestProperty("User-Agent", "BydTripStats/${BuildConfig.VERSION_NAME}")
            setFixedLengthStreamingMode(bytes.size)
        }

        try {
            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use(BufferedReader::readText)
                ?.trim()
                .orEmpty()

            if (code !in 200..299) {
                throw IllegalStateException("Upload failed (HTTP $code): ${body.take(200)}")
            }
            if (!body.startsWith("http")) {
                throw IllegalStateException("Unexpected response: ${body.take(200)}")
            }
            Log.i(TAG, "Probe uploaded ($retention): $body")
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun buildMultipartBody(
        boundary: String,
        fileName: String,
        content: String,
        retention: String
    ): ByteArray {
        val sb = StringBuilder()
        fun textField(name: String, value: String) {
            sb.append("--$boundary$CRLF")
            sb.append("Content-Disposition: form-data; name=\"$name\"$CRLF$CRLF")
            sb.append("$value$CRLF")
        }
        textField("reqtype", "fileupload")
        textField("time", retention)
        sb.append("--$boundary$CRLF")
        sb.append("Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"$fileName\"$CRLF")
        sb.append("Content-Type: application/json$CRLF$CRLF")
        sb.append(content)
        sb.append(CRLF)
        sb.append("--$boundary--$CRLF")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}
