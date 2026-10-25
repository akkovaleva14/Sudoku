package com.example.sudoku.domain.hint

data class LogicalHint(
    val targetIndex: Int,
    val digit: Int,
    val explanation: String,
    val highlightedCells: Set<Int>,
    val evidenceCells: Set<Int> = emptySet(),
    val excludedCells: Set<Int> = emptySet()
)