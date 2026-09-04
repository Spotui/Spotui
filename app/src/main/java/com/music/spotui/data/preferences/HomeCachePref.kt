package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import org.json.JSONArray
import org.json.JSONObject

private const val PREF_HOME = "HomeCachePref"
private const val KEY_HOME_FEED = "cached_home_feed"

fun cacheHomeFeed(context: Context, feed: HomeFeedModel) {
    if (feed.sections.isEmpty()) return
    runCatching {
        val root = JSONObject().apply {
            put("greeting", feed.greeting)
            val secArr = JSONArray()
            feed.sections.forEach { sec ->
                val secObj = JSONObject().apply {
                    put("title", sec.title)
                    val itemsArr = JSONArray()
                    sec.items.forEach { item ->
                        val itemObj = JSONObject()
                        when (item) {
                            is HomeItem.Album -> {
                                itemObj.put("type", "album")
                                itemObj.put("name", item.name)
                                itemObj.put("imageUrl", item.imageUrl)
                                itemObj.put("subtitle", item.subtitle)
                                itemObj.put("artists", item.artists)
                            }
                            is HomeItem.Artist -> {
                                itemObj.put("type", "artist")
                                itemObj.put("name", item.name)
                                itemObj.put("imageUrl", item.imageUrl)
                                itemObj.put("id", item.id)
                            }
                            is HomeItem.Playlist -> {
                                itemObj.put("type", "playlist")
                                itemObj.put("name", item.name)
                                itemObj.put("imageUrl", item.imageUrl)
                                itemObj.put("subtitle", item.subtitle)
                                itemObj.put("id", item.id)
                            }
                        }
                        itemsArr.put(itemObj)
                    }
                    put("items", itemsArr)
                }
                secArr.put(secObj)
            }
            put("sections", secArr)
        }
        context.getSharedPreferences(PREF_HOME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOME_FEED, root.toString())
            .apply()
    }
}

fun getCachedHomeFeed(context: Context): HomeFeedModel? = runCatching {
    val raw = context.getSharedPreferences(PREF_HOME, Context.MODE_PRIVATE)
        .getString(KEY_HOME_FEED, null) ?: return null
    val root = JSONObject(raw)
    val greeting = root.optString("greeting", "")
    val secArr = root.getJSONArray("sections")
    val sections = (0 until secArr.length()).mapNotNull { i ->
        val secObj = secArr.getJSONObject(i)
        val title = secObj.optString("title", "")
        val itemsArr = secObj.getJSONArray("items")
        val items = (0 until itemsArr.length()).mapNotNull { j ->
            val itemObj = itemsArr.getJSONObject(j)
            when (itemObj.optString("type")) {
                "album" -> HomeItem.Album(
                    name = itemObj.optString("name"),
                    imageUrl = itemObj.optString("imageUrl"),
                    subtitle = itemObj.optString("subtitle"),
                    artists = itemObj.optString("artists"),
                )
                "artist" -> HomeItem.Artist(
                    name = itemObj.optString("name"),
                    imageUrl = itemObj.optString("imageUrl"),
                    id = itemObj.optString("id"),
                )
                "playlist" -> HomeItem.Playlist(
                    name = itemObj.optString("name"),
                    imageUrl = itemObj.optString("imageUrl"),
                    subtitle = itemObj.optString("subtitle"),
                    id = itemObj.optString("id"),
                )
                else -> null
            }
        }
        if (items.isEmpty()) null else HomeSection(title = title, items = items)
    }
    if (sections.isEmpty()) null else HomeFeedModel(greeting = greeting, sections = sections)
}.getOrNull()
