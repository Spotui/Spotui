package com.music.spotui.piped

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fallback audio stream resolver using public, ad-free Piped and Invidious backends.
 * If YouTube InnerTube throttles or blocks streams (HTTP 403 / 429), this extracts
 * direct high-quality OPUS / AAC audio streams.
 */
object PipedSource {

    private const val TAG = "PipedSource"

    private val PIPED_INSTANCES = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.tokhmi.xyz",
        "https://pipedapi.smnz.de",
        "https://pipedapi.adminforge.de",
        "https://api.piped.privacydev.net",
        "https://api.piped.yt",
        "https://pipedapi.leptons.xyz",
        "https://pipedapi.r4ce.nl",
        "https://pipedapi.drgns.space",
        "https://piped-api.garudalinux.org",
        "https://piped-api.lunar.icu",
        "https://api.piped.projectsegfau.lt",
        "https://api.piped.private.coffee",
    )

    private val INVIDIOUS_INSTANCES = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://invidious.private.coffee",
        "https://yewtu.be",
        "https://vid.puffyan.us",
        "https://inv.tux.pizza",
        "https://yt.artemislena.eu",
        "https://invidious.flokinet.to",
        "https://invidious.asir.dev",
        "https://invidious.projectsegfau.lt",
        "https://iv.melmac.space",
    )

    data class PipedAudioStream(
        val url: String,
        val bitrate: Int,
        val mimeType: String,
        val qualityLabel: String,
    )

    /**
     * Resolves a YouTube videoId to an ad-free direct audio stream.
     */
    suspend fun resolveAudioStream(videoId: String): PipedAudioStream? = withContext(Dispatchers.IO) {
        if (videoId.isBlank() || videoId.length != 11) return@withContext null

        // Try Piped instances in parallel for fast response
        val pipedResult = coroutineScope {
            val jobs = PIPED_INSTANCES.map { instance ->
                async {
                    try {
                        val urlStr = "$instance/streams/$videoId"
                        val jsonStr = httpGet(urlStr) ?: return@async null
                        val json = JSONObject(jsonStr)
                        val audioStreams = json.optJSONArray("audioStreams") ?: return@async null

                        var bestStream: PipedAudioStream? = null
                        var maxBitrate = 0

                        for (i in 0 until audioStreams.length()) {
                            val stream = audioStreams.optJSONObject(i) ?: continue
                            val streamUrl = stream.optString("url", "")
                            if (streamUrl.isBlank() || !streamUrl.startsWith("http")) continue

                            val bitrate = stream.optInt("bitrate", 0)
                            val mimeType = stream.optString("mimeType", "audio/webm")
                            val codec = stream.optString("codec", "opus")

                            if (bitrate > maxBitrate || bestStream == null) {
                                maxBitrate = bitrate
                                val kbps = if (bitrate > 1000) bitrate / 1000 else bitrate
                                bestStream = PipedAudioStream(
                                    url = streamUrl,
                                    bitrate = bitrate,
                                    mimeType = mimeType,
                                    qualityLabel = "${codec.uppercase()} ${kbps} kbps",
                                )
                            }
                        }
                        bestStream
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            jobs.awaitAll().filterNotNull().maxByOrNull { it.bitrate }
        }

        if (pipedResult != null) {
            Log.d(TAG, "Resolved Piped audio stream for $videoId (${pipedResult.qualityLabel})")
            return@withContext pipedResult
        }

        // Try Invidious instances as secondary fallback
        val invidiousResult = coroutineScope {
            val jobs = INVIDIOUS_INSTANCES.map { instance ->
                async {
                    try {
                        val urlStr = "$instance/api/v1/videos/$videoId"
                        val jsonStr = httpGet(urlStr) ?: return@async null
                        val json = JSONObject(jsonStr)
                        val adaptiveFormats = json.optJSONArray("adaptiveFormats") ?: return@async null

                        var bestStream: PipedAudioStream? = null
                        var maxBitrate = 0

                        for (i in 0 until adaptiveFormats.length()) {
                            val stream = adaptiveFormats.optJSONObject(i) ?: continue
                            val type = stream.optString("type", "")
                            if (!type.startsWith("audio/")) continue

                            val streamUrl = stream.optString("url", "")
                            if (streamUrl.isBlank() || !streamUrl.startsWith("http")) continue

                            val bitrate = stream.optInt("bitrate", 0)
                            val mimeType = type.substringBefore(";")
                            val codec = if (type.contains("opus", ignoreCase = true)) "opus" else "aac"

                            if (bitrate > maxBitrate || bestStream == null) {
                                maxBitrate = bitrate
                                val kbps = if (bitrate > 1000) bitrate / 1000 else bitrate
                                bestStream = PipedAudioStream(
                                    url = streamUrl,
                                    bitrate = bitrate,
                                    mimeType = mimeType,
                                    qualityLabel = "${codec.uppercase()} ${kbps} kbps",
                                )
                            }
                        }
                        bestStream
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            jobs.awaitAll().filterNotNull().maxByOrNull { it.bitrate }
        }

        if (invidiousResult != null) {
            Log.d(TAG, "Resolved Invidious audio stream for $videoId (${invidiousResult.qualityLabel})")
            return@withContext invidiousResult
        }

        null
    }

    private fun httpGet(urlStr: String): String? {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                setRequestProperty("Accept", "application/json")
            }
            conn.connect()
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
            conn.disconnect()
            text
        }.getOrNull()
    }
}
