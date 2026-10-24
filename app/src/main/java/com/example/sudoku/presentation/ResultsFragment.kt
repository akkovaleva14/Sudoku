package com.example.sudoku.presentation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
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
        val adapter = ResultsAdapter()

        ui.resultsList.adapter = adapter

        ui.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val repository =
            (requireActivity().application as SudokuApplication)
                .container.resultsRepository

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(
                Lifecycle.State.STARTED
            ) {
                ui.resultsProgress.isVisible = true

                try {
                    repository.observeResults().collect { results ->
                        adapter.submit(results)

                        ui.resultsProgress.isVisible = false
                        ui.emptyText.isVisible = results.isEmpty()
                        ui.emptyText.setText(R.string.results_empty)

                        ui.summaryText.text = getString(
                            R.string.results_count,
                            results.size
                        )
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    ui.resultsProgress.isVisible = false
                    ui.emptyText.isVisible = true
                    ui.emptyText.setText(R.string.error_results)
                }
            }
        }
    }

    private class ResultsAdapter : BaseAdapter() {

        private var items: List<GameResult> = emptyList()

        fun submit(results: List<GameResult>) {
            items = results
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
            val ui = if (convertView == null) {
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

            ui.resultTitle.text = context.getString(
                R.string.result_title,
                context.difficultyName(result.difficulty),
                formatGameTime(result.elapsedMillis)
            )

            ui.resultDate.text =
                formatCompletionDate(result.completedAtMillis)

            ui.resultDetails.text = context.getString(
                R.string.result_details,
                result.clues,
                result.help.logicalHints,
                result.help.notesHints,
                result.help.automaticFills
            )

            ui.trackingNotice.isVisible =
                result.help.usedBeforeTracking

            return ui.root
        }
    }
}
