package com.aladdin.tv.ui.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.content.res.AppCompatResources
import com.aladdin.tv.R
import kotlin.math.sin

/** Pixel lamp, rising smoke and the existing launcher icon, drawn without video assets. */
class LampSplashView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint().apply { isAntiAlias = false }
    private val icon = AppCompatResources.getDrawable(context, R.drawable.ic_launcher)
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var finished = false
    private var loadingMode = false
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
        setOnClickListener { finish() }
    }

    fun start() {
        if (finished || animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2500L
            interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finish()
            })
            start()
        }
    }

    fun useAsLoadingIndicator() {
        loadingMode = true
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnClickListener(null)
        syncLoadingAnimation()
    }

    private fun syncLoadingAnimation() {
        if (!loadingMode) return
        if (!isShown || windowVisibility != VISIBLE) {
            cancelAnimation()
            return
        }
        if (animator != null) return
        animator = ValueAnimator.ofFloat(.18f, .60f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncLoadingAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncLoadingAnimation()
    }

    fun pauseAnimation() { animator?.pause() }
    fun resumeAnimation() { animator?.resume() }

    fun finish() {
        if (finished) return
        finished = true
        cancelAnimation()
        visibility = GONE
        onFinished?.invoke()
    }

    private fun cancelAnimation() {
        animator?.removeAllListeners()
        animator?.removeAllUpdateListeners()
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        cancelAnimation()
        onFinished = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!loadingMode) canvas.drawColor(Color.rgb(18, 20, 26))
        val size = if (loadingMode) minOf(width / 42f, height / 56f) else minOf(width / 112f, height / 76f)
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(size, size)
        val fade = ((progress - .66f) / .22f).coerceIn(0f, 1f)
        paint.alpha = ((1f - fade) * 255).toInt()
        val bob = sin(progress * 12f) * .6f
        for ((y, row) in lamp.withIndex()) for ((x, pixel) in row.withIndex()) {
            if (pixel != '.') {
                paint.color = lampColors[pixel - '0']
                paint.alpha = ((1f - fade) * 255).toInt()
                canvas.drawRect(x - 16f, y + 5f + bob, x - 15f, y + 6f + bob, paint)
            }
        }
        // Deterministic square particles rise from the spout, then gather into the icon.
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
        if (fade > 0f) {
            icon?.apply {
                alpha = (fade * 255).toInt()
                setBounds(-22, -25, 22, 19)
                draw(canvas)
            }
        }
        if (loadingMode) {
            canvas.restore()
            return
        }
        paint.color = Color.rgb(245, 245, 247)
        paint.alpha = 255
        paint.isAntiAlias = true
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 5f
        paint.typeface = android.graphics.Typeface.MONOSPACE
        canvas.drawText(context.getString(R.string.app_name), 0f, 32f, paint)
        paint.isAntiAlias = false
        canvas.restore()
    }
}
