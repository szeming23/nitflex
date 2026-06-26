package com.nitflex.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.nitflex.app.utils.SeasonalEvent
import kotlin.math.sin
import kotlin.random.Random

/**
 * Decorative overlay that renders on top of the provider logo in HomeMobileFragment.
 * Matches the season: Pride flag corner, snowflakes, or a pumpkin emoji.
 *
 * Add to fragment_home_mobile.xml with the same size + position as iv_provider_logo,
 * set pointerEvents to none (clickable=false, focusable=false).
 */
class SeasonalEventView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val event = SeasonalEvent.current()

    // ── Pride ─────────────────────────────────────────────────────────────────
    private val prideColors = intArrayOf(
        Color.parseColor("#FF0018"),
        Color.parseColor("#FFA52C"),
        Color.parseColor("#FFFF41"),
        Color.parseColor("#008018"),
        Color.parseColor("#0000F9"),
        Color.parseColor("#86007D"),
    )
    private val pridePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ── Snow ──────────────────────────────────────────────────────────────────
    private val snowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val flakes = if (event == SeasonalEvent.CHRISTMAS || event == SeasonalEvent.NEW_YEAR) {
        (0 until 7).map { i ->
            SnowFlake(
                xFraction = 0.1f + i * 0.13f,
                radius = if (i % 2 == 0) 3f else 2f,
                speed = 1.6f + (i % 3) * 0.5f,
                phase = i * 0.4f,
            )
        }
    } else emptyList()

    private var snowAnimator: ValueAnimator? = null
    private var progress = 0f

    // ── Halloween ─────────────────────────────────────────────────────────────
    private val halloweenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f }
    private val pumpkin = "🎃"

    init {
        isClickable = false
        isFocusable = false

        if (event == SeasonalEvent.CHRISTMAS || event == SeasonalEvent.NEW_YEAR) {
            snowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1600
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { p ->
                    progress = p.animatedFraction
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        snowAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (event) {
            SeasonalEvent.PRIDE -> drawPride(canvas)
            SeasonalEvent.CHRISTMAS, SeasonalEvent.NEW_YEAR -> drawSnow(canvas)
            SeasonalEvent.HALLOWEEN -> drawHalloween(canvas)
            null -> {}
        }
    }

    private fun drawPride(canvas: Canvas) {
        val flagW = width * 0.25f
        val flagH = height * 0.18f
        val left = width - flagW - 2f
        val top = height - flagH - 2f
        val stripeH = flagH / prideColors.size

        prideColors.forEachIndexed { i, color ->
            pridePaint.color = color
            canvas.drawRect(
                left, top + i * stripeH,
                left + flagW, top + (i + 1) * stripeH,
                pridePaint
            )
        }
    }

    private fun drawSnow(canvas: Canvas) {
        for (flake in flakes) {
            val x = width * flake.xFraction
            val cycleDuration = flake.speed
            val flakeProgress = ((progress / cycleDuration + flake.phase) % 1f)
            val y = height * flakeProgress
            val alpha = when {
                flakeProgress < 0.15f -> (flakeProgress / 0.15f * 255).toInt()
                flakeProgress > 0.85f -> ((1f - flakeProgress) / 0.15f * 255).toInt()
                else -> 255
            }
            snowPaint.alpha = alpha
            canvas.drawCircle(x, y, flake.radius, snowPaint)
        }
    }

    private fun drawHalloween(canvas: Canvas) {
        val bounds = android.graphics.Rect()
        halloweenPaint.getTextBounds(pumpkin, 0, pumpkin.length, bounds)
        val x = width - bounds.width() - 2f
        val y = height - 2f
        canvas.drawText(pumpkin, x, y, halloweenPaint)
    }

    private data class SnowFlake(
        val xFraction: Float,
        val radius: Float,
        val speed: Float,
        val phase: Float,
    )
}
