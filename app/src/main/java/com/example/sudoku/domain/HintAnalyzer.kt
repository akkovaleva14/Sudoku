package com.example.sudoku.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.random.Random

// Сохранено для совместимости с SudokuBoardView.
// Новый анализатор не создаёт пошаговых доказательств.
data class ProofStep(
    val explanation: String,
    val values: List<Int>,
    val highlightedCells: Set<Int> = emptySet(),
    val evidenceCells: Set<Int> = emptySet(),
    val assumedCells: Set<Int> = emptySet(),
    val derivedCells: Set<Int> = emptySet(),
    val contradictionCells: Set<Int> = emptySet()
)

data class LogicalHint(
    val targetIndex: Int,
    val digit: Int,
    val explanation: String,
    val highlightedCells: Set<Int>,
    val evidenceCells: Set<Int> = emptySet(),
    val excludedCells: Set<Int> = emptySet(),
    val proofSteps: List<ProofStep> = emptyList()
)

sealed interface HintAnalysis {

    data class Found(
        val hint: LogicalHint
    ) : HintAnalysis

    /**
     * Два числа для заметок.
     * Одно из них совпадает с сохранённым решением.
     * Правильное число отдельно не раскрывается.
     */
    data class Notes(
        val targetIndex: Int,
        val digits: Set<Int>,
        val explanation: String
    ) : HintAnalysis

    data class Unavailable(
        val message: String
    ) : HintAnalysis
}

class HintAnalyzer(
    private val random: Random = Random.Default
) {

    private data class UnitInfo(
        val cells: List<Int>,
        val description: String
    )

    private data class SimpleMove(
        val target: Int,
        val digit: Int,
        val unit: UnitInfo,
        val excluded: Set<Int>
    )

    private val units: List<UnitInfo> = buildList {
        for (row in 0 until 9) {
            add(
                UnitInfo(
                    cells = (0 until 9).map { col ->
                        row * 9 + col
                    },
                    description = "строке"
                )
            )
        }

        for (col in 0 until 9) {
            add(
                UnitInfo(
                    cells = (0 until 9).map { row ->
                        row * 9 + col
                    },
                    description = "столбце"
                )
            )
        }

        for (blockRow in 0 until 3) {
            for (blockCol in 0 until 3) {
                add(
                    UnitInfo(
                        cells = buildList {
                            for (row in 0 until 3) {
                                for (col in 0 until 3) {
                                    add(
                                        (blockRow * 3 + row) * 9 +
                                                blockCol * 3 + col
                                    )
                                }
                            }
                        },
                        description = "блоке 3×3"
                    )
                )
            }
        }
    }

    suspend fun analyze(
        game: SudokuGame
    ): HintAnalysis = withContext(Dispatchers.Default) {
        val context = coroutineContext

        val checkpoint: () -> Unit = {
            context.ensureActive()
        }

        checkpoint()

        if (game.isFinished) {
            return@withContext HintAnalysis.Unavailable(
                "Судоку уже решено."
            )
        }

        val incorrect = SudokuRules.incorrectCells(
            values = game.values,
            solution = game.puzzle.solution
        )

        if (incorrect.isNotEmpty()) {
            return@withContext HintAnalysis.Unavailable(
                "Сначала исправьте неправильные цифры, " +
                        "отмеченные красным."
            )
        }

        if (SudokuRules.conflicts(game.values).isNotEmpty()) {
            return@withContext HintAnalysis.Unavailable(
                "Сначала исправьте повторяющиеся числа."
            )
        }

        val board = game.values.toIntArray()

        val emptyCells = (0 until 81).filter {
            board[it] == 0 &&
                    game.puzzle.givens[it] == 0
        }

        if (emptyCells.isEmpty()) {
            return@withContext HintAnalysis.Unavailable(
                "На поле нет пустых клеток."
            )
        }

        val candidates = Array(81) { index ->
            if (board[index] == 0) {
                SudokuRules.candidates(board, index)
            } else {
                emptyList<Int>()
            }
        }

        if (emptyCells.any { candidates[it].isEmpty() }) {
            return@withContext HintAnalysis.Unavailable(
                "Поле противоречиво: в одной из пустых " +
                        "клеток нет допустимых цифр."
            )
        }

        for (unit in units) {
            checkpoint()

            for (digit in 1..9) {
                if (unit.cells.any { board[it] == digit }) {
                    continue
                }

                val hasPlace = unit.cells.any {
                    board[it] == 0 &&
                            digit in candidates[it]
                }

                if (!hasPlace) {
                    return@withContext HintAnalysis.Unavailable(
                        "Поле противоречиво: для цифры $digit " +
                                "не осталось места в " +
                                "${unit.description}."
                    )
                }
            }
        }

        val simpleHint = findSimpleHint(
            board = board,
            candidates = candidates,
            checkpoint = checkpoint
        )

        if (simpleHint != null) {
            return@withContext HintAnalysis.Found(simpleHint)
        }

        checkpoint()

        val suitableCells = emptyCells.filter { index ->
            val correct = game.puzzle.solution[index]

            correct in candidates[index] &&
                    candidates[index].any { it != correct }
        }

        if (suitableCells.isEmpty()) {
            return@withContext HintAnalysis.Unavailable(
                "Не удалось подобрать клетку " +
                        "для подсказки с двумя числами."
            )
        }

        val target = suitableCells.random(random)
        val correct = game.puzzle.solution[target]

        val alternative = candidates[target]
            .filter { it != correct }
            .random(random)

        // Порядок чисел не выдаёт правильный ответ.
        val digits = listOf(correct, alternative)
            .sorted()
            .toSet()

        HintAnalysis.Notes(
            targetIndex = target,
            digits = digits,
            explanation =
                "Простого логического хода сейчас нет.\n\n" +
                        "В заметки выделенной клетки записаны " +
                        "два числа: ${digits.joinToString(", ")}.\n\n" +
                        "Одно из них точно правильное. " +
                        "Это помощь по сохранённому решению, " +
                        "а не логическое доказательство."
        )
    }

    private fun findSimpleHint(
        board: IntArray,
        candidates: Array<List<Int>>,
        checkpoint: () -> Unit
    ): LogicalHint? {
        val moves = mutableListOf<SimpleMove>()

        // Скрытые одиночки.
        for (unit in units) {
            checkpoint()

            for (digit in 1..9) {
                if (unit.cells.any { board[it] == digit }) {
                    continue
                }

                val possible = unit.cells.filter {
                    board[it] == 0 &&
                            digit in candidates[it]
                }

                if (possible.size == 1) {
                    val target = possible.single()

                    moves.add(
                        SimpleMove(
                            target = target,
                            digit = digit,
                            unit = unit,
                            excluded = unit.cells.filterTo(
                                mutableSetOf<Int>()
                            ) {
                                it != target &&
                                        board[it] == 0
                            }
                        )
                    )
                }
            }
        }

        if (moves.isNotEmpty()) {
            val withEvidence = moves.filter {
                it.excluded.isNotEmpty()
            }

            val available =
                if (withEvidence.isNotEmpty()) {
                    withEvidence
                } else {
                    moves
                }

            val chosen = available
                .distinctBy { it.target to it.digit }
                .random(random)

            val move = available
                .filter {
                    it.target == chosen.target &&
                            it.digit == chosen.digit
                }
                .random(random)

            val evidence = blockersFor(
                board = board,
                cells = move.excluded,
                digit = move.digit
            )

            val explanation = buildString {
                append(
                    "Единственное место для цифры " +
                            "${move.digit}.\n\n"
                )

                append(
                    "В выделенной розовым " +
                            "${move.unit.description} " +
                            "этой цифры пока нет.\n\n"
                )

                if (move.excluded.isNotEmpty()) {
                    append(
                        "Голубые числа ${move.digit} " +
                                "запрещают поставить её " +
                                "в клетки с крестиками: " +
                                "они находятся с этими числами " +
                                "в одной строке, столбце или блоке.\n\n"
                    )
                }

                append(
                    "Другие клетки области уже заполнены. " +
                            "Остаётся только жёлтая клетка.\n\n"
                )

                append("Нажмите «Поставить число».")
            }

            return LogicalHint(
                targetIndex = move.target,
                digit = move.digit,
                explanation = explanation,
                highlightedCells = move.unit.cells.toSet(),
                evidenceCells = evidence,
                excludedCells = move.excluded
            )
        }

        // Явные одиночки.
        val nakedSingles = (0 until 81).filter {
            board[it] == 0 &&
                    candidates[it].size == 1
        }

        if (nakedSingles.isEmpty()) {
            return null
        }

        val target = nakedSingles.random(random)
        val digit = candidates[target].single()
        val evidence = filledPeers(board, target)

        val excludedDigits = evidence
            .map { board[it] }
            .distinct()
            .sorted()

        return LogicalHint(
            targetIndex = target,
            digit = digit,
            explanation =
                "Единственный кандидат.\n\n" +
                        "Голубые числа в строке, столбце " +
                        "и блоке жёлтой клетки запрещают цифры " +
                        "${excludedDigits.joinToString(", ")}.\n\n" +
                        "Остаётся только $digit.\n\n" +
                        "Нажмите «Поставить число».",
            highlightedCells =
                (0 until 81).filterTo(mutableSetOf<Int>()) {
                    it == target ||
                            SudokuRules.arePeers(target, it)
                },
            evidenceCells = evidence
        )
    }

    private fun filledPeers(
        board: IntArray,
        target: Int
    ): Set<Int> {
        return (0 until 81).filterTo(
            mutableSetOf<Int>()
        ) {
            it != target &&
                    board[it] != 0 &&
                    SudokuRules.arePeers(target, it)
        }
    }

    private fun blockersFor(
        board: IntArray,
        cells: Collection<Int>,
        digit: Int
    ): Set<Int> {
        return (0 until 81).filterTo(
            mutableSetOf<Int>()
        ) { other ->
            board[other] == digit &&
                    cells.any {
                        SudokuRules.arePeers(it, other)
                    }
        }
    }
}
