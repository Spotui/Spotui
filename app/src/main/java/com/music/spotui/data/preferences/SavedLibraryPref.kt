package com.music.spotui.data.preferences

import android.content.Context

fun isPlaylistSavedInPref(context: Context, playlistId: String): Boolean {
    if (playlistId.isBlank()) return false
    val inLibrary = getCachedLibraryEntries(context).any { it.spotifyId == playlistId }
    if (inLibrary) return true
    val sp = context.getSharedPreferences("SavedPlaylists", Context.MODE_PRIVATE)
    return sp.getBoolean(playlistId, false)
}

fun setPlaylistSavedInPref(context: Context, playlistId: String, saved: Boolean) {
    if (playlistId.isBlank()) return
    val sp = context.getSharedPreferences("SavedPlaylists", Context.MODE_PRIVATE)
    sp.edit().putBoolean(playlistId, saved).apply()
}

fun isAlbumSavedInPref(context: Context, albumId: String): Boolean {
    if (albumId.isBlank()) return false
    val inLibrary = getCachedLibraryEntries(context).any { it.spotifyId == albumId }
    if (inLibrary) return true
    val sp = context.getSharedPreferences("SavedAlbums", Context.MODE_PRIVATE)
    return sp.getBoolean(albumId, false)
}

fun setAlbumSavedInPref(context: Context, albumId: String, saved: Boolean) {
    if (albumId.isBlank()) return
    val sp = context.getSharedPreferences("SavedAlbums", Context.MODE_PRIVATE)
    sp.edit().putBoolean(albumId, saved).apply()
}
