package com.aladdin.tv.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import com.aladdin.tv.R
import kotlin.math.sin

/**
 * 像素神燈、吐煙粒子與應用程式圖示開機動畫
 * 不依賴系統 ValueAnimator 時長縮放，保證在關閉動畫之電視盒或各類裝置上完整播放 3.5 秒
 */
class LampSplashView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    companion object {
        const val DURATION_MS = 3500L
        const val STATIC_LOGO_DURATION_MS = 1000L
        private const val SKIP_GUARD_MS = 600L
    }

    private val paint = Paint().apply { isAntiAlias = false }
    private val icon = AppCompatResources.getDrawable(context, R.drawable.ic_launcher)
    private var progress = 0f
    private var finished = false
    private var isRunning = false
    private var isStaticLogoMode = false
    private var startUptime = 0L
    private var pausedUptime = 0L

    var onFinished: (() -> Unit)? = null

    private val lamp = arrayOf(
        "..................33............",
        ".................3333...........",
        "................222222..........",
        "...............11111111.........",
        "....2222......2222222222........",
        "..22....22...211111111112........",
        ".22......22221111111111112222222.",
        ".22......2111111111111111111112.",
        "..22.....211111111111111111112..",
        "...22222221111111111111111122...",
        "..........22222222222222222.....",
        "............22222222222.........",
        "................222.............",
        "...............22222............",
        "............22222222222.........",
        "...........3333333333333........"
    )
    private val lampColors = intArrayOf(0, 0xFFFFD66BL.toInt(), 0xFFD99B26L.toInt(), 0xFF8F581BL.toInt())

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.splash_description)
        setOnClickListener {
            // 點擊略過：保護期內忽略誤觸 (靜態模式可立即點擊進入)
            if (canSkipNow()) finish()
        }
    }

    fun canSkipNow(): Boolean {
        if (!isRunning || startUptime == 0L) return false
        if (isStaticLogoMode) return true
        val elapsed = SystemClock.uptimeMillis() - startUptime
        return elapsed >= SKIP_GUARD_MS
    }

    fun start(staticLogo: Boolean = false) {
        if (finished || isRunning) return
        isStaticLogoMode = staticLogo
        progress = if (staticLogo) 1f else 0f
        isRunning = true
        startUptime = SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun pauseAnimation() {
        if (!isRunning || finished) return
        isRunning = false
        pausedUptime = SystemClock.uptimeMillis()
        Choreographer.getInstance().removeFrameCallback(this)
    }

    fun resumeAnimation() {
        if (isRunning || finished || startUptime == 0L) return
        val pauseDuration = SystemClock.uptimeMillis() - pausedUptime
        startUptime += pauseDuration
        isRunning = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun finish() {
        if (finished) return
        finished = true
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
        visibility = GONE
        onFinished?.invoke()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning || finished) return
        val elapsed = SystemClock.uptimeMillis() - startUptime
        val limit = if (isStaticLogoMode) STATIC_LOGO_DURATION_MS else DURATION_MS
        progress = if (isStaticLogoMode) 1f else (elapsed.toFloat() / limit).coerceIn(0f, 1f)
        invalidate()

        if (elapsed >= limit) {
            finish()
        } else {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onDetachedFromWindow() {
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
        onFinished = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        canvas.drawColor(Color.rgb(18, 20, 26))
        val size = minOf(width / 112f, height / 76f)
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(size, size)

        val fade = ((progress - .66f) / .22f).coerceIn(0f, 1f)
        paint.alpha = ((1f - fade) * 255).toInt()
        val bob = sin(progress * 12f) * .6f

        // 繪製像素神燈
        for ((y, row) in lamp.withIndex()) {
            for ((x, pixel) in row.withIndex()) {
                if (pixel != '.') {
                    paint.color = lampColors[pixel - '0']
                    paint.alpha = ((1f - fade) * 255).toInt()
                    canvas.drawRect(x - 16f, y + 5f + bob, x - 15f, y + 6f + bob, paint)
                }
            }
        }

        // 噴嘴升起並凝聚為圖示的粒子 (72 顆粒子)
        for (i in 0 until 72) {
            val age = ((progress - .12f) * 1.8f - i * .009f).coerceIn(0f, 1f)
            if (age <= 0f) continue
            val gather = ((progress - .53f) / .30f).coerceIn(0f, 1f)
            val x = 13f + sin(i * 2.4f + age * 7f) * (3f + age * 13f)
            val y = 11f - age * 43f
            val targetX = (i % 9 - 4) * 3f
            val targetY = (i / 9 - 4) * 3f - 3f
            val px = (x + (targetX - x) * gather).toInt().toFloat()
            val py = (y + (targetY - y) * gather).toInt().toFloat()
            paint.color = if (i % 3 == 0) 0xFF00E5FFL.toInt() else 0xFFC7D9E5L.toInt()
            paint.alpha = ((1f - fade) * 210).toInt()
            canvas.drawRect(px, py, px + 2f, py + 2f, paint)
        }

        // 漸顯 Launcher 圖示
        if (fade > 0f) {
            icon?.apply {
                alpha = (fade * 255).toInt()
                setBounds(-22, -25, 22, 19)
                draw(canvas)
            }
        }

        // 應用程式名稱文字
        paint.color = Color.rgb(245, 245, 247)
        paint.alpha = 255
        paint.isAntiAlias = true
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 5f
        paint.typeface = android.graphics.Typeface.MONOSPACE
        canvas.drawText(context.getString(R.string.app_name), 0f, 31f, paint)

        // 標語副標題：隨動畫凝聚漸顯 (如你所願 / Live Streams as You Wish.)
        val sloganAlpha = if (fade > 0f) (fade * 220).toInt().coerceIn(0, 220) else 0
        if (sloganAlpha > 0) {
            paint.color = Color.rgb(217, 155, 38) // 神燈金 Accent 配色
            paint.alpha = sloganAlpha
            paint.textSize = 2.8f
            paint.typeface = android.graphics.Typeface.SANS_SERIF
            canvas.drawText(context.getString(R.string.app_slogan), 0f, 37f, paint)
        }
        paint.isAntiAlias = false
        canvas.restore()
    }
}
