package com.sdw.music.player.ui.screens

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaExtractor
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdw.music.player.MusicService
import com.sdw.music.player.OboeDirectPlayer
import com.sdw.music.player.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioDiagnosticScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val service = remember { MusicService.instance }
    val oboePlayer = remember { service?.getOboePlayer() }

    // Device native: AudioManager
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager }
    val deviceNativeRate = remember {
        audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
    }
    val framesPerBuffer = remember {
        audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
    }

    // Output device info (snapshot, updated live below)
    val outputDeviceInfoSnapshot = remember {
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull()
    }
    val outputDeviceLabelSnapshot = remember(outputDeviceInfoSnapshot) {
        getDeviceLabel(outputDeviceInfoSnapshot)
    }

    // Source rate: Oboe opens at source → getSampleRate(), or extract from file
    val sourceRate = remember {
        val oboeSrc = oboePlayer?.getSampleRate() ?: 0
        if (oboeSrc > 0) oboeSrc
        else {
            val path = MusicService.currentSong?.path
            if (!path.isNullOrEmpty()) extractSampleRateFromFile(path) else 0
        }
    }

    val streamRate = remember { oboePlayer?.getStreamSampleRate() ?: 0 }
    val isOboeMode = remember { service?.isOboeDirectMode() == true }

    // ── Live SharingMode + Output Device polling ──
    // Initial null → LaunchedEffect fills immediately on first poll
    var isExclusiveLive by remember { mutableStateOf<Boolean?>(null) }
    var liveOutputDeviceInfo by remember { mutableStateOf<AudioDeviceInfo?>(null) }
    var liveOutputDeviceLabel by remember { mutableStateOf("") }
    var trackFlagsHex by remember { mutableStateOf("—") }
    var trackFlagsLabel by remember { mutableStateOf("") }

    // ── AudioFlinger Track Flags (dumpsys parse) ──

    // ── Audio Focus event log ──
    val focusLog = remember { mutableStateListOf<FocusEvent>() }

    // ── AudioFocus listener: piggyback on MusicService's existing focus handling ──
    // We do NOT request focus ourselves (would fight with the player).
    // Instead, monitor via AudioManager.isMusicActive() and log changes.
    // Poll using fresh references each iteration — NOT stale remember captures.
    LaunchedEffect(Unit) {
        var wasMusicActive = audioManager?.isMusicActive ?: false
        var tick = 0
        while (true) {
            // Fresh reference every poll — avoids stale oboePlayer after re-entry
            val liveOboe = MusicService.instance?.getOboePlayer()
            isExclusiveLive = liveOboe?.isExclusiveMode()
            val dev = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull()
            liveOutputDeviceInfo = dev
            liveOutputDeviceLabel = getDeviceLabel(dev)

            // Track audio focus changes without requesting focus
            val nowActive = audioManager?.isMusicActive ?: false
            if (nowActive != wasMusicActive) {
                wasMusicActive = nowActive
                val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val event = if (nowActive) "GAIN" else "LOSS"
                focusLog.add(0, FocusEvent(now, event))
                if (focusLog.size > 20) focusLog.removeAt(focusLog.size - 1)
            }

            // dumpsys is expensive — only poll every 10s
            tick++
            if (tick % 10 == 0) {
                val flags = fetchTrackFlags(liveOboe)
                trackFlagsHex = flags.first
                trackFlagsLabel = flags.second
            }

            delay(1000L)
        }
    }

    // Resampling status (uses live Exclusive state; null → assume non-exclusive)
    val effectiveDeviceRate = when {
        isExclusiveLive == true && streamRate > 0 -> streamRate
        deviceNativeRate > 0 -> deviceNativeRate
        streamRate > 0 -> streamRate
        else -> 0
    }
    val bitPerfect = isExclusiveLive == true && sourceRate > 0 && effectiveDeviceRate > 0 && sourceRate == effectiveDeviceRate

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio Diagnostic", color = TextPrimary, style = MaterialTheme.typography.titleMedium) },
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Status banner ──
            item {
                val (statusText, statusColor, statusIcon) = when {
                    !isOboeMode -> Triple("ExoPlayer Mode", Color(0xFFFF9800), Icons.Default.MusicNote)
                    bitPerfect -> Triple("Bit-Perfect · Exclusive Direct", AccentGreen, Icons.Default.CheckCircle)
                    isExclusiveLive == true -> Triple("Exclusive · App-Layer Resampling", Color(0xFFFF9800), Icons.Default.Warning)
                    isOboeMode -> Triple("AAudio Shared · System Mixer In Path", Color(0xFFFF9800), Icons.Default.Warning)
                    else -> Triple("Detecting…", TextSecondary, Icons.Default.Info)
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.12f))
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(statusText, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }

            // ── Source Rate ──
            item {
                DiagnosticRow(
                    icon = Icons.Default.AudioFile,
                    label = "Source Rate",
                    value = if (sourceRate > 0) "${sourceRate / 1000.0} kHz" else "—",
                    sublabel = "源文件采样率",
                    accent = AccentPurple
                )
            }

            // ── Stream Rate (Oboe actual) ──
            if (isOboeMode && streamRate > 0) {
                item {
                    val matchesSource = sourceRate > 0 && sourceRate == streamRate
                    DiagnosticRow(
                        icon = Icons.Default.Stream,
                        label = "Stream Rate",
                        value = "${streamRate / 1000.0} kHz",
                        sublabel = "Oboe 实际流采样率",
                        accent = if (matchesSource) AccentGreen else Color(0xFFFF9800)
                    )
                }
            }

            // ── Device Native ──
            item {
                DiagnosticRow(
                    icon = Icons.Default.PhoneAndroid,
                    label = "Device Native",
                    value = if (deviceNativeRate > 0) "${deviceNativeRate / 1000.0} kHz" else "—",
                    sublabel = "AudioManager PROPERTY_OUTPUT_SAMPLE_RATE · 硬件原生采样率",
                    accent = AccentBlue
                )
            }

            // ── Buffer Size ──
            if (framesPerBuffer > 0) {
                item {
                    DiagnosticRow(
                        icon = Icons.Default.Tune,
                        label = "Buffer Size",
                        value = "$framesPerBuffer frames",
                        sublabel = "PROPERTY_OUTPUT_FRAMES_PER_BUFFER",
                        accent = TextSecondary
                    )
                }
            }

            // ── Output Device (live) ──
            item {
                val devInfo = liveOutputDeviceInfo
                val devIcon = when (devInfo?.type) {
                    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> Icons.Default.Usb
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> Icons.Default.Bluetooth
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> Icons.Default.Headphones
                    else -> Icons.Default.Smartphone
                }
                val devAccent = when (devInfo?.type) {
                    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> AccentGreen
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> Color(0xFF42A5F5)
                    else -> AccentBlue
                }
                DiagnosticRow(
                    icon = devIcon,
                    label = "Output Device",
                    value = liveOutputDeviceLabel,
                    sublabel = if (devInfo != null)
                        "当前音频路由设备 · ${devInfo.productName?.toString() ?: "内置输出"}"
                    else "未能获取设备信息",
                    accent = devAccent
                )
            }

            // ── DAC Capabilities ──
            item {
                val devInfo = liveOutputDeviceInfo
                if (devInfo != null) {
                    val rates = devInfo.sampleRates.joinToString(", ") { "${it / 1000.0}kHz" }.ifEmpty { "—" }
                    val encs = devInfo.encodings.joinToString(", ") { encodingName(it) }.ifEmpty { "—" }
                    val chsCounts = devInfo.channelCounts.joinToString(", ") { "$it ch" }.ifEmpty { "—" }
                    // channelMasks is more reliable than channelCounts on some HALs
                    val chsMasks = devInfo.channelMasks.joinToString(", ") { decodeChannelMask(it) }
                        .ifEmpty { "—" }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkCard)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Settings, null, tint = AccentPurple, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("DAC Capabilities", color = AccentPurple, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.height(6.dp))
                            CapRow("Sample Rates", rates)
                            CapRow("Encodings", encs)
                            CapRow("Channels", chsCounts)
                            CapRow("Chan Masks", chsMasks)
                        }
                    }
                }
            }

            // ── Live SharingMode Monitor ──
            item {
                val dotColor by animateColorAsState(
                    targetValue = when {
                        !isOboeMode -> TextSecondary
                        isExclusiveLive == null -> TextSecondary
                        isExclusiveLive == true -> AccentGreen
                        else -> Color(0xFFFF9800)
                    },
                    animationSpec = tween(400)
                )
                val dotLabel = when {
                    !isOboeMode -> "Inactive"
                    isExclusiveLive == null -> "—"
                    isExclusiveLive == true -> "EXCLUSIVE"
                    else -> "SHARED"
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkCard)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .drawWithContent {
                                    drawCircle(dotColor)
                                }
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Live Monitor", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "每秒轮询 getSharingMode() · 实时检测系统抢占",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            dotLabel,
                            color = dotColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // ── Output Mode ──
            item {
                val modeLabel = when {
                    isExclusiveLive == true -> "Oboe Exclusive"
                    isExclusiveLive == null -> "Detecting…"
                    isOboeMode -> "AAudio Shared"
                    else -> "ExoPlayer"
                }
                val modeColor = when {
                    isExclusiveLive == true -> AccentGreen
                    isExclusiveLive == null -> TextSecondary
                    isOboeMode -> AccentBlue
                    else -> Color(0xFFFF9800)
                }
                DiagnosticRow(
                    icon = Icons.Default.Speaker,
                    label = "Output Mode",
                    value = modeLabel,
                    sublabel = when {
                        isExclusiveLive == true -> "绕过 Android Mixer · 独占直出"
                        isExclusiveLive == null -> "轮询中…"
                        else -> "标准 Android 音频管线"
                    },
                    accent = modeColor
                )
            }

            // ── AudioFlinger Track Flags ──
            item {
                val flagAccent = when {
                    trackFlagsHex.contains("0x004") || trackFlagsHex.contains("0x100") -> AccentGreen
                    trackFlagsHex == "—" -> TextSecondary
                    else -> Color(0xFFFF9800)
                }
                DiagnosticRow(
                    icon = Icons.Default.Memory,
                    label = "AF Track Flags",
                    value = trackFlagsHex,
                    sublabel = if (trackFlagsLabel.isNotEmpty()) trackFlagsLabel
                    else "AudioFlinger 底层轨道标志 · dumpsys 解析",
                    accent = flagAccent
                )
            }

            // ── Audio Focus Events ──
            if (focusLog.isNotEmpty()) {
                item { Spacer(Modifier.height(4.dp)) }
                item {
                    SectionTitle("Audio Focus Events")
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkCard)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            focusLog.take(8).forEach { event ->
                                val (icon, color) = when (event.event) {
                                    "GAIN" -> Icons.Default.CheckCircle to AccentGreen
                                    "LOSS" -> Icons.Default.Cancel to AccentRed
                                    "LOSS_TRANSIENT" -> Icons.Default.Warning to Color(0xFFFF9800)
                                    "LOSS_TRANSIENT_CAN_DUCK" -> Icons.Default.Remove to AccentBlue
                                    else -> Icons.Default.Info to TextSecondary
                                }
                                Row(
                                    modifier = Modifier.padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(event.time, color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    Spacer(Modifier.width(8.dp))
                                    Text(event.event, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }

            // ── Lib Status ──
            item { Spacer(Modifier.height(4.dp)) }
            item {
                val libLoaded = remember { OboeDirectPlayer.nativeLibLoaded }
                DiagnosticRow(
                    icon = Icons.Default.Code,
                    label = "Native Library",
                    value = if (libLoaded) "Loaded ✓" else "Not Loaded",
                    sublabel = "OboeDirectPlayer JNI · liboboeaudio",
                    accent = if (libLoaded) AccentGreen else AccentRed
                )
            }

            // ── Resampling Analysis ──
            if (sourceRate > 0 && effectiveDeviceRate > 0) {
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    SectionTitle("Resampling Analysis")
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkCard)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            if (bitPerfect) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, null, tint = AccentGreen, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Exclusive 直出 · 源采样率 = 硬件原生采样率",
                                        color = AccentGreen,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Source ($sourceRate Hz) = Device Native ($deviceNativeRate Hz) → Oboe Exclusive 绕过 Android Mixer，真正的 bit-perfect 输出",
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            } else if (isExclusiveLive == false && isOboeMode) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9800), modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "AAudio Shared · 系统混音器介入",
                                        color = Color(0xFFFF9800),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Source ($sourceRate Hz) → App Stream ($streamRate Hz) → System Mixer → Device ($deviceNativeRate Hz)",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Shared 模式下 AudioFlinger 强制将所有音频流重采样到设备原生采样率。即使 App 层未重采样，系统混音器仍会在内部进行 SRC。\n\n" +
                                    "建议: 切换到 Oboe Exclusive 模式以获得真正 bit-perfect 输出。",
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9800), modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "正在进行应用层重采样",
                                        color = Color(0xFFFF9800),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Source ($sourceRate Hz) → Device ($effectiveDeviceRate Hz)",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "音频正在从源采样率重采样到设备原生采样率。重采样在 Oboe/AAudio 层执行，为软件 SRC，不影响音质。",
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
            }

            // ── Explanation ──
            item { Spacer(Modifier.height(8.dp)) }
            item {
                SectionTitle("About This Page")
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkCard)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "This page shows real-time audio path information.",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "• Source Rate — your audio file's native sample rate\n" +
                            "• Stream Rate — the rate Oboe opens the hardware stream at\n" +
                            "• Device Native — the hardware mixer's native rate (AudioManager)\n" +
                            "• DAC Capabilities — supported rates / encodings / channels\n" +
                            "• Live Monitor — 每秒轮询 getSharingMode(), 检测系统抢占/降级\n" +
                            "• AF Track Flags — AudioFlinger 底层轨道标志 (0x000=Shared, 0x100=MMAP, 0x004=Direct)\n" +
                            "• Focus Events — 音频焦点变化日志, 显示系统音频抢占时间线\n" +
                            "• Bit-Perfect 仅在 Oboe Exclusive 模式下成立: App直出, 绕过系统Mixer\n" +
                            "• AAudio Shared 模式下 AudioFlinger 始终参与, 内部重采样不可控",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ── Data class ──

private data class FocusEvent(val time: String, val event: String)

// ── Private utilities ──

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        color = AccentPurple,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun CapRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(label, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.width(90.dp))
        Text(value, color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun DiagnosticRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    sublabel: String,
    accent: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(sublabel, color = TextSecondary, fontSize = 11.sp)
            }
            Text(value, color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

// ── Helpers ──

private fun getDeviceLabel(info: AudioDeviceInfo?): String {
    if (info == null) return "—"
    return when (info.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired HP"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB DAC"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT SCO"
        AudioDeviceInfo.TYPE_DOCK -> "Dock"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "Other (${info.type})"
    }
}

private fun decodeChannelMask(mask: Int): String {
    val ch = mutableListOf<String>()
    if ((mask and AudioFormat.CHANNEL_OUT_FRONT_LEFT) != 0) ch.add("L")
    if ((mask and AudioFormat.CHANNEL_OUT_FRONT_RIGHT) != 0) ch.add("R")
    if ((mask and AudioFormat.CHANNEL_OUT_FRONT_CENTER) != 0) ch.add("C")
    if ((mask and AudioFormat.CHANNEL_OUT_LOW_FREQUENCY) != 0) ch.add("LFE")
    if ((mask and AudioFormat.CHANNEL_OUT_BACK_LEFT) != 0) ch.add("SL")
    if ((mask and AudioFormat.CHANNEL_OUT_BACK_RIGHT) != 0) ch.add("SR")
    if ((mask and AudioFormat.CHANNEL_OUT_SIDE_LEFT) != 0) ch.add("SL")
    if ((mask and AudioFormat.CHANNEL_OUT_SIDE_RIGHT) != 0) ch.add("SR")
    // MONO bit is same as FRONT_LEFT (0x1) — if only that bit is set, it's mono
    if (mask == AudioFormat.CHANNEL_OUT_MONO) return "Mono"
    return ch.joinToString("+").ifEmpty { ("0x%X · %d ch").format(mask, Integer.bitCount(mask)) }
}

private fun encodingName(encoding: Int): String = when (encoding) {
    AudioFormat.ENCODING_PCM_8BIT -> "PCM_8"
    AudioFormat.ENCODING_PCM_16BIT -> "PCM_16"
    AudioFormat.ENCODING_PCM_FLOAT -> "FLOAT"
    AudioFormat.ENCODING_AC3 -> "AC3"
    AudioFormat.ENCODING_E_AC3 -> "EAC3"
    AudioFormat.ENCODING_DTS -> "DTS"
    AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
    AudioFormat.ENCODING_DOLBY_TRUEHD -> "TrueHD"
    AudioFormat.ENCODING_IEC61937 -> "IEC61937"
    else -> "FMT_$encoding"
}

/**Parse dumpsys media.audio_flinger for our app's Active Track flags.
 * Track data lines embed the PID and flags inline:  ... PID/UID ... 0xHHHH ...
 * Returns Pair(hexFlags, description) or Pair("—", "") on failure.
 */
/**
 * Fetch AudioFlinger track flags for the current process.
 *
 * Android 13+ blocks non-system apps from running `dumpsys` on system services.
 * On those devices, we fall back to Oboe stream mode inference:
 *   - MMAP Exclusive  → 0x100 (MMAP_NOIRQ)
 *   - MMAP Shared    → 0x000 (Default Shared)
 *   - Legacy          → 0x000 + note
 */
private const val TAG = "AudioDiag"

private suspend fun fetchTrackFlags(oboePlayer: Any?): Pair<String, String> = withContext(Dispatchers.IO) {
    // ── Primary path: dumpsys media.audio_flinger ──
    try {
        val proc = Runtime.getRuntime().exec("dumpsys media.audio_flinger 2>/dev/null")
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val pid = android.os.Process.myPid().toString()

        var flagHex: String? = null
        val hexPattern = Regex("""\b0x[0-9A-Fa-f]{3,8}\b""")
        var lineCount = 0

        reader.useLines { lines ->
            for (line in lines) {
                lineCount++
                val idx = line.indexOf(pid)
                if (idx < 0) continue
                // Flags column appears after the PID/UID segment; grab the first 0xHHHH after it
                val tail = line.substring(idx + pid.length)
                val match = hexPattern.find(tail)
                if (match != null) {
                    flagHex = match.value
                    break
                }
            }
        }
        val exitCode = proc.waitFor()
        Log.d(TAG, "dumpsys exitCode=$exitCode lineCount=$lineCount flagHex=$flagHex pid=$pid")

        // dumpsys blocked (Android 13+): empty output or non-zero exit
        if (lineCount == 0 || exitCode != 0) {
            Log.w(TAG, "dumpsys unavailable — falling back to Oboe mode inference")
            return@withContext inferFromOboeMode(oboePlayer)
        }

        val hex = flagHex
        if (hex == null) {
            Log.w(TAG, "PID $pid found in $lineCount lines but no flag match — falling back")
            return@withContext inferFromOboeMode(oboePlayer)
        }

        val flags = hex.removePrefix("0x").toLong(16)
        val parts = mutableListOf<String>()
        if ((flags and 0x001) != 0L) parts.add("FAST")
        if ((flags and 0x002) != 0L) parts.add("DEEP_BUFFER")
        if ((flags and 0x004) != 0L) parts.add("DIRECT")
        if ((flags and 0x008) != 0L) parts.add("RAW")
        if ((flags and 0x100) != 0L) parts.add("MMAP_NOIRQ")
        if ((flags and 0x200) != 0L) parts.add("NON_BLOCKING")
        if ((flags and 0x400) != 0L) parts.add("GAPLESS")
        if (parts.isEmpty()) parts.add("Default Shared")

        Log.d(TAG, "parsed flags: $hex → ${parts.joinToString(" | ")}")
        Pair(hex, "AF Flags: ${parts.joinToString(" | ")}")
    } catch (e: Exception) {
        Log.e(TAG, "dumpsys failed", e)
        inferFromOboeMode(oboePlayer)
    }
}

/**
 * Fallback: infer track flags from the Oboe stream mode.
 * Called when dumpsys is unavailable (Android 13+ permission restriction).
 */
private fun inferFromOboeMode(oboePlayer: Any?): Pair<String, String> {
    Log.d(TAG, "inferFromOboeMode: oboePlayer=$oboePlayer")
    if (oboePlayer == null) {
        Log.w(TAG, "oboePlayer is null — cannot infer flags")
        return Pair("—", "")
    }
    return try {
        val method = oboePlayer.javaClass.getMethod("isExclusiveMode")
        val isExclusive = method.invoke(oboePlayer) as? Boolean ?: false
        Log.d(TAG, "oboePlayer.isExclusiveMode() = $isExclusive")
        if (isExclusive) {
            Pair("0x100", "AF Flags: MMAP_NOIRQ (Oboe Exclusive)")
        } else {
            // Shared or Legacy — the actual flags depend on AudioFlinger policy
            Pair("0x000", "AF Flags: Shared (Oboe/Legacy)")
        }
    } catch (e: Exception) {
        Log.e(TAG, "inferFromOboeMode reflection failed", e)
        Pair("—", "")
    }
}

private fun extractSampleRateFromFile(path: String): Int {
    return try {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)
        var rate = 0
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                rate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                break
            }
        }
        extractor.release()
        rate
    } catch (_: Exception) {
        0
    }
}
