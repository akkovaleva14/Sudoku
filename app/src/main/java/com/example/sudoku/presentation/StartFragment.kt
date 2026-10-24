package com.example.sudoku.presentation

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.sudoku.R
import com.example.sudoku.SudokuApplication
import com.example.sudoku.databinding.FragmentStartBinding
import com.example.sudoku.domain.Difficulty
import com.example.sudoku.domain.MistakeMode
import kotlinx.coroutines.launch

class StartFragment : Fragment(R.layout.fragment_start) {

    private var binding: FragmentStartBinding? = null

    private var finishGameDialog: AlertDialog? = null

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

        val ui = FragmentStartBinding.bind(view)
        binding = ui

        ui.difficultySpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            Difficulty.entries.map {
                requireContext().difficultyName(it)
            }
        ).apply {
            setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
            )
        }

        ui.mistakeModeSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            MistakeMode.entries.map {
                requireContext().mistakeModeName(it)
            }
        ).apply {
            setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
            )
        }

        val initialMode =
            viewModel.state.value.game?.mistakeMode
                ?: MistakeMode.UNLIMITED

        ui.mistakeModeSpinner.setSelection(
            MistakeMode.entries.indexOf(initialMode)
        )

        configureActions(ui)
        observeState(ui)
    }

    private fun configureActions(
        ui: FragmentStartBinding
    ) {
        ui.startGameButton.setOnClickListener {
            val current = viewModel.state.value

            if (current.isBusy) {
                return@setOnClickListener
            }

            val difficulty = Difficulty.entries[
                ui.difficultySpinner.selectedItemPosition
            ]

            val currentGame = current.game

            // Пока попытка активна, новая головоломка
            // использует зафиксированный режим ошибок.
            val mode =
                if (
                    currentGame != null &&
                    !currentGame.isFinished
                ) {
                    currentGame.mistakeMode
                } else {
                    MistakeMode.entries[
                        ui.mistakeModeSpinner.selectedItemPosition
                    ]
                }

            if (
                currentGame != null &&
                !currentGame.isFinished
            ) {
                val attemptId = currentGame.gameId

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.new_game)
                    .setMessage(R.string.confirm_new_game)
                    .setNegativeButton(
                        R.string.cancel,
                        null
                    )
                    .setPositiveButton(
                        R.string.continue_action
                    ) { _, _ ->
                        if (
                            binding !== ui ||
                            viewModel.state.value.isBusy ||
                            viewModel.state.value.game?.gameId !=
                            attemptId
                        ) {
                            return@setPositiveButton
                        }

                        viewModel.newGame(
                            difficulty,
                            mode
                        )
                    }
                    .show()
            } else {
                viewModel.newGame(
                    difficulty,
                    mode
                )
            }
        }

        ui.continueGameButton.setOnClickListener {
            val current = viewModel.state.value

            if (
                !current.isBusy &&
                current.game != null
            ) {
                (requireActivity() as MainActivity)
                    .openGame()
            }
        }

        ui.finishCurrentGameButton.setOnClickListener {
            showFinishCurrentGameDialog(ui)
        }

        ui.previousResultsButton.setOnClickListener {
            if (!viewModel.state.value.isBusy) {
                (requireActivity() as MainActivity)
                    .openResults()
            }
        }
    }

    private fun showFinishCurrentGameDialog(
        ui: FragmentStartBinding
    ) {
        val current = viewModel.state.value
        val game = current.game ?: return

        if (
            current.isBusy ||
            game.isFinished ||
            finishGameDialog?.isShowing == true
        ) {
            return
        }

        val attemptId = game.gameId

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.finish_current_game)
            .setMessage(R.string.confirm_finish_current_game)
            .setNegativeButton(
                R.string.cancel,
                null
            )
            .setPositiveButton(
                R.string.finish_current_game_action
            ) { _, _ ->
                if (binding !== ui) {
                    return@setPositiveButton
                }

                viewModel.finishCurrentGame(
                    expectedGameId = attemptId
                )
            }
            .create()

        finishGameDialog = dialog

        dialog.setOnDismissListener {
            if (finishGameDialog === dialog) {
                finishGameDialog = null
            }
        }

        dialog.show()
    }

    private fun observeState(
        ui: FragmentStartBinding
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(
                Lifecycle.State.RESUMED
            ) {
                viewModel.state.collect { state ->
                    val game = state.game

                    val modeLocked =
                        game?.isFinished == false

                    ui.startProgress.isVisible =
                        state.isBusy

                    ui.difficultySpinner.isEnabled =
                        !state.isBusy

                    ui.mistakeModeSpinner.isEnabled =
                        !state.isBusy && !modeLocked

                    ui.mistakeModeNotice.isVisible =
                        modeLocked

                    if (modeLocked && game != null) {
                        val position =
                            MistakeMode.entries.indexOf(
                                game.mistakeMode
                            )

                        if (
                            ui.mistakeModeSpinner
                                .selectedItemPosition != position
                        ) {
                            ui.mistakeModeSpinner.setSelection(
                                position
                            )
                        }
                    }

                    ui.startGameButton.isEnabled =
                        !state.isBusy

                    ui.previousResultsButton.isEnabled =
                        !state.isBusy

                    ui.continueGameButton.isVisible =
                        game != null

                    ui.continueGameButton.isEnabled =
                        !state.isBusy && game != null

                    ui.continueGameButton.setText(
                        if (game?.isFinished == true) {
                            R.string.return_to_game
                        } else {
                            R.string.continue_game
                        }
                    )

                    ui.finishCurrentGameButton.isVisible =
                        modeLocked

                    ui.finishCurrentGameButton.isEnabled =
                        !state.isBusy && modeLocked

                    ui.startStatusText.text = when {
                        state.isBusy ->
                            getString(R.string.preparing)

                        state.error != null ->
                            state.error

                        else ->
                            getString(R.string.start_description)
                    }

                    if (
                        state.openGameRequested &&
                        !state.isBusy
                    ) {
                        val opened =
                            (requireActivity() as MainActivity)
                                .openGame()

                        if (opened) {
                            viewModel.consumeOpenGameRequest()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        finishGameDialog?.dismiss()
        finishGameDialog = null

        binding = null

        super.onDestroyView()
    }
}
