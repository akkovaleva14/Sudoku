package com.example.sudoku.domain

object SudokuRules {

    fun arePeers(first: Int, second: Int): Boolean {
        val row1 = first / 9
        val col1 = first % 9
        val row2 = second / 9
        val col2 = second % 9

        return row1 == row2 ||
                col1 == col2 ||
                (
                        row1 / 3 == row2 / 3 &&
                                col1 / 3 == col2 / 3
                        )
    }

    fun candidates(
        board: IntArray,
        index: Int
    ): List<Int> {
        if (board[index] != 0) return emptyList()

        val used = BooleanArray(10)

        for (other in 0 until 81) {
            if (
                other != index &&
                arePeers(index, other)
            ) {
                used[board[other]] = true
            }
        }

        return (1..9).filter { !used[it] }
    }

    /**
     * Все клетки, участвующие в повторах.
     */
    fun conflicts(values: List<Int>): Set<Int> {
        require(values.size == 81)

        val result = mutableSetOf<Int>()

        for (first in 0 until 81) {
            if (values[first] == 0) continue

            for (second in first + 1 until 81) {
                if (
                    values[first] == values[second] &&
                    arePeers(first, second)
                ) {
                    result.add(first)
                    result.add(second)
                }
            }
        }

        return result
    }

    /**
     * Заполненные клетки, не совпадающие с решением.
     * Пустые клетки ошибками не считаются.
     */
    fun incorrectCells(
        values: List<Int>,
        solution: List<Int>
    ): Set<Int> {
        require(values.size == 81)
        require(solution.size == 81)

        return values.indices.filterTo(
            mutableSetOf<Int>()
        ) { index ->
            values[index] != 0 &&
                    values[index] != solution[index]
        }
    }

    fun isValidSolution(values: List<Int>): Boolean {
        return values.size == 81 &&
                values.all { it in 1..9 } &&
                conflicts(values).isEmpty()
    }

    fun isValidGame(game: SudokuGame): Boolean {
        val puzzle = game.puzzle

        if (!isValidSolution(puzzle.solution)) {
            return false
        }

        if (puzzle.givens.size != 81) return false
        if (puzzle.givens.any { it !in 0..9 }) return false

        if (game.selected !in -1..80) return false
        if (game.elapsedMillis < 0L) return false
        if (game.history.size > 100) return false

        if (game.mistakeCount < 0) return false

        // В ограниченном режиме ошибки должны учитываться
        // с самого начала попытки.
        if (
            game.mistakeMode != MistakeMode.UNLIMITED &&
            !game.mistakesTrackedFromStart
        ) {
            return false
        }

        for (index in 0 until 81) {
            val given = puzzle.givens[index]

            if (
                given != 0 &&
                given != puzzle.solution[index]
            ) {
                return false
            }
        }

        fun validBoard(
            values: List<Int>,
            notes: List<Int>
        ): Boolean {
            if (
                values.size != 81 ||
                notes.size != 81
            ) {
                return false
            }

            if (values.any { it !in 0..9 }) {
                return false
            }

            if (notes.any { it !in 0..511 }) {
                return false
            }

            return (0 until 81).all { index ->
                val given = puzzle.givens[index]

                (given == 0 || values[index] == given) &&
                        (values[index] == 0 || notes[index] == 0)
            }
        }

        // Неправильные введённые цифры разрешены:
        // они являются частью незавершённой партии.
        if (!validBoard(game.values, game.notes)) {
            return false
        }

        return game.history.all { snapshot ->
            snapshot.selected in -1..80 &&
                    validBoard(
                        values = snapshot.values,
                        notes = snapshot.notes
                    )
        }
    }
}
