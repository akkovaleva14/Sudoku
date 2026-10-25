package com.example.sudoku.domain.repository

import com.example.sudoku.domain.model.GameResult
import kotlinx.coroutines.flow.Flow

interface ResultsRepository {
    fun observeResults(): Flow<List<GameResult>>

    suspend fun deleteResult(gameId: String)

    suspend fun clearResults()
}