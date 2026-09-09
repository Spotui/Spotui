package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.Response
import com.music.spotui.data.api.SpotifySession
import com.music.spotui.data.entity.AccountModel
import com.music.spotui.R
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.preferences.isLibraryGridView
import com.music.spotui.data.preferences.setLibraryGridView
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.components.Snackbar
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(navController: NavController) {

    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val entries by libraryViewModel.entries.collectAsState()
    val account by libraryViewModel.account.collectAsState()
    var showAccount by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Spotify-style layout toggle: rows or a 3-column grid, persisted across runs.
    var gridView by remember { mutableStateOf(isLibraryGridView(context)) }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    if (showAccount) {
        AccountSheet(
            account = (account as? Response.Success)?.data ?: AccountModel(),
            navController = navController,
            onDismiss = { showAccount = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
            .statusBarsPadding()
    ) {
        // Header: title + account avatar. Built by hand (rather than a fixed-height
        // TopAppBar) so the title isn't clipped under the status bar.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp, 12.dp, 16.dp, 8.dp)
        ) {
            Text(
                text = "Your Library",
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 22.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search library",
                tint = if (searchActive) Color(AppPalette.toArgb()) else Color.White,
                modifier = Modifier
                    .padding(end = 14.dp)
                    .size(22.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        searchActive = !searchActive
                        if (!searchActive) searchQuery = ""
                    },
            )
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3A3A3A))
                    .clickable { showAccount = true },
                contentAlignment = Alignment.Center
            ) {
                val avatar = (account as? Response.Success)?.data?.imageUrl.orEmpty()
                if (avatar.isNotBlank()) {
                    AccountAvatar(avatar, 34.dp)
                } else {
                    Icon(Icons.Default.Person, contentDescription = "Account", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }

        // Grid/list switch, like the icon at the top-right of Spotify's library.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp, 0.dp, 16.dp, 4.dp)
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                painter = painterResource(if (gridView) R.drawable.ic_view_list else R.drawable.ic_view_grid),
                contentDescription = if (gridView) "Show as list" else "Show as grid",
                tint = Color.White,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        gridView = !gridView
                        setLibraryGridView(context, gridView)
                    }
            )
        }

        AnimatedVisibility(visible = searchActive) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp, 0.dp, 16.dp, 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF252525))
                    .padding(horizontal = 12.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF888888), modifier = Modifier.size(18.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp, 10.dp),
                    decorationBox = { inner ->
                        Box {
                            if (searchQuery.isEmpty()) Text("Filter your library…", color = Color(0xFF888888), fontSize = 15.sp)
                            inner()
                        }
                    },
                )
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = Color(0xFF888888),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { searchQuery = "" },
                    )
                }
            }
        }

        val followedArtists by libraryViewModel.followedArtists.collectAsState()
        when (entries) {
            is Response.Loading -> LibrarySkeleton(PaddingValues(0.dp))
            is Response.Success -> {
                val allEntries = (entries as Response.Success).data
                val filteredEntries = if (searchQuery.isBlank()) allEntries
                    else allEntries.filter {
                        it.name.contains(searchQuery, ignoreCase = true) ||
                        it.subtitle.contains(searchQuery, ignoreCase = true)
                    }
                val filteredArtists = if (searchQuery.isBlank()) followedArtists
                    else followedArtists.filter { it.name.contains(searchQuery, ignoreCase = true) }
                if (gridView) LibraryGridScreen(PaddingValues(0.dp), filteredEntries, filteredArtists, navController)
                else SumUpLibraryScreen(PaddingValues(0.dp), filteredEntries, filteredArtists, navController)
            }
            else -> Box(modifier = Modifier.padding(20.dp, 100.dp)) { Snackbar(showMessage = "Couldn't load your library") }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun AccountAvatar(url: String, size: androidx.compose.ui.unit.Dp) {
    GlideImage(
        modifier = Modifier.size(size).clip(CircleShape),
        model = url,
        contentScale = ContentScale.Crop,
        contentDescription = ""
    )
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SumUpLibraryScreen(
    padding: PaddingValues,
    entries: List<LibraryEntry>,
    followedArtists: List<com.music.spotui.data.entity.ArtistsModel>,
    navController: NavController
) {
    if (entries.isEmpty() && followedArtists.isEmpty()) {
        Box(modifier = Modifier.padding(20.dp, 40.dp)) { Snackbar(showMessage = "Library is Empty") }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(top = 10.dp, bottom = 130.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color(0xFF0E0E13))
    ) {
        // Listening history & stats entry.
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp, 6.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { navController.navigate(Routes.History.route) }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(55.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF27856A)),
                ) {
                    Icon(Icons.Default.DateRange, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(text = "Listening history", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(text = "Your plays and stats", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        // Local files (imported device audio) entry.
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp, 6.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { navController.navigate(Routes.LocalFiles.route) }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(55.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF3B5BA5)),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_library_big),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(text = "Local files", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(text = "Music imported from this device", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        items(entries, key = { it.spotifyId }) { entry ->
            Row(
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp, 6.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { openLibraryEntry(entry, navController) }
            ) {
                if (entry.spotifyId == Api.HomeCache.DOWNLOADS_ID) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(55.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF2E7D32)),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                } else {
                    GlideImage(
                        modifier = Modifier
                            .size(55.dp)
                            .clip(RoundedCornerShape(if (entry.isPlaylist) 6.dp else 4.dp)),
                        model = entry.coverUri,
                        contentScale = ContentScale.Crop,
                        contentDescription = ""
                    )
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(text = entry.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text = entry.subtitle, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // ── Artists the user follows on Spotify ──
        if (followedArtists.isNotEmpty()) {
            item {
                Text(
                    text = "Artists you follow",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 4.dp),
                )
            }
            items(followedArtists) { artist ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp, 6.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { navController.navigate(artistRoute(artist.name, artist.id)) }
                ) {
                    GlideImage(
                        modifier = Modifier
                            .size(55.dp)
                            .clip(CircleShape),
                        model = artist.coverUri,
                        contentScale = ContentScale.Crop,
                        contentDescription = ""
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(text = artist.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(text = "Artist", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

private fun openLibraryEntry(entry: LibraryEntry, navController: NavController) {
    if (entry.spotifyId == Api.HomeCache.LIKED_SONGS_ID) navController.navigate(Routes.Liked.route)
    else if (entry.spotifyId == Api.HomeCache.DOWNLOADS_ID) navController.navigate(Routes.Downloads.route)
    else if (entry.isPlaylist) navController.navigate(playlistRoute(entry.spotifyId, entry.name))
    else navController.navigate(albumRoute(entry.name, entry.artists))
}

/**
 * Grid layout of the same library content: 3 columns of square covers with the
 * title/subtitle underneath, like Spotify's "Grid" library view. Followed
 * artists render as circles under their own full-width header.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun LibraryGridScreen(
    padding: PaddingValues,
    entries: List<LibraryEntry>,
    followedArtists: List<com.music.spotui.data.entity.ArtistsModel>,
    navController: NavController
) {
    if (entries.isEmpty() && followedArtists.isEmpty()) {
        Box(modifier = Modifier.padding(20.dp, 40.dp)) { Snackbar(showMessage = "Library is Empty") }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color(0xFF0E0E13)),
        contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 130.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Listening history & stats entry, as a tile.
        item {
            Column(
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { navController.navigate(Routes.History.route) }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF27856A)),
                ) {
                    Icon(Icons.Default.DateRange, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = "Listening history", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = "Your plays and stats", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Local files (imported device audio) entry, as a tile.
        item {
            Column(
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { navController.navigate(Routes.LocalFiles.route) }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF3B5BA5)),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_library_big),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = "Local files", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = "Music on device", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        items(entries, key = { it.spotifyId }) { entry ->
            Column(
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { openLibraryEntry(entry, navController) }
            ) {
                if (entry.spotifyId == Api.HomeCache.DOWNLOADS_ID) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF2E7D32)),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                } else {
                    GlideImage(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(if (entry.isPlaylist) 6.dp else 4.dp)),
                        model = entry.coverUri,
                        contentScale = ContentScale.Crop,
                        contentDescription = ""
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = entry.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = entry.subtitle, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (followedArtists.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Artists you follow",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            items(followedArtists) { artist ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { navController.navigate(artistRoute(artist.name, artist.id)) }
                ) {
                    GlideImage(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(CircleShape),
                        model = artist.coverUri,
                        contentScale = ContentScale.Crop,
                        contentDescription = ""
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = artist.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(text = "Artist", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun LibrarySkeleton(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color(0xFF0E0E13))
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        repeat(8) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp, 6.dp)
            ) {
                Box(modifier = Modifier.size(55.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1E1E1E)))
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Box(modifier = Modifier.height(14.dp).width(160.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF1E1E1E)))
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(modifier = Modifier.height(11.dp).width(90.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF1E1E1E)))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSheet(
    account: AccountModel,
    navController: NavController,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1A1A),
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(16.dp, 4.dp, 16.dp, 16.dp)
            ) {
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF3A3A3A)),
                    contentAlignment = Alignment.Center
                ) {
                    if (account.imageUrl.isNotBlank()) AccountAvatar(account.imageUrl, 56.dp)
                    else Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(account.name.ifBlank { "Spotify user" }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (account.email.isNotBlank()) Text(account.email, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (account.plan.isNotBlank()) Text("Spotify ${account.plan}", color = Color(0xFF1DB954), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            HorizontalDivider(color = Color(0xFF2A2A2A))

            AccountRow("Account") { onDismiss() }
            AccountRow("Listening history") {
                onDismiss()
                navController.navigate(Routes.History.route)
            }
            HorizontalDivider(color = Color(0xFF2A2A2A))
            AccountRow("Log out", tint = Color(0xFFE57373)) {
                SpotifySession.setSpDc(context, "")
                Api.HomeCache.clear()
                onDismiss()
                navController.navigate(Routes.Login.route) {
                    popUpTo(0) { inclusive = true }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AccountRow(label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Text(
        text = label,
        color = tint,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(20.dp, 16.dp)
    )
}
