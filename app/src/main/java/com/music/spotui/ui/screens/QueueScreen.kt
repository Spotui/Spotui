package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.PlayerViewModel

/**
 * The "play queue" — the track that's playing now plus everything coming up
 * (Spotify's Queue view). Tapping an upcoming track jumps straight to it;
 * dragging the handle reorders it; swiping a row removes it.
 */
@OptIn(ExperimentalGlideComposeApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(navController: NavController) {
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val context = LocalContext.current
    val queue by playerViewModel.queue
    val currentId = playerViewModel.currentSongId.value
    val curIdx = queue.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val upcoming = if (queue.isNotEmpty()) queue.drop(curIdx + 1) else emptyList()
    val current = queue.getOrNull(curIdx)

    // Drag-to-reorder state: which upcoming row is dragged and its live offset.
    var draggingId by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { 64.dp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .statusBarsPadding()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp, 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier
                    .size(28.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { navController.navigateUp() }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text("Queue", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            current?.let {
                item {
                    Text(
                        "Now playing",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 6.dp)
                    )
                    QueueRow(song = it, highlight = true, onClick = {})
                }
            }
            if (upcoming.isNotEmpty()) {
                item {
                    Text(
                        "Next up",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp, 18.dp, 16.dp, 6.dp)
                    )
                }
                itemsIndexed(upcoming, key = { _, s -> s.id }) { upIdx, song ->
                    val isDragging = draggingId == song.id
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value != SwipeToDismissBoxValue.Settled) {
                                playerViewModel.removeFromQueue(song)
                                true
                            } else false
                        }
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            Box(
                                contentAlignment = Alignment.CenterEnd,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF7A1F1F))
                                    .padding(horizontal = 24.dp),
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.White)
                            }
                        },
                        modifier = Modifier.graphicsLayer {
                            translationY = if (isDragging) dragOffset else 0f
                        },
                    ) {
                        QueueRow(
                            song = song,
                            highlight = false,
                            onClick = {
                                val idx = queue.indexOfFirst { it.id == song.id }
                                playerViewModel.updateSongState(
                                    song.coverUri, song.title, song.singer, true,
                                    song.id, idx.coerceAtLeast(0), song.album
                                )
                                SongPlayer.playSong(song.url, context)
                                navController.navigateUp()
                            },
                            dragHandle = Modifier.pointerInput(song.id) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggingId = song.id
                                        dragOffset = 0f
                                    },
                                    onDragEnd = { draggingId = -1; dragOffset = 0f },
                                    onDragCancel = { draggingId = -1; dragOffset = 0f },
                                ) { change, dragAmount ->
                                    change.consume()
                                    dragOffset += dragAmount.y
                                    // Crossed a full row height → swap with the neighbour.
                                    val abs = queue.indexOfFirst { it.id == song.id }
                                    if (dragOffset > rowHeightPx / 2 && upIdx < upcoming.size - 1) {
                                        playerViewModel.moveQueueItem(abs, abs + 1)
                                        dragOffset -= rowHeightPx
                                    } else if (dragOffset < -rowHeightPx / 2 && upIdx > 0) {
                                        playerViewModel.moveQueueItem(abs, abs - 1)
                                        dragOffset += rowHeightPx
                                    }
                                }
                            },
                        )
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(120.dp)) }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun QueueRow(
    song: com.music.spotui.data.entity.SongsModel,
    highlight: Boolean,
    onClick: () -> Unit,
    dragHandle: Modifier? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121212))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(16.dp, 8.dp)
    ) {
        GlideImage(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp)),
            model = song.coverUri,
            contentScale = ContentScale.Crop,
            loading = placeholder(R.drawable.placeholder),
            failure = placeholder(R.drawable.placeholder),
            contentDescription = ""
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = if (highlight) Color(AppPalette.toArgb()) else Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.singer,
                color = Color.Gray,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!highlight && dragHandle != null) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Reorder",
                tint = Color(0xFFB3B3B3),
                modifier = Modifier
                    .size(28.dp)
                    .then(dragHandle)
            )
        }
    }
}
