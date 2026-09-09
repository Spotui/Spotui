package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.SongsModel
import org.json.JSONArray
import org.json.JSONObject

private const val PREF_PREFIX = "PlaylistCache_"

fun cachePlaylistData(context: Context, playlistId: String, album: AlbumsModel?, songs: List<SongsModel>) {
    if (playlistId.isBlank()) return
    val sp = context.getSharedPreferences("$PREF_PREFIX$playlistId", Context.MODE_PRIVATE)
    val editor = sp.edit()
    album?.let {
        val albumObj = JSONObject().apply {
            put("id", it.id)
            put("artists", it.artists)
            put("coverUri", it.coverUri)
            put("name", it.name)
            put("time", it.time)
        }
        editor.putString("metadata", albumObj.toString())
    }
    if (songs.isNotEmpty()) {
        val songsArr = JSONArray().apply {
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
        editor.putString("songs", songsArr.toString())
    }
    editor.apply()
}

fun getCachedPlaylistAlbum(context: Context, playlistId: String): AlbumsModel? = runCatching {
    val sp = context.getSharedPreferences("$PREF_PREFIX$playlistId", Context.MODE_PRIVATE)
    val raw = sp.getString("metadata", null) ?: return null
    val obj = JSONObject(raw)
    AlbumsModel(
        id = obj.getInt("id"),
        artists = obj.optString("artists", ""),
        coverUri = obj.optString("coverUri", ""),
        name = obj.optString("name", ""),
        time = obj.optString("time", ""),
    )
}.getOrNull()

fun getCachedPlaylistSongs(context: Context, playlistId: String): List<SongsModel> = runCatching {
    val sp = context.getSharedPreferences("$PREF_PREFIX$playlistId", Context.MODE_PRIVATE)
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
