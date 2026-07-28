package com.sdw.music.player.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.compose.AsyncImagePainter
import com.sdw.music.player.ui.animation.CoverPosition
import com.sdw.music.player.ui.animation.SharedCoverState
import com.sdw.music.player.ui.components.DefaultCoverImage
import com.sdw.music.player.ui.theme.*
import kotlin.math.exp

// ============================================================================
// Shared Player Components - extracted from PlayerScreen.kt
// ============================================================================

/** Top bar: back button + title + overflow menu (delete). */
@Composable
fun PlayerTopBar(
    showMenu: Boolean,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onNavigateBack: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onNavigateBack, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, "Back", tint = TextPrimary, modifier = Modifier.size(24.dp))
        }
        Text("Now Playing", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Box {
            IconButton(onClick = onToggleMenu, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.MoreVert, "Menu", tint = TextSecondary, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(
                    text = { Text("Delete Song", color = Color.Red) },
                    onClick = { onDismissMenu(); onDelete() },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                )
            }
        }
    }
}

/** Album cover art with glow ring and loading/error fallback. */
@Composable
fun PlayerCoverArt(
    artUri: String?,
    songTitle: String,
    songArtist: String,
    accentColor: Color,
    isPlaying: Boolean,
    rotation: Float,
    sizeDp: androidx.compose.ui.unit.Dp,
    glowSizeDp: androidx.compose.ui.unit.Dp,
    onCoverPositioned: ((Offset, Size) -> Unit)? = null,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val rippleRadius = sizeDp / 2
    Box(
        modifier = modifier
            .size(glowSizeDp)
            .clickable(
                indication = rememberRipple(bounded = false, radius = rippleRadius),
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // Glow ring behind the art
        Box(
            modifier = Modifier
                .size(glowSizeDp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.08f))
        )
        // Album art
        SubcomposeAsyncImage(
            model = artUri,
            contentDescription = "Album Art",
            modifier = Modifier
                .size(sizeDp)
                .clip(CircleShape)
                .then(
                    if (onCoverPositioned != null) {
                        Modifier.onGloballyPositioned { coords ->
                            onCoverPositioned(
                                Offset(coords.positionInWindow().x, coords.positionInWindow().y),
                                Size(coords.size.width.toFloat(), coords.size.height.toFloat())
                            )
                        }
                    } else Modifier
                )
                .then(if (isPlaying) Modifier.rotate(rotation) else Modifier),
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center
        ) {
            val imgState = painter.state
            if (imgState is AsyncImagePainter.State.Loading || imgState is AsyncImagePainter.State.Error) {
                DefaultCoverImage(songTitle, songArtist, Modifier.size(sizeDp))
            } else {
                SubcomposeAsyncImageContent()
            }
        }
    }
}

/** Song info row: artist (clickable) + format badge. */
@Composable
fun PlayerSongInfo(
    artist: String,
    format: String,
    textAccentColor: Color,
    onArtistClick: () -> Unit = {}
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Text(
            artist.ifEmpty { " " },
            style = MaterialTheme.typography.bodyLarge,
            color = textAccentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).clickable { onArtistClick() }
        )
        if (format.isNotEmpty()) {
            Surface(
                modifier = Modifier.padding(start = 8.dp),
                color = AccentBlue.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    format.uppercase(),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentBlue
                )
            }
        }
    }
}

/** Full song header: title + artist + format, portrait style. */
@Composable
fun PlayerSongHeader(
    title: String,
    artist: String,
    format: String,
    textAccentColor: Color,
    onArtistClick: () -> Unit = {}
) {
    Text(
        title.ifEmpty { "Not Playing" },
        style = MaterialTheme.typography.headlineMedium,
        color = TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    PlayerSongInfo(
        artist = artist,
        format = format,
        textAccentColor = textAccentColor,
        onArtistClick = onArtistClick
    )
}

/** Progress bar + time labels. */
@Composable
fun PlayerProgress(
    progressFraction: Float,
    durationMs: Long,
    positionMs: Long,
    accentColor: Color,
    onSeekTo: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (durationMs > 0) {
            Slider(
                value = progressFraction.coerceIn(0f, 1f),
                onValueChange = onSeekTo,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = DarkSurface
                ),
                modifier = Modifier.fillMaxWidth().height(20.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatDurationPlayer(positionMs), style = MaterialTheme.typography.labelSmall, color = TextTertiary)
            Text(formatDurationPlayer(durationMs), style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        }
    }
}

/** Playback control bar: shuffle, prev, play/pause, next, repeat. */
@Composable
fun PlayerControlBar(
    shuffleEnabled: Boolean,
    repeatMode: Int,
    isPlaying: Boolean,
    accentColor: Color,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
    playButtonSize: androidx.compose.ui.unit.Dp = 64.dp
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onToggleShuffle) {
            Icon(
                if (shuffleEnabled) Icons.Default.ShuffleOn else Icons.Default.Shuffle,
                null,
                tint = if (shuffleEnabled) accentColor else TextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
        IconButton(onClick = onPrevious) {
            Icon(Icons.Default.SkipPrevious, null, tint = TextPrimary, modifier = Modifier.size(36.dp))
        }
        FilledIconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(playButtonSize),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = accentColor, contentColor = DarkBg
            )
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                null,
                modifier = Modifier.size(playButtonSize / 2)
            )
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Default.SkipNext, null, tint = TextPrimary, modifier = Modifier.size(36.dp))
        }
        IconButton(onClick = onCycleRepeat) {
            val (icon, tintColor) = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne to accentColor
                Player.REPEAT_MODE_ALL -> Icons.Default.Repeat to accentColor
                else -> Icons.Default.Repeat to TextSecondary
            }
            Icon(icon, null, tint = tintColor, modifier = Modifier.size(24.dp))
        }
    }
}

/** Bottom action bar: lyrics, equalizer, favorite, share. */
@Composable
fun PlayerBottomActions(
    eqEnabled: Boolean,
    isCurrentSongFavorite: Boolean,
    onNavigateToLyrics: () -> Unit,
    onToggleEqualizer: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        IconButton(onClick = onNavigateToLyrics) {
            Icon(Icons.Default.Lyrics, null, tint = TextSecondary)
        }
        IconButton(onClick = onToggleEqualizer) {
            Icon(
                Icons.Default.Equalizer, null,
                tint = if (eqEnabled) AccentRed else TextSecondary
            )
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (isCurrentSongFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                null,
                tint = if (isCurrentSongFavorite) AccentRed else TextSecondary
            )
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Default.Share, null, tint = TextSecondary)
        }
    }
}

/** EQ preset label chip (centered, shown above progress bar). */
@Composable
fun PlayerEqLabel(
    eqPresetName: String?,
    accentColor: Color,
    textAccentColor: Color
) {
    if (eqPresetName != null) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = accentColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = eqPresetName,
                    style = MaterialTheme.typography.labelLarge,
                    color = textAccentColor,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp)
                )
            }
        }
    }
}

/** Inline lyric line (shown below artist info in non-foldable layouts). */
@Composable
fun PlayerInlineLyric(
    lyricLine: String?,
    color: Color,
    modifier: Modifier = Modifier
) {
    // Reserve fixed 2-line height so album cover doesn't jump when lyrics appear/disappear
    val lineHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.fontSize.value * 1.4f }
    Box(
        modifier = modifier.fillMaxWidth().heightIn(min = (lineHeight * 2f).dp),
        contentAlignment = Alignment.Center
    ) {
        if (!lyricLine.isNullOrBlank()) {
            Text(
                lyricLine,
                style = MaterialTheme.typography.bodyLarge,
                color = color,
                maxLines = 2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Duration formatter for PlayerScreen. */
internal fun formatDurationPlayer(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds)
    else "%d:%02d".format(minutes, seconds)
}


// ============================================================================
// V3.3.4: DAC info capsule bar - DAC model | 48kHz / 24bit | Hi-Res gold badge
// Shown only in USB DAC exclusive mode (auto-hides when claim absent)
// ============================================================================
@Composable
fun DacInfoBar(
    accentColor: Color,
    textAccentColor: Color,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    var dacName by remember { mutableStateOf("") }
    var sr by remember { mutableIntStateOf(0) }
    var bits by remember { mutableIntStateOf(0) }
    var diagCount by remember { mutableIntStateOf(0) }
    
    // [V3.3.6] 只在 DAC 激活时轮询，避免主线程卡顿
    LaunchedEffect(Unit) {
        while (true) {
            val claimed = try { com.sdw.music.player.core.audio.UsbDacManager.isClaimed() } catch (_: Throwable) { false }
            if (claimed) {
                dacName = com.sdw.music.player.core.audio.UsbDacManager.getDacDisplayName()
                sr = com.sdw.music.player.core.audio.UsbDacManager.queryActiveSampleRate()
                bits = com.sdw.music.player.core.audio.UsbDacManager.queryActiveBits()
            } else { 
                dacName = ""; sr = 0; bits = 0
            }
            if (diagCount % 5 == 0) {
                android.util.Log.d("DacInfoBar", "claimed=$claimed dacName='$dacName' sr=$sr bits=$bits")
            }
            diagCount++
            // DAC 未激活时降低轮询频率（5秒）
            kotlinx.coroutines.delay(if (claimed) 1000L else 5000L)
        }
    }
    if (dacName.isEmpty() || sr <= 0) return

    val hiRes = sr >= 88_200 || bits >= 24
    val srText = if (sr % 1000 == 0) "${sr / 1000}kHz" else String.format("%.1fkHz", sr / 1000f)

    val infinite = rememberInfiniteTransition(label = "dacDot")
    val pulse by infinite.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dacDotA"
    )
    val dotAlpha = if (isPlaying) pulse else 1f

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(accentColor.copy(alpha = 0.08f))
            .border(1.dp, accentColor.copy(alpha = 0.25f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).background(textAccentColor.copy(alpha = dotAlpha), CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(
            dacName,
            fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = textAccentColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 130.dp)
        )
        DacInfoDivider(textAccentColor)
        Text(
            "$srText / ${bits}bit",
            fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = textAccentColor,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
        )
        if (hiRes) {
            DacInfoDivider(textAccentColor)
            Box(
                Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFFF5D061), Color(0xFFC9A227))))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "Hi-Res",
                    fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = Color(0xFF1A1200),
                    letterSpacing = androidx.compose.ui.unit.TextUnit(0.3f, androidx.compose.ui.unit.TextUnitType.Sp)
                )
            }
        }
    }
}

@Composable
private fun DacInfoDivider(color: Color) {
    Spacer(Modifier.width(9.dp))
    Box(Modifier.width(1.dp).height(10.dp).background(color.copy(alpha = 0.2f)))
    Spacer(Modifier.width(9.dp))
}
