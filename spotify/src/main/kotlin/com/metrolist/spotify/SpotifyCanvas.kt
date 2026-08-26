package com.metrolist.spotify

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent

/**
 * Fetches Spotify Canvas — the short looping videos that play behind a track on
 * the mobile app. Uses Spotify's internal `canvaz-cache` service, which speaks
 * Protobuf. We hand-encode the tiny request and hand-decode the response (no
 * protobuf runtime dependency) since the messages are trivial:
 *
 *   EntityCanvazRequest { repeated Entity { string entity_uri = 1 } = 1 }
 *   EntityCanvazResponse { repeated Canvaz { string url = 2; string entity_uri = 5 } = 1 }
 *
 * Auth reuses the web-player access token (same Bearer token as color-lyrics).
 */
object SpotifyCanvas {

    private const val ENDPOINT = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"

    private var logger: ((String, String) -> Unit)? = null
    fun setLogger(l: (String, String) -> Unit) { logger = l }
    private fun log(level: String, msg: String) = logger?.invoke(level, "SpotifyCanvas: $msg")

    private val client by lazy {
        HttpClient(OkHttp) {
            engine { config { retryOnConnectionFailure(true) } }
            expectSuccess = false
        }
    }

    /**
     * Returns the canvas video URL (…cnvs.mp4) for a track, or null when the
     * track has no canvas / the request fails. [accessToken] is Spotify's
     * web-player Bearer token (see [Spotify.accessToken]).
     */
    suspend fun canvasUrl(trackId: String, accessToken: String): String? {
        if (trackId.isBlank() || accessToken.isBlank()) return null
        val uri = "spotify:track:$trackId"
        val body = encodeRequest(uri)
        return try {
            val resp = client.post(ENDPOINT) {
                header("Authorization", "Bearer $accessToken")
                header("Accept", "application/protobuf")
                setBody(ByteArrayContent(body, ContentType("application", "protobuf")))
            }
            if (resp.status.value !in 200..299) {
                log("D", "canvas($trackId) HTTP ${resp.status.value}")
                return null
            }
            val url = decodeFirstUrl(resp.readBytes())
            log("D", "canvas($trackId) -> ${url ?: "none"}")
            url
        } catch (e: Exception) {
            log("W", "canvas($trackId) failed: ${e.message}")
            null
        }
    }

    // ── Minimal protobuf wire encode/decode ─────────────────────────────────

    /** EntityCanvazRequest with a single Entity{entity_uri}. */
    private fun encodeRequest(uri: String): ByteArray {
        val uriBytes = uri.toByteArray(Charsets.UTF_8)
        // Entity: field 1 (entity_uri), wire type 2 (length-delimited).
        val entity = ByteArrayBuilder().apply {
            writeTag(1, 2); writeVarint(uriBytes.size.toLong()); write(uriBytes)
        }.toByteArray()
        // EntityCanvazRequest: field 1 (entities), wire type 2.
        return ByteArrayBuilder().apply {
            writeTag(1, 2); writeVarint(entity.size.toLong()); write(entity)
        }.toByteArray()
    }

    /** Walk EntityCanvazResponse → first Canvaz → its url (field 2). */
    private fun decodeFirstUrl(data: ByteArray): String? {
        val r = Reader(data)
        while (r.hasMore()) {
            val (field, wire) = r.readTag()
            if (field == 1 && wire == 2) {
                // canvases[i] — a nested Canvaz message.
                val canvaz = r.readBytes()
                val url = readCanvazUrl(canvaz)
                if (url != null) return url
            } else {
                r.skip(wire)
            }
        }
        return null
    }

    private fun readCanvazUrl(canvaz: ByteArray): String? {
        val r = Reader(canvaz)
        while (r.hasMore()) {
            val (field, wire) = r.readTag()
            if (field == 2 && wire == 2) {
                return String(r.readBytes(), Charsets.UTF_8)
            }
            r.skip(wire)
        }
        return null
    }

    private class ByteArrayBuilder {
        private val out = java.io.ByteArrayOutputStream()
        fun write(b: ByteArray) = out.write(b)
        fun writeByte(b: Int) = out.write(b)
        fun writeTag(field: Int, wire: Int) = writeVarint(((field shl 3) or wire).toLong())
        fun writeVarint(value: Long) {
            var v = value
            while (true) {
                val bits = (v and 0x7F).toInt()
                v = v ushr 7
                if (v != 0L) writeByte(bits or 0x80) else { writeByte(bits); break }
            }
        }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private class Reader(private val data: ByteArray) {
        private var pos = 0
        fun hasMore() = pos < data.size
        fun readTag(): Pair<Int, Int> {
            val v = readVarint().toInt()
            return (v ushr 3) to (v and 0x7)
        }
        fun readVarint(): Long {
            var result = 0L; var shift = 0
            while (pos < data.size) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift >= 64) break
            }
            return result
        }
        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            if (len <= 0) return ByteArray(0)
            val end = (pos + len).coerceAtMost(data.size)
            val out = if (pos < data.size) data.copyOfRange(pos, end) else ByteArray(0)
            pos = end
            return out
        }
        /** Skip a field's value given its wire type. */
        fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> pos = (pos + 8).coerceAtMost(data.size)
                2 -> { val len = readVarint().toInt().coerceAtLeast(0); pos = (pos + len).coerceAtMost(data.size) }
                5 -> pos = (pos + 4).coerceAtMost(data.size)
                else -> pos = data.size
            }
        }
    }
}
