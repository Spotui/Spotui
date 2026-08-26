package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.music.spotui.ui.viewmodel.ShowViewModel

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ShowScreen(navController: NavController, showId: String, showName: String = "") {
    val vm: ShowViewModel = hiltViewModel()
    val context = LocalContext.current
    LaunchedEffect(showId) { vm.loadShow(showId) }

    val episodesState by vm.episodes.collectAsState()
    val show by vm.show.collectAsState()
    val episodes = (episodesState as? Response.Success)?.data.orEmpty()

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 130.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
            .statusBarsPadding(),
    ) {
        item {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                tint = Color.White,
                contentDescription = "Back",
                modifier = Modifier
                    .padding(16.dp)
                    .size(26.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { navController.navigateUp() },
            )
        }
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                GlideImage(
                    model = show?.coverUri ?: episodes.firstOrNull()?.coverUri,
                    contentScale = ContentScale.Crop,
                    failure = placeholder(R.drawable.placeholder),
                    modifier = Modifier.size(180.dp).clip(RoundedCornerShape(8.dp)),
                    contentDescription = null,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = show?.name ?: showName,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                show?.publisher?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color(0xFFB3B3B3), fontSize = 13.sp)
                }
            }
        }

        if (episodesState is Response.Loading) {
            item { Loader() }
        }

        items(episodes.size) { i ->
            val ep = episodes[i]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        vm.updateQueue(listOf(ep))
                        SongPlayer.playSong(ep.url, context)
                        vm.updateSongState(ep.coverUri, ep.title, ep.singer, true, ep.id, 0, ep.album)
                    }
                    .padding(16.dp, 10.dp),
            ) {
                GlideImage(
                    model = ep.coverUri,
                    contentScale = ContentScale.Crop,
                    failure = placeholder(R.drawable.placeholder),
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                    contentDescription = null,
                )
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        ep.title,
                        color = if (ep.id == vm.currentSongId.value) Color(0xFF1ED760) else Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(ep.singer, color = Color(0xFFB3B3B3), fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        item { Spacer(Modifier.height(130.dp)) }
    }
}
