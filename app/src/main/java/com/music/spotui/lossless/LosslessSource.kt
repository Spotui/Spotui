package com.music.spotui.lossless

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Resolves a Spotify track to a lossless FLAC URL via SpotiFLAC's **public**
 * community backends — plain HTTP calls with a shared, published API key. No
 * login, no CAPTCHA, no per-user credentials.
 *
 * Providers are tried in priority order and each fails gracefully (a down or
 * account-limited backend just falls through to the next, then to YouTube):
 *   1. Tidal  — Spotify→Tidal id (Odesli) → `/track/?id=` → BTS manifest FLAC url
 *   2. Amazon — `/api/resolve/spotify/{id}` (self-resolving)   [often dormant]
 *   3. Qobuz  — Spotify→Qobuz id (Odesli) → `/api/track/{id}`  [often dormant]
 *
 * Amazon/Qobuz are frequently down or account-limited; they're kept in the chain
 * so coverage improves automatically when those servers rotate back up. Endpoint
 * lists come from [LosslessRegistry] so they follow the community's rotations.
 */
object LosslessSource {

    private const val TAG = "LosslessSource"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    data class FlacTrack(val url: String, val provider: String, val quality: String)

    sealed interface Result {
        data class Success(val track: FlacTrack) : Result
        data object NotFound : Result
        data class Error(val message: String) : Result
    }

    suspend fun resolve(context: Context, spotifyId: String, preferHiRes: Boolean = true): Result =
        withContext(Dispatchers.IO) {
            val ids = odesliIds(spotifyId)
            // 1. Tidal — SpotiFLAC's gated backend (if verified) then direct endpoints
            ids["tidal"]?.let { tid ->
                tidalFlac(context, tid, preferHiRes)?.let { return@withContext Result.Success(it) }
            }
            // 2. Amazon (self-resolves from the Spotify id; usually dormant)
            amazonFlac(spotifyId)?.let { return@withContext Result.Success(it) }
            // 3. Qobuz (needs a numeric Qobuz id; usually dormant)
            ids["qobuz"]?.let { qid ->
                qobuzFlac(qid, preferHiRes)?.let { return@withContext Result.Success(it) }
            }
            Result.NotFound
        }

    // ── Provider: Tidal ───────────────────────────────────────────────────────
    private fun tidalFlac(context: Context, tidalId: String, preferHiRes: Boolean): FlacTrack? {
        // SpotiFLAC's own gated backend first (if the user completed verification) —
        // it's their most reliable source. Falls through to the direct API-key ones.
        if (SpotiflacGated.hasSession(context)) {
            SpotiflacGated.tidalFlacUrl(context, tidalId, "LOSSLESS")?.let {
                Log.d(TAG, "SpotiFLAC gated FLAC for tidal:$tidalId")
                return FlacTrack(it, "SpotiFLAC", "16")
            }
        }
        // LOSSLESS (16-bit) is a single-file FLAC and the most reliable/fastest —
        // request only it. HI_RES usually comes back as a multi-segment manifest we
        // can't hand to the player as one URL anyway, and the extra slow call just
        // burns the timeout budget on these flaky community servers.
        val qualities = listOf("LOSSLESS")
        for (endpoint in LosslessRegistry.tidalEndpoints()) {
            for (q in qualities) {
                val track = runCatching {
                    val body = get("$endpoint/track/?id=$tidalId&quality=$q", keyHeader())
                    val data = JSONObject(body).optJSONObject("data") ?: return@runCatching null
                    val manifest = decodeBtsManifest(data.optString("manifest")) ?: return@runCatching null
                    val urls = manifest.optJSONArray("urls") ?: return@runCatching null
                    // Single-file FLAC only — a multi-segment manifest can't be one URL.
                    if (urls.length() != 1) return@runCatching null
                    val flacUrl = urls.optString(0).takeIf { it.isNotBlank() } ?: return@runCatching null
                    if (!manifest.optString("mimeType").contains("flac", true) && !flacUrl.contains(".flac")) {
                        return@runCatching null
                    }
                    val bits = data.optInt("bitDepth", 16).let { if (it in 8..32) it else 16 }
                    FlacTrack(flacUrl, "Tidal", bits.toString())
                }.getOrNull()
                if (track != null) {
                    Log.d(TAG, "Tidal ${track.quality}-bit via $endpoint (tidal:$tidalId)")
                    return track
                }
            }
        }
        return null
    }

    // ── Provider: Amazon (self-resolving; skips encrypted responses) ──────────
    private fun amazonFlac(spotifyId: String): FlacTrack? {
        for (endpoint in LosslessRegistry.amazonEndpoints()) {
            val track = runCatching {
                val body = get("$endpoint/api/resolve/spotify/$spotifyId", keyHeader())
                val json = JSONObject(body)
                val data = json.optJSONObject("data") ?: json
                // Only accept a directly-playable url with NO decryption key — we
                // don't ship Amazon's stream decryption, so encrypted responses are
                // skipped rather than saved as garbled audio.
                if (data.has("decryption_key") || json.has("decryption_key")) return@runCatching null
                val url = firstNonBlank(
                    data.optString("url"), data.optString("stream_url"),
                    data.optString("download_url"), data.optString("streamUrl"),
                )
                if (url.isBlank() || !url.startsWith("http")) return@runCatching null
                if (!url.contains("flac", true)) return@runCatching null
                FlacTrack(url, "Amazon", "16")
            }.getOrNull()
            if (track != null) {
                Log.d(TAG, "Amazon FLAC via $endpoint (spotify:$spotifyId)")
                return track
            }
        }
        return null
    }

    // ── Provider: Qobuz (needs Qobuz id; defensive parsing) ───────────────────
    private fun qobuzFlac(qobuzId: String, preferHiRes: Boolean): FlacTrack? {
        val qualities = if (preferHiRes) listOf("27", "7") else listOf("7") // 27=hi-res, 7=CD lossless
        for (endpoint in LosslessRegistry.qobuzEndpoints()) {
            for (q in qualities) {
                val track = runCatching {
                    val body = get("$endpoint/api/track/$qobuzId?quality=$q", keyHeader())
                    val json = JSONObject(body)
                    val data = json.optJSONObject("data") ?: json
                    val url = firstNonBlank(
                        data.optString("url"), data.optString("streaming_url"), data.optString("download_url"),
                    )
                    if (url.isBlank() || !url.startsWith("http")) return@runCatching null
                    if (!url.contains("flac", true) && !url.contains("audio", true)) return@runCatching null
                    val bits = if (q == "27") "24" else "16"
                    FlacTrack(url, "Qobuz", bits)
                }.getOrNull()
                if (track != null) {
                    Log.d(TAG, "Qobuz ${track.quality}-bit via $endpoint (qobuz:$qobuzId)")
                    return track
                }
            }
        }
        return null
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    /** One Odesli call → provider track ids (tidal / qobuz) for a Spotify track. */
    private fun odesliIds(spotifyId: String): Map<String, String> = runCatching {
        val url = "https://api.song.link/v1-alpha.1/links?url=" +
            URLEncoder.encode("https://open.spotify.com/track/$spotifyId", "UTF-8")
        val json = JSONObject(get(url, emptyMap()))
        val out = HashMap<String, String>()
        val links = json.optJSONObject("linksByPlatform")
        links?.optJSONObject("tidal")?.optString("entityUniqueId")?.substringAfter("::")
            ?.takeIf { it.isNotBlank() }?.let { out["tidal"] = it }
        links?.optJSONObject("qobuz")?.optString("entityUniqueId")?.substringAfter("::")
            ?.takeIf { it.isNotBlank() }?.let { out["qobuz"] = it }
        // Fallback: scan entity keys (TIDAL_SONG:: / QOBUZ_SONG::).
        json.optJSONObject("entitiesByUniqueId")?.let { ents ->
            ents.keys().forEach { k ->
                when {
                    k.startsWith("TIDAL_SONG::") -> out.putIfAbsent("tidal", k.substringAfter("::"))
                    k.startsWith("QOBUZ_SONG::") -> out.putIfAbsent("qobuz", k.substringAfter("::"))
                }
            }
        }
        out
    }.getOrDefault(emptyMap())

    private fun decodeBtsManifest(b64: String): JSONObject? {
        if (b64.isBlank()) return null
        return runCatching {
            JSONObject(String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8))
        }.getOrNull()
    }

    private fun keyHeader() = mapOf("X-API-Key" to LosslessRegistry.API_KEY)

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun get(urlString: String, headers: Map<String, String>): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            conn.connect()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw java.io.IOException("HTTP $code: ${text.take(120)}")
            text
        } finally {
            conn.disconnect()
        }
    }
}
