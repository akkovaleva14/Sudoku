package com.example.sudoku.presentation

import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.sudoku.R
import com.example.sudoku.SudokuApplication
import com.example.sudoku.databinding.FragmentResultsBinding
import com.example.sudoku.databinding.ItemResultBinding
import com.example.sudoku.domain.GameResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ResultsFragment : Fragment(R.layout.fragment_results) {

    private var binding: FragmentResultsBinding? = null
    private var deletionDialog: AlertDialog? = null
    private var itemTouchHelper: ItemTouchHelper? = null

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        val ui = FragmentResultsBinding.bind(view)
        binding = ui

        val repository =
            (requireActivity().application as SudokuApplication)
                .container.resultsRepository

        var deletionInProgress = false
        var loading = true
        var resultsAvailable = false

        val adapter = ResultsAdapter()

        fun canDelete(): Boolean =
            binding === ui &&
                    !loading &&
                    !deletionInProgress &&
                    resultsAvailable &&
                    deletionDialog == null

        fun renderControls() {
            if (binding !== ui) return

            ui.resultsProgress.isVisible =
                loading || deletionInProgress

            ui.clearResultsButton.isEnabled =
                canDelete() && adapter.itemCount > 0
        }

        fun performDeletion(
            action: suspend () -> Unit
        ) {
            if (
                binding !== ui ||
                loading ||
                deletionInProgress ||
                !resultsAvailable
            ) {
                return
            }

            deletionInProgress = true
            renderControls()

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    action()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    if (binding === ui) {
                        Toast.makeText(
                            ui.root.context,
                            R.string.error_delete_results,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } finally {
                    deletionInProgress = false
                    renderControls()
                }
            }
        }

        fun showDeletionDialog(
            result: GameResult?
        ) {
            if (!canDelete()) {
                result?.let {
                    adapter.restoreItem(it.gameId)
                }
                return
            }

            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(
                    if (result == null) {
                        R.string.clear_results
                    } else {
                        R.string.delete_result
                    }
                )
                .setMessage(
                    if (result == null) {
                        R.string.confirm_clear_results
                    } else {
                        R.string.confirm_delete_result
                    }
                )
                .setNegativeButton(
                    R.string.cancel,
                    null
                )
                .setPositiveButton(
                    R.string.delete_action
                ) { _, _ ->
                    performDeletion {
                        if (result == null) {
                            repository.clearResults()
                        } else {
                            repository.deleteResult(
                                result.gameId
                            )
                        }
                    }
                }
                .create()

            deletionDialog = dialog

            dialog.setOnDismissListener {
                if (deletionDialog === dialog) {
                    deletionDialog = null
                }

                if (binding === ui) {
                    // Возвращаем свайпнутую строку.
                    // Если результат удалён, обновление
                    // репозитория уберёт его из списка.
                    result?.let {
                        adapter.restoreItem(it.gameId)
                    }

                    renderControls()
                }
            }

            renderControls()
            dialog.show()
        }

        ui.resultsList.layoutManager =
            LinearLayoutManager(requireContext())

        ui.resultsList.adapter = adapter

        val backgroundPaint = Paint().apply {
            color = Color.rgb(198, 40, 40)
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize =
                16f * resources.displayMetrics.scaledDensity
            textAlign = Paint.Align.RIGHT
        }

        val horizontalPadding =
            24f * resources.displayMetrics.density

        val swipeCallback =
            object : ItemTouchHelper.SimpleCallback(
                0,
                ItemTouchHelper.LEFT
            ) {
                override fun getSwipeDirs(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ): Int {
                    return if (canDelete()) {
                        ItemTouchHelper.LEFT
                    } else {
                        0
                    }
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean = false

                override fun onSwiped(
                    viewHolder: RecyclerView.ViewHolder,
                    direction: Int
                ) {
                    // Берём именно результат, отображавшийся
                    // в строке, а не элемент по старой позиции.
                    val holder =
                        viewHolder as ResultsAdapter.ResultViewHolder

                    val result = holder.boundResult

                    if (result == null) {
                        adapter.notifyDataSetChanged()
                        return
                    }

                    showDeletionDialog(result)
                }

                override fun onChildDraw(
                    canvas: Canvas,
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    dX: Float,
                    dY: Float,
                    actionState: Int,
                    isCurrentlyActive: Boolean
                ) {
                    if (
                        actionState ==
                        ItemTouchHelper.ACTION_STATE_SWIPE &&
                        dX < 0f
                    ) {
                        val item = viewHolder.itemView

                        val saveCount = canvas.save()

                        // Рисуем только в открывшейся
                        // справа части строки.
                        canvas.clipRect(
                            item.right.toFloat() + dX,
                            item.top.toFloat(),
                            item.right.toFloat(),
                            item.bottom.toFloat()
                        )

                        canvas.drawRect(
                            item.left.toFloat(),
                            item.top.toFloat(),
                            item.right.toFloat(),
                            item.bottom.toFloat(),
                            backgroundPaint
                        )

                        val baseline =
                            (item.top + item.bottom) / 2f -
                                    (
                                            textPaint.ascent() +
                                                    textPaint.descent()
                                            ) / 2f

                        canvas.drawText(
                            getString(R.string.delete_action),
                            item.right.toFloat() -
                                    horizontalPadding,
                            baseline,
                            textPaint
                        )

                        canvas.restoreToCount(saveCount)
                    }

                    super.onChildDraw(
                        canvas,
                        recyclerView,
                        viewHolder,
                        dX,
                        dY,
                        actionState,
                        isCurrentlyActive
                    )
                }
            }

        itemTouchHelper = ItemTouchHelper(
            swipeCallback
        ).also {
            it.attachToRecyclerView(ui.resultsList)
        }

        ui.backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        ui.clearResultsButton.setOnClickListener {
            if (!canDelete() || adapter.itemCount == 0) {
                return@setOnClickListener
            }

            showDeletionDialog(result = null)
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

                        ui.emptyText.isVisible =
                            results.isEmpty()

                        ui.emptyText.setText(
                            R.string.results_empty
                        )

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
                    ui.emptyText.setText(
                        R.string.error_results
                    )
                    ui.summaryText.text = ""

                    renderControls()
                }
            }
        }
    }

    override fun onDestroyView() {
        // Сначала запрещаем обращения к старой разметке,
        // затем закрываем диалог и отключаем свайпы.
        val ui = binding
        binding = null

        deletionDialog?.dismiss()
        deletionDialog = null

        itemTouchHelper?.attachToRecyclerView(null)
        itemTouchHelper = null

        ui?.resultsList?.adapter = null

        super.onDestroyView()
    }

    private class ResultsAdapter :
        RecyclerView.Adapter<ResultsAdapter.ResultViewHolder>() {

        private var items: List<GameResult> = emptyList()

        fun submit(results: List<GameResult>) {
            items = results.toList()
            notifyDataSetChanged()
        }

        fun restoreItem(gameId: String) {
            val position = items.indexOfFirst {
                it.gameId == gameId
            }

            if (position != -1) {
                notifyItemChanged(position)
            }
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): ResultViewHolder {
            val ui = ItemResultBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

            return ResultViewHolder(ui)
        }

        override fun onBindViewHolder(
            holder: ResultViewHolder,
            position: Int
        ) {
            holder.bind(items[position])
        }

        class ResultViewHolder(
            private val ui: ItemResultBinding
        ) : RecyclerView.ViewHolder(ui.root) {

            var boundResult: GameResult? = null
                private set

            fun bind(result: GameResult) {
                boundResult = result

                val context = ui.root.context

                val title = context.getString(
                    R.string.result_title,
                    context.difficultyName(
                        result.difficulty
                    ),
                    formatGameTime(
                        result.elapsedMillis
                    )
                )

                val outcome = context.getString(
                    if (result.isVictory) {
                        R.string.result_victory
                    } else {
                        R.string.result_defeat
                    }
                )

                ui.resultTitle.text =
                    "$outcome · $title"

                ui.resultDate.text =
                    formatCompletionDate(
                        result.completedAtMillis
                    )

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
                    context.mistakeModeName(
                        result.mistakeMode
                    )
                )

                ui.resultDetails.text =
                    "$details\n$mistakeDetails\n$modeDetails"

                ui.trackingNotice.isVisible =
                    result.help.usedBeforeTracking
            }
        }
    }
}
