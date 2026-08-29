package com.music.spotui.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.music.spotui.data.entity.Lyrics
import com.music.spotui.R
import com.music.spotui.data.preferences.getLyricTranslateLang
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.viewmodel.LyricsViewModel
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos

private const val TRANSLATION_ANIMATION_MS = 260
private val SpotifyTranslationEasing = Easing { fraction ->
    ((cos((fraction + 1f) * Math.PI) / 2.0) + 0.5).toFloat()
}

private data class LyricsPlayback(val positionMs: Long, val isPlaying: Boolean)

/** Polls ExoPlayer's position every 250ms so the active lyric line tracks the music. */
@Composable
private fun rememberLyricsPlayback(): State<LyricsPlayback> {
    val playback = remember { mutableStateOf(LyricsPlayback(0L, false)) }
    LaunchedEffect(Unit) {
        while (true) {
            playback.value = LyricsPlayback(
                positionMs = SongPlayer.getCurrentPosition().coerceAtLeast(0L),
                isPlaying = SongPlayer.isPlaying(),
            )
            delay(250L)
        }
    }
    return playback
}

private fun activeIndexFor(lyrics: Lyrics, positionMs: Long): Int =
    if (lyrics.synced) lyrics.lines.indexOfLast { it.timeMs <= positionMs + 250 }.coerceAtLeast(0)
    else -1

/** Seek to a tapped synced line and make sure we're playing. */
private fun jumpTo(timeMs: Long) {
    SongPlayer.seekTo(timeMs)
    if (!SongPlayer.isPlaying()) SongPlayer.play()
}

/**
 * Full-screen synced-lyrics overlay (Spotify "Lyrics" view). The current line is
 * highlighted bright and the list auto-scrolls to keep it centered; tapping a line
 * jumps playback to it (synced lyrics only). Falls back to a static scroll for plain
 * (un-timed) lyrics.
 * Translations appear below the original lines when enabled.
 */
@Composable
fun LyricsScreen(
    title: String,
    artist: String,
    album: String,
    accentColor: Color,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val vm: LyricsViewModel = hiltViewModel()
    LaunchedEffect(title, artist) {
        val durationSec = (SongPlayer.getDuration() / 1000).toInt()
        vm.load(title, artist, album, durationSec)
    }
    val state by vm.state.collectAsState()
    val playback by rememberLyricsPlayback()
    val positionMs = playback.positionMs
    val targetLanguage = getLyricTranslateLang(context).ifBlank {
        Locale.getDefault().language.ifBlank { "en" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Solid base first — the gradient's translucent middle stop let the
            // player screen bleed through, making the lyrics page look transparent.
            .background(Color(0xFF121212))
            .background(
                Brush.verticalGradient(
                    colors = listOf(accentColor, accentColor.copy(alpha = 0.55f), Color.Black),
                )
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 64.dp),
            ) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    artist,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when (val s = state) {
            is LyricsViewModel.State.Loading ->
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            is LyricsViewModel.State.NotFound ->
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Couldn't find lyrics for this track", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
                }
            is LyricsViewModel.State.Loaded -> {
                val lyrics = s.lyrics
                val activeIndex = activeIndexFor(lyrics, positionMs)
                val listState = rememberLazyListState()
                LaunchedEffect(lyrics, targetLanguage) {
                    vm.prepareTranslation(targetLanguage)
                }
                LaunchedEffect(activeIndex) {
                    if (activeIndex >= 0) {
                        listState.animateScrollToItem(activeIndex.coerceAtLeast(0), scrollOffset = -260)
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithCache {
                            val topFade = 24.dp.toPx()
                            val bottomFade = 20.dp.toPx()
                            val mask = Brush.verticalGradient(
                                0f to Color.Transparent,
                                (topFade / size.height) to Color.Black,
                                ((size.height - bottomFade) / size.height) to Color.Black,
                                1f to Color.Transparent,
                            )
                            onDrawWithContent {
                                drawContent()
                                drawRect(mask, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                            }
                        },
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(lyrics.lines) { index, line ->
                            LyricLineText(
                                text = line.text,
                                translation = s.translations?.getOrNull(index),
                                showTranslation = s.showTranslation,
                                isActive = index == activeIndex,
                                synced = lyrics.synced,
                                fontSize = 20.sp,
                                onTap = if (lyrics.synced) ({ jumpTo(line.timeMs) }) else null,
                            )
                        }
                    }
                }
                val sameLanguage = s.sourceLanguage
                    ?.substringBefore('-')
                    ?.equals(targetLanguage.substringBefore('-'), ignoreCase = true) == true
                LyricsControlPanel(
                    isPlaying = playback.isPlaying,
                    positionMs = positionMs,
                    durationMs = SongPlayer.getDuration().coerceAtLeast(0L),
                    showTranslate = s.translationChecked && !sameLanguage,
                    translationActive = s.showTranslation,
                    translating = s.translating,
                    translationRequested = s.translationRequested,
                    onTranslate = { vm.toggleTranslation(targetLanguage) },
                )
            }
        }
    }
}

/** How many lines the inline lyrics card previews before "Show lyrics". */
private const val PREVIEW_LINE_COUNT = 5

/**
 * Inline lyrics card shown in the Now Playing scroll (no full-screen chrome).
 * Spotify-style *preview*: only a handful of lines (following the active synced
 * line) plus a "Show lyrics" button that opens the full-screen lyrics view.
 */
@Composable
fun InlineLyrics(
    title: String,
    artist: String,
    album: String,
    accentColor: Color,
    onExpand: () -> Unit,
) {
    val vm: LyricsViewModel = hiltViewModel()
    LaunchedEffect(title, artist) {
        val durationSec = (SongPlayer.getDuration() / 1000).toInt()
        vm.load(title, artist, album, durationSec)
    }
    val state by vm.state.collectAsState()
    val playback by rememberLyricsPlayback()
    val positionMs = playback.positionMs

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp, 16.dp, 40.dp)
            .background(
                Brush.verticalGradient(listOf(accentColor.copy(alpha = 0.55f), accentColor.copy(alpha = 0.18f))),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onExpand() }
            .padding(20.dp)
    ) {
        Text("Lyrics preview", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(12.dp))

        when (val s = state) {
            is LyricsViewModel.State.Loading ->
                Text("Loading lyrics…", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
            is LyricsViewModel.State.NotFound ->
                Text("No lyrics found for this track", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
            is LyricsViewModel.State.Loaded -> {
                val lyrics = s.lyrics
                val activeIndex = activeIndexFor(lyrics, positionMs)
                // Preview window: keep the active synced line in view; plain
                // lyrics just show the first few lines.
                val windowStart =
                    if (lyrics.synced) activeIndex.coerceIn(0, (lyrics.lines.size - PREVIEW_LINE_COUNT).coerceAtLeast(0))
                    else 0
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    lyrics.lines.drop(windowStart).take(PREVIEW_LINE_COUNT).forEachIndexed { i, line ->
                        LyricLineText(
                            text = line.text,
                            translation = null,
                            isActive = windowStart + i == activeIndex,
                            synced = lyrics.synced,
                            fontSize = 22.sp,
                            onTap = if (lyrics.synced) ({ jumpTo(line.timeMs) }) else null,
                        )
                    }
                }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(18.dp))
                Box(
                    modifier = Modifier
                        .background(Color.White, shape = androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onExpand() }
                        .padding(16.dp, 8.dp)
                ) {
                    Text("Show lyrics", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun TranslateButton(
    active: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                enabled = !loading,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        color = if (active) Color(0xFF1DB954) else Color.Transparent,
                        shape = RoundedCornerShape(4.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_translate_lyrics),
                    contentDescription = if (active) "Stop translating lyrics" else "Translate lyrics",
                    tint = if (active) Color.Black else Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun LyricsControlPanel(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    showTranslate: Boolean,
    translationActive: Boolean,
    translating: Boolean,
    translationRequested: Boolean,
    onTranslate: () -> Unit,
) {
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        if (showTranslate) {
            TranslateButton(
                active = translationActive,
                loading = translating && translationRequested,
                onClick = onTranslate,
            )
        }
        LyricsSeekBar(
            progress = progress,
            onProgressFinished = { finalProgress ->
                SongPlayer.seekTo((durationMs * finalProgress).toLong())
            },
        )
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Text(formatLyricsTime(positionMs), color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
            Text(formatLyricsTime(durationMs), color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
        }
        IconButton(
            onClick = {
                if (SongPlayer.isPlaying()) SongPlayer.pause() else SongPlayer.play()
            },
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp, bottom = 4.dp)
                .size(64.dp)
                .offset(y = (-8).dp)
                .background(Color.White, CircleShape),
        ) {
            Icon(
                painter = painterResource(
                    if (isPlaying) R.drawable.ic_playing else R.drawable.play_svgrepo_com
                ),
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.Black,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun LyricsSeekBar(
    progress: Float,
    onProgressFinished: (Float) -> Unit,
) {
    var widthPx by remember { mutableStateOf(1f) }
    var value by remember { mutableStateOf(progress) }
    var dragging by remember { mutableStateOf(false) }
    var pendingSeek by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(progress, dragging, pendingSeek) {
        if (!dragging && (pendingSeek == null || abs(progress - pendingSeek!!) < 0.01f)) {
            value = progress
            pendingSeek = null
        }
    }
    LaunchedEffect(pendingSeek) {
        if (pendingSeek != null) {
            delay(1_500)
            pendingSeek = null
        }
    }

    fun updateValue(next: Float) {
        value = next.coerceIn(0f, 1f)
    }

    val dragState = rememberDraggableState { delta ->
        updateValue(value + delta / widthPx)
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(widthPx) {
                detectTapGestures { offset ->
                    val finalValue = (offset.x / widthPx).coerceIn(0f, 1f)
                    value = finalValue
                    pendingSeek = finalValue
                    onProgressFinished(finalValue)
                }
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = {
                    dragging = true
                    value = progress
                },
                onDragStopped = {
                    dragging = false
                    pendingSeek = value
                    onProgressFinished(value)
                },
            ),
    ) {
        val trackHeight = 3.dp.toPx()
        val centerY = size.height / 2f
        val corner = trackHeight / 2f
        drawRoundRect(
            color = Color.White.copy(alpha = 0.3f),
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(size.width, trackHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner),
        )
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(size.width * value, trackHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner),
        )
        drawCircle(Color.White, radius = 4.dp.toPx(), center = Offset(size.width * value, centerY))
    }
}

private fun formatLyricsTime(timeMs: Long): String {
    val totalSeconds = timeMs.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

@Composable
private fun LyricLineText(
    text: String,
    translation: String?,
    showTranslation: Boolean? = null,
    isActive: Boolean,
    synced: Boolean,
    fontSize: androidx.compose.ui.unit.TextUnit,
    onTap: (() -> Unit)?,
) {
    if (text.isBlank() && translation.isNullOrBlank()) {
        Box(modifier = Modifier.size(1.dp))
        return
    }
    val target = when {
        !synced -> Color.White.copy(alpha = 0.9f)
        isActive -> Color.White
        else -> Color.White.copy(alpha = 0.45f)
    }
    val color by animateColorAsState(targetValue = target, label = "lyricColor")
    val translationColor = when {
        !synced -> Color.White.copy(alpha = 0.55f)
        isActive -> Color.White.copy(alpha = 0.7f)
        else -> Color.White.copy(alpha = 0.35f)
    }
    val extraLineSpacing by animateDpAsState(
        targetValue = if (showTranslation == true && !translation.isNullOrBlank()) 0.dp else 5.dp,
        animationSpec = tween(
            durationMillis = TRANSLATION_ANIMATION_MS,
            easing = SpotifyTranslationEasing,
        ),
        label = "lyricSpacing",
    )
    Column(
        modifier = if (onTap != null) Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onTap() } else Modifier,
    ) {
        if (text.isNotBlank()) {
            Text(
                text = text,
                color = color,
                fontSize = fontSize,
                letterSpacing = 0.1.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        AnimatedVisibility(
            visible = showTranslation == true && !translation.isNullOrBlank(),
            enter = expandVertically(
                animationSpec = tween(
                    durationMillis = TRANSLATION_ANIMATION_MS,
                    easing = SpotifyTranslationEasing,
                ),
                expandFrom = Alignment.Top,
            ) + slideInVertically(
                animationSpec = tween(
                    durationMillis = TRANSLATION_ANIMATION_MS,
                    easing = SpotifyTranslationEasing,
                ),
                initialOffsetY = { -it },
            ) + fadeIn(
                tween(
                    durationMillis = TRANSLATION_ANIMATION_MS,
                    easing = SpotifyTranslationEasing,
                ),
            ),
            exit = shrinkVertically(
                animationSpec = tween(
                    durationMillis = TRANSLATION_ANIMATION_MS,
                    easing = SpotifyTranslationEasing,
                ),
                shrinkTowards = Alignment.Top,
            ) + slideOutVertically(
                animationSpec = tween(
                    durationMillis = TRANSLATION_ANIMATION_MS,
                    easing = SpotifyTranslationEasing,
                ),
                targetOffsetY = { -it },
            ) + fadeOut(
                tween(
                    durationMillis = TRANSLATION_ANIMATION_MS,
                    easing = SpotifyTranslationEasing,
                ),
            ),
        ) {
            Text(
                text = translation.orEmpty(),
                color = translationColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.offset(y = (-4).dp),
            )
        }
        if (showTranslation != null) {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(extraLineSpacing))
        }
    }
}
