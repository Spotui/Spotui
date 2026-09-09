package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.LibraryEntry
import org.json.JSONArray
import org.json.JSONObject

private const val PREF = "LibraryCache"

fun cacheLibraryEntries(context: Context, entries: List<LibraryEntry>) {
    val json = JSONArray().apply {
        entries.distinctBy { it.spotifyId }.forEach { e ->
            put(JSONObject().apply {
                put("spotifyId", e.spotifyId)
                put("name", e.name)
                put("subtitle", e.subtitle)
                put("coverUri", e.coverUri)
                put("isPlaylist", e.isPlaylist)
                put("artists", e.artists)
            })
        }
    }.toString()
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit().putString("entries", json).apply()
}

fun getCachedLibraryEntries(context: Context): List<LibraryEntry> = runCatching {
    val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString("entries", null) ?: return emptyList()
    val arr = JSONArray(raw)
    (0 until arr.length()).map { i ->
        arr.getJSONObject(i).let { o ->
            LibraryEntry(
                spotifyId = o.getString("spotifyId"),
                name = o.getString("name"),
                subtitle = o.getString("subtitle"),
                coverUri = o.getString("coverUri"),
                isPlaylist = o.getBoolean("isPlaylist"),
                artists = o.optString("artists", ""),
            )
        }
    }
}.getOrDefault(emptyList())

fun cacheFollowedArtists(context: Context, artists: List<ArtistsModel>) {
    val json = JSONArray().apply {
        artists.forEach { a ->
            put(JSONObject().apply {
                put("name", a.name)
                put("coverUri", a.coverUri)
                put("id", a.id)
            })
        }
    }.toString()
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit().putString("followedArtists", json).apply()
}

fun getCachedFollowedArtists(context: Context): List<ArtistsModel> = runCatching {
    val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString("followedArtists", null) ?: return emptyList()
    val arr = JSONArray(raw)
    (0 until arr.length()).map { i ->
        arr.getJSONObject(i).let { o ->
            ArtistsModel(
                name = o.getString("name"),
                coverUri = o.getString("coverUri"),
                id = o.optString("id", ""),
            )
        }
    }
}.getOrDefault(emptyList())
