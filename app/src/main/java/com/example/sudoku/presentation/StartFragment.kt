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
    private var newGameDialog: AlertDialog? = null

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

        configureThemeSwitch(ui)

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

        val initialGame = viewModel.state.value.game

        if (initialGame != null && !initialGame.isFinished) {
            ui.difficultySpinner.setSelection(
                Difficulty.entries.indexOf(
                    initialGame.puzzle.difficulty
                )
            )
        }

        val initialMode =
            initialGame?.mistakeMode ?: MistakeMode.UNLIMITED

        ui.mistakeModeSpinner.setSelection(
            MistakeMode.entries.indexOf(initialMode)
        )

        // Сразу выставляем состояние настроек,
        // не дожидаясь запуска наблюдения.
        renderSettings(
            ui = ui,
            state = viewModel.state.value
        )

        configureActions(ui)
        observeState(ui)
    }

    private fun configureThemeSwitch(
        ui: FragmentStartBinding
    ) {
        // Сначала устанавливаем состояние,
        // затем подключаем слушатель.
        ui.darkThemeSwitch.isChecked =
            AppTheme.isDarkEnabled(requireContext())

        ui.darkThemeSwitch.isEnabled =
            !viewModel.state.value.isBusy

        ui.darkThemeSwitch.setOnCheckedChangeListener { _, enabled ->
            if (
                binding !== ui ||
                viewModel.state.value.isBusy
            ) {
                return@setOnCheckedChangeListener
            }

            (requireActivity() as MainActivity)
                .setDarkTheme(enabled)
        }
    }

    private fun renderSettings(
        ui: FragmentStartBinding,
        state: SudokuUiState
    ) {
        val game = state.game

        val settingsLocked =
            game != null && !game.isFinished

        val settingsEnabled =
            !state.isBusy && !settingsLocked

        ui.difficultySpinner.isEnabled =
            settingsEnabled

        ui.mistakeModeSpinner.isEnabled =
            settingsEnabled

        ui.difficultyLabel.isEnabled =
            settingsEnabled

        ui.mistakeModeLabel.isEnabled =
            settingsEnabled

        // Недоступные настройки заметно приглушены
        // как в светлой, так и в тёмной теме.
        val settingsAlpha =
            if (settingsEnabled) {
                1f
            } else {
                0.45f
            }

        ui.difficultySpinner.alpha =
            settingsAlpha

        ui.mistakeModeSpinner.alpha =
            settingsAlpha

        ui.difficultyLabel.alpha =
            settingsAlpha

        ui.mistakeModeLabel.alpha =
            settingsAlpha

        // Пояснение остаётся обычной яркости.
        ui.mistakeModeNotice.isVisible =
            settingsLocked

        // В заблокированных списках показываем
        // параметры текущей попытки.
        if (settingsLocked && game != null) {
            val difficultyPosition =
                Difficulty.entries.indexOf(
                    game.puzzle.difficulty
                )

            if (
                ui.difficultySpinner.selectedItemPosition !=
                difficultyPosition
            ) {
                ui.difficultySpinner.setSelection(
                    difficultyPosition
                )
            }

            val modePosition =
                MistakeMode.entries.indexOf(
                    game.mistakeMode
                )

            if (
                ui.mistakeModeSpinner.selectedItemPosition !=
                modePosition
            ) {
                ui.mistakeModeSpinner.setSelection(
                    modePosition
                )
            }
        }
    }

    private fun configureActions(
        ui: FragmentStartBinding
    ) {
        ui.startGameButton.setOnClickListener {
            val current = viewModel.state.value

            if (
                current.isBusy ||
                newGameDialog?.isShowing == true
            ) {
                return@setOnClickListener
            }

            val currentGame = current.game

            val hasActiveGame =
                currentGame != null && !currentGame.isFinished

            // При активной попытке сохраняем её параметры
            // при создании новой головоломки.
            val difficulty =
                if (hasActiveGame && currentGame != null) {
                    currentGame.puzzle.difficulty
                } else {
                    Difficulty.entries[
                        ui.difficultySpinner.selectedItemPosition
                    ]
                }

            val mode =
                if (hasActiveGame && currentGame != null) {
                    currentGame.mistakeMode
                } else {
                    MistakeMode.entries[
                        ui.mistakeModeSpinner.selectedItemPosition
                    ]
                }

            if (hasActiveGame && currentGame != null) {
                val attemptId = currentGame.gameId

                val dialog = AlertDialog.Builder(requireContext())
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
                            difficulty = difficulty,
                            mistakeMode = mode
                        )
                    }
                    .create()

                newGameDialog = dialog

                dialog.setOnDismissListener {
                    if (newGameDialog === dialog) {
                        newGameDialog = null
                    }
                }

                dialog.show()
            } else {
                viewModel.newGame(
                    difficulty = difficulty,
                    mistakeMode = mode
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
                var openingGame = false

                viewModel.state.collect { state ->
                    if (binding !== ui || openingGame) {
                        return@collect
                    }

                    // Обрабатываем переход ДО обновления меню.
                    // Пока транзакция выполняется, сохраняем
                    // предыдущий вид стартового экрана.
                    if (
                        state.openGameRequested &&
                        !state.isBusy
                    ) {
                        val opened =
                            (requireActivity() as MainActivity)
                                .openGame()

                        if (opened) {
                            // consumeOpenGameRequest() тоже меняет
                            // состояние. Блокируем последующую
                            // отрисовку до завершения перехода.
                            openingGame = true

                            viewModel.consumeOpenGameRequest()
                        }

                        return@collect
                    }

                    val game = state.game

                    val settingsLocked =
                        game != null && !game.isFinished

                    ui.startProgress.isVisible =
                        state.isBusy

                    ui.darkThemeSwitch.isEnabled =
                        !state.isBusy

                    renderSettings(
                        ui = ui,
                        state = state
                    )

                    ui.startGameButton.isEnabled =
                        !state.isBusy

                    ui.previousResultsButton.isEnabled =
                        !state.isBusy

                    ui.continueGameButton.isVisible =
                        game != null

                    ui.continueGameButton.isEnabled =
                        !state.isBusy && game != null

                    ui.continueGameButton.setText(
                        R.string.return_to_game
                    )

                    ui.finishCurrentGameButton.isVisible =
                        settingsLocked

                    ui.finishCurrentGameButton.isEnabled =
                        !state.isBusy && settingsLocked

                    ui.startStatusText.text = when {
                        state.isBusy ->
                            getString(R.string.preparing)

                        state.error != null ->
                            state.error

                        else ->
                            getString(R.string.start_description)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding?.darkThemeSwitch
            ?.setOnCheckedChangeListener(null)

        binding = null

        finishGameDialog?.dismiss()
        finishGameDialog = null

        newGameDialog?.dismiss()
        newGameDialog = null

        super.onDestroyView()
    }
}
