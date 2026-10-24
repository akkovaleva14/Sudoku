package com.example.sudoku.presentation

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
import kotlinx.coroutines.launch

class GameFragment : Fragment(R.layout.fragment_game) {

    private var binding: FragmentGameBinding? = null
    private val digitButtons = mutableListOf<Button>()

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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(
                Lifecycle.State.STARTED
            ) {
                viewModel.state.collect { state ->
                    render(ui, state)
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
            viewModel.setForeground(false)
            (requireActivity() as MainActivity).openStart()
        }

        ui.pauseButton.setOnClickListener {
            viewModel.togglePause()
        }

        ui.restartButton.setOnClickListener {
            confirm(
                R.string.restart,
                R.string.confirm_restart
            ) {
                viewModel.restart()
            }
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

    private fun render(
        ui: FragmentGameBinding,
        state: SudokuUiState
    ) {
        val game = state.game

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

        // Паузу можно включить и во время поиска подсказки:
        // ViewModel отменит анализ.
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
        ui.sudokuBoard.isEnabled = ready

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
                    (
                            state.hint != null ||
                                    state.hintMessage != null
                            )

        ui.undoButton.isEnabled =
            ready && game?.history?.isNotEmpty() == true

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

        digitButtons.forEach {
            it.isEnabled = canEnter
        }

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

                    game.isFinished ->
                        getString(R.string.status_finished)

                    state.isHintBusy ->
                        getString(R.string.status_finding_hint)

                    state.hint != null ->
                        getString(R.string.status_hint)

                    else -> ""
                }

                getString(
                    R.string.game_status,
                    requireContext().difficultyName(
                        game.puzzle.difficulty
                    ),
                    formatGameTime(game.elapsedMillis),
                    game.puzzle.givens.count { it != 0 },
                    suffix
                )
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
                if (showingExplanation) {
                    R.color.explanation_text
                } else {
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

            game.isFinished ->
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

    private fun confirm(
        title: Int,
        message: Int,
        action: () -> Unit
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                action()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        viewModel.setForeground(true)
    }

    override fun onPause() {
        viewModel.setForeground(false)
        super.onPause()
    }

    override fun onDestroyView() {
        binding?.sudokuBoard?.onCellSelected = null
        digitButtons.clear()
        binding = null
        super.onDestroyView()
    }
}
