package com.music.spotui.spotify

import android.content.Context
import android.util.Log
import com.music.spotui.data.api.SpotifySession
import com.music.spotui.data.api.SpotifyTokenProvider
import com.music.spotui.data.preferences.isSpotifyHistorySyncEnabled
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Reports playback events to Spotify's listening history so tracks played in
 * Spotui appear in the user's Recently Played on Spotify.
 *
 * Registers a virtual Web Player device named "stupid ass client" and speaks
 * the same protocol as the Spotify Web Player (Dealer WebSocket + track-playback
 * + melody batch API). Ported from MetroFuse's SpotifyCanvasClient/
 * SpotifyListeningHistoryManager.
 */
object SpotifyHistorySync {

    private const val TAG = "SpotifyHistorySync"
    private const val DEVICE_NAME = "stupid ass client"

    // ── Spotify protocol constants ──
    private const val APRESOLVE_URL = "https://apresolve.spotify.com/?type=dealer-g2&type=spclient"
    private const val HISTORY_BATCH_URL = "https://gew1-spclient.spotify.com/melody/v1/msg/batch"
    private const val CLIENT_VERSION = "0.0.0"
    private const val PLATFORM = "web_player windows 10;chrome 148.0.0.0;desktop"
    private const val SDK_ID = "harmony:4.72.0"
    private const val DEVICE_MODEL = "harmony-4.72.0-web-player"
    private const val BITRATE = 128_000
    private const val DEVICE_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
    private const val SESSION_TTL_MS = 45 * 60 * 1000L
    private const val DEVICE_TTL_MS = 45 * 60 * 1000L
    private const val DEALER_TIMEOUT_MS = 10_000L
    private const val MIN_DURATION_SEC = 30

    private const val WEB_REFERER = "https://open.spotify.com/"
    private const val WEB_ORIGIN = "https://open.spotify.com"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private val JSON_MT = "application/json".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val dealerClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    // ── State ──
    @Volatile private var backedOffUntilMs = 0L
    private val sessions = ConcurrentHashMap<String, Session>()
    private val deviceMutex = Mutex()
    @Volatile private var device: Device? = null
    private val rng = SecureRandom()
    @Volatile private var activeTrackUri: String? = null
    @Volatile private var activeStartedAtMs = 0L
    @Volatile private var activeDurationMs = 0L
    @Volatile private var activeSessionStarted = false
    @Volatile private var startJob: Job? = null
    @Volatile private var reportJob: Job? = null
    private var reportRemainingMs = 0L
    private var reportTimerStartedAt = 0L
    @Volatile private var currentStateMachineId = newStateMachineId()
    @Volatile private var currentStateId = newStateId()
    private val reportedKeys = LinkedHashSet<String>()

    // ── Data classes ──
    private data class Session(
        val trackUri: String,
        @Volatile var stateMachineId: String,
        @Volatile var stateId: String,
        @Volatile var statePaused: Boolean = false,
        @Volatile var playbackId: String,
        val sessionId: String,
        val correlationId: String,
        val startedAtMs: Long,
        @Volatile var durationMs: Long,
        val createdAtMs: Long = System.currentTimeMillis(),
        val startReported: AtomicBoolean = AtomicBoolean(false),
        val thresholdReported: AtomicBoolean = AtomicBoolean(false),
        val finalized: AtomicBoolean = AtomicBoolean(false),
    )

    private data class Endpoints(val dealerUrl: String, val webgateUrl: String)

    private data class DealerConnection(
        val id: String,
        val webSocket: WebSocket,
        val commandQueue: CommandQueue,
    )

    private class CommandQueue {
        private val pending = ArrayDeque<JsonObject>()
        private var waiting: CompletableDeferred<JsonObject>? = null
        @Synchronized fun next(): CompletableDeferred<JsonObject> {
            if (pending.isNotEmpty()) return CompletableDeferred(pending.removeFirst())
            return CompletableDeferred<JsonObject>().also { waiting = it }
        }
        @Synchronized fun offer(cmd: JsonObject) {
            val w = waiting
            if (w != null) { waiting = null; if (w.complete(cmd)) return }
            pending.addLast(cmd)
        }
    }

    private data class PlaybackState(
        val stateMachineId: String,
        val stateId: String,
        val paused: Boolean,
        val durationMs: Long?,
        val playbackId: String?,
    )

    private class Device(
        val cookieHash: Int,
        val deviceId: String,
        val webgateUrl: String,
        val connectionId: String,
        val webSocket: WebSocket,
        val commandQueue: CommandQueue,
        initialSeqNum: Int,
        val createdAtMs: Long = System.currentTimeMillis(),
    ) {
        private val seqNum = AtomicInteger(initialSeqNum)
        @Volatile var closed = false; private set
        fun nextSeq(): Int = seqNum.incrementAndGet()
        fun close() { closed = true; runCatching { webSocket.close(1000, "refresh") } }
    }

    private val CONNECTION_ID_REGEX = Regex("""hm://pusher/(?:[^/]+/)?connections/([^/?#]+)""")

    // ── Public API: called from SongPlayer / CurrentSongState ──

    /**
     * Call when a new track starts playing.
     * [spotifyTrackId] is the 22-char Spotify track id (e.g. "3n3Ppam7vgaVa1iaRUc9Lp").
     * [durationMs] is the track length in ms.
     */
    fun onTrackStart(context: Context, spotifyTrackId: String, durationMs: Long) {
        if (spotifyTrackId.isBlank()) return
        if (!isSpotifyHistorySyncEnabled(context)) return
        if (isBackedOff()) return
        val spDc = SpotifySession.spDc(context)
        if (spDc.isBlank()) return

        val trackUri = "spotify:track:$spotifyTrackId"
        val durationSec = (durationMs / 1000).toInt()

        // Finalize previous track
        if (activeTrackUri != null && activeTrackUri != trackUri) {
            finalizeCurrentSession(spDc)
        }

        activeTrackUri = trackUri
        activeStartedAtMs = System.currentTimeMillis()
        activeDurationMs = durationMs
        activeSessionStarted = false
        currentStateId = newStateId()

        startReportTimer(spDc, trackUri, durationMs, durationSec)
        startSession(context, spDc, trackUri, durationMs)
    }

    fun onPause(context: Context) {
        if (!isSpotifyHistorySyncEnabled(context)) return
        pauseReportTimer()
        reportPlaybackControl(context, paused = true)
    }

    fun onResume(context: Context) {
        if (!isSpotifyHistorySyncEnabled(context)) return
        resumeReportTimer(context)
        reportPlaybackControl(context, paused = false)
    }

    fun onStop(context: Context) {
        if (!isSpotifyHistorySyncEnabled(context)) return
        val spDc = SpotifySession.spDc(context)
        finalizeCurrentSession(spDc)
        stopReportTimer()
        startJob?.cancel(); startJob = null
        activeTrackUri = null
        activeSessionStarted = false
    }

    fun onSeek(context: Context, positionMs: Long, isPlaying: Boolean) {
        if (!isSpotifyHistorySyncEnabled(context)) return
        val trackUri = activeTrackUri ?: return
        val session = sessions[sessionKey(trackUri, activeStartedAtMs)] ?: return
        if (!session.startReported.get() || session.finalized.get()) return
        val spDc = SpotifySession.spDc(context)
        if (spDc.isBlank()) return

        scope.launch {
            val cookie = "sp_dc=$spDc"
            val token = ensureToken(context) ?: return@launch
            val dev = ensureDevice(cookie, token) ?: return@launch
            val clamped = if (session.durationMs > 0) positionMs.coerceIn(0, session.durationMs) else positionMs.coerceAtLeast(0)
            reportState(
                cookie, session, "seek", "seek", clamped, clamped,
                session.durationMs, false, if (isPlaying) false else true, dev,
            )
        }
    }

    // ── Internal ──

    private fun isBackedOff() = System.currentTimeMillis() < backedOffUntilMs

    private fun backOff(reason: String, ms: Long = 120_000L) {
        val until = System.currentTimeMillis() + ms.coerceAtLeast(15_000L)
        if (until > backedOffUntilMs) backedOffUntilMs = until
        Log.w(TAG, "Backed off: $reason")
    }

    private suspend fun ensureToken(context: Context): String? {
        if (!SpotifyTokenProvider.ensureToken(context)) return null
        return com.metrolist.spotify.Spotify.accessToken
    }

    private fun startReportTimer(spDc: String, trackUri: String, durationMs: Long, durationSec: Int) {
        reportJob?.cancel()
        if (durationSec <= MIN_DURATION_SEC) return
        val threshold = (durationMs * 0.5f).toLong()
        reportRemainingMs = min(threshold, 50_000L)
        if (reportRemainingMs <= 0L) return
        reportTimerStartedAt = System.currentTimeMillis()
        reportJob = scope.launch {
            delay(reportRemainingMs)
            reportThreshold(spDc, trackUri, durationMs)
            reportJob = null
        }
    }

    private fun pauseReportTimer() {
        reportJob?.cancel(); reportJob = null
        if (reportTimerStartedAt != 0L) {
            reportRemainingMs -= System.currentTimeMillis() - reportTimerStartedAt
            if (reportRemainingMs < 0) reportRemainingMs = 0
            reportTimerStartedAt = 0L
        }
    }

    private fun resumeReportTimer(context: Context) {
        if (reportRemainingMs <= 0L) return
        reportJob?.cancel()
        reportTimerStartedAt = System.currentTimeMillis()
        val spDc = SpotifySession.spDc(context)
        val trackUri = activeTrackUri ?: return
        val durationMs = activeDurationMs
        reportJob = scope.launch {
            delay(reportRemainingMs)
            reportThreshold(spDc, trackUri, durationMs)
            reportJob = null
        }
    }

    private fun stopReportTimer() {
        reportJob?.cancel(); reportJob = null
        reportRemainingMs = 0L; reportTimerStartedAt = 0L
    }

    private fun startSession(context: Context, spDc: String, trackUri: String, durationMs: Long) {
        if (durationMs <= MIN_DURATION_SEC * 1000L) return
        startJob?.cancel()
        val startedAt = activeStartedAtMs
        startJob = scope.launch {
            val cookie = "sp_dc=$spDc"
            val token = ensureToken(context) ?: return@launch
            val session = getOrCreateSession(trackUri, startedAt, durationMs)
            if (!session.startReported.compareAndSet(false, true)) return@launch

            val dev = ensureDevice(cookie, token) ?: run {
                session.startReported.set(false); return@launch
            }

            // Request playback state via connect-state command
            val pbState = requestPlaybackState(cookie, token, trackUri, dev)
            if (pbState == null) { session.startReported.set(false); return@launch }
            session.stateMachineId = pbState.stateMachineId
            session.stateId = pbState.stateId
            session.statePaused = pbState.paused
            pbState.durationMs?.takeIf { it > 0 }?.let { session.durationMs = it }
            pbState.playbackId?.takeIf { it.isNotBlank() }?.let { session.playbackId = it }

            // Send batch start event
            val startEvent = buildStartEvent(session, durationMs)
            val batchOk = reportBatch(cookie, trackUri.trackId()!!, "start", listOf(startEvent))
            if (!batchOk) { session.startReported.set(false); return@launch }

            // Send before_track_load state
            val btlOk = reportState(cookie, session, "before-track-load", "before_track_load",
                0L, 0L, session.durationMs, false, null, dev)
            if (!btlOk) { session.startReported.set(false); return@launch }

            // Send started_playing state
            val spOk = reportState(cookie, session, "start", "started_playing",
                session.durationMs.coerceAtLeast(0L).let { minOf(1004L, it) },
                0L, session.durationMs, false, null, dev)
            if (!spOk) session.startReported.set(false)
            else activeSessionStarted = true
        }
    }

    private suspend fun reportThreshold(spDc: String, trackUri: String, durationMs: Long) {
        if (isBackedOff()) return
        val cookie = "sp_dc=$spDc"
        val trackId = trackUri.trackId() ?: return
        val startedAt = activeStartedAtMs
        val session = getOrCreateSession(trackUri, startedAt, durationMs)

        val reportKey = "$trackUri:${startedAt / 30_000L}"
        synchronized(reportedKeys) {
            if (!reportedKeys.add(reportKey)) return
            while (reportedKeys.size > 64) reportedKeys.remove(reportedKeys.first())
        }

        if (!session.thresholdReported.compareAndSet(false, true)) return

        if (!session.startReported.get()) {
            // The start wasn't reported yet — bail (it'll come via startSession)
            session.thresholdReported.set(false)
            return
        }

        val elapsed = System.currentTimeMillis() - startedAt
        val posMs = if (durationMs > 0) elapsed.coerceIn(0, durationMs) else elapsed.coerceAtLeast(0)
        val thresholdPos = posMs.coerceAtLeast(30_000L).let { p ->
            if (session.durationMs > 0) p.coerceAtMost(session.durationMs) else p
        }

        val ok = reportState(cookie, session, "threshold", "played_threshold_reached",
            thresholdPos, posMs, session.durationMs, false, false, null)
        if (!ok) session.thresholdReported.set(false)
    }

    private fun reportPlaybackControl(context: Context, paused: Boolean) {
        val trackUri = activeTrackUri ?: return
        val session = sessions[sessionKey(trackUri, activeStartedAtMs)] ?: return
        if (!session.startReported.get() || session.finalized.get()) return
        val spDc = SpotifySession.spDc(context)
        if (spDc.isBlank()) return

        scope.launch {
            val cookie = "sp_dc=$spDc"
            val elapsed = System.currentTimeMillis() - activeStartedAtMs
            val posMs = if (activeDurationMs > 0) elapsed.coerceIn(0, activeDurationMs) else elapsed.coerceAtLeast(0)
            reportState(cookie, session,
                if (paused) "pause" else "resume",
                if (paused) "pause" else "resume",
                posMs, posMs, session.durationMs, false, paused, null)
        }
    }

    private fun finalizeCurrentSession(spDc: String) {
        val trackUri = activeTrackUri ?: return
        val session = sessions[sessionKey(trackUri, activeStartedAtMs)] ?: return
        if (!session.startReported.get() || session.finalized.get()) return
        if (!session.finalized.compareAndSet(false, true)) return

        val cookie = "sp_dc=$spDc"
        val elapsed = System.currentTimeMillis() - activeStartedAtMs
        val posMs = if (activeDurationMs > 0) elapsed.coerceIn(0, activeDurationMs) else elapsed.coerceAtLeast(0)
        val trackId = trackUri.trackId() ?: return

        scope.launch {
            val verifyEvent = buildVerifyEvent(session, posMs, null)
            val statsEvent = buildStatsEvent(session, posMs, session.durationMs)
            reportFinalizationBatch(cookie, trackId, listOf(verifyEvent, statsEvent))

            reportState(cookie, session, "finalize", "track_data_finalized",
                posMs, posMs, session.durationMs, true, null, null)
        }
    }

    // ── Session management ──

    private fun getOrCreateSession(trackUri: String, startedAtMs: Long, durationMs: Long): Session {
        pruneSessions()
        return sessions.computeIfAbsent(sessionKey(trackUri, startedAtMs)) {
            Session(
                trackUri = trackUri,
                stateMachineId = currentStateMachineId,
                stateId = currentStateId,
                playbackId = newStateId(),
                sessionId = startedAtMs.toString(),
                correlationId = newStateId(),
                startedAtMs = startedAtMs,
                durationMs = durationMs,
            )
        }
    }

    private fun sessionKey(trackUri: String, startedAtMs: Long) = "$trackUri:${startedAtMs / 30_000L}"

    private fun pruneSessions() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { now - it.value.createdAtMs > SESSION_TTL_MS }
    }

    // ── Device registration ──

    private suspend fun ensureDevice(cookie: String, token: String): Device? =
        deviceMutex.withLock {
            val now = System.currentTimeMillis()
            val hash = cookie.hashCode()
            device?.takeIf { !it.closed && it.cookieHash == hash && now - it.createdAtMs < DEVICE_TTL_MS }
                ?.let { return@withLock it }
            device?.close(); device = null

            runCatching {
                val ep = fetchEndpoints()
                val dealer = connectDealer(ep.dealerUrl, token)
                registerDevice(cookie, token, hash, ep, dealer)
            }.onFailure { Log.w(TAG, "Device registration failed: ${it.message}") }
                .getOrNull()?.also { device = it }
        }

    private fun fetchEndpoints(): Endpoints {
        val req = Request.Builder().url(APRESOLVE_URL).header("User-Agent", UA).get().build()
        client.newCall(req).execute().use { resp ->
            val root = json.parseToJsonElement(resp.body.string()).jsonObject
            val dealer = root["dealer-g2"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: "dealer.g2.spotify.com"
            val spc = root["spclient"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: "spclient.wg.spotify.com"
            return Endpoints("wss://${dealer.removeSuffix(":443")}", "https://${spc.removeSuffix(":443")}")
        }
    }

    private suspend fun connectDealer(dealerUrl: String, token: String): DealerConnection {
        val deferred = CompletableDeferred<DealerConnection>()
        val queue = CommandQueue()
        val encoded = URLEncoder.encode(token, Charsets.UTF_8.name())
        lateinit var ws: WebSocket
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                parseDealerPlaybackCommand(text)?.let(queue::offer)
                parseDealerConnectionId(text)?.let { connId ->
                    deferred.complete(DealerConnection(connId, webSocket, queue))
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                deferred.completeExceptionally(t)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!deferred.isCompleted) deferred.completeExceptionally(IllegalStateException("Dealer closed early"))
            }
        }
        ws = dealerClient.newWebSocket(Request.Builder().url("$dealerUrl?access_token=$encoded").build(), listener)
        return withTimeoutOrNull(DEALER_TIMEOUT_MS) { deferred.await() }
            ?: run { ws.close(1000, "timeout"); error("Dealer connection id timed out") }
    }

    private fun registerDevice(cookie: String, token: String, cookieHash: Int, ep: Endpoints, dealer: DealerConnection): Device {
        val deviceId = randomDeviceId()
        // Register observer
        registerObserver(cookie, token, ep.webgateUrl, deviceId, dealer.id)
        // Register track-playback device
        val payload = buildJsonObject {
            put("device", buildDeviceJson(deviceId))
            put("outro_endcontent_snooping", false)
            put("connection_id", dealer.id)
            put("client_version", SDK_ID)
            put("volume", 65535)
        }
        val req = Request.Builder()
            .url("${ep.webgateUrl}/track-playback/v1/devices")
            .headers(apiHeaders(cookie, token))
            .post(payload.toString().toRequestBody(JSON_MT))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                dealer.webSocket.close(1000, "rejected")
                if (resp.code == 429) backOff("device rate limited")
                error("Device HTTP ${resp.code}: $body")
            }
            val initSeq = runCatching { json.parseToJsonElement(body).jsonObject["initial_seq_num"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0
            return Device(cookieHash, deviceId, ep.webgateUrl, dealer.id, dealer.webSocket, dealer.commandQueue, initSeq)
        }
    }

    private fun registerObserver(cookie: String, token: String, webgateUrl: String, deviceId: String, connectionId: String) {
        val obsId = "hobs_$deviceId".take(40)
        val payload = buildJsonObject {
            put("member_type", "CONNECT_STATE")
            putJsonObject("device") { putJsonObject("device_info") { putJsonObject("capabilities") {
                put("can_be_player", false); put("hidden", true); put("needs_full_player_state", true)
            } } }
        }
        val req = Request.Builder()
            .url("$webgateUrl/connect-state/v1/devices/$obsId")
            .header("User-Agent", UA).header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("X-Spotify-Connection-Id", connectionId)
            .header("App-Platform", "WebPlayer")
            .header("Referer", WEB_REFERER).header("Origin", WEB_ORIGIN)
            .header("Cookie", cookie).header("Authorization", "Bearer $token")
            .put(payload.toString().toRequestBody(JSON_MT))
            .build()
        runCatching { client.newCall(req).execute().use { it.body.string() } }
    }

    private fun buildDeviceJson(deviceId: String): JsonObject = buildJsonObject {
        put("brand", "SpotifyHarmonyGeneric")
        putJsonObject("capabilities") {
            put("change_volume", true); put("enable_play_token", true)
            put("supports_file_media_type", true); put("play_token_lost_behavior", "pause")
            put("disable_connect", false); put("audio_podcasts", true)
            put("manifest_formats", JsonArray(listOf(
                JsonPrimitive("file_ids_mp3"), JsonPrimitive("file_urls_mp3"),
                JsonPrimitive("file_ids_mp4"), JsonPrimitive("file_ids_mp4_dual"),
            )))
            put("supports_preferred_media_type", true)
            put("supports_playback_offsets", true)
            put("supports_playback_speed", true)
        }
        put("device_id", deviceId)
        put("device_type", "computer")
        putJsonObject("metadata") { }
        put("model", DEVICE_MODEL)
        put("name", DEVICE_NAME)
        put("platform_name", PLATFORM)
        put("platform_identifier", PLATFORM)
        put("is_group", false)
        put("correlation_id", newStateId())
        put("client_version", SDK_ID)
    }

    // ── Playback state request ──

    private suspend fun requestPlaybackState(cookie: String, token: String, trackUri: String, dev: Device): PlaybackState? {
        val trackId = trackUri.trackId()
        val contextUris = buildList {
            add(trackUri)
            trackId?.let { add("spotify:station:track:$it") }
        }
        for ((i, ctx) in contextUris.withIndex()) {
            requestPlaybackStateWithContext(cookie, token, trackUri, ctx, dev, i == contextUris.lastIndex)
                ?.let { return it }
        }
        return null
    }

    private suspend fun requestPlaybackStateWithContext(
        cookie: String, token: String, trackUri: String, contextUri: String,
        dev: Device, notify: Boolean,
    ): PlaybackState? {
        val cmdWait = dev.commandQueue.next()
        val payload = buildJsonObject {
            putJsonObject("command") {
                put("endpoint", "play")
                putJsonObject("context") { put("uri", contextUri); put("url", "context://$contextUri") }
                putJsonObject("play_origin") { put("feature_identifier", "web-player"); put("feature_version", SDK_ID) }
                putJsonObject("options") {
                    put("license", "")
                    putJsonObject("skip_to") { put("track_uri", trackUri) }
                    put("initially_paused", false)
                }
                putJsonObject("logging_params") {
                    put("page_instance_ids", JsonArray(emptyList()))
                    put("interaction_ids", JsonArray(emptyList()))
                    put("command_id", newStateId())
                }
            }
        }
        val req = Request.Builder()
            .url("${dev.webgateUrl}/connect-state/v1/player/command/from/${dev.deviceId}/to/${dev.deviceId}")
            .headers(apiHeaders(cookie, token))
            .post(payload.toString().toRequestBody(JSON_MT))
            .build()

        val sent = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 429) backOff("command rate limited")
                    false
                } else true
            }
        }.getOrDefault(false)
        if (!sent) return null

        // Await the dealer's replace_state response
        return withTimeoutOrNull(DEALER_TIMEOUT_MS) {
            var wait = cmdWait
            while (true) {
                val cmd = wait.await()
                parsePlaybackState(cmd, trackUri.trackId())?.let { return@withTimeoutOrNull it }
                wait = dev.commandQueue.next()
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }

    // ── Batch reporting ──

    private suspend fun reportBatch(cookie: String, trackId: String, op: String, events: List<JsonObject>): Boolean {
        val token = tokenFromCookie(cookie) ?: return false
        val payload = buildJsonObject {
            put("client_version", CLIENT_VERSION)
            put("platform", PLATFORM)
            put("sdk_id", SDK_ID)
            put("messages", JsonArray(events.map { it as JsonElement }))
        }
        val req = Request.Builder().url(HISTORY_BATCH_URL)
            .headers(apiHeaders(cookie, token))
            .post(payload.toString().toRequestBody(JSON_MT)).build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.d(TAG, "History $op batch accepted for $trackId (${events.size} events)")
                    true
                } else {
                    if (resp.code == 429) handleRateLimit(resp)
                    Log.w(TAG, "History $op batch rejected: ${resp.code} ${resp.body.string()}")
                    false
                }
            }
        }.getOrDefault(false)
    }

    private suspend fun reportFinalizationBatch(cookie: String, trackId: String, events: List<JsonObject>): Boolean {
        val token = tokenFromCookie(cookie) ?: return false
        val payload = buildJsonObject {
            put("client_version", CLIENT_VERSION)
            put("platform", PLATFORM)
            put("sdk_id", SDK_ID)
            put("messages", JsonArray(events.map { it as JsonElement }))
        }
        val req = Request.Builder().url(HISTORY_BATCH_URL)
            .headers(apiHeaders(cookie, token))
            .post(payload.toString().toRequestBody(JSON_MT)).build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.d(TAG, "History finalization accepted for $trackId")
                    true
                } else {
                    if (resp.code == 429) handleRateLimit(resp)
                    if (resp.code == 410) { Log.d(TAG, "Finalization 410 (stale) for $trackId"); return@use true }
                    Log.w(TAG, "History finalization rejected: ${resp.code}")
                    false
                }
            }
        }.getOrDefault(false)
    }

    // ── State reporting ──

    private suspend fun reportState(
        cookie: String, session: Session, op: String, debugSource: String,
        positionMs: Long, previousPositionMs: Long, durationMs: Long,
        includeStats: Boolean, pausedOverride: Boolean?,
        dev: Device?,
    ): Boolean {
        val token = tokenFromCookie(cookie) ?: return false
        val resolvedDev = dev ?: ensureDevice(cookie, token) ?: return false

        val payload = buildStatePayload(session, debugSource, positionMs, previousPositionMs,
            durationMs, resolvedDev.nextSeq(), includeStats, pausedOverride)
        val req = Request.Builder()
            .url("${resolvedDev.webgateUrl}/track-playback/v1/devices/${resolvedDev.deviceId}/state")
            .headers(apiHeaders(cookie, token))
            .put(payload.toString().toRequestBody(JSON_MT)).build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (resp.isSuccessful) {
                    updateSessionFromResponse(session, body)
                    Log.d(TAG, "History $op state accepted for ${session.trackUri.trackId()}")
                    true
                } else {
                    if (resp.code == 429) handleRateLimit(resp)
                    else if (resp.code in listOf(400, 401, 403, 404, 409)) clearDevice(resolvedDev)
                    Log.w(TAG, "History $op state rejected: ${resp.code}")
                    false
                }
            }
        }.onFailure { clearDevice(resolvedDev) }.getOrDefault(false)
    }

    private fun clearDevice(dev: Device?) {
        val cur = device
        if (dev == null || cur === dev) { cur?.close(); device = null } else dev.close()
    }

    // ── JSON builders ──

    private fun buildStatePayload(
        session: Session, debugSource: String, positionMs: Long, previousPositionMs: Long,
        durationMs: Long, seqNum: Int, includeStats: Boolean, pausedOverride: Boolean?,
    ): JsonObject = buildJsonObject {
        val paused = pausedOverride ?: session.statePaused
        put("seq_num", seqNum)
        put("debug_source", debugSource)
        put("previous_position", previousPositionMs.coerceAtLeast(0))
        putJsonObject("state_ref") {
            put("state_machine_id", session.stateMachineId)
            put("state_id", session.stateId)
            put("paused", paused)
        }
        putJsonObject("sub_state") {
            put("playback_speed", if (paused) 0 else 1)
            put("position", positionMs.coerceAtLeast(0))
            put("duration", durationMs.coerceAtLeast(0))
            put("media_type", "AUDIO")
            put("bitrate", BITRATE)
            put("audio_quality", "HIGH")
            put("format", "file_ids_mp4")
            put("is_video_on", false)
        }
        if (includeStats) {
            putJsonObject("playback_stats") {
                put("ms_total_est", durationMs.coerceAtLeast(0))
                put("ms_metadata_duration", 0)
                put("ms_manifest_latency", 0)
                put("ms_latency", 98)
            }
        }
    }

    private fun buildStartEvent(session: Session, durationMs: Long): JsonObject = buildJsonObject {
        put("type", "jssdk_playback_start")
        put("message", buildJsonObject {
            put("play_track", session.trackUri)
            put("file_id", "")
            put("playback_id", session.playbackId)
            put("session_id", session.sessionId)
            put("ms_start_position", 0)
            put("initially_paused", false)
            put("client_id", DEVICE_CLIENT_ID)
            put("correlation_id", session.correlationId)
            put("feature_identifier", "web-player")
        })
    }

    private fun buildStatsEvent(session: Session, positionMs: Long, durationMs: Long): JsonObject = buildJsonObject {
        put("type", "jssdk_playback_stats")
        put("message", buildJsonObject {
            put("play_track", session.trackUri)
            put("file_id", ""); put("playback_id", session.playbackId)
            put("internal_play_id", session.playbackId)
            put("memory_cached", false); put("persistent_cached", false)
            put("audio_format", ""); put("video_format", ""); put("manifest_id", "")
            put("protected", false); put("key_system", ""); put("key_system_impl", "")
            put("urls_json", "[]")
            put("start_time", session.startedAtMs)
            put("end_time", session.startedAtMs + positionMs.coerceAtLeast(0))
            put("external_start_time", session.startedAtMs)
            put("ms_play_latency", 0); put("ms_init_latency", 0); put("ms_head_latency", 0)
            put("ms_first_bytes_latency", 0); put("ms_manifest_latency", 0)
            put("ms_resolve_latency", 0); put("ms_license_session_latency", 0)
            put("ms_license_generation_latency", 0); put("ms_license_request_latency", 0)
            put("ms_license_update_latency", 0)
            put("ms_played", positionMs); put("ms_nominal_played", positionMs)
            put("ms_file_duration", durationMs); put("ms_actual_duration", durationMs)
            put("ms_metadata_duration", 0); put("ms_start_position", 0)
            put("ms_end_position", positionMs)
            put("ms_initial_rebuffer", 0); put("ms_seek_rebuffer", 0)
            put("ms_seek_rebuffer_longest", 0); put("ms_stall_rebuffer", 0)
            put("ms_stall_rebuffer_longest", 0)
            put("ms_played_per_surface", buildJsonObject { })
            put("ms_played_visible", positionMs)
            put("n_stalls", 0); put("n_rendition_upgrade", 0); put("n_rendition_downgrade", 0)
            put("bps_bandwidth_max", 0); put("bps_bandwidth_min", 0); put("bps_bandwidth_avg", 0)
            put("n_seekback", 0); put("n_seekforward", 0)
            put("audio_start_bitrate", BITRATE); put("video_start_bitrate", 0)
            put("start_bitrate", BITRATE); put("audio_quality", "")
            put("time_weighted_bitrate", BITRATE)
            put("reason_start", "playbtn"); put("reason_end", "endplay")
            put("initially_paused", false); put("had_error", false)
            put("n_warnings", 0); put("n_navigator_offline", 0)
            put("session_id", session.sessionId); put("sequence_id", 1)
            put("client_id", DEVICE_CLIENT_ID); put("correlation_id", session.correlationId)
            put("n_dropped_video_frames", 0); put("n_total_video_frames", 0)
            put("resolution_max", 0); put("resolution_min", 0); put("total_bytes", 0)
            put("strategy", "")
            put("ms_played_per_audio_format", buildJsonObject { })
            put("ms_played_per_video_format", buildJsonObject { })
        })
    }

    private fun buildVerifyEvent(session: Session, positionMs: Long, nextPlaybackId: String?): JsonObject = buildJsonObject {
        put("type", "track_stream_verification")
        put("message", buildJsonObject {
            put("play_track", session.trackUri)
            put("playback_id", session.playbackId)
            put("ms_played", positionMs)
            put("ms_nominal_played", positionMs)
            put("session_id", session.sessionId)
            put("sequence_id", 1)
            put("next_playback_id", nextPlaybackId.orEmpty())
            put("playback_service", "web_player")
        })
    }

    // ── Dealer message parsing ──

    private fun parseDealerConnectionId(msg: String): String? = runCatching {
        val root = json.parseToJsonElement(msg).jsonObject
        if (root["type"]?.jsonPrimitive?.contentOrNull != "message") return@runCatching null
        root["headers"]?.jsonObject?.get("Spotify-Connection-Id")?.jsonPrimitive?.contentOrNull
            ?: CONNECTION_ID_REGEX.find(root["uri"]?.jsonPrimitive?.contentOrNull ?: "")?.groupValues?.getOrNull(1)
    }.getOrNull()

    private fun parseDealerPlaybackCommand(msg: String): JsonObject? = runCatching {
        val root = json.parseToJsonElement(msg).jsonObject
        if (root["type"]?.jsonPrimitive?.contentOrNull != "message") return@runCatching null
        if (root["uri"]?.jsonPrimitive?.contentOrNull != "hm://track-playback/v1/command") return@runCatching null
        root["payloads"]?.jsonArray?.firstOrNull()?.jsonObject?.takeIf {
            it["type"]?.jsonPrimitive?.contentOrNull == "replace_state"
        }
    }.getOrNull()

    private fun parsePlaybackState(cmd: JsonObject, expectedTrackId: String?): PlaybackState? {
        val sm = cmd["state_machine"]?.jsonObject ?: return null
        val stateRef = cmd["state_ref"]?.jsonObject ?: return null
        val stateIdx = stateRef["state_index"]?.jsonPrimitive?.intOrNull ?: return null
        val state = sm["states"]?.jsonArray?.getOrNull(stateIdx)?.jsonObject ?: return null
        val smId = sm["state_machine_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val sId = state["state_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val trackIdx = state["track"]?.jsonPrimitive?.intOrNull
        val track = trackIdx?.let { sm["tracks"]?.jsonArray?.getOrNull(it)?.jsonObject }
        val currentTid = track?.trackIdFromStateMachine()
        if (expectedTrackId != null && currentTid != expectedTrackId) return null
        val durMs = state["duration_override"]?.jsonPrimitive?.longOrNull
            ?: track?.get("metadata")?.jsonObject?.get("duration")?.jsonPrimitive?.longOrNull
        val pbId = track?.get("logData")?.jsonObject?.get("playbackId")?.jsonPrimitive?.contentOrNull
            ?: track?.get("log_data")?.jsonObject?.get("playback_id")?.jsonPrimitive?.contentOrNull
        return PlaybackState(smId, sId, stateRef["paused"]?.jsonPrimitive?.contentOrNull == "true", durMs, pbId)
    }

    private fun updateSessionFromResponse(session: Session, body: String) {
        runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val sm = root["state_machine"]?.jsonObject ?: return
            val ref = root["updated_state_ref"]?.jsonObject ?: return
            val pb = parsePlaybackState(buildJsonObject {
                put("state_machine", sm); put("state_ref", ref)
            }, session.trackUri.trackId()) ?: return
            session.stateMachineId = pb.stateMachineId
            session.stateId = pb.stateId
            session.statePaused = pb.paused
            pb.durationMs?.takeIf { it > 0 }?.let { session.durationMs = it }
            pb.playbackId?.takeIf { it.isNotBlank() }?.let { session.playbackId = it }
        }
    }

    // ── Token helper ──
    // The cookie is "sp_dc=<value>". We reuse the already-ensured token from Spotify.accessToken.
    private fun tokenFromCookie(cookie: String): String? = com.metrolist.spotify.Spotify.accessToken

    // ── Helpers ──

    private fun apiHeaders(cookie: String, token: String) = okhttp3.Headers.Builder()
        .add("User-Agent", UA).add("Accept", "application/json")
        .add("Content-Type", "application/json")
        .add("App-Platform", "WebPlayer")
        .add("Referer", WEB_REFERER).add("Origin", WEB_ORIGIN)
        .add("Cookie", cookie).add("Authorization", "Bearer $token")
        .build()

    private fun handleRateLimit(resp: okhttp3.Response) {
        val retryAfter = resp.header("Retry-After")?.toLongOrNull()?.times(1000) ?: 120_000L
        backOff("429 rate limited", retryAfter)
    }

    private fun extractTrackId(uri: String): String? {
        if (uri.startsWith("spotify:track:")) return uri.removePrefix("spotify:track:").takeIf { it.isNotBlank() }
        return null
    }

    private fun JsonObject.trackIdFromStateMachine(): String? =
        listOfNotNull(
            this["uri"]?.jsonPrimitive?.contentOrNull?.let { extractTrackId(it) },
            this["metadata"]?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull?.let { extractTrackId(it) },
        ).firstOrNull()

    private fun String.trackId(): String? = extractTrackId(this)

    private fun newStateMachineId(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return buildString {
            append("Ch")
            repeat(28) { append(chars[synchronized(rng) { rng.nextInt(chars.length) }]) }
        }
    }

    private fun newStateId(): String = randomBytes(16).toHex()
    private fun randomDeviceId(): String = randomBytes(20).toHex()
    private fun randomBytes(n: Int) = ByteArray(n).also { synchronized(rng) { rng.nextBytes(it) } }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
