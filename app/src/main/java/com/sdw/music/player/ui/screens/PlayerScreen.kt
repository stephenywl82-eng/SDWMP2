package com.sdw.music.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import android.app.Activity
import android.os.Build
import android.content.res.Configuration
import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.WindowInsets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sdw.music.player.R
import androidx.compose.ui.viewinterop.AndroidView
import com.sdw.music.player.LyricView
import com.sdw.music.player.LyricLine
import com.sdw.music.player.LrcParser
import com.sdw.music.player.BpmKeyCache
import com.sdw.music.player.MusicService
import kotlin.math.exp
import android.os.PowerManager
import com.sdw.music.player.ui.theme.*

import com.sdw.music.player.EqualizerManager
import com.sdw.music.player.ui.viewmodel.PlayerState
import com.sdw.music.player.ui.animation.CoverPosition
import com.sdw.music.player.ui.animation.SharedCoverState
import androidx.media3.common.Player

data class BandLevels(val sub: Float = 0f, val bass: Float = 0f, val mid: Float = 0f, val high: Float = 0f, val rms: Float = 0f)


@Composable
fun PlayerScreen(
    state: PlayerState,
    sharedCoverState: SharedCoverState? = null,
    fullCoverVisible: Boolean = true,
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    isPlaying: Boolean = false,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToLyrics: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onToggleEqualizer: () -> Unit,
    onDeleteSong: () -> Unit,
    onNavigateToAlbum: (albumName: String) -> Unit = {},
    onNavigateToArtist: (artistName: String) -> Unit = {},
    onDismiss: (() -> Unit)? = null,
) {
    val accentColor by animateColorAsState(
        targetValue = Color(state.accentColor),
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "accentColor"
    )
    val textAccentColor = remember(accentColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (accentColor.red * 255).toInt(), (accentColor.green * 255).toInt(),
            (accentColor.blue * 255).toInt(), hsv
        )
        if (hsv[2] < 0.65f) hsv[2] = 0.65f + (hsv[2] * 0.25f).coerceIn(0f, 0.1f)
        if (hsv[1] > 0.55f) hsv[1] = 0.55f + (hsv[1] - 0.55f) * 0.3f
        Color(android.graphics.Color.HSVToColor(hsv))
    }
    var showMenu by remember { mutableStateOf(false) }

    // Lyrics
    val lyricsLines = remember(state.currentLyrics) {
        if (!state.currentLyrics.isNullOrBlank()) LrcParser.parse(state.currentLyrics) else emptyList<LyricLine>()
    }
    val currentLyricLine = if (lyricsLines.isNotEmpty()) {
        val idx = LrcParser.findCurrentLineIndex(lyricsLines, positionMs)
        if (idx in lyricsLines.indices) lyricsLines[idx].text else null
    } else null

    // BackHandler: system back triggers navigate back
    BackHandler(onBack = onNavigateBack)

    // Immersive: hide status bar, restore on leave
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowInsetsControllerCompat(window, view)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller.show(WindowInsetsCompat.Type.statusBars()) }
    }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val isCompact = screenWidthDp < 400
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isFoldable = screenWidthDp >= 580 && (screenWidthDp.toFloat() / screenHeightDp.toFloat()) in 0.7f..1.4f

    val artSize = if (isLandscape) (screenHeightDp * 0.68f).dp.coerceAtMost(260.dp)
                  else (screenWidthDp * 0.55f).dp.coerceAtMost(240.dp)

    // 【V7.97】屏幕状态感知：熄屏时停掉60fps动画，主线程CPU从~16%压到~1%
    val screenOn = remember { mutableStateOf(true) }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        while (isActive) {
            screenOn.value = pm.isInteractive
            delay(2000)
        }
    }

    val rotation by key(screenOn.value) {
        if (screenOn.value) {
            val infiniteTransition = rememberInfiniteTransition(label = "spin")
            infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(30000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "albumRotation"
            )
        } else {
            remember { mutableFloatStateOf(0f) }
        }
    }

    // EQ status poll
    var eqEnabled by remember { mutableStateOf(false) }
    var eqPresetName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            try {
                eqEnabled = EqualizerManager.isEnabled() && EqualizerManager.isInitialized()
                eqPresetName = if (eqEnabled) EqualizerManager.getCurrentPresetName() else null
            } catch (_: Exception) { eqPresetName = null }
            kotlinx.coroutines.delay(500)
        }
    }

    // Dismiss drag (for embedded mode)
    var _dismissDragAmount by remember { mutableStateOf(0f) }
    val portraitModifier = if (onDismiss != null) {
        Modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragEnd = {
                    if (_dismissDragAmount > 300f) onDismiss.invoke()
                    _dismissDragAmount = 0f
                },
                onVerticalDrag = { _, dragAmount ->
                    if (dragAmount > 0) _dismissDragAmount += dragAmount
                    else _dismissDragAmount = maxOf(0f, _dismissDragAmount + dragAmount)
                }
            )
        }
    } else Modifier

    val bandLevelsState = remember { mutableStateOf(BandLevels()) }
    // Onset detection → drives PolarEdgeLight beats (replaces BPM timing)
    val onsetSeq = remember { mutableStateOf(0L) }
    val onsetIntensityState = remember { mutableStateOf(1f) }
    LaunchedEffect(Unit) {
        val emaSub = mutableStateOf(0f); val emaBass = mutableStateOf(0f)
        val emaMid = mutableStateOf(0f); val emaHigh = mutableStateOf(0f)
        var bgRhythm = 0f; var onsetArmed = true // onset detection locals
        MusicService.instance?.setFftCallback { fft ->
            val n = fft.size / 2 // pairs of (real, imag)
            if (n < 8) return@setFftCallback
            // Split FFT bins into 4 bands
            var s = 0f; var b = 0f; var m = 0f; var h = 0f
            var cs = 0; var cb = 0; var cm = 0; var ch = 0
            val subEnd = (n * 0.08f).toInt().coerceAtLeast(1)
            val bassEnd = (n * 0.20f).toInt().coerceAtLeast(2)
            val midEnd = (n * 0.55f).toInt().coerceAtLeast(4)
            for (i in 0 until n step 2) {
                val re = fft[i].toFloat() / 128f
                val im = if (i + 1 < fft.size) fft[i + 1].toFloat() / 128f else 0f
                val mag = kotlin.math.sqrt(re * re + im * im)
                if (i < subEnd) { s += mag; cs++ }
                else if (i < bassEnd) { b += mag; cb++ }
                else if (i < midEnd) { m += mag; cm++ }
                else { h += mag; ch++ }
            }
            val sv = (s / maxOf(1, cs)).coerceIn(0f, 1f)
            val bv = (b / maxOf(1, cb)).coerceIn(0f, 1f)
            val mv = (m / maxOf(1, cm)).coerceIn(0f, 1f)
            val hv = (h / maxOf(1, ch)).coerceIn(0f, 1f)
            // ── Onset detection (adaptive, audio-rate) ──
            // Bass-heavy weighting: bass (80–250 Hz) carries most rhythmic info
            val rhythmEnergy = sv * 0.7f + bv * 1.3f + mv * 0.25f + hv * 0.08f
            bgRhythm = bgRhythm * 0.92f + rhythmEnergy * 0.08f
            val onsetThresh = (bgRhythm * 1.35f).coerceAtLeast(0.05f)
            if (rhythmEnergy > onsetThresh && onsetArmed) {
                onsetArmed = false
                onsetSeq.value += 1
                onsetIntensityState.value = (rhythmEnergy / onsetThresh).coerceIn(0.5f, 3.0f)
            }
            if (rhythmEnergy < onsetThresh * 0.5f) { onsetArmed = true }
            emaSub.value = emaSub.value * 0.85f + sv * 0.15f
            emaBass.value = emaBass.value * 0.85f + bv * 0.15f
            emaMid.value = emaMid.value * 0.85f + mv * 0.15f
            emaHigh.value = emaHigh.value * 0.85f + hv * 0.15f
            bandLevelsState.value = BandLevels(emaSub.value, emaBass.value, emaMid.value, emaHigh.value, s.coerceAtMost(1f))
        }
        // Also keep polling RMS as fallback
        while (true) {
            try {
                val rms = MusicService.instance?.getOboePlayer()?.getRmsLevel() ?: 0f
                if (bandLevelsState.value.rms < rms) {
                    bandLevelsState.value = bandLevelsState.value.copy(rms = rms)
                }
            } catch (_: Exception) { }
            kotlinx.coroutines.delay(80)
        }
    }
    DisposableEffect(Unit) {
        onDispose { MusicService.instance?.setFftCallback(null) }
    }

    val progressFraction = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f

    // Band-reactive Aurora 鈥?4 band groups 脳 3 blobs each
    val baseHue = remember(accentColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(
            android.graphics.Color.argb(
                (accentColor.alpha * 255).toInt(), (accentColor.red * 255).toInt(),
                (accentColor.green * 255).toInt(), (accentColor.blue * 255).toInt()
            ), hsv
        )
        hsv[0]
    }
    val seedHues = remember(baseHue) {
        listOf(baseHue, (baseHue + 120f) % 360f, (baseHue + 240f) % 360f)
    }

    data class BandBlob(
        val hueOff: Float, val period: Int, val scale: Float,
        val baseX: Float, val baseY: Float, val baseAlpha: Float,
        val sat: Float, val bright: Float, val phaseOff: Float,
        val bandGroup: Int // 0=sub, 1=bass, 2=mid, 3=high
    )

    val bandReactiveBlobs = remember {
        listOf(
            BandBlob( 4f,   19000, 0.82f, 0.08f, 0.08f, 0.22f, 0.62f, 0.72f, 0.0f, 0),
            BandBlob(-3f,   23000, 0.64f, 0.88f, 0.15f, 0.24f, 0.58f, 0.76f, 1.5f, 0),
            BandBlob( 7f,   27000, 0.78f, 0.22f, 0.84f, 0.20f, 0.68f, 0.64f, 0.8f, 0),
            BandBlob(-9f,   29000, 0.56f, 0.92f, 0.78f, 0.20f, 0.60f, 0.70f, 2.3f, 1),
            BandBlob(11f,   31000, 0.70f, 0.12f, 0.92f, 0.22f, 0.65f, 0.74f, 1.1f, 1),
            BandBlob(-5f,   34000, 0.60f, 0.74f, 0.24f, 0.24f, 0.62f, 0.68f, 0.4f, 1),
            BandBlob(14f,   37000, 0.74f, 0.96f, 0.12f, 0.18f, 0.70f, 0.62f, 1.9f, 2),
            BandBlob(-8f,   41000, 0.54f, 0.36f, 0.68f, 0.20f, 0.58f, 0.78f, 0.6f, 2),
            BandBlob( 2f,   43000, 0.68f, 0.55f, 0.15f, 0.18f, 0.66f, 0.66f, 2.7f, 2),
            BandBlob(-12f,  47000, 0.58f, 0.05f, 0.58f, 0.22f, 0.64f, 0.72f, 0.2f, 3),
            BandBlob( 6f,   49000, 0.72f, 0.78f, 0.88f, 0.24f, 0.68f, 0.70f, 1.7f, 3),
            BandBlob(-15f,  53000, 0.62f, 0.32f, 0.42f, 0.20f, 0.60f, 0.76f, 3.1f, 3),
        )
    }

    val ease = CubicBezierEasing(0.42f, 0.0f, 0.58f, 1.0f)

    // Edge light preference — gates ALL visual animations (Aurora + edge glow)
    val edgeLightPref = LocalContext.current.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
    val edgeLightEnabled = edgeLightPref.getBoolean("moto_edge_light", true)

    // Aurora phases — infinite transitions only when animations are enabled
    val auroraPhases = if (edgeLightEnabled && screenOn.value) {
        bandReactiveBlobs.map { blob ->
            rememberInfiniteTransition().animateFloat(
                0f, 2f * Math.PI.toFloat(),
                infiniteRepeatable(tween(blob.period, easing = ease), RepeatMode.Restart),
                label = "ph${blob.hueOff.toInt()}"
            ).value
        }
    } else if (edgeLightEnabled) {
        // screen off: static phases, zero CPU
        remember { bandReactiveBlobs.mapIndexed { i, _ -> i * 0.4f } }
    } else {
        remember { bandReactiveBlobs.map { 0f } }
    }

    // Per-band beat flash for pulse effect
    data class BandBeat(val flash: Float = 0f, val decay: Float = 0f)
    var subBeat by remember { mutableStateOf(BandBeat()) }
    var bassBeat by remember { mutableStateOf(BandBeat()) }
    var midBeat by remember { mutableStateOf(BandBeat()) }
    var highBeat by remember { mutableStateOf(BandBeat()) }
    if (edgeLightEnabled) {
        LaunchedEffect(Unit) {
            var lastSub = 0f; var lastBass = 0f; var lastMid = 0f; var lastHigh = 0f
            while (true) {
                val b = bandLevelsState.value
                if (isPlaying) {
                    fun detectBeat(prev: Float, curr: Float, currentBeat: BandBeat): BandBeat {
                        val beat = curr > prev * 1.3f && curr > 0.06f
                        val flash = if (beat) (curr * 2f).coerceAtMost(1.2f) else 0f
                        val decay = (if (beat) 1f else currentBeat.decay * 0.85f).coerceAtLeast(0f)
                        return BandBeat(flash, decay)
                    }
                    subBeat = detectBeat(lastSub, b.sub, subBeat)
                    bassBeat = detectBeat(lastBass, b.bass, bassBeat)
                    midBeat = detectBeat(lastMid, b.mid, midBeat)
                    highBeat = detectBeat(lastHigh, b.high, highBeat)
                    lastSub = b.sub; lastBass = b.bass; lastMid = b.mid; lastHigh = b.high
                } else {
                    subBeat = BandBeat(); bassBeat = BandBeat(); midBeat = BandBeat(); highBeat = BandBeat()
                }
                kotlinx.coroutines.delay(60)
            }
        }
    }
    val beatBoosts = if (edgeLightEnabled) {
        listOf(
            if (isPlaying) (1f + subBeat.flash * subBeat.decay * 1.5f).coerceAtMost(2.5f) else 0.6f,
            if (isPlaying) (1f + bassBeat.flash * bassBeat.decay * 1.3f).coerceAtMost(2.2f) else 0.6f,
            if (isPlaying) (1f + midBeat.flash * midBeat.decay).coerceAtMost(2.0f) else 0.6f,
            if (isPlaying) (1f + highBeat.flash * highBeat.decay * 0.7f).coerceAtMost(1.8f) else 0.7f,
        )
    } else {
        listOf(0.6f, 0.6f, 0.6f, 0.7f)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Band-reactive Aurora background — only when animations enabled
        if (edgeLightEnabled) {
            Canvas(modifier = Modifier.fillMaxSize().blur(48.dp)) {
            val w = size.width; val h = size.height
            val maxDim = kotlin.math.max(w, h)
            bandReactiveBlobs.forEachIndexed { i, blob ->
                val p0 = auroraPhases[i]
                val boost = beatBoosts[blob.bandGroup]
                val bandEnergy = when (blob.bandGroup) {
                    0 -> bandLevelsState.value.sub; 1 -> bandLevelsState.value.bass
                    2 -> bandLevelsState.value.mid; else -> bandLevelsState.value.high
                }
                // Beat pulse: burst alpha on transient
                val beatFlash = when (blob.bandGroup) {
                    0 -> subBeat.flash * subBeat.decay
                    1 -> bassBeat.flash * bassBeat.decay
                    2 -> midBeat.flash * midBeat.decay
                    else -> highBeat.flash * highBeat.decay
                }
                // Sub band: drive movement speed, bass: alpha, mid: scale, high: extra alpha spike
                val speedMul = 1f + (if (blob.bandGroup == 0) bandEnergy * 1.5f else 0f)
                val pulseAlpha = if (blob.bandGroup == 3) beatFlash * 0.3f else 0f
                val alphaMul = if (blob.bandGroup == 1) 1f + bandEnergy * 0.6f else if (blob.bandGroup == 3) 1f else 1f
                val scaleMul = if (blob.bandGroup == 2) 1f + bandEnergy * 0.15f else 1f

                val cx = w * (blob.baseX +
                        (0.22f * speedMul) * Math.sin((p0 * speedMul).toDouble()).toFloat() +
                        0.11f * Math.sin((p0 * 2.3 + blob.phaseOff).toDouble()).toFloat() +
                        0.04f * Math.sin((p0 * 3.7 + blob.phaseOff * 2.1).toDouble()).toFloat())
                val cy = h * (blob.baseY +
                        (0.20f * speedMul) * Math.cos((p0 * 0.88 * speedMul).toDouble()).toFloat() +
                        0.10f * Math.cos((p0 * 2.7 + blob.phaseOff + 1.0).toDouble()).toFloat() +
                        0.05f * Math.cos((p0 * 3.1 + blob.phaseOff * 1.8).toDouble()).toFloat())
                val r = maxDim * blob.scale * scaleMul
                val finalAlpha = (blob.baseAlpha * boost * alphaMul + pulseAlpha).coerceIn(0f, 0.92f)
                val c = Color(android.graphics.Color.HSVToColor(
                    floatArrayOf(((seedHues[blob.bandGroup % 3] + blob.hueOff) % 360f + 360f) % 360f, blob.sat, blob.bright)
                ))
                drawCircle(
                    Brush.radialGradient(
                        0.0f to c.copy(alpha = finalAlpha),
                        0.20f to c.copy(alpha = finalAlpha * 0.45f),
                        0.55f to c.copy(alpha = finalAlpha * 0.08f),
                        1.0f to Color.Transparent,
                        center = Offset(cx, cy), radius = r
                    ),
                    radius = r, center = Offset(cx, cy)
                )
            }
        }
        } // if (edgeLightEnabled)

        // Vignette
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f; val cy = size.height / 2f
            val maxR = kotlin.math.sqrt(cx * cx + cy * cy)
            drawCircle(
                Brush.radialGradient(
                    0.0f to Color.Transparent,
                    0.45f to Color.Transparent,
                    1.0f to Color.Black.copy(alpha = 0.10f),
                    center = Offset(cx, cy), radius = maxR * 1.2f
                ),
                radius = maxR * 1.2f, center = Offset(cx, cy)
            )
        }

        // Edge light — Polar dual-flow (gated by same pref)
        if (!isFoldable && edgeLightEnabled) {
            val songBpm = BpmKeyCache.get(state.currentSongFilePath)?.first ?: 0
            PolarEdgeLight(accentColor = accentColor, isPlaying = isPlaying, isLandscape = isLandscape, songBpm = songBpm, positionMs = positionMs, onsetSeq = onsetSeq.value, onsetIntensity = onsetIntensityState.value)
        }

        // Layout switch
        if (isLandscape && isFoldable) {
            FoldableLayout(
                state = state,
                sharedCoverState = sharedCoverState,
                accentColor = accentColor,
                textAccentColor = textAccentColor,
                isPlaying = isPlaying,
                rotation = rotation,
                progressFraction = progressFraction,
                positionMs = positionMs,
                durationMs = durationMs,
                eqPresetName = eqPresetName,
                eqEnabled = eqEnabled,
                lyricsLines = lyricsLines,
                showMenu = showMenu,
                onToggleMenu = { showMenu = true },
                onDismissMenu = { showMenu = false },
                onNavigateBack = onNavigateBack,
                onDeleteSong = onDeleteSong,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                onSeekTo = onSeekTo,
                onNavigateToAlbum = onNavigateToAlbum,
                onNavigateToArtist = onNavigateToArtist,
                onToggleFavorite = onToggleFavorite,
                onToggleEqualizer = onToggleEqualizer,
                onShare = onShare,
                onNavigateToLyrics = onNavigateToLyrics
            )
        } else if (isLandscape) {
            LandscapeLayout(
                state = state,
                accentColor = accentColor,
                textAccentColor = textAccentColor,
                isPlaying = isPlaying,
                rotation = rotation,
                artSize = artSize,
                progressFraction = progressFraction,
                positionMs = positionMs,
                durationMs = durationMs,
                eqPresetName = eqPresetName,
                eqEnabled = eqEnabled,
                currentLyricLine = currentLyricLine,
                showMenu = showMenu,
                onToggleMenu = { showMenu = true },
                onDismissMenu = { showMenu = false },
                onNavigateBack = onNavigateBack,
                onDeleteSong = onDeleteSong,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                onSeekTo = onSeekTo,
                onNavigateToAlbum = onNavigateToAlbum,
                onNavigateToArtist = onNavigateToArtist,
                onToggleFavorite = onToggleFavorite,
                onToggleEqualizer = onToggleEqualizer,
                onShare = onShare,
                onNavigateToLyrics = onNavigateToLyrics
            )
        } else {
            PortraitLayout(
                state = state,
                accentColor = accentColor,
                textAccentColor = textAccentColor,
                isPlaying = isPlaying,
                rotation = rotation,
                artSize = artSize,
                isCompact = isCompact,
                progressFraction = progressFraction,
                positionMs = positionMs,
                durationMs = durationMs,
                eqPresetName = eqPresetName,
                eqEnabled = eqEnabled,
                currentLyricLine = currentLyricLine,
                showMenu = showMenu,
                portraitModifier = portraitModifier,
                onToggleMenu = { showMenu = true },
                onDismissMenu = { showMenu = false },
                onNavigateBack = onNavigateBack,
                onDeleteSong = onDeleteSong,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                onSeekTo = onSeekTo,
                onNavigateToAlbum = onNavigateToAlbum,
                onNavigateToArtist = onNavigateToArtist,
                onToggleFavorite = onToggleFavorite,
                onToggleEqualizer = onToggleEqualizer,
                onShare = onShare,
                onNavigateToLyrics = onNavigateToLyrics
            )
        }
    }
}

// ============================================================================
// Layout Composables
// ============================================================================

@Composable
private fun FoldableLayout(
    state: PlayerState,
    sharedCoverState: SharedCoverState?,
    accentColor: Color,
    textAccentColor: Color,
    isPlaying: Boolean,
    rotation: Float,
    progressFraction: Float,
    positionMs: Long,
    durationMs: Long,
    eqPresetName: String?,
    eqEnabled: Boolean,
    lyricsLines: List<LyricLine>,
    showMenu: Boolean,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onNavigateBack: () -> Unit,
    onDeleteSong: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleEqualizer: () -> Unit,
    onShare: () -> Unit,
    onNavigateToLyrics: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)
    ) {
        // Left: player controls
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp, end = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PlayerTopBar(
                showMenu = showMenu,
                onToggleMenu = onToggleMenu,
                onDismissMenu = onDismissMenu,
                onNavigateBack = onNavigateBack,
                onDelete = onDeleteSong,
                modifier = Modifier.padding(horizontal = 0.dp, vertical = 4.dp)
            )

            // Center: cover + song info
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Cover (fixed position, outside AnimatedContent)
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .graphicsLayer { alpha = if (true) 1f else 0f }
                        .clickable(
                            indication = rememberRipple(bounded = false, radius = 70.dp),
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onNavigateToAlbum(state.currentSongAlbum) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(140.dp).clip(CircleShape).background(accentColor.copy(alpha = 0.08f)))
                    PlayerCoverArt(
                        artUri = state.currentSongAlbumArt,
                        songTitle = state.currentSongTitle,
                        songArtist = state.currentSongArtist,
                        accentColor = accentColor,
                        isPlaying = isPlaying,
                        rotation = rotation,
                        sizeDp = 130.dp,
                        glowSizeDp = 140.dp,
                        onCoverPositioned = { offset, size ->
                            sharedCoverState?.fullCoverPosition = CoverPosition(windowOffset = offset, size = size)
                        },
                        onClick = { onNavigateToAlbum(state.currentSongAlbum) }
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Song info animated on track change
                AnimatedContent(
                    targetState = state.currentSongId,
                    transitionSpec = {
                        (fadeIn(tween(350))).togetherWith(fadeOut(tween(250)))
                    }
                ) { _ ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.currentSongTitle.ifEmpty { "Not Playing" },
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                        )
                        PlayerSongInfo(
                            artist = state.currentSongArtist,
                            format = state.currentSongFormat,
                            textAccentColor = textAccentColor
                        )
                    }
                }
            }

            // Bottom: controls
            PlayerEqLabel(eqPresetName, accentColor, textAccentColor)
            Spacer(Modifier.height(4.dp))

            PlayerProgress(
                progressFraction, durationMs, positionMs, accentColor,
                onSeekTo = { onSeekTo((durationMs * it).toLong()) },
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(Modifier.height(6.dp))

            PlayerControlBar(
                shuffleEnabled = state.shuffleEnabled,
                repeatMode = state.repeatMode,
                isPlaying = isPlaying,
                accentColor = accentColor,
                onToggleShuffle = onToggleShuffle,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onCycleRepeat = onCycleRepeat,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                playButtonSize = 48.dp
            )
            Spacer(Modifier.height(4.dp))

            PlayerBottomActions(
                eqEnabled = eqEnabled,
                isCurrentSongFavorite = state.isCurrentSongFavorite,
                onNavigateToLyrics = onNavigateToLyrics,
                onToggleEqualizer = onToggleEqualizer,
                onToggleFavorite = onToggleFavorite,
                onShare = onShare,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )

            MotorolaWatermark(accentColor = accentColor)
        }

        // Divider
        Box(
            modifier = Modifier.fillMaxHeight().width(0.5.dp)
                .background(accentColor.copy(alpha = 0.15f))
        )

        // Right: full-screen lyrics
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Lyrics - ${state.currentSongTitle.ifEmpty { "Lyrics" }}",
                    style = MaterialTheme.typography.labelLarge, color = TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onNavigateToLyrics, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Search, null, tint = TextTertiary.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                }
            }

            if (lyricsLines.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MusicNote, null, tint = TextTertiary.copy(alpha = 0.3f), modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No Lyrics", color = TextSecondary.copy(alpha = 0.5f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        LyricView(ctx).apply {
                            setThemeColor(accentColor.value.toInt())
                            setLyrics(lyricsLines)
                            onLineClick = { line -> onSeekTo(line.timeMs) }
                        }
                    },
                    update = { view -> view.updatePosition(positionMs) },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun LandscapeLayout(
    state: PlayerState,
    accentColor: Color,
    textAccentColor: Color,
    isPlaying: Boolean,
    rotation: Float,
    artSize: androidx.compose.ui.unit.Dp,
    progressFraction: Float,
    positionMs: Long,
    durationMs: Long,
    eqPresetName: String?,
    eqEnabled: Boolean,
    currentLyricLine: String?,
    showMenu: Boolean,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onNavigateBack: () -> Unit,
    onDeleteSong: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleEqualizer: () -> Unit,
    onShare: () -> Unit,
    onNavigateToLyrics: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)
    ) {
        // Left: controls
        Column(
            modifier = Modifier.weight(1.05f).fillMaxHeight().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PlayerTopBar(
                showMenu = showMenu,
                onToggleMenu = onToggleMenu,
                onDismissMenu = onDismissMenu,
                onNavigateBack = onNavigateBack,
                onDelete = onDeleteSong
            )

            // Center: song info
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    state.currentSongTitle.ifEmpty { "Not Playing" },
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
                PlayerSongInfo(
                    artist = state.currentSongArtist,
                    format = state.currentSongFormat,
                    textAccentColor = textAccentColor,
                    onArtistClick = { onNavigateToArtist(state.currentSongArtist) }
                )
                Spacer(Modifier.height(10.dp))
                PlayerInlineLyric(currentLyricLine, textAccentColor)
            }

            // Bottom: controls
            PlayerEqLabel(eqPresetName, accentColor, textAccentColor)
            PlayerProgress(
                progressFraction, durationMs, positionMs, accentColor,
                onSeekTo = { onSeekTo((durationMs * it).toLong()) }
            )
            Spacer(Modifier.height(8.dp))

            PlayerControlBar(
                shuffleEnabled = state.shuffleEnabled,
                repeatMode = state.repeatMode,
                isPlaying = isPlaying,
                accentColor = accentColor,
                onToggleShuffle = onToggleShuffle,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onCycleRepeat = onCycleRepeat,
                modifier = Modifier.fillMaxWidth(),
                playButtonSize = 52.dp
            )
            Spacer(Modifier.height(4.dp))

            PlayerBottomActions(
                eqEnabled = eqEnabled,
                isCurrentSongFavorite = state.isCurrentSongFavorite,
                onNavigateToLyrics = onNavigateToLyrics,
                onToggleEqualizer = onToggleEqualizer,
                onToggleFavorite = onToggleFavorite,
                onShare = onShare,
                modifier = Modifier.fillMaxWidth()
            )

            MotorolaWatermark(accentColor = accentColor)
        }

        // Right: album art
        PlayerCoverArt(
            artUri = state.currentSongAlbumArt,
            songTitle = state.currentSongTitle,
            songArtist = state.currentSongArtist,
            accentColor = accentColor,
            isPlaying = isPlaying,
            rotation = rotation,
            sizeDp = artSize,
            glowSizeDp = artSize + 32.dp,
            onClick = { onNavigateToAlbum(state.currentSongAlbum) },
            modifier = Modifier.weight(1f).fillMaxHeight().padding(24.dp)
        )
    }
}

@Composable
private fun PortraitLayout(
    state: PlayerState,
    accentColor: Color,
    textAccentColor: Color,
    isPlaying: Boolean,
    rotation: Float,
    artSize: androidx.compose.ui.unit.Dp,
    isCompact: Boolean,
    progressFraction: Float,
    positionMs: Long,
    durationMs: Long,
    eqPresetName: String?,
    eqEnabled: Boolean,
    currentLyricLine: String?,
    showMenu: Boolean,
    portraitModifier: Modifier,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onNavigateBack: () -> Unit,
    onDeleteSong: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleEqualizer: () -> Unit,
    onShare: () -> Unit,
    onNavigateToLyrics: () -> Unit
) {
    val hPadding = if (isCompact) 24.dp else 48.dp
    val infoPadding = if (isCompact) 20.dp else 32.dp

    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).then(portraitModifier),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.Default.KeyboardArrowDown, "Back", tint = TextPrimary, modifier = Modifier.size(32.dp))
            }
            Text("Now Playing", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
            Box {
                IconButton(onClick = onToggleMenu) {
                    Icon(Icons.Default.MoreVert, "Menu", tint = TextSecondary)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = onDismissMenu) {
                    DropdownMenuItem(
                        text = { Text("Delete Song", color = Color.Red) },
                        onClick = { onDismissMenu(); onDeleteSong() },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Song header
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = infoPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                state.currentSongTitle.ifEmpty { "Not Playing" },
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            PlayerSongInfo(
                artist = state.currentSongArtist,
                format = state.currentSongFormat,
                textAccentColor = textAccentColor,
                onArtistClick = { onNavigateToArtist(state.currentSongArtist) }
            )
            Spacer(Modifier.height(14.dp))
            PlayerInlineLyric(currentLyricLine, textAccentColor)
        }

        Spacer(Modifier.height(8.dp))

        // Album art — fixed position, does not move with lyrics
        PlayerCoverArt(
            artUri = state.currentSongAlbumArt,
            songTitle = state.currentSongTitle,
            songArtist = state.currentSongArtist,
            accentColor = accentColor,
            isPlaying = isPlaying,
            rotation = rotation,
            sizeDp = artSize,
            glowSizeDp = artSize + 36.dp,
            onClick = { onNavigateToAlbum(state.currentSongAlbum) },
            modifier = Modifier.fillMaxWidth().height(artSize + 36.dp)
        )

        Spacer(Modifier.weight(1f))

        // EQ label
        PlayerEqLabel(eqPresetName, accentColor, textAccentColor)
        Spacer(Modifier.height(8.dp))

        // Progress
        PlayerProgress(
            progressFraction, durationMs, positionMs, accentColor,
            onSeekTo = { onSeekTo((durationMs * it).toLong()) },
            modifier = Modifier.padding(horizontal = infoPadding)
        )
        Spacer(Modifier.height(16.dp))

        // Controls
        PlayerControlBar(
            shuffleEnabled = state.shuffleEnabled,
            repeatMode = state.repeatMode,
            isPlaying = isPlaying,
            accentColor = accentColor,
            onToggleShuffle = onToggleShuffle,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onCycleRepeat = onCycleRepeat,
            modifier = Modifier.fillMaxWidth().padding(horizontal = infoPadding)
        )
        Spacer(Modifier.height(8.dp))

        // Bottom actions
        PlayerBottomActions(
            eqEnabled = eqEnabled,
            isCurrentSongFavorite = state.isCurrentSongFavorite,
            onNavigateToLyrics = onNavigateToLyrics,
            onToggleEqualizer = onToggleEqualizer,
            onToggleFavorite = onToggleFavorite,
            onShare = onShare,
            modifier = Modifier.fillMaxWidth().padding(horizontal = hPadding)
        )

        MotorolaWatermark(accentColor = accentColor)
    }
}

// ============================================================================
// Motorola Device Detection & Branding
// ============================================================================

private val MOTOROLA_DEVICE_MAP = mapOf(
    "XT2503" to "ThinkPhone 25",
    "XT2651" to "Razr Fold",
    "XT2651-1" to "Razr Fold",
    "XT2651-4" to "Razr Fold",
    "XT2453" to "Edge 50 Pro",
    "XT2455" to "Edge 50 Ultra",
    "XT2451" to "Edge 50 Fusion",
    "XT2401" to "Edge 50 Ultra",
    "XT2409" to "Edge 50 Neo",
    "XT2407" to "Edge 50",
    "XT2507" to "Edge 60 Pro",
    "XT2501" to "Edge 60 Ultra",
    "XT2541" to "Edge 60",
    "XT2543" to "Edge 60 Fusion",
    "XT2555" to "Edge 60 Stylus",
    "XT2343" to "Edge 40 Pro",
    "XT2301" to "Razr Plus",
    "XT2303" to "Razr 40",
    "XT2323" to "Edge 40 Neo",
    "XT2423" to "Moto G75",
    "XT2337" to "Moto G54",
    "XT2369" to "Moto G55",
    "XT2341" to "Moto G64",
    "XT2335" to "Moto G34",
    "XT2427" to "Moto G35",
)

internal fun getMotorolaDeviceName(): String? {
    val manufacturer = Build.MANUFACTURER.lowercase()
    if (!manufacturer.contains("motorola")) return null
    val model = Build.MODEL
    MOTOROLA_DEVICE_MAP[model]?.let { return@let it }
    for ((key, value) in MOTOROLA_DEVICE_MAP) {
        if (model.startsWith(key, ignoreCase = true)) return value
    }
    return model
}

@Composable
private fun MotorolaWatermark(accentColor: Color) {
    val deviceName = remember { getMotorolaDeviceName() } ?: return
    val montserrat = remember { FontFamily(Font(R.font.montserrat_regular)) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 64.dp)
                .height(0.5.dp)
                .fillMaxWidth()
                .background(accentColor.copy(alpha = 0.2f))
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Motorola $deviceName",
            fontFamily = montserrat,
            fontSize = MaterialTheme.typography.titleMedium.fontSize,
            color = lerp(accentColor, Color.White, 0.65f),
            modifier = Modifier.padding(bottom = 12.dp)
        )
    }
}

// ============================================================================
// Moto Edge Light (curved edge ambient glow)
// ============================================================================

@Composable


private fun PolarEdgeLight(accentColor: Color, isPlaying: Boolean, isLandscape: Boolean = false, songBpm: Int = 0, positionMs: Long = 0L, onsetSeq: Long = 0L, onsetIntensity: Float = 1f) {
    val baseHue = remember(accentColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(
            android.graphics.Color.argb(
                (accentColor.alpha * 255).toInt(), (accentColor.red * 255).toInt(),
                (accentColor.green * 255).toInt(), (accentColor.blue * 255).toInt()
            ), hsv
        )
        hsv[0]
    }

    val leftHueOffset = remember { kotlin.random.Random.nextFloat() * 180f - 90f }
    val rightHueOffset = remember { kotlin.random.Random.nextFloat() * 180f - 90f }

    var tick by remember { mutableStateOf(0L) }

    // ── Animation state ──
    val leftOffset = remember { mutableStateOf(0f) }
    val rightOffset = remember { mutableStateOf(0.45f) }
    val prevLandscape = remember { mutableStateOf(isLandscape) }
    val leftHue = remember { mutableStateOf(baseHue + leftHueOffset) }
    val rightHue = remember { mutableStateOf(baseHue + rightHueOffset) }
    val leftBreath = remember { mutableStateOf(1f) }
    val rightBreath = remember { mutableStateOf(1f) }
    val leftBeat = remember { mutableStateOf(0f) }
    val rightBeat = remember { mutableStateOf(0f) }
    val leftStrobe = remember { mutableStateOf(0f) }
    val rightStrobe = remember { mutableStateOf(0f) }

    val effectiveBpm = if (songBpm in 40..220) songBpm else 128
    val beatMs = 60000f / effectiveBpm

    val baseIntensity = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.15f,
        animationSpec = androidx.compose.animation.core.tween(1500),
        label = "edgeIntensity"
    )

    LaunchedEffect(isPlaying, positionMs) {
        var lastFrame = 0L
        var lastBeatL = -1f; var lastBeatR = -1f
        var lastOnsetId = 0L; var onsetSide = false
        val beatHalfLife = 0.045f
        val breathPeriod = 6f

        while (true) {
            withFrameMillis { frameTime ->
                if (lastFrame == 0L) { lastFrame = frameTime; return@withFrameMillis }
                val dt = (frameTime - lastFrame).coerceAtMost(50L) / 1000f
                lastFrame = frameTime
                val t = frameTime * 0.001f
                val posMs = positionMs.toFloat()

                // ── Rotation detect: swap offsets when landscape↔portrait ──
                if (prevLandscape.value != isLandscape) {
                    // flip both so light appears to continue circling around the screen
                    val oldL = leftOffset.value
                    leftOffset.value = rightOffset.value
                    rightOffset.value = oldL
                    prevLandscape.value = isLandscape
                }

                // Breathing
                val breathPhaseL = t / breathPeriod * 2f * kotlin.math.PI.toFloat()
                val breathPhaseR = breathPhaseL + kotlin.math.PI.toFloat()
                leftBreath.value = ((kotlin.math.sin(breathPhaseL) + 1f) * 0.5f).coerceIn(0.04f, 1f)
                rightBreath.value = ((kotlin.math.sin(breathPhaseR) + 1f) * 0.5f).coerceIn(0.04f, 1f)

                // Beat decay — fast sharp punch
                val beatDecay = kotlin.math.exp((-dt * kotlin.math.ln(2f)) / beatHalfLife).toFloat()
                leftBeat.value = (leftBeat.value * beatDecay).coerceAtMost(6f)
                rightBeat.value = (rightBeat.value * beatDecay).coerceAtMost(6f)
                leftStrobe.value = (leftStrobe.value * beatDecay * 0.5f).coerceAtMost(1f)
                rightStrobe.value = (rightStrobe.value * beatDecay * 0.5f).coerceAtMost(1f)

                // ── Beat trigger: onset-driven (primary) + BPM fallback ──
                if (onsetSeq != lastOnsetId) {
                    lastOnsetId = onsetSeq
                    onsetSide = !onsetSide
                    val punch = onsetIntensity.coerceIn(0.4f, 3.5f) * 6f
                    if (onsetSide) {
                        leftBeat.value = punch
                        leftStrobe.value = 1f
                    } else {
                        rightBeat.value = punch
                        rightStrobe.value = 1f
                    }
                } else {
                    // BPM fallback — when no FFT onsets (quiet ambient / fade-out)
                    val beatPhaseFull = posMs / beatMs
                    val beatPhase = beatPhaseFull % 1f
                    val offBeatPhase = ((beatPhaseFull + 0.5f) % 1f)
                    if (lastBeatL >= 0f && beatPhase < lastBeatL) {
                        leftBeat.value = 6f
                        leftStrobe.value = 1f
                    }
                    if (lastBeatR >= 0f && offBeatPhase < lastBeatR) {
                        rightBeat.value = 6f
                        rightStrobe.value = 1f
                    }
                    lastBeatL = beatPhase; lastBeatR = offBeatPhase
                }

                // Scroll — faster during beat surge
                val baseSpeed = if (isPlaying) 1f / 7f else 1f / 60f
                val speedBoost = (leftStrobe.value + rightStrobe.value) * 0.3f
                // ── Direction reversal per side for looping effect ──
                // Vertical (portrait): left scrolls down, right scrolls up
                // Horizontal (landscape): top scrolls right, bottom scrolls left
                val leftDir = if (isLandscape) 1f else 1f     // top(landscape)/left(portrait)
                val rightDir = if (isLandscape) -1f else -1f   // bottom(landscape)/right(portrait)
                leftOffset.value = ((leftOffset.value + leftDir * (baseSpeed + speedBoost) * (0.5f + leftBreath.value * 0.5f) * dt) % 1f + 1f) % 1f
                rightOffset.value = ((rightOffset.value + rightDir * (baseSpeed + speedBoost) * (0.5f + rightBreath.value * 0.5f) * dt) % 1f + 1f) % 1f

                // Hue drift — shift faster on beat
                val hueSpeedL = 5f + leftBeat.value * 15f
                val hueSpeedR = -6f - rightBeat.value * 12f
                leftHue.value = ((baseHue + leftHueOffset + t * hueSpeedL) % 360f + 360f) % 360f
                rightHue.value = ((baseHue + rightHueOffset + t * hueSpeedR) % 360f + 360f) % 360f

                tick = frameTime
            }
        }
    }

    // Brush: color gradient along the bar axis
    fun axisBrush(
        hue: Float, gradLen: Float, center: Float, axisLen: Float,
        glowAlpha: Float, baseAlpha: Float, isVertical: Boolean
    ): Brush {
        val c0 = center - gradLen * 0.55f
        val c2 = center + gradLen * 0.55f
        val s0 = (c0 / axisLen).coerceIn(0f, 1f)
        val s1 = ((center - gradLen * 0.08f) / axisLen).coerceIn(0f, 1f)
        val s2 = ((center + gradLen * 0.08f) / axisLen).coerceIn(0f, 1f)
        val s3 = (c2 / axisLen).coerceIn(0f, 1f)

        // color-shift: hue varies ±18° along the bar for richer look
        val hueHead = (hue + 18f + 360f) % 360f
        val hueCore = hue
        val hueTail = (hue - 18f + 360f) % 360f
        val barS = 0.55f; val barV = 0.10f
        val glowS = 0.18f; val glowV = 0.98f
        val whiteGlow = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.05f, 1.0f)))

        val stops = mutableListOf<Pair<Float, Color>>()
        if (s0 > 0f) stops.add(0f to Color.Transparent)
        stops.add(s0.coerceAtLeast(0f) to Color(android.graphics.Color.HSVToColor(floatArrayOf(hueTail, barS, barV))).copy(alpha = baseAlpha * 0.5f))
        stops.add(s1 to whiteGlow.copy(alpha = glowAlpha))
        stops.add(s2 to whiteGlow.copy(alpha = glowAlpha))
        stops.add(s3.coerceAtMost(1f) to Color(android.graphics.Color.HSVToColor(floatArrayOf(hueHead, barS, barV))).copy(alpha = baseAlpha * 0.5f))
        if (s3 < 1f) stops.add(1f to Color.Transparent)

        return if (isVertical) {
            Brush.verticalGradient(*stops.toTypedArray(), startY = 0f, endY = axisLen)
        } else {
            Brush.horizontalGradient(*stops.toTypedArray(), startX = 0f, endX = axisLen)
        }
    }

    @Suppress("UNUSED_VARIABLE")
    val _forceRecompose = tick
    val intensity = baseIntensity.value

    // ── Draw ──
    Box(Modifier.fillMaxSize()) {
        // Ambient layer — faint base glow with large bloom
        Canvas(Modifier.fillMaxSize().blur(18.dp)) {
            val w = size.width; val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas
            val isVertical = !isLandscape
            val axisLen = if (isVertical) h else w
            val barTk = (if (isVertical) w else h) * 0.045f
            val ambAlpha = intensity * 0.06f
            if (ambAlpha < 0.003f) return@Canvas
            drawEdgeAmbient(axisLen, barTk, intensity, isVertical, w, h, lHue = leftHue.value, rHue = rightHue.value, lBreath = leftBreath.value, rBreath = rightBreath.value, lBeat = leftBeat.value, rBeat = rightBeat.value)
        }

        // Glow layer — main flow bar with bloom
        Canvas(Modifier.fillMaxSize().blur(10.dp)) {
            val w = size.width; val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas
            val isVertical = !isLandscape
            val axisLen = if (isVertical) h else w
            val barTk = (if (isVertical) w else h) * 0.04f
            drawEdgeFlow(true, leftOffset.value, barTk, axisLen, intensity, leftBreath.value, leftBeat.value, leftHue.value, isVertical, w, h)
            drawEdgeFlow(false, rightOffset.value, barTk, axisLen, intensity, rightBreath.value, rightBeat.value, rightHue.value, isVertical, w, h)
        }
    }
}

// Ambient: faint constant bar glow, pulsing with breath
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEdgeAmbient(
    axisLen: Float, barTk: Float, intensity: Float,
    isVertical: Boolean, w: Float, h: Float,
    lHue: Float, rHue: Float, lBreath: Float, rBreath: Float,
    lBeat: Float = 0f, rBeat: Float = 0f
) {
    val ambAlphaMul = (0.4f + lBreath * 0.6f) * (1f + lBeat * 0.4f)
    val rAlphaMul = (0.4f + rBreath * 0.6f) * (1f + rBeat * 0.4f)
    val ambSat = (0.55f * (1f + lBeat * 0.2f)).coerceAtMost(1f)
    val rSat = (0.55f * (1f + rBeat * 0.2f)).coerceAtMost(1f)
    val ambL = Color(android.graphics.Color.HSVToColor(floatArrayOf(lHue, ambSat, 0.07f))).copy(alpha = intensity * 0.06f * ambAlphaMul)
    val ambR = Color(android.graphics.Color.HSVToColor(floatArrayOf(rHue, rSat, 0.07f))).copy(alpha = intensity * 0.06f * rAlphaMul)
    if (isVertical) {
        drawRect(ambL, topLeft = Offset(0f, 0f), size = Size(barTk, axisLen))
        drawRect(ambR, topLeft = Offset(w - barTk, 0f), size = Size(barTk, axisLen))
    } else {
        drawRect(ambL, topLeft = Offset(0f, 0f), size = Size(axisLen, barTk))
        drawRect(ambR, topLeft = Offset(0f, h - barTk), size = Size(axisLen, barTk))
    }
}

// Flow: main highlight bar with hue gradient
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEdgeFlow(
    isLeft: Boolean, offset: Float, barTk: Float, axisLen: Float,
    intensity: Float, breath: Float, beat: Float, hue: Float,
    isVertical: Boolean, w: Float, h: Float
) {
    val gradLen = axisLen * (0.22f + breath * 0.28f + beat * 0.12f)
    val center = offset * (axisLen + gradLen) - gradLen * 0.5f

    val glowAlpha = (intensity * (0.25f + breath * 0.75f) * (1f + beat * 1.5f)).coerceIn(0f, 1f)
    if (glowAlpha < 0.015f) return

    // hue-gradient brush
    val c0 = center - gradLen * 0.55f
    val c2 = center + gradLen * 0.55f
    val s0 = (c0 / axisLen).coerceIn(0f, 1f)
    val s1 = ((center - gradLen * 0.08f) / axisLen).coerceIn(0f, 1f)
    val s2 = ((center + gradLen * 0.08f) / axisLen).coerceIn(0f, 1f)
    val s3 = (c2 / axisLen).coerceIn(0f, 1f)

    val hueHead = (hue + 20f + 360f) % 360f
    val hueTail = (hue - 20f + 360f) % 360f
    // Beat surge: saturation & value spike on beat
    val beatSurge = (1f + beat * 0.35f).coerceAtMost(3.1f)
    val coreSat = (0.40f * beatSurge).coerceAtMost(0.95f)
    val coreVal = 0.90f
    val glowCore = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, coreSat, coreVal)))
    val edgeSat = (0.50f * beatSurge).coerceAtMost(0.95f)
    val glowEdge = Color(android.graphics.Color.HSVToColor(floatArrayOf(hueHead, edgeSat, 0.85f)))

    val stops = mutableListOf<Pair<Float, Color>>()
    if (s0 > 0.005f) stops.add(0f to Color.Transparent)
    stops.add(s0.coerceAtLeast(0f) to glowEdge.copy(alpha = glowAlpha * 0.15f))
    stops.add(s1 to glowCore.copy(alpha = glowAlpha))
    stops.add(s2 to glowCore.copy(alpha = glowAlpha))
    stops.add(s3.coerceAtMost(1f) to glowEdge.copy(alpha = glowAlpha * 0.15f))
    if (s3 < 0.995f) stops.add(1f to Color.Transparent)

    val brush = if (isVertical) {
        Brush.verticalGradient(*stops.toTypedArray(), startY = 0f, endY = axisLen)
    } else {
        Brush.horizontalGradient(*stops.toTypedArray(), startX = 0f, endX = axisLen)
    }

    if (isVertical) {
        drawRect(brush, topLeft = Offset(if (isLeft) 0f else w - barTk, 0f), size = Size(barTk, axisLen))
    } else {
        drawRect(brush, topLeft = Offset(0f, if (isLeft) 0f else h - barTk), size = Size(axisLen, barTk))
    }
}







