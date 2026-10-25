package com.example.sudoku.presentation

import android.content.Context
import android.content.res.Configuration

object AppTheme {

    private const val PREFERENCES_NAME = "sudoku_theme_preferences"
    private const val KEY_DARK_THEME = "dark_theme"

    fun isDarkEnabled(context: Context): Boolean {
        return context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).getBoolean(
            KEY_DARK_THEME,
            false
        )
    }

    fun setDarkEnabled(
        context: Context,
        enabled: Boolean
    ) {
        context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putBoolean(KEY_DARK_THEME, enabled)
            .apply()
    }

    fun wrapContext(base: Context): Context {
        val nightMode =
            if (isDarkEnabled(base)) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }

        val configuration = Configuration(
            base.resources.configuration
        ).apply {
            uiMode =
                (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        nightMode
        }

        return base.createConfigurationContext(configuration)
    }
}
