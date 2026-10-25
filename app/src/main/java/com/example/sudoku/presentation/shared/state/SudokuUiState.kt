package com.example.sudoku.presentation.shared.state

import com.example.sudoku.domain.hint.LogicalHint
import com.example.sudoku.domain.model.SudokuGame

data class SudokuUiState(
    val game: SudokuGame? = null,
    val isBusy: Boolean = true,
    val error: String? = null,
    val hint: LogicalHint? = null,
    val hintMessage: String? = null,
    val isHintBusy: Boolean = false,
    val isPaused: Boolean = false,
    val openGameRequested: Boolean = false
)