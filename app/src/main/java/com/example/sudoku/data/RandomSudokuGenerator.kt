package com.example.sudoku.data

import com.example.sudoku.domain.generation.SudokuDifficultyEvaluator
import com.example.sudoku.domain.generation.SudokuGenerator
import com.example.sudoku.domain.model.Difficulty
import com.example.sudoku.domain.model.SudokuPuzzle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

class RandomSudokuGenerator(
    private val random: Random = Random.Default,
    private val maxAttempts: Int = 120,
    private val maxGenerationMillis: Long = 12_000L
) : SudokuGenerator {

    private val evaluator = SudokuDifficultyEvaluator()

    private class GenerationTimeLimitReached :
        RuntimeException()

    init {
        require(maxAttempts > 0)
        require(maxGenerationMillis > 0L)
    }

    override suspend fun generate(
        difficulty: Difficulty
    ): SudokuPuzzle = withContext(Dispatchers.Default) {
        val context = coroutineContext
        val startedAt = System.nanoTime()

        val checkpoint: () -> Unit = {
            checkGeneration(
                context = context,
                startedAt = startedAt
            )
        }

        val evaluationStartsAt = when (difficulty) {
            Difficulty.EASY,
            Difficulty.MEDIUM,
            Difficulty.HARD -> difficulty.targetClues

            Difficulty.EXPERT,
            Difficulty.MASTER,
            Difficulty.EXTREME -> 28
        }

        val minimumClues = when (difficulty) {
            Difficulty.EASY -> 37
            Difficulty.MEDIUM -> 31
            Difficulty.HARD -> 25

            Difficulty.EXPERT,
            Difficulty.MASTER,
            Difficulty.EXTREME -> 20
        }

        try {
            for (attempt in 0 until maxAttempts) {
                checkpoint()

                val solution = IntArray(81)

                check(
                    FastSolver(
                        board = solution,
                        checkpoint = checkpoint,
                        random = random
                    ).fill()
                ) {
                    "Не удалось построить решение"
                }

                val givens = solution.copyOf()
                var clueCount = 81

                val removalOrder =
                    (0 until 81).shuffled(random)

                for (index in removalOrder) {
                    checkpoint()

                    if (clueCount <= minimumClues) {
                        break
                    }

                    val previous = givens[index]
                    givens[index] = 0

                    val solutionCount = FastSolver(
                        board = givens.copyOf(),
                        checkpoint = checkpoint,
                        random = random
                    ).countSolutions(limit = 2)

                    if (solutionCount != 1) {
                        givens[index] = previous
                        continue
                    }

                    clueCount--

                    if (clueCount > evaluationStartsAt) {
                        continue
                    }

                    val evaluation = evaluator.evaluate(
                        values = givens.toList(),
                        maxSearchNodes = 20_000,
                        checkpoint = checkpoint
                    ) ?: continue

                    if (evaluation.difficulty != difficulty) {
                        continue
                    }

                    checkpoint()

                    return@withContext SudokuPuzzle(
                        givens = givens.toList(),
                        solution = solution.toList(),
                        difficulty = difficulty
                    )
                }
            }
        } catch (_: GenerationTimeLimitReached) {
            throw IllegalStateException(
                "За ${maxGenerationMillis / 1000} секунд " +
                        "не удалось подобрать уровень " +
                        "«${difficulty.title}». " +
                        "Предыдущая партия сохранена. " +
                        "Нажмите «Новая игра» для новой попытки."
            )
        }

        throw IllegalStateException(
            "За $maxAttempts попыток не удалось подобрать " +
                    "уровень «${difficulty.title}». " +
                    "Предыдущая партия сохранена. " +
                    "Нажмите «Новая игра» для новой попытки."
        )
    }

    private fun checkGeneration(
        context: CoroutineContext,
        startedAt: Long
    ) {
        context.ensureActive()

        val elapsedMillis =
            (System.nanoTime() - startedAt) / 1_000_000L

        if (elapsedMillis >= maxGenerationMillis) {
            throw GenerationTimeLimitReached()
        }
    }

    /**
     * Решатель с битовыми масками строк,
     * столбцов и блоков.
     */
    private class FastSolver(
        private val board: IntArray,
        private val checkpoint: () -> Unit,
        private val random: Random
    ) {

        private val rowMasks = IntArray(9)
        private val columnMasks = IntArray(9)
        private val blockMasks = IntArray(9)

        init {
            require(board.size == 81)
            require(board.all { it in 0..9 })

            for (index in board.indices) {
                val digit = board[index]
                if (digit == 0) continue

                val row = index / 9
                val column = index % 9
                val block = blockIndex(index)
                val bit = 1 shl (digit - 1)

                require(
                    (rowMasks[row] and bit) == 0 &&
                            (columnMasks[column] and bit) == 0 &&
                            (blockMasks[block] and bit) == 0
                ) {
                    "Поле содержит повторяющиеся числа"
                }

                rowMasks[row] = rowMasks[row] or bit
                columnMasks[column] =
                    columnMasks[column] or bit
                blockMasks[block] =
                    blockMasks[block] or bit
            }
        }

        private data class CellChoice(
            val index: Int,
            val mask: Int
        )

        private fun blockIndex(index: Int): Int {
            val row = index / 9
            val column = index % 9

            return (row / 3) * 3 + column / 3
        }

        private fun candidateMask(index: Int): Int {
            val used =
                rowMasks[index / 9] or
                        columnMasks[index % 9] or
                        blockMasks[blockIndex(index)]

            return ALL_DIGITS and used.inv()
        }

        private fun chooseCell(): CellChoice? {
            var bestIndex = -1
            var bestMask = 0
            var bestCount = 10

            for (index in board.indices) {
                if (board[index] != 0) continue

                val mask = candidateMask(index)
                val count = Integer.bitCount(mask)

                if (count == 0) {
                    return CellChoice(index, 0)
                }

                if (count < bestCount) {
                    bestIndex = index
                    bestMask = mask
                    bestCount = count

                    if (count == 1) {
                        break
                    }
                }
            }

            return if (bestIndex == -1) {
                null
            } else {
                CellChoice(bestIndex, bestMask)
            }
        }

        private fun place(index: Int, digit: Int) {
            val bit = 1 shl (digit - 1)

            board[index] = digit

            rowMasks[index / 9] =
                rowMasks[index / 9] or bit
            columnMasks[index % 9] =
                columnMasks[index % 9] or bit

            val block = blockIndex(index)
            blockMasks[block] =
                blockMasks[block] or bit
        }

        private fun remove(index: Int, digit: Int) {
            val inverseBit =
                (1 shl (digit - 1)).inv()

            board[index] = 0

            rowMasks[index / 9] =
                rowMasks[index / 9] and inverseBit
            columnMasks[index % 9] =
                columnMasks[index % 9] and inverseBit

            val block = blockIndex(index)
            blockMasks[block] =
                blockMasks[block] and inverseBit
        }

        fun fill(): Boolean {
            checkpoint()

            val cell = chooseCell() ?: return true
            if (cell.mask == 0) return false

            val digits = (1..9)
                .filter {
                    (cell.mask and (1 shl (it - 1))) != 0
                }
                .shuffled(random)

            for (digit in digits) {
                place(cell.index, digit)

                if (fill()) {
                    return true
                }

                remove(cell.index, digit)
            }

            return false
        }

        fun countSolutions(limit: Int): Int {
            checkpoint()

            if (limit <= 0) return 0

            val cell = chooseCell() ?: return 1
            if (cell.mask == 0) return 0

            var total = 0
            var remainingMask = cell.mask

            while (remainingMask != 0) {
                checkpoint()

                val bit =
                    remainingMask and -remainingMask

                remainingMask =
                    remainingMask xor bit

                val digit =
                    Integer.numberOfTrailingZeros(bit) + 1

                place(cell.index, digit)

                total += countSolutions(
                    limit = limit - total
                )

                remove(cell.index, digit)

                if (total >= limit) {
                    break
                }
            }

            return total
        }

        private companion object {
            const val ALL_DIGITS = 0b111111111
        }
    }
}
