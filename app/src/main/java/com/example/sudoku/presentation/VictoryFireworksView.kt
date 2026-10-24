package com.example.sudoku.presentation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class VictoryFireworksView(
    context: Context
) : View(context) {

    private data class Spark(
        val angle: Float,
        val distanceFactor: Float,
        val radiusDp: Float,
        val color: Int
    )

    private data class Burst(
        val xFraction: Float,
        val yFraction: Float,
        val delaySeconds: Float,
        val sizeFactor: Float,
        val sparks: List<Spark>
    )

    private val density = resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }

    private val colors = listOf(
        Color.rgb(255, 215, 35),
        Color.rgb(75, 255, 120),
        Color.rgb(45, 195, 255),
        Color.rgb(255, 105, 35),
        Color.rgb(215, 95, 255),
        Color.rgb(255, 70, 115)
    )

    private val bursts = listOf(
        createBurst(
            x = 0.26f,
            y = 0.32f,
            delay = 0f,
            size = 0.34f
        ),
        createBurst(
            x = 0.74f,
            y = 0.38f,
            delay = 0.35f,
            size = 0.34f
        ),
        createBurst(
            x = 0.50f,
            y = 0.24f,
            delay = 0.70f,
            size = 0.42f
        ),
        createBurst(
            x = 0.30f,
            y = 0.58f,
            delay = 1.05f,
            size = 0.32f
        ),
        createBurst(
            x = 0.70f,
            y = 0.62f,
            delay = 1.40f,
            size = 0.36f
        )
    )

    private var elapsedSeconds = 0f
    private var animator: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false

        importantForAccessibility =
            IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun createBurst(
        x: Float,
        y: Float,
        delay: Float,
        size: Float
    ): Burst {
        val mainColor = colors.random()

        val sparks = List(SPARKS_PER_BURST) { index ->
            val angle = (
                    2.0 * PI * index / SPARKS_PER_BURST +
                            Random.nextDouble(-0.035, 0.035)
                    ).toFloat()

            // Часть искр образует внутреннее кольцо,
            // остальные — большой внешний разлёт.
            val distanceFactor =
                if (index % 4 == 0) {
                    Random.nextFloat() * 0.20f + 0.45f
                } else {
                    Random.nextFloat() * 0.25f + 0.75f
                }

            Spark(
                angle = angle,
                distanceFactor = distanceFactor,
                radiusDp = Random.nextFloat() * 1.2f + 2.2f,
                color =
                    if (index % 5 == 0) {
                        colors[index % colors.size]
                    } else {
                        mainColor
                    }
            )
        }

        return Burst(
            xFraction = x,
            yFraction = y,
            delaySeconds = delay,
            sizeFactor = size,
            sparks = sparks
        )
    }

    fun start(onFinished: () -> Unit) {
        stop()
        elapsedSeconds = 0f

        animator = ValueAnimator.ofFloat(
            0f,
            TOTAL_SECONDS
        ).apply {
            duration = (TOTAL_SECONDS * 1000).toLong()
            interpolator = LinearInterpolator()

            addUpdateListener {
                elapsedSeconds = it.animatedValue as Float
                invalidate()
            }

            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(
                        animation: Animator
                    ) {
                        onFinished()
                    }
                }
            )

            start()
        }
    }

    fun stop() {
        animator?.let {
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }

        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val screenSize = min(width, height).toFloat()

        if (screenSize <= 0f) return

        for (burst in bursts) {
            val age = elapsedSeconds - burst.delaySeconds

            if (age < 0f || age >= BURST_SECONDS) {
                continue
            }

            val originX = width * burst.xFraction
            val originY = height * burst.yFraction

            // Масштабируем салют под размер экрана.
            val maxRadius = screenSize * burst.sizeFactor

            // Искры сначала разлетаются быстро,
            // затем постепенно замедляются.
            val expansion = expansionAt(age)

            val previousAge =
                (age - TRAIL_SECONDS).coerceAtLeast(0f)

            val previousExpansion = expansionAt(previousAge)

            val gravity = screenSize * 0.065f

            val fall = 0.5f * gravity * age * age

            val previousFall =
                0.5f * gravity * previousAge * previousAge

            // Первое время сохраняем полную яркость.
            // Затем плавно гасим искры.
            val opacity =
                if (age <= FULL_BRIGHTNESS_SECONDS) {
                    1f
                } else {
                    (
                            1f -
                                    (age - FULL_BRIGHTNESS_SECONDS) /
                                    (
                                            BURST_SECONDS -
                                                    FULL_BRIGHTNESS_SECONDS
                                            )
                            ).coerceIn(0f, 1f)
                }

            drawCentralFlash(
                canvas = canvas,
                x = originX,
                y = originY,
                age = age,
                maxRadius = maxRadius
            )

            for (spark in burst.sparks) {
                val directionX =
                    cos(spark.angle.toDouble()).toFloat()

                val directionY =
                    sin(spark.angle.toDouble()).toFloat()

                val distance =
                    maxRadius * spark.distanceFactor

                val x =
                    originX +
                            directionX * distance * expansion

                val y =
                    originY +
                            directionY * distance * expansion +
                            fall

                val previousX =
                    originX +
                            directionX * distance * previousExpansion

                val previousY =
                    originY +
                            directionY * distance * previousExpansion +
                            previousFall

                val radius = spark.radiusDp * density

                // Широкий полупрозрачный след.
                paint.style = Paint.Style.FILL
                paint.color = spark.color
                paint.alpha = (opacity * 65f).toInt()
                paint.strokeWidth = radius * 3.5f

                canvas.drawLine(
                    previousX,
                    previousY,
                    x,
                    y,
                    paint
                )

                // Яркая середина следа.
                paint.alpha = (opacity * 235f).toInt()
                paint.strokeWidth = radius * 1.15f

                canvas.drawLine(
                    previousX,
                    previousY,
                    x,
                    y,
                    paint
                )

                // Внешнее свечение искры.
                paint.alpha = (opacity * 35f).toInt()

                canvas.drawCircle(
                    x,
                    y,
                    radius * 4.5f,
                    paint
                )

                // Внутреннее свечение.
                paint.alpha = (opacity * 90f).toInt()

                canvas.drawCircle(
                    x,
                    y,
                    radius * 2.5f,
                    paint
                )

                // Цветное ядро.
                paint.alpha = (opacity * 255f).toInt()

                canvas.drawCircle(
                    x,
                    y,
                    radius,
                    paint
                )

                // Светлый центр делает искру
                // яркой даже на белом фоне.
                paint.color = Color.WHITE
                paint.alpha = (opacity * 245f).toInt()

                canvas.drawCircle(
                    x,
                    y,
                    radius * 0.45f,
                    paint
                )
            }
        }

        paint.alpha = 255
        paint.style = Paint.Style.FILL
    }

    private fun expansionAt(age: Float): Float {
        return (
                1.0 - exp(-2.8 * age.toDouble())
                ).toFloat()
    }

    private fun drawCentralFlash(
        canvas: Canvas,
        x: Float,
        y: Float,
        age: Float,
        maxRadius: Float
    ) {
        if (age >= FLASH_SECONDS) return

        val progress = age / FLASH_SECONDS
        val opacity = 1f - progress

        // Расширяющееся световое кольцо.
        paint.style = Paint.Style.STROKE
        paint.color = Color.rgb(255, 185, 35)
        paint.alpha = (opacity * 210f).toInt()
        paint.strokeWidth =
            (2f + opacity * 3f) * density

        canvas.drawCircle(
            x,
            y,
            maxRadius * 0.35f * progress,
            paint
        )

        // Мягкое золотое свечение в центре.
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(255, 205, 65)
        paint.alpha = (opacity * 80f).toInt()

        canvas.drawCircle(
            x,
            y,
            (12f + progress * 26f) * density,
            paint
        )

        // Яркая центральная вспышка.
        paint.color = Color.rgb(255, 245, 190)
        paint.alpha = (opacity * 255f).toInt()

        canvas.drawCircle(
            x,
            y,
            (5f + progress * 9f) * density,
            paint
        )
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val SPARKS_PER_BURST = 64

        // Каждая вспышка видна 3 секунды.
        const val BURST_SECONDS = 3.0f

        // Последняя вспышка начинается на 1.4 секунде:
        const val TOTAL_SECONDS = 4.4f

        const val FULL_BRIGHTNESS_SECONDS = 1.2f

        const val FLASH_SECONDS = 0.30f
        const val TRAIL_SECONDS = 0.11f
    }
}
