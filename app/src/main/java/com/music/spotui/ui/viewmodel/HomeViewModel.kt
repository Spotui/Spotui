package com.music.spotui.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: AppRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
)  : ViewModel() {


    private val _albums : MutableStateFlow<Response<List<AlbumsModel>>> = MutableStateFlow(Response.Loading())
    val albums : StateFlow<Response<List<AlbumsModel>>> = _albums

    private val _artists : MutableStateFlow<Response<List<ArtistsModel>>> = MutableStateFlow(Response.Loading())
    val artists : StateFlow<Response<List<ArtistsModel>>> = _artists

    private val _home : MutableStateFlow<Response<HomeFeedModel>> = MutableStateFlow(Response.Loading())
    val home : StateFlow<Response<HomeFeedModel>> = _home


    init {
        com.music.spotui.data.preferences.getCachedHomeFeed(context)?.let {
            _home.value = Response.Success(it)
        }
        fetchHome()
        fetchArtists()
        fetchAlbums()
    }

    private fun fetchHome() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideHomeFeed().collect { feed ->
            if (feed is Response.Error && _home.value is Response.Success) return@collect
            _home.value = feed
        }
    }

    private fun fetchAlbums() = viewModelScope.launch(Dispatchers.IO) {
            repository.provideAlbums().collect{ album ->
                _albums.value = album as Response<List<AlbumsModel>>
            }
    }

    private fun fetchArtists() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideArtists().collect { artist ->
            _artists.value = artist as Response<List<ArtistsModel>>
        }
    }



}