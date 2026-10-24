package com.example.sudoku.domain

interface SudokuGenerator {
    suspend fun generate(
        difficulty: Difficulty
    ): SudokuPuzzle
}

interface GameRepository {
    suspend fun load(): SudokuGame?

    suspend fun save(game: SudokuGame)
}

class GeneratePuzzleUseCase(
    private val generator: SudokuGenerator
) {
    suspend operator fun invoke(
        difficulty: Difficulty
    ): SudokuPuzzle {
        return generator.generate(difficulty)
    }
}
