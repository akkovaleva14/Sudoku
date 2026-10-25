package com.example.sudoku.domain.model

data class SudokuPuzzle(
    val givens: List<Int>,
    val solution: List<Int>,
    val difficulty: Difficulty
)