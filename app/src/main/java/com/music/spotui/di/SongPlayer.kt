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
import com.music.spotui.di.TrackMatcher.CandidateScore
import com.music.spotui.di.TrackMatcher.featSearchPattern
import com.music.spotui.di.TrackMatcher.isAcceptableMatch
import com.music.spotui.di.TrackMatcher.ytmusicTransferScore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    // Soulseek P2P as the last lossless fallback (see SoulseekSource).
    @Volatile var soulseekEnabled = true

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
    // Next queue track already appended to the player (its play query), so ExoPlayer
    // moves on by itself without passing through ENDED/IDLE (#67: Android Auto left
    // Now Playing on that gap). Null when nothing is queued ahead.
    @Volatile private var preloadedNext: String? = null
    @Volatile private var preloading = false

    /** Fired (main thread) when the player moved on to the preloaded track [query]. */
    @Volatile var onPreloadedTrackStarted: ((query: String) -> Unit)? = null

    // Request and position of the last re-resolve after a mid-play error.
    @Volatile private var retriedRequest: String? = null
    @Volatile private var retriedAtMs: Long = 0L

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

    /** What the queue registered for [song], so lossless matching needs no Spotify lookup. */
    private fun losslessTrackInfo(song: String): com.music.spotui.lossless.LosslessSource.TrackInfo {
        val meta = metadataRegistry[song] ?: return com.music.spotui.lossless.LosslessSource.TrackInfo()
        return com.music.spotui.lossless.LosslessSource.TrackInfo(
            title = meta.title,
            artist = meta.artist.substringBefore(",").trim(),
            durationSec = (durationRegistry[song] ?: 0) / 1000,
            isrc = meta.isrc,
        )
    }

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
        retriedRequest = null
        preloadedNext = null
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
                if (wantsQuickStart(song, appContext)) {
                    quickStartThenUpgrade(song, appContext)
                    return@launch
                }
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
                startStream(song, streamUrl, appContext)
            } catch (e: Exception) {
                Log.e(TAG, "playSong failed for query: $song", e)
            }
        }
    }

    private suspend fun startStream(song: String, streamUrl: String, appContext: Context, mimeType: String? = streamMimeType(streamUrl)) {
        withContext(Dispatchers.Main) {
            if (currentRequest != song) return@withContext
            ensurePlayer(appContext)
            player!!.setMediaItem(buildMediaItem(streamUrl, mimeType))
            player!!.prepare()
            // Restored session: continue from where the last run stopped.
            if (song == restoreQuery && restorePositionMs > 0) {
                player!!.seekTo(restorePositionMs)
            }
            restoreQuery = null
            player!!.playWhenReady = true
        }
        startPositionWatch()
        // Quick-start songs trigger it after their own upgrade instead.
        if (!wantsQuickStart(song, appContext)) soulseekLookahead(appContext)
    }

    private fun streamFailed(song: String, appContext: Context) {
        if (currentRequest != song) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                appContext, "Couldn't find a playable stream for this track", android.widget.Toast.LENGTH_SHORT,
            ).show()
            onStreamFailed?.invoke(song)
        }
    }

    // Lossless lookups take ~20 s against ~4 s for YouTube, so a fresh lossless track
    // starts on YouTube and switches over when the FLAC is ready.
    private fun wantsQuickStart(song: String, ctx: Context): Boolean =
        losslessStreaming && youtubeEnabled &&
            com.music.spotui.data.preferences.currentStreamingQuality(ctx).lossless &&
            !song.startsWith("content://") && !song.startsWith("file://") &&
            !streamCache.containsKey(song) &&
            com.music.spotui.data.preferences.downloadedPathForQuery(ctx, song) == null &&
            alternativeStreamForPlayback(song, ctx) == null &&
            (trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song)) != null

    private fun soulseekOn(ctx: Context): Boolean =
        soulseekEnabled && com.music.spotui.data.preferences.isSoulseekEnabled(ctx)

    private fun soulseekKey(song: String): String =
        trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song) ?: song.hashCode().toUInt().toString()

    // One look-ahead at a time; the client moves one file at a time anyway.
    private val lookaheadLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Fetch the next queued track's FLAC from Soulseek while this one plays, so it
     * starts lossless instead of upgrading a minute in. Only the next track: a
     * skipped song's file is wasted data.
     */
    private fun soulseekLookahead(ctx: Context) {
        if (!soulseekOn(ctx) || !losslessStreaming || !youtubeEnabled) return
        if (!com.music.spotui.data.preferences.currentStreamingQuality(ctx).lossless) return
        val state = boundState ?: return
        if (lookaheadLock.isLocked) return
        scope.launch {
            lookaheadLock.withLock {
                val next = withContext(Dispatchers.Main) {
                    val q = state.queue.value
                    val cur = q.indexOfFirst { it.id == state.songId.value }
                    q.getOrNull(cur + 1)?.takeIf { cur >= 0 }
                } ?: return@withLock
                val query = next.url
                if (com.music.spotui.data.preferences.downloadedPathForQuery(ctx, query) != null) return@withLock
                val key = soulseekKey(query)
                if (com.music.spotui.soulseek.SoulseekSource.cached(ctx, key) != null) return@withLock
                val info = losslessTrackInfo(query)
                if (info.title.isBlank() || info.artist.isBlank()) return@withLock
                Log.d(TAG, "look-ahead: fetching ${info.title} from Soulseek")
                runCatching { com.music.spotui.soulseek.SoulseekSource.flac(ctx, key, info.title, info.artist, info.durationSec) }
            }
        }
    }

    private fun isLosslessSource(song: String): Boolean =
        sourceCache[song]?.startsWith("Lossless") == true || qualityCache[song] == "FLAC"

    private suspend fun quickStartThenUpgrade(song: String, ctx: Context) = kotlinx.coroutines.coroutineScope {
        val quality = com.music.spotui.data.preferences.currentStreamingQuality(ctx)
        val best = async { resolveStreamUrl(song, ctx, allowYouTube = false) }
        val quick = async { resolveYtPlayback(song, quality.audioQuality, ctx) }
        val bestFirst = kotlinx.coroutines.selects.select<Boolean> {
            best.onAwait { true }
            quick.onAwait { false }
        }
        // Lossless was ready first: play it straight away.
        val early = if (bestFirst) best.await()?.takeIf { isLosslessSource(song) } else null
        if (early != null) {
            quick.cancel()
            currentSource = sourceCache[song] ?: currentSource
            currentQuality = qualityCache[song].orEmpty()
            startStream(song, early, ctx, streamMimeType(early, sourceCache[song].orEmpty()))
            return@coroutineScope
        }
        val fast = quick.await()
        if (currentRequest != song) return@coroutineScope
        if (fast != null) {
            currentSource = "YouTube"
            currentQuality = ytQualityLabel(fast)
            startStream(song, fast.streamUrl, ctx)
        }
        val better = best.await()
        // This song's lookup is done; use the time left for the next one.
        soulseekLookahead(ctx)
        if (currentRequest != song) return@coroutineScope
        when {
            better != null && isLosslessSource(song) && fast != null -> swapToLossless(song, better)
            better != null && fast == null -> startStream(song, better, ctx, streamMimeType(better, sourceCache[song].orEmpty()))
            fast == null -> streamFailed(song, ctx)
            else -> {
                // No lossless copy: remember the YouTube stream so a replay starts at once.
                streamCache[song] = fast.streamUrl
                sourceCache[song] = "YouTube"
                qualityCache[song] = ytQualityLabel(fast)
            }
        }
    }

    /**
     * Replace the quick-start stream with the lossless one without a gap: the FLAC
     * loads muted on a second player a few seconds ahead, starts when playback gets
     * there, and a short crossfade (the crossfade engine) hides any small offset
     * between the two masters. Falls back to a plain switch if it can't line up.
     */
    private suspend fun swapToLossless(song: String, url: String) {
        val ctx = appCtx ?: return
        val mime = streamMimeType(url, sourceCache[song].orEmpty())
        val primary = player ?: return
        val playing = withContext(Dispatchers.Main) { primary.playWhenReady && primary.isPlaying }
        if (currentRequest != song || isCrossfading) return
        if (!playing || !seamlessSwap(song, url, mime, primary, ctx)) hardSwap(song, url, mime)
    }

    private suspend fun seamlessSwap(
        song: String, url: String, mime: String?, primary: ExoPlayer, ctx: Context,
    ): Boolean {
        val leadMs = 4_000L
        val (sp, sf) = withContext(Dispatchers.Main) { createPlayerWithFilter(ctx, handleAudioFocus = false) }
        val startAt = withContext(Dispatchers.Main) { primary.currentPosition + leadMs }
        isCrossfading = true // user actions now cancel this via cancelCrossfade()
        secondaryPlayer = sp
        secondaryPlayerFilter = sf
        pendingNextSong = null // same song: no queue/UI change when it completes
        fun stillOn() = currentRequest == song && secondaryPlayer === sp && player === primary
        withContext(Dispatchers.Main) {
            sp.setMediaItem(buildMediaItem(url, mime), startAt)
            sp.volume = 0f
            sp.playWhenReady = false
            sp.prepare()
        }
        // Wait until the FLAC is buffered at startAt, while the stream plays toward it.
        val ready = kotlinx.coroutines.withTimeoutOrNull(leadMs - 500) {
            while (withContext(Dispatchers.Main) { sp.playbackState != Player.STATE_READY }) {
                if (!stillOn()) return@withTimeoutOrNull false
                delay(50)
            }
            true
        } == true
        if (!ready || !stillOn()) {
            if (secondaryPlayer === sp) cancelCrossfade() // releases sp, restores state
            return false
        }
        // Start it the moment playback reaches startAt (a pause meanwhile: plain switch).
        while (stillOn()) {
            val (pos, playingNow) = withContext(Dispatchers.Main) { primary.currentPosition to primary.isPlaying }
            if (!playingNow) {
                cancelCrossfade()
                return false
            }
            if (pos >= startAt - 15) break
            delay(10)
        }
        if (!stillOn()) return false
        withContext(Dispatchers.Main) { sp.playWhenReady = true }
        preloadedNext = null // the promoted player starts without anything queued ahead
        performCrossfade(400, djMode = false)
        currentSource = sourceCache[song] ?: currentSource
        currentQuality = qualityCache[song].orEmpty()
        Log.d(TAG, "switched seamlessly to $currentSource for: $song")
        return true
    }

    /** Plain switch at the same position (used when paused or if lining up failed). */
    private suspend fun hardSwap(song: String, url: String, mime: String?) = withContext(Dispatchers.Main) {
        val p = player ?: return@withContext
        if (currentRequest != song || isCrossfading) return@withContext
        val pos = p.currentPosition
        val playing = p.playWhenReady
        preloadedNext = null // setMediaItem replaces anything queued ahead
        p.setMediaItem(buildMediaItem(url, mime), pos)
        p.prepare()
        p.playWhenReady = playing
        currentSource = sourceCache[song] ?: currentSource
        currentQuality = qualityCache[song].orEmpty()
        Log.d(TAG, "switched to $currentSource at ${pos / 1000}s for: $song")
    }

    // Build a MediaItem carrying the current track's metadata so the system media
    // notification (MediaSession) shows the right title / artist / artwork.
    private fun buildMediaItem(
        streamUrl: String,
        mimeType: String? = null,
        query: String = currentRequest,
        title: String = metaTitle,
        artist: String = metaArtist,
        cover: String = metaCover,
    ): MediaItem {
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .apply { if (cover.isNotBlank()) setArtworkUri(android.net.Uri.parse(cover)) }
            .build()
        return MediaItem.Builder()
            // The play query, so a gapless transition tells us which track is now playing.
            .setMediaId(query)
            .setUri(streamUrl)
            // Hint the container so ExoPlayer picks the right source/extractor even
            // when the URL has no extension: TIDAL lossless is a DASH .mpd manifest,
            // and single-file lossless is FLAC.
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .setMediaMetadata(metadata)
            .build()
    }

    /** MIME hint for a resolved stream: DASH manifest, single-file FLAC, or none. */
    private fun streamMimeType(streamUrl: String, source: String = currentSource): String? {
        val bare = streamUrl.substringBefore('?').lowercase()
        return when {
            streamUrl.startsWith("deezer://") ->
                if (streamUrl.contains("fmt=flac")) androidx.media3.common.MimeTypes.AUDIO_FLAC
                else androidx.media3.common.MimeTypes.AUDIO_MPEG
            streamUrl.startsWith("data:application/dash+xml") ||
                bare.endsWith(".mpd") || streamUrl.contains("manifest.tidal.com") || streamUrl.contains("/manifests/") ->
                androidx.media3.common.MimeTypes.APPLICATION_MPD
            bare.endsWith(".flac") || source.startsWith("Lossless") ->
                androidx.media3.common.MimeTypes.AUDIO_FLAC
            else -> null
        }
    }

    /** Warm the cache for an upcoming track (e.g. the next/previous queue item). */
    fun prefetch(song: String, context: Context) {
        if (song.isBlank() || streamCache.containsKey(song)) return
        val appContext = context.applicationContext
        // No point resolving YouTube streams while Spotify web is the engine — it's
        // wasted network/CPU that competes with the streaming audio (caused stutter).
        if (webPlaybackActive()) return
        // Lossless FLAC URLs from the backends are short-lived / single-use. Caching
        // one now + preloading a partial intro makes playback stop after ~30s when the
        // continuation hits a stale URL, so resolve those fresh at play time instead.
        if (losslessStreaming && com.music.spotui.data.preferences.currentStreamingQuality(appContext).lossless) return
        // Deezer resolves a fresh CDN url per play; a YouTube prefetch cached under
        // the same key would shadow it, so skip prefetch entirely when Deezer is on.
        if (deezerEnabled && com.music.spotui.data.preferences.isDeezerEnabled(appContext)) return
        scope.launch {
            val url = runCatching { resolveStreamUrl(song, appContext) }.getOrNull()
            if (url != null) cacheIntro(url, appContext)
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

    internal fun mediaCache(context: Context): androidx.media3.datasource.cache.SimpleCache =
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
    private suspend fun resolveStreamUrl(
        song: String,
        appContext: Context,
        forPlayback: Boolean = false,
        allowYouTube: Boolean = true,
    ): String? {
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
                    if (forPlayback) currentQuality = ytQualityLabel(playback)
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
        // Quality for the current network (Wi-Fi vs cellular), from Settings.
        val quality = com.music.spotui.data.preferences.currentStreamingQuality(appContext)

        // A Soulseek FLAC fetched earlier (look-ahead or a past play) starts right away.
        if (soulseekOn(appContext) && quality.lossless) {
            com.music.spotui.soulseek.SoulseekSource.cached(appContext, soulseekKey(song))?.let { file ->
                if (forPlayback) { currentSource = "Lossless • Soulseek"; currentQuality = "FLAC" }
                sourceCache[song] = "Lossless • Soulseek"
                qualityCache[song] = "FLAC"
                return android.net.Uri.fromFile(file).toString()
            }
        }

        // Deezer — preferred, but when Lossless is selected a FREE Deezer account only
        // yields MP3. In that case we HOLD the MP3 as a fallback and try the real FLAC
        // sources first, so lossless isn't silently pre-empted by Deezer MP3.
        var heldDeezer: com.music.spotui.deezer.DeezerSource.Result.Success? = null
        if (deezerEnabled && com.music.spotui.data.preferences.isDeezerEnabled(appContext)) {
            val spotifyId = trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song)
            val r = kotlinx.coroutines.withTimeoutOrNull(12_000) {
                com.music.spotui.deezer.DeezerSource.resolve(
                    appContext,
                    spotifyId = spotifyId,
                    isrc = null,
                    searchQuery = searchTextForPlayback(song),
                    expectedDurationSec = (durationRegistry[song] ?: 0) / 1000,
                )
            }
            if (r is com.music.spotui.deezer.DeezerSource.Result.Success) {
                // At High, YouTube Opus (~160 kbps) beats a free account's MP3 128 (#66).
                val belowYouTube = r.lowBitrate && youtubeEnabled &&
                    quality.audioQuality == com.metrolist.music.constants.AudioQuality.HIGH
                if (r.mimeFlac || (!quality.lossless && !belowYouTube)) {
                    Log.d(TAG, "deezer ${r.qualityLabel} for: $song")
                    if (forPlayback) { currentSource = "Deezer"; currentQuality = r.qualityLabel }
                    streamCache[song] = r.uri
                    sourceCache[song] = "Deezer"
                    qualityCache[song] = r.qualityLabel
                    return r.uri
                }
                heldDeezer = r // MP3 only: try FLAC (Lossless) or YouTube (MP3 128 at High) first.
                Log.d(TAG, "deezer only ${r.qualityLabel}; trying better sources first for: $song")
            } else {
                Log.d(TAG, "deezer miss ($r), continuing for: $song")
            }
        }

        // Lossless FLAC: SpotiFLAC gated (if verified) + Tidal/community, ISRC-matched.
        if (losslessStreaming && quality.lossless) {
            (trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song))?.let { spotifyId ->
                val r = kotlinx.coroutines.withTimeoutOrNull(15_000) {
                    com.music.spotui.lossless.LosslessSource.resolve(appContext, spotifyId, losslessTrackInfo(song), losslessHiRes)
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
                    return r.track.url
                } else {
                    Log.d(TAG, "lossless miss ($r) for: $song")
                }
            }
        }

        // Soulseek (P2P) when the lossless servers have nothing. A peer can take a
        // minute, so only for the background upgrade, while another stream plays.
        if (soulseekOn(appContext) && !allowYouTube && quality.lossless) {
            val info = losslessTrackInfo(song)
            val key = soulseekKey(song)
            if (info.title.isNotBlank() && info.artist.isNotBlank()) {
                val file = runCatching {
                    com.music.spotui.soulseek.SoulseekSource.flac(appContext, key, info.title, info.artist, info.durationSec)
                }.getOrNull()
                if (file != null) {
                    val url = android.net.Uri.fromFile(file).toString()
                    streamCache[song] = url
                    sourceCache[song] = "Lossless • Soulseek"
                    qualityCache[song] = "FLAC"
                    return url
                }
            }
        }

        fun useHeldDeezer(r: com.music.spotui.deezer.DeezerSource.Result.Success): String {
            Log.d(TAG, "using Deezer MP3 fallback for: $song")
            if (forPlayback) { currentSource = "Deezer"; currentQuality = r.qualityLabel }
            streamCache[song] = r.uri
            sourceCache[song] = "Deezer"
            qualityCache[song] = r.qualityLabel
            return r.uri
        }
        // Deezer MP3 320 (held above) before YouTube; MP3 128 only if YouTube fails.
        heldDeezer?.takeIf { !it.lowBitrate }?.let { return useHeldDeezer(it) }
        if (!youtubeEnabled || !allowYouTube) {
            if (!youtubeEnabled) Log.w(TAG, "YouTube fallback disabled — no stream for: $song")
            return heldDeezer?.let(::useHeldDeezer)
        }
        if (forPlayback) {
            currentSource = "YouTube"
            // Clear the previous track's quality so a failed resolve can't leave
            // a stale "FLAC 24-bit" badge on a YouTube stream.
            currentQuality = ""
        }
        val playback = resolveYtPlayback(song, quality.audioQuality, appContext)
            ?: return heldDeezer?.let(::useHeldDeezer)
        val ytQuality = ytQualityLabel(playback)
        if (forPlayback) currentQuality = ytQuality
        streamCache[song] = playback.streamUrl
        sourceCache[song] = "YouTube"
        qualityCache[song] = ytQuality
        return playback.streamUrl
    }

    // e.g. "OPUS 141 kbps" from the chosen adaptive format.
    private fun ytQualityLabel(playback: YTPlayerUtils.PlaybackData): String {
        val codec = playback.format.mimeType
            .substringAfter("codecs=\"", "").substringBefore('"').substringBefore('.')
            .uppercase()
        return listOf(codec, "${playback.format.bitrate / 1000} kbps")
            .filter { it.isNotBlank() }.joinToString(" ")
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

    /** Fired (main thread) when the current track plays to its end. */
    @Volatile var onTrackEnded: (() -> Unit)? = null

    /** Fired (main thread) when a stream can't be resolved for [song], so the caller
     *  can skip to the next track instead of leaving playback silent. */
    @Volatile var onStreamFailed: ((String) -> Unit)? = null

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
    internal suspend fun resolveYtPlayback(
        query: String,
        audioQuality: com.metrolist.music.constants.AudioQuality,
        appContext: Context,
    ): YTPlayerUtils.PlaybackData? =
        // The PoToken/solver WebViews wait on JS callbacks with no deadline; a hung
        // WebView left playback at 0:00 forever (#95, #79) instead of failing over.
        kotlinx.coroutines.withTimeoutOrNull(45_000L) {
            resolveYtPlaybackUnbounded(query, audioQuality, appContext)
        } ?: null.also { Log.w(TAG, "YouTube resolve failed or timed out for: ${searchTextForPlayback(query)}") }

    private suspend fun resolveYtPlaybackUnbounded(
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
                // One hung candidate must not use up the whole budget.
                (kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    YTPlayerUtils.playerResponseForPlayback(
                        videoId = videoId,
                        audioQuality = audioQuality,
                        connectivityManager = connectivityManager,
                    )
                } ?: Result.failure(java.io.IOException("timed out"))).fold(
                    onSuccess = { return it },
                    onFailure = { Log.w(TAG, "stream failed for $videoId (${it.message}) — trying next candidate for: ${searchTextForPlayback(query)}") },
                )
            }
            return null
        }
        tryIds(resolveVideoCandidates(query).take(3))?.let { return it }
        if (!com.music.spotui.data.preferences.isVideoFallbackEnabled(appContext)) {
            Log.w(TAG, "song candidates exhausted and video fallback disabled for: ${searchTextForPlayback(query)}")
            return null
        }
        // Song results exhausted (e.g. every official upload is age-restricted and
        // we're not signed in to YouTube). Regular video uploads — lyric videos,
        // reuploads — usually aren't age-gated: last-resort pass over those.
        Log.w(TAG, "song candidates exhausted, trying video search for: ${searchTextForPlayback(query)}")
        tryIds(resolveVideoCandidates(query, YouTube.SearchFilter.FILTER_VIDEO).take(3))?.let { return it }
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
        // Track end is detected here, not in the UI: Compose stops when the screen is
        // off or the app is in the background, so the queue used to stall there.
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val query = mediaItem?.mediaId ?: return
            if (query != preloadedNext) return
            // Gapless move onto the preloaded track: it is now the current request.
            preloadedNext = null
            currentRequest = query
            retriedRequest = null
            val p = player ?: return
            if (p.currentMediaItemIndex > 0) p.removeMediaItems(0, p.currentMediaItemIndex)
            appCtx?.let { ctx ->
                scope.launch { resolveStreamUrl(query, ctx, forPlayback = true) } // badge
                soulseekLookahead(ctx)
            }
            onPreloadedTrackStarted?.invoke(query)
        }

        // Play/pause from the notification, headset or car goes straight to the player;
        // mirror it so the in-app button doesn't keep showing the old state.
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!isCrossfading) boundState?.updatePlayingState(playWhenReady)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Mid-crossfade the outgoing player may end first; the blend advances itself.
            // An emptied playlist also reports ENDED (playSong clears it on every tap);
            // treating that as a finished song skipped the track the user just picked.
            if (playbackState == Player.STATE_ENDED && !isCrossfading &&
                (player?.mediaItemCount ?: 0) > 0
            ) onTrackEnded?.invoke()
        }

        override fun onPlayerError(error: PlaybackException) {
            // A queued-ahead track can fail after the player moved onto it.
            val song = player?.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
                ?: currentRequest.takeIf { it.isNotBlank() } ?: return
            preloadedNext = null
            Log.w(TAG, "mid-play error for $song: ${error.errorCodeName} (code ${error.errorCode})")
            // Drop the cached URL — it may have expired (Deezer CDN, YouTube signing).
            streamCache.remove(song)
            sourceCache.remove(song)
            val ctx = appCtx ?: run { onStreamFailed?.invoke(song); return }
            // Re-resolve again only if playback got further since the last one: YouTube
            // can 403 a stream after ~1 min (#76, #48), and each fresh URL plays on. A URL
            // that fails at the same spot again is dead, so skip instead of looping.
            val pos = player?.currentPosition ?: 0L
            val madeProgress = retriedRequest != song || pos > retriedAtMs + 10_000L
            val isIoError = error.errorCode in 2000..2999 && madeProgress
            retriedRequest = song
            retriedAtMs = pos
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
                managePreload(ctx, crossfadeMs)
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

    /**
     * Queue the next track inside ExoPlayer ~30 s before the current one ends, so it
     * starts gaplessly and the session never reports "stopped" between tracks.
     * Crossfade and repeat keep their own paths; a stale preload is removed.
     */
    private suspend fun managePreload(ctx: Context, crossfadeMs: Int) {
        val state = boundState ?: return
        val p = player ?: return
        val next = withContext(Dispatchers.Main) {
            val q = state.queue.value
            val cur = q.indexOfFirst { it.id == state.songId.value }
            q.getOrNull(cur + 1)?.takeIf { cur >= 0 }
        }
        val wanted = next?.url?.takeIf { crossfadeMs <= 0 && !state.repeat.value && !webPlaybackActive() }
        val queued = preloadedNext
        if (queued != null && queued != wanted) {
            // Repeat/crossfade turned on or the queue changed: drop the queued track.
            withContext(Dispatchers.Main) {
                if (p.mediaItemCount > 1) p.removeMediaItems(1, p.mediaItemCount)
            }
            preloadedNext = null
        }
        if (wanted == null || preloadedNext != null || preloading) return
        val (pos, dur, ready) = withContext(Dispatchers.Main) {
            Triple(p.currentPosition, p.duration, p.playbackState == Player.STATE_READY && p.mediaItemCount == 1)
        }
        if (!ready || dur <= 0 || dur - pos > 30_000L) return
        val song = next
        preloading = true
        try {
            val url = runCatching { resolveStreamUrl(song.url, ctx) }.getOrNull() ?: return
            withContext(Dispatchers.Main) {
                // Only if nothing changed while resolving.
                if (player !== p || p.mediaItemCount != 1 || state.queue.value.none { it.url == song.url }) return@withContext
                val mime = streamMimeType(url, sourceCache[song.url].orEmpty())
                p.addMediaItem(buildMediaItem(url, mime, song.url, song.title, song.singer, song.coverUri))
                preloadedNext = song.url
                Log.d(TAG, "queued next track ahead: ${song.title}")
            }
        } finally {
            preloading = false
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
            // Error recovery and end-of-track detection follow the promoted player.
            incoming.addListener(midPlayErrorListener)
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
