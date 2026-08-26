package com.music.spotui.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.local.LocalImport
import com.music.spotui.data.preferences.addLocalTracks
import com.music.spotui.data.preferences.getLocalSongs
import com.music.spotui.data.preferences.removeLocalTrack
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun LocalFilesScreen(navController: NavController) {
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = AppPalette

    var songs by remember { mutableStateOf(getLocalSongs(context)) }
    var importing by remember { mutableStateOf(false) }

    fun toast(msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

    val addSongs = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch(Dispatchers.IO) {
            val tracks = LocalImport.importFiles(context, uris)
            addLocalTracks(context, tracks)
            withContext(Dispatchers.Main) {
                songs = getLocalSongs(context)
                importing = false
                toast("Imported ${tracks.size} song${if (tracks.size == 1) "" else "s"}")
            }
        }
    }

    val addFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch(Dispatchers.IO) {
            val tracks = LocalImport.importFolder(context, treeUri)
            addLocalTracks(context, tracks)
            withContext(Dispatchers.Main) {
                songs = getLocalSongs(context)
                importing = false
                toast("Imported ${tracks.size} song${if (tracks.size == 1) "" else "s"} from folder")
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize().background(Color(AppBackground.toArgb())),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    modifier = Modifier.padding(16.dp, 0.dp),
                    navigationIcon = {
                        Icon(
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { navController.navigateUp() },
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "",
                            tint = Color.White,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                    ),
                    title = { Text("Local files", color = Color.White, fontWeight = FontWeight.Bold) },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(AppBackground.toArgb()))
                    .consumeWindowInsets(innerPadding)
                    .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "On this device",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 2.dp),
                )
                Text(
                    text = if (songs.isEmpty()) "Import FLAC, MP3, WAV and more" else "${songs.size} songs",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 12.dp),
                )

                // Import buttons
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp, 0.dp, 20.dp, 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ImportButton("Add songs", accent, Modifier.weight(1f), enabled = !importing) {
                        addSongs.launch(arrayOf("audio/*"))
                    }
                    ImportButton("Add folder", Color(0xFF2A2A33), Modifier.weight(1f), enabled = !importing) {
                        addFolder.launch(null)
                    }
                }
                if (importing) {
                    Text(
                        "Importing…",
                        color = accent,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(20.dp, 2.dp),
                    )
                }

                Spacer(Modifier.height(8.dp))

                if (songs.isEmpty()) {
                    Text(
                        text = "No local music yet. Tap “Add songs” or “Add folder” to import from your device.",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(20.dp),
                    )
                } else {
                    songs.forEachIndexed { index, song ->
                        val currentColor = if (song.id == playerViewModel.currentSongId.value)
                            Color(AppPalette.toArgb()) else Color.White
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp, 8.dp)
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onLongClick = {
                                        removeLocalTrack(context, song.url)
                                        songs = getLocalSongs(context)
                                        toast("Removed from library")
                                    },
                                    onClick = {
                                        playerViewModel.updateQueue(songs)
                                        SongPlayer.playSong(song.url, context)
                                        playerViewModel.updateSongState(
                                            song.coverUri, song.title, song.singer,
                                            true, song.id, index, song.album,
                                        )
                                    },
                                ),
                        ) {
                            GlideImage(
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                                model = song.coverUri,
                                failure = placeholder(R.drawable.placeholder),
                                loading = placeholder(R.drawable.placeholder),
                                contentScale = ContentScale.Crop,
                                contentDescription = "",
                            )
                            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(song.title, color = currentColor, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(song.singer, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            }
                        }
                    }
                    Text(
                        text = "Long-press a song to remove it from your library.",
                        color = Color(0xFF6A6A6A),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(20.dp, 12.dp),
                    )
                }

                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun ImportButton(
    label: String,
    container: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = if (container == Color(0xFF2A2A33)) Color.White else Color.Black,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) container else Color(0xFF333333))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 12.dp),
    )
}
