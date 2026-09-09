package com.music.spotui.data.preferences

import android.content.Context
import android.net.ConnectivityManager
import com.metrolist.music.constants.AudioQuality

/**
 * User-facing audio quality tiers (Spotify-style). Each maps to the YouTube format
 * selector ([AudioQuality]) and whether to attempt a lossless FLAC source first.
 */
enum class StreamQuality(
    val label: String,
    val detail: String,
    val audioQuality: AudioQuality,
    val lossless: Boolean,
) {
    LOW("Low", "Data saver — smallest size", AudioQuality.LOW, false),
    NORMAL("Normal", "Balanced for the network", AudioQuality.AUTO, false),
    HIGH("High", "Best compressed quality", AudioQuality.HIGH, false),
    LOSSLESS("Lossless", "FLAC when available, else High", AudioQuality.HIGH, true),
}

private const val PREF = "settings_prefs"
private const val KEY_WIFI_Q = "stream_quality_wifi"
private const val KEY_CELL_Q = "stream_quality_cellular"
private const val KEY_DL_Q = "download_quality"
private const val KEY_PRELOAD = "preload_enabled"
private const val KEY_CROSSFADE_MS = "crossfade_duration_ms"
private const val KEY_CROSSFADE_DJ = "crossfade_dj_mode"
private const val KEY_WEB_PLAYBACK = "web_playback_enabled"
private const val KEY_VIDEO_FALLBACK = "video_fallback_enabled"
private const val KEY_LIBRARY_GRID = "library_grid_view"

/** Off (0s) … 12s. 0 disables crossfade. */
const val CROSSFADE_MIN_MS = 0
const val CROSSFADE_MAX_MS = 12000
const val CROSSFADE_DEFAULT_MS = 6000

private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

private fun readQ(c: Context, key: String, def: StreamQuality): StreamQuality =
    runCatching { StreamQuality.valueOf(prefs(c).getString(key, def.name)!!) }.getOrDefault(def)

private fun writeQ(c: Context, key: String, q: StreamQuality) =
    prefs(c).edit().putString(key, q.name).apply()

fun getWifiQuality(c: Context): StreamQuality = readQ(c, KEY_WIFI_Q, StreamQuality.HIGH)
fun setWifiQuality(c: Context, q: StreamQuality) = writeQ(c, KEY_WIFI_Q, q)

fun getCellularQuality(c: Context): StreamQuality = readQ(c, KEY_CELL_Q, StreamQuality.NORMAL)
fun setCellularQuality(c: Context, q: StreamQuality) = writeQ(c, KEY_CELL_Q, q)

fun getDownloadQuality(c: Context): StreamQuality = readQ(c, KEY_DL_Q, StreamQuality.LOSSLESS)
fun setDownloadQuality(c: Context, q: StreamQuality) = writeQ(c, KEY_DL_Q, q)

/** Library layout: false = rows (default), true = Spotify-style 3-column grid. */
fun isLibraryGridView(c: Context): Boolean = prefs(c).getBoolean(KEY_LIBRARY_GRID, false)
fun setLibraryGridView(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_LIBRARY_GRID, v).apply()

fun isPreloadEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_PRELOAD, true)
fun setPreloadEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_PRELOAD, v).apply()

private const val KEY_MEDIA_CACHE_MAX_MB = "media_cache_max_mb"
fun getMediaCacheMaxMb(c: Context): Int = prefs(c).getInt(KEY_MEDIA_CACHE_MAX_MB, 1024)
fun setMediaCacheMaxMb(c: Context, mb: Int) = prefs(c).edit().putInt(KEY_MEDIA_CACHE_MAX_MB, mb).apply()

fun formatBytesHumanReadable(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.1f GB".format(gb)
        mb >= 1.0 -> "%.0f MB".format(mb)
        else -> "%.0f KB".format(kb)
    }
}

/**
 * YouTube account cookie (captured from an in-app WebView login). Passed to the
 * InnerTube client so age-restricted / login-required videos resolve. Empty when
 * not signed in — the app then uses anonymous YouTube access.
 */
private const val KEY_YT_COOKIE = "youtube_cookie"
private const val KEY_YT_VISITOR_DATA = "youtube_visitor_data"
private const val KEY_YT_DATA_SYNC_ID = "youtube_data_sync_id"
private const val KEY_YT_LOGIN_VERSION = "youtube_login_version"
private const val YT_LOGIN_VERSION = 2
fun getYoutubeCookie(c: Context): String = prefs(c).getString(KEY_YT_COOKIE, "").orEmpty()
fun setYoutubeCookie(c: Context, v: String) = prefs(c).edit().putString(KEY_YT_COOKIE, v).apply()
fun getYoutubeVisitorData(c: Context): String = prefs(c).getString(KEY_YT_VISITOR_DATA, "").orEmpty()
fun getYoutubeDataSyncId(c: Context): String = prefs(c).getString(KEY_YT_DATA_SYNC_ID, "").orEmpty()

/** Handles both primary-channel and delegated-channel DATASYNC_ID shapes used by YouTube. */
fun normalizeYoutubeDataSyncId(value: String): String = when {
    "||" !in value -> value
    value.endsWith("||") -> value.substringBefore("||")
    else -> value.substringAfter("||")
}

fun setYoutubeLogin(c: Context, cookie: String, visitorData: String = "", dataSyncId: String = "") {
    prefs(c).edit()
        .putString(KEY_YT_COOKIE, cookie)
        .putString(KEY_YT_VISITOR_DATA, visitorData)
        .putString(KEY_YT_DATA_SYNC_ID, normalizeYoutubeDataSyncId(dataSyncId))
        .putInt(KEY_YT_LOGIN_VERSION, YT_LOGIN_VERSION)
        .apply()
}

fun clearYoutubeLogin(c: Context) {
    prefs(c).edit()
        .remove(KEY_YT_COOKIE)
        .remove(KEY_YT_VISITOR_DATA)
        .remove(KEY_YT_DATA_SYNC_ID)
        .remove(KEY_YT_LOGIN_VERSION)
        .apply()
}

/** SAPISID is required to sign InnerTube requests; a generic Google cookie is not a login. */
fun isYoutubeLoggedIn(c: Context): Boolean =
    prefs(c).getInt(KEY_YT_LOGIN_VERSION, 0) >= YT_LOGIN_VERSION &&
        getYoutubeCookie(c).split(';').any { it.substringBefore('=').trim() == "SAPISID" }

/**
 * Play audio through Spotify's own web player in a hidden WebView (real Spotify
 * streaming, no decryption/bypass). DEFAULT OFF (and hidden from Settings) — the
 * YouTube/FLAC engine is the primary source; this path is real-time only with no
 * download/crossfade support.
 */
fun isWebPlaybackEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_WEB_PLAYBACK, false)
fun setWebPlaybackEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_WEB_PLAYBACK, v).apply()

fun isVideoFallbackEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_VIDEO_FALLBACK, true)
fun setVideoFallbackEnabled(c: Context, v: Boolean) =
    prefs(c).edit().putBoolean(KEY_VIDEO_FALLBACK, v).apply()

/**
 * Crossfade overlap length in ms (0 = off). When > 0, the end of each track is blended
 * into the start of the next over this window.
 */
fun getCrossfadeMs(c: Context): Int = prefs(c).getInt(KEY_CROSSFADE_MS, 0)
fun setCrossfadeMs(c: Context, ms: Int) =
    prefs(c).edit().putInt(KEY_CROSSFADE_MS, ms.coerceIn(CROSSFADE_MIN_MS, CROSSFADE_MAX_MS)).apply()

fun isCrossfadeEnabled(c: Context): Boolean = getCrossfadeMs(c) > 0

fun isNetworkAvailable(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}.getOrDefault(false)

/** DJ-style mixing: low-pass the outgoing track and high-pass the incoming one during the blend. */
fun isCrossfadeDjMode(c: Context): Boolean = prefs(c).getBoolean(KEY_CROSSFADE_DJ, false)
fun setCrossfadeDjMode(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_CROSSFADE_DJ, v).apply()

/**
 * The streaming quality to use for the *current* active network: the cellular setting
 * on a metered connection (mobile data / metered hotspot), the Wi-Fi setting otherwise.
 */
fun currentStreamingQuality(c: Context): StreamQuality {
    val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val isMetered = cm?.isActiveNetworkMetered ?: false
    return if (isMetered) getCellularQuality(c) else getWifiQuality(c)
}
