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
class AlbumViewModel @Inject constructor(
    private val repository: AppRepository,
    private val currentSongState: CurrentSongState,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) :  ViewModel() {

    val currentSongPlayingState: State<Boolean> get() = currentSongState.playingState

    val currentSongId: State<Int> get() = currentSongState.songId

    private val _songs : MutableStateFlow<Response<List<SongsModel>>> = MutableStateFlow(Response.Loading())
    val songs : StateFlow<Response<List<SongsModel>>> = _songs

    private val _albums : MutableStateFlow<Response<List<AlbumsModel>>> = MutableStateFlow(Response.Loading())
    val albums : StateFlow<Response<List<AlbumsModel>>> = _albums

    val queue: State<List<SongsModel>> get() = currentSongState.queue

    fun updateQueue(songs: List<SongsModel>) = currentSongState.updateQueue(songs)

    fun startShuffled(songs: List<SongsModel>) = currentSongState.startShuffled(songs)

    /** Pause/resume global playback (the header play button stays visible while playing). */
    fun setPlaying(playing: Boolean) {
        if (playing) com.music.spotui.di.SongPlayer.play() else com.music.spotui.di.SongPlayer.pause()
        currentSongState.updatePlayingState(playing)
    }

    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId: Int, songIndex : Int = 0, album : String = "") {
        currentSongState.updateSongState(coverUri, title, singer, playingState, songId, songIndex, album)
    }

    fun updatePlaybackContext(uri: String?) = currentSongState.updatePlaybackContextUri(uri)

    fun getAlbumContextUri(): String? {
        val key = albumKey ?: return null
        val id = com.music.spotui.data.preferences.getCachedAlbumId(context, key) ?: return null
        return "spotify:album:$id"
    }

    val likeState = currentSongState.likeState

    fun updateLikeState(likeState : Boolean){
        currentSongState.updateLikeState(likeState)
    }
    init {
        fetchAlbums()
    }

    private var albumKey: String? = null

    private val _isAlbumSaved = MutableStateFlow(false)
    val isAlbumSaved: StateFlow<Boolean> = _isAlbumSaved

    fun checkAlbumSaved() {
        val key = albumKey ?: return
        val id = com.music.spotui.data.preferences.getCachedAlbumId(context, key) ?: return
        _isAlbumSaved.value = com.music.spotui.data.preferences.isAlbumSavedInPref(context, id)
    }

    fun toggleSaveAlbum() {
        val key = albumKey ?: return
        val id = com.music.spotui.data.preferences.getCachedAlbumId(context, key) ?: return
        val next = !_isAlbumSaved.value
        _isAlbumSaved.value = next
        com.music.spotui.data.preferences.setAlbumSavedInPref(context, id, next)
        com.music.spotui.data.api.SpotifySync.setAlbumSaved(context, id, next)
    }

    /** Loads the tracks for a specific album (resolved via Spotify search). */
    fun loadAlbumSongs(name: String, artist: String = "") {
        val key = "$name|$artist"
        if (albumKey == key) {
            checkAlbumSaved()
            return
        }
        albumKey = key
        checkAlbumSaved()
        val cached = com.music.spotui.data.preferences.getCachedAlbumSongs(context, key)
        if (cached.isNotEmpty()) _songs.value = Response.Success(cached)

        viewModelScope.launch(Dispatchers.IO) {
            repository.provideAlbumSongs(name, artist).collect { songs ->
                checkAlbumSaved()
                if (songs is Response.Error && _songs.value is Response.Success) return@collect
                _songs.value = songs as Response<List<SongsModel>>
            }
        }
    }

    private fun fetchAlbums() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideAlbums().collect{ album ->
            _albums.value = album as Response<List<AlbumsModel>>
        }
    }


}