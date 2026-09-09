package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.api.Response
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.LikedSongsViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun LikedSongsScreen(navController: NavController) {

    val likedSongsViewModel: LikedSongsViewModel = hiltViewModel()
    val songsResp by likedSongsViewModel.songs.collectAsState()
    val context = LocalContext.current

    val songs = (songsResp as? Response.Success)?.data.orEmpty()

    LaunchedEffect(songs) {
        if (songs.isNotEmpty()) {
            SongPlayer.prefetchList(songs.map { it.url }, context)
        }
    }

    var menuSong by remember { mutableStateOf<com.music.spotui.data.entity.SongsModel?>(null) }
    menuSong?.let { sel ->
        com.music.spotui.ui.components.SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = {
                // If the song was unliked in the menu, drop it from the list right
                // away (the Spotify-side removal is already in flight).
                if (!com.music.spotui.data.preferences.isSongLiked(context, sel.id.toString())) {
                    likedSongsViewModel.removeLocally(sel.id)
                }
                menuSong = null
            },
        )
    }

    // Spotify's Liked Songs uses a purple → dark gradient.
    val likedColor = Color(0xFF5038A0)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        if (songsResp is Response.Loading) {
            Loader()
            return@Surface
        }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    modifier = Modifier.padding(16.dp, 0.dp),
                    navigationIcon = {
                        Icon(
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { navController.navigateUp() },
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "",
                            tint = Color.White
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                    ),
                    title = { Text(text = "") }
                )
            }
        ) { innerPadding ->
            LazyColumn(
                contentPadding = innerPadding,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(AppBackground.toArgb()))
                    .consumeWindowInsets(innerPadding)
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(440.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(likedColor, Color(AppBackground.toArgb())),
                                    startY = -100f,
                                ),
                            ),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Spacer(modifier = Modifier.padding(25.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(230.dp)
                                    .background(
                                        brush = Brush.linearGradient(
                                            colors = listOf(Color(0xFF8E6FE0), Color(0xFF3B2A82)),
                                        )
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Favorite,
                                    contentDescription = "",
                                    tint = Color.White,
                                    modifier = Modifier.size(90.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.padding(5.dp))
                        Text(
                            modifier = Modifier.padding(20.dp, 5.dp, 0.dp, 0.dp),
                            text = "Liked Songs",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            modifier = Modifier.padding(20.dp, 4.dp, 20.dp, 0.dp),
                            text = "${songs.size} songs",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Row(
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .padding(20.dp, 0.dp)
                        ) {
                            // Download all liked songs for offline playback.
                            var likedDownloaded by remember(songs) {
                                mutableStateOf(songs.isNotEmpty() && SongPlayer.allDownloaded(songs, context))
                            }
                            if (songs.isNotEmpty()) {
                                Icon(
                                    imageVector = if (likedDownloaded)
                                        Icons.Default.CheckCircle else ImageVector.vectorResource(R.drawable.ic_download),
                                    tint = if (likedDownloaded) Color(AppPalette.toArgb()) else Color.White,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            if (!likedDownloaded) {
                                                SongPlayer.downloadAll(songs, context)
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Downloading ${songs.size} tracks…",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        },
                                    contentDescription = "Download liked songs",
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                // Shuffle-play: start liked songs in random order.
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_player_shuffle),
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            likedSongsViewModel.startShuffled(songs)?.let { first ->
                                                SongPlayer.playSong(first.url, context)
                                                likedSongsViewModel.updateSongState(
                                                    first.coverUri,
                                                    first.title,
                                                    first.singer,
                                                    true,
                                                    first.id,
                                                    0,
                                                    "Liked Songs",
                                                )
                                            }
                                        },
                                    contentDescription = "Shuffle play",
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                            }
                            // Always visible: pause when playing, resume when this
                            // list's track is paused, otherwise start from the top.
                            if (songs.isNotEmpty()) {
                                val isOnline = com.music.spotui.data.preferences.isNetworkAvailable(context)
                                val playableQueue = if (isOnline) songs else songs.filter {
                                    com.music.spotui.data.preferences.isDownloaded(context, it.id.toString()) ||
                                    com.music.spotui.data.preferences.downloadedPathForQuery(context, it.url) != null
                                }
                                val playing = likedSongsViewModel.currentSongPlayingState.value
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(100.dp))
                                        .background(Color.White)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            if (playableQueue.isEmpty()) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "No downloaded songs in Liked Songs",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                                return@clickable
                                            }
                                            when {
                                                playing -> likedSongsViewModel.setPlaying(false)
                                                playableQueue.any { it.id == likedSongsViewModel.currentSongId.value } ->
                                                    likedSongsViewModel.setPlaying(true)
                                                else -> {
                                                    likedSongsViewModel.updatePlaybackContext("spotify:collection:tracks")
                                                    likedSongsViewModel.updateQueue(playableQueue)
                                                    SongPlayer.playSong(playableQueue[0].url, context)
                                                    likedSongsViewModel.updateSongState(
                                                        playableQueue[0].coverUri,
                                                        playableQueue[0].title,
                                                        playableQueue[0].singer,
                                                        true,
                                                        playableQueue[0].id,
                                                        0,
                                                        "Liked Songs"
                                                    )
                                                }
                                            }
                                        }
                                ) {
                                    Icon(
                                        modifier = Modifier.size(25.dp),
                                        tint = Color.Black,
                                        painter = painterResource(
                                            id = if (playing) R.drawable.ic_playing else R.drawable.play_svgrepo_com,
                                        ),
                                        contentDescription = if (playing) "Pause" else "Play"
                                    )
                                }
                            }
                        }
                    }
                }

                itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                    val isDownloaded = remember(song.id) {
                        com.music.spotui.data.preferences.isDownloaded(context, song.id.toString()) ||
                        com.music.spotui.data.preferences.downloadedPathForQuery(context, song.url) != null
                    }
                    val isOnline = remember { com.music.spotui.data.preferences.isNetworkAvailable(context) }
                    val isPlayable = isOnline || isDownloaded

                    val isCurrent = song.id == likedSongsViewModel.currentSongId.value
                    val currentColor = when {
                        isCurrent -> Color(AppPalette.toArgb())
                        !isPlayable -> Color(0xFF666666)
                        else -> Color.White
                    }
                    val subtitleColor = if (!isPlayable) Color(0xFF444444) else Color.Gray

                    Row(
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp, 8.dp)
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onLongClick = { menuSong = song },
                                onClick = {
                                    if (!isPlayable) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Download this song to play it offline",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        return@combinedClickable
                                    }
                                    val playableQueue = if (isOnline) songs else songs.filter {
                                        com.music.spotui.data.preferences.isDownloaded(context, it.id.toString()) ||
                                        com.music.spotui.data.preferences.downloadedPathForQuery(context, it.url) != null
                                    }
                                    likedSongsViewModel.updatePlaybackContext("spotify:collection:tracks")
                                    likedSongsViewModel.updateQueue(playableQueue)
                                    SongPlayer.playSong(song.url, context)
                                    val targetIdx = playableQueue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                                    likedSongsViewModel.updateSongState(
                                        song.coverUri,
                                        song.title,
                                        song.singer,
                                        true,
                                        song.id,
                                        targetIdx,
                                        "Liked Songs"
                                    )
                                },
                            )
                    ) {
                        GlideImage(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            model = song.coverUri,
                            failure = placeholder(R.drawable.placeholder),
                            contentScale = ContentScale.Crop,
                            contentDescription = ""
                        )
                        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(
                                text = song.title,
                                color = currentColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isDownloaded) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_download),
                                        contentDescription = "Downloaded",
                                        tint = Color(AppPalette.toArgb()),
                                        modifier = Modifier.size(12.dp).padding(end = 3.dp)
                                    )
                                }
                                Text(
                                    text = song.singer,
                                    color = subtitleColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.padding(80.dp)) }
            }
        }
    }
}
