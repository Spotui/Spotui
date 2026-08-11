package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.LibraryEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the last successfully fetched "Your Library" list to disk (not just
 * in-memory [com.music.spotui.data.api.Api.HomeCache]) so that a cold app start
 * with no internet connection still has something to show instead of an empty /
 * error screen. Overwritten every time [getLibrary] succeeds online.
 */
private const val PREF = "LibraryCache"
private const val KEY = "entries"

private fun LibraryEntry.toJson(): JSONObject = JSONObject().apply {
    put("spotifyId", spotifyId)
    put("name", name)
    put("subtitle", subtitle)
    put("coverUri", coverUri)
    put("isPlaylist", isPlaylist)
    put("artists", artists)
}

private fun parseEntry(o: JSONObject): LibraryEntry = LibraryEntry(
    spotifyId = o.getString("spotifyId"),
    name = o.getString("name"),
    subtitle = o.optString("subtitle"),
    coverUri = o.optString("coverUri"),
    isPlaylist = o.optBoolean("isPlaylist", false),
    artists = o.optString("artists"),
)

fun saveLibraryCache(context: Context, entries: List<LibraryEntry>) {
    val arr = JSONArray()
    entries.forEach { arr.put(it.toJson()) }
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        .putString(KEY, arr.toString())
        .apply()
}

fun loadLibraryCache(context: Context): List<LibraryEntry>? {
    val json = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString(KEY, null) ?: return null
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { parseEntry(arr.getJSONObject(it)) }
    }.getOrNull()
}

// ---------------------------------------------------------------------------
// Home feed cache — same idea, for the Home/Discover tab (getHomeFeed()),
// which previously also only cached in memory and errored out on a cold
// offline start even if it had loaded fine minutes earlier.
// ---------------------------------------------------------------------------

private const val HOME_PREF = "HomeFeedCache"
private const val HOME_KEY = "feed"

private fun com.music.spotui.data.entity.HomeItem.toJson(): JSONObject = JSONObject().apply {
    when (val item = this@toJson) {
        is com.music.spotui.data.entity.HomeItem.Album -> {
            put("type", "album"); put("name", item.name); put("imageUrl", item.imageUrl)
            put("subtitle", item.subtitle); put("artists", item.artists)
        }
        is com.music.spotui.data.entity.HomeItem.Artist -> {
            put("type", "artist"); put("name", item.name); put("imageUrl", item.imageUrl)
            put("id", item.id)
        }
        is com.music.spotui.data.entity.HomeItem.Playlist -> {
            put("type", "playlist"); put("name", item.name); put("imageUrl", item.imageUrl)
            put("subtitle", item.subtitle); put("id", item.id)
        }
    }
}

private fun parseHomeItem(o: JSONObject): com.music.spotui.data.entity.HomeItem? = when (o.optString("type")) {
    "album" -> com.music.spotui.data.entity.HomeItem.Album(
        name = o.getString("name"), imageUrl = o.optString("imageUrl"),
        subtitle = o.optString("subtitle"), artists = o.optString("artists"),
    )
    "artist" -> com.music.spotui.data.entity.HomeItem.Artist(
        name = o.getString("name"), imageUrl = o.optString("imageUrl"), id = o.optString("id"),
    )
    "playlist" -> com.music.spotui.data.entity.HomeItem.Playlist(
        name = o.getString("name"), imageUrl = o.optString("imageUrl"),
        subtitle = o.optString("subtitle"), id = o.optString("id"),
    )
    else -> null
}

fun saveHomeFeedCache(context: Context, feed: com.music.spotui.data.entity.HomeFeedModel) {
    val sections = JSONArray()
    feed.sections.forEach { section ->
        val items = JSONArray()
        section.items.forEach { items.put(it.toJson()) }
        sections.put(JSONObject().apply { put("title", section.title); put("items", items) })
    }
    val root = JSONObject().apply { put("greeting", feed.greeting); put("sections", sections) }
    context.getSharedPreferences(HOME_PREF, Context.MODE_PRIVATE).edit()
        .putString(HOME_KEY, root.toString())
        .apply()
}

fun loadHomeFeedCache(context: Context): com.music.spotui.data.entity.HomeFeedModel? {
    val json = context.getSharedPreferences(HOME_PREF, Context.MODE_PRIVATE)
        .getString(HOME_KEY, null) ?: return null
    return runCatching {
        val root = JSONObject(json)
        val sectionsArr = root.getJSONArray("sections")
        val sections = (0 until sectionsArr.length()).map { i ->
            val s = sectionsArr.getJSONObject(i)
            val itemsArr = s.getJSONArray("items")
            val items = (0 until itemsArr.length()).mapNotNull { parseHomeItem(itemsArr.getJSONObject(it)) }
            com.music.spotui.data.entity.HomeSection(title = s.getString("title"), items = items)
        }
        com.music.spotui.data.entity.HomeFeedModel(greeting = root.optString("greeting"), sections = sections)
    }.getOrNull()
}
