package com.example.sudoku.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.sudoku.domain.LogicalHint
import com.example.sudoku.domain.ProofStep
import com.example.sudoku.domain.SudokuRules
import kotlin.math.min

class SudokuBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    private var givens: List<Int> = List(81) { 0 }
    private var values: List<Int> = List(81) { 0 }
    private var notes: List<Int> = List(81) { 0 }
    private var solution: List<Int> = List(81) { 0 }

    private var selected = -1
    private var errorCells: Set<Int> = emptySet()

    private var hint: LogicalHint? = null
    private var proofStep: ProofStep? = null

    var onCellSelected: ((Int) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
    }

    fun setState(
        givens: List<Int>,
        values: List<Int>,
        notes: List<Int>,
        solution: List<Int>,
        selected: Int,
        hint: LogicalHint? = null,
        proofStep: ProofStep? = null
    ) {
        require(givens.size == 81)
        require(values.size == 81)
        require(notes.size == 81)
        require(solution.size == 81)
        require(selected in -1..80)

        require(
            proofStep == null ||
                    (
                            proofStep.values.size == 81 &&
                                    proofStep.values.all { it in 0..9 }
                            )
        )

        require(
            hint == null ||
                    (
                            hint.targetIndex in 0..80 &&
                                    hint.digit in 1..9
                            )
        )

        if (
            this.givens == givens &&
            this.values == values &&
            this.notes == notes &&
            this.solution == solution &&
            this.selected == selected &&
            this.hint == hint &&
            this.proofStep == proofStep
        ) {
            return
        }

        this.givens = givens.toList()
        this.values = values.toList()
        this.notes = notes.toList()
        this.solution = solution.toList()
        this.selected = selected
        this.hint = hint
        this.proofStep = proofStep

        errorCells =
            SudokuRules.incorrectCells(
                values = this.values,
                solution = this.solution
            ) + SudokuRules.conflicts(this.values)

        invalidate()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int
    ) {
        val desiredSize = (360 * density).toInt()

        // Соблюдаем ограничения родительского контейнера.
        // Рисование использует меньшую сторону.
        val measuredWidth =
            resolveSize(desiredSize, widthMeasureSpec)

        val measuredHeight =
            resolveSize(measuredWidth, heightMeasureSpec)

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
        val activeStep = proofStep
        val displayedValues = activeStep?.values ?: values

        val selectedValue =
            if (selected in 0..80) {
                displayedValues[selected]
            } else {
                0
            }

        paint.style = Paint.Style.FILL

        for (index in 0 until 81) {
            val row = index / 9
            val col = index % 9

            paint.color = when {
                activeStep != null -> {
                    when {
                        index in activeStep.contradictionCells ->
                            Color.rgb(255, 190, 190)

                        index == activeHint?.targetIndex ->
                            Color.rgb(255, 224, 130)

                        index in activeStep.assumedCells ->
                            Color.rgb(255, 211, 157)

                        index in activeStep.evidenceCells ->
                            Color.rgb(173, 216, 255)

                        index in activeStep.highlightedCells ->
                            Color.rgb(255, 235, 242)

                        index in activeStep.derivedCells ->
                            Color.rgb(232, 220, 255)

                        else ->
                            Color.WHITE
                    }
                }

                activeHint != null -> {
                    when {
                        index == activeHint.targetIndex ->
                            Color.rgb(255, 224, 130)

                        index in activeHint.evidenceCells ->
                            Color.rgb(173, 216, 255)

                        index in activeHint.excludedCells ->
                            Color.rgb(255, 214, 228)

                        index in activeHint.highlightedCells ->
                            Color.rgb(255, 235, 242)

                        else ->
                            Color.WHITE
                    }
                }

                else -> {
                    when {
                        index == selected ->
                            Color.rgb(255, 182, 205)

                        selectedValue != 0 &&
                                displayedValues[index] == selectedValue ->
                            Color.rgb(255, 214, 228)

                        selected in 0..80 &&
                                SudokuRules.arePeers(index, selected) ->
                            Color.rgb(255, 235, 242)

                        else ->
                            Color.WHITE
                    }
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

        for (index in 0 until 81) {
            val row = index / 9
            val col = index % 9
            val value = displayedValues[index]

            if (value == 0) {
                val isExcluded =
                    activeStep == null &&
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
                        mask =
                            if (activeStep == null) {
                                notes[index]
                            } else {
                                0
                            }
                    )
                }

                continue
            }

            val isEvidence =
                if (activeStep != null) {
                    index in activeStep.evidenceCells
                } else {
                    activeHint != null &&
                            index in activeHint.evidenceCells
                }

            val isAssumed =
                activeStep != null &&
                        index in activeStep.assumedCells

            val isDerived =
                activeStep != null &&
                        index in activeStep.derivedCells

            paint.style = Paint.Style.FILL
            paint.textSize = cellSize * 0.58f
            paint.textAlign = Paint.Align.CENTER

            paint.isFakeBoldText =
                givens[index] != 0 ||
                        isEvidence ||
                        isAssumed ||
                        isDerived

            paint.color = when {
                activeStep == null && index in errorCells ->
                    Color.rgb(205, 48, 63)

                isAssumed ->
                    Color.rgb(166, 82, 0)

                isDerived ->
                    Color.rgb(112, 53, 170)

                isEvidence ->
                    Color.rgb(15, 70, 125)

                givens[index] != 0 ->
                    Color.rgb(30, 41, 59)

                else ->
                    Color.rgb(35, 98, 190)
            }

            val centerX = (col + 0.5f) * cellSize
            val centerY = (row + 0.5f) * cellSize

            val baseline =
                centerY - (paint.ascent() + paint.descent()) / 2f

            canvas.drawText(
                value.toString(),
                centerX,
                baseline,
                paint
            )
        }

        paint.style = Paint.Style.FILL
        paint.isFakeBoldText = false
        paint.color = Color.rgb(62, 75, 96)

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

        if (activeHint != null) {
            val row = activeHint.targetIndex / 9
            val col = activeHint.targetIndex % 9

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.color = Color.rgb(218, 139, 0)

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

        paint.color = Color.rgb(180, 105, 130)
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
        paint.color = Color.rgb(104, 117, 138)
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
                col * cellSize + (smallCol + 0.5f) * smallCell

            val centerY =
                row * cellSize + (smallRow + 0.5f) * smallCell

            val baseline =
                centerY - (paint.ascent() + paint.descent()) / 2f

            canvas.drawText(
                digit.toString(),
                centerX,
                baseline,
                paint
            )
        }
    }

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                return true

            MotionEvent.ACTION_UP -> {
                val boardSize = min(width, height).toFloat()

                if (
                    boardSize > 0f &&
                    event.x >= 0f &&
                    event.y >= 0f &&
                    event.x < boardSize &&
                    event.y < boardSize
                ) {
                    val cellSize = boardSize / 9f

                    val col =
                        (event.x / cellSize).toInt().coerceIn(0, 8)

                    val row =
                        (event.y / cellSize).toInt().coerceIn(0, 8)

                    onCellSelected?.invoke(row * 9 + col)
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
