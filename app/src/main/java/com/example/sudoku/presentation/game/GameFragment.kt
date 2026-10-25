package com.example.sudoku.presentation.game

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.sudoku.R
import com.example.sudoku.SudokuApplication
import com.example.sudoku.databinding.FragmentGameBinding
import com.example.sudoku.domain.model.MistakeMode
import com.example.sudoku.domain.model.SudokuGame
import com.example.sudoku.presentation.shared.state.SudokuUiState
import com.example.sudoku.presentation.shared.state.SudokuViewModel
import com.example.sudoku.presentation.shared.state.SudokuViewModelFactory
import com.example.sudoku.presentation.shared.formatting.difficultyName
import com.example.sudoku.presentation.shared.formatting.formatGameTime
import com.example.sudoku.presentation.main.MainActivity
import com.example.sudoku.presentation.shared.formatting.mistakeModeName
import kotlinx.coroutines.launch

class GameFragment : Fragment(R.layout.fragment_game) {

    private var binding: FragmentGameBinding? = null

    private val digitButtons = mutableListOf<Button>()

    private var fireworksView: VictoryFireworksView? = null
    private var fireworksHost: ViewGroup? = null

    private var lossAnimation: LossAnimationController? = null

    private val viewModel: SudokuViewModel by activityViewModels {
        SudokuViewModelFactory(
            requireActivity().application as SudokuApplication
        )
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        val ui = FragmentGameBinding.bind(view)
        binding = ui

        configureDigits(ui)
        configureActions(ui)

        lossAnimation = LossAnimationController(ui)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(
                Lifecycle.State.STARTED
            ) {
                // Состояние при открытии экрана не считается
                // новой победой или новым проигрышем.
                var previousGame = viewModel.state.value.game

                viewModel.state.collect { state ->
                    val before = previousGame
                    val current = state.game

                    val justCompleted =
                        before != null &&
                                current != null &&
                                before.gameId == current.gameId &&
                                before.result == null &&
                                current.result != null &&
                                current.isWon

                    val justLost =
                        before != null &&
                                current != null &&
                                before.gameId == current.gameId &&
                                !before.isLost &&
                                current.isLost

                    previousGame = current

                    render(ui, state)

                    // Выставляем визуальное состояние проигрыша
                    // после обычной отрисовки интерфейса.
                    lossAnimation?.render(
                        state = state,
                        animateLoss = justLost
                    )

                    if (justCompleted) {
                        showVictoryFireworks(ui)
                    }
                }
            }
        }
    }

    private fun configureDigits(ui: FragmentGameBinding) {
        val height = resources.getDimensionPixelSize(
            R.dimen.digit_height
        )

        for (digit in 1..9) {
            val button = Button(requireContext()).apply {
                text = digit.toString()
                textSize = 16f
                minWidth = 0
                minimumWidth = 0

                setPadding(0, 0, 0, 0)

                contentDescription = getString(
                    R.string.enter_digit,
                    digit
                )

                layoutParams = LinearLayout.LayoutParams(
                    0,
                    height,
                    1f
                )

                setOnClickListener {
                    viewModel.enterDigit(digit)
                }
            }

            digitButtons.add(button)
            ui.digitButtons.addView(button)
        }
    }

    private fun configureActions(ui: FragmentGameBinding) {
        ui.sudokuBoard.onCellSelected = viewModel::select

        ui.menuButton.setOnClickListener {
            clearVictoryFireworks()
            lossAnimation?.finishImmediately()

            viewModel.setForeground(false)

            (requireActivity() as MainActivity).openStart()
        }

        ui.pauseButton.setOnClickListener {
            viewModel.togglePause()
        }

        ui.restartButton.setOnClickListener {
            showRestartDialog()
        }

        ui.notesButton.setOnClickListener {
            viewModel.toggleNotes()
        }

        ui.eraseButton.setOnClickListener {
            viewModel.erase()
        }

        ui.undoButton.setOnClickListener {
            viewModel.undo()
        }

        ui.hintButton.setOnClickListener {
            viewModel.hint()
        }

        ui.fillRandomCellButton.setOnClickListener {
            viewModel.fillRandomCell()
        }

        ui.fillSelectedCellButton.setOnClickListener {
            viewModel.fillSelectedCell()
        }

        ui.closeHintButton.setOnClickListener {
            viewModel.closeHint()
        }
    }

    private fun showRestartDialog() {
        val current = viewModel.state.value
        val game = current.game ?: return

        if (
            current.isBusy ||
            current.isHintBusy ||
            current.isPaused
        ) {
            return
        }

        val modes = MistakeMode.entries
        val attemptId = game.gameId

        var selectedPosition =
            modes.indexOf(game.mistakeMode)

        val labels = modes.map {
            requireContext().mistakeModeName(it)
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.restart_select_mode)
            .setSingleChoiceItems(
                labels,
                selectedPosition
            ) { _, position ->
                selectedPosition = position
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(
                R.string.continue_action
            ) { _, _ ->
                val selectedMode = modes[selectedPosition]

                if (
                    binding == null ||
                    viewModel.state.value.game?.gameId != attemptId
                ) {
                    return@setPositiveButton
                }

                confirm(
                    R.string.restart,
                    R.string.confirm_restart
                ) {
                    if (
                        binding != null &&
                        viewModel.state.value.game?.gameId == attemptId
                    ) {
                        clearVictoryFireworks()
                        lossAnimation?.finishImmediately()

                        viewModel.restart(selectedMode)
                    }
                }
            }
            .show()
    }

    private fun render(
        ui: FragmentGameBinding,
        state: SudokuUiState
    ) {
        val game = state.game

        if (
            state.isBusy ||
            state.isPaused ||
            game?.isWon != true
        ) {
            clearVictoryFireworks()
        }

        val ready =
            !state.isBusy &&
                    !state.isHintBusy &&
                    !state.isPaused &&
                    game != null

        val unfinished =
            ready && game?.isFinished == false

        ui.generationProgress.isVisible =
            state.isBusy || state.isHintBusy

        ui.menuButton.isEnabled = !state.isBusy

        // Во время анализа подсказки пауза доступна.
        // ViewModel отменит поиск.
        ui.pauseButton.isEnabled =
            !state.isBusy &&
                    game != null &&
                    !game.isFinished

        ui.pauseButton.setText(
            if (state.isPaused) {
                R.string.resume_game
            } else {
                R.string.pause_game
            }
        )

        ui.sudokuBoard.visibility =
            if (state.isPaused) {
                View.INVISIBLE
            } else {
                View.VISIBLE
            }

        ui.pauseOverlay.isVisible = state.isPaused

        // После проигрыша поле остаётся видимым,
        // но больше не принимает нажатия.
        ui.sudokuBoard.isEnabled =
            ready && game?.isLost == false

        // Меню и рестарт не блокируются
        // проигрышной анимацией.
        ui.restartButton.isEnabled = ready
        ui.notesButton.isEnabled = unfinished

        ui.hintButton.isEnabled =
            !state.isBusy &&
                    !state.isPaused &&
                    game?.isFinished == false

        ui.hintButton.setText(
            when {
                state.isHintBusy -> R.string.cancel_hint
                state.hint != null -> R.string.place_digit
                else -> R.string.hint
            }
        )

        ui.fillRandomCellButton.isEnabled =
            unfinished &&
                    game != null &&
                    game.values.indices.any {
                        game.values[it] == 0 &&
                                game.puzzle.givens[it] == 0
                    }

        ui.closeHintButton.isVisible =
            ready &&
                    game?.isLost == false &&
                    (
                            state.hint != null ||
                                    state.hintMessage != null
                            )

        // Победный ход по-прежнему можно отменить.
        // Проигрыш отменять нельзя.
        ui.undoButton.isEnabled =
            ready &&
                    game != null &&
                    !game.isLost &&
                    game.history.isNotEmpty()

        val editable =
            unfinished &&
                    game != null &&
                    game.selected in 0..80 &&
                    game.puzzle.givens[game.selected] == 0

        val canEnter =
            editable &&
                    game != null &&
                    (
                            !game.notesMode ||
                                    game.values[game.selected] == 0
                            )

        renderDigitButtons(
            game = game,
            canEnter = canEnter
        )

        ui.eraseButton.isEnabled =
            editable &&
                    game != null &&
                    (
                            game.values[game.selected] != 0 ||
                                    game.notes[game.selected] != 0
                            )

        ui.fillSelectedCellButton.isEnabled =
            editable &&
                    game != null &&
                    game.values[game.selected] !=
                    game.puzzle.solution[game.selected]

        if (game != null) {
            ui.sudokuBoard.setState(
                givens = game.puzzle.givens,
                values = game.values,
                notes = game.notes,
                solution = game.puzzle.solution,
                selected = game.selected,
                hint = state.hint
            )

            ui.notesButton.setText(
                if (game.notesMode) {
                    R.string.notes_on
                } else {
                    R.string.notes_off
                }
            )

            ui.notesButton.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (game.notesMode) {
                        R.color.accent
                    } else {
                        R.color.text_primary
                    }
                )
            )
        }

        renderExplanation(ui, state)
        renderStatus(ui, state)
    }

    private fun renderStatus(
        ui: FragmentGameBinding,
        state: SudokuUiState
    ) {
        val game = state.game

        ui.statusText.text = when {
            state.isBusy ->
                getString(R.string.generating)

            state.error != null ->
                state.error

            game == null ->
                getString(R.string.start_new_game)

            else -> {
                val suffix = when {
                    state.isPaused ->
                        getString(R.string.status_paused)

                    game.isLost ->
                        getString(R.string.status_lost)

                    game.isWon ->
                        getString(R.string.status_finished)

                    state.isHintBusy ->
                        getString(R.string.status_finding_hint)

                    state.hint != null ->
                        getString(R.string.status_hint)

                    else -> ""
                }

                // Количество исходных чисел здесь
                // больше не отображается.
                val gameStatus = getString(
                    R.string.game_status_without_clues,
                    requireContext().difficultyName(
                        game.puzzle.difficulty
                    ),
                    formatGameTime(game.elapsedMillis),
                    suffix
                )

                if (game.mistakeMode == MistakeMode.TWO_MISTAKES) {
                    // Счётчик 0/3, 1/3, 2/3, 3/3
                    // отображает LossAnimationController
                    // в отдельном TextView той же строки.
                    gameStatus
                } else {
                    val mistakeStatus = getString(
                        if (game.mistakesTrackedFromStart) {
                            R.string.mistakes_count
                        } else {
                            R.string.mistakes_count_since_update
                        },
                        game.mistakeCount
                    )

                    val modeStatus = getString(
                        R.string.game_mistake_mode,
                        requireContext().mistakeModeName(
                            game.mistakeMode
                        )
                    )

                    "$gameStatus · $mistakeStatus · $modeStatus"
                }
            }
        }
    }

    private fun renderDigitButtons(
        game: SudokuGame?,
        canEnter: Boolean
    ) {
        val completedColor = ContextCompat.getColor(
            requireContext(),
            R.color.digit_completed
        )

        val normalColor = ContextCompat.getColor(
            requireContext(),
            R.color.text_primary
        )

        val disabledColor = ContextCompat.getColor(
            requireContext(),
            R.color.text_secondary
        )

        digitButtons.forEachIndexed { index, button ->
            val digit = index + 1

            val completed =
                if (game == null) {
                    false
                } else {
                    // Галочка появляется только при
                    // девяти правильных экземплярах цифры.
                    val correctCount =
                        game.values.indices.count { cell ->
                            game.values[cell] == digit &&
                                    game.puzzle.solution[cell] == digit
                        }

                    val totalCount =
                        game.values.count { it == digit }

                    correctCount == 9 && totalCount == 9
                }

            button.text =
                if (completed) {
                    "✓"
                } else {
                    digit.toString()
                }

            button.textSize =
                if (completed) {
                    22f
                } else {
                    16f
                }

            button.isEnabled = canEnter && !completed

            button.setTextColor(
                when {
                    completed -> completedColor
                    button.isEnabled -> normalColor
                    else -> disabledColor
                }
            )

            val digitDescription = getString(
                R.string.enter_digit,
                digit
            )

            button.contentDescription =
                if (completed) {
                    "$digitDescription. ✓. 9/9"
                } else {
                    digitDescription
                }
        }
    }

    private fun renderExplanation(
        ui: FragmentGameBinding,
        state: SudokuUiState
    ) {
        val game = state.game

        val explanation = when {
            state.isPaused -> null
            state.isBusy -> null
            game?.isLost == true -> null

            state.isHintBusy ->
                getString(R.string.hint_search_info)

            state.hint != null ->
                state.hint.explanation

            else ->
                state.hintMessage
        }

        val showingExplanation = explanation != null

        ui.selectionText.gravity =
            if (showingExplanation) {
                Gravity.START
            } else {
                Gravity.CENTER
            }

        val padding =
            if (showingExplanation) {
                resources.getDimensionPixelSize(
                    R.dimen.screen_padding
                )
            } else {
                0
            }

        ui.selectionText.setPadding(
            padding,
            padding,
            padding,
            padding
        )

        ui.selectionText.setBackgroundColor(
            ContextCompat.getColor(
                requireContext(),
                if (showingExplanation) {
                    R.color.explanation_background
                } else {
                    android.R.color.transparent
                }
            )
        )

        ui.selectionText.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when {
                    game?.isLost == true &&
                            !state.isBusy &&
                            !state.isPaused ->
                        R.color.loss_error

                    showingExplanation ->
                        R.color.explanation_text

                    else ->
                        R.color.text_secondary
                }
            )
        )

        ui.selectionText.text = explanation ?: when {
            state.isPaused ->
                getString(R.string.pause_description)

            state.isBusy ->
                getString(R.string.preparing)

            game == null ->
                getString(R.string.create_game)

            game.isLost ->
                getString(R.string.game_lost_description)

            game.isWon ->
                getString(R.string.well_done)

            game.selected !in 0..80 ->
                getString(R.string.select_cell)

            game.puzzle.givens[game.selected] != 0 ->
                getString(R.string.given_cannot_change)

            game.notesMode &&
                    game.values[game.selected] != 0 ->
                getString(R.string.clear_before_notes)

            else ->
                getString(
                    R.string.cell_position,
                    game.selected / 9 + 1,
                    game.selected % 9 + 1
                )
        }
    }

    private fun showVictoryFireworks(
        ui: FragmentGameBinding
    ) {
        val host = ui.root as? ViewGroup ?: return

        val completedGameId =
            viewModel.state.value.game?.gameId ?: return

        host.post {
            // Не обращаемся к уничтоженной
            // или заменённой разметке.
            if (binding !== ui) {
                return@post
            }

            if (
                !viewLifecycleOwner.lifecycle.currentState
                    .isAtLeast(Lifecycle.State.STARTED)
            ) {
                return@post
            }

            val state = viewModel.state.value
            val game = state.game

            if (
                state.isBusy ||
                state.isPaused ||
                game == null ||
                game.gameId != completedGameId ||
                !game.isWon ||
                game.result == null ||
                !host.isAttachedToWindow ||
                host.width <= 0 ||
                host.height <= 0
            ) {
                return@post
            }

            clearVictoryFireworks()

            val fireworks = VictoryFireworksView(
                requireContext()
            )

            // Overlay не выполняет обычную раскладку.
            fireworks.layout(
                0,
                0,
                host.width,
                host.height
            )

            fireworksHost = host
            fireworksView = fireworks

            // Салют не занимает место в разметке
            // и не перехватывает нажатия.
            host.overlay.add(fireworks)

            fireworks.start {
                if (fireworksView === fireworks) {
                    clearVictoryFireworks()
                }
            }
        }
    }

    private fun clearVictoryFireworks() {
        val fireworks = fireworksView
        val host = fireworksHost

        fireworksView = null
        fireworksHost = null

        fireworks?.stop()

        if (fireworks != null && host != null) {
            host.overlay.remove(fireworks)
        }
    }

    private fun confirm(
        title: Int,
        message: Int,
        action: () -> Unit
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(
                R.string.continue_action
            ) { _, _ ->
                action()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        viewModel.setForeground(true)
    }

    override fun onPause() {
        clearVictoryFireworks()
        lossAnimation?.finishImmediately()

        viewModel.setForeground(false)

        super.onPause()
    }

    override fun onDestroyView() {
        clearVictoryFireworks()

        lossAnimation?.dispose()
        lossAnimation = null

        binding?.sudokuBoard?.onCellSelected = null

        digitButtons.clear()
        binding = null

        super.onDestroyView()
    }
}