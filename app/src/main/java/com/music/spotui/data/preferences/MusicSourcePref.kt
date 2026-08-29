package com.music.spotui.data.preferences

import android.content.Context

/** The service used for normal (non-local, non-lossless) playback. */
enum class MusicSource(val label: String) {
    YOUTUBE_MUSIC("YouTube Music"),
    DEEZER("Deezer"),
}

private const val PREF = "music_source"
private const val KEY_PRIMARY = "primary"

private fun sourcePrefs(context: Context) =
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

/**
 * Returns null only for a new user who has not made the post-Spotify choice yet.
 * An existing Deezer login is treated as a Deezer choice when migrating older installs.
 */
fun getPrimaryMusicSource(context: Context): MusicSource? {
    val stored = sourcePrefs(context).getString(KEY_PRIMARY, null)
    if (stored != null) {
        return runCatching { MusicSource.valueOf(stored) }.getOrNull()
    }
    return if (getDeezerArl(context) != null) MusicSource.DEEZER else null
}

fun setPrimaryMusicSource(context: Context, source: MusicSource) {
    sourcePrefs(context).edit().putString(KEY_PRIMARY, source.name).apply()
}

