package com.sdw.music.player.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import com.sdw.music.player.R
import kotlin.math.*

/**
 * WaveSeekBar - 播放时带正弦波动画的进度条
 * 参考 Android 13 媒体通知 SquigglySlider 效果
 * 暂停时波形停止，播放时波形持续动画
 */
class WaveSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 回调接口
    var onSeekListener: OnSeekListener? = null

    interface OnSeekListener {
        fun onSeekChanged(progress: Int)
        fun onSeekStart(progress: Int)
        fun onSeekEnd(progress: Int)
    }

    // 状态
    private var progress = 0           // 当前进度 0-100
    private var maxProgress = 100       // 最大值
    private var isTracking = false     // 是否正在拖动
    private var isPlaying = false      // 是否正在播放（控制波形动画）

    // 画笔
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 颜色
    private var trackColor = Color.parseColor("#33FFFFFF")
    private var progressColor = Color.parseColor("#8E6FD0")   // 主题紫色
    private var waveColor = Color.parseColor("#B388FF")       // 亮紫
    private var thumbColor = Color.parseColor("#8E6FD0")
    private var thumbRingColor = Color.WHITE

    // 尺寸
    private val trackHeight = 8f * resources.displayMetrics.density
    private val thumbRadius = 8f * resources.displayMetrics.density
    private val thumbRingRadius = 10f * resources.displayMetrics.density
    private val waveAmplitude = 3f * resources.displayMetrics.density   // 波形幅度
    private val waveFrequency = 0.04f    // 波形频率

    // 动画
    private var wavePhase = 0f           // 波形相位 (0-2π)
    private var waveAnimator: ValueAnimator? = null
    private val waveSpeed = 2f            // 波形滚动速度

    // 渐变
    private var progressGradient: LinearGradient? = null
    private var waveGradient: LinearGradient? = null

    // 区域
    private var trackLeft = 0f
    private var trackRight = 0f
    private var trackTop = 0f
    private var trackBottom = 0f
    private var thumbX = 0f

    init {
        trackPaint.style = Paint.Style.FILL
        progressPaint.style = Paint.Style.FILL
        wavePaint.style = Paint.Style.FILL
        thumbPaint.style = Paint.Style.FILL
        thumbRingPaint.style = Paint.Style.STROKE
        thumbRingPaint.strokeWidth = 2f * resources.displayMetrics.density

        waveAnimator = ValueAnimator.ofFloat(0f, 2f * Math.PI.toFloat()).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                wavePhase = animator.animatedValue as Float
                if (isPlaying) invalidate()
            }
        }

        // 读取颜色属性
        context.theme.obtainStyledAttributes(attrs, R.styleable.WaveSeekBar, 0, 0).apply {
            try {
                trackColor = getColor(R.styleable.WaveSeekBar_wsbTrackColor, trackColor)
                progressColor = getColor(R.styleable.WaveSeekBar_wsbProgressColor, progressColor)
                waveColor = getColor(R.styleable.WaveSeekBar_wsbWaveColor, waveColor)
                thumbColor = getColor(R.styleable.WaveSeekBar_wsbThumbColor, thumbColor)
                thumbRingColor = getColor(R.styleable.WaveSeekBar_wsbThumbRingColor, thumbRingColor)
            } finally {
                recycle()
            }
        }

        updatePaints()
    }

    private fun updatePaints() {
        trackPaint.color = trackColor
        progressPaint.color = progressColor
        wavePaint.color = waveColor
        thumbPaint.color = thumbColor
        thumbRingPaint.color = thumbRingColor
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padding = thumbRingRadius + 4f * resources.displayMetrics.density
        trackLeft = padding
        trackRight = w - padding
        trackTop = h / 2f - trackHeight / 2f
        trackBottom = h / 2f + trackHeight / 2f
        thumbX = trackLeft + (trackRight - trackLeft) * progress / maxProgress.toFloat()

        progressGradient = LinearGradient(
            trackLeft, 0f, trackRight, 0f,
            intArrayOf(progressColor, waveColor),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        waveGradient = LinearGradient(
            trackLeft, 0f, trackRight, 0f,
            intArrayOf(waveColor, Color.WHITE),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerY = height / 2f

        // 1. 画背景轨道
        val trackRect = RectF(trackLeft, centerY - trackHeight / 2f, trackRight, centerY + trackHeight / 2f)
        canvas.drawRoundRect(trackRect, trackHeight / 2f, trackHeight / 2f, trackPaint)

        // 2. 画已播放进度（正弦波上边缘）
        val progressWidth = thumbX - trackLeft
        if (progressWidth > 0) {
            drawSquigglyProgress(canvas, trackLeft, centerY, progressWidth)
        }

        // 3. 画 thumb
        canvas.drawCircle(thumbX, centerY, thumbRingRadius, thumbRingPaint)
        canvas.drawCircle(thumbX, centerY, thumbRadius, thumbPaint)
    }

    private fun drawSquigglyProgress(canvas: Canvas, startX: Float, centerY: Float, width: Float) {
        val path = Path()
        val halfH = trackHeight / 2f

        // 上边缘正弦波
        path.moveTo(startX, centerY - halfH)
        var x = 0f
        while (x <= width) {
            val wx = startX + x
            // 正弦波计算
            val phase = wavePhase + x * waveFrequency
            val y = centerY - halfH + waveAmplitude * sin(phase.toDouble()).toFloat()
            if (x == 0f) {
                path.lineTo(wx, y)
            } else {
                path.lineTo(wx, y)
            }
            x += 2f
        }

        // 右边缘到下边缘
        path.lineTo(startX + width, centerY + halfH)

        // 下边缘正弦波（反向，相位差π）
        x = width
        while (x >= 0f) {
            val wx = startX + x
            val phase = wavePhase + x * waveFrequency + Math.PI.toFloat()
            val y = centerY + halfH + waveAmplitude * 0.5f * sin(phase.toDouble()).toFloat()
            path.lineTo(wx, y)
            x -= 2f
        }

        path.close()

        progressPaint.shader = progressGradient
        canvas.drawPath(path, progressPaint)
        progressPaint.shader = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isTracking = true
                updateThumb(event.x)
                onSeekListener?.onSeekStart(progress)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTracking) {
                    updateThumb(event.x)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isTracking) {
                    isTracking = false
                    updateThumb(event.x)
                    onSeekListener?.onSeekEnd(progress)
                    onSeekListener?.onSeekChanged(progress)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateThumb(x: Float) {
        val trackWidth = trackRight - trackLeft
        val rawProgress = ((x - trackLeft) / trackWidth * maxProgress).toInt()
        progress = rawProgress.coerceIn(0, maxProgress)
        thumbX = trackLeft + trackWidth * progress / maxProgress.toFloat()
        onSeekListener?.onSeekChanged(progress)
        invalidate()
    }

    // 公On方法
    fun setProgress(value: Int) {
        progress = value.coerceIn(0, maxProgress)
        thumbX = trackLeft + (trackRight - trackLeft) * progress / maxProgress.toFloat()
        invalidate()
    }

    fun getProgress(): Int = progress

    fun setMax(max: Int) {
        maxProgress = max
        invalidate()
    }

    fun setIsPlaying(playing: Boolean) {
        if (isPlaying != playing) {
            isPlaying = playing
            if (playing) {
                waveAnimator?.start()
            } else {
                waveAnimator?.pause()
            }
            invalidate()
        }
    }

    fun isIsPlaying(): Boolean = isPlaying

    fun setColors(track: Int, progress: Int, wave: Int, thumb: Int, thumbRing: Int) {
        trackColor = track
        progressColor = progress
        waveColor = wave
        thumbColor = thumb
        thumbRingColor = thumbRing
        updatePaints()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        waveAnimator?.cancel()
    }
}
