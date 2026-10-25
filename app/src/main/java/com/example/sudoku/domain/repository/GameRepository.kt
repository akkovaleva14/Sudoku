package com.example.sudoku.domain.repository

import com.example.sudoku.domain.model.SudokuGame

interface GameRepository {
    suspend fun load(): SudokuGame?

    suspend fun save(game: SudokuGame)

    suspend fun clearCurrentGame()
}