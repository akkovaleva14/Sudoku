package com.example.sudoku.domain

import java.util.UUID

enum class Difficulty(
    val title: String,
    val targetClues: Int
) {
    EASY("Лёгкая", 40),
    MEDIUM("Средняя", 34),
    HARD("Сложная", 28),
    EXPERT("Эксперт", 26),
    MASTER("Мастер", 24),
    EXTREME("Экстрим", 22)
}

enum class MistakeMode(
    val allowedMistakes: Int?
) {
    NO_MISTAKES(0),
    TWO_MISTAKES(2),
    UNLIMITED(null)
}

data class SudokuPuzzle(
    val givens: List<Int>,
    val solution: List<Int>,
    val difficulty: Difficulty
)

data class MoveSnapshot(
    val values: List<Int>,
    val notes: List<Int>,
    val selected: Int
)

data class HelpStatistics(
    val logicalHints: Int = 0,
    val notesHints: Int = 0,
    val automaticFills: Int = 0,
    val usedBeforeTracking: Boolean = false
)

data class GameResult(
    val gameId: String,
    val difficulty: Difficulty,
    val elapsedMillis: Long,
    val completedAtMillis: Long,
    val clues: Int,
    val help: HelpStatistics,
    val mistakeCount: Int = 0,
    val mistakesTrackedFromStart: Boolean = true,
    val mistakeMode: MistakeMode = MistakeMode.UNLIMITED,
    val isVictory: Boolean = true
)

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
