package com.example.sudoku

import android.app.Application
import com.example.sudoku.data.PreferencesGameRepository
import com.example.sudoku.data.RandomSudokuGenerator
import com.example.sudoku.domain.game.GameEngine
import com.example.sudoku.domain.repository.GameRepository
import com.example.sudoku.domain.generation.GeneratePuzzleUseCase
import com.example.sudoku.domain.repository.ResultsRepository

class SudokuApplication : Application() {

    val container: AppContainer by lazy {
        AppContainer(this)
    }
}

class AppContainer(application: Application) {

    private val storage = PreferencesGameRepository(
        application.getSharedPreferences(
            "sudoku_preferences",
            Application.MODE_PRIVATE
        )
    )

    val repository: GameRepository = storage
    val resultsRepository: ResultsRepository = storage

    val generatePuzzle = GeneratePuzzleUseCase(
        RandomSudokuGenerator()
    )

    val gameEngine = GameEngine()
}
