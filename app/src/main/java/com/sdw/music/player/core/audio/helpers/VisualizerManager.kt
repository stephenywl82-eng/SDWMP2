package com.sdw.music.player.core.audio.helpers

import android.media.audiofx.Visualizer
import android.util.Log
import androidx.media3.common.Player

/**
 * 【v3.36】FFT 可视化数据管理
 * 从 MusicService 拆出，封装 Visualizer 生命周期
 */
class VisualizerManager(
    private val getPlayer: () -> Player?,
    private val getFftCallback: () -> ((ByteArray) -> Unit)?,
    private val tag: String = "VisualizerManager"
) {
    private var visualizer: Visualizer? = null

    /** 初始化或重试 Visualizer */
    fun setup() {
        try {
            release()
            val player = getPlayer() as? androidx.media3.exoplayer.ExoPlayer ?: return
            val audioSessionId = player.audioSessionId
            if (audioSessionId == 0) {
                Log.w(tag, "Audio session ID not ready")
                return
            }

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
            Log.e(tag, "Failed to initialize Visualizer: ${e.message}")
        }
    }

    /** 释放 Visualizer 资源 */
    fun release() {
        visualizer?.apply {
            enabled = false
            release()
        }
        visualizer = null
        Log.d(tag, "Visualizer released")
    }

    /** 检查 Visualizer 是否已就绪 */
    fun isReady(): Boolean = visualizer != null && visualizer?.enabled == true

    /** 重试初始化（audioSessionId=0 时延迟调用） */
    fun retry() {
        if (getFftCallback() != null && visualizer == null) {
            setup()
        }
    }
}
