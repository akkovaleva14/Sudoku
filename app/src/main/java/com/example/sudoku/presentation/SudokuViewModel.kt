package com.example.sudoku.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sudoku.R
import com.example.sudoku.domain.Difficulty
import com.example.sudoku.domain.GameEngine
import com.example.sudoku.domain.GameRepository
import com.example.sudoku.domain.GameResult
import com.example.sudoku.domain.GeneratePuzzleUseCase
import com.example.sudoku.domain.HintAnalysis
import com.example.sudoku.domain.HintAnalyzer
import com.example.sudoku.domain.LogicalHint
import com.example.sudoku.domain.MoveSnapshot
import com.example.sudoku.domain.SudokuGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

data class SudokuUiState(
    val game: SudokuGame? = null,
    val isBusy: Boolean = true,
    val error: String? = null,
    val hint: LogicalHint? = null,
    val hintMessage: String? = null,
    val isHintBusy: Boolean = false,
    val isPaused: Boolean = false,
    val openGameRequested: Boolean = false
)

class SudokuViewModel(
    private val generatePuzzle: GeneratePuzzleUseCase,
    private val repository: GameRepository,
    private val engine: GameEngine,
    private val hintAnalyzer: HintAnalyzer,
    private val nowMillis: () -> Long,
    private val wallClockMillis: () -> Long,
    private val text: (Int, List<Any>) -> String
) : ViewModel() {

    private val mutableState = MutableStateFlow(SudokuUiState())

    val state: StateFlow<SudokuUiState> =
        mutableState.asStateFlow()

    private val saves = Channel<SudokuGame>(Channel.UNLIMITED)

    private var generationJob: Job? = null
    private var hintJob: Job? = null
    private var hintRequestId = 0L

    private var foreground = false
    private var lastTimestamp = nowMillis()

    init {
        startSaveWorker()
        loadInitialGame()

        viewModelScope.launch {
            while (true) {
                delay(1000L)
                updateClock()
            }
        }
    }

    private fun message(id: Int, vararg args: Any): String =
        text(id, args.toList())

    private fun startSaveWorker() {
        viewModelScope.launch {
            for (game in saves) {
                try {
                    repository.save(game)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    mutableState.value = mutableState.value.copy(
                        error = message(R.string.error_save)
                    )
                }
            }
        }
    }

    private fun loadInitialGame() {
        generationJob = viewModelScope.launch {
            try {
                val saved = repository.load()

                lastTimestamp = nowMillis()

                // Только загружаем сохранение.
                // Новую игру создаёт кнопка стартового экрана.
                mutableState.value = SudokuUiState(
                    game = saved,
                    isBusy = false
                )

                if (saved?.result != null) {
                    saves.trySend(saved)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableState.value = SudokuUiState(
                    isBusy = false,
                    error = message(R.string.error_load)
                )
            }
        }
    }

    fun newGame(difficulty: Difficulty) {
        if (mutableState.value.isBusy) return

        closeHint()
        updateClock()

        mutableState.value = mutableState.value.copy(
            isBusy = true,
            error = null,
            openGameRequested = false
        )

        generationJob = viewModelScope.launch {
            try {
                // До завершения генерации старую партию
                // не заменяем.
                val puzzle = generatePuzzle(difficulty)
                val game = SudokuGame(puzzle = puzzle)

                lastTimestamp = nowMillis()

                mutableState.value = SudokuUiState(
                    game = game,
                    isBusy = false,
                    isPaused = false,
                    openGameRequested = true
                )

                saves.trySend(game)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                lastTimestamp = nowMillis()

                mutableState.value = mutableState.value.copy(
                    isBusy = false,
                    openGameRequested = false,
                    error = exception.message
                        ?: message(R.string.error_generate)
                )
            }
        }
    }

    fun consumeOpenGameRequest() {
        mutableState.value = mutableState.value.copy(
            openGameRequested = false
        )
    }

    fun togglePause() {
        val current = mutableState.value
        val game = current.game ?: return

        if (current.isBusy || game.isFinished) return

        // Сначала учитываем время до нажатия кнопки.
        updateClock()
        closeHint()

        val latest = mutableState.value

        mutableState.value = latest.copy(
            isPaused = !latest.isPaused
        )

        lastTimestamp = nowMillis()

        mutableState.value.game?.let {
            saves.trySend(it)
        }
    }

    fun select(index: Int) {
        change { engine.select(it, index) }
    }

    fun enterDigit(digit: Int) {
        change { engine.enterDigit(it, digit) }
    }

    fun toggleNotes() {
        change { engine.toggleNotes(it) }
    }

    fun erase() {
        change { engine.erase(it) }
    }

    fun undo() {
        change { engine.undo(it) }
    }

    fun restart() {
        change { engine.restart(it) }
    }

    fun hint() {
        val initial = mutableState.value

        if (initial.isBusy || initial.isPaused) return

        if (initial.isHintBusy) {
            closeHint()
            return
        }

        updateClock()

        val current = mutableState.value
        val game = current.game ?: return

        if (game.isFinished) return

        current.hint?.let {
            applyHint(game, it)
            return
        }

        hintRequestId++
        val requestId = hintRequestId

        mutableState.value = current.copy(
            isHintBusy = true,
            hint = null,
            hintMessage = null,
            error = null
        )

        hintJob = viewModelScope.launch {
            try {
                val analysis = hintAnalyzer.analyze(game)

                if (requestId != hintRequestId) return@launch

                // Анализ мог занять время.
                // Применяем его к актуальной партии,
                // не откатывая таймер к началу анализа.
                updateClock()

                val latest = mutableState.value
                val latestGame = latest.game

                if (
                    latest.isPaused ||
                    latestGame == null ||
                    latestGame.gameId != game.gameId ||
                    latestGame.values != game.values ||
                    latestGame.notes != game.notes
                ) {
                    mutableState.value = latest.copy(
                        isHintBusy = false,
                        hint = null,
                        hintMessage = null
                    )
                    return@launch
                }

                when (analysis) {
                    is HintAnalysis.Found -> {
                        mutableState.value = latest.copy(
                            isHintBusy = false,
                            hint = analysis.hint,
                            hintMessage = null
                        )
                    }

                    is HintAnalysis.Notes -> {
                        applyNotesHint(latestGame, analysis)
                    }

                    is HintAnalysis.Unavailable -> {
                        mutableState.value = latest.copy(
                            isHintBusy = false,
                            hint = null,
                            hintMessage = analysis.message
                        )
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                if (requestId == hintRequestId) {
                    mutableState.value = mutableState.value.copy(
                        isHintBusy = false,
                        hint = null,
                        hintMessage = message(R.string.error_hint)
                    )
                }
            } finally {
                if (requestId == hintRequestId) {
                    hintJob = null
                }
            }
        }
    }

    fun fillSelectedCell() {
        if (!canUseAutomaticFill()) return

        closeHint()
        updateClock()

        val game = mutableState.value.game ?: return
        if (game.isFinished) return

        val target = game.selected

        if (target !in 0..80) {
            mutableState.value = mutableState.value.copy(
                hintMessage = message(R.string.select_cell)
            )
            return
        }

        if (game.puzzle.givens[target] != 0) {
            mutableState.value = mutableState.value.copy(
                hintMessage = message(R.string.given_cannot_change)
            )
            return
        }

        val digit = game.puzzle.solution[target]

        // Не перезаписываем уже правильную цифру:
        // это не помощь и не новый ход.
        if (game.values[target] == digit) {
            mutableState.value = mutableState.value.copy(
                hintMessage = message(R.string.selected_already_correct)
            )
            return
        }

        val entered = fillCell(game, target, digit)

        if (entered.values == game.values) return

        val updated = entered.copy(
            help = game.help.copy(
                automaticFills = game.help.automaticFills + 1
            )
        )

        publish(
            before = game,
            updated = updated,
            hintMessage = message(R.string.selected_filled, digit)
        )
    }

    fun fillRandomCell() {
        if (!canUseAutomaticFill()) return

        closeHint()
        updateClock()

        val game = mutableState.value.game ?: return
        if (game.isFinished) return

        val emptyCells = game.values.indices.filter {
            game.values[it] == 0 &&
                    game.puzzle.givens[it] == 0
        }

        if (emptyCells.isEmpty()) {
            mutableState.value = mutableState.value.copy(
                hintMessage = message(R.string.no_empty_cells)
            )
            return
        }

        val target = emptyCells.random(Random.Default)
        val digit = game.puzzle.solution[target]
        val entered = fillCell(game, target, digit)

        if (entered.values == game.values) return

        val updated = entered.copy(
            help = game.help.copy(
                automaticFills = game.help.automaticFills + 1
            )
        )

        publish(
            before = game,
            updated = updated,
            hintMessage = message(R.string.random_filled, digit)
        )
    }

    private fun canUseAutomaticFill(): Boolean {
        val current = mutableState.value

        return !current.isBusy &&
                !current.isHintBusy &&
                !current.isPaused
    }

    private fun applyNotesHint(
        game: SudokuGame,
        analysis: HintAnalysis.Notes
    ) {
        val target = analysis.targetIndex
        val digits = analysis.digits

        if (
            target !in 0..80 ||
            digits.size != 2 ||
            digits.any { it !in 1..9 } ||
            game.values[target] != 0 ||
            game.puzzle.givens[target] != 0 ||
            game.puzzle.solution[target] !in digits
        ) {
            mutableState.value = mutableState.value.copy(
                isHintBusy = false,
                hint = null,
                hintMessage = message(R.string.field_changed)
            )
            return
        }

        val mask = digits.fold(0) { result, digit ->
            result or (1 shl (digit - 1))
        }

        val changed = game.notes[target] != mask
        val notes = game.notes.toMutableList()
        notes[target] = mask

        val history =
            if (changed) {
                (
                        game.history + MoveSnapshot(
                            values = game.values,
                            notes = game.notes,
                            selected = game.selected
                        )
                        ).takeLast(100)
            } else {
                game.history
            }

        val updated = game.copy(
            notes = notes.toList(),
            selected = target,
            history = history,
            help = game.help.copy(
                notesHints = game.help.notesHints + 1
            )
        )

        publish(
            before = game,
            updated = updated,
            hintMessage = analysis.explanation
        )
    }

    private fun applyHint(
        game: SudokuGame,
        hint: LogicalHint
    ) {
        val target = hint.targetIndex

        if (
            target !in 0..80 ||
            hint.digit !in 1..9 ||
            game.values[target] != 0 ||
            game.puzzle.givens[target] != 0 ||
            game.puzzle.solution[target] != hint.digit
        ) {
            closeHint()

            mutableState.value = mutableState.value.copy(
                hintMessage = message(R.string.field_changed)
            )
            return
        }

        val entered = fillCell(game, target, hint.digit)

        if (entered.values == game.values) {
            closeHint()
            return
        }

        val updated = entered.copy(
            help = game.help.copy(
                logicalHints = game.help.logicalHints + 1
            )
        )

        publish(game, updated)
    }

    private fun fillCell(
        game: SudokuGame,
        target: Int,
        digit: Int
    ): SudokuGame {
        val prepared = game.copy(
            selected = target,
            notesMode = false
        )

        val entered = engine.enterDigit(prepared, digit)

        val history =
            if (
                entered.values != game.values &&
                entered.history.isNotEmpty()
            ) {
                entered.history.dropLast(1) +
                        entered.history.last().copy(
                            selected = game.selected
                        )
            } else {
                entered.history
            }

        return entered.copy(
            notesMode = game.notesMode,
            history = history
        )
    }

    private fun finishIfNeeded(
        before: SudokuGame,
        updated: SudokuGame
    ): SudokuGame {
        if (
            before.gameId != updated.gameId ||
            before.isFinished ||
            !updated.isFinished ||
            updated.result != null
        ) {
            return updated
        }

        return updated.copy(
            result = GameResult(
                gameId = updated.gameId,
                difficulty = updated.puzzle.difficulty,
                elapsedMillis = updated.elapsedMillis,
                completedAtMillis = wallClockMillis(),
                clues = updated.puzzle.givens.count { it != 0 },
                help = updated.help
            )
        )
    }

    private fun publish(
        before: SudokuGame,
        updated: SudokuGame,
        hintMessage: String? = null
    ) {
        val finalGame = finishIfNeeded(before, updated)

        mutableState.value = mutableState.value.copy(
            game = finalGame,
            error = null,
            hint = null,
            hintMessage = hintMessage,
            isHintBusy = false
        )

        saves.trySend(finalGame)
    }

    fun closeHint() {
        hintRequestId++
        hintJob?.cancel()
        hintJob = null

        mutableState.value = mutableState.value.copy(
            isHintBusy = false,
            hint = null,
            hintMessage = null
        )
    }

    fun setForeground(value: Boolean) {
        updateClock()

        foreground = value
        lastTimestamp = nowMillis()

        if (!value) {
            closeHint()

            mutableState.value.game?.let {
                saves.trySend(it)
            }
        }
    }

    private fun change(action: (SudokuGame) -> SudokuGame) {
        val current = mutableState.value

        if (
            current.isBusy ||
            current.isHintBusy ||
            current.isPaused
        ) {
            return
        }

        closeHint()
        updateClock()

        val game = mutableState.value.game ?: return
        val updated = action(game)

        if (updated != game) {
            publish(game, updated)
        }
    }

    private fun updateClock() {
        val now = nowMillis()
        val delta = (now - lastTimestamp).coerceAtLeast(0L)
        lastTimestamp = now

        val current = mutableState.value
        val game = current.game ?: return

        if (
            !foreground ||
            current.isBusy ||
            current.isPaused ||
            game.isFinished
        ) {
            return
        }

        val updated = engine.addElapsedTime(game, delta)

        if (updated != game) {
            mutableState.value = current.copy(game = updated)
            saves.trySend(updated)
        }
    }
}
