package com.music.spotui

import android.app.Application
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import com.music.spotui.data.api.Api
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale
import timber.log.Timber

@HiltAndroidApp
class MyApplication : Application(){
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        // For non-DI singletons (LyricsApi) that need a Context to refresh the token.
        @JvmStatic
        lateinit var instance: MyApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (BuildConfig.DEBUG && Timber.forest().isEmpty()) {
            Timber.plant(Timber.DebugTree())
        }
        // Surface Spotify REST/GQL logs to logcat for diagnosis.
        com.metrolist.spotify.Spotify.logger = { level, msg ->
            android.util.Log.d("SpotifyREST", "[$level] $msg")
        }
        com.metrolist.spotify.SpotifyCanvas.setLogger { level, msg ->
            android.util.Log.d("SpotifyCanvas", "[$level] $msg")
        }
        // Required by the ported YouTube streaming flow (cipher/PoToken WebViews).
        CipherDeobfuscator.initialize(this)

        // Locale + visitorData must be set or the player can't mint a PoToken,
        // and googlevideo rejects the stream URL with HTTP 403 (tracks stuck at 0:00).
        val locale = Locale.getDefault()
        YouTube.locale = YouTubeLocale(
            gl = locale.country.takeIf { it.isNotBlank() } ?: "US",
            hl = locale.language.takeIf { it.isNotBlank() } ?: "en",
        )
        val hasValidSavedLogin = com.music.spotui.data.preferences.isYoutubeLoggedIn(this)
        val savedCookie = com.music.spotui.data.preferences.getYoutubeCookie(this)
        val savedVisitorData = com.music.spotui.data.preferences.getYoutubeVisitorData(this)
        val savedDataSyncId = com.music.spotui.data.preferences.getYoutubeDataSyncId(this)
        YouTube.cookie = savedCookie.takeIf { hasValidSavedLogin && it.isNotBlank() }
        YouTube.visitorData = savedVisitorData.takeIf { it.isNotBlank() }
        YouTube.dataSyncId = savedDataSyncId.takeIf { it.isNotBlank() }
        // Meld searches/browses with the same signed-in account used by /player.
        // Leaving this false makes search anonymous and can return the clean edit.
        YouTube.useLoginForBrowse = hasValidSavedLogin
        if (YouTube.visitorData.isNullOrBlank()) {
            appScope.launch {
                YouTube.visitorData = YouTube.visitorData().getOrNull() ?: YouTube.visitorData
            }
        }

        // Warm the Home feed cache so the first navigation to Home is instant.
        // No-op (gracefully) until a Spotify token is available.
        val api = Api(this)
        appScope.launch { runCatching { api.getHomeFeed().collect {} } }
        appScope.launch { runCatching { api.getAlbums().collect {} } }
        appScope.launch { runCatching { api.getArtists().collect {} } }
    }
}
