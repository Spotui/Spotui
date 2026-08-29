package com.music.spotui.ui.navigation

import androidx.annotation.DrawableRes
import com.music.spotui.R

sealed class Routes(
    @DrawableRes val icon : Int = 0,
    val label : String,
    val route : String
) {
    object Home : Routes(icon = R.drawable.ic_home_filled, label = "Home", route = "home")
    object Search : Routes(icon = R.drawable.ic_search_big, label = "Search", route = "search")
    object Library : Routes(icon = R.drawable.ic_library_big, label = "Library", route = "library")
    object Album : Routes(0, "Album", "album")
    object Player : Routes(0, "Player", "player")
    object Artist : Routes(0, "Artist", "artist")
    object ArtistReleases : Routes(0, "ArtistReleases", "artistreleases")
    object Playlist : Routes(0, "Playlist", "playlist")
    object Show : Routes(0, "Show", "show")
    object Queue : Routes(0, "Queue", "queue")
    object Liked : Routes(0, "Liked", "liked")
    object Downloads : Routes(0, "Downloads", "downloads")
    object Category : Routes(0, "Category", "category")
    object Login : Routes(0, "Login", "login")
    object Settings : Routes(0, "Settings", "settings")
    object History : Routes(0, "History", "history")
    object DeezerLogin : Routes(0, "DeezerLogin", "deezerlogin")
    object DeezerIntro : Routes(0, "DeezerIntro", "deezerintro")
    object MusicSource : Routes(0, "MusicSource", "musicsource")
    object YouTubeLogin : Routes(0, "YouTubeLogin", "youtubelogin")
    object LocalFiles : Routes(0, "LocalFiles", "localfiles")
    object SpotiflacVerify : Routes(0, "SpotiflacVerify", "spotiflacverify")
}

/** Builds a Browse-category route carrying the search genre and a display title. */
fun categoryRoute(genre: String, title: String): String =
    "${Routes.Category.route}/${android.net.Uri.encode(genre)}?title=${android.net.Uri.encode(title)}"

/** Builds a playlist route carrying the Spotify playlist id (and a display name). */
fun playlistRoute(id: String, name: String = ""): String =
    "${Routes.Playlist.route}/${android.net.Uri.encode(id)}?name=${android.net.Uri.encode(name)}"

/** Builds a podcast-show route carrying the Spotify show id (and a display name). */
fun showRoute(id: String, name: String = ""): String =
    "${Routes.Show.route}/${android.net.Uri.encode(id)}?name=${android.net.Uri.encode(name)}"

/**
 * Builds an artist route. When the exact Spotify artist id is known it is
 * carried along so the artist page opens the exact artist instead of
 * re-resolving the name via a fuzzy search ("RAM" must not open "Rammstein").
 */
fun artistRoute(name: String, id: String = ""): String {
    val base = "${Routes.Artist.route}/${android.net.Uri.encode(name)}"
    return if (id.isBlank()) base
    else "$base?id=${android.net.Uri.encode(id)}"
}

/**
 * Builds an album route, optionally carrying the artist so same-named albums by
 * different artists resolve to the right one. The artist value is URL-encoded.
 */
fun albumRoute(name: String, artist: String = ""): String {
    val base = "${Routes.Album.route}/$name"
    return if (artist.isBlank()) base
    else "$base?artist=${android.net.Uri.encode(artist)}"
}
