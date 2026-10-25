package com.example.sudoku.domain.model

data class MoveSnapshot(
    val values: List<Int>,
    val notes: List<Int>,
    val selected: Int
)