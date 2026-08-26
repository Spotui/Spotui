package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.SongsModel

fun addLikedSongId(context: Context, songId: String) {
    val sharedPreferences = context.getSharedPreferences("LikedSongs", Context.MODE_PRIVATE)
    val editor = sharedPreferences.edit()
    editor.putString(songId, songId)
    editor.apply()
}

fun removeLikedSongId(context: Context, songId: String) {
    val sharedPreferences = context.getSharedPreferences("LikedSongs", Context.MODE_PRIVATE)
    val editor = sharedPreferences.edit()
    editor.remove(songId)
    editor.apply()
}

fun isSongLiked(context: Context, songId: String): Boolean {
    val sharedPreferences = context.getSharedPreferences("LikedSongs", Context.MODE_PRIVATE)
    return sharedPreferences.contains(songId)
}

fun getLikedSongIds(context: Context): Set<Int> {
    val sharedPreferences = context.getSharedPreferences("LikedSongs", Context.MODE_PRIVATE)
    return sharedPreferences.all.keys.mapNotNull { it.toIntOrNull() }.toSet()
}

fun getSongsByIds(songIds: Set<Int>, songs: List<SongsModel>): List<SongsModel> {
    return songs.filter { song -> song.id in songIds }
}