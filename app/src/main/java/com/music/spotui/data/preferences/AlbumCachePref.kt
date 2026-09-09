package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.SongsModel
import org.json.JSONArray
import org.json.JSONObject

private const val PREF_ALBUM_PREFIX = "AlbumCache_"

fun cacheAlbumData(context: Context, albumKey: String, songs: List<SongsModel>) {
    if (albumKey.isBlank() || songs.isEmpty()) return
    runCatching {
        val sp = context.getSharedPreferences("$PREF_ALBUM_PREFIX$albumKey", Context.MODE_PRIVATE)
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
        sp.edit().putString("songs", arr.toString()).apply()
    }
}

fun cacheAlbumId(context: Context, albumKey: String, albumId: String) {
    if (albumKey.isBlank() || albumId.isBlank()) return
    runCatching {
        context.getSharedPreferences("$PREF_ALBUM_PREFIX$albumKey", Context.MODE_PRIVATE)
            .edit().putString("albumId", albumId).apply()
    }
}

fun getCachedAlbumId(context: Context, albumKey: String): String? = runCatching {
    context.getSharedPreferences("$PREF_ALBUM_PREFIX$albumKey", Context.MODE_PRIVATE)
        .getString("albumId", null)
}.getOrNull()

fun getCachedAlbumSongs(context: Context, albumKey: String): List<SongsModel> = runCatching {
    val sp = context.getSharedPreferences("$PREF_ALBUM_PREFIX$albumKey", Context.MODE_PRIVATE)
    val raw = sp.getString("songs", null) ?: return emptyList()
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

// ── Radio / Recommendations Cache ──
private const val PREF_RADIO_PREFIX = "RadioCache_"

fun cacheRadioSongs(context: Context, seedId: String, songs: List<SongsModel>) {
    if (seedId.isBlank() || songs.isEmpty()) return
    runCatching {
        val sp = context.getSharedPreferences("$PREF_RADIO_PREFIX$seedId", Context.MODE_PRIVATE)
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
        sp.edit().putString("songs", arr.toString()).apply()
    }
}

fun getCachedRadioSongs(context: Context, seedId: String): List<SongsModel> = runCatching {
    val sp = context.getSharedPreferences("$PREF_RADIO_PREFIX$seedId", Context.MODE_PRIVATE)
    val raw = sp.getString("songs", null) ?: return emptyList()
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
