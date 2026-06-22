package com.sdw.music.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OboeAudioSink — Full AudioSink implementation using Oboe (AAudio native) output.
 *
 * Replaces DefaultAudioSink to route PCM audio through JNI → Oboe → AAudio,
 * enabling Exclusive mode and potentially bypassing Motorola HAL 44.1→48kHz resampling.
 *
 * Architecture:
 *   ExoPlayer decoder → handleBuffer() → ByteBuffer → float[] → JNI ring buffer → Oboe callback → AAudio
 */
class OboeAudioSink(
    private val context: Context
) : AudioSink {

    companion object {
        private const val TAG = "OboeAudioSink"
        private const val DEFAULT_SAMPLE_RATE = 44100
        private const val DEFAULT_CHANNELS = 2
        private const val kRingBufferCapacity = 65536 // must match oboe_bridge.cpp

        var nativeLibLoaded = false
            private set

        init {
            try {
                System.loadLibrary("oboe_bridge")
                nativeLibLoaded = true
                Log.i(TAG, "oboe_bridge native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load oboe_bridge: ${e.message}")
                nativeLibLoaded = false
            }
        }

        /** Android 5-band EQ standard center frequencies */
        val EQ_FREQS = floatArrayOf(60f, 230f, 910f, 3600f, 14000f)
        const val EQ_Q = 1.0f

        private var sinkEqNativeAvailable: Boolean? = null
        private fun checkSinkEqNative(): Boolean {
            if (!nativeLibLoaded) return false
            if (sinkEqNativeAvailable != null) return sinkEqNativeAvailable!!
            sinkEqNativeAvailable = try {
                nativeIsSinkEqEnabled()  // just test if the function exists
                true  // ← FIX: no exception = native methods available, regardless of EQ state
            } catch (_: UnsatisfiedLinkError) {
                Log.w(TAG, "Sink EQ native methods not available in oboe_bridge, disabling")
                false
            }
            return sinkEqNativeAvailable!!
        }

        fun setSinkEqEnabled(enabled: Boolean) {
            if (checkSinkEqNative()) nativeSetSinkEqEnabled(enabled)
        }

        fun isSinkEqEnabled(): Boolean {
            return checkSinkEqNative() && nativeIsSinkEqEnabled()
        }

        /**
         * Apply 5-band EQ from millibel gains (matches Android Equalizer format)
         * @param gainsMb ShortArray of 5 gains in millibels (-1500 to +1500)
         */
        fun setSinkEqFromMillibels(gainsMb: ShortArray) {
            if (!checkSinkEqNative()) return
            val gainsDb = FloatArray(5) { gainsMb[it].toFloat() / 100.0f }
            nativeSetSinkEqAllBands(gainsDb, EQ_FREQS)
        }

        /** Set all EQ bands to flat (bypass) */
        fun setSinkEqFlat() {
            if (!checkSinkEqNative()) return
            val gainsDb = FloatArray(5) { 0f }
            nativeSetSinkEqAllBands(gainsDb, EQ_FREQS)
        }

        @JvmStatic private external fun nativeSetSinkEqEnabled(enabled: Boolean)
        @JvmStatic private external fun nativeIsSinkEqEnabled(): Boolean
        @JvmStatic private external fun nativeSetSinkEqBand(bandIndex: Int, freqHz: Float, gainDb: Float, q: Float)
        @JvmStatic private external fun nativeSetSinkEqAllBands(gainsDb: FloatArray, freqsHz: FloatArray?)
    }

    private external fun nativeDestroy()
    private external fun nativeWriteFloat(data: FloatArray, offset: Int, length: Int): Int
    private external fun nativeWriteShort(data: ShortArray, offset: Int, length: Int): Int
    private external fun nativeGetSampleRate(): Int
    private external fun nativeGetChannelCount(): Int
    private external fun nativeIsExclusive(): Boolean
    private external fun nativeGetFramesWritten(): Long
    private external fun nativeGetBufferAvailable(): Int
    private external fun nativeGetPresentationTimeUs(): Long
    private external fun nativeInit(): Long
    private external fun nativeOpenStream(sampleRate: Int, channels: Int): Boolean
    private external fun nativeStart(): Boolean
    private external fun nativePause()
    private external fun nativeFlush()
    private external fun nativeClose()

    // ---- Fallback flag (uses companion object nativeLibLoaded) ----

    // ---- State ----
    private var streamHandle: Long = 0
    private var isInitialized = false
    private var isConfigured = false
    private var isStarted = false
    private var currentFormat: Format? = null
    private var currentSampleRate: Int = DEFAULT_SAMPLE_RATE
    private var currentChannelCount: Int = DEFAULT_CHANNELS
    private var currentPcmEncoding: Int = C.ENCODING_PCM_FLOAT
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var audioAttributes: AudioAttributes = AudioAttributes.DEFAULT
    private var audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var skipSilenceEnabled = false
    private var outputStreamOffsetUs: Long = 0

    // Buffer for converting ByteBuffer to arrays
    private var floatBuffer = FloatArray(4096)
    private var shortBuffer = ShortArray(4096)

    // Listener
    private var listener: AudioSink.Listener? = null

    // Position tracking
    private var startSystemTimeMs: Long = 0
    private var positionUsAtStart: Long = 0

    // ---- AudioSink interface implementation ----

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun setPlayerId(playerId: PlayerId?) {
        // No-op
    }

    override fun setClock(clock: Clock) {
        // No-op — we use SystemClock for timing
    }

    override fun supportsFormat(format: Format): Boolean {
        // Accept any audio format — ExoPlayer decodes non-PCM (MP3/AAC/WAV etc.)
        // to PCM before feeding to this sink. Only PCM reaches handleBuffer().
        val mime = format.sampleMimeType ?: return false
        if (!mime.startsWith("audio/")) return false
        // For raw PCM, check encoding is one we can handle
        if (mime == MimeTypes.AUDIO_RAW) {
            return format.pcmEncoding == C.ENCODING_PCM_FLOAT ||
                   format.pcmEncoding == C.ENCODING_PCM_16BIT ||
                   format.pcmEncoding == C.ENCODING_PCM_8BIT ||
                   format.pcmEncoding == C.ENCODING_PCM_24BIT ||
                   format.pcmEncoding == C.ENCODING_PCM_32BIT ||
                   format.pcmEncoding == Format.NO_VALUE // treat unknown as 16-bit
        }
        // For encoded formats (MP3, AAC, etc.), ExoPlayer will decode to PCM first
        return true
    }

    override fun getFormatSupport(format: Format): Int {
        if (!supportsFormat(format)) return AudioSink.SINK_FORMAT_UNSUPPORTED
        val mime = format.sampleMimeType ?: return AudioSink.SINK_FORMAT_UNSUPPORTED
        // PCM formats handled directly; encoded formats need transcoding (decoding)
        val isPcm = mime == MimeTypes.AUDIO_RAW &&
                    format.pcmEncoding != Format.NO_VALUE
        return if (isPcm) {
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
        }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        return nativeGetPresentationTimeUs()
    }

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        val sampleRate = inputFormat.sampleRate
        val channelCount = inputFormat.channelCount
        val encoding = inputFormat.pcmEncoding

        Log.d(TAG, "configure: sampleRate=$sampleRate, channels=$channelCount, encoding=$encoding, mime=${inputFormat.sampleMimeType}")

        if (!nativeLibLoaded) {
            Log.e(TAG, "oboe_bridge not loaded, cannot configure OboeAudioSink")
            throw AudioSink.ConfigurationException("oboe_bridge native library not loaded", inputFormat)
        }

        if (isStarted) {
            nativePause()
            nativeClose()
            isStarted = false
        }

        currentFormat = inputFormat
        currentSampleRate = sampleRate
        currentChannelCount = channelCount
        currentPcmEncoding = encoding
        streamEnded = false

        if (!isInitialized) {
            streamHandle = nativeInit()
            if (streamHandle != 0L) {
                isInitialized = true
                Log.i(TAG, "OboeAudioSink initialized (handle=$streamHandle)")
            } else {
                Log.e(TAG, "Failed to initialize OboeAudioSink")
                throw AudioSink.ConfigurationException("Failed to init OboeAudioSink", inputFormat)
            }
        }

        val opened = nativeOpenStream(sampleRate, channelCount)
        if (opened) {
            val actualRate = nativeGetSampleRate()
            val actualChannels = nativeGetChannelCount()
            val exclusive = nativeIsExclusive()
            Log.i(TAG, "Oboe stream configured: ${actualRate}Hz, ${actualChannels}ch, exclusive=$exclusive")
            isConfigured = true
        } else {
            Log.e(TAG, "Failed to open Oboe stream at ${sampleRate}Hz/${channelCount}ch")
            isConfigured = false
            throw AudioSink.ConfigurationException("Failed to open Oboe stream", inputFormat)
        }
    }

    override fun play() {
        if (!isConfigured) return
        if (!isStarted) {
            val started = nativeStart()
            if (started) {
                isStarted = true
                startSystemTimeMs = SystemClock.elapsedRealtime()
                Log.i(TAG, "Oboe stream play: started")
            } else {
                Log.e(TAG, "Oboe stream play: start failed")
            }
        }
    }

    override fun handleDiscontinuity() {
        Log.d(TAG, "handleDiscontinuity")
        // Clear the ring buffer on discontinuity
        nativeFlush()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (!isConfigured) {
            Log.w(TAG, "handleBuffer called before configure — skipping")
            return true
        }

        // Auto-start on first buffer
        if (!isStarted) {
            val started = nativeStart()
            if (!started) {
                Log.e(TAG, "Failed to start Oboe stream")
                return true
            }
            isStarted = true
            startSystemTimeMs = SystemClock.elapsedRealtime()
            Log.i(TAG, "Oboe stream auto-started on first buffer")
        }

        val remaining = buffer.remaining()
        if (remaining == 0) return true

        // Determine PCM format and write to ring buffer
        val bytesPerSample = when (currentPcmEncoding) {
            C.ENCODING_PCM_FLOAT -> 4
            C.ENCODING_PCM_32BIT -> 4
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        val numSamples = remaining / bytesPerSample

        // Resize buffers if needed
        if (currentPcmEncoding == C.ENCODING_PCM_FLOAT && numSamples > floatBuffer.size) {
            floatBuffer = FloatArray(numSamples + 1024)
        } else if (currentPcmEncoding == C.ENCODING_PCM_16BIT && numSamples > shortBuffer.size) {
            shortBuffer = ShortArray(numSamples + 1024)
        }

        try {
            when (currentPcmEncoding) {
                C.ENCODING_PCM_FLOAT -> {
                    val floatBuf = buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val toRead = minOf(floatBuf.remaining(), floatBuffer.size)
                    floatBuf.get(floatBuffer, 0, toRead)
                    nativeWriteFloat(floatBuffer, 0, toRead)
                }
                C.ENCODING_PCM_16BIT -> {
                    val shortBuf = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val toRead = minOf(shortBuf.remaining(), shortBuffer.size)
                    shortBuf.get(shortBuffer, 0, toRead)
                    nativeWriteShort(shortBuffer, 0, toRead)
                }
                else -> {
                    // Fallback: treat as 16-bit PCM
                    Log.w(TAG, "Unsupported encoding $currentPcmEncoding, treating as 16-bit PCM")
                    val shortBuf = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val toRead = minOf(shortBuf.remaining(), shortBuffer.size)
                    shortBuf.get(shortBuffer, 0, toRead)
                    nativeWriteShort(shortBuffer, 0, toRead)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleBuffer error: ${e.message}")
        }

        // 【v6.20】背压控制：ring buffer 满时等待音频回调消费，防止解码器无限写入
        val available = nativeGetBufferAvailable()
        if (available > kRingBufferCapacity * 3 / 4) {
            Thread.sleep(5) // 等待 5ms 让音频回调消费
        }

        return true
    }

    override fun playToEndOfStream() {
        Log.d(TAG, "playToEndOfStream")
        streamEnded = true
        // Let the ring buffer drain naturally
    }

    private var streamEnded = false

    override fun isEnded(): Boolean {
        if (!isStarted) return false
        // Only report ended after playToEndOfStream() is called AND buffer is drained
        return streamEnded && nativeGetBufferAvailable() == 0
    }

    override fun hasPendingData(): Boolean {
        if (!isStarted) return false
        return nativeGetBufferAvailable() > 0
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        return playbackParameters
    }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        this.skipSilenceEnabled = skipSilenceEnabled
    }

    override fun getSkipSilenceEnabled(): Boolean {
        return skipSilenceEnabled
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
    }

    override fun getAudioAttributes(): AudioAttributes? {
        return audioAttributes
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        this.audioSessionId = audioSessionId
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        // Not supported in Oboe path
    }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        // Not supported in Oboe path
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        this.outputStreamOffsetUs = outputStreamOffsetUs
    }

    override fun enableTunnelingV21() {
        // Not supported
    }

    override fun disableTunneling() {
        // Not supported
    }

    override fun setOffloadMode(offloadMode: Int) {
        // No offload support
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {
        // No offload support
    }

    override fun setVolume(volume: Float) {
        // Volume handled at system level
    }

    override fun pause() {
        if (isStarted) {
            nativePause()
            isStarted = false
            Log.i(TAG, "Oboe stream paused")
        }
    }

    override fun flush() {
        if (isConfigured) {
            nativeFlush()
            Log.d(TAG, "Oboe stream flushed")
        }
    }

    override fun reset() {
        Log.d(TAG, "reset")
        nativeClose()
        isStarted = false
        isConfigured = false
        streamEnded = false
        currentFormat = null
        currentSampleRate = DEFAULT_SAMPLE_RATE
        currentChannelCount = DEFAULT_CHANNELS
    }

    override fun release() {
        Log.i(TAG, "release")
        nativeClose()
        nativeDestroy()
        isInitialized = false
        isConfigured = false
        isStarted = false
        streamEnded = false
        streamHandle = 0
    }

    // ---- Diagnostic info ----

    fun getActualSampleRate(): Int = nativeGetSampleRate()
    fun getActualChannelCount(): Int = nativeGetChannelCount()
    fun isExclusiveMode(): Boolean = nativeIsExclusive()
    fun getFramesWritten(): Long = nativeGetFramesWritten()
}
