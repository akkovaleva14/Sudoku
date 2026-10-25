package com.example.sudoku.domain.hint

sealed interface HintAnalysis {

    data class Found(
        val hint: LogicalHint
    ) : HintAnalysis

    /**
     * Два числа для заметок.
     * Одно из них совпадает с сохранённым решением.
     * Правильное число отдельно не раскрывается.
     */
    data class Notes(
        val targetIndex: Int,
        val digits: Set<Int>,
        val explanation: String
    ) : HintAnalysis

    data class Unavailable(
        val message: String
    ) : HintAnalysis
}