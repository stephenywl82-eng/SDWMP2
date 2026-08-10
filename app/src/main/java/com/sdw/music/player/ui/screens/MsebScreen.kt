package com.sdw.music.player.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdw.music.player.MsebCalculator
import com.sdw.music.player.MsebParams
import com.sdw.music.player.MsebPreset
import com.sdw.music.player.MsebPresets
import com.sdw.music.player.MusicService
import kotlinx.coroutines.delay

private val BAND_LABELS = listOf(
    "60 Hz" to "Sub",
    "200 Hz" to "Body",
    "2.5 kHz" to "Vocal",
    "5.8 kHz" to "Sib",
    "10 kHz" to "Air"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MsebScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var params by remember { mutableStateOf(MsebCalculator.load(context)) }
    var enabled by remember { mutableStateOf(MsebCalculator.isEnabled(context)) }
    var savedName by remember { mutableStateOf("") }

    // Preset list (built-in + user)
    val presets = remember { MsebPresets.getAll(context) }

    // Save dialog
    var showSaveDialog by remember { mutableStateOf(false) }

    // FFT band levels — 8 bands from native
    var bandLevels by remember { mutableStateOf(floatArrayOf(0f,0f,0f,0f,0f,0f,0f,0f)) }

    LaunchedEffect(Unit) {
        while (true) {
            val oboe = MusicService.instance?.oboeDirectPlayer ?: break
            bandLevels = oboe.getBands8()
            delay(100)
        }
    }

    LaunchedEffect(Unit) {
        if (enabled && !params.isFlat) applyMseb(params)
    }

    // 【V7.200】Arm MSEB guard in C++ layer — prevents nativeSetDspMode / nativeResetDspEq5Band
    // from clearing the 5-band EQ coefficients while MSEB screen is open.
    // Guard is released on dispose (navigate away).
    DisposableEffect(Unit) {
        val oboe = MusicService.instance?.oboeDirectPlayer
        oboe?.setMsebActive(true)
        onDispose {
            oboe?.setMsebActive(false)
        }
    }

    fun update(newParams: MsebParams) {
        params = newParams
        MsebCalculator.save(context, newParams)
        if (enabled) applyMseb(newParams)
    }

    fun toggleEnabled(on: Boolean) {
        enabled = on
        MsebCalculator.setEnabled(context, on)
        val svc = MusicService.instance
        val oboe = svc?.oboeDirectPlayer
        if (on) applyMseb(params)
        else {
            svc?.setDspEqEnabled(false)  // Sync flag
            oboe?.resetDspEq5Band()
            oboe?.setDspEnabled(false)
        }
    }

    fun applyPreset(preset: MsebPreset) {
        update(preset.params)
        savedName = preset.name
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MSEB") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back", color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    if (!params.isFlat) {
                        TextButton(onClick = { savedName = ""; showSaveDialog = true }) {
                            Text("Save", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Preset Chips (horizontal scroll) ──
            Text(
                "Presets",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(presets) { preset ->
                    val isActive = preset.params == params
                    AssistChip(
                        onClick = { applyPreset(preset) },
                        label = {
                            Text(
                                preset.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = if (isActive) {
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else AssistChipDefaults.assistChipColors()
                    )
                }
            }

            // ── A/B Bypass Toggle ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (enabled)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (enabled) "MSEB Active (B)" else "Raw Bit-Perfect (A)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (enabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            MsebCalculator.describe(params),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { toggleEnabled(it) })
                }
            }

            // ── 10 Sliders ──
            val alpha = if (enabled) 1f else 0.45f

            Column(modifier = Modifier.alpha(alpha)) {
                MsebSlider("Temperature", "Cool", "Warm", "中低频与高频能量比例", params.temperature) { update(params.copy(temperature = it)) }
                MsebSlider("Thickness", "Thin", "Thick", "人声与乐器基音区厚薄", params.thickness) { update(params.copy(thickness = it)) }
                MsebSlider("Vocal", "Distant", "Forward", "人声结像前后位置", params.vocalForward) { update(params.copy(vocalForward = it)) }
                MsebSlider("Sub-bass", "Lean", "Deep", "超低频深度与冲击力", params.subBass) { update(params.copy(subBass = it)) }
                MsebSlider("Bass Texture", "Loose", "Tight", "低音弹性与松紧度", params.bassTexture) { update(params.copy(bassTexture = it)) }
                MsebSlider("Sibilance", "Smooth", "Bright", "全局齿音明亮度", params.sibilance) { update(params.copy(sibilance = it)) }

                Divider(modifier = Modifier.padding(vertical = 4.dp))

                MsebSlider("Sibilance LF", "Soft", "Crisp", "精准狙击 ~6kHz 毛刺", params.sibilanceLf) { update(params.copy(sibilanceLf = it)) }
                MsebSlider("Sibilance HF", "Soft", "Crisp", "精准狙击 ~9kHz 刺耳声", params.sibilanceHf) { update(params.copy(sibilanceHf = it)) }
                MsebSlider("Female Overtone", "Dry", "Sweet", "女声甜美度与华丽感", params.femaleOvertones) { update(params.copy(femaleOvertones = it)) }
                MsebSlider("Air", "Dark", "Airy", "极高频通透度与解析力", params.air) { update(params.copy(air = it)) }

                MsebSlider("Impulse", "Soft", "Fast", "瞬态响应速度与凌厉感", params.impulseResponse) { update(params.copy(impulseResponse = it)) }
            }

            // ── Reset ──
            if (!params.isFlat) {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { savedName = ""; update(MsebParams()) },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Reset to Flat")
                }
            }

            // ── FFT Spectrum ──
            Spacer(modifier = Modifier.height(8.dp))
            MsebFftBars(bandLevels)
            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    // ── Save Preset Dialog ──
    if (showSaveDialog) {
        var nameText by remember { mutableStateOf(savedName) }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Preset") },
            text = {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text("Preset name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = nameText.trim()
                    if (name.isNotEmpty()) {
                        MsebPresets.save(context, name, params)
                        savedName = name
                        showSaveDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// ── FFT Bars ──

@Composable
private fun MsebFftBars(levels: FloatArray) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Real-time FFT",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            // FFT bars — fixed-height container, bars animate; labels stay still
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                for (i in levels.indices) {
                    val level = levels[i].coerceIn(0f, 1f)
                    val animatedLevel by animateFloatAsState(
                        targetValue = level.coerceIn(0.04f, 1f),
                        animationSpec = tween(120),
                        label = "fft_$i"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(animatedLevel)
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    )
                                )
                            )
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Static frequency labels — hardcoded, zero recomposition
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Text("60 Hz",    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("120 Hz",   style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("250 Hz",   style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("500 Hz",   style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("2 kHz",    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("6 kHz",    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("12 kHz",   style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("20 kHz",   style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

// ── Slider ──

@Composable
private fun MsebSlider(
    label: String,
    leftLabel: String,
    rightLabel: String,
    description: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                String.format("%+.1f", value),
                style = MaterialTheme.typography.labelLarge,
                color = if (value == 0f) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
            )
        }
        Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant)
        Slider(value = value, onValueChange = onValueChange, valueRange = -10f..10f, modifier = Modifier.fillMaxWidth())
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(leftLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant)
            Text(rightLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

// ── DSP ──

private fun applyMseb(params: MsebParams) {
    val svc = MusicService.instance ?: return
    val oboe = svc.oboeDirectPlayer ?: return
    svc.setDspEqEnabled(true)  // Sync flag so PlayerScreen polling doesn't reset MSEB
    oboe.setDspEnabled(true)
    oboe.setDspEq5Band(MsebCalculator.calculateGains(params), MsebCalculator.BAND_FREQS)
}
