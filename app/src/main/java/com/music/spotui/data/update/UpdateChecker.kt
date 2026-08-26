package com.music.spotui.data.update

import android.content.Context
import android.util.Log
import com.music.spotui.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the project's GitHub release (rolling tag "Release") for a newer build
 * than the installed one, for the launch-time "update available" prompt.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val RELEASE_API =
        "https://api.github.com/repos/Spotui/Spotui/releases/tags/Release"
    private const val PREFS = "update_prefs"
    private const val KEY_SKIP = "skip_fingerprint"

    data class UpdateInfo(
        /** Version shown in the dialog, e.g. "1.2". */
        val version: String,
        /** Where "Upgrade" takes the user: the APK asset, or the release page. */
        val downloadUrl: String,
        /** Identifies this exact release build, for "Don't show again". */
        val fingerprint: String,
    )

    /**
     * Returns the available update, or null when up to date / opted out of this
     * release / the check failed (a failed check must never block launch).
     */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val info = runCatching { fetchRelease() }
            .onFailure { Log.d(TAG, "update check failed: ${it.message}") }
            .getOrNull() ?: return@withContext null
        if (!isNewer(info.version, BuildConfig.VERSION_NAME)) return@withContext null
        val skipped = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SKIP, null)
        if (skipped == info.fingerprint) return@withContext null
        info
    }

    /** "Don't show again" — suppress the prompt for THIS release (a later one prompts again). */
    fun skipRelease(context: Context, info: UpdateInfo) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SKIP, info.fingerprint).apply()
    }

    private fun fetchRelease(): UpdateInfo? {
        val conn = URL(RELEASE_API).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Spotui-App")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            // The tag is the fixed "Release", so the version lives in the release
            // title (e.g. "spotui 1.2"), falling back to the description.
            val version = extractVersion(json.optString("name"))
                ?: extractVersion(json.optString("body"))
                ?: return null
            val assets = json.optJSONArray("assets")
            val apkUrl = (0 until (assets?.length() ?: 0))
                .asSequence()
                .mapNotNull { assets?.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?.optString("browser_download_url")
            val page = json.optString("html_url")
                .ifBlank { "https://github.com/Spotui/Spotui/releases/tag/Release" }
            return UpdateInfo(
                version = version,
                downloadUrl = apkUrl?.ifBlank { null } ?: page,
                fingerprint = "${json.optLong("id")}:${json.optString("updated_at")}:$version",
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun extractVersion(text: String?): String? =
        text?.let { Regex("""\d+(?:\.\d+)+""").find(it)?.value }

    private fun isNewer(remote: String, installed: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val i = installed.split('.').map { it.toIntOrNull() ?: 0 }
        for (n in 0 until maxOf(r.size, i.size)) {
            val a = r.getOrElse(n) { 0 }
            val b = i.getOrElse(n) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
