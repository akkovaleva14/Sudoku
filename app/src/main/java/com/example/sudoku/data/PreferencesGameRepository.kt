package com.example.sudoku.data

import android.content.SharedPreferences
import com.example.sudoku.domain.Difficulty
import com.example.sudoku.domain.GameRepository
import com.example.sudoku.domain.GameResult
import com.example.sudoku.domain.HelpStatistics
import com.example.sudoku.domain.MoveSnapshot
import com.example.sudoku.domain.ResultsRepository
import com.example.sudoku.domain.SudokuGame
import com.example.sudoku.domain.SudokuPuzzle
import com.example.sudoku.domain.SudokuRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
                    decodeGame(saved)
                } catch (_: Exception) {
                    // Повреждённая партия не блокирует запуск.
                    null
                }
            }
        }

    override suspend fun save(game: SudokuGame) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(SudokuRules.isValidGame(game))
                require(game.gameId.isNotBlank())
                require(validHelp(game.help))

                val results = readResults().toMutableList()

                game.result?.let { result ->
                    require(validResult(result))
                    require(result.gameId == game.gameId)

                    if (results.none { it.gameId == result.gameId }) {
                        results.add(result)
                    }
                }

                val sorted = results.sortedByDescending {
                    it.completedAtMillis
                }

                val saved = preferences.edit()
                    .putString(
                        GAME_KEY,
                        encodeGame(game).toString()
                    )
                    .putString(
                        RESULTS_KEY,
                        JSONArray().apply {
                            sorted.forEach {
                                put(encodeResult(it))
                            }
                        }.toString()
                    )
                    .commit()

                check(saved) {
                    "Не удалось сохранить игру"
                }

                // Публикуем только после успешной записи.
                resultsState.value = sorted
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

    private fun decodeGame(saved: String): SudokuGame {
        val json = JSONObject(saved)
        val version = json.getInt("version")

        require(version == 1 || version == VERSION)

        val puzzle = SudokuPuzzle(
            givens = readList(json.getJSONArray("givens")),
            solution = readList(json.getJSONArray("solution")),
            difficulty = Difficulty.valueOf(
                json.getString("difficulty")
            )
        )

        val historyJson = json.getJSONArray("history")
        require(historyJson.length() <= 100)

        val history = List(historyJson.length()) { index ->
            val item = historyJson.getJSONObject(index)

            MoveSnapshot(
                values = readList(item.getJSONArray("values")),
                notes = readList(item.getJSONArray("notes")),
                selected = item.getInt("selected")
            )
        }

        // Для старого сохранения ID детерминирован:
        // повторная загрузка до первой записи не меняет его.
        val gameId = if (version == 1) {
            UUID.nameUUIDFromBytes(
                saved.toByteArray(Charsets.UTF_8)
            ).toString()
        } else {
            json.getString("gameId")
        }

        val help = if (version == 1) {
            HelpStatistics(usedBeforeTracking = true)
        } else {
            decodeHelp(json.getJSONObject("help"))
        }

        val result =
            if (version >= 2 && !json.isNull("result")) {
                decodeResult(json.getJSONObject("result"))
            } else {
                null
            }

        val game = SudokuGame(
            puzzle = puzzle,
            values = readList(json.getJSONArray("values")),
            notes = readList(json.getJSONArray("notes")),
            selected = json.getInt("selected"),
            notesMode = json.getBoolean("notesMode"),
            elapsedMillis = json.getLong("elapsedMillis"),
            history = history,
            gameId = gameId,
            help = help,
            result = result
        )

        require(SudokuRules.isValidGame(game))
        require(game.gameId.isNotBlank())
        require(validHelp(game.help))

        if (result != null) {
            require(validResult(result))
            require(result.gameId == game.gameId)
            require(result.difficulty == puzzle.difficulty)
            require(result.clues == puzzle.givens.count { it != 0 })
        }

        return game
    }

    private fun encodeGame(game: SudokuGame): JSONObject =
        JSONObject().apply {
            put("version", VERSION)
            put("gameId", game.gameId)
            put("difficulty", game.puzzle.difficulty.name)
            put("givens", toJson(game.puzzle.givens))
            put("solution", toJson(game.puzzle.solution))
            put("values", toJson(game.values))
            put("notes", toJson(game.notes))
            put("selected", game.selected)
            put("notesMode", game.notesMode)
            put("elapsedMillis", game.elapsedMillis)
            put("help", encodeHelp(game.help))
            put(
                "result",
                game.result?.let { encodeResult(it) }
                    ?: JSONObject.NULL
            )

            put(
                "history",
                JSONArray().apply {
                    game.history.forEach { snapshot ->
                        put(
                            JSONObject().apply {
                                put("values", toJson(snapshot.values))
                                put("notes", toJson(snapshot.notes))
                                put("selected", snapshot.selected)
                            }
                        )
                    }
                }
            )
        }

    private fun readResults(): List<GameResult> {
        val saved =
            preferences.getString(RESULTS_KEY, null)
                ?: return emptyList()

        // Повреждение истории не затираем молча:
        // ошибка будет показана экраном или при сохранении.
        val array = JSONArray(saved)

        return List(array.length()) { index ->
            decodeResult(array.getJSONObject(index)).also {
                require(validResult(it))
            }
        }.distinctBy { it.gameId }
    }

    private fun encodeHelp(help: HelpStatistics): JSONObject =
        JSONObject().apply {
            put("logicalHints", help.logicalHints)
            put("notesHints", help.notesHints)
            put("automaticFills", help.automaticFills)
            put("usedBeforeTracking", help.usedBeforeTracking)
        }

    private fun decodeHelp(json: JSONObject): HelpStatistics =
        HelpStatistics(
            logicalHints = json.getInt("logicalHints"),
            notesHints = json.getInt("notesHints"),
            automaticFills = json.getInt("automaticFills"),
            usedBeforeTracking = json.getBoolean("usedBeforeTracking")
        )

    private fun encodeResult(result: GameResult): JSONObject =
        JSONObject().apply {
            put("gameId", result.gameId)
            put("difficulty", result.difficulty.name)
            put("elapsedMillis", result.elapsedMillis)
            put("completedAtMillis", result.completedAtMillis)
            put("clues", result.clues)
            put("help", encodeHelp(result.help))
        }

    private fun decodeResult(json: JSONObject): GameResult =
        GameResult(
            gameId = json.getString("gameId"),
            difficulty = Difficulty.valueOf(
                json.getString("difficulty")
            ),
            elapsedMillis = json.getLong("elapsedMillis"),
            completedAtMillis = json.getLong("completedAtMillis"),
            clues = json.getInt("clues"),
            help = decodeHelp(json.getJSONObject("help"))
        )

    private fun validHelp(help: HelpStatistics): Boolean =
        help.logicalHints >= 0 &&
                help.notesHints >= 0 &&
                help.automaticFills >= 0

    private fun validResult(result: GameResult): Boolean =
        result.gameId.isNotBlank() &&
                result.elapsedMillis >= 0L &&
                result.completedAtMillis > 0L &&
                result.clues in 0..81 &&
                validHelp(result.help)

    private fun toJson(values: List<Int>): JSONArray =
        JSONArray().apply {
            values.forEach { put(it) }
        }

    private fun readList(array: JSONArray): List<Int> {
        require(array.length() == 81)

        return List(81) { index ->
            array.getInt(index)
        }
    }

    private companion object {
        const val GAME_KEY = "sudoku_game"
        const val RESULTS_KEY = "sudoku_results"
        const val VERSION = 2
    }
}
