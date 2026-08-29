package com.music.spotui.data.preferences

import android.content.Context

/**
 * Local storage for the Deezer account: the ARL cookie captured at login, the
 * detected account tier (for display), and whether Deezer is used as a source.
 */
private const val PREF = "Deezer"
private const val KEY_ARL = "arl"
private const val KEY_ENABLED = "enabled"
private const val KEY_TIER = "tier"

private fun prefs(context: Context) =
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

fun setDeezerArl(context: Context, arl: String) {
    prefs(context).edit().putString(KEY_ARL, arl).apply()
}

fun getDeezerArl(context: Context): String? =
    prefs(context).getString(KEY_ARL, null)?.takeIf { it.isNotBlank() }

fun clearDeezer(context: Context) {
    prefs(context).edit().remove(KEY_ARL).remove(KEY_TIER).apply()
}

/** Deezer is used when the user has logged in and hasn't turned it off. */
fun isDeezerEnabled(context: Context): Boolean =
    getDeezerArl(context) != null &&
        prefs(context).getBoolean(KEY_ENABLED, true) &&
        getPrimaryMusicSource(context) == MusicSource.DEEZER

fun setDeezerEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
}

/** Human-readable tier, e.g. "Premium (FLAC)" / "Free (MP3 128)", "" if unknown. */
fun getDeezerTier(context: Context): String =
    prefs(context).getString(KEY_TIER, "").orEmpty()

fun setDeezerTier(context: Context, tier: String) {
    prefs(context).edit().putString(KEY_TIER, tier).apply()
}
