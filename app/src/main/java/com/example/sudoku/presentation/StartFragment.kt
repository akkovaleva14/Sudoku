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
import kotlinx.coroutines.launch

class StartFragment : Fragment(R.layout.fragment_start) {

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

        ui.startGameButton.setOnClickListener {
            val difficulty = Difficulty.entries[
                ui.difficultySpinner.selectedItemPosition
            ]

            val current = viewModel.state.value

            if (current.isBusy) {
                return@setOnClickListener
            }

            if (current.game?.isFinished == false) {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.new_game)
                    .setMessage(R.string.confirm_new_game)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(
                        R.string.continue_action
                    ) { _, _ ->
                        viewModel.newGame(difficulty)
                    }
                    .show()
            } else {
                viewModel.newGame(difficulty)
            }
        }

        ui.continueGameButton.setOnClickListener {
            val current = viewModel.state.value

            if (!current.isBusy && current.game != null) {
                (requireActivity() as MainActivity).openGame()
            }
        }

        ui.previousResultsButton.setOnClickListener {
            (requireActivity() as MainActivity).openResults()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(
                Lifecycle.State.RESUMED
            ) {
                viewModel.state.collect { state ->
                    ui.startProgress.isVisible = state.isBusy

                    ui.difficultySpinner.isEnabled = !state.isBusy
                    ui.startGameButton.isEnabled = !state.isBusy
                    ui.previousResultsButton.isEnabled = !state.isBusy

                    ui.continueGameButton.isVisible =
                        state.game != null

                    ui.continueGameButton.isEnabled =
                        !state.isBusy && state.game != null

                    ui.continueGameButton.setText(
                        if (state.game?.isFinished == true) {
                            R.string.return_to_game
                        } else {
                            R.string.continue_game
                        }
                    )

                    ui.startStatusText.text = when {
                        state.isBusy ->
                            getString(R.string.preparing)

                        state.error != null ->
                            state.error

                        else ->
                            getString(R.string.start_description)
                    }

                    if (state.openGameRequested && !state.isBusy) {
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
}
