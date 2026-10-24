package com.example.sudoku.presentation

import android.content.Context
import com.example.sudoku.R
import com.example.sudoku.domain.Difficulty
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun formatGameTime(millis: Long): String {
    val seconds = millis / 1000
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val remainingSeconds = seconds % 60

    return if (hours > 0) {
        String.format(
            Locale.getDefault(),
            "%d:%02d:%02d",
            hours,
            minutes,
            remainingSeconds
        )
    } else {
        String.format(
            Locale.getDefault(),
            "%02d:%02d",
            minutes,
            remainingSeconds
        )
    }
}

fun formatCompletionDate(millis: Long): String =
    DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT
    ).format(Date(millis))

fun Context.difficultyName(difficulty: Difficulty): String =
    getString(
        when (difficulty) {
            Difficulty.EASY -> R.string.difficulty_easy
            Difficulty.MEDIUM -> R.string.difficulty_medium
            Difficulty.HARD -> R.string.difficulty_hard
            Difficulty.EXPERT -> R.string.difficulty_expert
            Difficulty.MASTER -> R.string.difficulty_master
            Difficulty.EXTREME -> R.string.difficulty_extreme
        }
    )
