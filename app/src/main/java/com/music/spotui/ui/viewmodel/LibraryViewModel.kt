package com.music.spotui.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AccountModel
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.preferences.cacheFollowedArtists
import com.music.spotui.data.preferences.cacheLibraryEntries
import com.music.spotui.data.preferences.getCachedFollowedArtists
import com.music.spotui.data.preferences.getCachedLibraryEntries
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: AppRepository,
    @ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    private val _entries: MutableStateFlow<Response<List<LibraryEntry>>> = MutableStateFlow(Response.Loading())
    val entries: StateFlow<Response<List<LibraryEntry>>> = _entries

    private val _account: MutableStateFlow<Response<AccountModel>> = MutableStateFlow(Response.Loading())
    val account: StateFlow<Response<AccountModel>> = _account

    private val _followedArtists: MutableStateFlow<List<com.music.spotui.data.entity.ArtistsModel>> =
        MutableStateFlow(emptyList())
    val followedArtists: StateFlow<List<com.music.spotui.data.entity.ArtistsModel>> = _followedArtists

    init {
        // Seed UI with the last-seen library immediately so it's visible offline.
        val cached = getCachedLibraryEntries(context)
        if (cached.isNotEmpty()) _entries.value = Response.Success(cached)
        val cachedArtists = getCachedFollowedArtists(context)
        if (cachedArtists.isNotEmpty()) _followedArtists.value = cachedArtists

        load()
        loadAccount()
        loadFollowedArtists()
    }

    fun load() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideLibrary().collect { response ->
            if (response is Response.Error) {
                // When offline / error, preserve cached entries if available instead of blowing away the UI
                val cached = getCachedLibraryEntries(context)
                if (cached.isNotEmpty() || _entries.value is Response.Success) {
                    return@collect
                }
            }
            _entries.value = response
            if (response is Response.Success) cacheLibraryEntries(context, response.data)
        }
    }

    private fun loadFollowedArtists() = viewModelScope.launch(Dispatchers.IO) {
        val artists = repository.provideFollowedArtists()
        _followedArtists.value = artists
        if (artists.isNotEmpty()) cacheFollowedArtists(context, artists)
    }

    private fun loadAccount() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideAccount().collect { _account.value = it }
    }
}
