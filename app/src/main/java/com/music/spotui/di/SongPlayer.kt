package com.music.spotui.di

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.utils.YouTubeUrlParser
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.YTPlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Plays audio resolved from YouTube. The `song` argument is a "title artist"
 * search query (set by the Spotify-backed data layer): it's matched to a
 * YouTube video, whose stream URL is resolved via the ported [YTPlayerUtils]
 * flow (cipher / PoToken / sabr) and handed to ExoPlayer.
 */
object SongPlayer {
    private const val TAG = "SongPlayer"
    private const val SPOTIFY_TRACK_PREFIX = "spotify:track:"
    private var player: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Cache of resolved stream URLs keyed by the "title artist" query, so replays
    // and prefetched neighbours start instantly instead of re-hitting the network.
    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Which engine each cached stream came from ("YouTube", "Lossless • …") so a
    // cache hit can restore the correct source badge.
    private val sourceCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    // ── Lossless (SpotiFLAC) ──
    // When enabled, playback first tries to resolve a lossless FLAC stream (Tidal/
    // Amazon via SpotiFLAC's free community proxies) for the current track and only
    // falls back to YouTube if no FLAC is available or the proxies are throttled.
    // Trades a little first-tap latency for true lossless audio.
    @Volatile var losslessStreaming = true
    @Volatile var losslessHiRes = true

    // Source kill-switches. The Spotify web player is currently broken (off).
    // YouTube is the last-resort fallback, kept on so tracks SpotiFLAC misses or
    // can't serve during a proxy cooldown still play — with the wrong-song guards
    // (videoId match check + artist/title scoring + candidate fallback).
    @Volatile var webPlayerEnabled = false
    @Volatile var youtubeEnabled = true

    // ── Deezer ──
    // When enabled and a Deezer account is logged in (ARL stored), playback tries
    // Deezer FIRST (ISRC → Deezer track → encrypted CDN stream, decrypted on the
    // fly by DeezerDataSource). Quality follows the account tier: free → MP3 128,
    // Premium → MP3 320 / FLAC. Falls back to SpotiFLAC/YouTube on any miss.
    @Volatile var deezerEnabled = true

    // Which engine is feeding the CURRENT track, for the on-screen source badge.
    // "Lossless" (SpotiFLAC: Tidal/Qobuz/Amazon) is NOT Spotify — surfaced so the
    // user knows real Spotify vs a lossless mirror vs the YouTube fallback.
    @Volatile var currentSource: String = "YouTube"
        private set
    // Human-readable quality of the CURRENT stream (e.g. "FLAC 16-bit",
    // "OPUS 141 kbps"), shown next to the source badge.
    @Volatile var currentQuality: String = ""
        private set
    private val qualityCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Maps a "title artist" play query -> the track's real Spotify id, so the
    // lossless resolver can be seeded from a play site that only has the query.
    // Populated centrally whenever the queue changes (see CurrentSongState).
    private val trackIdRegistry = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val alternativeKeyRegistry = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val trackResolver = com.music.spotui.resolver.TrackResolver()

    /** Register query→spotifyTrackId pairs so lossless can be resolved by query. */
    fun registerLossless(pairs: List<Pair<String, String>>) {
        pairs.forEach { (query, spotifyId) ->
            if (query.isNotBlank() && spotifyId.isNotBlank()) trackIdRegistry[query] = spotifyId
        }
    }

    fun registerAlternativeKeys(pairs: List<Pair<String, String>>) {
        pairs.forEach { (query, key) ->
            if (query.isNotBlank() && key.isNotBlank()) alternativeKeyRegistry[query] = key
        }
    }

    // Whether each play query is the explicit version on Spotify, so the YouTube
    // fallback can pick the matching (explicit vs clean) edit.
    private val explicitRegistry = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Register query→explicit pairs (populated whenever the queue changes). */
    fun registerExplicit(pairs: List<Pair<String, Boolean>>) {
        pairs.forEach { (query, explicit) ->
            if (query.isNotBlank()) explicitRegistry[query] = explicit
        }
    }

    // Expected track length (ms) per query, from Spotify — lets the YouTube match
    // reject a same-title song by a different artist (different duration).
    private val durationRegistry = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Register query→durationMs pairs (populated whenever the queue changes). */
    fun registerDuration(pairs: List<Pair<String, Int>>) {
        pairs.forEach { (query, ms) ->
            if (query.isNotBlank() && ms > 0) durationRegistry[query] = ms
        }
    }

    data class TrackMatchMetadata(
        val title: String,
        val artist: String,
        val album: String,
        val isrc: String = "",
    )

    private val metadataRegistry =
        java.util.concurrent.ConcurrentHashMap<String, TrackMatchMetadata>()
    // GQL track objects do not consistently carry the explicit flag. Keep this
    // separate from explicitRegistry so the model's default `false` is not
    // mistaken for a confirmed clean track.
    private val spotifyMetadataRepaired = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Register query→Spotify metadata pairs for strict YouTube result scoring. */
    fun registerMetadata(pairs: List<Pair<String, TrackMatchMetadata>>) {
        pairs.forEach { (query, meta) ->
            if (query.isNotBlank() && meta.title.isNotBlank()) metadataRegistry[query] = meta
        }
    }
    // Tracks which query is the latest play request so a slow resolve for an old
    // tap doesn't clobber a newer one (fast switching).
    @Volatile private var currentRequest: String = ""

    // Latest track metadata (title / artist / cover URL) so the MediaItem we build
    // carries it into the system media notification. Set via [setNowPlayingMeta]
    // (driven by CurrentSongState) just before / as playback starts.
    @Volatile private var metaTitle: String = ""
    @Volatile private var metaArtist: String = ""
    @Volatile private var metaCover: String = ""

    fun setNowPlayingMeta(title: String, artist: String, coverUri: String) {
        metaTitle = title
        metaArtist = artist
        metaCover = coverUri
    }

    /**
     * Build a stable playback identity for Spotify tracks. The full value is used
     * as cache/registry key, while only the text after "|" is sent to YouTube
     * search. This prevents same-title/same-artist tracks from reusing each
     * other's resolved stream.
     */
    fun buildSpotifyPlayQuery(spotifyTrackId: String, title: String, artist: String): String {
        val searchText = listOf(cleanSpotifySearchTitle(title), artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return if (spotifyTrackId.isBlank()) searchText else "$SPOTIFY_TRACK_PREFIX$spotifyTrackId|$searchText"
    }

    private val featSearchPattern = Regex("""\s*[\(\[]\s*(feat|ft)\..*?[\)\]]""", RegexOption.IGNORE_CASE)

    private fun cleanSpotifySearchTitle(title: String): String =
        title.replace(featSearchPattern, "").trim()

    private fun searchTextForPlayback(song: String): String =
        if (song.startsWith(SPOTIFY_TRACK_PREFIX) && song.contains('|')) {
            song.substringAfter('|').ifBlank { song }
        } else {
            song
        }

    private fun spotifyTrackIdForPlayback(song: String): String? =
        if (song.startsWith(SPOTIFY_TRACK_PREFIX)) {
            song.removePrefix(SPOTIFY_TRACK_PREFIX).substringBefore('|').takeIf { it.isNotBlank() }
        } else {
            null
        }

    fun videoIdFromYouTubeLink(text: String): String? =
        YouTubeUrlParser.extractVideoId(text)
            ?: text.trim().takeIf { it.matches(Regex("""[A-Za-z0-9_-]{11}""")) }

    fun invalidateResolvedStream(song: String) {
        streamCache.remove(song)
        sourceCache.remove(song)
        qualityCache.remove(song)
    }

    fun playSong(song: String, context: Context) {
        val appContext = context.applicationContext
        appCtx = appContext
        currentRequest = song
        // A manual play (tap / next / prev) supersedes any in-flight crossfade.
        cancelCrossfade()
        // Do not keep the previous track audible while this request resolves.
        // Otherwise the UI can show the newly tapped track while old audio keeps
        // playing for several seconds, or forever if resolution fails.
        runCatching {
            player?.pause()
            player?.clearMediaItems()
        }

        // Podcast episodes are encoded as "episode:<id>" queries — play them via the
        // Spotify web player's episode page (same engine as tracks).
        if (song.startsWith("episode:") && webPlayerEnabled && SpotifyWebPlayer.canPlay &&
            com.music.spotui.data.preferences.isWebPlaybackEnabled(appContext)
        ) {
            runCatching { player?.pause() }
            currentSource = "Spotify"
            currentQuality = ""
            SpotifyWebPlayer.playEpisode(song.removePrefix("episode:"))
            return
        }

        // Downloaded tracks ALWAYS play the local file — even with Spotify web
        // playback on. (Web is now the default and used to run first, so a
        // downloaded track streamed from Spotify instead of playing offline.)
        val downloadedPath = com.music.spotui.data.preferences.downloadedPathForQuery(appContext, song)
        if (downloadedPath == null && webPlayerEnabled &&
            // Experimental: stream through Spotify's own web player (real Spotify audio,
            // no bypass) when enabled AND the device WebView has Widevine. Otherwise
            // fall through to the normal YouTube/FLAC engine so playback is never silent.
            com.music.spotui.data.preferences.isWebPlaybackEnabled(appContext) &&
            SpotifyWebPlayer.canPlay
        ) {
            val spotifyId = trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song)
            if (spotifyId != null) {
                runCatching { player?.pause() }
                currentSource = "Spotify"
                currentQuality = ""
                SpotifyWebPlayer.play(spotifyId)
                return
            }
            Log.w(TAG, "web playback on but no Spotify id for query: $song — using fallback engine")
        }
        scope.launch {
            try {
                val streamUrl = resolveStreamUrl(song, appContext, forPlayback = true) ?: run {
                    // Tell the user instead of silently leaving the request on.
                    if (currentRequest == song) withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            appContext, "Couldn't find a playable stream for this track",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        // Signal failure so the queue can advance past this track
                        // instead of going silent (issue 3: playlist stalls on bad stream).
                        onStreamFailed?.invoke(song)
                    }
                    return@launch
                }
                // A newer tap superseded this one while we were resolving — drop it.
                if (currentRequest != song) return@launch
                withContext(Dispatchers.Main) {
                    if (currentRequest != song) return@withContext
                    ensurePlayer(appContext)
                    player!!.setMediaItem(buildMediaItem(streamUrl, streamMimeType(streamUrl)))
                    player!!.prepare()
                    // Restored session: continue from where the last run stopped.
                    if (song == restoreQuery && restorePositionMs > 0) {
                        player!!.seekTo(restorePositionMs)
                    }
                    restoreQuery = null
                    player!!.playWhenReady = true
                }
                startPositionWatch()
                prefetchNextTracks(appContext, 3)
            } catch (e: Exception) {
                Log.e(TAG, "playSong failed for query: $song", e)
            }
        }
    }

    // Build a MediaItem carrying the current track's metadata so the system media
    // notification (MediaSession) shows the right title / artist / artwork.
    private fun buildMediaItem(streamUrl: String, mimeType: String? = null): MediaItem {
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(metaTitle)
            .setArtist(metaArtist)
            .apply { if (metaCover.isNotBlank()) setArtworkUri(android.net.Uri.parse(metaCover)) }
            .build()
        return MediaItem.Builder()
            .setUri(streamUrl)
            // Hint the container so ExoPlayer picks the right source/extractor even
            // when the URL has no extension: TIDAL lossless is a DASH .mpd manifest,
            // and single-file lossless is FLAC.
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .setMediaMetadata(metadata)
            .build()
    }

    /** MIME hint for a resolved stream: DASH manifest, single-file FLAC, or none. */
    private fun streamMimeType(streamUrl: String): String? {
        val bare = streamUrl.substringBefore('?').lowercase()
        return when {
            streamUrl.startsWith("deezer://") ->
                if (streamUrl.contains("fmt=flac")) androidx.media3.common.MimeTypes.AUDIO_FLAC
                else androidx.media3.common.MimeTypes.AUDIO_MPEG
            streamUrl.startsWith("data:application/dash+xml") ||
                bare.endsWith(".mpd") || streamUrl.contains("manifest.tidal.com") || streamUrl.contains("/manifests/") ->
                androidx.media3.common.MimeTypes.APPLICATION_MPD
            bare.endsWith(".flac") || currentSource.startsWith("Lossless") ->
                androidx.media3.common.MimeTypes.AUDIO_FLAC
            else -> null
        }
    }

    /** Warm the cache for an upcoming track (e.g. the next/previous queue item). */
    fun prefetch(song: String, context: Context) {
        if (song.isBlank() || streamCache.containsKey(song) || com.music.spotui.player.StreamUrlCache.get(song) != null) return
        val appContext = context.applicationContext
        // No point resolving YouTube streams while Spotify web is the engine — it's
        // wasted network/CPU that competes with the streaming audio (caused stutter).
        if (webPlaybackActive()) return
        scope.launch {
            val url = runCatching { resolveStreamUrl(song, appContext) }.getOrNull()
            if (url != null) cacheIntro(url, appContext)
        }
    }

    /**
     * Warm the cache for the first [count] tracks of a freshly-loaded list
     * (album/artist/search). Resolves them sequentially so we don't fire a dozen
     * PoToken/player chains at once, but get the likely-next taps ready ahead of
     * time — this is what kills the "~3s per track" first-tap latency.
     */
    fun prefetchList(songs: List<String>, context: Context, count: Int = 4) {
        // Do not resolve streams for whole result/album lists. That made search
        // and album screens kick off several network player/FLAC lookups before
        // the user chose anything, which feels like the app is downloading the
        // catalog instead of streaming the tapped song.
    }

    /** Pre-caches the stream and intro audio for the next [count] tracks in the current queue. */
    fun prefetchNextTracks(context: Context, count: Int = 3) {
        val state = boundState ?: return
        val q = state.queue.value
        if (q.isEmpty()) return
        val curIndex = state.songIndex.value
        val isOnline = com.music.spotui.data.preferences.isNetworkAvailable(context)

        scope.launch(Dispatchers.IO) {
            for (i in 1..count) {
                val nextIdx = curIndex + i
                if (nextIdx >= q.size) break
                val nextSong = q[nextIdx]
                launch {
                    if (isOnline) {
                        prefetch(nextSong.url, context)
                    }
                }
            }
        }
    }

    // ── Intro preloading (instant playback) ──
    // Resolving the stream URL hides most latency, but ExoPlayer still has to open the
    // connection and buffer the first segment on tap. We pre-cache the first ~1 MB (≈20–40s
    // of audio) of upcoming tracks into a media cache the player reads through, so a tap on a
    // preloaded track starts almost instantly. Skipped for local files (already instant) and
    // when the user turns preloading off in Settings.
    private const val PRELOAD_BYTES = 1L * 1024 * 1024

    @Volatile private var mediaCache: androidx.media3.datasource.cache.SimpleCache? = null

    private fun mediaCache(context: Context): androidx.media3.datasource.cache.SimpleCache =
        mediaCache ?: synchronized(this) {
            mediaCache ?: androidx.media3.datasource.cache.SimpleCache(
                java.io.File(context.cacheDir, "media"),
                androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
                androidx.media3.database.StandaloneDatabaseProvider(context),
            ).also { mediaCache = it }
        }

    private fun cacheDataSourceFactory(context: Context): androidx.media3.datasource.cache.CacheDataSource.Factory {
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )
            .setAllowCrossProtocolRedirects(true)
        val baseUpstream = androidx.media3.datasource.DefaultDataSource.Factory(context, http)
        val resolvedUpstream = androidx.media3.datasource.ResolvingDataSource.Factory(baseUpstream) { dataSpec ->
            val url = dataSpec.uri.toString()
            if (url.contains("googlevideo.com")) {
                dataSpec.withAdditionalHeaders(
                    com.metrolist.innertube.models.YouTubeClient.forStreamUrl(url).mediaHeaders(),
                )
            } else dataSpec
        }
        val upstream = com.music.spotui.playback.ChunkedDataSource.Factory(
            resolvedUpstream,
            2L * 1024 * 1024,
        )
        return androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(mediaCache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Pre-cache the first [PRELOAD_BYTES] of [url] into the media cache (http(s) only). */
    private fun cacheIntro(url: String, appContext: Context) {
        if (!url.startsWith("http")) return
        if (!com.music.spotui.data.preferences.isPreloadEnabled(appContext)) return
        runCatching {
            val ds = cacheDataSourceFactory(appContext).createDataSource()
            val spec = androidx.media3.datasource.DataSpec.Builder()
                .setUri(android.net.Uri.parse(url))
                .setLength(PRELOAD_BYTES)
                .build()
            androidx.media3.datasource.cache.CacheWriter(ds, spec, null, null).cache()
        }.onFailure { Log.d(TAG, "intro preload skipped: ${it.message}") }
    }

    // forPlayback=true only for the track actually being played — so background
    // prefetch of upcoming tracks doesn't clobber the current source badge (a
    // prefetch resolving the NEXT track via YouTube was flipping the badge to
    // "YouTube" while the current track streamed from Spotify).
    private suspend fun resolveStreamUrl(song: String, appContext: Context, forPlayback: Boolean = false): String? {
        // Imported local files: the play query IS the file's content:// / file:// URI.
        // ExoPlayer plays it directly (FLAC/MP3/WAV/… via its built-in extractors).
        if (song.startsWith("content://") || song.startsWith("file://")) {
            if (forPlayback) {
                currentSource = "Local file"
                currentQuality = song.substringBefore('?').substringAfterLast('.', "")
                    .uppercase().takeIf { it.length in 2..5 }.orEmpty()
            }
            return song
        }
        alternativeStreamForPlayback(song, appContext)?.let { alt ->
            invalidateResolvedStream(song)
            return when {
                alt.isLocal -> {
                    if (forPlayback) {
                        currentSource = "Alternative file"
                        currentQuality = alt.label.substringAfterLast('.', "").uppercase().takeIf { it.length in 2..5 }.orEmpty()
                    }
                    alt.value
                }
                alt.isYouTube -> {
                    if (forPlayback) {
                        currentSource = "Alternative YouTube"
                        currentQuality = ""
                    }
                    val quality = com.music.spotui.data.preferences.currentStreamingQuality(appContext)
                    val playback = resolveYtPlayback(alt.value, quality.audioQuality, appContext) ?: return null
                    val codec = playback.format.mimeType
                        .substringAfter("codecs=\"", "").substringBefore('"').substringBefore('.')
                        .uppercase()
                    val ytQuality = listOf(codec, "${playback.format.bitrate / 1000} kbps")
                        .filter { it.isNotBlank() }.joinToString(" ")
                    if (forPlayback) currentQuality = ytQuality
                    playback.streamUrl
                }
                else -> null
            }
        }
        // Offline: if this track was downloaded, play the local file instead of the network.
        com.music.spotui.data.preferences.downloadedPathForQuery(appContext, song)?.let { path ->
            if (forPlayback) {
                currentSource = "Downloaded"
                currentQuality = path.substringAfterLast('.', "").uppercase()
            }
            return android.net.Uri.fromFile(java.io.File(path)).toString()
        }

        // Quality for the current network (Wi-Fi vs cellular), from Settings.
        val quality = com.music.spotui.data.preferences.currentStreamingQuality(appContext)

        // High-Speed Stream URL LRU Cache (4-hr TTL)
        com.music.spotui.player.StreamUrlCache.getEntry(song)?.let { cached ->
            val isSuboptimalDeezerCache = cached.source == "Deezer" &&
                cached.quality.contains("128") &&
                quality != com.music.spotui.data.preferences.StreamQuality.LOW
            if (!isSuboptimalDeezerCache) {
                if (forPlayback) {
                    currentSource = cached.source.ifBlank { "YouTube" }
                    currentQuality = cached.quality
                }
                return cached.url
            } else {
                com.music.spotui.player.StreamUrlCache.remove(song)
            }
        }

        streamCache[song]?.let { cachedUrl ->
            // Source choices take effect immediately; never let a URL cached under the
            // previous choice silently keep using that provider.
            val cachedSource = sourceCache[song]
            val selectedSource = com.music.spotui.data.preferences.getPrimaryMusicSource(appContext)
            val sourceStillSelected = when (cachedSource) {
                "Deezer" -> selectedSource == com.music.spotui.data.preferences.MusicSource.DEEZER
                "YouTube" -> selectedSource != com.music.spotui.data.preferences.MusicSource.DEEZER
                else -> true // local, downloaded, alternative and lossless sources are independent
            }
            if (!sourceStillSelected) {
                invalidateResolvedStream(song)
            } else {
            // Cache hits must still update the badge — returning early kept the
            // previous track's label (e.g. "Downloaded") on a streamed track.
                if (forPlayback) {
                    currentSource = cachedSource ?: "YouTube"
                    currentQuality = qualityCache[song] ?: ""
                }
                return cachedUrl
            }
        }

        // Deezer — preferred, but when Lossless is selected a FREE Deezer account only
        // yields MP3. In that case we HOLD the MP3 as a fallback and try the real FLAC
        // sources first, so lossless isn't silently pre-empted by Deezer MP3.
        var heldDeezer: com.music.spotui.deezer.DeezerSource.Result.Success? = null
        if (deezerEnabled && com.music.spotui.data.preferences.isDeezerEnabled(appContext)) {
            val spotifyId = trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song)
            val r = kotlinx.coroutines.withTimeoutOrNull(6_000) {
                com.music.spotui.deezer.DeezerSource.resolve(
                    appContext,
                    spotifyId = spotifyId,
                    isrc = null,
                    searchQuery = searchTextForPlayback(song),
                    expectedDurationSec = (durationRegistry[song] ?: 0) / 1000,
                )
            }
            if (r is com.music.spotui.deezer.DeezerSource.Result.Success) {
                if (r.mimeFlac || !quality.lossless) {
                    Log.d(TAG, "deezer ${r.qualityLabel} for: $song")
                    if (forPlayback) { currentSource = "Deezer"; currentQuality = r.qualityLabel }
                    streamCache[song] = r.uri
                    sourceCache[song] = "Deezer"
                    qualityCache[song] = r.qualityLabel
                    com.music.spotui.player.StreamUrlCache.put(
                        trackId = song,
                        directUrl = r.uri,
                        source = "Deezer",
                        quality = r.qualityLabel,
                    )
                    return r.uri
                }
                heldDeezer = r // Deezer MP3, but Lossless requested — try FLAC first.
                Log.d(TAG, "deezer only MP3; trying FLAC first for: $song")
            } else {
                Log.d(TAG, "deezer miss ($r), continuing for: $song")
            }
        }

        // Lossless FLAC: SpotiFLAC gated (if verified) + Tidal/community, ISRC-matched.
        if (losslessStreaming && quality.lossless) {
            (trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song))?.let { spotifyId ->
                val r = kotlinx.coroutines.withTimeoutOrNull(3_500) {
                    com.music.spotui.lossless.LosslessSource.resolve(appContext, spotifyId, preferHiRes = losslessHiRes)
                }
                if (r is com.music.spotui.lossless.LosslessSource.Result.Success) {
                    val flacQuality = "FLAC ${r.track.quality}-bit"
                    if (forPlayback) {
                        currentSource = "Lossless • ${r.track.provider}"
                        currentQuality = flacQuality
                    }
                    streamCache[song] = r.track.url
                    sourceCache[song] = "Lossless • ${r.track.provider}"
                    qualityCache[song] = flacQuality
                    com.music.spotui.player.StreamUrlCache.put(
                        trackId = song,
                        directUrl = r.track.url,
                        source = "Lossless • ${r.track.provider}",
                        quality = flacQuality,
                    )
                    return r.track.url
                } else {
                    Log.d(TAG, "lossless miss ($r) for: $song")
                }
            }
        }

        // Deezer MP3 fallback (held above) before dropping to YouTube.
        heldDeezer?.let { r ->
            Log.d(TAG, "using Deezer MP3 fallback for: $song")
            if (forPlayback) { currentSource = "Deezer"; currentQuality = r.qualityLabel }
            streamCache[song] = r.uri
            sourceCache[song] = "Deezer"
            qualityCache[song] = r.qualityLabel
            com.music.spotui.player.StreamUrlCache.put(
                trackId = song,
                directUrl = r.uri,
                source = "Deezer",
                quality = r.qualityLabel,
            )
            return r.uri
        }
        if (!youtubeEnabled) {
            Log.w(TAG, "YouTube fallback disabled — no stream for: $song")
            return null
        }
        if (forPlayback) {
            currentSource = "YouTube"
            // Clear the previous track's quality so a failed resolve can't leave
            // a stale "FLAC 24-bit" badge on a YouTube stream.
            currentQuality = ""
        }
        val playback = resolveYtPlayback(song, quality.audioQuality, appContext) ?: return null
        // e.g. "OPUS 141 kbps" from the chosen adaptive format.
        val codec = playback.format.mimeType
            .substringAfter("codecs=\"", "").substringBefore('"').substringBefore('.')
            .uppercase()
        val ytQuality = listOf(codec, "${playback.format.bitrate / 1000} kbps")
            .filter { it.isNotBlank() }.joinToString(" ")
        if (forPlayback) currentQuality = ytQuality
        streamCache[song] = playback.streamUrl
        sourceCache[song] = "YouTube"
        qualityCache[song] = ytQuality
        com.music.spotui.player.StreamUrlCache.put(
            trackId = song,
            directUrl = playback.streamUrl,
            source = "YouTube",
            quality = ytQuality,
        )
        return playback.streamUrl
    }

    private fun alternativeStreamForPlayback(
        song: String,
        appContext: Context,
    ): com.music.spotui.data.preferences.AlternativeStream? {
        val key = alternativeKeyRegistry[song]
            ?: spotifyTrackIdForPlayback(song)?.let {
                com.music.spotui.data.preferences.alternativeStreamKeyForSpotifyId(it)
            }
        return key?.let { com.music.spotui.data.preferences.getAlternativeStream(appContext, it) }
    }

    // ── Downloads (offline playback) ──
    // Tracks which song queries are mid-download so the UI can show a spinner and
    // we don't kick off the same download twice.
    private val downloading = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    @Volatile var onDownloadsChanged: (() -> Unit)? = null

    /** Fired (main thread) when a stream can't be resolved for [song], so the caller
     *  can skip to the next track instead of leaving playback silent. */
    @Volatile var onStreamFailed: ((String) -> Unit)? = null

    // Per-query download progress, 0..100. Present only while a download is active.
    private val downloadProgress = java.util.concurrent.ConcurrentHashMap<String, Int>()
    // The actual SongsModel of each in-progress download, so the Downloads screen can
    // render it (with a progress bar) before the file exists / it's added to prefs.
    private val downloadingSongs =
        java.util.concurrent.ConcurrentHashMap<String, com.music.spotui.data.entity.SongsModel>()

    fun isDownloading(query: String): Boolean = downloading.contains(query)

    /** Current download progress (0..100) for a query, or -1 if unknown/not downloading. */
    fun downloadProgress(query: String): Int = downloadProgress[query] ?: -1

    /** Snapshot of the currently-downloading tracks paired with their percent (0..100). */
    fun downloadingSnapshot(): List<Pair<com.music.spotui.data.entity.SongsModel, Int>> =
        downloadingSongs.entries.map { (q, song) -> song to (downloadProgress[q] ?: 0) }

    // Last download failure reason, surfaced to the user as a Toast for diagnosis.
    @Volatile var lastDownloadError: String? = null

    // googlevideo stream URLs 403 without a browser User-Agent and need redirects
    // followed (http↔https) — ExoPlayer does both, so a raw URLConnection must too.
    private fun openDownloadConn(url: String): java.net.HttpURLConnection =
        (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )
        }

    /**
     * Download [url] to [tmpFile] using HTTP **Range** requests in chunks, reporting
     * progress (0..100) for [query]. A single full-file GET of a googlevideo stream gets
     * reset partway through (`SocketException: Connection reset`) — the server expects the
     * audio fetched in byte ranges, which is how ExoPlayer/NewPipe get it. Each chunk is a
     * short connection (retried a few times on reset); writing is append-continuous so a
     * retried chunk resumes from the current byte position. Returns true iff the whole
     * file was written. Falls back gracefully if the server ignores Range (HTTP 200).
     */
    private fun httpDownloadRanged(url: String, tmpFile: java.io.File, query: String): Boolean {
        val chunk = 8L * 1024 * 1024 // 8 MB
        var total = -1L
        var position = 0L
        downloadProgress[query] = 0
        try {
            java.io.BufferedOutputStream(tmpFile.outputStream()).use { output ->
                outer@ while (true) {
                    val end = if (total > 0) minOf(position + chunk - 1, total - 1) else position + chunk - 1
                    var attempt = 0
                    var fullBody = false
                    while (true) {
                        attempt++
                        val conn = openDownloadConn(url)
                        conn.setRequestProperty("Range", "bytes=$position-$end")
                        try {
                            val code = conn.responseCode
                            if (code !in 200..299) {
                                lastDownloadError = "Stream returned HTTP $code"
                                return false
                            }
                            if (total < 0) {
                                total = conn.getHeaderField("Content-Range")
                                    ?.substringAfter('/')?.toLongOrNull()
                                    ?: conn.contentLengthLong
                            }
                            fullBody = code == 200 // server ignored Range → whole file in one body
                            conn.inputStream.use { input ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val r = input.read(buf)
                                    if (r < 0) break
                                    output.write(buf, 0, r)
                                    position += r
                                    if (total > 0) {
                                        val pct = ((position * 100) / total).toInt().coerceIn(0, 100)
                                        if (downloadProgress[query] != pct) {
                                            downloadProgress[query] = pct
                                            onDownloadsChanged?.invoke()
                                        }
                                    }
                                }
                            }
                            break // this chunk completed
                        } catch (e: Exception) {
                            Log.w(TAG, "chunk @${position} failed (attempt $attempt): ${e.message}")
                            if (attempt >= 4) {
                                lastDownloadError = e.message ?: "Connection reset"
                                return false
                            }
                            // retry the remainder of this chunk from the current position
                        } finally {
                            conn.disconnect()
                        }
                    }
                    if (fullBody) { total = position; break@outer }
                    if (total in 1..position) break@outer
                    if (total < 0) break@outer // couldn't determine size; assume done
                }
            }
            downloadProgress[query] = 100
            return total <= 0 || position >= total
        } catch (e: Exception) {
            lastDownloadError = e.message ?: "Download error"
            return false
        }
    }

    /**
     * Resolve the track's stream and save the audio to local storage for offline
     * playback. Runs on the IO scope; invokes [onComplete] (main thread) with whether
     * it succeeded. No-op if it's already downloaded or downloading.
     */
    /** Download every track in a list (album/playlist). Each song dedupes and
     *  reports its own progress via the existing per-song machinery. */
    fun downloadAll(songs: List<com.music.spotui.data.entity.SongsModel>, context: Context) {
        songs.forEach { downloadSong(it, context) }
    }

    /** True once every track in [songs] is downloaded (for the album's "downloaded" state). */
    fun allDownloaded(
        songs: List<com.music.spotui.data.entity.SongsModel>,
        context: Context,
    ): Boolean {
        if (songs.isEmpty()) return false
        val appContext = context.applicationContext
        return songs.all {
            com.music.spotui.data.preferences.isDownloaded(appContext, it.id.toString())
        }
    }

    fun downloadSong(
        song: com.music.spotui.data.entity.SongsModel,
        context: Context,
        onComplete: (Boolean) -> Unit = {},
    ) {
        val appContext = context.applicationContext
        val query = song.url
        if (query.isBlank() ||
            com.music.spotui.data.preferences.isDownloaded(appContext, song.id.toString()) ||
            !downloading.add(query)
        ) return
        downloadingSongs[query] = song
        downloadProgress[query] = 0
        onDownloadsChanged?.invoke()
        lastDownloadError = null
        scope.launch {
            val ok = runCatching { downloadToFile(song, appContext) }
                .onFailure { lastDownloadError = it.message ?: "Unexpected error" }
                .getOrDefault(false)
            downloading.remove(query)
            downloadProgress.remove(query)
            downloadingSongs.remove(query)
            withContext(Dispatchers.Main) {
                if (!ok) {
                    android.widget.Toast.makeText(
                        appContext,
                        "Download failed: ${lastDownloadError ?: "unknown reason"}",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                onDownloadsChanged?.invoke()
                onComplete(ok)
            }
        }
    }

    private suspend fun downloadToFile(
        song: com.music.spotui.data.entity.SongsModel,
        appContext: Context,
    ): Boolean {
        val dlQuality = com.music.spotui.data.preferences.getDownloadQuality(appContext)
        // Deezer: use immediately if it yields FLAC (HiFi). If it only yields MP3
        // (free account), HOLD it and try the real FLAC sources first — otherwise a
        // free Deezer MP3 would pre-empt lossless.
        var heldDeezer: com.music.spotui.deezer.DeezerSource.Resolved? = null
        if (deezerEnabled && com.music.spotui.data.preferences.isDeezerEnabled(appContext)) {
            val raw = kotlinx.coroutines.withTimeoutOrNull(30_000) { resolveDeezerRaw(song, appContext) }
            if (raw != null) {
                if (raw.isFlac) {
                    if (downloadDeezerRaw(song, appContext, raw)) return true
                } else {
                    heldDeezer = raw
                }
            }
        }
        // Lossless FLAC: SpotiFLAC gated (if verified) + Tidal/community. Saves .flac.
        if (losslessStreaming && song.spotifyTrackId.isNotBlank()) {
            val r = kotlinx.coroutines.withTimeoutOrNull(45_000) {
                com.music.spotui.lossless.LosslessSource.resolve(appContext, song.spotifyTrackId, preferHiRes = losslessHiRes)
            }
            if (r is com.music.spotui.lossless.LosslessSource.Result.Success) {
                val dir = java.io.File(appContext.filesDir, "downloads").apply { mkdirs() }
                val outFile = java.io.File(dir, "${song.id}.flac")
                val tmpFile = java.io.File(dir, "${song.id}.flacpart")
                if (httpDownloadRanged(r.track.url, tmpFile, song.url) && tmpFile.renameTo(outFile)) {
                    com.music.spotui.data.preferences.addDownload(appContext, song, outFile.absolutePath)
                    Log.d(TAG, "lossless downloaded (${r.track.provider} ${r.track.quality}-bit): ${song.title}")
                    return true
                }
                runCatching { tmpFile.delete() }
            }
        }
        // Deezer MP3 fallback (held above) before dropping to a YouTube m4a.
        heldDeezer?.let { if (downloadDeezerRaw(song, appContext, it)) return true }
        if (!youtubeEnabled) {
            lastDownloadError = "Track not available for download"
            return false
        }

        val query = song.url
        // Resolve a fresh network stream URL (bypass any local-file short-circuit),
        // walking the ranked video candidates like playback does.
        val playback = resolveYtPlayback(query, dlQuality.audioQuality, appContext) ?: run {
            lastDownloadError = "Couldn't resolve a stream"
            return false
        }

        val dir = java.io.File(appContext.filesDir, "downloads").apply { mkdirs() }
        val outFile = java.io.File(dir, "${song.id}.m4a")
        val tmpFile = java.io.File(dir, "${song.id}.part")

        if (!httpDownloadRanged(playback.streamUrl, tmpFile, song.url)) {
            runCatching { tmpFile.delete() }
            return false
        }
        if (!tmpFile.renameTo(outFile)) {
            lastDownloadError = "Couldn't save file"
            runCatching { tmpFile.delete() }
            return false
        }
        com.music.spotui.data.preferences.addDownload(appContext, song, outFile.absolutePath)
        return true
    }

    // (Removed downloadFlacToFile — the SpotiFLAC community-proxy FLAC downloader.
    // Those servers are dead and it never produced a file; real FLAC now comes from
    // the Deezer downloader below.)

    /**
     * Download a Deezer track and decrypt it to a real audio file. Resolves the
     * stream (ISRC → Deezer, account-tier quality), streams the Blowfish-encrypted
     * CDN bytes and decrypts every 3rd 2048-byte block on the way to `<id>.flac` /
     * `<id>.mp3`. Returns false on any miss so the caller can fall back.
     */
    private suspend fun resolveDeezerRaw(
        song: com.music.spotui.data.entity.SongsModel,
        appContext: Context,
    ): com.music.spotui.deezer.DeezerSource.Resolved? =
        com.music.spotui.deezer.DeezerSource.resolveRaw(
            appContext,
            spotifyId = song.spotifyTrackId.takeIf { it.isNotBlank() },
            isrc = null,
            searchQuery = listOf(song.title, song.singer).filter { it.isNotBlank() }.joinToString(" "),
            expectedDurationSec = if (song.durationMs > 0) song.durationMs / 1000 else 0,
        )

    private fun downloadDeezerRaw(
        song: com.music.spotui.data.entity.SongsModel,
        appContext: Context,
        raw: com.music.spotui.deezer.DeezerSource.Resolved,
    ): Boolean {
        val ext = if (raw.isFlac) "flac" else "mp3"
        val dir = java.io.File(appContext.filesDir, "downloads").apply { mkdirs() }
        val outFile = java.io.File(dir, "${song.id}.$ext")
        val tmpFile = java.io.File(dir, "${song.id}.dzpart")
        if (!deezerDownloadDecrypted(raw.url, raw.encrypted, raw.trackId, tmpFile, song.url)) {
            runCatching { tmpFile.delete() }
            return false
        }
        if (!tmpFile.renameTo(outFile)) {
            lastDownloadError = "Couldn't save file"
            runCatching { tmpFile.delete() }
            return false
        }
        com.music.spotui.data.preferences.addDownload(appContext, song, outFile.absolutePath)
        Log.d(TAG, "Deezer downloaded (${raw.qualityLabel}): ${song.title}")
        return true
    }

    /**
     * Stream [url] and, if [encrypted], Blowfish-decrypt every 3rd 2048-byte block
     * (Deezer's stripe cipher) while writing to [tmpFile], reporting progress for
     * [query]. Produces a plain, fully-decrypted audio file.
     */
    private fun deezerDownloadDecrypted(
        url: String,
        encrypted: Boolean,
        trackId: String,
        tmpFile: java.io.File,
        query: String,
    ): Boolean {
        downloadProgress[query] = 0
        val conn = openDownloadConn(url)
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                lastDownloadError = "Deezer CDN HTTP $code"
                return false
            }
            val total = conn.contentLengthLong
            val cipher = if (encrypted) {
                com.music.spotui.deezer.DeezerCrypto.cipher(
                    com.music.spotui.deezer.DeezerCrypto.trackKey(trackId),
                )
            } else {
                null
            }
            java.io.BufferedOutputStream(tmpFile.outputStream()).use { output ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(2048)
                    var counter = 0
                    var position = 0L
                    while (true) {
                        var read = 0
                        while (read < 2048) {
                            val r = input.read(buf, read, 2048 - read)
                            if (r < 0) break
                            read += r
                        }
                        if (read == 0) break
                        val out = if (encrypted && read == 2048 && counter % 3 == 0) {
                            cipher!!.doFinal(buf)
                        } else {
                            buf
                        }
                        output.write(out, 0, read)
                        counter++
                        position += read
                        if (total > 0) {
                            val pct = ((position * 100) / total).toInt().coerceIn(0, 100)
                            if (downloadProgress[query] != pct) {
                                downloadProgress[query] = pct
                                onDownloadsChanged?.invoke()
                            }
                        }
                        if (read < 2048) break // final partial chunk
                    }
                }
            }
            downloadProgress[query] = 100
            true
        } catch (e: Exception) {
            lastDownloadError = e.message ?: "Deezer download error"
            false
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private data class CandidateScore(
        val item: SongItem,
        val score: Double,
        val titleScore: Double,
        val artistScore: Double,
        val artistEvidenceScore: Double,
        val durationScore: Double?,
        val albumScore: Double?,
        val alternatePenalty: Double,
        val unexpectedAlternates: List<String>,
    )

    private fun normalizedForMatch(value: String): String =
        value.lowercase()
            .replace(featSearchPattern, "")
            .replace(Regex("""[^\p{L}\p{Nd}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private val anyLatinTransliterator by lazy {
        runCatching {
            android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII")
        }.getOrNull()
    }

    private fun foldLatinDiacritics(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")

    private fun transliterateCyrillic(value: String): String {
        val map = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
            'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
            'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
            'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
            'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch",
            'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "",
            'э' to "e", 'ю' to "yu", 'я' to "ya",
        )
        return buildString {
            value.lowercase().forEach { ch ->
                append(map[ch] ?: ch)
            }
        }
    }

    private fun bigramSimilarity(a: String, b: String): Double {
        fun variants(value: String): List<String> =
            listOfNotNull(
                value,
                foldLatinDiacritics(value),
                transliterateCyrillic(value),
                anyLatinTransliterator?.transliterate(value),
            )
                .map(::normalizedForMatch)
                .filter { it.isNotBlank() }
                .distinct()

        fun score(na: String, nb: String): Double {
            if (na == nb) return 1.0
            if (na.length < 2 || nb.length < 2) return 0.0
            val aBigrams = na.windowed(2).toSet()
            val bBigrams = nb.windowed(2).toSet()
            if (aBigrams.isEmpty() || bBigrams.isEmpty()) return 0.0
            val intersection = aBigrams.count { it in bBigrams }
            return (2.0 * intersection) / (aBigrams.size + bBigrams.size)
        }
        return variants(a).maxOf { aa ->
            variants(b).maxOf { bb -> score(aa, bb) }
        }
    }

    private data class VersionMarker(
        val name: String,
        val pattern: Regex,
        val hardReject: Boolean,
    )

    private fun markerPattern(terms: String): Regex =
        Regex("""(^|\s)($terms)(\s|$)""", RegexOption.IGNORE_CASE)

    private val alternateVersionMarkers = listOf(
        VersionMarker("remix", markerPattern("""re\s*mix|rmx|club mix|dance mix|dub mix|vip mix|ремикс|рмикс"""), true),
        VersionMarker("alternate", markerPattern("""alternative|alternate|alt version|demo|demo version|unreleased|rough mix|early version|альтернатив\w*|демо|неиздан\w*|чернов\w*"""), true),
        VersionMarker("sped up", markerPattern("""sped\s*up|speed\s*up|fast version|ускоренн\w*|быстрая версия"""), true),
        VersionMarker("slowed", markerPattern("""slowed|slowed reverb|slow version|замедленн\w*|медленная версия"""), true),
        VersionMarker("nightcore", markerPattern("""nightcore|daycore"""), true),
        VersionMarker("live", markerPattern("""live|concert|session|performance|лайв|концерт|с концерта|выступлен\w*"""), true),
        VersionMarker("acoustic", markerPattern("""acoustic|unplugged|piano version|guitar version|акустик\w*|пианино|гитар\w*"""), true),
        VersionMarker("cover", markerPattern("""cover|covered by|tribute|кавер|трибьют"""), true),
        VersionMarker("karaoke", markerPattern("""karaoke|minus one|караоке|минусовка"""), true),
        VersionMarker("instrumental", markerPattern("""instrumental|no vocals|инструментал|без вокала"""), true),
        VersionMarker("mashup", markerPattern("""mashup|mash up|bootleg|rework|flip|мешап|мэшап|бутлег"""), true),
        VersionMarker("fan edit", markerPattern("""fan edit|fanmade|right version|edit audio|перезалив|перезалит\w*"""), true),
        VersionMarker("extended", markerPattern("""extended mix|extended version|12 inch|12"""), false),
        VersionMarker("radio edit", markerPattern("""radio edit|single edit|edit version"""), false),
        VersionMarker("remaster", markerPattern("""remaster|remastered|anniversary edition"""), false),
    )

    private fun versionMarkers(value: String): Set<String> {
        val normalized = normalizedForMatch(value)
        return alternateVersionMarkers
            .filter { marker -> marker.pattern.containsMatchIn(normalized) }
            .map { it.name }
            .toSet()
    }

    private fun hardVersionMarkers(names: Collection<String>): Set<String> {
        val hardNames = alternateVersionMarkers.filter { it.hardReject }.map { it.name }.toSet()
        return names.filterTo(mutableSetOf()) { it in hardNames }
    }

    private fun ytmusicTransferScore(
        candidate: SongItem,
        expected: TrackMatchMetadata,
        expectedDurationMs: Int,
    ): CandidateScore {
        var candidateTitle = candidate.title
        if (candidate.isVideoSong) {
            val split = candidateTitle.split("-", limit = 2)
            if (split.size == 2) candidateTitle = split[1].trim()
        }

        val titleScore = bigramSimilarity(candidateTitle, expected.title)
        val uploaderArtistScore = bigramSimilarity(
            candidate.artists.joinToString(" ") { it.name },
            expected.artist,
        )
        val titleArtistScore = if (candidate.isVideoSong) {
            bigramSimilarity(candidate.title.substringBefore("-"), expected.artist)
        } else {
            0.0
        }
        val artistScore = maxOf(uploaderArtistScore, titleArtistScore)

        val expectedDurationSec = expectedDurationMs / 1000.0
        val candidateDuration = candidate.duration
        val durationScore = if (expectedDurationSec > 0 && candidateDuration != null) {
            (1.0 - abs(candidateDuration - expectedDurationSec) * 2.0 / expectedDurationSec)
                .coerceIn(0.0, 1.0)
        } else {
            null
        }

        val albumScore = if (!candidate.isVideoSong && expected.album.isNotBlank()) {
            candidate.album?.name?.let { bigramSimilarity(it, expected.album) }
        } else {
            null
        }

        val expectedMarkers = versionMarkers(expected.title)
        val candidateMarkers = versionMarkers(
            listOf(
                candidate.title,
                candidate.album?.name.orEmpty(),
            ).joinToString(" "),
        )
        val unexpectedAlternates = (candidateMarkers - expectedMarkers).toList().sorted()
        val hardUnexpected = hardVersionMarkers(unexpectedAlternates).size
        val softUnexpected = unexpectedAlternates.size - hardUnexpected
        val alternatePenalty = hardUnexpected * 1.75 + softUnexpected * 0.65

        val parts = mutableListOf(titleScore, artistScore)
        durationScore?.let { parts += it * 5.0 }
        albumScore?.let { parts += it }
        val resultTypeBoost = if (candidate.isVideoSong) 1.0 else 2.0
        val baseScore = parts.average() * resultTypeBoost
        return CandidateScore(
            item = candidate,
            score = (baseScore - alternatePenalty).coerceAtLeast(0.0),
            titleScore = titleScore,
            artistScore = uploaderArtistScore,
            artistEvidenceScore = artistScore,
            durationScore = durationScore,
            albumScore = albumScore,
            alternatePenalty = alternatePenalty,
            unexpectedAlternates = unexpectedAlternates,
        )
    }

    private fun CandidateScore.isAcceptableMatch(): Boolean {
        val durationStrong = durationScore?.let { it >= 0.94 } ?: false
        val albumUseful = albumScore?.let { it >= 0.45 } ?: false
        val hasDuration = durationScore != null
        val minScore = when {
            hasDuration && item.isVideoSong -> 1.55
            hasDuration -> 2.25
            item.isVideoSong -> 0.78
            else -> 1.35
        }

        // Duration is deliberately weighted heavily, as in spotify_to_ytmusic,
        // but these gates prevent a same-length wrong-artist song from winning.
        // If Spotify did not ask for a remix/live/acoustic/etc version, do not
        // let that alternate upload win just because its title and length are close.
        // When duration is missing, allow strong title+artist/album matches
        // instead of rejecting every possible candidate.
        val hasUnexpectedHardAlternate = hardVersionMarkers(unexpectedAlternates).isNotEmpty()
        return score >= minScore &&
            !hasUnexpectedHardAlternate &&
            titleScore >= 0.45 &&
            (
                artistEvidenceScore >= 0.32 ||
                    (albumUseful && artistEvidenceScore >= 0.18) ||
                    (durationStrong && artistEvidenceScore >= 0.25)
                )
    }

    private suspend fun ensureSpotifyMatchMetadata(query: String): TrackMatchMetadata? {
        val currentMeta = metadataRegistry[query]
        val hasUsefulMeta = currentMeta?.let {
            it.title.isNotBlank() && it.artist.isNotBlank() && it.album.isNotBlank()
        } ?: false
        if (hasUsefulMeta && durationRegistry[query] != null && explicitRegistry.containsKey(query) &&
            spotifyMetadataRepaired.contains(query)) {
            return currentMeta
        }

        val spotifyId = trackIdRegistry[query] ?: spotifyTrackIdForPlayback(query) ?: return currentMeta
        val track = runCatching { com.metrolist.spotify.Spotify.track(spotifyId).getOrNull() }
            .onFailure { Log.w(TAG, "Spotify metadata repair failed for $spotifyId", it) }
            .getOrNull()
            ?: return currentMeta

        val repaired = TrackMatchMetadata(
            title = track.name,
            artist = track.artists.joinToString(", ") { it.name },
            album = track.album?.name ?: currentMeta?.album.orEmpty(),
            isrc = track.isrc.orEmpty(),
        )
        metadataRegistry[query] = repaired
        trackIdRegistry[query] = spotifyId
        explicitRegistry[query] = track.explicit
        if (track.durationMs > 0) durationRegistry[query] = track.durationMs
        spotifyMetadataRepaired += query
        return repaired
    }

    private suspend fun resolveVideoCandidates(
        query: String,
        filter: YouTube.SearchFilter = YouTube.SearchFilter.FILTER_SONG,
    ): List<String> {
        val searchText = searchTextForPlayback(query)
        // A raw YouTube videoId is 11 chars with no spaces — accept it directly.
        if (searchText.length == 11 && !searchText.contains(' ')) return listOf(searchText)
        val exactMeta = ensureSpotifyMatchMetadata(query)

        val target = if (exactMeta != null) {
            com.music.spotui.resolver.TrackTarget(
                title = exactMeta.title,
                artist = exactMeta.artist,
                durationMs = durationRegistry[query]?.toLong() ?: 0L,
                album = exactMeta.album
            )
        } else {
            val parts = searchText.split(" - ", limit = 2)
            if (parts.size == 2) {
                com.music.spotui.resolver.TrackTarget(
                    title = parts[1],
                    artist = parts[0],
                    durationMs = durationRegistry[query]?.toLong() ?: 0L
                )
            } else {
                com.music.spotui.resolver.TrackTarget(
                    title = searchText,
                    artist = "",
                    durationMs = durationRegistry[query]?.toLong() ?: 0L
                )
            }
        }

        // Multi-Tiered Track Matching Engine with fallback
        val resolverCandidates = trackResolver.resolveRankedCandidates(target)
        if (resolverCandidates.isNotEmpty()) {
            val resolverIds = resolverCandidates.map { it.videoId }.distinct()
            Log.d(TAG, "TrackResolver produced ${resolverIds.size} ranked candidates for '$searchText': primary='${resolverCandidates.first().title}' [${resolverIds.first()}]")
            return resolverIds
        }

        val wantExplicit = explicitRegistry[query]

        suspend fun searchSongs(text: String): List<SongItem> {
            val firstPage = YouTube.search(text, filter)
                .onFailure { Log.w(TAG, "resolveVideoId: YouTube search failed for: $text", it) }
                .getOrNull() ?: return emptyList()
            val found = firstPage.items.filterIsInstance<SongItem>().toMutableList()

            // The explicit edition is often just below the clean edition. Walk two
            // continuation pages before accepting that YouTube Music has no match.
            var continuation = firstPage.continuation
            repeat(2) {
                if (wantExplicit != true || found.any { it.explicit } || continuation == null) return@repeat
                val next = YouTube.searchContinuation(continuation!!).getOrNull() ?: return@repeat
                found += next.items.filterIsInstance<SongItem>()
                continuation = next.continuation
            }
            return found
        }

        val searchQueries = buildList {
            if (wantExplicit == true && !exactMeta?.isrc.isNullOrBlank()) add(exactMeta!!.isrc)
            add(searchText)
            if (wantExplicit == true) add("$searchText explicit")
        }.distinct()
        val hits = searchQueries.flatMap { searchSongs(it) }.distinctBy { it.id }
        if (hits.isEmpty()) {
            Log.w(TAG, "resolveVideoId: no YouTube song results for: $searchText")
            return emptyList()
        }
        // YouTube's top hit is NOT always the requested song (worst for
        // non-English titles). Score every hit against the query — the query is
        // "title artist1, artist2":
        //   +2 artist match, +1 title match, +2 duration match.
        // Duration is the key disambiguator for same-title/different-artist: a
        // wrong-artist song with the same name almost always has a different
        // length, so it never ties the real track once duration is in the score.
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val qn = norm(searchText)
        val wantSec = durationRegistry[query]?.let { it / 1000 }
        val scored = hits.map { h ->
            val cleanTitle = norm(h.title.substringBefore('(').substringBefore('['))
            var s = 0
            if (cleanTitle.isNotEmpty() && qn.contains(cleanTitle)) s += 1
            if (h.artists.any { a -> norm(a.name).let { it.isNotEmpty() && qn.contains(it) } }) s += 2
            // Within ~4s of the Spotify track length → very likely the same recording.
            val hDur = h.duration
            if (wantSec != null && hDur != null && kotlin.math.abs(hDur - wantSec) <= 4) s += 2
            h to s
        }
        val transferScored = if (exactMeta != null) {
            hits.map { ytmusicTransferScore(it, exactMeta, durationRegistry[query] ?: 0) }
        } else {
            emptyList()
        }
        // A hit is "verified" as the right recording when its artist matches the
        // query, OR (when we know the Spotify length) its duration is within ~4s.
        fun verified(h: SongItem): Boolean {
            val artistOk = h.artists.any { a -> norm(a.name).let { it.isNotEmpty() && qn.contains(it) } }
            val d = h.duration
            val durOk = wantSec != null && d != null && kotlin.math.abs(d - wantSec) <= 4
            return artistOk || durOk
        }
        fun isRequestedEdition(item: SongItem): Boolean = when (wantExplicit) {
            true -> item.explicit || (filter == YouTube.SearchFilter.FILTER_VIDEO &&
                Regex("\\b(explicit|uncensored)\\b", RegexOption.IGNORE_CASE).containsMatchIn(item.title))
            false -> !item.explicit
            null -> true
        }
        fun explicitFirst(list: List<SongItem>) =
            if (wantExplicit != null) list.sortedByDescending { it.explicit == wantExplicit } else list
        val ordered = if (transferScored.isNotEmpty()) {
            val accepted = transferScored
                .filter { it.isAcceptableMatch() }
                .filter { isRequestedEdition(it.item) }
                .sortedWith(
                    compareByDescending<CandidateScore> { it.item.explicit == wantExplicit || wantExplicit == null }
                        .thenByDescending { it.score }
                )
                .map { it.item }
            if (accepted.isEmpty()) {
                val best = transferScored.maxByOrNull { it.score }
                Log.w(
                    TAG,
                    "resolveVideoId: rejecting weak YouTube match for '$searchText' " +
                        "(best='${best?.item?.title}' score=${"%.2f".format(best?.score ?: 0.0)} " +
                        "title=${"%.2f".format(best?.titleScore ?: 0.0)} " +
                        "artist=${"%.2f".format(best?.artistEvidenceScore ?: 0.0)} " +
                        "duration=${"%.2f".format(best?.durationScore ?: 0.0)} " +
                        "album=${"%.2f".format(best?.albumScore ?: 0.0)} " +
                        "alt=${best?.unexpectedAlternates.orEmpty().joinToString("/")})",
                )
                return emptyList()
            }
            accepted.distinctBy { it.id }
        } else {
            // Legacy/plain queries without registered Spotify metadata: keep the
            // old best-effort ordering.
            val verifiedRanked = scored
                .filter { verified(it.first) }
                .sortedByDescending { it.second }
                .map { it.first }
            val restRanked = scored
                .filter { !verified(it.first) }
                .sortedByDescending { it.second }
                .map { it.first }
            (explicitFirst(verifiedRanked) + explicitFirst(restRanked))
                .filter(::isRequestedEdition)
                .distinctBy { it.id }
        }

        if (ordered.isEmpty()) return emptyList()
        val chosen = ordered.first()
        if (transferScored.isEmpty() && !verified(chosen)) {
            Log.w(TAG, "resolveVideoId: no verified match for: $searchText (want=${wantSec}s) — best-effort '${chosen.title}'")
        }
        val chosenScore = transferScored.firstOrNull { it.item.id == chosen.id }
        Log.d(
            TAG,
            "resolveVideoId: '$searchText' -> '${chosen.title}' by " +
                chosen.artists.joinToString { it.name } +
                " [explicit=${chosen.explicit} dur=${chosen.duration}s want=${wantSec}s id=${chosen.id}] " +
                (chosenScore?.let {
                    "score=${"%.2f".format(it.score)} title=${"%.2f".format(it.titleScore)} " +
                        "artist=${"%.2f".format(it.artistEvidenceScore)} duration=${"%.2f".format(it.durationScore ?: 0.0)} " +
                        "album=${"%.2f".format(it.albumScore ?: 0.0)} " +
                        "altPenalty=${"%.2f".format(it.alternatePenalty)} " +
                        "alt=${it.unexpectedAlternates.joinToString("/")}"
                } ?: "${ordered.count { verified(it) }} verified/${ordered.size}"),
        )
        return ordered.map { it.id }.distinct()
    }

    /**
     * Resolves a playable YouTube stream for [query], falling back through up to
     * 3 ranked video candidates when one has no obtainable stream.
     */
    private suspend fun resolveYtPlayback(
        query: String,
        audioQuality: com.metrolist.music.constants.AudioQuality,
        appContext: Context,
    ): YTPlayerUtils.PlaybackData? {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val tried = mutableSetOf<String>()
        suspend fun tryIds(ids: List<String>): YTPlayerUtils.PlaybackData? {
            for (videoId in ids) {
                if (!tried.add(videoId)) continue
                YTPlayerUtils.playerResponseForPlayback(
                    videoId = videoId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                ).fold(
                    onSuccess = { return it },
                    onFailure = { Log.w(TAG, "stream failed for $videoId (${it.message}) — trying next candidate for: ${searchTextForPlayback(query)}") },
                )
            }
            return null
        }
        val candidateSongIds = resolveVideoCandidates(query).take(5)
        tryIds(candidateSongIds)?.let { return it }

        // If InnerTube player failed on all candidate IDs, attempt direct Piped resolution
        for (videoId in candidateSongIds) {
            val piped = com.music.spotui.piped.PipedSource.resolveAudioStream(videoId)
            if (piped != null) {
                Log.d(TAG, "Recovered YouTube stream via Piped fallback for videoId=$videoId")
                return YTPlayerUtils.PlaybackData(
                    audioConfig = null,
                    videoDetails = null,
                    playbackTracking = null,
                    format = com.metrolist.innertube.models.response.PlayerResponse.StreamingData.Format(
                        itag = 251,
                        url = piped.url,
                        mimeType = piped.mimeType,
                        bitrate = piped.bitrate,
                    ),
                    streamUrl = piped.url,
                    streamExpiresInSeconds = 21600,
                )
            }
        }

        if (!com.music.spotui.data.preferences.isVideoFallbackEnabled(appContext)) {
            Log.w(TAG, "song candidates exhausted and video fallback disabled for: ${searchTextForPlayback(query)}")
            return null
        }
        // Song results exhausted (e.g. every official upload is age-restricted and
        // we're not signed in to YouTube). Regular video uploads — lyric videos,
        // reuploads — usually aren't age-gated: last-resort pass over those.
        Log.w(TAG, "song candidates exhausted, trying video search for: ${searchTextForPlayback(query)}")
        val videoCandidates = resolveVideoCandidates(query, YouTube.SearchFilter.FILTER_VIDEO).take(3)
        tryIds(videoCandidates)?.let { return it }
        for (videoId in videoCandidates) {
            val piped = com.music.spotui.piped.PipedSource.resolveAudioStream(videoId)
            if (piped != null) {
                Log.d(TAG, "Recovered video stream via Piped fallback for videoId=$videoId")
                return YTPlayerUtils.PlaybackData(
                    audioConfig = null,
                    videoDetails = null,
                    playbackTracking = null,
                    format = com.metrolist.innertube.models.response.PlayerResponse.StreamingData.Format(
                        itag = 251,
                        url = piped.url,
                        mimeType = piped.mimeType,
                        bitrate = piped.bitrate,
                    ),
                    streamUrl = piped.url,
                    streamExpiresInSeconds = 21600,
                )
            }
        }
        Log.e(TAG, "All YouTube candidates failed for: ${searchTextForPlayback(query)}")
        return null
    }

    private fun buildAudioAttributes() =
        androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .build()

    /**
     * Build an ExoPlayer that reads through the shared media cache (so preloaded intro
     * bytes are reused) and carries its own [CrossfadeFilterAudioProcessor] so the DJ-style
     * low/high-pass sweep can be applied per track during a crossfade. The filter is
     * disabled (pass-through) outside a crossfade, so there's no overhead in normal playback.
     *
     * @param handleAudioFocus true for the active/session player, false for the transient
     *   secondary (incoming) player so it doesn't fight the primary for focus mid-fade.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun createPlayerWithFilter(
        context: Context,
        handleAudioFocus: Boolean,
    ): Pair<ExoPlayer, com.music.spotui.audio.CrossfadeFilterAudioProcessor> {
        val filter = com.music.spotui.audio.CrossfadeFilterAudioProcessor()
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(
                        androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain(
                            filter,
                        ),
                    ).build()
        }
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    // Routes deezer:// URIs to the decrypting DeezerDataSource and
                    // everything else through the normal cached HTTP stack.
                    com.music.spotui.deezer.DeezerAwareDataSourceFactory(cacheDataSourceFactory(context)),
                ),
            )
            .setRenderersFactory(renderers)
            .setAudioAttributes(buildAudioAttributes(), handleAudioFocus)
            .setHandleAudioBecomingNoisy(handleAudioFocus)
            .build()
        return p to filter
    }

    private fun ensurePlayer(context: Context) {
        appCtx = context.applicationContext
        if (player == null) {
            val (p, filter) = createPlayerWithFilter(context, handleAudioFocus = true)
            player = p
            currentPlayerFilter = filter
            p.addListener(midPlayErrorListener)
            onPlayerCreated?.invoke(p)
        }
    }

    /**
     * Catches ExoPlayer errors that happen mid-stream (expired CDN URL, network drop,
     * HTTP 403/404, decode failure, etc.) — none of which were previously handled,
     * so the player would silently go idle.
     *
     * IO errors (2xxx) get one re-resolve attempt: the cached URL is discarded and a
     * fresh one is fetched; if that succeeds we resume from where we left off.
     * Non-IO errors (decode, DRM, etc.) skip straight to the next track.
     */
    private val midPlayErrorListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val song = currentRequest.takeIf { it.isNotBlank() } ?: return
            Log.w(TAG, "mid-play error for $song: ${error.errorCodeName} (code ${error.errorCode})")
            // Drop the cached URL — it may have expired (Deezer CDN, YouTube signing).
            streamCache.remove(song)
            sourceCache.remove(song)
            val ctx = appCtx ?: run { onStreamFailed?.invoke(song); return }
            val isIoError = error.errorCode in 2000..2999
            scope.launch {
                if (isIoError) {
                    // Brief wait so a transient network blip has time to recover.
                    delay(3_000L)
                    // User may have manually skipped while we were waiting — bail if so.
                    if (currentRequest != song) return@launch
                    val fresh = resolveStreamUrl(song, ctx, forPlayback = true)
                    if (fresh != null && currentRequest == song) {
                        val savedPos = withContext(Dispatchers.Main) { player?.currentPosition ?: 0L }
                        withContext(Dispatchers.Main) {
                            player?.setMediaItem(buildMediaItem(fresh, streamMimeType(fresh)))
                            player?.prepare()
                            if (savedPos > 5_000L) player?.seekTo(savedPos)
                            player?.play()
                        }
                        return@launch
                    }
                }
                // Non-IO error or re-resolve failed — advance to the next track.
                withContext(Dispatchers.Main) { onStreamFailed?.invoke(song) }
            }
        }
    }

    /** The live ExoPlayer instance (may be null before first play). */
    val exoPlayer: ExoPlayer? get() = player

    /** Make sure the player exists (used by the media-session service). */
    fun ensureCreated(context: Context) = ensurePlayer(context.applicationContext)

    /** Notified right after the ExoPlayer is built so the session can attach to it. */
    @Volatile var onPlayerCreated: ((ExoPlayer) -> Unit)? = null

    fun isPlaying(): Boolean {
        if (webPlaybackActive()) return SpotifyWebPlayer.isPlaying
        return player?.isPlaying ?: false
    }

    fun webPlaybackActive(): Boolean {
        if (!webPlayerEnabled) return false
        val ctx = appCtx ?: return false
        // Spotify web playback needs: user hasn't opted out, the WebView actually has
        // Widevine, AND the user is logged into Spotify (sp_dc). Missing any of these
        // → fall back to the YouTube/FLAC engine so playback is never silent.
        return com.music.spotui.data.preferences.isWebPlaybackEnabled(ctx) &&
            SpotifyWebPlayer.canPlay &&
            com.music.spotui.data.api.SpotifySession.spDc(ctx).isNotBlank()
    }

    // ── Session restore (survive app restarts) ──
    // Set at launch from the persisted playback state; the first playSong for
    // this query seeks to the saved position, and play() with an empty player
    // re-resolves the track instead of doing nothing.
    @Volatile private var restoreQuery: String? = null
    @Volatile private var restorePositionMs: Long = 0L

    fun setRestorePoint(query: String, positionMs: Long) {
        if (query.isBlank()) return
        restoreQuery = query
        restorePositionMs = positionMs.coerceAtLeast(0L)
    }

    fun play() {
        if (webPlaybackActive()) { SpotifyWebPlayer.resume(); return }
        // Fresh launch: nothing loaded yet — resume the restored session track.
        if ((player?.mediaItemCount ?: 0) == 0) {
            val q = restoreQuery
            val ctx = appCtx
            if (q != null && ctx != null) { playSong(q, ctx); return }
        }
        player?.play()
    }

    fun pause() {
        cancelCrossfade()
        if (webPlaybackActive()) { SpotifyWebPlayer.pause(); return }
        player?.let {
            it.playWhenReady = false
            // Remember where we stopped so a relaunch can resume mid-track.
            appCtx?.let { ctx ->
                val pos = it.currentPosition
                if (pos > 0) com.music.spotui.data.preferences.saveLastPosition(ctx, pos)
            }
        }
    }

    fun stop() {
        cancelCrossfade()
        player?.stop()
    }

    fun seekTo(position: Long) {
        cancelCrossfade()
        if (webPlaybackActive()) { SpotifyWebPlayer.seekTo(position); return }
        player?.seekTo(position)
    }

    fun release() {
        positionWatchJob?.cancel()
        cancelCrossfade()
        player?.release()
        player = null
    }

    fun getDuration(): Long {
        if (webPlaybackActive()) return SpotifyWebPlayer.durationMs
        return player?.duration ?: 0L
    }

    fun getCurrentPosition(): Long {
        if (webPlaybackActive()) return SpotifyWebPlayer.positionMs
        return player?.currentPosition ?: 0L
    }

    fun isPrepared(): Boolean {
        val playerState = player?.playbackState
        return playerState != null && playerState != ExoPlayer.STATE_IDLE && playerState != ExoPlayer.STATE_ENDED
    }

    // ── Crossfade + DJ-style mixing ──
    // The end of the current track is blended into the start of the next over a user-set
    // window (Settings). A second, transient ExoPlayer plays the incoming track while the
    // primary fades out; volumes follow an equal-power (cos/sin) curve so total loudness
    // stays constant. In DJ mode, the outgoing track is low-passed (treble drops out) and the
    // incoming track high-passed (bass fills in) via per-player [CrossfadeFilterAudioProcessor]s,
    // swept on an S-curve — like a real DJ mixer. When the blend finishes the secondary player
    // is promoted to primary and the media session is re-bound to it via [onPlayerSwapped].
    private const val CF_LPF_START_HZ = 20000f
    private const val CF_LPF_END_HZ = 200f
    private const val CF_HPF_START_HZ = 2000f
    private const val CF_HPF_END_HZ = 20f
    private const val CF_SIGMOID_K = 6f

    @Volatile private var appCtx: Context? = null
    @Volatile private var boundState: CurrentSongState? = null
    @Volatile private var currentPlayerFilter: com.music.spotui.audio.CrossfadeFilterAudioProcessor? = null
    @Volatile private var secondaryPlayer: ExoPlayer? = null
    @Volatile private var secondaryPlayerFilter: com.music.spotui.audio.CrossfadeFilterAudioProcessor? = null
    @Volatile private var isCrossfading = false
    @Volatile private var crossfadeJob: kotlinx.coroutines.Job? = null
    @Volatile private var positionWatchJob: kotlinx.coroutines.Job? = null
    @Volatile private var pendingNextSong: com.music.spotui.data.entity.SongsModel? = null
    @Volatile private var pendingNextSongIdx: Int = 0

    /** Notified (on the main thread) when the active ExoPlayer instance changes after a
     *  crossfade, so the media session can re-bind to the promoted player. */
    @Volatile var onPlayerSwapped: ((ExoPlayer) -> Unit)? = null

    /** Give the player access to the shared queue/now-playing state so it can advance the
     *  app's notion of "current track" itself when a crossfade fires. Called once at startup. */
    fun bindState(state: CurrentSongState) { boundState = state }

    fun isCrossfadeActive(): Boolean = isCrossfading

    private fun sigmoid(t: Float): Float = 1.0f / (1.0f + exp(-CF_SIGMOID_K * (t - 0.5f)))

    private fun expInterpolate(start: Float, end: Float, t: Float): Float {
        if (start <= 0f || end <= 0f) return end
        return exp(ln(start) + (ln(end) - ln(start)) * t).toFloat()
    }

    /** Cancel an in-flight crossfade and tear down the secondary player, restoring the
     *  primary to full volume with its filter disabled. Safe to call when not crossfading. */
    private fun cancelCrossfade() {
        if (!isCrossfading && secondaryPlayer == null) return
        crossfadeJob?.cancel()
        crossfadeJob = null
        currentPlayerFilter?.enabled = false
        secondaryPlayerFilter?.enabled = false
        runCatching { secondaryPlayer?.release() }
        secondaryPlayer = null
        secondaryPlayerFilter = null
        player?.volume = 1f
        isCrossfading = false
        pendingNextSong = null
    }

    /** (Re)start the loop that watches playback position and fires a crossfade as the
     *  current track approaches its end. */
    private var posSaveTick = 0

    private fun startPositionWatch() {
        positionWatchJob?.cancel()
        positionWatchJob = scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(250)
                val ctx = appCtx ?: continue
                // Persist the position every ~3s so a relaunch resumes mid-track.
                if (++posSaveTick % 12 == 0 && !webPlaybackActive()) {
                    player?.let { p ->
                        val pos = withContext(Dispatchers.Main) {
                            if (p.isPlaying) p.currentPosition else -1L
                        }
                        if (pos > 0) com.music.spotui.data.preferences.saveLastPosition(ctx, pos)
                    }
                }
                if (isCrossfading) continue
                val crossfadeMs = com.music.spotui.data.preferences.getCrossfadeMs(ctx)
                if (crossfadeMs <= 0) continue
                val state = boundState ?: continue
                if (state.repeat.value) continue // repeat-one loops the same track
                val p = player ?: continue
                val playing = withContext(Dispatchers.Main) { p.isPlaying }
                if (!playing) continue
                val dur = withContext(Dispatchers.Main) { p.duration }
                val pos = withContext(Dispatchers.Main) { p.currentPosition }
                if (dur <= 0 || pos < 0) continue
                if (pos >= dur - crossfadeMs) {
                    triggerCrossfade(ctx, crossfadeMs)
                }
            }
        }
    }

    /** Begin blending the current track into the next queue item. */
    private fun triggerCrossfade(ctx: Context, configuredMs: Int) {
        if (isCrossfading) return
        val state = boundState ?: return
        val q = state.queue.value
        if (q.isEmpty()) return
        val cur = q.indexOfFirst { it.id == state.songId.value }
        if (cur < 0 || cur >= q.size - 1) return // last track ends normally
        val nextSong = q[cur + 1]
        isCrossfading = true
        scope.launch {
            try {
                val nextUrl = resolveStreamUrl(nextSong.url, ctx, forPlayback = true) ?: run {
                    isCrossfading = false; return@launch
                }
                // Effective duration: never longer than the real time left on the outgoing track.
                val remaining = withContext(Dispatchers.Main) {
                    val p = player ?: return@withContext configuredMs.toLong()
                    val d = p.duration; val ps = p.currentPosition
                    if (d > 0 && ps >= 0) (d - ps) else configuredMs.toLong()
                }
                val effectiveMs = minOf(configuredMs.toLong(), remaining).coerceAtLeast(1000L).toInt()
                val djMode = com.music.spotui.data.preferences.isCrossfadeDjMode(ctx)

                // Stash for finalizeCrossfade, which updates the UI when the new track
                // is the sole audio source (not at blend-start while the old track fades).
                pendingNextSong = nextSong
                pendingNextSongIdx = cur + 1
                // Pre-load metadata so buildMediaItem() tags the secondary player correctly.
                setNowPlayingMeta(nextSong.title, nextSong.singer, nextSong.coverUri)
                withContext(Dispatchers.Main) {
                    val (sp, sf) = createPlayerWithFilter(ctx, handleAudioFocus = false)
                    secondaryPlayer = sp
                    secondaryPlayerFilter = sf
                    sp.setMediaItem(buildMediaItem(nextUrl, streamMimeType(nextUrl)))
                    sp.prepare()
                    sp.volume = 0f
                    sp.playWhenReady = true
                }
                performCrossfade(effectiveMs, djMode)
            } catch (e: Exception) {
                Log.e(TAG, "crossfade failed", e)
                cancelCrossfade()
            }
        }
    }

    private suspend fun performCrossfade(effectiveMs: Int, djMode: Boolean) {
        val steps = 50
        val delayPerStep = (effectiveMs / steps).coerceAtLeast(20)
        if (djMode) {
            currentPlayerFilter?.apply {
                filterType = com.music.spotui.audio.BiquadFilter.FilterType.LOW_PASS
                cutoffFrequencyHz = CF_LPF_START_HZ; enabled = true
            }
            secondaryPlayerFilter?.apply {
                filterType = com.music.spotui.audio.BiquadFilter.FilterType.HIGH_PASS
                cutoffFrequencyHz = CF_HPF_START_HZ; enabled = true
            }
        }
        crossfadeJob?.cancel()
        val job = scope.launch {
            try {
                for (step in 0..steps) {
                    if (!isActive) break
                    val progress = step.toFloat() / steps
                    val angle = (progress * PI / 2).toFloat()
                    withContext(Dispatchers.Main) {
                        player?.volume = cos(angle)
                        secondaryPlayer?.volume = sin(angle)
                        if (djMode) {
                            val fp = sigmoid(progress)
                            currentPlayerFilter?.cutoffFrequencyHz = expInterpolate(CF_LPF_START_HZ, CF_LPF_END_HZ, fp)
                            secondaryPlayerFilter?.cutoffFrequencyHz = expInterpolate(CF_HPF_START_HZ, CF_HPF_END_HZ, fp)
                        }
                    }
                    kotlinx.coroutines.delay(delayPerStep.toLong())
                }
                finalizeCrossfade()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
        }
        crossfadeJob = job
        job.join()
    }

    private suspend fun finalizeCrossfade() {
        withContext(Dispatchers.Main) {
            val incoming = secondaryPlayer ?: run { isCrossfading = false; return@withContext }
            val old = player
            // Promote the incoming (secondary) player to primary.
            currentPlayerFilter?.enabled = false
            secondaryPlayerFilter?.enabled = false
            player = incoming
            currentPlayerFilter = secondaryPlayerFilter
            secondaryPlayer = null
            secondaryPlayerFilter = null
            incoming.volume = 1f
            // The promoted player now owns audio focus / becoming-noisy handling.
            incoming.setAudioAttributes(buildAudioAttributes(), /* handleAudioFocus = */ true)
            incoming.setHandleAudioBecomingNoisy(true)
            runCatching { old?.stop(); old?.release() }
            isCrossfading = false
            // Now that the new track is the sole audio source, switch the in-app UI
            // (cover art, canvas, title) and update the playback identity for error recovery.
            pendingNextSong?.let { next ->
                currentRequest = next.url
                boundState?.updateSongState(
                    next.coverUri, next.title, next.singer, true,
                    next.id, pendingNextSongIdx, next.album,
                )
                pendingNextSong = null
            }
            // Re-bind the media session to the new player.
            onPlayerSwapped?.invoke(incoming)
        }
        // Watch the newly-promoted track for its own end.
        startPositionWatch()
    }

    // ── Sleep timer ──
    // Pauses playback after a delay. A new call replaces any pending timer;
    // passing 0 (or calling cancelSleepTimer) clears it.
    @Volatile private var sleepJob: kotlinx.coroutines.Job? = null
    @Volatile var sleepTimerEndAt: Long = 0L
        private set

    fun setSleepTimer(durationMillis: Long) {
        sleepJob?.cancel()
        if (durationMillis <= 0L) {
            sleepTimerEndAt = 0L
            return
        }
        sleepTimerEndAt = System.currentTimeMillis() + durationMillis
        sleepJob = scope.launch {
            kotlinx.coroutines.delay(durationMillis)
            withContext(Dispatchers.Main) { pause() }
            sleepTimerEndAt = 0L
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        sleepTimerEndAt = 0L
    }
}
