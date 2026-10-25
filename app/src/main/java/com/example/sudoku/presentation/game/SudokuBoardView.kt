package com.example.sudoku.presentation.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.sudoku.R
import com.example.sudoku.domain.game.SudokuRules
import com.example.sudoku.domain.hint.LogicalHint
import kotlin.math.min

class SudokuBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    // При переключении темы Activity пересоздаётся,
    // и новая View загружает выбранную палитру.
    private val boardBackgroundColor = themedColor(R.color.board_background)
    private val selectedColor = themedColor(R.color.board_selected)
    private val matchingColor = themedColor(R.color.board_matching)
    private val relatedColor = themedColor(R.color.board_related)
    private val excludedColor = themedColor(R.color.board_excluded)
    private val exclusionMarkColor = themedColor(R.color.board_exclusion_mark)
    private val lossCellColor = themedColor(R.color.loss_cell_background)
    private val errorColor = themedColor(R.color.loss_error)
    private val givenDigitColor = themedColor(R.color.text_primary)
    private val enteredDigitColor = themedColor(R.color.accent)
    private val gridColor = themedColor(R.color.board_grid)
    private val notesColor = themedColor(R.color.board_notes)
    private val hintTargetColor = themedColor(R.color.board_hint_target)
    private val hintEvidenceColor = themedColor(R.color.board_hint_evidence)
    private val hintBorderColor = themedColor(R.color.board_hint_border)
    private val evidenceTextColor = themedColor(R.color.board_evidence_text)

    private var givens: List<Int> = List(81) { 0 }
    private var values: List<Int> = List(81) { 0 }
    private var notes: List<Int> = List(81) { 0 }
    private var solution: List<Int> = List(81) { 0 }

    private var selected = -1

    private var errorCells: Set<Int> = emptySet()
    private var hint: LogicalHint? = null

    private var lossCellIndex = -1
    private var lossFlash = 0f
    private var lossOffsetDp = 0f
    private var lossDim = 0f

    var onCellSelected: ((Int) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
    }

    private fun themedColor(resourceId: Int): Int {
        return ContextCompat.getColor(
            context,
            resourceId
        )
    }

    fun setState(
        givens: List<Int>,
        values: List<Int>,
        notes: List<Int>,
        solution: List<Int>,
        selected: Int,
        hint: LogicalHint? = null
    ) {
        require(givens.size == 81)
        require(values.size == 81)
        require(notes.size == 81)
        require(solution.size == 81)
        require(selected in -1..80)

        require(hint == null || (hint.targetIndex in 0..80 && hint.digit in 1..9))

        if (
            this.givens == givens &&
            this.values == values &&
            this.notes == notes &&
            this.solution == solution &&
            this.selected == selected &&
            this.hint == hint
        ) {
            return
        }

        this.givens = givens.toList()
        this.values = values.toList()
        this.notes = notes.toList()
        this.solution = solution.toList()
        this.selected = selected
        this.hint = hint

        errorCells =
            SudokuRules.incorrectCells(
                values = this.values,
                solution = this.solution
            ) + SudokuRules.conflicts(this.values)

        invalidate()
    }

    fun setLossVisuals(
        cellIndex: Int,
        flash: Float,
        offsetDp: Float,
        dim: Float
    ) {
        val safeIndex =
            cellIndex.takeIf { it in 0..80 } ?: -1

        val safeFlash = flash.coerceIn(0f, 1f)
        val safeDim = dim.coerceIn(0f, 1f)

        if (
            lossCellIndex == safeIndex &&
            lossFlash == safeFlash &&
            lossOffsetDp == offsetDp &&
            lossDim == safeDim
        ) {
            return
        }

        lossCellIndex = safeIndex
        lossFlash = safeFlash
        lossOffsetDp = offsetDp
        lossDim = safeDim

        invalidate()
    }

    fun clearLossVisuals() {
        setLossVisuals(
            cellIndex = -1,
            flash = 0f,
            offsetDp = 0f,
            dim = 0f
        )
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int
    ) {
        val desiredSize = (360 * density).toInt()

        val measuredWidth = resolveSize(
            desiredSize,
            widthMeasureSpec
        )

        val measuredHeight = resolveSize(
            measuredWidth,
            heightMeasureSpec
        )

        setMeasuredDimension(
            measuredWidth,
            measuredHeight
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val boardSize = min(width, height).toFloat()
        val cellSize = boardSize / 9f

        if (cellSize <= 0f) return

        val activeHint = hint

        val selectedValue =
            if (selected in 0..80) {
                values[selected]
            } else {
                0
            }

        paint.style = Paint.Style.FILL
        paint.alpha = 255

        // Фоны клеток и подсветка подсказки.
        for (index in 0 until 81) {
            val row = index / 9
            val col = index % 9

            paint.color =
                if (activeHint != null) {
                    when {
                        index == activeHint.targetIndex ->
                            hintTargetColor

                        index in activeHint.evidenceCells ->
                            hintEvidenceColor

                        index in activeHint.excludedCells ->
                            excludedColor

                        index in activeHint.highlightedCells ->
                            relatedColor

                        else ->
                            boardBackgroundColor
                    }
                } else {
                    when {
                        index == selected ->
                            selectedColor

                        selectedValue != 0 &&
                                values[index] == selectedValue ->
                            matchingColor

                        selected in 0..80 &&
                                SudokuRules.arePeers(index, selected) ->
                            relatedColor

                        else ->
                            boardBackgroundColor
                    }
                }

            canvas.drawRect(
                col * cellSize,
                row * cellSize,
                (col + 1) * cellSize,
                (row + 1) * cellSize,
                paint
            )
        }

        // Подсветка последней ошибочной клетки.
        // Сетка и соседние клетки не смещаются.
        if (lossCellIndex in 0..80 && lossFlash > 0f) {
            val row = lossCellIndex / 9
            val col = lossCellIndex % 9

            val left = col * cellSize
            val top = row * cellSize
            val right = left + cellSize
            val bottom = top + cellSize

            val savedCanvas = canvas.save()

            canvas.clipRect(
                left,
                top,
                right,
                bottom
            )

            canvas.translate(
                lossOffsetDp * density,
                0f
            )

            paint.style = Paint.Style.FILL
            paint.color = lossCellColor
            paint.alpha = (lossFlash * 255f).toInt()

            canvas.drawRect(
                left,
                top,
                right,
                bottom,
                paint
            )

            paint.alpha = 255
            canvas.restoreToCount(savedCanvas)
        }

        // Цифры, заметки и отметки исключений.
        for (index in 0 until 81) {
            val row = index / 9
            val col = index % 9
            val value = values[index]

            if (value == 0) {
                val isExcluded =
                    activeHint != null &&
                            index in activeHint.excludedCells

                if (isExcluded) {
                    drawExclusion(
                        canvas = canvas,
                        row = row,
                        col = col,
                        cellSize = cellSize
                    )
                } else {
                    drawNotes(
                        canvas = canvas,
                        row = row,
                        col = col,
                        cellSize = cellSize,
                        mask = notes[index]
                    )
                }

                continue
            }

            val isEvidence =
                activeHint != null &&
                        index in activeHint.evidenceCells

            paint.style = Paint.Style.FILL
            paint.alpha = 255
            paint.textSize = cellSize * 0.58f
            paint.textAlign = Paint.Align.CENTER

            paint.isFakeBoldText =
                givens[index] != 0 || isEvidence

            paint.color = when {
                index in errorCells ->
                    errorColor

                isEvidence ->
                    evidenceTextColor

                givens[index] != 0 ->
                    givenDigitColor

                else ->
                    enteredDigitColor
            }

            val centerX = (col + 0.5f) * cellSize
            val centerY = (row + 0.5f) * cellSize

            val baseline =
                centerY -
                        (paint.ascent() + paint.descent()) / 2f

            if (
                index == lossCellIndex &&
                lossOffsetDp != 0f
            ) {
                val savedCanvas = canvas.save()

                // Ошибочная цифра покачивается только
                // внутри границ своей клетки.
                canvas.clipRect(
                    col * cellSize,
                    row * cellSize,
                    (col + 1) * cellSize,
                    (row + 1) * cellSize
                )

                canvas.translate(
                    lossOffsetDp * density,
                    0f
                )

                canvas.drawText(
                    value.toString(),
                    centerX,
                    baseline,
                    paint
                )

                canvas.restoreToCount(savedCanvas)
            } else {
                canvas.drawText(
                    value.toString(),
                    centerX,
                    baseline,
                    paint
                )
            }
        }

        // Сетка всегда остаётся неподвижной.
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.isFakeBoldText = false
        paint.color = gridColor

        for (line in 0..9) {
            paint.strokeWidth =
                if (line % 3 == 0) {
                    2.2f * density
                } else {
                    0.7f * density
                }

            val position = when (line) {
                0 ->
                    paint.strokeWidth / 2f

                9 ->
                    boardSize - paint.strokeWidth / 2f

                else ->
                    line * cellSize
            }

            canvas.drawLine(
                position,
                0f,
                position,
                boardSize,
                paint
            )

            canvas.drawLine(
                0f,
                position,
                boardSize,
                position,
                paint
            )
        }

        // Рамка целевой клетки подсказки.
        if (activeHint != null) {
            val row = activeHint.targetIndex / 9
            val col = activeHint.targetIndex % 9

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.color = hintBorderColor
            paint.alpha = 255

            val inset = paint.strokeWidth / 2f

            canvas.drawRect(
                col * cellSize + inset,
                row * cellSize + inset,
                (col + 1) * cellSize - inset,
                (row + 1) * cellSize - inset,
                paint
            )

            paint.style = Paint.Style.FILL
        }

        // Затемняющий слой остаётся чёрным в обеих темах.
        if (lossDim > 0f) {
            paint.style = Paint.Style.FILL
            paint.color = Color.BLACK
            paint.alpha = (lossDim * 255f).toInt()

            canvas.drawRect(
                0f,
                0f,
                boardSize,
                boardSize,
                paint
            )
        }

        paint.alpha = 255
        paint.style = Paint.Style.FILL
        paint.isFakeBoldText = false
    }

    private fun drawExclusion(
        canvas: Canvas,
        row: Int,
        col: Int,
        cellSize: Float
    ) {
        val centerX = (col + 0.5f) * cellSize
        val centerY = (row + 0.5f) * cellSize
        val radius = cellSize * 0.12f

        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = exclusionMarkColor
        paint.strokeWidth = 1.8f * density

        canvas.drawLine(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius,
            paint
        )

        canvas.drawLine(
            centerX - radius,
            centerY + radius,
            centerX + radius,
            centerY - radius,
            paint
        )
    }

    private fun drawNotes(
        canvas: Canvas,
        row: Int,
        col: Int,
        cellSize: Float,
        mask: Int
    ) {
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = notesColor
        paint.textSize = cellSize * 0.23f
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = false

        val smallCell = cellSize / 3f

        for (digit in 1..9) {
            val bit = 1 shl (digit - 1)

            if ((mask and bit) == 0) continue

            val smallRow = (digit - 1) / 3
            val smallCol = (digit - 1) % 3

            val centerX =
                col * cellSize +
                        (smallCol + 0.5f) * smallCell

            val centerY =
                row * cellSize +
                        (smallRow + 0.5f) * smallCell

            val baseline =
                centerY -
                        (paint.ascent() + paint.descent()) / 2f

            canvas.drawText(
                digit.toString(),
                centerX,
                baseline,
                paint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                return true

            MotionEvent.ACTION_UP -> {
                val boardSize =
                    min(width, height).toFloat()

                if (
                    boardSize > 0f &&
                    event.x >= 0f &&
                    event.y >= 0f &&
                    event.x < boardSize &&
                    event.y < boardSize
                ) {
                    val cellSize = boardSize / 9f

                    val col =
                        (event.x / cellSize)
                            .toInt()
                            .coerceIn(0, 8)

                    val row =
                        (event.y / cellSize)
                            .toInt()
                            .coerceIn(0, 8)

                    onCellSelected?.invoke(
                        row * 9 + col
                    )

                    performClick()
                }

                return true
            }

            MotionEvent.ACTION_CANCEL ->
                return true
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
