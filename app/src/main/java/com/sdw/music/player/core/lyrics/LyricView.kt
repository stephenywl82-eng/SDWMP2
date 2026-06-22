package com.sdw.music.player

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import kotlin.math.abs

/**
 * 【Steven v2.1 美化版】视差Lyrics显示 View - 支持主题色渐变
 * 
 * 参考主流播放器设计（NetEase、QQ Music、Apple Music、Spotify）：
 * - 7行显示，当前行居中
 * - 平滑透明度渐变（非阶梯式）
 * - 当前行使用主题色（从专辑Cover提取）+ 渐变效果
 * - 平滑滚动动画
 * - 合理行间距
 */
class LyricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    // Lyrics数据
    private var lyrics: List<LyricLine> = emptyList()
    
    // 【v7.XX】手动滚动相关
    private var manualLineOffset = 0       // 手动滚动行偏移（正=向下，负=向上）
    private var isManualScrolling = false  // 是否正在手动滚动
    private val gestureDetector: GestureDetector by lazy {
        GestureDetector(context, LyricGestureListener())
    }
    private val scrollHandler = android.os.Handler(android.os.Looper.getMainLooper()!!)
    private val autoRevertRunnable = Runnable { animateRevertToCurrent() }
    private val AUTO_REVERT_DELAY = 3000L  // 松手 3 秒后恢复自动跟随
    
    // 当前显示的Lyrics行索引
    private var currentLineIndex = -1
    
    // TextView 缓存池
    private val textViewPool = mutableListOf<TextView>()
    
    // 【Steven v2.1】7行显示（当前行 + 上下各3行）
    private val visibleLines = 7
    
    // 【Steven v2.1 配色】
    private val highlightColor = Color.parseColor("#FFFFFF")      // 当前行：纯白（备用）
    private val normalColor = Color.parseColor("#E0FFFFFF")       // 普通行：淡白
    
    // 【Steven v2.1 字体大小】
    private val highlightTextSize = 28f   // 当前行：最大
    private val normalTextSize = 22f      // 普通行：标准
    
    // 【Steven v2.1】主题色（从专辑Cover提取）
    private var themeColor: Int = 0
    
    // 【Steven v2.1】是否启用渐变效果
    private var enableGradient = true
    
    // 【v2.9.6 新增】是否显示翻译
    private var showTranslation = true
    
    // 【v2.9.6 新增】是否有翻译内容
    private var hasTranslationContent = false

    /**
     * Callback when user clicks a lyric line to seek to that position
     */
    var onLineClick: ((LyricLine) -> Unit)? = null

    /**
     * Callback when user clicks a lyric line to seek to that position
     */

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER

        // 【v7.xx】上下留有过渡空间：高亮Lyrics在屏幕上半部居中（约40%处）
        // topPadding = 40% * viewH - 8.5 * lineH  → 高亮行移至40%位置
        // bottomPadding = 60% * viewH - 8.5 * lineH  → 下方有足够过渡空间
        // 具体数值在 onSizeChanged 中按实际 viewH 动态计算

        // 边缘淡出效果已移除（避免中间分割线视觉假象）
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val lineH = (highlightTextSize * resources.displayMetrics.scaledDensity).toInt() + 32
        val totalContentH = visibleLines * lineH
        // 70% 屏幕居中显示：上下各留 15%
        val desiredPad = (h * 0.15f).toInt()
        val effectivePad = if (totalContentH > h - 2 * desiredPad) {
            ((h - totalContentH) / 2).coerceAtLeast(0)
        } else {
            desiredPad
        }
        setPadding(16, effectivePad, 16, effectivePad)
    }

    /**
     * 【v7.XX】手势监听内部类
     */
    private inner class LyricGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            // Cancel自动恢复定时器
            scrollHandler.removeCallbacks(autoRevertRunnable)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            // 【v7.XX】方向修正：手指下滑(distanceY<0)→显示后面Lyrics(偏移+)
            //                    手指上滑(distanceY>0)→显示前面Lyrics(偏移-)
            val lineHeight = (highlightTextSize * resources.displayMetrics.density).toInt() + 32  // 含行间距
            val deltaLines = (-distanceY / lineHeight).toInt()
            
            if (deltaLines != 0) {
                val newOffset = (manualLineOffset + deltaLines).coerceIn(-lyrics.size + 1, lyrics.size - 1)
                if (newOffset != manualLineOffset) {
                    manualLineOffset = newOffset
                    isManualScrolling = true
                    // 用偏移后的 centerIndex Refresh显示
                    val displayIndex = (currentLineIndex + manualLineOffset).coerceIn(0, lyrics.size - 1)
                    updateDisplay(displayIndex)
                }
            }
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            // 快速滑动也触发滚动（复用 onScroll 逻辑）
            val lineHeight = (highlightTextSize * resources.displayMetrics.density).toInt() + 32
            // 【v7.XX】方向修正：下滑(velocityY<0)→偏移+，上滑(velocityY>0)→偏移-
            val flingLines = (-velocityY / lineHeight / 10).toInt().coerceIn(-10, 10)
            if (flingLines != 0) {
                val newOffset = (manualLineOffset + flingLines).coerceIn(-lyrics.size + 1, lyrics.size - 1)
                manualLineOffset = newOffset
                isManualScrolling = true
                val displayIndex = (currentLineIndex + manualLineOffset).coerceIn(0, lyrics.size - 1)
                updateDisplay(displayIndex)
            }
            return true
        }
    }

    /**
     * 【v7.XX】拦截触摸事件，交给 GestureDetector
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            // 松手后 3 秒自动恢复
            scrollHandler.postDelayed(autoRevertRunnable, AUTO_REVERT_DELAY)
        }
        return true
    }

    
    /**
     * 【v7.XX】manualLineOffset 的 setter（供属性动画使用）
     */
    @Suppress("UNUSED")
    fun setManualLineOffset(offset: Int) {
        manualLineOffset = offset
    }
    /**
     * 【v7.XX】松手后动画恢复自动跟随
     */
    private fun animateRevertToCurrent() {
        if (manualLineOffset == 0) {
            isManualScrolling = false
            return
        }
        // 逐行回弹动画
        val animator = ObjectAnimator.ofInt(this, "manualLineOffset", manualLineOffset, 0)
        animator.duration = 400
        animator.interpolator = android.view.animation.DecelerateInterpolator()
        animator.addUpdateListener {
            val displayIndex = (currentLineIndex + manualLineOffset).coerceIn(0, lyrics.size - 1)
            updateDisplay(displayIndex)
        }
        animator.start()
        manualLineOffset = 0
        isManualScrolling = false
    }

    /**
     * Settings主题色（从专辑Cover提取）
     */
    fun setThemeColor(color: Int) {
        this.themeColor = color
        // Refresh当前显示
        if (currentLineIndex >= 0) {
            updateDisplay(currentLineIndex)
        }
    }

    /**
     * SettingsLyrics数据
     */
    fun setLyrics(lyricList: List<LyricLine>) {
        lyrics = lyricList.sortedBy { it.timeMs }
        currentLineIndex = -1
        
        // 【v2.9.6】检测是否有翻译内容
        hasTranslationContent = lyrics.any { !it.translation.isNullOrBlank() }
        
        // 清空现有视图
        removeAllViews()
        textViewPool.clear()
        
        // 创建 TextView 池
        for (i in 0 until visibleLines) {
            val tv = createTextView()
            textViewPool.add(tv)
            addView(tv)
        }
        
        // 初始显示
        updateDisplay(0)
    }
    
    /**
     * 【v2.9.6 新增】Settings是否显示翻译
     */
    fun setShowTranslation(show: Boolean) {
        if (this.showTranslation != show) {
            this.showTranslation = show
            // Refresh当前显示
            if (currentLineIndex >= 0) {
                updateDisplay(currentLineIndex)
            }
        }
    }
    
    /**
     * 【v2.9.6 新增】是否有翻译内容
     */
    fun hasTranslation(): Boolean = hasTranslationContent
    
    /**
     * 【v2.9.6 新增】获取当前翻译On关状态
     */
    fun isTranslationEnabled(): Boolean = showTranslation

    /**
     * 根据播放进度更新Lyrics显示
     * @param positionMs 当前播放位置（毫秒）
     */
    fun updatePosition(positionMs: Long) {
        if (lyrics.isEmpty()) return
        
        // 查找当前Lyrics行
        val newIndex = LrcParser.findCurrentLineIndex(lyrics, positionMs)
        
        if (newIndex != currentLineIndex && newIndex >= 0) {
            currentLineIndex = newIndex
            // 【v7.XX】手动滚动时暂停自动跟随，只更新 currentLineIndex
            if (!isManualScrolling) {
                updateDisplay(newIndex)
            } else {
                // 仍在手动滚动状态，更新显示（用偏移）
                val displayIndex = (currentLineIndex + manualLineOffset).coerceIn(0, lyrics.size - 1)
                updateDisplay(displayIndex)
            }
        }
    }

    /**
     * 【Steven v2.1 核心】更新显示 - 平滑渐变效果 + 主题色
     * 【v7.XX】支持 manualLineOffset 手动滚动偏移
     */
    private fun updateDisplay(centerIndex: Int) {
        // 【v7.XX】手动滚动时，用偏移后的 centerIndex
        val actualCenterIndex = if (isManualScrolling) {
            (centerIndex + manualLineOffset).coerceIn(0, if (lyrics.isNotEmpty()) lyrics.size - 1 else 0)
        } else {
            centerIndex
        }
        
        if (lyrics.isEmpty() || actualCenterIndex < 0) {
            // 无Lyrics时显示空
            if (textViewPool.isNotEmpty()) {
                val centerTv = textViewPool[visibleLines / 2]
                centerTv.text = ""
                centerTv.textSize = highlightTextSize
                centerTv.setTextColor(highlightColor)
            }
            return
        }
        
        // 获取当前主题色
        val currentThemeColor = if (themeColor != 0) themeColor else MusicService.themeColor
        
        // 计算中心偏移（centerIndex 在中间行）
        val centerOffset = visibleLines / 2
        
        for (i in 0 until visibleLines) {
            val lyricIndex = actualCenterIndex - centerOffset + i
            val tv = textViewPool.getOrNull(i) ?: continue
            
            if (lyricIndex in lyrics.indices) {
                val line = lyrics[lyricIndex]
                
                // 【v2.9.6】处理翻译显示
                val displayText = if (showTranslation && !line.translation.isNullOrBlank()) {
                    "${line.text}\n${line.translation}"
                } else {
                    line.text.ifEmpty { "~" }
                }
                
                tv.text = displayText
                
                tv.tag = lyricIndex

                // 【Steven v2.1】计算距离中心的距离
                val distance = abs(i - centerOffset)
                updateTextStyle(tv, distance, i == centerOffset, currentThemeColor)
            } else {
                tv.text = ""
                tv.alpha = 0f
            }
        }
    }

    /**
     * 【Steven v2.1】创建Lyrics TextView
     */
    private fun createTextView(): TextView {

        return TextView(context).apply {
            // 宽度填充父容器，允许长Lyrics自动换行
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )

            // Click listener for seek-to-line
            setOnClickListener {
                val idx = tag as? Int ?: -1
                if (idx >= 0 && idx < lyrics.size) {
                    onLineClick?.invoke(lyrics[idx])
                }
            }
            textSize = normalTextSize
            setTextColor(normalColor)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)  // 行间距 32dp
            alpha = 0.5f
            
            // 【Steven v2.1】使用无衬线字体（更现代）
            typeface = Typeface.SANS_SERIF
            
            // 【Steven 关键】添加文字阴影，复杂背景下依然清晰
            setShadowLayer(6f, 1f, 1f, Color.parseColor("#60000000"))
        }
    }

    /**
     * 【Steven v2.1 核心】更新 TextView 样式 - 平滑渐变 + 主题色
     * @param distance 距离中心的距离（0=当前行，1=相邻行，2=更远，3=最远）
     * @param isHighlight 是否为高亮行
     * @param themeColor 主题色（从专辑Cover提取）
     */
    private fun updateTextStyle(tv: TextView, distance: Int, isHighlight: Boolean, themeColor: Int) {
        when (distance) {
            0 -> {
                // 当前行：最大、最亮、加粗 + 主题色渐变
                tv.textSize = highlightTextSize
                tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                tv.alpha = 1.0f
                
                // 【v6.10】纯白 + 金色强发光 → 暗色背景上最醒目
                tv.setTextColor(Color.WHITE)
                tv.setShadowLayer(20f, 0f, 0f, Color.parseColor("#CCFFD700"))
                
                // 淡入动画
                animateHighlight(tv)
            }
            1 -> {
                // 第1层：中等大小、70%透明、主题色调
                tv.textSize = normalTextSize
                tv.setTextColor(normalColor)
                tv.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL)
                tv.alpha = 0.7f
                tv.setShadowLayer(6f, 1f, 1f, Color.parseColor("#40000000"))
            }
            2 -> {
                // 第2层：标准大小、45%透明
                tv.textSize = normalTextSize
                tv.setTextColor(normalColor)
                tv.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL)
                tv.alpha = 0.45f
                tv.setShadowLayer(4f, 1f, 1f, Color.parseColor("#30000000"))
            }
            3 -> {
                // 第3层：标准大小、25%透明
                tv.textSize = normalTextSize
                tv.setTextColor(normalColor)
                tv.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL)
                tv.alpha = 0.25f
                tv.setShadowLayer(2f, 1f, 1f, Color.parseColor("#20000000"))
            }
        }
    }

    /**
     * 【Steven v2.1】应用渐变文字效果
     */
    private fun applyGradientText(tv: TextView, startColor: Int, endColor: Int) {
        tv.post {
            val width = tv.paint.measureText(tv.text.toString()).coerceAtLeast(1f)
            val gradient = LinearGradient(
                0f, 0f, width, 0f,
                startColor,
                endColor,
                Shader.TileMode.CLAMP
            )
            tv.paint.shader = gradient
            tv.invalidate()
        }
    }

    /**
     * 【Steven v2.1】提亮颜色（确保在深色背景上可见）
     */
    private fun brightenColor(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        // 提高亮度
        hsv[2] = (hsv[2] + (1f - hsv[2]) * factor).coerceIn(0.7f, 1f)
        // 降低饱和度（避免太鲜艳）
        hsv[1] = (hsv[1] * 0.7f).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    /**
     * 【v1.9.5】高亮动画 - 增强版组合动画
     * 
     * 效果：缩放弹出 + 淡入 + 轻微位移，营造律动感
     */
    private fun animateHighlight(tv: TextView) {
        // 【v3.0.4】更平滑的动画：更柔和的起始状态 + 弹性效果
        val scaleX = ObjectAnimator.ofFloat(tv, "scaleX", 0.88f, 1.0f)
        val scaleY = ObjectAnimator.ofFloat(tv, "scaleY", 0.88f, 1.0f)
        val alpha = ObjectAnimator.ofFloat(tv, "alpha", 0.5f, 1.0f)
        val translationY = ObjectAnimator.ofFloat(tv, "translationY", 12f, 0f)
        
        AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha, translationY)
            duration = 400  // 稍微延长，让过渡更自然
            interpolator = OvershootInterpolator(1.2f)  // 弹性效果
            start()
        }
    }
    
    /**
     * 【v1.9.5】行切换动画 - 平滑过渡
     * 【v3.0.4】优化：使用弹性插值器
     * 
     * 效果：整组Lyrics轻微上下位移，营造流动感
     */
    private fun animateLineTransition(direction: Int) {
        val translationY = if (direction > 0) -20f else 20f
        val animator = ObjectAnimator.ofFloat(this, "translationY", translationY, 0f)
        animator.apply {
            duration = 350
            interpolator = OvershootInterpolator(1.1f)  // 弹性效果
            start()
        }
    }

    /**
     * 清空Lyrics
     */
    fun clear() {
        lyrics = emptyList()
        currentLineIndex = -1
        textViewPool.forEach { 
            it.text = ""
            it.paint.shader = null  // 清除渐变
        }
    }

    /**
     * 获取当前Lyrics行
     */
    fun getCurrentLine(): LyricLine? {
        return if (currentLineIndex in lyrics.indices) lyrics[currentLineIndex] else null
    }
}



