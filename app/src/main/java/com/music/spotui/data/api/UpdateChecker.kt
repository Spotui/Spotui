package com.music.spotui.data.api

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub for a release newer than this build. */
object UpdateChecker {
    private const val LATEST = "https://api.github.com/repos/Nonit0/Spotui/releases/latest"
    private const val RELEASES_PAGE = "https://github.com/Nonit0/Spotui/releases/latest"
    // GitHub allows 60 unauthenticated API calls an hour per IP; a few a day is plenty.
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    private const val PREF = "updates"

    data class Update(val version: String, val url: String)

    /**
     * The newer release, if any. Asks GitHub when [force] or the last check is old,
     * otherwise answers from the last result so the badge also shows offline.
     * Network: call off the main thread.
     */
    fun check(context: Context, current: String, force: Boolean = false): Update? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (force || now - p.getLong("checked_at", 0L) > CHECK_EVERY_MS) {
            runCatching {
                val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                val body = try {
                    if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
                val o = JSONObject(body)
                p.edit()
                    .putString("latest", o.optString("tag_name"))
                    .putString("url", o.optString("html_url").ifBlank { RELEASES_PAGE })
                    .putLong("checked_at", now)
                    .apply()
            }
        }
        val latest = p.getString("latest", null) ?: return null
        return if (isNewer(latest, current)) Update(latest.removePrefix("v"), p.getString("url", null) ?: RELEASES_PAGE) else null
    }

    /** Numeric, part by part: "v1.10" is newer than "1.6"; missing parts count as 0. */
    internal fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', ' ').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
