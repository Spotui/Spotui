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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
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
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.di.Palette
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.PlaylistViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistScreen(navController: NavController, playlistId: String, playlistName: String = "") {

    val playlistViewModel: PlaylistViewModel = hiltViewModel()
    val songsResp by playlistViewModel.songs.collectAsState()
    val playlistResp by playlistViewModel.playlist.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(playlistId) {
        playlistViewModel.loadPlaylist(playlistId)
    }

    val songs = (songsResp as? Response.Success)?.data.orEmpty()
    val playlist = (playlistResp as? Response.Success)?.data
        ?: AlbumsModel(
            id = playlistId.hashCode() and 0x7fffffff,
            artists = "",
            coverUri = songs.firstOrNull()?.coverUri ?: "",
            name = playlistName,
            time = "",
        )

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
            onDismiss = { menuSong = null },
        )
    }

    // 0 = playlist order, 1 = title A-Z, 2 = title Z-A, 3 = artist A-Z
    var sortOrder by remember { mutableStateOf(0) }
    val isSaved by playlistViewModel.isSaved.collectAsState()
    androidx.compose.runtime.LaunchedEffect(playlistId) {
        playlistViewModel.checkSaved(playlistId)
    }
    var showMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    val isPublic by playlistViewModel.isPublic.collectAsState()
    val canEdit by playlistViewModel.canEdit.collectAsState()

    if (showEditDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showEditDialog = false },
            containerColor = Color(0xFF282828),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = {
                Text("Edit playlist", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    placeholder = { Text("Playlist name", color = Color.Gray) },
                    singleLine = true,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF1ED760),
                        unfocusedBorderColor = Color.Gray,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val name = editName.trim().ifBlank { playlist.name.ifBlank { playlistName } }
                        playlistViewModel.editPlaylist(playlistId, name) { ok ->
                            if (ok) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    android.widget.Toast.makeText(context, "Playlist updated!", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        showEditDialog = false
                    }
                ) {
                    Text("Save", color = Color(0xFF1ED760), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showEditDialog = false }
                ) {
                    Text("Cancel", color = Color.LightGray)
                }
            }
        )
    }

    if (showDeleteDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Color(0xFF282828),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = {
                Text("Delete playlist?", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Text("This will remove '${playlist.name.ifBlank { playlistName }}' from your library and Spotify.", color = Color.LightGray)
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showDeleteDialog = false
                        playlistViewModel.deletePlaylist(playlistId) { ok ->
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (ok) {
                                    android.widget.Toast.makeText(context, "Playlist deleted", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                navController.navigateUp()
                            }
                        }
                    }
                ) {
                    Text("Delete", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showDeleteDialog = false }
                ) {
                    Text("Cancel", color = Color.LightGray)
                }
            }
        )
    }
    val displaySongs = when (sortOrder) {
        1 -> songs.sortedBy { it.title.lowercase() }
        2 -> songs.sortedByDescending { it.title.lowercase() }
        3 -> songs.sortedBy { it.singer.lowercase() }
        else -> songs
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        if (songsResp is Response.Loading && playlistResp is Response.Loading) {
            Loader()
            return@Surface
        }

        var dominentColor by remember { mutableStateOf(Color(AppBackground.toArgb())) }
        Palette().extractSecondColorFromCoverUrl(context = context, playlist.coverUri) { color ->
            dominentColor = color
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
                    title = { Text(text = "") },
                    actions = {
                        if (canEdit) {
                            Box {
                                Icon(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) { showMenu = true },
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More options",
                                    tint = Color.White
                                )
                                androidx.compose.material3.DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                    modifier = Modifier.background(Color(0xFF282828))
                                ) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("Edit playlist", color = Color.White) },
                                        leadingIcon = {
                                            Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White)
                                        },
                                        onClick = {
                                            showMenu = false
                                            editName = playlist.name.ifBlank { playlistName }
                                            showEditDialog = true
                                        }
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (isPublic) "Make private" else "Make public",
                                                color = Color.White
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = if (isPublic) Color(0xFF1ED760) else Color.White
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            val nextPublic = !isPublic
                                            playlistViewModel.setPlaylistPublic(playlistId, nextPublic) { ok ->
                                                if (ok) {
                                                    val msg = if (nextPublic) "Playlist is now public" else "Playlist is now private"
                                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        }
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("Delete playlist", color = Color(0xFFFF5252)) },
                                        leadingIcon = {
                                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF5252))
                                        },
                                        onClick = {
                                            showMenu = false
                                            showDeleteDialog = true
                                        }
                                    )
                                }
                            }
                        }
                    }
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
                            // Minimum, not fixed: a long playlist description used to
                            // overflow the fixed height and squash the play button.
                            .heightIn(min = 440.dp)
                            .padding(bottom = 8.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(dominentColor, Color(AppBackground.toArgb())),
                                    startY = -100f,
                                ),
                            ),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Spacer(modifier = Modifier.padding(25.dp))

                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            GlideImage(
                                modifier = Modifier.size(230.dp),
                                model = playlist.coverUri,
                                failure = placeholder(R.drawable.placeholder),
                                contentDescription = "",
                            )
                        }
                        Spacer(modifier = Modifier.padding(5.dp))
                        Text(
                            modifier = Modifier.padding(20.dp, 5.dp, 0.dp, 0.dp),
                            text = playlist.name.ifBlank { playlistName },
                            color = Color.White,
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (playlist.time.isNotBlank()) {
                            Text(
                                modifier = Modifier.padding(20.dp, 4.dp, 20.dp, 0.dp),
                                text = playlist.time,
                                color = Color.Gray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (playlist.artists.isNotBlank()) {
                            Text(
                                modifier = Modifier.padding(20.dp, 4.dp, 0.dp, 0.dp),
                                text = "Playlist • ${playlist.artists}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        if (songs.isNotEmpty()) {
                            val sortLabels = listOf("Custom order", "Title A-Z", "Title Z-A", "Artist")
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp, 0.dp, 20.dp, 2.dp),
                            ) {
                                Text(
                                    text = sortLabels[sortOrder],
                                    color = if (sortOrder == 0) Color.Gray else Color(AppPalette.toArgb()),
                                    fontSize = 12.sp,
                                    modifier = Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { sortOrder = (sortOrder + 1) % 4 },
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = if (sortOrder == 0) Color.Gray else Color(AppPalette.toArgb()),
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) { sortOrder = (sortOrder + 1) % 4 },
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .padding(20.dp, 0.dp)
                        ) {
                            // Download the whole playlist (all tracks) for offline playback.
                            var playlistDownloaded by remember(songs) {
                                mutableStateOf(songs.isNotEmpty() && SongPlayer.allDownloaded(songs, context))
                            }
                            if (songs.isNotEmpty()) {
                                Icon(
                                    imageVector = if (playlistDownloaded)
                                        Icons.Default.CheckCircle else ImageVector.vectorResource(R.drawable.ic_download),
                                    tint = if (playlistDownloaded) Color(AppPalette.toArgb()) else Color.White,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            if (!playlistDownloaded) {
                                                SongPlayer.downloadAll(songs, context)
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Downloading ${songs.size} tracks…",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        },
                                    contentDescription = "Download playlist",
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                // Shuffle-play: start the playlist in random order.
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_player_shuffle),
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            playlistViewModel.startShuffled(displaySongs)?.let { first ->
                                                SongPlayer.playSong(first.url, context)
                                                playlistViewModel.updateSongState(
                                                    first.coverUri,
                                                    first.title,
                                                    first.singer,
                                                    true,
                                                    first.id,
                                                    0,
                                                    playlist.name,
                                                )
                                            }
                                        },
                                    contentDescription = "Shuffle play",
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                // Save / follow playlist to library
                                Icon(
                                    imageVector = if (isSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    tint = if (isSaved) Color(0xFF1ED760) else Color.White,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            playlistViewModel.toggleSavePlaylist(playlistId)
                                            val msg = if (!isSaved) "Added to Your Library" else "Removed from Your Library"
                                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                    contentDescription = if (isSaved) "Remove from library" else "Add to library",
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                            }
                            // Always visible: pause when playing, resume when this
                            // list's track is paused, otherwise start from the top.
                            if (songs.isNotEmpty()) {
                                val playing = playlistViewModel.currentSongPlayingState.value
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
                                            val isOnline = com.music.spotui.data.preferences.isNetworkAvailable(context)
                                            val playableSongs = if (isOnline) displaySongs else displaySongs.filter {
                                                com.music.spotui.data.preferences.isDownloaded(context, it.id.toString()) ||
                                                com.music.spotui.data.preferences.downloadedPathForQuery(context, it.url) != null
                                            }
                                            if (playableSongs.isEmpty()) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "No downloaded songs available in this playlist",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                                return@clickable
                                            }
                                            when {
                                                playing -> playlistViewModel.setPlaying(false)
                                                playableSongs.any { it.id == playlistViewModel.currentSongId.value } ->
                                                    playlistViewModel.setPlaying(true)
                                                else -> {
                                                    playlistViewModel.updatePlaybackContext("spotify:playlist:$playlistId")
                                                    playlistViewModel.updateQueue(playableSongs)
                                                    SongPlayer.playSong(playableSongs[0].url, context)
                                                    playlistViewModel.updateSongState(
                                                        playableSongs[0].coverUri,
                                                        playableSongs[0].title,
                                                        playableSongs[0].singer,
                                                        true,
                                                        playableSongs[0].id,
                                                        0,
                                                        playlist.name
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

                itemsIndexed(displaySongs, key = { _, song -> song.id }) { index, song ->
                    val isDownloaded = remember(song.id) {
                        com.music.spotui.data.preferences.isDownloaded(context, song.id.toString()) ||
                        com.music.spotui.data.preferences.downloadedPathForQuery(context, song.url) != null
                    }
                    val isOnline = remember { com.music.spotui.data.preferences.isNetworkAvailable(context) }
                    val isPlayable = isOnline || isDownloaded

                    val isCurrent = song.id == playlistViewModel.currentSongId.value
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
                                    val playableQueue = if (isOnline) displaySongs else displaySongs.filter {
                                        com.music.spotui.data.preferences.isDownloaded(context, it.id.toString()) ||
                                        com.music.spotui.data.preferences.downloadedPathForQuery(context, it.url) != null
                                    }
                                    playlistViewModel.updatePlaybackContext("spotify:playlist:$playlistId")
                                    playlistViewModel.updateQueue(playableQueue)
                                    SongPlayer.playSong(song.url, context)
                                    val targetIdx = playableQueue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                                    playlistViewModel.updateSongState(
                                        song.coverUri,
                                        song.title,
                                        song.singer,
                                        true,
                                        song.id,
                                        targetIdx,
                                        playlist.name
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
