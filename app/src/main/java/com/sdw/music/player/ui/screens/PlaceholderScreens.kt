package com.sdw.music.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sdw.music.player.ui.theme.DarkBg
import com.sdw.music.player.ui.theme.DarkSurface
import com.sdw.music.player.ui.theme.DarkCard
import com.sdw.music.player.ui.theme.TextPrimary
import com.sdw.music.player.ui.theme.TextSecondary
import androidx.compose.foundation.combinedClickable
import com.sdw.music.player.ui.theme.AccentRed
import com.sdw.music.player.ui.theme.TextTertiary
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.SongRepository
import com.sdw.music.player.Playlist
import androidx.compose.foundation.ExperimentalFoundationApi
import com.sdw.music.player.PlaylistManager

// SettingsScreen moved
// SettingsScreen moved to SettingsScreen.kt

// === Folder List Screen ===
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    onNavigateBack: () -> Unit,
    onPlaySongs: (List<com.sdw.music.player.Song>) -> Unit
) {
    var foldersVersion by remember { mutableStateOf(0L) }
    // Observe foldersVersion so folder list + counts refresh after scan/delete
    LaunchedEffect(Unit) {
        com.sdw.music.player.SongRepository.foldersVersion.collect { v ->
            foldersVersion = v
        }
    }
    val folders = remember(foldersVersion) {
        com.sdw.music.player.SongRepository.getFolders()
    }
    var selectedFolder by remember { mutableStateOf<com.sdw.music.player.Folder?>(null) }
    val accentPurple = Color(0xFF8E6FD0)

    // If a folder is selected, show its songs
    if (selectedFolder != null) {
        FolderSongsView(
            folder = selectedFolder!!,
            onBack = { selectedFolder = null },
            onPlaySongs = onPlaySongs
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Folders", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${folders.size} folders",
                            color = TextTertiary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg,
        snackbarHost = {}
    ) { padding ->
        if (folders.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Folder,
                        null,
                        tint = TextTertiary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No music folders found", color = TextSecondary)
                    Text("Scan music in SongList first", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                item { Spacer(Modifier.height(8.dp)) }
                itemsIndexed(folders, key = { _, f -> f.path }) { index, folder ->
                    val folderAlbumArt = remember(folder.path) {
                        val songs = com.sdw.music.player.SongRepository.getSongsInFolder(folder.path)
                        songs.firstOrNull { !it.albumArtUri.isNullOrBlank() }?.albumArtUri ?: ""
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkCard),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                ) { selectedFolder = folder }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (folderAlbumArt.isNotBlank()) {
                                Box(
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(DarkSurface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    coil.compose.AsyncImage(
                                        model = folderAlbumArt,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            } else {
                                DefaultCoverImage(
                                    songTitle = folder.name,
                                    songArtist = "",
                                    modifier = Modifier.size(48.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    overlayAlpha = 0.2f
                                )
                            }

                            Spacer(Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    folder.name,
                                    color = TextPrimary,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${folder.songCount} songs",
                                    color = TextTertiary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    folder.path,
                                    color = TextTertiary.copy(alpha = 0.5f),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Icon(
                                Icons.Default.ChevronRight,
                                null,
                                tint = TextTertiary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

// === Playlist List Screen ===
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistListScreen(
    onNavigateBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onPlaySongs: (List<com.sdw.music.player.Song>) -> Unit,
    onAddToPlaylist: (String) -> Unit = {}
) {
    var playlists by remember {
        mutableStateOf<List<Playlist>>(PlaylistManager.getPlaylists())
    }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Playlists", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                        Text(
                            playlists.size.toString() + " playlists",
                            color = TextTertiary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    newPlaylistName = ""
                    showCreateDialog = true
                },
                containerColor = Color(0xFF8E6FD0),
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, "New Playlist")
            }
        },
        containerColor = DarkBg
    ) { padding ->
        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Folder,
                        null,
                        tint = TextTertiary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No playlists yet", color = TextSecondary)
                    Text("Tap + to create", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                itemsIndexed<Playlist>(playlists, key = { _, pl: Playlist -> pl.id }) { _, playlist ->
                    val previewSongs = remember(playlist.id) {
                        PlaylistManager.getPlaylistSongs(playlist.id)
                    }
                    val coverUri = remember(playlist.id) {
                        previewSongs.firstOrNull { it.albumArtUri.isNotBlank() }?.albumArtUri ?: ""
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .combinedClickable(
                                onClick = {
                                    if (previewSongs.isNotEmpty()) {
                                        onPlaySongs(previewSongs)
                                    }
                                },
                                onLongClick = {
                                    showDeleteConfirm = playlist.id
                                }
                            ),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkCard),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (coverUri.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(DarkSurface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    coil.compose.AsyncImage(
                                        model = coverUri,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            } else {
                                DefaultCoverImage(
                                    songTitle = playlist.name,
                                    songArtist = "",
                                    modifier = Modifier.size(48.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    overlayAlpha = 0.2f
                                )
                            }

                            Spacer(Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    playlist.name,
                                    color = TextPrimary,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    playlist.songIds.size.toString() + " songs",
                                    color = TextTertiary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Icon(
                                Icons.Default.Delete,
                                null,
                                tint = TextTertiary.copy(alpha = 0.4f),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { showDeleteConfirm = playlist.id }
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New Playlist", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text("Enter name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = Color(0xFF8E6FD0),
                        unfocusedBorderColor = TextTertiary
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            PlaylistManager.createPlaylist(newPlaylistName)
                            playlists = PlaylistManager.getPlaylists()
                            showCreateDialog = false
                        }
                    },
                    enabled = newPlaylistName.isNotBlank()
                ) {
                    Text("Create", color = Color(0xFF8E6FD0))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkCard
        )
    }

    showDeleteConfirm?.let { playlistId ->
        val pl = playlists.find { it.id == playlistId }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete Playlist", color = TextPrimary) },
            text = {
                Text(
                    "Delete this playlist?",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        PlaylistManager.deletePlaylist(playlistId)
                        playlists = PlaylistManager.getPlaylists()
                        showDeleteConfirm = null
                    }
                ) {
                    Text("Delete", color = AccentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkCard
        )
    }
}

// === Folder Songs View (inline within FolderListScreen) ===
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderSongsView(
    folder: com.sdw.music.player.Folder,
    onBack: () -> Unit,
    onPlaySongs: (List<com.sdw.music.player.Song>) -> Unit
) {
    val songs = remember(folder.path) {
        com.sdw.music.player.SongRepository.getSongsInFolder(folder.path)
    }
    var searchQuery by remember { mutableStateOf("") }
    val displayedSongs = remember(songs, searchQuery) {
        if (searchQuery.isBlank()) songs
        else songs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(folder.name, color = TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${songs.size} songs", color = TextTertiary, style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary)
                    }
                },
                actions = {
                    if (songs.isNotEmpty()) {
                        IconButton(onClick = { onPlaySongs(songs) }) {
                            Icon(Icons.Default.PlayArrow, "Play All", tint = Color(0xFF8E6FD0))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { padding ->
        if (displayedSongs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.MusicNote, null, tint = TextTertiary, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No folders", color = TextSecondary)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                item { Spacer(Modifier.height(8.dp)) }
                itemsIndexed(displayedSongs, key = { _, s -> s.id }) { index, song ->
                    com.sdw.music.player.ui.screens.SongItem(
                        song = song,
                        index = index,
                        isPlaying = false,
                        accentColor = Color(0xFF8E6FD0),
                        onClick = { onPlaySongs(listOf(song)) },
                        onLongClick = { }
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}


