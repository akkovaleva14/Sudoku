package com.example.sudoku.domain.model

enum class MistakeMode(
    val allowedMistakes: Int?
) {
    NO_MISTAKES(0),
    TWO_MISTAKES(2),
    UNLIMITED(null)
}