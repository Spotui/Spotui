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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.getDownloadedSongs
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(navController: NavController) {

    val playerViewModel: PlayerViewModel = hiltViewModel()
    val context = LocalContext.current
    val accent = AppPalette

    // Completed downloads (from prefs) + live in-progress ones (from SongPlayer). Poll
    // while the screen is open so a track appears here the moment its download starts,
    // shows a live percentage, and moves into the list when it finishes.
    var songs by remember { mutableStateOf(getDownloadedSongs(context)) }
    var inProgress by remember {
        mutableStateOf(com.music.spotui.di.SongPlayer.downloadingSnapshot())
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            val snap = com.music.spotui.di.SongPlayer.downloadingSnapshot()
            if (snap.size != inProgress.size) songs = getDownloadedSongs(context)
            inProgress = snap
            kotlinx.coroutines.delay(400)
        }
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

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(AppBackground.toArgb()))
                    .consumeWindowInsets(innerPadding)
                    .padding(bottom = innerPadding.calculateBottomPadding())
                    .verticalScroll(rememberScrollState())
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(accent.copy(alpha = 0.5f), Color(AppBackground.toArgb())),
                                startY = -100f,
                            ),
                        ),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Spacer(modifier = Modifier.padding(25.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .background(accent.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_download),
                                contentDescription = "",
                                tint = accent,
                                modifier = Modifier.size(90.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.padding(5.dp))
                    Text(
                        modifier = Modifier.padding(20.dp, 5.dp, 0.dp, 0.dp),
                        text = "Downloaded",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        modifier = Modifier.padding(20.dp, 4.dp, 20.dp, 0.dp),
                        text = "${songs.size} songs • available offline",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // ── Clear all action (per-song Export lives in the ⋯ menu) ──
                if (songs.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp, 0.dp, 20.dp, 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            text = "Clear all",
                            color = Color(0xFFE57373),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF1A1A20))
                                .clickable {
                                    val n = com.music.spotui.data.preferences.clearAllDownloads(context)
                                    songs = getDownloadedSongs(context)
                                    android.widget.Toast.makeText(
                                        context, "Removed $n download${if (n == 1) "" else "s"}",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }
                }

                // ── In-progress downloads (with live progress bar) ──
                inProgress.forEach { (song, pct) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp, 8.dp),
                    ) {
                        GlideImage(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            model = song.coverUri,
                            failure = placeholder(R.drawable.placeholder),
                            contentScale = ContentScale.Crop,
                            contentDescription = "",
                        )
                        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(text = song.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            Spacer(modifier = Modifier.height(4.dp))
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { (pct.coerceIn(0, 100)) / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = accent,
                                trackColor = Color(0xFF333333),
                            )
                        }
                        Text(
                            text = "$pct%",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }

                if (songs.isEmpty() && inProgress.isEmpty()) {
                    Text(
                        text = "No downloads yet. Tap ⋯ on a track and choose Download.",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(20.dp),
                    )
                } else {
                    repeat(songs.size) { index ->
                        val song = songs[index]
                        val currentColor = if (song.id == playerViewModel.currentSongId.value)
                            Color(AppPalette.toArgb()) else Color.White

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
                                        playerViewModel.updateQueue(songs)
                                        SongPlayer.playSong(song.url, context)
                                        playerViewModel.updateSongState(
                                            song.coverUri, song.title, song.singer,
                                            true, song.id, index, "Downloaded"
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
                                Text(text = song.title, color = currentColor, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(text = song.singer, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.padding(80.dp))
            }
        }
    }
}
