package com.example.sudoku.domain

import kotlinx.coroutines.flow.Flow

interface ResultsRepository {
    fun observeResults(): Flow<List<GameResult>>

    suspend fun deleteResult(gameId: String)

    suspend fun clearResults()
}
