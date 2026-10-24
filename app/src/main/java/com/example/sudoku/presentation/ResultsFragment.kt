package com.example.sudoku.presentation

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.sudoku.R
import com.example.sudoku.SudokuApplication
import com.example.sudoku.databinding.FragmentResultsBinding
import com.example.sudoku.databinding.ItemResultBinding
import com.example.sudoku.domain.GameResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ResultsFragment : Fragment(R.layout.fragment_results) {

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        val ui = FragmentResultsBinding.bind(view)

        val repository =
            (requireActivity().application as SudokuApplication)
                .container.resultsRepository

        var deletionInProgress = false
        var loading = true
        var resultsAvailable = false

        lateinit var adapter: ResultsAdapter

        fun renderControls() {
            ui.resultsProgress.isVisible =
                loading || deletionInProgress

            ui.clearResultsButton.isEnabled =
                !loading &&
                        !deletionInProgress &&
                        resultsAvailable &&
                        adapter.count > 0

            adapter.setDeletionEnabled(
                !loading &&
                        !deletionInProgress &&
                        resultsAvailable
            )
        }

        fun performDeletion(
            action: suspend () -> Unit
        ) {
            if (deletionInProgress || !resultsAvailable) return

            deletionInProgress = true
            renderControls()

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    action()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    Toast.makeText(
                        ui.root.context,
                        R.string.error_delete_results,
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    deletionInProgress = false
                    renderControls()
                }
            }
        }

        adapter = ResultsAdapter { result ->
            if (deletionInProgress || !resultsAvailable) {
                return@ResultsAdapter
            }

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.delete_result)
                .setMessage(R.string.confirm_delete_result)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                    R.string.delete_action
                ) { _, _ ->
                    performDeletion {
                        repository.deleteResult(result.gameId)
                    }
                }
                .show()
        }

        ui.resultsList.adapter = adapter

        ui.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        ui.clearResultsButton.setOnClickListener {
            if (deletionInProgress || adapter.count == 0) {
                return@setOnClickListener
            }

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.clear_results)
                .setMessage(R.string.confirm_clear_results)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                    R.string.delete_action
                ) { _, _ ->
                    performDeletion {
                        repository.clearResults()
                    }
                }
                .show()
        }

        renderControls()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(
                Lifecycle.State.STARTED
            ) {
                loading = true
                resultsAvailable = false
                renderControls()

                try {
                    repository.observeResults().collect { results ->
                        adapter.submit(results)

                        loading = false
                        resultsAvailable = true

                        ui.emptyText.isVisible = results.isEmpty()
                        ui.emptyText.setText(R.string.results_empty)

                        ui.summaryText.text = getString(
                            R.string.results_count,
                            results.size
                        )

                        renderControls()
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    loading = false
                    resultsAvailable = false

                    adapter.submit(emptyList())

                    ui.emptyText.isVisible = true
                    ui.emptyText.setText(R.string.error_results)
                    ui.summaryText.text = ""

                    renderControls()
                }
            }
        }
    }

    private class ResultsAdapter(
        private val onDelete: (GameResult) -> Unit
    ) : BaseAdapter() {

        private var items: List<GameResult> = emptyList()
        private var deletionEnabled = false

        fun submit(results: List<GameResult>) {
            items = results
            notifyDataSetChanged()
        }

        fun setDeletionEnabled(enabled: Boolean) {
            if (deletionEnabled == enabled) return

            deletionEnabled = enabled
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): GameResult =
            items[position]

        override fun getItemId(position: Int): Long =
            position.toLong()

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup
        ): View {
            val ui =
                if (convertView == null) {
                    ItemResultBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false
                    ).also {
                        it.root.tag = it
                    }
                } else {
                    convertView.tag as ItemResultBinding
                }

            val result = getItem(position)
            val context = parent.context

            val title = context.getString(
                R.string.result_title,
                context.difficultyName(result.difficulty),
                formatGameTime(result.elapsedMillis)
            )

            val outcome = context.getString(
                if (result.isVictory) {
                    R.string.result_victory
                } else {
                    R.string.result_defeat
                }
            )

            ui.resultTitle.text = "$outcome · $title"

            ui.resultDate.text =
                formatCompletionDate(result.completedAtMillis)

            val details = context.getString(
                R.string.result_details,
                result.clues,
                result.help.logicalHints,
                result.help.notesHints,
                result.help.automaticFills
            )

            val mistakeDetails = when {
                result.mistakesTrackedFromStart -> {
                    context.getString(
                        R.string.mistakes_count,
                        result.mistakeCount
                    )
                }

                result.mistakeCount > 0 -> {
                    context.getString(
                        R.string.mistakes_count_since_update,
                        result.mistakeCount
                    )
                }

                else -> {
                    context.getString(
                        R.string.mistakes_not_fully_tracked
                    )
                }
            }

            val modeDetails = context.getString(
                R.string.game_mistake_mode,
                context.mistakeModeName(result.mistakeMode)
            )

            ui.resultDetails.text =
                "$details\n$mistakeDetails\n$modeDetails"

            ui.trackingNotice.isVisible =
                result.help.usedBeforeTracking

            ui.deleteResultButton.isEnabled = deletionEnabled

            ui.deleteResultButton.contentDescription =
                context.getString(
                    R.string.delete_result_description,
                    ui.resultTitle.text.toString(),
                    ui.resultDate.text.toString()
                )

            ui.deleteResultButton.setOnClickListener {
                if (deletionEnabled) {
                    onDelete(result)
                }
            }

            return ui.root
        }
    }
}
