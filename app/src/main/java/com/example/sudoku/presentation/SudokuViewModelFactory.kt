package com.example.sudoku.presentation

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.sudoku.SudokuApplication
import com.example.sudoku.domain.HintAnalyzer

class SudokuViewModelFactory(
    private val application: SudokuApplication
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(
        modelClass: Class<T>
    ): T {
        require(
            modelClass.isAssignableFrom(SudokuViewModel::class.java)
        )

        val container = application.container

        @Suppress("UNCHECKED_CAST")
        return SudokuViewModel(
            generatePuzzle = container.generatePuzzle,
            repository = container.repository,
            engine = container.gameEngine,
            hintAnalyzer = HintAnalyzer(),
            nowMillis = { SystemClock.elapsedRealtime() },
            wallClockMillis = { System.currentTimeMillis() },
            text = { id, args ->
                application.getString(id, *args.toTypedArray())
            }
        ) as T
    }
}
