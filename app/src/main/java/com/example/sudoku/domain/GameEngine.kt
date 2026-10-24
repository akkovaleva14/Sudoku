package com.example.sudoku.domain

class GameEngine {

    fun select(
        game: SudokuGame,
        index: Int
    ): SudokuGame {
        if (index !in 0..80) return game

        return game.copy(selected = index)
    }

    fun toggleNotes(game: SudokuGame): SudokuGame {
        if (game.isFinished) return game

        return game.copy(
            notesMode = !game.notesMode
        )
    }

    fun enterDigit(
        game: SudokuGame,
        digit: Int
    ): SudokuGame {
        if (digit !in 1..9 || !canEdit(game)) {
            return game
        }

        val index = game.selected

        if (game.notesMode) {
            if (game.values[index] != 0) return game

            val notes = game.notes.toMutableList()

            notes[index] =
                notes[index] xor (1 shl (digit - 1))

            return remember(game).copy(
                notes = notes.toList()
            )
        }

        if (game.values[index] == digit) return game

        val values = game.values.toMutableList()
        val notes = game.notes.toMutableList()

        values[index] = digit
        notes[index] = 0

        return remember(game).copy(
            values = values.toList(),
            notes = notes.toList()
        )
    }

    fun erase(game: SudokuGame): SudokuGame {
        if (!canEdit(game)) return game

        val index = game.selected

        if (
            game.values[index] == 0 &&
            game.notes[index] == 0
        ) {
            return game
        }

        val values = game.values.toMutableList()
        val notes = game.notes.toMutableList()

        values[index] = 0
        notes[index] = 0

        return remember(game).copy(
            values = values.toList(),
            notes = notes.toList()
        )
    }

    fun hint(game: SudokuGame): SudokuGame {
        if (game.isFinished) return game

        val selected = game.selected

        val target = if (
            selected in 0..80 &&
            game.puzzle.givens[selected] == 0 &&
            game.values[selected] !=
            game.puzzle.solution[selected]
        ) {
            selected
        } else {
            (0 until 81).firstOrNull { index ->
                game.puzzle.givens[index] == 0 &&
                        game.values[index] !=
                        game.puzzle.solution[index]
            } ?: return game
        }

        val values = game.values.toMutableList()
        val notes = game.notes.toMutableList()

        values[target] = game.puzzle.solution[target]
        notes[target] = 0

        return remember(game).copy(
            values = values.toList(),
            notes = notes.toList(),
            selected = target
        )
    }

    fun undo(game: SudokuGame): SudokuGame {
        val snapshot =
            game.history.lastOrNull() ?: return game

        return game.copy(
            values = snapshot.values,
            notes = snapshot.notes,
            selected = snapshot.selected,
            history = game.history.dropLast(1)
        )
    }

    fun restart(game: SudokuGame): SudokuGame {
        return SudokuGame(
            puzzle = game.puzzle
        )
    }

    fun addElapsedTime(
        game: SudokuGame,
        millis: Long
    ): SudokuGame {
        if (game.isFinished || millis <= 0L) {
            return game
        }

        return game.copy(
            elapsedMillis = game.elapsedMillis + millis
        )
    }

    private fun canEdit(game: SudokuGame): Boolean {
        return !game.isFinished &&
                game.selected in 0..80 &&
                game.puzzle.givens[game.selected] == 0
    }

    private fun remember(game: SudokuGame): SudokuGame {
        val snapshot = MoveSnapshot(
            values = game.values,
            notes = game.notes,
            selected = game.selected
        )

        return game.copy(
            history = (game.history + snapshot).takeLast(100)
        )
    }
}
