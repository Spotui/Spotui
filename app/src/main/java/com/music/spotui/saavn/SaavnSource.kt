package com.music.spotui.saavn

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * High-speed, ad-free streaming source powered by JioSaavn's public CDN audio infrastructure.
 * Provides direct 320 kbps (lossless-equivalent AAC) and 160 kbps streams with 0 ads,
 * fast global CDN delivery, and instant playback start.
 */
object SaavnSource {

    private const val TAG = "SaavnSource"

    private val API_ENDPOINTS = listOf(
        "https://saavn.dev/api",
        "https://saavn.me/api",
        "https://jiosaavn-api-sigma-six.vercel.app/api",
    )

    data class SaavnTrack(
        val url: String,
        val qualityLabel: String,
        val bitrate: Int,
        val title: String,
        val artist: String,
        val durationSec: Int,
    )

    sealed interface Result {
        data class Success(val track: SaavnTrack) : Result
        data object NotFound : Result
        data class Error(val message: String) : Result
    }

    /**
     * Attempts to resolve a Spotify track to a high-speed ad-free Saavn stream.
     */
    suspend fun resolve(
        title: String,
        artist: String,
        expectedDurationMs: Int? = null,
    ): Result = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext Result.NotFound

        val cleanTitle = cleanSearchQuery(title)
        val primaryArtist = artist.split(Regex("""(?i)\s*(?:,|\band\b|&|\bfeat\.?|\bft\.?|\bwith\b|\bx\b)\s*"""))
            .firstOrNull { it.isNotBlank() }?.trim() ?: artist
        val cleanArtist = cleanSearchQuery(primaryArtist)

        val queries = listOfNotNull(
            if (cleanTitle.isNotBlank() && cleanArtist.isNotBlank()) "$cleanTitle $cleanArtist" else null,
            if (cleanTitle.isNotBlank() && artist.isNotBlank() && artist != cleanArtist) "$cleanTitle $artist" else null,
            cleanTitle.takeIf { it.isNotBlank() },
        ).distinct()

        for (query in queries) {
            for (baseEndpoint in API_ENDPOINTS) {
                try {
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    val urlStr = "$baseEndpoint/search/songs?query=$encoded&limit=10"
                    val jsonStr = httpGet(urlStr) ?: continue
                    val json = JSONObject(jsonStr)
                    val data = json.optJSONObject("data") ?: json
                    val results = data.optJSONArray("results") ?: data.optJSONArray("songs") ?: json.optJSONArray("data") ?: continue

                    var bestMatch: SaavnTrack? = null
                    var bestScore = 0.0

                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val itemTitle = item.optString("name", "").ifBlank { item.optString("title", "") }
                        val itemArtist = item.optJSONObject("artists")?.optJSONArray("primary")
                            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }.joinToString(", ") }
                            ?: item.optString("primaryArtists", "").ifBlank { item.optString("artist", "") }
                        val itemDuration = item.optInt("duration", 0)

                        // Calculate match score
                        val score = matchScore(
                            expectedTitle = title,
                            expectedArtist = artist,
                            expectedDurationSec = expectedDurationMs?.let { it / 1000 },
                            candTitle = itemTitle,
                            candArtist = itemArtist,
                            candDurationSec = itemDuration,
                        )

                        if (score >= 0.78 && score > bestScore) {
                            // Extract highest available download quality
                            val downloadUrls = item.optJSONArray("downloadUrl")
                            var streamUrl = ""
                            var bitrate = 320
                            var qualityLabel = "320 kbps"

                            if (downloadUrls != null && downloadUrls.length() > 0) {
                                // Find 320kbps first, then 160kbps, then highest available
                                for (j in (downloadUrls.length() - 1) downTo 0) {
                                    val dlObj = downloadUrls.optJSONObject(j) ?: continue
                                    val q = dlObj.optString("quality", "")
                                    val link = dlObj.optString("url", "").ifBlank { dlObj.optString("link", "") }
                                    if (link.isNotBlank() && (link.startsWith("http://") || link.startsWith("https://"))) {
                                        streamUrl = link
                                        qualityLabel = if (q.isNotBlank()) "$q kbps" else "320 kbps"
                                        bitrate = q.toIntOrNull() ?: 320
                                        break
                                    }
                                }
                            }

                            if (streamUrl.isNotBlank()) {
                                bestScore = score
                                bestMatch = SaavnTrack(
                                    url = streamUrl,
                                    qualityLabel = qualityLabel,
                                    bitrate = bitrate,
                                    title = itemTitle,
                                    artist = itemArtist,
                                    durationSec = itemDuration,
                                )
                            }
                        }
                    }

                    if (bestMatch != null) {
                        Log.d(TAG, "Resolved Saavn stream (${bestMatch.qualityLabel}) for '$title - $artist' [score=${"%.2f".format(bestScore)}]")
                        return@withContext Result.Success(bestMatch)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Saavn query failed on $baseEndpoint: ${e.message}")
                }
            }
        }

        Result.NotFound
    }

    private fun matchScore(
        expectedTitle: String,
        expectedArtist: String,
        expectedDurationSec: Int?,
        candTitle: String,
        candArtist: String,
        candDurationSec: Int,
    ): Double {
        fun norm(s: String) = cleanSearchQuery(s).lowercase().filter { it.isLetterOrDigit() }
        val nt1 = norm(expectedTitle)
        val nt2 = norm(candTitle)
        if (nt1.isEmpty() || nt2.isEmpty()) return 0.0

        val titleScore = when {
            nt1 == nt2 -> 1.0
            nt1.contains(nt2) || nt2.contains(nt1) -> 0.90
            else -> bigramSim(nt1, nt2)
        }
        if (titleScore < 0.40) return 0.0

        val na1 = norm(expectedArtist)
        val na2 = norm(candArtist)
        val artistScore = when {
            na1.isEmpty() || na2.isEmpty() -> 0.60
            na1 == na2 -> 1.0
            na1.contains(na2) || na2.contains(na1) -> 0.90
            else -> bigramSim(na1, na2)
        }

        val durScore = if (expectedDurationSec != null && expectedDurationSec > 0 && candDurationSec > 0) {
            val diff = abs(expectedDurationSec - candDurationSec)
            when {
                diff <= 3 -> 1.0
                diff <= 6 -> 0.85
                diff <= 12 -> 0.60
                diff <= 20 -> 0.20
                else -> -1.0
            }
        } else {
            0.5
        }

        if (durScore < 0) return 0.0 // Duration mismatch > 20s is unlikely to be the same song
        return (titleScore * 0.45 + artistScore * 0.35 + durScore * 0.20)
    }

    private fun bigramSim(s1: String, s2: String): Double {
        if (s1.length < 2 || s2.length < 2) return 0.0
        val b1 = s1.windowed(2).toSet()
        val b2 = s2.windowed(2).toSet()
        val inter = b1.count { it in b2 }
        return (2.0 * inter) / (b1.size + b2.size)
    }

    private fun cleanSearchQuery(s: String): String = s
        .replace(Regex("""(?i)\s*[\(\[]\s*(feat|ft|with)\.?\s+.*?[\]\)]"""), "")
        .replace(Regex("""(?i)\s*[-–—]?\s*[\(\[]?\s*(\d{4}\s+)?remaster(ed)?(\s+\d{4})?\s*[\)\]]?"""), "")
        .replace(Regex("""(?i)\s*[-–—]?\s*[\(\[]?\s*(deluxe|bonus track|from [^\]\)]+|soundtrack|ost)\s*[\)\]]?"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun httpGet(urlStr: String): String? {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2000
                readTimeout = 2000
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
