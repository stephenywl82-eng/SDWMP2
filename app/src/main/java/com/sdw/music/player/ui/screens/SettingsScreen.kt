package com.sdw.music.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdw.music.player.R
import com.sdw.music.player.EqualizerManager
import com.sdw.music.player.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit, onNavigateToAudioDiagnostic: (() -> Unit)? = null) {
    val context = LocalContext.current
    var refreshTrigger by remember { mutableStateOf(0) }

    // Preferences state
    var minDuration by remember(refreshTrigger) {
        mutableStateOf(
            context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
                .getInt("min_duration", 10)
        )
    }
    var dspMode by remember(refreshTrigger) {
        mutableStateOf(
            context.getSharedPreferences("dsp_mode", android.content.Context.MODE_PRIVATE)
                .getInt("mode", -1)
        )
    }
    var audioOutput by remember(refreshTrigger) {
        mutableStateOf(
            context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                .getString("audio_output", "Oboe Exclusive") ?: "Oboe Exclusive"
        )
    }
    val eqPresetId by remember(refreshTrigger) {
        mutableStateOf(EqualizerManager.getCurrentPresetId(context))
    }
    val eqPresetName = EqualizerManager.PRESETS.find { it.id == eqPresetId }?.name ?: "Flat"
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextPrimary, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { Spacer(Modifier.height(8.dp)) }

            // === 扫描Settings ===
            item {
                SettingsSectionTitle("Scan Settings")
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Timer,
                    title = "Min Duration Filter",
                    subtitle = "Audio files shorter than this will be ignored",
                    onClick = { }
                )
                // Duration options
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(10, 20, 30, 60, 120).forEach { sec ->
                        val selected = minDuration == sec
                        FilterChip(
                            selected = selected,
                            onClick = {
                                minDuration = sec
                                context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
                                    .edit().putInt("min_duration", sec).apply()
                                refreshTrigger++
                            },
                            label = { Text("${sec}s", color = if (selected) DarkBg else TextPrimary) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentPurple,
                                containerColor = DarkCard
                            )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            item { SettingsDivider() }

            // === Audio Output ===
            item {
                SettingsSectionTitle("Audio Output")
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Speaker,
                    title = "Output Mode",
                    subtitle = "Current: " + audioOutput
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("AudioTrack", "OpenSL ES", "AAudio", "Oboe Exclusive").forEach { mode ->
                        val selected = audioOutput == mode
                        FilterChip(
                            selected = selected,
                            onClick = {
                                audioOutput = mode
                                context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                                    .edit().putString("audio_output", mode).apply()
                                refreshTrigger++
                                android.widget.Toast.makeText(context, "Switched to $mode. Restart playback for best results.", android.widget.Toast.LENGTH_LONG).show()
                            },
                            label = { Text(mode, color = if (selected) DarkBg else TextPrimary, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentPurple,
                                containerColor = DarkCard
                            )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            item {
                val dspLabels = listOf("Close", "Steven Special", "🐱 Cat Mode")
                SettingsItem(
                    icon = Icons.Default.GraphicEq,
                    title = "DSP Mode",
                    subtitle = "Current: " + dspLabels.getOrElse(dspMode + 1) { "Close" }
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    dspLabels.forEachIndexed { idx, label ->
                        val selected = (dspMode + 1) == idx
                        FilterChip(
                            selected = selected,
                            onClick = {
                                dspMode = idx - 1
                                context.getSharedPreferences("dsp_mode", android.content.Context.MODE_PRIVATE)
                                    .edit().putInt("mode", idx - 1).apply()
                                refreshTrigger++
                            },
                            label = { Text(label, color = if (selected) DarkBg else TextPrimary, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentPurple,
                                containerColor = DarkCard
                            )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            item {
                val edgeLightPref = context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
                var edgeLightEnabled by remember(refreshTrigger) {
                    mutableStateOf(edgeLightPref.getBoolean("moto_edge_light", true))
                }
                SettingsSwitchItem(
                    icon = Icons.Default.LightMode,
                    title = "Curved Edge Glow",
                    subtitle = if (edgeLightEnabled) "On · Edge glow on player screen"
                    else "Off",
                    checked = edgeLightEnabled,
                    onCheckedChange = { enabled ->
                        edgeLightEnabled = enabled
                        edgeLightPref.edit().putBoolean("moto_edge_light", enabled).apply()
                        refreshTrigger++
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
            item { SettingsDivider() }

            // === Widget ===
            item {
                SettingsSectionTitle("Widget")
            }
            item {
                val widgetPref = context.getSharedPreferences("widget_prefs", android.content.Context.MODE_PRIVATE)
                var transparentBg by remember(refreshTrigger) {
                    mutableStateOf(widgetPref.getBoolean("transparent_bg", false))
                }
                SettingsSwitchItem(
                    icon = Icons.Default.BlurOn,
                    title = "Transparent Widget Background",
                    subtitle = if (transparentBg) "Widget background is fully transparent" else "Using gradient background",
                    checked = transparentBg,
                    onCheckedChange = { enabled ->
                        transparentBg = enabled
                        widgetPref.edit().putBoolean("transparent_bg", enabled).apply()
                        refreshTrigger++
                        // 立即刷新所有 widget
                        try {
                            com.sdw.music.player.widget.MusicWidgetProvider.updateAllWidgets(context)
                        } catch (_: Exception) {}
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
            item { SettingsDivider() }

            // === Standby (Idle) ===
            item {
                SettingsSectionTitle("Standby (Idle)")
            }
            item {
                val idlePref = context.getSharedPreferences("sdw_music_prefs", android.content.Context.MODE_PRIVATE)
                var idleLevel by remember(refreshTrigger) {
                    mutableStateOf(idlePref.getString("idle_level", "Rare") ?: "Rare")
                }
                val idleLabels = mapOf(
                    "Working Set" to "Keep alive 30m",
                    "Frequent" to "Timeout 5min",
                    "Rare" to "Timeout 3s (default)",
                    "Restricted" to "Kill immediately"
                )
                SettingsItem(
                    icon = Icons.Default.Schedule,
                    title = "Idle Behavior",
                    subtitle = idleLabels[idleLevel] ?: "Timeout 3s"
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    idleLabels.keys.toList().forEach { level ->
                        val selected = idleLevel == level
                        FilterChip(
                            selected = selected,
                            onClick = {
                                idleLevel = level
                                idlePref.edit().putString("idle_level", level).apply()
                                refreshTrigger++
                            },
                            label = { Text(if (selected) level else level.take(4), color = if (selected) DarkBg else TextPrimary, fontSize = 11.sp, maxLines = 1) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentPurple,
                                containerColor = DarkCard
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            item {
                // [v7.122] Show hint when system auto-syncs idle_level
                val sysBucket = remember {
                    try {
                        val ctx = context
                        val usm = ctx.getSystemService(android.content.Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
                        usm?.appStandbyBucket
                    } catch (_: Exception) { null }
                }
                if (sysBucket != null && sysBucket != android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE) {
                    val bucketName = when (sysBucket) {
                        android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "Working Set"
                        android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "Frequent"
                        android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE -> "Rare"
                        android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "Restricted"
                        else -> "Rare"
                    }
                    Text(
                        "Auto-sync: system standby = $bucketName → idle_level auto-mapped",
                        color = TextSecondary.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                    )
                }
            }
            item { SettingsDivider() }

            // === Equalizer预设 ===
            item {
                SettingsSectionTitle("EQ Presets")
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Equalizer,
                    title = "Current Preset",
                    subtitle = eqPresetName
                )
            }
            item {
                val presets = EqualizerManager.PRESETS
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    presets.chunked(4).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            row.forEach { preset ->
                                val selected = eqPresetId == preset.id
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        EqualizerManager.applyPreset(preset.id, context)
                                        refreshTrigger++
                                    },
                                    label = { 
                                        Text(preset.name, color = if (selected) DarkBg else TextPrimary, fontSize = 11.sp, maxLines = 1)
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentPurple,
                                        containerColor = DarkCard
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // === Audio Diagnostic ===
            if (onNavigateToAudioDiagnostic != null) {
                item { SettingsDivider() }
                item {
                    SettingsItem(
                        icon = Icons.Default.QueryStats,
                        title = "Audio Diagnostic",
                        subtitle = "Source rate, device native, resampling status",
                        onClick = onNavigateToAudioDiagnostic
                    )
                }
            }

            // === About ===
            item {
                SettingsSectionTitle("About")
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "Moto Music",
                    subtitle = "v3.0 | Developed by Stephen Yu"
                )
            }
            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        title,
        color = AccentPurple,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AccentPurple,
                checkedTrackColor = AccentPurple.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
private fun SettingsDivider() {
    Spacer(Modifier.height(4.dp))
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = 54.dp, end = 16.dp),
        color = DividerColor
    )
    Spacer(Modifier.height(4.dp))
}



