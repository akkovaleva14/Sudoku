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
import com.example.sudoku.domain.MistakeMode
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

    private sealed class SaveCommand {
        data class Save(
            val game: SudokuGame
        ) : SaveCommand()

        data class ClearCurrentGame(
            val gameId: String
        ) : SaveCommand()
    }

    private val saves =
        Channel<SaveCommand>(Channel.UNLIMITED)

    private var isClearingGame = false

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

    private fun message(
        id: Int,
        vararg args: Any
    ): String =
        text(id, args.toList())

    private fun queueSave(game: SudokuGame) {
        // После постановки удаления в очередь нельзя
        // снова поставить сохранение старой партии.
        if (isClearingGame) return

        saves.trySend(SaveCommand.Save(game))
    }

    private fun startSaveWorker() {
        viewModelScope.launch {
            for (command in saves) {
                when (command) {
                    is SaveCommand.Save -> {
                        try {
                            repository.save(command.game)
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (_: Exception) {
                            mutableState.value =
                                mutableState.value.copy(
                                    error = message(
                                        R.string.error_save
                                    )
                                )
                        }
                    }

                    is SaveCommand.ClearCurrentGame -> {
                        try {
                            // Сначала выполняются все старые
                            // сохранения, затем удаление.
                            repository.clearCurrentGame()

                            lastTimestamp = nowMillis()

                            if (
                                mutableState.value.game?.gameId ==
                                command.gameId
                            ) {
                                mutableState.value = SudokuUiState(
                                    game = null,
                                    isBusy = false,
                                    isPaused = false,
                                    openGameRequested = false
                                )
                            }
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (_: Exception) {
                            lastTimestamp = nowMillis()

                            // При ошибке партия остаётся
                            // доступной пользователю.
                            mutableState.value =
                                mutableState.value.copy(
                                    isBusy = false,
                                    openGameRequested = false,
                                    error = message(
                                        R.string.error_finish_current_game
                                    )
                                )
                        } finally {
                            isClearingGame = false
                        }
                    }
                }
            }
        }
    }

    private fun loadInitialGame() {
        generationJob = viewModelScope.launch {
            try {
                val saved = repository.load()

                lastTimestamp = nowMillis()

                // Загружаем сохранение, не создавая новую игру.
                mutableState.value = SudokuUiState(
                    game = saved,
                    isBusy = false
                )

                // Репозиторий защищает удалённые результаты
                // от повторного добавления.
                if (saved?.result != null) {
                    queueSave(saved)
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

    fun finishCurrentGame(expectedGameId: String) {
        val current = mutableState.value
        val game = current.game ?: return

        if (
            current.isBusy ||
            isClearingGame ||
            game.isFinished ||
            game.gameId != expectedGameId
        ) {
            return
        }

        closeHint()
        updateClock()

        val latest = mutableState.value
        val latestGame = latest.game ?: return

        if (
            latestGame.gameId != expectedGameId ||
            latestGame.isFinished
        ) {
            return
        }

        isClearingGame = true

        mutableState.value = latest.copy(
            isBusy = true,
            error = null,
            hint = null,
            hintMessage = null,
            isHintBusy = false,
            openGameRequested = false
        )

        val queued = saves.trySend(
            SaveCommand.ClearCurrentGame(
                gameId = expectedGameId
            )
        )

        if (queued.isFailure) {
            isClearingGame = false
            lastTimestamp = nowMillis()

            mutableState.value = mutableState.value.copy(
                isBusy = false,
                error = message(
                    R.string.error_finish_current_game
                )
            )
        }
    }

    fun newGame(
        difficulty: Difficulty,
        mistakeMode: MistakeMode = MistakeMode.UNLIMITED
    ) {
        val current = mutableState.value
        val currentGame = current.game

        if (current.isBusy) return

        // Пока текущая попытка не завершена,
        // другой режим можно выбрать только при рестарте
        // или после сброса текущей игры.
        if (
            currentGame != null &&
            !currentGame.isFinished &&
            currentGame.mistakeMode != mistakeMode
        ) {
            return
        }

        closeHint()
        updateClock()

        mutableState.value = mutableState.value.copy(
            isBusy = true,
            error = null,
            openGameRequested = false
        )

        generationJob = viewModelScope.launch {
            try {
                // Старую партию заменяем только после
                // успешного создания головоломки.
                val puzzle = generatePuzzle(difficulty)

                val game = SudokuGame(
                    puzzle = puzzle,
                    mistakeMode = mistakeMode
                )

                lastTimestamp = nowMillis()

                mutableState.value = SudokuUiState(
                    game = game,
                    isBusy = false,
                    isPaused = false,
                    openGameRequested = true
                )

                queueSave(game)
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

        updateClock()
        closeHint()

        val latest = mutableState.value

        mutableState.value = latest.copy(
            isPaused = !latest.isPaused
        )

        lastTimestamp = nowMillis()

        mutableState.value.game?.let {
            queueSave(it)
        }
    }

    fun select(index: Int) {
        change { engine.select(it, index) }
    }

    fun enterDigit(digit: Int) {
        change { game ->
            val entered = engine.enterDigit(game, digit)
            val index = game.selected

            // Считаем только новый неправильный ввод.
            // Заметки и повторное нажатие на уже стоящую
            // цифру ошибку не добавляют.
            val isMistake =
                !game.isFinished &&
                        !game.notesMode &&
                        digit in 1..9 &&
                        index in 0..80 &&
                        game.puzzle.givens[index] == 0 &&
                        entered.values[index] != game.values[index] &&
                        entered.values[index] == digit &&
                        digit != game.puzzle.solution[index]

            if (isMistake) {
                entered.copy(
                    mistakeCount = game.mistakeCount + 1
                )
            } else {
                entered
            }
        }
    }

    fun toggleNotes() {
        change { engine.toggleNotes(it) }
    }

    fun erase() {
        change { engine.erase(it) }
    }

    fun undo() {
        change { game ->
            // Счётчики ошибок и помощи отменой
            // хода не уменьшаются.
            engine.undo(game).copy(
                mistakeCount = game.mistakeCount,
                mistakesTrackedFromStart =
                    game.mistakesTrackedFromStart,
                mistakeMode = game.mistakeMode,
                help = game.help,
                result = game.result
            )
        }
    }

    fun restart(
        mistakeMode: MistakeMode? = null
    ) {
        // Проигранную партию можно только
        // перезапустить как новую попытку.
        change(allowLost = true) { game ->
            engine.restart(
                game = game,
                mistakeMode = mistakeMode ?: game.mistakeMode
            )
        }
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

                // Используем актуальное время партии,
                // а не время начала анализа.
                updateClock()

                val latest = mutableState.value
                val latestGame = latest.game

                if (
                    latest.isBusy ||
                    latest.isPaused ||
                    latestGame == null ||
                    latestGame.isFinished ||
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

        // Уже правильная цифра не является новой помощью.
        if (game.values[target] == digit) {
            mutableState.value = mutableState.value.copy(
                hintMessage = message(
                    R.string.selected_already_correct
                )
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
                !current.isPaused &&
                current.game?.isFinished == false
    }

    private fun applyNotesHint(
        game: SudokuGame,
        analysis: HintAnalysis.Notes
    ) {
        if (game.isFinished) return

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
        if (game.isFinished) return

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
        if (game.isFinished) return game

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
                help = updated.help,
                mistakeCount = updated.mistakeCount,
                mistakesTrackedFromStart =
                    updated.mistakesTrackedFromStart,
                mistakeMode = updated.mistakeMode,
                isVictory = updated.isWon
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

            // Сообщение о проигрыше не должно
            // перекрываться объяснением помощи.
            hintMessage =
                if (finalGame.isLost) null else hintMessage,

            isHintBusy = false
        )

        queueSave(finalGame)
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
                queueSave(it)
            }
        }
    }

    private fun change(
        allowLost: Boolean = false,
        action: (SudokuGame) -> SudokuGame
    ) {
        val current = mutableState.value

        if (
            current.isBusy ||
            current.isHintBusy ||
            current.isPaused
        ) {
            return
        }

        val initialGame = current.game ?: return

        // После проигрыша блокируем изменения
        // также на уровне ViewModel.
        if (initialGame.isLost && !allowLost) return

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
            queueSave(updated)
        }
    }
}
