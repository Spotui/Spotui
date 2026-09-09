package com.music.spotui.ui.viewmodel

import androidx.compose.runtime.State
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SearchResults
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(private val repository: AppRepository, private val currentSongState: CurrentSongState) : ViewModel() {

    private val _songs : MutableStateFlow<Response<List<SongsModel>>> = MutableStateFlow(Response.Loading())
    val songs : StateFlow<Response<List<SongsModel>>> = _songs

    private val _results : MutableStateFlow<Response<SearchResults>> = MutableStateFlow(Response.Success(SearchResults()))
    val results : StateFlow<Response<SearchResults>> = _results

    val likeState = currentSongState.likeState

    val currentSongId: State<Int> get() = currentSongState.songId


    fun updateLikeState(likeState : Boolean){
        currentSongState.updateLikeState(likeState)
    }

    private var searchJob: Job? = null

    init {
        // Start empty — results come from live searches, not the rate-limited
        // personalized top-tracks feed (which returned 429 and blanked the screen).
        _songs.value = Response.Success(emptyList())
    }

    fun updateQueue(songs: List<SongsModel>) = currentSongState.updateQueue(songs)

    /**
     * Start playback of a single search result as a *radio*, the way Spotify does:
     * the queue becomes just this track, then Spotify-recommended tracks (seeded from
     * it) are appended as they load — instead of queuing the rest of the search list
     * (which made playback "just go down the search results"). The append is skipped
     * if the user has since started something else.
     */
    fun startRadioFromSong(song: SongsModel) {
        if (song.spotifyTrackId.isNotBlank()) {
            currentSongState.updatePlaybackContextUri("spotify:station:track:${song.spotifyTrackId}")
        }
        currentSongState.updateQueue(listOf(song))
        val seed = song.spotifyTrackId
        if (seed.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val recs = repository.provideRecommendations(listOf(seed))
            val current = currentSongState.queue.value
            // Only extend if this radio is still the active queue (user didn't tap away).
            if (current.size == 1 && current.first().id == song.id) {
                val fresh = recs.filter { it.id != song.id }
                if (fresh.isNotEmpty()) currentSongState.updateQueue(current + fresh)
            }
        }
    }

    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId : Int, songIndex : Int = 0, album : String = "") {
        currentSongState.updateSongState(coverUri, title, singer, playingState, songId, songIndex, album)
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _results.value = Response.Success(SearchResults())
            _songs.value = Response.Success(emptyList())
            return
        }
        _results.value = Response.Loading()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(150) // short debounce for snappy real-time results
            repository.searchEverything(query).collect { result ->
                _results.value = result
                // Keep the legacy songs flow in sync for any remaining consumers.
                _songs.value = when (result) {
                    is Response.Success -> Response.Success(result.data.songs)
                    is Response.Error -> Response.Error(result.error)
                    is Response.Loading -> Response.Loading()
                }
            }
        }
    }
}