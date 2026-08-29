package com.music.spotui.data.entity

/** One timestamped line of synced lyrics. [timeMs] is the start time in ms. */
data class LyricLine(
    val timeMs: Long,
    val text: String,
)

/**
 * Lyrics for a track. When [synced] is true [lines] carry real timestamps and the
 * UI highlights/scrolls in time with playback; otherwise [lines] are just the
 * plain lyric lines (timeMs = 0) shown statically.
 */
data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val language: String? = null,
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}
