package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
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
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.addLikedAlbumId
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.isAlbumLiked
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedAlbumId
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.di.Palette
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.LikedSongsScreen
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.components.Snackbar
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.AlbumViewModel
import kotlinx.coroutines.delay


@Composable
fun AlbumScreen(navController: NavController, albumName: String, artist: String = "") {


    val albumViewModel : AlbumViewModel = hiltViewModel()
    val songs by albumViewModel.songs.collectAsState()
    val albums by albumViewModel.albums.collectAsState()

    // Load this album's actual tracks from Spotify (by name, disambiguated by artist).
    LaunchedEffect(albumName, artist) {
        albumViewModel.loadAlbumSongs(albumName, artist)
    }

    val context = LocalContext.current




    Log.d("check", albumName.toString())

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        val albumsResponse = (albums as? Response.Success)?.data.orEmpty()
        val songsResponse = (songs as? Response.Success)?.data.orEmpty()

        when {
            albums is Response.Loading && songs is Response.Loading -> {
                Log.d("homeMain", "loading..-albums")
                Loader()
            }

            else -> {
                Log.d("homeMain", "albums ready")
                if (albumName == "Liked Songs"){
                    LikedSongsScreen(albumsResponse, songsResponse, navController, context)
                }
                else{
                    SumUpAlbumScreen(navController = navController,albumViewModel, albumsResponse, songsResponse, albumName, context)
                }
            }
        }
    }

}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun SumUpAlbumScreen(
    navController: NavController,
    albumViewModel: AlbumViewModel,
    albums: List<AlbumsModel>,
    songs: List<SongsModel>,
    albumName: String,
    context: Context
) {
    // `songs` is already this album's track list (loaded by AlbumViewModel).
    val albumSongs: List<SongsModel> = songs

    // Warm the stream cache for the first few tracks so the first tap plays
    // (near-)instantly instead of resolving YouTube on the tap.
    LaunchedEffect(albumSongs) {
        if (albumSongs.isNotEmpty()) {
            SongPlayer.prefetchList(albumSongs.map { it.url }, context)
        }
    }

    val albumByName : Map<String, List<AlbumsModel>> = albums.groupBy { it.name }
    // The album may not be in the cached new-releases list (e.g. opened from
    // search) — fall back to a model built from the album's first track.
    val album : List<AlbumsModel> = albumByName[albumName]
        ?: listOf(
            AlbumsModel(
                id = albumName.hashCode() and 0x7fffffff,
                artists = albumSongs.firstOrNull()?.singer ?: "",
                coverUri = albumSongs.firstOrNull()?.coverUri ?: "",
                name = albumName,
                time = "",
            )
        )
    var dominentColor by remember {
        mutableStateOf(Color(AppBackground.toArgb()))
    }
    Palette().extractSecondColorFromCoverUrl(context = context, album[0].coverUri){ color ->
        dominentColor = color
    }

    var isAlbumLiked by remember { mutableStateOf( isAlbumLiked(context, album[0].id.toString())) }

    var snackbarMessage by remember {
        mutableStateOf("")
    }
    var snackbarVisible by remember {
        mutableStateOf(false)
    }
    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    menuSong?.let { sel ->
        com.music.spotui.ui.components.SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
        )
    }
    LaunchedEffect(snackbarVisible) {
        delay(1500)
        snackbarVisible = false
    }



    Log.d("color", dominentColor.toString())
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
                        ) {
                            navController.navigateUp()
                        },
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "",
                        tint = Color.White)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                ),
                title = {
                    Text(text = "")
                }
            )
        }
    ) { innerPadding ->


        Column(modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
            .consumeWindowInsets(innerPadding)
            .padding(bottom = innerPadding.calculateBottomPadding())
            .verticalScroll(rememberScrollState())
        ) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(460.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(dominentColor, Color(AppBackground.toArgb())),
                            startY = -100f,

                            ),

                        )
                ,
                verticalArrangement = Arrangement.Center,
               // horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.padding(25.dp))

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    GlideImage(
                        modifier = Modifier.size(230.dp),
                        model = album[0].coverUri,
                        failure = placeholder(R.drawable.placeholder),
                        //loading = placeholder(R.drawable.album),
                        //contentScale = ContentScale.Crop,
                        contentDescription = "",
                    )
                }
                Spacer(modifier = Modifier.padding(5.dp))
                Text(modifier = Modifier
                    .padding(20.dp, 5.dp, 0.dp, 0.dp),
                    text = albumName,
                    color = Color.White,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold)
                Text(modifier = Modifier
                    .padding(20.dp, 0.dp, 0.dp, 0.dp),
                    text = album[0].artists.ifBlank { albumSongs.firstOrNull()?.singer ?: "" },
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium)
                Text(modifier = Modifier
                    .padding(20.dp, 0.dp, 0.dp, 0.dp),
                    text = "Album : ${album[0].time}",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )

                Row(horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(20.dp, 0.dp)
                ){

                    if (snackbarVisible){
                            Snackbar(showMessage = snackbarMessage)
                        }
                    else{
                        // Let the action icons take their natural width — a fixed
                        // 75dp squeezed the add + download buttons together.
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {

                            GlideImage(
                                modifier = Modifier
                                    .height(60.dp)
                                    .width(32.dp)
                                    .padding(0.dp, 5.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                ,
                                model = album[0].coverUri,
                                failure = placeholder(R.drawable.placeholder),
                                //loading = placeholder(R.drawable.album),
                                contentScale = ContentScale.Crop,
                                contentDescription = "",
                            )
                            Icon(
                                modifier = Modifier
                                    .size(23.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        if (isAlbumLiked) {
                                            removeLikedAlbumId(context, album[0].id.toString())
                                            snackbarMessage = "Removed from Library"
                                        } else {
                                            addLikedAlbumId(context, album[0].id.toString())
                                            snackbarMessage = "Added to Library"
                                        }
                                        isAlbumLiked = isAlbumLiked(context, album[0].id.toString())
                                        snackbarVisible = true

                                    },
                                painter = if (isAlbumLiked){
                                    painterResource(id = R.drawable.added)
                                }
                                else{
                                    painterResource(id = R.drawable.ic_add)
                                }
                                ,
                                tint = if (isAlbumLiked){
                                    Color(AppPalette.toArgb())
                                }
                                else{
                                    Color.White
                                },
                                contentDescription = ""
                            )
                            // Download the whole album (all tracks) for offline playback.
                            var albumDownloaded by remember(albumSongs) {
                                mutableStateOf(SongPlayer.allDownloaded(albumSongs, context))
                            }
                            Icon(
                                imageVector = if (albumDownloaded)
                                    Icons.Default.CheckCircle else ImageVector.vectorResource(R.drawable.ic_download),
                                tint = if (albumDownloaded) Color(AppPalette.toArgb()) else Color.White,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        if (!albumDownloaded && albumSongs.isNotEmpty()) {
                                            SongPlayer.downloadAll(albumSongs, context)
                                            snackbarMessage = "Downloading ${albumSongs.size} tracks…"
                                            snackbarVisible = true
                                        }
                                    },
                                contentDescription = "Download album",
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            // Shuffle-play: start the album in random order.
                            Icon(
                                painter = painterResource(id = R.drawable.ic_player_shuffle),
                                tint = Color.White,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        albumViewModel.startShuffled(albumSongs)?.let { first ->
                                            albumViewModel.updatePlaybackContext(albumViewModel.getAlbumContextUri())
                                            SongPlayer.playSong(first.url, context)
                                            albumViewModel.updateSongState(
                                                first.coverUri,
                                                first.title,
                                                first.singer,
                                                true,
                                                first.id,
                                                0,
                                                albumName,
                                            )
                                        }
                                    },
                                contentDescription = "Shuffle play",
                            )
                        }


                        // Always visible: pause when playing, resume when this
                        // album's track is paused, otherwise start from the top.
                        if (albumSongs.isNotEmpty()) {
                            val playing = albumViewModel.currentSongPlayingState.value
                            androidx.compose.foundation.layout.Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(100.dp))
                                    .background(Color.White)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        when {
                                            playing -> albumViewModel.setPlaying(false)
                                            albumSongs.any { it.id == albumViewModel.currentSongId.value } ->
                                                albumViewModel.setPlaying(true)
                                            else -> {
                                                albumViewModel.updatePlaybackContext(albumViewModel.getAlbumContextUri())
                                                albumViewModel.updateQueue(albumSongs)
                                                SongPlayer.playSong(albumSongs[0].url, context)
                                                albumViewModel.updateSongState(
                                                    albumSongs[0].coverUri,
                                                    albumSongs[0].title,
                                                    albumSongs[0].singer,
                                                    true,
                                                    albumSongs[0].id,
                                                    0,
                                                    albumName
                                                )
                                            }
                                        }
                                    }
                            ) {
                                Icon(
                                    modifier = Modifier
                                        .size(25.dp),
                                    tint = Color.Black,
                                    painter = painterResource(
                                        id = if (playing) R.drawable.ic_playing else R.drawable.play_svgrepo_com,
                                    ),
                                    contentDescription = if (playing) "Pause" else "Play")
                            }
                        }
                    }




                }

            }

//            Spacer(modifier = Modifier.padding(25.dp))

            if(albumSongs.isNotEmpty()){
                repeat(albumSongs.size) {song ->


                    var isLiked by remember {
                        mutableStateOf(isSongLiked(context, albumSongs[song].id.toString()))
                    }
                    val likeState = albumViewModel.likeState.value
                    LaunchedEffect(likeState){
                        isLiked = isSongLiked(context, albumSongs[song].id.toString())
                    }
                    val songId = albumSongs[song].id

                    val currentPlayingIndicatorColor = if(songId == albumViewModel.currentSongId.value) Color(AppPalette.toArgb()) else Color.White

                    val songItem = albumSongs[song]
                    val isDownloaded = remember(songItem.id) {
                        com.music.spotui.data.preferences.isDownloaded(context, songItem.id.toString()) ||
                        com.music.spotui.data.preferences.downloadedPathForQuery(context, songItem.url) != null
                    }
                    val isOnline = remember { com.music.spotui.data.preferences.isNetworkAvailable(context) }
                    val isPlayable = isOnline || isDownloaded

                    val isCurrent = songId == albumViewModel.currentSongId.value
                    val titleColor = when {
                        isCurrent -> Color(AppPalette.toArgb())
                        !isPlayable -> Color(0xFF666666)
                        else -> Color.White
                    }
                    val subColor = if (!isPlayable) Color(0xFF444444) else Color.Gray

                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp, 8.dp)
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onLongClick = { menuSong = songItem },
                                onClick = {
                                    if (!isPlayable) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Download this song to play it offline",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        return@combinedClickable
                                    }
                                    val playableQueue = if (isOnline) albumSongs else albumSongs.filter {
                                        com.music.spotui.data.preferences.isDownloaded(context, it.id.toString()) ||
                                        com.music.spotui.data.preferences.downloadedPathForQuery(context, it.url) != null
                                    }
                                    albumViewModel.updatePlaybackContext(albumViewModel.getAlbumContextUri())
                                    albumViewModel.updateQueue(playableQueue)
                                    SongPlayer.playSong(songItem.url, context)
                                    val targetIdx = playableQueue.indexOfFirst { it.id == songItem.id }.coerceAtLeast(0)
                                    albumViewModel.updateSongState(
                                        songItem.coverUri,
                                        songItem.title,
                                        songItem.singer,
                                        true,
                                        songItem.id,
                                        targetIdx,
                                        albumName
                                    )
                                },
                            )
                    ) {

                        Row(
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f).padding(end = 12.dp)
                        ) {
                            Column {
                                Text(
                                    text = songItem.title,
                                    color = titleColor,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
                                        text = songItem.singer,
                                        color = subColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Icon(
                            modifier = Modifier
                                .size(20.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (isLiked) {
                                        removeLikedSongId(context, songId.toString())
                                    } else {
                                        addLikedSongId(context, songId.toString())
                                    }
                                    isLiked = isSongLiked(context, songId.toString())
                                    albumViewModel.updateLikeState(!albumViewModel.likeState.value)

                                },
                            painter = if (isLiked){
                                painterResource(id = R.drawable.added)
                            }
                            else{
                                painterResource(id = R.drawable.ic_add)
                            }
                            ,
                            tint = if (isLiked){
                                Color.White
                            }else{
                                Color.Gray
                            },
                            contentDescription = ""
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.padding(80.dp))
        }

    }
}
