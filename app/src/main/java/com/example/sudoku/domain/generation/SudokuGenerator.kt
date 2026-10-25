package com.example.sudoku.domain.generation

import com.example.sudoku.domain.model.Difficulty
import com.example.sudoku.domain.model.SudokuPuzzle

interface SudokuGenerator {
    suspend fun generate(
        difficulty: Difficulty
    ): SudokuPuzzle
}