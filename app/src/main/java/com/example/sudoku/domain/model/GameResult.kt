package com.example.sudoku.domain.model

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