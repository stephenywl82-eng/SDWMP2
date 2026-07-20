package com.sdw.music.player.core.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer

class UsbDacPlaybackController(
    private val onCompletion: () -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "UsbDacPlayback"
        const val DEFAULT_SAMPLE_RATE = 48000
        const val DEFAULT_CHANNELS = 2
        const val DEFAULT_BITS = 16  // TTGK DAC output 16-bit (Salt verified)
        private const val TIMEOUT_US = 10000L
        private const val PREBUFFER_TARGET_MS = 500L
    }

    @Volatile var isPlaying = false; private set
    @Volatile var positionMs = 0L; private set
    val durationMs get() = extractorDurationMs
    @Volatile var sourceSampleRate = 0; private set
    @Volatile var sourceBits = 16; private set
    @Volatile var sourceChannelCount = 0; private set

    private var decodeThread: Thread? = null
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var extractorDurationMs = 0L
    private var audioTrackIndex = -1
    private var dacSampleRate = DEFAULT_SAMPLE_RATE
    private var dacChannels = DEFAULT_CHANNELS

    private val pauseLock = Object()
    @Volatile private var paused = false
    @Volatile private var shouldStop = false

    fun open(
        filePath: String,
        dacSampleRate: Int = DEFAULT_SAMPLE_RATE,
        dacChannels: Int = DEFAULT_CHANNELS
    ): Boolean {
        this.dacSampleRate = dacSampleRate
        this.dacChannels = dacChannels
        releaseResources()

        return try {
            DebugLog.add(TAG, "open: $filePath")
            val ex = MediaExtractor()
            ex.setDataSource(filePath)
            extractor = ex

            audioTrackIndex = -1
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { audioTrackIndex = i; ex.selectTrack(i); break }
            }
            if (audioTrackIndex < 0) { onError("No audio track"); releaseResources(); return false }

            val fmt = ex.getTrackFormat(audioTrackIndex)
            sourceSampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            sourceChannelCount = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
            extractorDurationMs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L

            DebugLog.add(TAG, "open: mime=$mime srcSr=$sourceSampleRate srcCh=$sourceChannelCount dacSr=$dacSampleRate dacCh=$dacChannels dur=${extractorDurationMs}ms")

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(MediaFormat.createAudioFormat(mime, sourceSampleRate, sourceChannelCount), null, null, 0)
            codec.start()
            this.codec = codec
            DebugLog.add(TAG, "open: codec started OK")
            true
        } catch (e: Exception) {
            DebugLog.add(TAG, "open FAIL: ${e.message}")
            onError("Failed to open: ${e.message}")
            releaseResources()
            false
        }
    }

    fun play() {
        if (isPlaying) return
        if (codec == null || extractor == null) { onError("Not ready"); return }
        shouldStop = false; paused = false; isPlaying = true

        val targetFrames = ((dacSampleRate * PREBUFFER_TARGET_MS) / 1000L).toInt() * dacChannels
        DebugLog.add(TAG, "play: starting decode thread, prebuffer=${PREBUFFER_TARGET_MS}ms (${targetFrames} frames)")

        decodeThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            // Pre-buffer before starting DAC stream (Salt pattern: fill ring, then open tap)
            val preBufFrames = preBuffer(targetFrames)
            DebugLog.add(TAG, "play: prebuffer done, $preBufFrames frames")

            // Start DAC stream NOW — ring buffer is full, no underruns
            val started = UsbDacManager.startStreaming(dacSampleRate, dacChannels, DEFAULT_BITS)
            if (!started) { onError("startStreaming FAIL"); return@Thread }
            DebugLog.add(TAG, "play: DAC stream started after prebuffer, entering decode loop")
            decodeLoop()
        }, "UsbDacDecode").apply { isDaemon = true; start() }
    }

    fun pause() {
        if (!isPlaying) return
        paused = true; isPlaying = false
        UsbDacManager.stopAndRelease()
        synchronized(pauseLock) { (pauseLock as java.lang.Object).notifyAll() }
        DebugLog.add(TAG, "pause at ${positionMs}ms")
    }

    fun resume() {
        if (isPlaying) return
        paused = false; shouldStop = false
        UsbDacManager.startStreaming(dacSampleRate, dacChannels, DEFAULT_BITS)
        isPlaying = true
        DebugLog.add(TAG, "resume from ${positionMs}ms")
    }

    fun stop() {
        shouldStop = true; paused = false; isPlaying = false
        synchronized(pauseLock) { (pauseLock as java.lang.Object).notifyAll() }
        decodeThread?.interrupt(); decodeThread?.join(2000); decodeThread = null
        UsbDacManager.stopAndRelease()
        releaseResources()
        DebugLog.add(TAG, "stop")
    }

    fun stopDecode() {
        shouldStop = true; paused = false; isPlaying = false
        synchronized(pauseLock) { (pauseLock as java.lang.Object).notifyAll() }
        decodeThread?.interrupt(); decodeThread?.join(2000); decodeThread = null
        UsbDacManager.pauseStream()  // stop streamLoop, prevent underruns during silence
        releaseResources()
        DebugLog.add(TAG, "stopDecode (DAC stream paused)")
    }

    fun seekTo(timeMs: Long) {
        val ex = extractor ?: return; val cd = codec ?: return
        cd.flush()
        ex.seekTo(timeMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        positionMs = ex.sampleTime / 1000L
        DebugLog.add(TAG, "seekTo ${timeMs}ms → actual ${positionMs}ms")
    }

    // ============================================================
    // Internal
    // ============================================================

    private fun releaseResources() {
        codec?.stop(); codec?.release(); codec = null
        extractor?.release(); extractor = null
        audioTrackIndex = -1; extractorDurationMs = 0L
    }

    private fun preBuffer(targetFrames: Int): Int {
        val ex = extractor ?: return 0; val cd = codec ?: return 0
        val info = MediaCodec.BufferInfo()
        var pushed = 0; var eos = false
        try {
            while (!shouldStop && pushed < targetFrames && !eos) {
                val inIdx = cd.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = cd.getInputBuffer(inIdx) ?: continue
                    val sz = ex.readSampleData(buf, 0)
                    if (sz < 0) { cd.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                    else { cd.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0); ex.advance() }
                }
                val outIdx = cd.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx >= 0) {
                    val outBuf = cd.getOutputBuffer(outIdx) ?: continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { cd.releaseOutputBuffer(outIdx, false); break }
                    if (info.size > 0) {
                        val f = decodeToFloat(outBuf, info.size)
                        if (f.isNotEmpty()) {
                            UsbDacManager.pushPcm(f, f.size / dacChannels)
                            pushed += f.size / dacChannels
                        }
                    }
                    cd.releaseOutputBuffer(outIdx, false)
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) continue
                else break
            }
        } catch (e: Exception) { DebugLog.add(TAG, "preBuffer err: ${e.message}") }
        return pushed
    }

    private fun decodeLoop() {
        val ex = extractor ?: return; val cd = codec ?: return
        val info = MediaCodec.BufferInfo()
        var eos = false; val sampleCount = dacChannels
        var pushErrors = 0

        try {
            while (!shouldStop) {
                if (paused) synchronized(pauseLock) { while (paused && !shouldStop) (pauseLock as java.lang.Object).wait() }
                if (shouldStop) break

                if (!eos) {
                    val inIdx = cd.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = cd.getInputBuffer(inIdx) ?: continue
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) { cd.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                        else { cd.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0); ex.advance() }
                    }
                }

                val outIdx = cd.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx >= 0) {
                    val outBuf = cd.getOutputBuffer(outIdx) ?: continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { cd.releaseOutputBuffer(outIdx, false); break }
                    if (info.size > 0) {
                        val f = decodeToFloat(outBuf, info.size)
                        if (f.isNotEmpty()) {
                            val fc = f.size / sampleCount
                            if (UsbDacManager.pushPcm(f, fc) < 0) {
                                pushErrors++
                                if (pushErrors > 100) { DebugLog.add(TAG, "pushPcm: too many errors, stopping"); break }
                            } else pushErrors = 0
                        }
                    }
                    cd.releaseOutputBuffer(outIdx, false)
                    if (info.presentationTimeUs > 0) positionMs = info.presentationTimeUs / 1000L
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = cd.outputFormat
                    DebugLog.add(TAG, "fmt changed: sr=${nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)} ch=${nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}")
                }
            }
        } catch (e: Exception) {
            if (!shouldStop) { DebugLog.add(TAG, "decodeLoop err: ${e.message}"); onError("Decode: ${e.message}") }
        } finally {
            if (!shouldStop) {
                try { cd.stop(); cd.release() } catch (_: Exception) {}
                try { ex.release() } catch (_: Exception) {}
                codec = null; extractor = null; isPlaying = false
                UsbDacManager.stopAndRelease()
                DebugLog.add(TAG, "decodeLoop done, calling onCompletion")
                onCompletion()
            }
        }
    }

    private fun decodeToFloat(buffer: ByteBuffer, size: Int): FloatArray {
        val shortCount = size / 2
        if (shortCount <= 0) return FloatArray(0)
        val tmp = ShortArray(shortCount)
        if (buffer.isDirect) {
            buffer.position(0)
            for (i in 0 until shortCount) tmp[i] = buffer.short
        } else {
            val bytes = ByteArray(size)
            buffer.position(0); buffer.get(bytes)
            for (i in 0 until shortCount) {
                tmp[i] = (((bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 0xFF))).toShort()
            }
        }
        return FloatArray(shortCount) { tmp[it].toFloat() / Short.MAX_VALUE.toFloat() }
    }
}
