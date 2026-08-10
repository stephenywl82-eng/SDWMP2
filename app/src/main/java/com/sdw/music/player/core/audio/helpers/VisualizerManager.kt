package com.sdw.music.player.core.audio.helpers

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player

class VisualizerManager(
    private val getPlayer: () -> Player?,
    private val getFftCallback: () -> ((ByteArray) -> Unit)?,
    private val tag: String = "VisualizerManager"
) {
    private var visualizer: Visualizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0

    fun setup() {
        retryCount = 0
        release()
        val player = getPlayer() as? androidx.media3.exoplayer.ExoPlayer ?: return
        val audioSessionId = player.audioSessionId
        if (audioSessionId == 0) {
            if (retryCount < 10) {
                retryCount++
                Log.d(tag, "Audio session ID not ready, retry $retryCount/10 in 500ms")
                handler.postDelayed({ setup() }, 500)
            } else {
                Log.w(tag, "Audio session ID never ready after 10 retries, giving up")
            }
            return
        }

        try {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        fft?.let { getFftCallback()?.invoke(it) }
                    }
                }, 15000, false, true)
                enabled = true
            }
            Log.d(tag, "Visualizer initialized with session ID: $audioSessionId")
        } catch (e: Exception) {
            Log.w(tag, "Visualizer construction failed (${e.message}), retrying in 800ms")
            visualizer = null
            if (retryCount < 8) {
                retryCount++
                handler.postDelayed({ setup() }, 800)
            } else {
                Log.e(tag, "Visualizer failed after 8 retries")
                retryCount = 0
            }
        }
    }

    fun release() {
        visualizer?.apply {
            enabled = false
            release()
        }
        visualizer = null
        Log.d(tag, "Visualizer released")
    }

    fun isReady(): Boolean = visualizer != null && visualizer?.enabled == true

    fun retry() {
        if (getFftCallback() != null && visualizer == null) {
            setup()
        }
    }
}
