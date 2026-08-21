package com.metrolist.spotify.models

/**
 * Synced lyrics from Spotify's own color-lyrics endpoint (what the official
 * client shows). `synced` is true for LINE_SYNCED responses; unsynced lyrics
 * come back with all `startMs` = 0.
 */
data class SpotifyLyrics(
    val synced: Boolean,
    val lines: List<SpotifyLyricLine>,
    val language: String?,
)

data class SpotifyLyricLine(
    val startMs: Long,
    val words: String,
)
