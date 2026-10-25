package com.example.sudoku.domain.generation

import com.example.sudoku.domain.model.Difficulty
import com.example.sudoku.domain.game.SudokuRules

class SudokuDifficultyEvaluator {

    data class Evaluation(
        val difficulty: Difficulty,
        val branchingNodes: Int,
        val usedHiddenSingles: Boolean
    )

    private class SearchStats {
        var branchingNodes = 0
        var usedHiddenSingles = false
        var visitedNodes = 0
    }

    private class SearchLimitReached : RuntimeException()

    private val units: List<List<Int>> = buildList {
        for (row in 0 until 9) {
            add(
                (0 until 9).map { col ->
                    row * 9 + col
                }
            )
        }

        for (col in 0 until 9) {
            add(
                (0 until 9).map { row ->
                    row * 9 + col
                }
            )
        }

        for (blockRow in 0 until 3) {
            for (blockCol in 0 until 3) {
                add(
                    buildList {
                        for (row in 0 until 3) {
                            for (col in 0 until 3) {
                                add(
                                    (blockRow * 3 + row) * 9 +
                                            blockCol * 3 + col
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    /**
     * null означает:
     * - некорректное поле;
     * - решение не найдено;
     * - превышен лимит поиска.
     *
     * Проверку единственности выполняет генератор.
     *
     * checkpoint используется для отмены и ограничения времени.
     */
    fun evaluate(
        values: List<Int>,
        maxSearchNodes: Int = 20_000,
        checkpoint: () -> Unit = {}
    ): Evaluation? {
        require(maxSearchNodes > 0)

        if (
            values.size != 81 ||
            values.any { it !in 0..9 }
        ) {
            return null
        }

        if (SudokuRules.conflicts(values).isNotEmpty()) {
            return null
        }

        val stats = SearchStats()

        val solved = try {
            solve(
                board = values.toIntArray(),
                stats = stats,
                maxSearchNodes = maxSearchNodes,
                checkpoint = checkpoint
            )
        } catch (_: SearchLimitReached) {
            return null
        }

        if (!solved) return null

        val difficulty = when {
            stats.branchingNodes >= 20 ->
                Difficulty.EXTREME

            stats.branchingNodes >= 8 ->
                Difficulty.MASTER

            stats.branchingNodes >= 3 ->
                Difficulty.EXPERT

            stats.branchingNodes >= 1 ->
                Difficulty.HARD

            stats.usedHiddenSingles ->
                Difficulty.MEDIUM

            else ->
                Difficulty.EASY
        }

        return Evaluation(
            difficulty = difficulty,
            branchingNodes = stats.branchingNodes,
            usedHiddenSingles = stats.usedHiddenSingles
        )
    }

    private fun solve(
        board: IntArray,
        stats: SearchStats,
        maxSearchNodes: Int,
        checkpoint: () -> Unit
    ): Boolean {
        checkpoint()

        stats.visitedNodes++

        if (stats.visitedNodes > maxSearchNodes) {
            throw SearchLimitReached()
        }

        if (!applySingles(board, stats, checkpoint)) {
            return false
        }

        var selectedIndex = -1
        var selectedCandidates: List<Int> = emptyList()

        for (index in 0 until 81) {
            if (board[index] != 0) continue

            val candidates = SudokuRules.candidates(
                board = board,
                index = index
            )

            if (candidates.isEmpty()) return false

            if (
                selectedIndex == -1 ||
                candidates.size < selectedCandidates.size
            ) {
                selectedIndex = index
                selectedCandidates = candidates
            }
        }

        if (selectedIndex == -1) {
            return SudokuRules.isValidSolution(
                board.toList()
            )
        }

        // Считаем узлы разветвления, включая те,
        // которые впоследствии привели к неудачной ветке.
        stats.branchingNodes++

        for (digit in selectedCandidates) {
            checkpoint()

            val nextBoard = board.copyOf()
            nextBoard[selectedIndex] = digit

            if (
                solve(
                    board = nextBoard,
                    stats = stats,
                    maxSearchNodes = maxSearchNodes,
                    checkpoint = checkpoint
                )
            ) {
                return true
            }
        }

        return false
    }

    /**
     * После каждого поставленного числа пересчитываем
     * кандидатов, чтобы не использовать устаревшие данные.
     */
    private fun applySingles(
        board: IntArray,
        stats: SearchStats,
        checkpoint: () -> Unit
    ): Boolean {
        while (true) {
            checkpoint()

            val candidates = Array(81) { index ->
                if (board[index] == 0) {
                    SudokuRules.candidates(board, index)
                } else {
                    emptyList<Int>()
                }
            }

            for (index in 0 until 81) {
                if (
                    board[index] == 0 &&
                    candidates[index].isEmpty()
                ) {
                    return false
                }
            }

            // Единственный кандидат в клетке.
            val nakedSingle = (0 until 81).firstOrNull { index ->
                board[index] == 0 &&
                        candidates[index].size == 1
            }

            if (nakedSingle != null) {
                board[nakedSingle] =
                    candidates[nakedSingle].single()

                continue
            }

            // Единственное место для цифры в области.
            var hiddenIndex = -1
            var hiddenDigit = 0

            unitLoop@ for (unit in units) {
                for (digit in 1..9) {
                    if (unit.any { board[it] == digit }) {
                        continue
                    }

                    val possibleCells = unit.filter { index ->
                        board[index] == 0 &&
                                digit in candidates[index]
                    }

                    // Цифре нет места в области:
                    // текущая ветка противоречива.
                    if (possibleCells.isEmpty()) {
                        return false
                    }

                    if (possibleCells.size == 1) {
                        hiddenIndex = possibleCells.single()
                        hiddenDigit = digit
                        break@unitLoop
                    }
                }
            }

            if (hiddenIndex != -1) {
                board[hiddenIndex] = hiddenDigit
                stats.usedHiddenSingles = true
                continue
            }

            return true
        }
    }
}