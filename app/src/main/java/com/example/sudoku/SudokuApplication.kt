package com.example.sudoku

import android.app.Application
import com.example.sudoku.data.PreferencesGameRepository
import com.example.sudoku.data.RandomSudokuGenerator
import com.example.sudoku.domain.GameEngine
import com.example.sudoku.domain.GameRepository
import com.example.sudoku.domain.GeneratePuzzleUseCase
import com.example.sudoku.domain.ResultsRepository

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
