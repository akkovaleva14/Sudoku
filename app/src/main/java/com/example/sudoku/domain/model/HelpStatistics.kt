package com.example.sudoku.domain.model

data class HelpStatistics(
    val logicalHints: Int = 0,
    val notesHints: Int = 0,
    val automaticFills: Int = 0,
    val usedBeforeTracking: Boolean = false
)