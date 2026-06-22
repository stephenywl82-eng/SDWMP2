package com.sdw.music.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sdw.music.player.EqualizerManager
import com.sdw.music.player.MusicService
import com.sdw.music.player.OboeDirectPlayer
import com.sdw.music.player.core.audio.AutoEqPresetManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val isOboeMode = EqualizerManager.isOboeMode(context)

    if (isOboeMode) {
        OboeEqFullScreen(onBack)
    } else {
        EqualizerPresetsScreen(onBack)
    }
}

// ================ Oboe 独占模式：DSP + Equalizer预设合并 ================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OboeEqFullScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dspPrefs = context.getSharedPreferences("dsp_mode", android.content.Context.MODE_PRIVATE)
    val eqPrefs = context.getSharedPreferences("dsp_eq", android.content.Context.MODE_PRIVATE)
    val presets = remember { EqualizerManager.PRESETS }

    // AutoEQ 状态
    val autoEqBrands = remember { AutoEqPresetManager.getBrands() }
    var autoEqExpandedBrand by remember { mutableStateOf<String?>(null) }
    var currentAutoEqPreset by remember { mutableStateOf(EqualizerManager.getAutoEqPreset(context)) }

    var dspMode by remember { mutableIntStateOf(dspPrefs.getInt("mode", -1)) }
    var dspEnabled by remember { mutableStateOf(eqPrefs.getBoolean("enabled", false)) }

    val eqEnabled by EqualizerManager.enabledFlow.collectAsState()
    var currentPresetId by remember { mutableStateOf(EqualizerManager.getCurrentPresetId(context)) }
    val dspLabels = listOf("Off", "Steven", "Cat")

    LaunchedEffect(Unit) {
        EqualizerManager.restoreSettings(context)
        currentPresetId = EqualizerManager.getCurrentPresetId(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DSP Equalizer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Text(
                        text = if (eqEnabled) "On" else "Off",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Switch(
                        checked = eqEnabled,
                        onCheckedChange = { on ->
                            EqualizerManager.setEnabled(on, context)
                            if (on && currentPresetId == "flat") {
                                presets.find { it.id != "flat" }?.let {
                                    currentPresetId = it.id
                                    EqualizerManager.applyPreset(it.id, context)
                                }
                            }
                        },
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // === DSP Mode选择器 ===
            item {
                Text(
                    text = "DSP Mode",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    dspLabels.forEachIndexed { idx, label ->
                        val selected = (dspMode + 1) == idx && !eqEnabled
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val newMode = idx - 1
                                dspMode = newMode
                                dspPrefs.edit().putInt("mode", newMode).apply()
                                val dspEnum = when (newMode) {
                                    -1 -> OboeDirectPlayer.DspMode.OFF
                                    0 -> OboeDirectPlayer.DspMode.STEVEN_SPECIAL
                                    1 -> OboeDirectPlayer.DspMode.CAT_MODE
                                    else -> OboeDirectPlayer.DspMode.OFF
                                }
                                MusicService.instance?.oboeDirectPlayer?.setDspMode(dspEnum)
                                dspEnabled = newMode >= 0
                                eqPrefs.edit().putBoolean("enabled", newMode >= 0).apply()
                            },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }


            // === 分隔 ===
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            // === 28 种品牌Equalizer预设 ===
            item {
                Text(
                    text = "Brand Presets",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            items(presets) { preset ->
                val isSelected = currentPresetId == preset.id && eqEnabled
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = if (isSelected) 4.dp else 0.dp,
                    onClick = {
                        currentPresetId = preset.id
                        EqualizerManager.applyPreset(preset.id, context)
                    }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = preset.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = preset.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // === AutoEQ Headphone Correction ===
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AutoEQ Headphone Correction",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "10-band Precise EQ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // AutoEQ 预设列表（按品牌分组）

            items(autoEqBrands, key = { "auto_eq_brand_$it" }) { brand ->
                    val isExpanded = autoEqExpandedBrand == brand
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = if (isExpanded) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        onClick = {
                            autoEqExpandedBrand = if (isExpanded) null else brand
                        }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = brand,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isExpanded) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (isExpanded) {
                                Spacer(modifier = Modifier.height(8.dp))
                                val brandPresets = AutoEqPresetManager.presets.filter { it.brand == brand }
                                brandPresets.forEach { aPreset ->
                                    val isActive = currentAutoEqPreset == aPreset.name
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        shape = MaterialTheme.shapes.small,
                                        color = if (isActive) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                        tonalElevation = if (isActive) 2.dp else 0.dp,
                                        onClick = {
                                            if (isActive) {
                                                EqualizerManager.clearAutoEq(context)
                                                currentAutoEqPreset = null
                                            } else {
                                                EqualizerManager.applyAutoEqPreset(aPreset.name, context)
                                                currentAutoEqPreset = aPreset.name
                                            }
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = aPreset.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                                                    else MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = "${aPreset.type} · Preamp ${aPreset.preamp}dB · ${aPreset.filters.size} bands",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                )
                                            }
                                            if (isActive) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.ArrowBack,
                                                    contentDescription = "Active",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            // AutoEQ 说明
            item {
                Text(
                    text = "Based on AutoEq project · 10-band Parametric EQ · Harman target curve",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            // Oboe 模式说明
            item {
                Text(
                    text = "Using Oboe DSP hardware 5-band EQ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                )
            }
        }
    }
}

// ================ Oboe 独占模式：DSP 控制（旧版，保留） ================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OboeDspScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dspPrefs = context.getSharedPreferences("dsp_mode", android.content.Context.MODE_PRIVATE)
    val eqPrefs = context.getSharedPreferences("dsp_eq", android.content.Context.MODE_PRIVATE)

    var dspMode by remember { mutableIntStateOf(dspPrefs.getInt("mode", -1)) }
    var dspEnabled by remember { mutableStateOf(eqPrefs.getBoolean("enabled", false)) }

    val dspLabels = listOf("Off", "Steven Special", "Cat Mode")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DSP Effects") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Text(
                        text = if (dspEnabled) "On" else "Off",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Switch(
                        checked = dspEnabled,
                        onCheckedChange = { on ->
                            dspEnabled = on
                            eqPrefs.edit().putBoolean("enabled", on).apply()
                            MusicService.instance?.setDspEqEnabled(on)
                        },
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 模式说明
            item {
                Text(
                    text = "Oboe Exclusive mode\nEQ presets unavailable, use DSP hardware audio",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                )
            }

            // DSP Mode选择
            item {
                Text(
                    text = "DSP Mode",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    dspLabels.forEachIndexed { idx, label ->
                        val selected = (dspMode + 1) == idx  // mode: -1/0/1 → idx: 0/1/2
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val newMode = idx - 1
                                dspMode = newMode
                                dspPrefs.edit().putInt("mode", newMode).apply()
                                // 应用 DSP Mode到 OboeDirectPlayer
                                val dspEnum = when (newMode) {
                                    -1 -> OboeDirectPlayer.DspMode.OFF
                                    0 -> OboeDirectPlayer.DspMode.STEVEN_SPECIAL
                                    1 -> OboeDirectPlayer.DspMode.CAT_MODE
                                    else -> OboeDirectPlayer.DspMode.OFF
                                }
                                MusicService.instance?.oboeDirectPlayer?.setDspMode(dspEnum)
                            },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 当前状态提示
            item {
                Spacer(Modifier.height(24.dp))
                val currentLabel = dspLabels.getOrElse(dspMode + 1) { "Close" }
                Text(
                    text = if (dspEnabled) "Current: $currentLabel" else "Current: DSP off",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ================ 非 Oboe 模式：Android Equalizer 预设 ================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqualizerPresetsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val presets = remember { EqualizerManager.PRESETS }
    val eqAvailable by EqualizerManager.initialized.collectAsState()
    val enabled by EqualizerManager.enabledFlow.collectAsState()
    var currentPresetId by remember { mutableStateOf(EqualizerManager.getCurrentPresetId(context)) }

    // 【V7.XX】打OnEqualizer页面时主动初始化 EQ（onAudioSessionIdChanged 不可靠）
    LaunchedEffect(Unit) {
        if (!EqualizerManager.isInitialized() && !EqualizerManager.isOboeMode(context)) {
            var attempts = 0
            while (attempts < 10 && !EqualizerManager.isInitialized()) {
                val sessionId = MusicService.instance?.getAudioSessionId() ?: 0
                if (sessionId != 0) {
                    EqualizerManager.init(sessionId)
                    EqualizerManager.restoreSettings(context)
                    currentPresetId = EqualizerManager.getCurrentPresetId(context)
                }
                kotlinx.coroutines.delay(500)
                attempts++
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Equalizer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Text(
                        text = if (enabled) "On" else "Off",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            EqualizerManager.setEnabled(on, context)
                            if (on && currentPresetId == "flat") {
                                val first = presets.find { it.id != "flat" }
                                first?.let {
                                    currentPresetId = it.id
                                    EqualizerManager.applyPreset(it.id, context)
                                }
                            }
                        },
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(presets) { preset ->
                val isSelected = currentPresetId == preset.id && enabled
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = if (isSelected) 4.dp else 0.dp,
                    onClick = {
                        currentPresetId = preset.id
                        EqualizerManager.applyPreset(preset.id, context)
                    }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = preset.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = preset.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // EQ Unavailable时提示
            if (!eqAvailable) {
                item {
                    Text(
                        text = "Android EQ unavailable.\nSwitch audio output to AAudio / OpenSL ES / AudioTrack.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}


