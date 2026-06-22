package com.sdw.music.player.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.compose.AsyncImagePainter
import androidx.compose.ui.res.painterResource
import com.sdw.music.player.ui.animation.SharedCoverState
import com.sdw.music.player.ui.animation.CoverPosition
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.Song
import com.sdw.music.player.ui.theme.*
import androidx.media3.common.Player
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SongListScreen(
    songs: List<Song>,
    currentPlayingSong: Song?,
    isPlaying: Boolean,
    accentColor: Long,
    sharedCoverState: SharedCoverState? = null,
    miniCoverVisible: Boolean = true,
    deviceName: String = "Moto Audio",
    isTablet: Boolean = false,
    onNavigateBack: () -> Unit = {},
    onSongClick: (Song) -> Unit,
    onNavigateToPlayer: () -> Unit,
    onNavigateToFolder: () -> Unit,
    onNavigateToPlaylist: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAlbum: () -> Unit = {},
    onNavigateToArtist: () -> Unit = {},
    onRefresh: () -> Unit = {},
    scanStatus: String = "idle",
    scanProgress: Float = 0f,
    // Tablet detail pane controls
    onPlayPause: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onSeekTo: (Long) -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onCycleRepeat: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onToggleEqualizer: () -> Unit = {},
    onShare: () -> Unit = {},
    onNavigateToLyrics: () -> Unit = {},
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    eqEnabled: Boolean = false,
    shuffleEnabled: Boolean = false,
    repeatMode: Int = Player.REPEAT_MODE_OFF,
    isCurrentSongFavorite: Boolean = false
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var filterMode by remember { mutableStateOf("all") }
    val activeColor = Color(accentColor)
    val scope = rememberCoroutineScope()

    val recentThreshold = System.currentTimeMillis() / 1000 - 7 * 24 * 3600

    // Filter songs by search + filter mode
    val displayedSongs = remember(songs, searchQuery, filterMode) {
        val base = if (searchQuery.isBlank()) songs
        else songs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true) ||
            it.album.contains(searchQuery, ignoreCase = true)
        }
        when (filterMode) {
            "hires" -> base.filter { it.format.uppercase() in setOf("FLAC", "WAV", "DSD", "ALAC", "AIFF", "APE", "WAVPACK") }
            "recent" -> base.filter { it.lastPlayedAt > recentThreshold }
            "favorites" -> base.filter { it.isFavorite }
            else -> base
        }
    }

    // Count Hi-Res songs (FLAC / WAV / DSD)
    val hiresCount = remember(songs) { songs.count { it.format.uppercase() in setOf("FLAC", "WAV", "DSD", "ALAC") } }
    // Count recent (added within 7 days)
        val recentCount = remember(songs) { songs.count { it.lastPlayedAt > recentThreshold } }
    // Count favorites
    val favoriteCount = remember(songs) { songs.count { it.isFavorite } }

    // Group by first char (A-Z + #)
    val groupedSongs = remember(displayedSongs) {
        displayedSongs.groupBy { song ->
            val c = song.title.firstOrNull()?.uppercase()?.firstOrNull() ?: '#'
            if (c in 'A'..'Z') c else '#'
        }.toSortedMap()
    }
    val alphabet = ('A'..'Z').toList() + '#'
    val listState = rememberLazyListState()
    val groupFirstIndices = remember(groupedSongs) {
        val map = mutableMapOf<Char, Int>()
        var idx = 0
        groupedSongs.forEach { (letter, songsInGroup) ->
            map[letter] = idx
            idx += songsInGroup.size + 1 // +1 for header
        }
        map
    }

    // Track current visible group from scroll position
    val currentGroupLetter by remember {
        derivedStateOf {
            val visibleIdx = listState.firstVisibleItemIndex
            var best: Char = alphabet.firstOrNull() ?: 'A'
            groupFirstIndices.forEach { (letter, startIdx) ->
                if (startIdx <= visibleIdx) best = letter
            }
            best
        }
    }

    // Tablet detail pane visibility — dismissible via back arrow
    var detailPaneVisible by remember { mutableStateOf(currentPlayingSong != null) }
    // Auto-show detail pane when a new song starts playing
    LaunchedEffect(currentPlayingSong?.id) {
        if (currentPlayingSong != null) detailPaneVisible = true
    }

    if (isTablet && detailPaneVisible && currentPlayingSong != null) {
        // System back in tablet TwoPane: dismiss detail pane instead of minimizing
        BackHandler { detailPaneVisible = false }

        // === Tablet Master-Detail Layout ===
        Row(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
            // Left: Song list + top bar (60%)
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
            ) {
                // === Top App Bar (compact for tablet left pane) ===
                TopAppBar(
                    title = {
                        if (isSearching) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search...", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    cursorColor = MaterialTheme.colorScheme.tertiary
                                )
                            )
                        } else {
                            Text(
                                "Moto Music",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { detailPaneVisible = false }) {
                            Icon(Icons.Default.Close, "Close Detail", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isSearching) {
                            IconButton(onClick = { isSearching = false; searchQuery = "" }) {
                                Icon(Icons.Default.Close, "CloseSearch", tint = MaterialTheme.colorScheme.onSurface)
                            }
                        } else {
                            IconButton(onClick = { isSearching = true }) {
                                Icon(Icons.Default.Search, "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = onRefresh) {
                                Icon(Icons.Default.Refresh, "Refresh", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = onNavigateToPlaylist) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, "Playlists", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = onNavigateToSettings) {
                                Icon(Icons.Default.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )

                // === Smart Tiles ===
                if (!isSearching) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SmartTile("All", "${songs.size}", Icons.Default.MusicNote, Purple60, isSelected = filterMode == "all" || (filterMode != "hires" && filterMode != "recent" && filterMode != "favorites"), onClick = { filterMode = "all"; searchQuery = "" })
                        SmartTile("Hi-Res", "$hiresCount", Icons.Default.HighQuality, AccentBlue, isSelected = filterMode == "hires", onClick = { filterMode = "hires"; searchQuery = "" })
                        SmartTile("Recent", "$recentCount", Icons.Default.Schedule, Gold80, isSelected = filterMode == "recent", onClick = { filterMode = "recent" })
                        SmartTile("Favorites", "$favoriteCount", Icons.Default.Favorite, AccentRed, isSelected = filterMode == "favorites", onClick = { filterMode = "favorites" })
                    }
                }

                TabletSongListContent(
                    songs = displayedSongs,
                    groupedSongs = groupedSongs,
                    alphabet = alphabet,
                    groupFirstIndices = groupFirstIndices,
                    currentGroupLetter = currentGroupLetter,
                    listState = listState,
                    currentPlayingSong = currentPlayingSong,
                    isPlaying = isPlaying,
                    activeColor = activeColor,
                    onSongClick = { song ->
                        detailPaneVisible = true
                        onSongClick(song)
                    },
                    scope = scope
                )
            }

            // Right: Now Playing detail (40%) — full player controls
            TabletPlayerDetailPanel(
                currentPlayingSong = currentPlayingSong,
                isPlaying = isPlaying,
                accentColor = activeColor,
                positionMs = positionMs,
                durationMs = durationMs,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                eqEnabled = eqEnabled,
                isCurrentSongFavorite = isCurrentSongFavorite,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onSeekTo = onSeekTo,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                onToggleFavorite = onToggleFavorite,
                onToggleEqualizer = onToggleEqualizer,
                onShare = onShare,
                onNavigateToLyrics = onNavigateToLyrics,
                onNavigateToPlayer = onNavigateToPlayer,
                modifier = Modifier.weight(0.4f).fillMaxHeight().padding(16.dp)
            )
        }
    } else {
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // === Top App Bar ===
            TopAppBar(
                title = {
                    if (isSearching) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search songs...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                cursorColor = MaterialTheme.colorScheme.tertiary
                            )
                        )
                    } else {
                        Text(
                            "Moto Music",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (isSearching) {
                        IconButton(onClick = { isSearching = false; searchQuery = "" }) {
                            Icon(Icons.Default.Close, "CloseSearch", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    } else {
                        IconButton(onClick = { isSearching = true }) {
                            Icon(Icons.Default.Search, "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, "Refresh", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onNavigateToPlaylist) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, "Playlists", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onNavigateToAlbum) {
                            Icon(Icons.Default.Album, "Albums", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onNavigateToArtist) {
                            Icon(Icons.Default.Person, "Artists", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onNavigateToFolder) {
                            Icon(Icons.Default.Folder, "Folders", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )

            // === Smart Tiles ===
            if (!isSearching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SmartTile("All", "${songs.size}", Icons.Default.MusicNote, Purple60, isSelected = filterMode == "all" || (filterMode != "hires" && filterMode != "recent" && filterMode != "favorites"), onClick = { filterMode = "all"; searchQuery = "" })
                    SmartTile("Hi-Res", "$hiresCount", Icons.Default.HighQuality, AccentBlue, isSelected = filterMode == "hires", onClick = { filterMode = "hires"; searchQuery = "" })
                    SmartTile("Recent", "$recentCount", Icons.Default.Schedule, Gold80, isSelected = filterMode == "recent", onClick = { filterMode = "recent" }) // TODO: genre filter not available
                    SmartTile("Favorites", "$favoriteCount", Icons.Default.Favorite, AccentRed, isSelected = filterMode == "favorites", onClick = { filterMode = "favorites" })
                }
            }

            // === Scan Progress Bar (visible whenever scanning) ===
            val isScanning = scanStatus.startsWith("scanning") || (scanProgress > 0f && scanProgress < 1f)
            if (isScanning) {
                LinearProgressIndicator(
                    progress = { scanProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    color = activeColor,
                    trackColor = activeColor.copy(alpha = 0.2f)
                )
                Text(
                    "Scanning media library... ${(scanProgress * 100).toInt()}%",
                    color = MaterialTheme.colorScheme.outlineVariant,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // === Song List ===
            if (displayedSongs.isEmpty() && !isSearching) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = 72.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.MusicNote,
                            null,
                            tint = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (scanProgress > 0f) "Scanning..." else "No songs found",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            if (scanProgress > 0f) "Scan in progress" else "Check your music files",
                            color = MaterialTheme.colorScheme.outlineVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        if (scanProgress <= 0f) {
                            Text("Status: $scanStatus", color = MaterialTheme.colorScheme.outlineVariant, style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = onRefresh) {
                                Text("Scan")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 72.dp),
                    contentPadding = PaddingValues(end = 32.dp),
                    state = listState
                ) {
                    groupedSongs.forEach { (letter, songsInGroup) ->
                        stickyHeader {
                            Surface(
                                color = MaterialTheme.colorScheme.background,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                            ) {
                                Text(letter.toString(), style = MaterialTheme.typography.labelMedium, color = activeColor, modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                        items(songsInGroup, key = { it.id }) { song ->
                            val idx = displayedSongs.indexOf(song)
                            SongItem(
                                song = song,
                                index = idx,
                                isPlaying = currentPlayingSong?.id == song.id && isPlaying,
                                accentColor = activeColor,
                                onClick = { onSongClick(song) },
                                onLongClick = { }
                            )
                        }
                    }
                }
            }

            // === Song Count ===
            if (displayedSongs.isNotEmpty()) {
                Text(
                    "${displayedSongs.size} songs",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 76.dp)
                )
            }
        }

        // === A-Z Sidebar (scroll-aware) ===
        if (displayedSongs.size > 20) {
            val sidebarHeight = alphabet.size * 22
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .width(24.dp)
                    .height(sidebarHeight.dp)
                    .pointerInput(alphabet) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                val idx = (offset.y / size.height * alphabet.size).toInt()
                                    .coerceIn(0, alphabet.size - 1)
                                groupFirstIndices[alphabet[idx]]?.let { pos ->
                                    scope.launch { listState.scrollToItem(pos) }
                                }
                            },
                            onVerticalDrag = { change, _ ->
                                val y = change.position.y.coerceIn(0f, size.height.toFloat())
                                val idx = (y / size.height * alphabet.size).toInt()
                                    .coerceIn(0, alphabet.size - 1)
                                groupFirstIndices[alphabet[idx]]?.let { pos ->
                                    scope.launch { listState.scrollToItem(pos) }
                                }
                            }
                        )
                    },
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                alphabet.forEach { letter ->
                    val hasGroup = letter in groupedSongs
                    val isCurrent = letter == currentGroupLetter
                    Text(
                        text = letter.toString(),
                        fontSize = if (isCurrent) 11.sp else 9.sp,
                        fontWeight = if (isCurrent) androidx.compose.ui.text.font.FontWeight.Bold
                                     else androidx.compose.ui.text.font.FontWeight.Normal,
                        color = if (isCurrent) activeColor
                                else if (hasGroup) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.clickable(enabled = hasGroup) {
                            groupFirstIndices[letter]?.let { idx ->
                                scope.launch { listState.animateScrollToItem(idx) }
                            }
                        }
                    )
                }
            }
        }

        // === Mini Player ===
        if (currentPlayingSong != null) {
            MiniPlayer(
                song = currentPlayingSong,
                isPlaying = isPlaying,
                accentColor = activeColor,
                coverVisible = miniCoverVisible,
                onClick = onNavigateToPlayer,
                onCoverPositioned = { offset, size ->
                    sharedCoverState?.miniCoverPosition = CoverPosition(
                        windowOffset = offset,
                        size = size
                    )
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        } else {
            // Device brand watermark when mini player is hidden
            Text(
                text = deviceName,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            )
        }
    }
}
}



/**
 * Song list content for tablet/foldable master-detail layout.
 * Shows a grouped A-Z list with an alphabet sidebar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabletSongListContent(
    songs: List<Song>,
    groupedSongs: Map<Char, List<Song>>,
    alphabet: List<Char>,
    groupFirstIndices: Map<Char, Int>,
    currentGroupLetter: Char,
    listState: androidx.compose.foundation.lazy.LazyListState,
    currentPlayingSong: Song?,
    isPlaying: Boolean,
    activeColor: Color,
    onSongClick: (Song) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(end = 32.dp),
            state = listState
        ) {
            groupedSongs.forEach { (letter, songsInGroup) ->
                stickyHeader {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                    ) {
                        Text(letter.toString(), style = MaterialTheme.typography.labelMedium, color = activeColor, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
                items(songsInGroup, key = { it.id }) { song ->
                    SongItem(
                        song = song,
                        index = songs.indexOf(song),
                        isPlaying = currentPlayingSong?.id == song.id && isPlaying,
                        accentColor = activeColor,
                        onClick = { onSongClick(song) },
                        onLongClick = { }
                    )
                }
            }
        }

        // A-Z Sidebar
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .width(24.dp)
                .fillMaxHeight(0.8f),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            alphabet.forEach { letter ->
                val hasGroup = letter in groupedSongs
                val isCurrent = letter == currentGroupLetter
                Text(
                    text = letter.toString(),
                    fontSize = if (isCurrent) 11.sp else 9.sp,
                    fontWeight = if (isCurrent) androidx.compose.ui.text.font.FontWeight.Bold
                                 else androidx.compose.ui.text.font.FontWeight.Normal,
                    color = if (isCurrent) activeColor
                            else if (hasGroup) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    modifier = Modifier.clickable(enabled = hasGroup) {
                        groupFirstIndices[letter]?.let { idx ->
                            scope.launch { listState.animateScrollToItem(idx) }
                        }
                    }
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        val hours = minutes / 60
        val remMin = minutes % 60
        "%d:%02d:%02d".format(hours, remMin, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}


@Composable
private fun RowScope.SmartTile(
    label: String,
    count: String,
    icon: ImageVector,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) color.copy(alpha = 0.15f) else Color.Transparent
    val contentColor = if (isSelected) color else MaterialTheme.colorScheme.onSurfaceVariant
    
    Surface(
        modifier = Modifier
            .weight(1f)
            .height(48.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        color = bgColor
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = count,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor
            )
        }
    }
}




@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongItem(
    song: Song,
    index: Int,
    isPlaying: Boolean,
    accentColor: Color,
    onClick: (Int) -> Unit,
    onLongClick: (Int) -> Unit
) {
    val textColor = if (isPlaying) accentColor else MaterialTheme.colorScheme.onSurface
    val subColor = if (isPlaying) accentColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick(index) },
                onLongClick = { onLongClick(index) }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cover art
        SubcomposeAsyncImage(
            model = song.albumArtUri,
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(CircleShape),
            contentScale = ContentScale.Crop
        ) {
            val imgState = painter.state
            if (imgState is AsyncImagePainter.State.Loading || imgState is AsyncImagePainter.State.Error) {
                DefaultCoverImage(
                    songTitle = song.title,
                    songArtist = song.artist,
                    modifier = Modifier.size(48.dp)
                )
            } else {
                SubcomposeAsyncImageContent()
            }
        }
        
        Spacer(Modifier.width(12.dp))
        
        // Title + Artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        // Duration
        Text(
            text = formatDuration(song.duration),
            style = MaterialTheme.typography.labelSmall,
            color = subColor
        )
    }
}




@Composable
fun MiniPlayer(
    song: Song?,
    isPlaying: Boolean,
    accentColor: Color,
    coverVisible: Boolean = true,
    onClick: () -> Unit,
    onCoverPositioned: ((Offset, Size) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (song == null) return
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Small cover
        Box(modifier = Modifier
            .size(40.dp)
            .graphicsLayer {
                alpha = if (coverVisible) 1f else 0f
            }
        ) {
            SubcomposeAsyncImage(
                model = song.albumArtUri,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .onGloballyPositioned { coords ->
                        val pos = coords.positionInWindow()
                        val sz = coords.size
                        onCoverPositioned?.invoke(
                            androidx.compose.ui.geometry.Offset(pos.x, pos.y),
                            androidx.compose.ui.geometry.Size(sz.width.toFloat(), sz.height.toFloat())
                        )
                    },
                contentScale = ContentScale.Crop
            ) {
                val imgState = painter.state
                if (imgState is AsyncImagePainter.State.Loading || imgState is AsyncImagePainter.State.Error) {
                    DefaultCoverImage(
                        songTitle = song.title,
                        songArtist = song.artist,
                        modifier = Modifier.size(40.dp),
                        shape = RoundedCornerShape(4.dp)
                    )
                } else {
                    SubcomposeAsyncImageContent()
                }
            }
        }
        
        Spacer(Modifier.width(12.dp))
        
        // Title + Artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        // Play/Pause icon
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(24.dp)
        )
    }
}

// ============================================================================
// Tablet Detail Pane — full player controls
// ============================================================================

@Composable
private fun TabletPlayerDetailPanel(
    currentPlayingSong: Song?,
    isPlaying: Boolean,
    accentColor: Color,
    positionMs: Long,
    durationMs: Long,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    eqEnabled: Boolean,
    isCurrentSongFavorite: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleEqualizer: () -> Unit,
    onShare: () -> Unit,
    onNavigateToLyrics: () -> Unit,
    onNavigateToPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val song = currentPlayingSong ?: return
    val progressFraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // Cover art — keep previous cover visible during transition using Crossfade
        Crossfade(targetState = song.id, animationSpec = tween(350), label = "cover") { _ ->
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                AsyncImage(
                    model = song.albumArtUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    // Don't show placeholder — let Coil handle cache + transition
                    placeholder = null,
                    error = painterResource(id = android.R.drawable.ic_menu_gallery)
                )
            }
        }

        // Song info
        Text(
            text = song.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = song.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Progress bar
        PlayerProgress(
            progressFraction = progressFraction,
            durationMs = durationMs,
            positionMs = positionMs,
            accentColor = accentColor,
            onSeekTo = { fraction ->
                onSeekTo((fraction * durationMs).toLong())
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        )

        // Playback controls
        PlayerControlBar(
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            isPlaying = isPlaying,
            accentColor = accentColor,
            onToggleShuffle = onToggleShuffle,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onCycleRepeat = onCycleRepeat
        )

        // EQ label + bottom actions
        PlayerEqLabel(
            eqPresetName = if (eqEnabled) "EQ" else null,
            accentColor = accentColor,
            textAccentColor = accentColor
        )

        PlayerBottomActions(
            eqEnabled = eqEnabled,
            isCurrentSongFavorite = isCurrentSongFavorite,
            onNavigateToLyrics = onNavigateToLyrics,
            onToggleEqualizer = onToggleEqualizer,
            onToggleFavorite = onToggleFavorite,
            onShare = onShare
        )
    }
}
