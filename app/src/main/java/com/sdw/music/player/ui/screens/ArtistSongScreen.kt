package com.sdw.music.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sdw.music.player.Song
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistSongScreen(
    artistName: String,
    songs: List<Song>,
    currentSongId: Long,
    isPlaying: Boolean,
    onSongClick: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val coverUri = songs.firstOrNull()?.albumArtUri.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(artistName, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = onPlayAll) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, "Play All", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Cover Header
            Box(
                modifier = Modifier.fillMaxWidth().height(200.dp)
                    .background(Brush.verticalGradient(listOf(DarkBg, DarkCard)))
            ) {
                if (coverUri.isNotBlank()) {
                    AsyncImage(
                        model = coverUri, contentDescription = artistName,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                    )
                } else {
                    DefaultCoverImage(songTitle = artistName, songArtist = "", modifier = Modifier.fillMaxSize())
                }
                Box(
                    modifier = Modifier.fillMaxWidth().height(80.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, DarkBg)))
                )
            }

            Text(
                text = "${songs.size} songs",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                items(songs, key = { it.id }) { song ->
                    ArtistSongItem(
                        song = song,
                        isPlaying = song.id == currentSongId && isPlaying,
                        onClick = { onSongClick(song) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistSongItem(
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isPlaying) Purple60.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (song.albumArtUri.isNotBlank()) {
            AsyncImage(
                model = song.albumArtUri, contentDescription = song.title,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            DefaultCoverImage(
                songTitle = song.title,
                songArtist = song.artist,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(6.dp)
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, color = if (isPlaying) AccentBlue else TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.album, style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isPlaying) {
            Icon(Icons.AutoMirrored.Filled.PlaylistPlay, "Playing", tint = AccentBlue, modifier = Modifier.size(20.dp))
        }
    }
}