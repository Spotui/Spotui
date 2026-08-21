package com.music.spotui.data.api

import android.util.Log
import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.Lyrics
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Synced lyrics. Primary source is Spotify's own color-lyrics endpoint (the
 * exact synced lyrics the official client shows, fetched by track id) — with
 * LRCLIB (lrclib.net, free + key-less) as the fallback for tracks Spotify has
 * no lyrics for or when the track id isn't known.
 *
 * LRCLIB asks clients to send a descriptive User-Agent; we oblige.
 */
object LyricsApi {
    private const val BASE = "https://lrclib.net/api"
    private const val UA = "SpotuiSpotifyClone (https://github.com/)"

    // In-memory cache keyed by "title|artist" so re-opening the lyrics view (or the
    // inline card + full-screen view, which both request the same track) is instant
    // and we never re-hit the network for a track we already resolved this session.
    // A miss is cached too, but only for MISS_RETRY_MS — a "not found" is often just
    // a timeout / flaky network, not a fact, so it becomes retryable.
    private class Miss(val at: Long = System.currentTimeMillis())
    private const val MISS_RETRY_MS = 120_000L

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Any>() // Lyrics | Miss
    private val prefetchScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private fun cacheKey(title: String, artist: String) =
        "${cleanTitle(title).lowercase()}|${artist.substringBefore(",").trim().lowercase()}"

    // Strip the noise Spotify puts in titles that LRCLIB doesn't know about:
    // "Song - 2011 Remaster", "Song (feat. X)", "Song [Bonus Track]".
    private val featTag = Regex("""\s*[(\[][^)\]]*(feat\.?|ft\.?|with )[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val bracketTag = Regex("""\s*[(\[][^)\]]*(remaster|remastered|live|version|edit|mono|stereo|deluxe|bonus)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private fun cleanTitle(title: String) =
        title.substringBefore(" - ")
            .replace(featTag, "")
            .replace(bracketTag, "")
            .trim()

    // Play-queue registry: "title|artist" → Spotify track id, seeded by
    // CurrentSongState.updateQueue so we can hit Spotify's own lyrics endpoint
    // (the exact lyrics the official client shows) before falling back to LRCLIB.
    private val trackIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun registerTracks(songs: List<com.music.spotui.data.entity.SongsModel>) {
        for (s in songs) {
            if (s.spotifyTrackId.isNotBlank() && s.title.isNotBlank()) {
                trackIds[cacheKey(s.title, s.singer)] = s.spotifyTrackId
            }
        }
    }

    /** Warm the cache for a track in the background (call when playback starts). */
    fun prefetch(title: String, artist: String, album: String, durationSec: Int = 0) {
        if (title.isBlank()) return
        val cached = cache[cacheKey(title, artist)]
        if (cached is Lyrics) return
        if (cached is Miss && System.currentTimeMillis() - cached.at < MISS_RETRY_MS) return
        prefetchScope.launch { runCatching { fetch(title, artist, album, durationSec) } }
    }

    suspend fun fetch(title: String, artist: String, album: String, durationSec: Int): Lyrics? {
        val key = cacheKey(title, artist)
        when (val cached = cache[key]) {
            is Lyrics -> return cached
            is Miss -> if (System.currentTimeMillis() - cached.at < MISS_RETRY_MS) return null
        }

        val primaryArtist = artist.substringBefore(",").trim()
        val cleaned = cleanTitle(title)

        // 1) Spotify's own color-lyrics — the exact synced lyrics the official app
        //    shows, keyed by track id, so no title/artist matching can go wrong.
        // 2) LRCLIB fallback: exact get + fuzzy search fired CONCURRENTLY, then a
        //    title-only search. Serial fallbacks used to stack 3 × 5s timeouts.
        val result = fromSpotify(key) ?: kotlinx.coroutines.coroutineScope {
            val exact = async(kotlinx.coroutines.Dispatchers.IO) {
                getExact(cleaned, primaryArtist, album, durationSec)
            }
            val fuzzy = async(kotlinx.coroutines.Dispatchers.IO) {
                search(cleaned, primaryArtist, durationSec)
            }
            exact.await()
                ?: fuzzy.await()
                ?: searchTitleOnly(cleaned, primaryArtist, durationSec)
        }
        cache[key] = result ?: Miss()
        return result
    }

    private suspend fun fromSpotify(key: String): Lyrics? {
        val trackId = trackIds[key] ?: return null
        // The web token expires hourly — refresh before hitting color-lyrics.
        runCatching {
            SpotifyTokenProvider.ensureToken(com.music.spotui.MyApplication.instance)
        }
        return com.metrolist.spotify.Spotify.lyrics(trackId).fold(
            onSuccess = { sp ->
                Lyrics(
                    lines = sp.lines.map { LyricLine(it.startMs, it.words) },
                    synced = sp.synced,
                    language = sp.language,
                )
            },
            onFailure = {
                Log.d("LyricsApi", "spotify lyrics miss for $trackId: ${it.message}")
                null
            },
        )
    }

    private fun getExact(title: String, artist: String, album: String, durationSec: Int): Lyrics? {
        val url = buildString {
            append("$BASE/get?")
            append("track_name=").append(enc(title))
            append("&artist_name=").append(enc(artist))
            if (album.isNotBlank()) append("&album_name=").append(enc(album))
            if (durationSec > 0) append("&duration=").append(durationSec)
        }
        val body = httpGet(url) ?: return null
        return runCatching { parse(JSONObject(body)) }.getOrNull()
    }

    private fun search(title: String, artist: String, durationSec: Int): Lyrics? =
        searchUrl("$BASE/search?track_name=${enc(title)}&artist_name=${enc(artist)}", null, durationSec)

    /**
     * Title-only search for when the artist is spelled differently on LRCLIB
     * (transliterations, "feat." in the artist field, …). Candidates whose artist
     * loosely matches ours are strongly preferred so we don't sync a cover.
     */
    private fun searchTitleOnly(title: String, artist: String, durationSec: Int): Lyrics? =
        searchUrl("$BASE/search?track_name=${enc(title)}", artist, durationSec)

    private fun searchUrl(url: String, preferArtist: String?, durationSec: Int): Lyrics? {
        val body = httpGet(url) ?: return null
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return null
        if (arr.length() == 0) return null
        // Prefer the candidate whose duration is closest, that has synced lyrics,
        // and (for title-only searches) whose artist loosely matches ours.
        var best: JSONObject? = null
        var bestScore = Long.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val hasSynced = !o.optString("syncedLyrics").isNullOrBlank()
            val dur = o.optInt("duration", 0)
            val diff = if (durationSec > 0 && dur > 0) kotlin.math.abs(dur - durationSec).toLong() else 0L
            val artistMismatch = if (preferArtist != null) {
                val candidate = o.optString("artistName").lowercase()
                val ours = preferArtist.lowercase()
                if (candidate.contains(ours) || ours.contains(candidate)) 0 else 500_000
            } else 0
            val score = diff + (if (hasSynced) 0 else 100_000) + artistMismatch
            if (score < bestScore) { bestScore = score; best = o }
        }
        return best?.let { runCatching { parse(it) }.getOrNull() }
    }

    private fun parse(o: JSONObject): Lyrics? {
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() }
        if (synced != null) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return Lyrics(lines, synced = true)
        }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() } ?: return null
        val lines = plain.split("\n").map { LyricLine(0L, it) }
        return Lyrics(lines, synced = false)
    }

    // LRC: each line is "[mm:ss.xx] text" (a line may carry multiple timestamps).
    private val lrcTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private fun parseLrc(lrc: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in lrc.split("\n")) {
            val tags = lrcTag.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val text = raw.substring(tags.last().range.last + 1).trim()
            for (m in tags) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                out.add(LyricLine(min * 60_000 + sec * 1_000 + ms, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(spec: String): String? = runCatching {
        val conn = (URL(spec).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != 200) {
                Log.d("LyricsApi", "HTTP ${conn.responseCode} for $spec")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrElse { Log.e("LyricsApi", "request failed", it); null }
}
