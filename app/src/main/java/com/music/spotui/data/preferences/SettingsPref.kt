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
private const val KEY_LYRIC_TRANSLATE_LANG = "lyric_translate_lang"

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

/**
 * YouTube account cookie (captured from an in-app WebView login). Passed to the
 * InnerTube client so age-restricted / login-required videos resolve. Empty when
 * not signed in — the app then uses anonymous YouTube access.
 */
private const val KEY_YT_COOKIE = "youtube_cookie"
fun getYoutubeCookie(c: Context): String = prefs(c).getString(KEY_YT_COOKIE, "").orEmpty()
fun setYoutubeCookie(c: Context, v: String) = prefs(c).edit().putString(KEY_YT_COOKIE, v).apply()
fun isYoutubeLoggedIn(c: Context): Boolean = getYoutubeCookie(c).contains("SAPISID")

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

/** Lyric translation target (e.g. en, ja). Empty = device language. */
fun getLyricTranslateLang(c: Context): String =
    prefs(c).getString(KEY_LYRIC_TRANSLATE_LANG, "").orEmpty()

fun setLyricTranslateLang(c: Context, code: String) =
    prefs(c).edit().putString(KEY_LYRIC_TRANSLATE_LANG, code.trim()).apply()

/**
 * Crossfade overlap length in ms (0 = off). When > 0, the end of each track is blended
 * into the start of the next over this window.
 */
fun getCrossfadeMs(c: Context): Int = prefs(c).getInt(KEY_CROSSFADE_MS, 0)
fun setCrossfadeMs(c: Context, ms: Int) =
    prefs(c).edit().putInt(KEY_CROSSFADE_MS, ms.coerceIn(CROSSFADE_MIN_MS, CROSSFADE_MAX_MS)).apply()

fun isCrossfadeEnabled(c: Context): Boolean = getCrossfadeMs(c) > 0

/** DJ-style mixing: low-pass the outgoing track and high-pass the incoming one during the blend. */
fun isCrossfadeDjMode(c: Context): Boolean = prefs(c).getBoolean(KEY_CROSSFADE_DJ, false)
fun setCrossfadeDjMode(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_CROSSFADE_DJ, v).apply()

/**
 * The streaming quality to use for the *current* active network: the cellular setting
 * on a metered connection (mobile data / metered hotspot), the Wi-Fi setting otherwise.
 */
fun currentStreamingQuality(c: Context): StreamQuality {
    val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return if (cm.isActiveNetworkMetered) getCellularQuality(c) else getWifiQuality(c)
}
