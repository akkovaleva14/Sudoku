package com.example.sudoku.presentation

import android.content.Context
import com.example.sudoku.R
import com.example.sudoku.domain.MistakeMode

fun Context.mistakeModeName(
    mode: MistakeMode
): String = getString(
    when (mode) {
        MistakeMode.NO_MISTAKES ->
            R.string.mistake_mode_none

        MistakeMode.TWO_MISTAKES ->
            R.string.mistake_mode_two

        MistakeMode.UNLIMITED ->
            R.string.mistake_mode_unlimited
    }
)
