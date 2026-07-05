package com.sdw.music.player.core.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer

/**
 * Decodes audio files via MediaExtractor + MediaCodec and feeds raw PCM to
 * [UsbDacManager] on a dedicated high-priority thread.
 *
 * Usage:
 * ```
 * val controller = UsbDacPlaybackController(
 *     onCompletion = { ... },
 *     onError = { msg -> Log.e(TAG, msg) }
 * )
 * if (controller.open("/sdcard/Music/song.flac", 48000, 2)) {
 *     controller.play()
 * }
 * // later:
 * controller.stop()
 * ```
 */
class UsbDacPlaybackController(
    private val onCompletion: () -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "UsbDacPlayback"

        /** Default output sample rate for USB DAC */
        const val DEFAULT_SAMPLE_RATE = 48000

        /** Default output channels for USB DAC */
        const val DEFAULT_CHANNELS = 2

        /** Default bits per sample for USB DAC */
        const val DEFAULT_BITS = 24

        private const val TIMEOUT_US = 10000L
    }

    @Volatile var isPlaying = false
        private set

    @Volatile var positionMs = 0L
        private set

    val durationMs get() = extractorDurationMs

    private var decodeThread: Thread? = null
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var extractorDurationMs = 0L
    private var audioTrackIndex = -1
    private var sourceSampleRate = 0
    private var sourceChannelCount = 0
    private var dacSampleRate = DEFAULT_SAMPLE_RATE
    private var dacChannels = DEFAULT_CHANNELS

    private val pauseLock = Object()
    @Volatile private var paused = false
    @Volatile private var shouldStop = false

    /**
     * Open and prepare an audio file for playback through the USB DAC.
     *
     * @param filePath path to the audio file
     * @param dacSampleRate output sample rate (e.g. 48000, 44100)
     * @param dacChannels output channel count (e.g. 2 for stereo)
     * @return true if the file was opened successfully
     */
    fun open(filePath: String, dacSampleRate: Int = DEFAULT_SAMPLE_RATE, dacChannels: Int = DEFAULT_CHANNELS): Boolean {
        this.dacSampleRate = dacSampleRate
        this.dacChannels = dacChannels

        releaseResources()

        return try {
            val ex = MediaExtractor()
            ex.setDataSource(filePath)
            extractor = ex

            audioTrackIndex = -1
            for (i in 0 until ex.trackCount) {
                val format = ex.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    ex.selectTrack(i)
                    break
                }
            }

            if (audioTrackIndex < 0) {
                onError("No audio track found in file")
                releaseResources()
                return false
            }

            val format = ex.getTrackFormat(audioTrackIndex)
            sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            sourceChannelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mimeType = format.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"

            extractorDurationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            Log.i(TAG, "Opening: $filePath, mime=$mimeType, " +
                    "srcRate=$sourceSampleRate, srcCh=$sourceChannelCount, " +
                    "dacRate=$dacSampleRate, dacCh=$dacChannels, durationMs=$extractorDurationMs")

            val codecInstance = MediaCodec.createDecoderByType(mimeType)
            val codecFormat = MediaFormat.createAudioFormat(mimeType, sourceSampleRate, sourceChannelCount)
            // Request PCM output in float format if supported
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                codecFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, MediaCodecInfo.CodecProfileLevel.AACObjectXHE)
            }
            codecInstance.configure(codecFormat, null, null, 0)
            codecInstance.start()
            codec = codecInstance

            Log.i(TAG, "Codec started: $mimeType")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open file: ${e.message}", e)
            onError("Failed to open: ${e.message}")
            releaseResources()
            false
        }
    }

    /**
     * Start playback on a dedicated decode thread.
     */
    fun play() {
        if (isPlaying) return
        if (codec == null || extractor == null) {
            onError("Not ready — call open() first")
            return
        }

        shouldStop = false
        paused = false

        decodeThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            Log.d(TAG, "Decode thread started with URGENT_AUDIO priority")
            decodeLoop()
        }, "UsbDacDecode").apply {
            isDaemon = true
            start()
        }

        isPlaying = true
    }

    /**
     * Pause playback. Stops the DAC stream and releases resources temporarily.
     */
    fun pause() {
        if (!isPlaying) return
        paused = true
        isPlaying = false
        // The decode thread will be blocked at pushPcm, stopAndRelease will unblock
        UsbDacManager.stopAndRelease()
        synchronized(pauseLock) {
            (pauseLock as java.lang.Object).notifyAll()
        }
        Log.d(TAG, "Paused at ${positionMs}ms")
    }

    /**
     * Resume playback after pause.
     */
    fun resume() {
        if (isPlaying) return
        if (codec == null) return

        paused = false
        shouldStop = false

        val started = UsbDacManager.startStreaming(dacSampleRate, dacChannels, DEFAULT_BITS)
        if (!started) {
            onError("Failed to restart USB DAC stream")
            return
        }

        isPlaying = true
        Log.d(TAG, "Resumed from ${positionMs}ms")
    }

    /**
     * Stop playback and release all resources.
     */
    fun stop() {
        shouldStop = true
        paused = false
        isPlaying = false
        synchronized(pauseLock) {
            (pauseLock as java.lang.Object).notifyAll()
        }
        decodeThread?.interrupt()
        decodeThread?.join(2000)
        decodeThread = null
        UsbDacManager.stopAndRelease()
        releaseResources()
        Log.d(TAG, "Stopped")
    }

    /**
     * Seek to a position in milliseconds.
     */
    fun seekTo(timeMs: Long) {
        val ex = extractor ?: return
        val cd = codec ?: return

        try {
            cd.flush()
            // Seek to previous sync sample to ensure clean decode start
            ex.seekTo(timeMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            positionMs = ex.sampleTime / 1000L
            Log.d(TAG, "Seek to ${timeMs}ms (actual ${positionMs}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Seek failed: ${e.message}", e)
        }
    }

    // ============================================================
    // Internal
    // ============================================================

    private fun releaseResources() {
        codec?.stop()
        codec?.release()
        codec = null

        extractor?.release()
        extractor = null

        audioTrackIndex = -1
        extractorDurationMs = 0L
    }

    private fun decodeLoop() {
        val ex = extractor ?: return
        val cd = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        val sampleCount = dacChannels // samples per frame
        var eos = false

        try {
            while (!shouldStop) {
                // Check pause state
                if (paused) {
                    synchronized(pauseLock) {
                        while (paused && !shouldStop) {
                            try { (pauseLock as java.lang.Object).wait() } catch (_: InterruptedException) {}
                        }
                    }
                    if (shouldStop) break
                }

                // Feed data to the decoder
                if (!eos) {
                    val inputBufferIndex = cd.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = cd.getInputBuffer(inputBufferIndex) ?: continue
                        val sampleSize = ex.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            // End of stream
                            cd.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                            Log.d(TAG, "EOS signaled")
                        } else {
                            val presentationTimeUs = ex.sampleTime
                            cd.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                            ex.advance()
                        }
                    }
                }

                // Get decoded output
                val outputBufferIndex = cd.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = cd.getOutputBuffer(outputBufferIndex) ?: continue

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        cd.releaseOutputBuffer(outputBufferIndex, false)
                        Log.d(TAG, "Decode EOS reached")
                        break
                    }

                    if (bufferInfo.size > 0) {
                        // Convert short[] to float[] (normalized to -1.0..1.0)
                        val floatChunk = decodeToFloat(outputBuffer, bufferInfo.size)
                        if (floatChunk.isNotEmpty()) {
                            val frameCount = floatChunk.size / sampleCount
                            val pushed = UsbDacManager.pushPcm(floatChunk, frameCount)
                            if (pushed < 0) {
                                Log.w(TAG, "pushPcm returned $pushed — DAC may have disconnected")
                                break
                            }
                        }
                    }

                    cd.releaseOutputBuffer(outputBufferIndex, false)

                    // Update position from presentation timestamp
                    if (bufferInfo.presentationTimeUs > 0) {
                        positionMs = bufferInfo.presentationTimeUs / 1000L
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = cd.outputFormat
                    val sr = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val ch = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    Log.d(TAG, "Output format: $sr Hz, $ch channels")
                }
            }
        } catch (e: Exception) {
            if (!shouldStop) {
                Log.e(TAG, "Decode loop error: ${e.message}", e)
                onError("Decode error: ${e.message}")
            }
        } finally {
            if (!shouldStop) {
                // Natural completion
                try {
                    cd.stop()
                    cd.release()
                } catch (_: Exception) {}
                try {
                    ex.release()
                } catch (_: Exception) {}
                codec = null
                extractor = null
                isPlaying = false
                UsbDacManager.stopAndRelease()
                Log.d(TAG, "Decode loop finished, calling onCompletion")
                onCompletion()
            }
        }
    }

    /**
     * Decode a short[] buffer from MediaCodec output into a normalized float[].
     * Audio is interleaved.
     */
    private fun decodeToFloat(buffer: ByteBuffer, size: Int): FloatArray {
        val shortCount = size / 2  // 2 bytes per short (16-bit PCM)
        if (shortCount <= 0) return FloatArray(0)

        val tmp = ShortArray(shortCount)
        val floatResult = FloatArray(shortCount)

        // Extract shorts from ByteBuffer
        if (buffer.isDirect) {
            // Direct buffer: use bulk get
            buffer.position(0)
            for (i in 0 until shortCount) {
                tmp[i] = buffer.short
            }
        } else {
            // Non-direct: copy bytes then interpret as shorts
            val bytes = ByteArray(size)
            buffer.position(0)
            buffer.get(bytes)
            for (i in 0 until shortCount) {
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt()
                tmp[i] = ((hi shl 8) or lo).toShort()
            }
        }

        // Normalize to [-1.0, 1.0]
        for (i in 0 until shortCount) {
            floatResult[i] = tmp[i].toFloat() / Short.MAX_VALUE.toFloat()
        }

        return floatResult
    }
}
