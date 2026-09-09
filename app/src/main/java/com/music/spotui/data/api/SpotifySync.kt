package com.music.spotui.data.api

import android.content.Context
import android.util.Log
import com.metrolist.spotify.Spotify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Fire-and-forget mirroring of local library actions to the user's REAL Spotify
 * account (web-player token): liking a song, saving an album or following an
 * artist here shows up in Spotify itself. Failures are logged, never surfaced —
 * the local action already succeeded.
 */
object SpotifySync {

    private const val TAG = "SpotifySync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun dispatchMain(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    fun setTrackSaved(context: Context, trackId: String, saved: Boolean) =
        setSaved(context, trackId, "spotify:track:$trackId", saved)

    fun setAlbumSaved(context: Context, albumId: String, saved: Boolean) =
        setSaved(context, albumId, "spotify:album:$albumId", saved)

    fun setArtistFollowed(context: Context, artistId: String, followed: Boolean) =
        setSaved(context, artistId, "spotify:artist:$artistId", followed)

    fun setPlaylistSaved(context: Context, playlistId: String, saved: Boolean) {
        val cleanId = playlistId.removePrefix("spotify:playlist:")
        setSaved(context, cleanId, "spotify:playlist:$cleanId", saved)
    }

    private fun setSaved(context: Context, id: String, uri: String, saved: Boolean) {
        if (id.isBlank()) return
        val app = context.applicationContext
        scope.launch {
            if (!SpotifyTokenProvider.ensureToken(app)) {
                Log.w(TAG, "no token — skipped syncing $uri saved=$saved")
                return@launch
            }
            val result =
                if (saved) Spotify.addToLibrary(listOf(uri))
                else Spotify.removeFromLibrary(listOf(uri))
            result.fold(
                onSuccess = { Log.d(TAG, "synced $uri saved=$saved") },
                onFailure = { Log.w(TAG, "failed syncing $uri saved=$saved", it) },
            )
        }
    }

    /** Adds a track to one of the user's playlists on Spotify itself. */
    fun addTrackToPlaylist(context: Context, playlistId: String, trackId: String, onDone: (Boolean) -> Unit = {}) {
        if (playlistId.isBlank() || trackId.isBlank()) { dispatchMain { onDone(false) }; return }
        val app = context.applicationContext
        scope.launch {
            val ok = SpotifyTokenProvider.ensureToken(app) &&
                Spotify.addTracksToPlaylist(playlistId, listOf("spotify:track:$trackId")).isSuccess
            if (ok) membershipCache[playlistId]?.add(trackId)
            if (!ok) Log.w(TAG, "failed adding track $trackId to playlist $playlistId")
            dispatchMain { onDone(ok) }
        }
    }

    /** Removes every occurrence of a track from one of the user's playlists on
     *  Spotify itself (resolves the playlist-scoped item uids first). */
    fun removeTrackFromPlaylist(context: Context, playlistId: String, trackId: String, onDone: (Boolean) -> Unit = {}) {
        if (playlistId.isBlank() || trackId.isBlank()) { dispatchMain { onDone(false) }; return }
        val app = context.applicationContext
        scope.launch {
            if (!SpotifyTokenProvider.ensureToken(app)) { dispatchMain { onDone(false) }; return@launch }
            val uri = "spotify:track:$trackId"
            val refs = Spotify.playlistTracks(playlistId, limit = 100).getOrNull()
                ?.items.orEmpty()
                .filter { it.track?.id == trackId || it.track?.uri == uri }
                .mapNotNull { pt -> pt.uid?.let { Spotify.PlaylistItemRef(uri = uri, uid = it) } }
            val ok = refs.isNotEmpty() &&
                Spotify.removeTracksFromPlaylist(playlistId, refs).isSuccess
            if (ok) membershipCache[playlistId]?.remove(trackId)
            if (!ok) Log.w(TAG, "failed removing track $trackId from playlist $playlistId")
            dispatchMain { onDone(ok) }
        }
    }

    /** Creates a new playlist on Spotify and adds the track to it. */
    fun createPlaylistWithTrack(context: Context, name: String, trackId: String, onDone: (Boolean) -> Unit = {}) {
        if (name.isBlank()) { dispatchMain { onDone(false) }; return }
        val app = context.applicationContext
        scope.launch {
            if (!SpotifyTokenProvider.ensureToken(app)) { dispatchMain { onDone(false) }; return@launch }
            val playlist = Spotify.createPlaylist(name).getOrNull()
            if (playlist == null || playlist.id.isBlank()) {
                Log.w(TAG, "createPlaylist '$name' failed")
                dispatchMain { onDone(false) }
                return@launch
            }
            val ok = trackId.isBlank() ||
                Spotify.addTracksToPlaylist(playlist.id, listOf("spotify:track:$trackId")).isSuccess
            if (ok && trackId.isNotBlank()) {
                membershipCache[playlist.id] = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { add(trackId) }
            }
            dispatchMain { onDone(ok) }
        }
    }

    /** Edits a playlist's name and/or description on Spotify. */
    fun editPlaylist(context: Context, playlistId: String, newName: String, onDone: (Boolean) -> Unit = {}) {
        if (playlistId.isBlank() || newName.isBlank()) { dispatchMain { onDone(false) }; return }
        val app = context.applicationContext
        scope.launch {
            if (!SpotifyTokenProvider.ensureToken(app)) { dispatchMain { onDone(false) }; return@launch }
            val ok = Spotify.editPlaylistAttributes(playlistId, newName = newName).isSuccess
            if (!ok) Log.w(TAG, "failed editing playlist $playlistId")
            dispatchMain { onDone(ok) }
        }
    }

    /** Deletes a playlist on Spotify. */
    fun deletePlaylist(context: Context, playlistId: String, onDone: (Boolean) -> Unit = {}) {
        if (playlistId.isBlank()) { dispatchMain { onDone(false) }; return }
        val app = context.applicationContext
        scope.launch {
            if (!SpotifyTokenProvider.ensureToken(app)) { dispatchMain { onDone(false) }; return@launch }
            val ok = Spotify.deletePlaylist(playlistId).isSuccess
            if (!ok) Log.w(TAG, "failed deleting playlist $playlistId")
            dispatchMain { onDone(ok) }
        }
    }

    /** Sets a playlist's public/private visibility on Spotify. */
    fun setPlaylistPublic(context: Context, playlistId: String, isPublic: Boolean, onDone: (Boolean) -> Unit = {}) {
        if (playlistId.isBlank()) { dispatchMain { onDone(false) }; return }
        val app = context.applicationContext
        scope.launch {
            if (!SpotifyTokenProvider.ensureToken(app)) { dispatchMain { onDone(false) }; return@launch }
            val ok = Spotify.setPlaylistPublic(playlistId, isPublic).isSuccess
            if (!ok) Log.w(TAG, "failed setting public=$isPublic for playlist $playlistId")
            dispatchMain { onDone(ok) }
        }
    }

    // ── Playlist membership (which playlists contain a track) ──
    // Cached per playlist for the app session so the "Saved in" sheet can show
    // check marks without re-fetching on every open.
    private val membershipCache = ConcurrentHashMap<String, MutableSet<String>>()

    /** Track ids contained in [playlistId] (first call fetches, then cached). */
    suspend fun playlistTrackIds(context: Context, playlistId: String): Set<String> {
        membershipCache[playlistId]?.let { return it }
        if (!SpotifyTokenProvider.ensureToken(context.applicationContext)) return emptySet()
        val ids = Spotify.playlistTracks(playlistId, limit = 100).getOrNull()
            ?.items.orEmpty()
            .mapNotNull { it.track?.id?.ifBlank { null } }
            .toMutableSet()
        val set = ConcurrentHashMap.newKeySet<String>().apply { addAll(ids) }
        membershipCache[playlistId] = set
        return set
    }
}
