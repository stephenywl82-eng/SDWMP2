package com.sdw.music.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Hi-Fi 音频元数据提取工具
 * Steven 建议显示：FLAC | 24bit | 96kHz | 2304 kbps
 */
object AudioMetadata {

    private const val TAG = "AudioMetadata"

    /**
     * 提取音频文件的高保真参数
     * @return 格式字符串，如 "FLAC · 24bit · 96kHz · 2304 kbps"
     */
    fun extractHifiTags(context: Context, song: Song): String {
        // 【Steven 修复】使用实际文件路径，而非 content URI
        val actualPath = song.filePath.ifBlank { song.path }
        if (actualPath.isEmpty()) return ""

        val tags = mutableListOf<String>()

        try {
            // 从文件扩展名获取格式
            val format = extractFormat(actualPath)
            if (format.isNotEmpty()) {
                tags.add(format)
            }

            // 使用 MediaMetadataRetriever 提取元数据
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(actualPath)

            // 比特率 (kbps)
            val bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            if (bitrateStr != null) {
                val bitrate = bitrateStr.toIntOrNull() ?: 0
                if (bitrate > 0) {
                    val bitrateDisplay = when {
                        bitrate >= 1_000_000 -> "${bitrate / 1_000_000} Mbps"
                        bitrate >= 1_000 -> "${bitrate / 1_000} kbps"
                        else -> "$bitrate bps"
                    }
                    tags.add(bitrateDisplay)
                }
            }

            // 采样率 (Hz)
            // 注意：MediaMetadataRetriever 不直接提供采样率，需要用其他方式
            val sampleRate = extractSampleRate(actualPath)
            if (sampleRate > 0) {
                val sampleRateDisplay = when {
                    sampleRate >= 100_000 -> "${sampleRate / 1000}kHz"
                    sampleRate >= 10_000 -> "${sampleRate / 1000}kHz"
                    else -> "${sampleRate}Hz"
                }
                tags.add(sampleRateDisplay)
            }

            // 位深 (bits) - FLAC 等无损格式常用 16/24 bit
            val bitDepth = extractBitDepth(actualPath)
            if (bitDepth > 0) {
                tags.add("${bitDepth}bit")
            }

            retriever.release()

        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract metadata: ${e.message}")
        }

        return if (tags.isNotEmpty()) {
            val result = tags.joinToString(" · ")
            // 【Steven 安全检查】确保不Back URI 或路径
            if (result.contains("content://", ignoreCase = true) || 
                result.contains("/media/external/audio/media/", ignoreCase = true)) {
                song.format.ifEmpty { "" }  // 回退到 song.format
            } else {
                result
            }
        } else {
            song.format.ifEmpty { "" }
        }
    }

    /**
     * 从文件路径提取音频格式
     */
    private fun extractFormat(path: String): String {
        val extension = File(path).extension.lowercase()
        return when (extension) {
            "flac" -> "FLAC"
            "wav" -> "WAV"
            "aiff", "aif" -> "AIFF"
            "alac", "m4a" -> "ALAC"
            "dsd", "dsf", "dff" -> "DSD"
            "mp3" -> "MP3"
            "aac", "m4a" -> "AAC"
            "ogg", "oga" -> "OGG"
            "opus" -> "Opus"
            "wma" -> "WMA"
            else -> extension.uppercase()
        }
    }

    /**
     * 提取采样率（通过 MediaExtractor）
     */
    private fun extractSampleRate(path: String): Int {
        return try {
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(path)
            val format = extractor.getTrackFormat(0)
            val sampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            extractor.release()
            sampleRate
        } catch (e: Exception) {
            Log.d(TAG, "Cannot extract sample rate: ${e.message}")
            0
        }
    }

    /**
     * 提取位深（通过 MediaExtractor）
     */
    private fun extractBitDepth(path: String): Int {
        return try {
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(path)
            val format = extractor.getTrackFormat(0)
            val bitDepth = if (format.containsKey(android.media.MediaFormat.KEY_PCM_ENCODING)) {
                val encoding = format.getInteger(android.media.MediaFormat.KEY_PCM_ENCODING)
                when (encoding) {
                    // 【兼容性修复】使用数值常量避免 API 版本检查
                    android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                    android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                    android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
                    // 24bit = 0x50000000 (API 31+), 32bit = 0x60000000 (API 31+)
                    0x50000000 -> 24  // ENCODING_PCM_24BIT
                    0x60000000 -> 32  // ENCODING_PCM_32BIT
                    else -> 16
                }
            } else {
                // FLAC 等格式通过 bits per sample 判断
                // 如果无法获取，根据格式推断
                val extension = File(path).extension.lowercase()
                when (extension) {
                    "flac" -> 24  // FLAC 通常 16-24bit
                    "wav" -> 16   // WAV 默认 16bit
                    "dsd", "dsf", "dff" -> 1  // DSD 是 1bit
                    else -> 0
                }
            }
            extractor.release()
            bitDepth
        } catch (e: Exception) {
            Log.d(TAG, "Cannot extract bit depth: ${e.message}")
            0
        }
    }
}

