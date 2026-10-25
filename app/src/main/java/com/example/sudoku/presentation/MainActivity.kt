package com.example.sudoku.presentation

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.example.sudoku.R
import com.example.sudoku.databinding.ActivityMainBinding

class MainActivity : FragmentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(
            AppTheme.wrapContext(newBase)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Sudoku)

        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureInsets(binding)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .replace(
                    R.id.fragmentContainer,
                    StartFragment()
                )
                .commit()
        }
    }

    fun setDarkTheme(enabled: Boolean) {
        if (AppTheme.isDarkEnabled(this) == enabled) {
            return
        }

        if (
            isFinishing ||
            isDestroyed ||
            supportFragmentManager.isStateSaved
        ) {
            return
        }

        AppTheme.setDarkEnabled(this, enabled)

        recreate()
    }

    private fun configureInsets(binding: ActivityMainBinding) {
        val root = binding.root

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
            )

            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                bars.bottom
            )

            insets
        }

        val isDark =
            (
                    resources.configuration.uiMode and
                            Configuration.UI_MODE_NIGHT_MASK
                    ) == Configuration.UI_MODE_NIGHT_YES

        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }

        ViewCompat.requestApplyInsets(root)
    }

    fun openGame(): Boolean {
        return openScreen(
            fragment = GameFragment(),
            backStackName = "game"
        )
    }

    fun openResults(): Boolean {
        return openScreen(
            fragment = ResultsFragment(),
            backStackName = "results"
        )
    }

    fun openStart() {
        val manager = supportFragmentManager

        if (manager.isStateSaved) return

        manager.executePendingTransactions()

        manager.popBackStackImmediate(
            null,
            FragmentManager.POP_BACK_STACK_INCLUSIVE
        )

        val current = manager.findFragmentById(
            R.id.fragmentContainer
        )

        if (current !is StartFragment) {
            manager.beginTransaction()
                .setReorderingAllowed(true)
                .replace(
                    R.id.fragmentContainer,
                    StartFragment()
                )
                .commit()
        }
    }

    private fun openScreen(
        fragment: Fragment,
        backStackName: String
    ): Boolean {
        val manager = supportFragmentManager

        if (manager.isStateSaved) return false

        manager.executePendingTransactions()

        val current = manager.findFragmentById(
            R.id.fragmentContainer
        )

        if (current?.javaClass == fragment.javaClass) {
            return true
        }

        manager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(
                R.id.fragmentContainer,
                fragment
            )
            .addToBackStack(backStackName)
            .commit()

        return true
    }
}
