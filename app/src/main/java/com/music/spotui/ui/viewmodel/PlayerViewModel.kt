package com.music.spotui.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(private val currentSongState: CurrentSongState, private val repository: AppRepository) : ViewModel(){

    val currentSongTitle: State<String> get() = currentSongState.title
    val currentSongSinger: State<String> get() = currentSongState.singer
    val currentSongCoverUri: State<String> get() = currentSongState.coverUri
    val currentSongPlayingState: State<Boolean> get() = currentSongState.playingState
    val currentSongIndex : State<Int> get() = currentSongState.songIndex
    val currentSongAlbum : State<String> get() = currentSongState.album

    val currentSongId : State<Int> get() = currentSongState.songId

    val queue : State<List<SongsModel>> get() = currentSongState.queue

    fun updateQueue(songs: List<SongsModel>) = currentSongState.updateQueue(songs)

    /** Insert a track right after the one currently playing ("Play next"). */
    fun playNext(song: SongsModel) {
        val q = currentSongState.queue.value.toMutableList()
        q.removeAll { it.id == song.id }
        val cur = q.indexOfFirst { it.id == currentSongId.value }
        val insertAt = (if (cur >= 0) cur + 1 else 0).coerceAtMost(q.size)
        q.add(insertAt, song)
        currentSongState.updateQueue(q)
    }

    /** Append a track to the end of the queue ("Add to queue"). */
    fun addToQueue(song: SongsModel) {
        val q = currentSongState.queue.value
        if (q.any { it.id == song.id }) return
        currentSongState.updateQueue(q + song)
    }

    /** Reorder the queue, moving the track at [from] to [to] (both absolute indices). */
    fun moveQueueItem(from: Int, to: Int) {
        val q = currentSongState.queue.value.toMutableList()
        if (from !in q.indices || to !in q.indices || from == to) return
        q.add(to, q.removeAt(from))
        currentSongState.updateQueue(q)
    }

    /** Remove a track from the queue entirely. */
    fun removeFromQueue(song: SongsModel) {
        val q = currentSongState.queue.value.toMutableList()
        q.removeAll { it.id == song.id }
        currentSongState.updateQueue(q)
    }


    val shuffleState = currentSongState.shuffle
    val repeatState = currentSongState.repeat
    val likeState = currentSongState.likeState


    private val _songs : MutableStateFlow<Response<List<SongsModel>>> = MutableStateFlow(Response.Loading())
    val songs : StateFlow<Response<List<SongsModel>>> = _songs

    val playingArtist by mutableStateOf(currentSongSinger.value)

    init {
        fetchSongs()
    }


    //val songsResponse = (songs.value as Response.Success).data

    // Resolve where we currently are in the queue. The stored index can be stale
    // (e.g. queue swapped out), so match by song id first and fall back to the index.
    private fun currentPositionIn(queueSongs: List<SongsModel>): Int {
        val byId = queueSongs.indexOfFirst { it.id == currentSongId.value }
        if (byId >= 0) return byId
        return currentSongIndex.value.coerceIn(0, queueSongs.size - 1)
    }

    // ── Autoplay radio (Spotify recommendations) ──
    // When the queue nears its end, fetch Spotify-recommended tracks seeded by what's
    // playing and append them, so music keeps going like Spotify's autoplay instead of
    // looping the same list. On by default; can be turned off via [autoplayRadioEnabled].
    var autoplayRadioEnabled = true
    @Volatile private var radioLoading = false

    private fun maybeExtendRadio(queueSongs: List<SongsModel>, cur: Int) {
        if (!autoplayRadioEnabled || radioLoading) return
        // Only start fetching when we're within one track of the end.
        if (cur < queueSongs.size - 2) return
        val seeds = queueSongs.takeLast(5)
            .mapNotNull { it.spotifyTrackId.ifBlank { null } }
            .distinct()
        if (seeds.isEmpty()) return
        radioLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val recs = repository.provideRecommendations(seeds)
                val existing = currentSongState.queue.value
                val existingIds = existing.map { it.id }.toSet()
                val fresh = recs.filter { it.id !in existingIds }
                if (fresh.isNotEmpty()) currentSongState.updateQueue(existing + fresh)
            } finally {
                radioLoading = false
            }
        }
    }

    // End-of-track autoplay fires from the UI on every recomposition while the
    // finished track's position still equals its duration (the next stream takes
    // seconds to resolve), so without a debounce it advances 30+ tracks in a
    // burst. Allow ONE auto-advance, then hold until the new track takes over.
    @Volatile private var lastAutoAdvanceMs = 0L

    fun autoAdvance(queueSongs: List<SongsModel>, context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAutoAdvanceMs < 4000) return
        lastAutoAdvanceMs = now
        playNextSongs(queueSongs, context)
    }

    // Function to play the next song in the album
    fun playNextSongs(queueSongs : List<SongsModel>, context: Context) {
        if (queueSongs.isEmpty()) return
        // A crossfade is already advancing the queue itself — don't double-skip.
        if (SongPlayer.isCrossfadeActive()) return
        val cur = currentPositionIn(queueSongs)
        // Top up the queue with Spotify recommendations as we approach the end.
        maybeExtendRadio(queueSongs, cur)
        if (cur >= queueSongs.size - 1 && autoplayRadioEnabled) {
            // End of the queue (e.g. a single). Don't loop back to the start —
            // wait for the radio fetch kicked off above to append tracks and
            // continue into them, like Spotify's autoplay.
            continueIntoRadio(queueSongs, context)
            return
        }
        val nextIdx = if (cur < queueSongs.size - 1) cur + 1 else 0
        val nextSong = queueSongs[nextIdx]
        updateSongState(
            nextSong.coverUri,
            nextSong.title,
            nextSong.singer,
            true,
            nextSong.id,
            nextIdx,
            nextSong.album
        )
        SongPlayer.playSong(nextSong.url, context)
    }

    /**
     * Opens the artist page for a track: resolves the EXACT artist (name + id)
     * from the track's Spotify id when available, so a display name like "RAM"
     * can't fuzzy-match to "Rammstein". Falls back to the display name.
     */
    // Spotify Canvas (looping video) URL for the current track, or null. Fetched
    // per track; null means no canvas / not resolved yet.
    private val _canvasUrl = mutableStateOf<String?>(null)
    val canvasUrl: State<String?> get() = _canvasUrl
    @Volatile private var canvasRequestId: String = ""

    fun loadCanvas(trackId: String) {
        _canvasUrl.value = null
        canvasRequestId = trackId
        if (trackId.isBlank()) return
        viewModelScope.launch {
            val url = withContext(Dispatchers.IO) { repository.provideCanvasUrl(trackId) }
            // Ignore a slow fetch for a track the user already skipped past.
            if (canvasRequestId == trackId) _canvasUrl.value = url
        }
    }

    fun goToArtist(trackId: String, fallbackName: String, navigate: (route: String) -> Unit) {
        viewModelScope.launch {
            val route = withContext(Dispatchers.IO) {
                val artist = if (trackId.isNotBlank())
                    com.metrolist.spotify.Spotify.track(trackId).getOrNull()?.artists?.firstOrNull()
                else null
                artistRoute(
                    artist?.name?.ifBlank { null } ?: fallbackName.substringBefore(",").trim(),
                    artist?.id.orEmpty(),
                )
            }
            navigate(route)
        }
    }

    @Volatile private var awaitingRadioContinue = false

    /** Waits (max ~10s) for the autoplay radio to extend the queue past
     *  [queueSongs] and plays the first appended track; falls back to looping
     *  the queue if no radio tracks arrive. */
    private fun continueIntoRadio(queueSongs: List<SongsModel>, context: Context) {
        if (awaitingRadioContinue) return
        awaitingRadioContinue = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repeat(40) {
                    val q = currentSongState.queue.value
                    if (q.size > queueSongs.size) {
                        val next = q[queueSongs.size]
                        withContext(Dispatchers.Main) {
                            updateSongState(next.coverUri, next.title, next.singer, true, next.id, queueSongs.size, next.album)
                            SongPlayer.playSong(next.url, context)
                        }
                        return@launch
                    }
                    if (!radioLoading) {
                        val first = queueSongs.first()
                        withContext(Dispatchers.Main) {
                            updateSongState(first.coverUri, first.title, first.singer, true, first.id, 0, first.album)
                            SongPlayer.playSong(first.url, context)
                        }
                        return@launch
                    }
                    delay(250L)
                }
                // Radio never arrived (offline / no seed id) — loop like before.
                val first = queueSongs.first()
                withContext(Dispatchers.Main) {
                    updateSongState(first.coverUri, first.title, first.singer, true, first.id, 0, first.album)
                    SongPlayer.playSong(first.url, context)
                }
            } finally {
                awaitingRadioContinue = false
            }
        }
    }

    /**
     * Play the track at an absolute [index] in [queueSongs]. Used by the now-playing
     * swipe pager, where the artwork follows the finger and settles on a chosen page.
     * No-op if [index] is already the current track (prevents a feedback replay when
     * the pager is merely snapping to reflect an external track change).
     */
    fun playSongAt(queueSongs: List<SongsModel>, index: Int, context: Context) {
        if (index !in queueSongs.indices) return
        val song = queueSongs[index]
        if (song.id == currentSongId.value) return
        maybeExtendRadio(queueSongs, index)
        updateSongState(song.coverUri, song.title, song.singer, true, song.id, index, song.album)
        SongPlayer.playSong(song.url, context)
    }

    // Function to play the previous song in the album
    fun playPreviousSong(queueSongs : List<SongsModel>, context: Context) {
        if (queueSongs.isEmpty()) return
        val cur = currentPositionIn(queueSongs)
        val prevIdx = if (cur > 0) cur - 1 else queueSongs.size - 1
        val previousSong = queueSongs[prevIdx]
        updateSongState(previousSong.coverUri, previousSong.title, previousSong.singer, true, previousSong.id, prevIdx, previousSong.album)
        SongPlayer.playSong(previousSong.url, context)
    }

    private fun fetchSongs() = viewModelScope.launch(Dispatchers.IO) {

        repository.provideSongs().collect { songs ->
            _songs.value = songs as Response<List<SongsModel>>

        }
    }
    fun formatDuration(durationMillis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMillis) - TimeUnit.MINUTES.toSeconds(minutes)
        return String.format("%01d:%02d", minutes, seconds)
    }
    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId : Int, songIndex : Int = 0, album : String = "") {
        currentSongState.updateSongState(coverUri, title, singer, playingState, songId, songIndex, album)
    }

    fun updateShuffleState(shuffleState : Boolean){
        currentSongState.updateShuffleState(shuffleState)
    }
    fun updateRepeatState(repeatState : Boolean){
        currentSongState.updateRepeatState(repeatState)
    }
    fun updateLikeState(likeState : Boolean){
        currentSongState.updateLikeState(likeState)
    }
}
