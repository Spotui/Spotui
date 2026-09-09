package com.music.spotui.ui.viewmodel

import androidx.compose.runtime.State
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val repository: AppRepository,
    private val currentSongState: CurrentSongState,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    val currentSongPlayingState: State<Boolean> get() = currentSongState.playingState
    val currentSongId: State<Int> get() = currentSongState.songId

    private val _songs: MutableStateFlow<Response<List<SongsModel>>> = MutableStateFlow(Response.Loading())
    val songs: StateFlow<Response<List<SongsModel>>> = _songs

    private val _playlist: MutableStateFlow<Response<AlbumsModel>> = MutableStateFlow(Response.Loading())
    val playlist: StateFlow<Response<AlbumsModel>> = _playlist

    val queue: State<List<SongsModel>> get() = currentSongState.queue

    fun updateQueue(songs: List<SongsModel>) = currentSongState.updateQueue(songs)

    fun startShuffled(songs: List<SongsModel>) = currentSongState.startShuffled(songs)

    /** Pause/resume global playback (the header play button stays visible while playing). */
    fun setPlaying(playing: Boolean) {
        if (playing) com.music.spotui.di.SongPlayer.play() else com.music.spotui.di.SongPlayer.pause()
        currentSongState.updatePlayingState(playing)
    }

    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId: Int, songIndex: Int = 0, album: String = "") {
        currentSongState.updateSongState(coverUri, title, singer, playingState, songId, songIndex, album)
    }

    fun updatePlaybackContext(uri: String?) = currentSongState.updatePlaybackContextUri(uri)

    val likeState = currentSongState.likeState

    fun updateLikeState(likeState: Boolean) {
        currentSongState.updateLikeState(likeState)
    }

    private var playlistKey: String? = null

    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved

    fun checkSaved(playlistId: String) {
        _isSaved.value = com.music.spotui.data.preferences.isPlaylistSavedInPref(context, playlistId)
    }

    fun toggleSavePlaylist(playlistId: String) {
        val next = !_isSaved.value
        _isSaved.value = next
        com.music.spotui.data.preferences.setPlaylistSavedInPref(context, playlistId, next)
        com.music.spotui.data.api.SpotifySync.setPlaylistSaved(context, playlistId, next)
    }

    fun loadPlaylist(playlistId: String) {
        if (playlistKey == playlistId) {
            checkSaved(playlistId)
            return
        }
        playlistKey = playlistId
        checkSaved(playlistId)
        val cachedAlbum = com.music.spotui.data.preferences.getCachedPlaylistAlbum(context, playlistId)
        if (cachedAlbum != null) _playlist.value = Response.Success(cachedAlbum)
        val cachedSongs = com.music.spotui.data.preferences.getCachedPlaylistSongs(context, playlistId)
        if (cachedSongs.isNotEmpty()) _songs.value = Response.Success(cachedSongs)

        viewModelScope.launch(Dispatchers.IO) {
            repository.providePlaylist(playlistId).collect {
                if (it is Response.Error && _playlist.value is Response.Success) return@collect
                _playlist.value = it
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.providePlaylistSongs(playlistId).collect {
                if (it is Response.Error && _songs.value is Response.Success) return@collect
                _songs.value = it
            }
        }
    }

    fun editPlaylist(playlistId: String, newName: String, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch(Dispatchers.IO) {
        com.music.spotui.data.api.SpotifySync.editPlaylist(context, playlistId, newName) { ok ->
            if (ok) {
                val curAlbum = _playlist.value
                if (curAlbum is Response.Success) {
                    val updated = curAlbum.data.copy(name = newName)
                    _playlist.value = Response.Success(updated)
                    com.music.spotui.data.preferences.cachePlaylistData(context, playlistId, updated, emptyList())
                }
            }
            viewModelScope.launch(Dispatchers.Main) { onDone(ok) }
        }
    }

    fun deletePlaylist(playlistId: String, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch(Dispatchers.IO) {
        com.music.spotui.data.api.SpotifySync.deletePlaylist(context, playlistId) { ok ->
            if (ok) {
                com.music.spotui.data.preferences.setPlaylistSavedInPref(context, playlistId, false)
            }
            viewModelScope.launch(Dispatchers.Main) { onDone(ok) }
        }
    }

    fun setPlaylistPublic(playlistId: String, isPublic: Boolean, onDone: (Boolean) -> Unit = {}) = viewModelScope.launch(Dispatchers.IO) {
        com.music.spotui.data.api.SpotifySync.setPlaylistPublic(context, playlistId, isPublic) { ok ->
            viewModelScope.launch(Dispatchers.Main) { onDone(ok) }
        }
    }
}
