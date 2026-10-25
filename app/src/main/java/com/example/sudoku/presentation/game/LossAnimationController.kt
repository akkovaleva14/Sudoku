package com.example.sudoku.presentation.game

import android.animation.ValueAnimator
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.sudoku.R
import com.example.sudoku.databinding.FragmentGameBinding
import com.example.sudoku.domain.model.MistakeMode
import com.example.sudoku.domain.model.SudokuGame
import com.example.sudoku.presentation.shared.state.SudokuUiState
import kotlin.math.PI
import kotlin.math.sin

class LossAnimationController(
    private val ui: FragmentGameBinding
) {

    private val context = ui.root.context

    private val errorColor = ContextCompat.getColor(
        context,
        R.color.loss_error
    )

    private val secondaryColor = ContextCompat.getColor(
        context,
        R.color.text_secondary
    )

    private val restartLossColor = ContextCompat.getColor(
        context,
        R.color.text_primary
    )

    private val originalRestartColors =
        ui.restartButton.textColors

    private val originalRestartTypeface =
        ui.restartButton.typeface

    private var animator: ValueAnimator? = null

    private var latestGame: SudokuGame? = null
    private var animatedGameId: String? = null
    private var errorIndex = -1

    fun render(
        state: SudokuUiState,
        animateLoss: Boolean
    ) {
        val game = state.game
        latestGame = game

        val showCounter =
            !state.isBusy &&
                    state.error == null &&
                    game?.mistakeMode == MistakeMode.TWO_MISTAKES

        ui.mistakeCounterText.isVisible =
            showCounter

        if (showCounter && game != null) {
            ui.mistakeCounterText.text =
                context.getString(
                    R.string.mistakes_progress,
                    game.mistakeCount
                )
        }

        if (
            state.isBusy ||
            state.isPaused ||
            game == null ||
            !game.isLost
        ) {
            cancelAnimator()
            animatedGameId = null
            resetVisuals()
            return
        }

        // Защита на случай замены партии.
        if (
            animator != null &&
            animatedGameId != game.gameId
        ) {
            cancelAnimator()
        }

        if (animateLoss) {
            start(game)
        } else if (animator == null) {
            applyStaticLoss(game)
        }
    }

    private fun start(game: SudokuGame) {
        cancelAnimator()

        animatedGameId = game.gameId
        errorIndex = findErrorIndex(game)

        if (!animationsEnabled()) {
            applyStaticLoss(game)
            return
        }

        val animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_DURATION_MILLIS
            interpolator = LinearInterpolator()
        }

        animator = animation

        animation.addUpdateListener {
            if (animator !== animation) {
                return@addUpdateListener
            }

            val current = latestGame

            if (
                current == null ||
                !current.isLost ||
                current.gameId != game.gameId
            ) {
                finishImmediately()
                return@addUpdateListener
            }

            val progress =
                it.animatedValue as Float

            applyFrame(progress, game)

            if (progress >= 1f) {
                animator = null
                applyStaticLoss(game)
            }
        }

        // Начальное состояние выставляем сразу,
        // чтобы сообщение не мелькнуло перед анимацией.
        applyFrame(0f, game)
        animation.start()
    }

    private fun applyFrame(
        progress: Float,
        game: SudokuGame
    ) {
        // 0–1080 мс: покачивание и подсветка клетки.
        val shakeProgress =
            (progress / 0.45f).coerceIn(0f, 1f)

        val shake =
            if (shakeProgress < 1f) {
                (
                        sin(shakeProgress * PI * 6.0) *
                                (1f - shakeProgress) *
                                3.5f
                        ).toFloat()
            } else {
                0f
            }

        val flash =
            if (shakeProgress < 1f) {
                0.35f +
                        0.65f *
                        sin(shakeProgress * PI).toFloat()
            } else {
                0.35f
            }

        // 960–1800 мс: поле плавно затемняется.
        val dimProgress =
            ((progress - 0.40f) / 0.35f)
                .coerceIn(0f, 1f)

        ui.sudokuBoard.setLossVisuals(
            cellIndex = errorIndex,
            flash = flash,
            offsetDp = shake,
            dim = dimProgress * FINAL_DIM
        )

        // 240–1200 мс: пульсация счётчика ошибок.
        val pulseProgress =
            ((progress - 0.10f) / 0.40f)
                .coerceIn(0f, 1f)

        val pulse =
            sin(pulseProgress * PI)
                .toFloat()
                .coerceAtLeast(0f)

        val scale =
            if (
                game.mistakeMode == MistakeMode.TWO_MISTAKES
            ) {
                1f + pulse * 0.18f
            } else {
                1f
            }

        ui.mistakeCounterText.scaleX = scale
        ui.mistakeCounterText.scaleY = scale
        ui.mistakeCounterText.setTextColor(errorColor)

        // 1560–2400 мс: появляется сообщение о проигрыше.
        // Кнопка рестарта выделяется с начала эффекта.
        val revealProgress =
            ((progress - 0.65f) / 0.35f)
                .coerceIn(0f, 1f)

        ui.selectionText.alpha =
            revealProgress

        ui.selectionText.translationY =
            (1f - revealProgress) *
                    6f *
                    context.resources.displayMetrics.density

        ui.selectionText.setTextColor(errorColor)

        applyRestartLossStyle()
    }

    private fun applyStaticLoss(game: SudokuGame) {
        errorIndex = findErrorIndex(game)

        ui.sudokuBoard.setLossVisuals(
            cellIndex = errorIndex,
            flash = 0.35f,
            offsetDp = 0f,
            dim = FINAL_DIM
        )

        ui.mistakeCounterText.scaleX = 1f
        ui.mistakeCounterText.scaleY = 1f
        ui.mistakeCounterText.setTextColor(errorColor)

        ui.selectionText.alpha = 1f
        ui.selectionText.translationY = 0f
        ui.selectionText.setTextColor(errorColor)

        applyRestartLossStyle()
    }

    private fun applyRestartLossStyle() {
        // Тёмный текст в светлой теме,
        // светлый текст в тёмной.
        ui.restartButton.setTextColor(
            restartLossColor
        )

        ui.restartButton.setTypeface(
            originalRestartTypeface,
            Typeface.BOLD
        )
    }

    private fun resetVisuals() {
        ui.sudokuBoard.clearLossVisuals()

        ui.mistakeCounterText.scaleX = 1f
        ui.mistakeCounterText.scaleY = 1f
        ui.mistakeCounterText.setTextColor(
            secondaryColor
        )

        ui.selectionText.alpha = 1f
        ui.selectionText.translationY = 0f

        // Обычный цвет selectionText устанавливается
        // в GameFragment.renderExplanation().

        ui.restartButton.setTextColor(
            originalRestartColors
        )

        ui.restartButton.typeface =
            originalRestartTypeface
    }

    private fun findErrorIndex(game: SudokuGame): Int {
        val selected = game.selected

        // Последняя ошибка сохраняет выбранную клетку.
        return if (
            selected in 0..80 &&
            game.puzzle.givens[selected] == 0 &&
            game.values[selected] != 0 &&
            game.values[selected] !=
            game.puzzle.solution[selected]
        ) {
            selected
        } else {
            -1
        }
    }

    fun finishImmediately() {
        cancelAnimator()

        val game = latestGame

        if (game?.isLost == true) {
            applyStaticLoss(game)
        } else {
            resetVisuals()
        }
    }

    fun dispose() {
        cancelAnimator()

        latestGame = null
        animatedGameId = null

        resetVisuals()
    }

    private fun cancelAnimator() {
        val current = animator
        animator = null

        current?.removeAllUpdateListeners()
        current?.removeAllListeners()
        current?.cancel()
    }

    private fun animationsEnabled(): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            return ValueAnimator.areAnimatorsEnabled()
        }

        return try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        } catch (_: Exception) {
            // Если настройку прочитать нельзя,
            // используем статичное отображение.
            false
        }
    }

    private companion object {
        const val ANIMATION_DURATION_MILLIS = 2400L
        const val FINAL_DIM = 0.30f
    }
}