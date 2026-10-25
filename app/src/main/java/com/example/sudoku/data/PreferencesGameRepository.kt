package com.example.sudoku.data

import android.content.SharedPreferences
import com.example.sudoku.domain.model.GameResult
import com.example.sudoku.domain.model.SudokuGame
import com.example.sudoku.domain.repository.GameRepository
import com.example.sudoku.domain.repository.ResultsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

class PreferencesGameRepository(
    private val preferences: SharedPreferences
) : GameRepository, ResultsRepository {

    private val mutex = Mutex()

    private val resultsState =
        MutableStateFlow<List<GameResult>>(emptyList())

    override suspend fun load(): SudokuGame? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val saved =
                    preferences.getString(GAME_KEY, null)
                        ?: return@withLock null

                try {
                    SudokuJsonCodec.decodeGame(saved)
                } catch (_: Exception) {
                    null
                }
            }
        }

    override suspend fun save(game: SudokuGame) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                SudokuJsonCodec.validateGame(game)

                val deletedIds = readDeletedIds()
                val results = readResults().toMutableList()

                game.result?.let { result ->
                    if (
                        result.gameId !in deletedIds &&
                        results.none {
                            it.gameId == result.gameId
                        }
                    ) {
                        results.add(result)
                    }
                }

                commitState(
                    results = results,
                    game = game
                )
            }
        }
    }

    override suspend fun clearCurrentGame() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // Удаляем только текущую партию.
                // История результатов остаётся без изменений.
                check(
                    preferences.edit()
                        .remove(GAME_KEY)
                        .commit()
                ) {
                    "Не удалось удалить текущую игру"
                }
            }
        }
    }

    override suspend fun deleteResult(gameId: String) {
        require(gameId.isNotBlank())

        withContext(Dispatchers.IO) {
            mutex.withLock {
                val deletedIds = readDeletedIds() + gameId

                val results = readResults().filterNot {
                    it.gameId == gameId
                }

                commitState(
                    results = results,
                    deletedIds = deletedIds
                )
            }
        }
    }

    override suspend fun clearResults() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val results = readResults()

                val deletedIds =
                    readDeletedIds().toMutableSet()

                results.forEach {
                    deletedIds.add(it.gameId)
                }

                // Результат текущей сохранённой партии
                // тоже нельзя повторно добавить в историю.
                storedResultGameId()?.let {
                    deletedIds.add(it)
                }

                commitState(
                    results = emptyList(),
                    deletedIds = deletedIds
                )
            }
        }
    }

    override fun observeResults(): Flow<List<GameResult>> =
        flow {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    resultsState.value = readResults()
                        .sortedByDescending {
                            it.completedAtMillis
                        }
                }
            }

            emitAll(resultsState)
        }

    // Вызывать только под mutex.
    private fun commitState(
        results: List<GameResult>,
        game: SudokuGame? = null,
        deletedIds: Set<String>? = null
    ) {
        val sorted = results
            .distinctBy { it.gameId }
            .sortedByDescending {
                it.completedAtMillis
            }

        val editor = preferences.edit()
            .putString(
                RESULTS_KEY,
                JSONArray().apply {
                    sorted.forEach {
                        put(SudokuJsonCodec.encodeResult(it))
                    }
                }.toString()
            )

        if (game != null) {
            editor.putString(
                GAME_KEY,
                SudokuJsonCodec.encodeGame(game).toString()
            )
        }

        if (deletedIds != null) {
            editor.putStringSet(
                DELETED_RESULTS_KEY,
                deletedIds.toSet()
            )
        }

        check(editor.commit()) {
            "Не удалось сохранить изменения"
        }

        // Обновляем экран только после успешной записи.
        resultsState.value = sorted
    }

    private fun readDeletedIds(): Set<String> =
        preferences.getStringSet(
            DELETED_RESULTS_KEY,
            emptySet()
        )?.toSet() ?: emptySet()

    private fun storedResultGameId(): String? {
        val saved =
            preferences.getString(GAME_KEY, null)
                ?: return null

        return SudokuJsonCodec.readStoredResultGameId(saved)
    }

    private fun readResults(): List<GameResult> {
        val saved =
            preferences.getString(RESULTS_KEY, null)
                ?: return emptyList()

        val array = JSONArray(saved)
        val deletedIds = readDeletedIds()

        return List(array.length()) { index ->
            SudokuJsonCodec.decodeResult(
                array.getJSONObject(index)
            )
        }
            .distinctBy { it.gameId }
            .filterNot { it.gameId in deletedIds }
    }

    private companion object {
        const val GAME_KEY = "sudoku_game"
        const val RESULTS_KEY = "sudoku_results"
        const val DELETED_RESULTS_KEY = "sudoku_deleted_results"
    }
}
