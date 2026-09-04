package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.SongsModel
import org.json.JSONArray
import org.json.JSONObject

private const val PREF_LIKED_CACHE = "LikedSongsFullCache"
private const val KEY_LIKED_SONGS = "cached_liked_songs"

fun cacheLikedSongs(context: Context, songs: List<SongsModel>) {
    if (songs.isEmpty()) return
    runCatching {
        val sp = context.getSharedPreferences(PREF_LIKED_CACHE, Context.MODE_PRIVATE)
        val arr = JSONArray().apply {
            songs.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("album", s.album)
                    put("singer", s.singer)
                    put("coverUri", s.coverUri)
                    put("url", s.url)
                    put("spotifyTrackId", s.spotifyTrackId)
                    put("explicit", s.explicit)
                    put("durationMs", s.durationMs)
                })
            }
        }
        sp.edit().putString(KEY_LIKED_SONGS, arr.toString()).apply()
    }
}

fun getCachedLikedSongs(context: Context): List<SongsModel> = runCatching {
    val sp = context.getSharedPreferences(PREF_LIKED_CACHE, Context.MODE_PRIVATE)
    val raw = sp.getString(KEY_LIKED_SONGS, null) ?: return emptyList()
    val arr = JSONArray(raw)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        SongsModel(
            id = o.getInt("id"),
            title = o.getString("title"),
            album = o.optString("album", ""),
            singer = o.optString("singer", ""),
            coverUri = o.optString("coverUri", ""),
            url = o.getString("url"),
            spotifyTrackId = o.optString("spotifyTrackId", ""),
            explicit = o.optBoolean("explicit", false),
            durationMs = o.optInt("durationMs", 0),
        )
    }
}.getOrDefault(emptyList())
