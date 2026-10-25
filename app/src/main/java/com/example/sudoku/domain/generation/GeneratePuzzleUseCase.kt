package com.example.sudoku.domain.generation

import com.example.sudoku.domain.model.Difficulty
import com.example.sudoku.domain.model.SudokuPuzzle

class GeneratePuzzleUseCase(
    private val generator: SudokuGenerator
) {
    suspend operator fun invoke(
        difficulty: Difficulty
    ): SudokuPuzzle {
        return generator.generate(difficulty)
    }
}