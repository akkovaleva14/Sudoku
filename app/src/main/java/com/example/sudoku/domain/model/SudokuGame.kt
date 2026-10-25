package com.example.sudoku.domain.model

import java.util.UUID

data class SudokuGame(
    val puzzle: SudokuPuzzle,
    val values: List<Int> = puzzle.givens.toList(),
    val notes: List<Int> = List(81) { 0 },
    val selected: Int = -1,
    val notesMode: Boolean = false,
    val elapsedMillis: Long = 0L,
    val history: List<MoveSnapshot> = emptyList(),
    val gameId: String = UUID.randomUUID().toString(),
    val help: HelpStatistics = HelpStatistics(),

    // Первый результат попытки остаётся неизменным,
    // даже если он удалён из истории.
    val result: GameResult? = null,

    val mistakeCount: Int = 0,
    val mistakesTrackedFromStart: Boolean = true,

    // Режим неизменен на протяжении одной попытки.
    val mistakeMode: MistakeMode = MistakeMode.UNLIMITED
) {
    val isLost: Boolean
        get() {
            val limit = mistakeMode.allowedMistakes
                ?: return false

            return mistakeCount > limit
        }

    val isWon: Boolean
        get() = !isLost && values == puzzle.solution

    val isFinished: Boolean
        get() = isLost || isWon
}
