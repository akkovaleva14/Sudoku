package com.example.sudoku.data

import android.content.SharedPreferences
import com.example.sudoku.domain.Difficulty
import com.example.sudoku.domain.GameRepository
import com.example.sudoku.domain.GameResult
import com.example.sudoku.domain.HelpStatistics
import com.example.sudoku.domain.MistakeMode
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
                require(game.mistakeCount >= 0)

                val deletedIds = readDeletedIds()
                val results = readResults().toMutableList()

                game.result?.let { result ->
                    require(validResult(result))
                    require(result.gameId == game.gameId)
                    require(result.mistakeMode == game.mistakeMode)

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
                        put(encodeResult(it))
                    }
                }.toString()
            )

        if (game != null) {
            editor.putString(
                GAME_KEY,
                encodeGame(game).toString()
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

        return try {
            JSONObject(saved)
                .optJSONObject("result")
                ?.optString("gameId")
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeGame(saved: String): SudokuGame {
        val json = JSONObject(saved)
        val version = json.getInt("version")

        require(version in 1..VERSION)

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

        val gameId =
            if (version == 1) {
                UUID.nameUUIDFromBytes(
                    saved.toByteArray(Charsets.UTF_8)
                ).toString()
            } else {
                json.getString("gameId")
            }

        val help =
            if (version == 1) {
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

        val mistakeCount =
            if (version >= 3) {
                json.getInt("mistakeCount")
            } else {
                0
            }

        val mistakesTrackedFromStart =
            if (version >= 3) {
                json.getBoolean("mistakesTrackedFromStart")
            } else {
                false
            }

        val mistakeMode =
            if (version >= 4) {
                MistakeMode.valueOf(
                    json.getString("mistakeMode")
                )
            } else {
                MistakeMode.UNLIMITED
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
            result = result,
            mistakeCount = mistakeCount,
            mistakesTrackedFromStart = mistakesTrackedFromStart,
            mistakeMode = mistakeMode
        )

        require(SudokuRules.isValidGame(game))
        require(game.gameId.isNotBlank())
        require(validHelp(game.help))
        require(game.mistakeCount >= 0)

        if (result != null) {
            require(validResult(result))
            require(result.gameId == game.gameId)
            require(result.difficulty == puzzle.difficulty)
            require(result.mistakeMode == game.mistakeMode)
            require(
                result.clues == puzzle.givens.count { it != 0 }
            )
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
            put("mistakeMode", game.mistakeMode.name)
            put("mistakeCount", game.mistakeCount)

            put(
                "mistakesTrackedFromStart",
                game.mistakesTrackedFromStart
            )

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
                                put(
                                    "values",
                                    toJson(snapshot.values)
                                )
                                put(
                                    "notes",
                                    toJson(snapshot.notes)
                                )
                                put(
                                    "selected",
                                    snapshot.selected
                                )
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

        val array = JSONArray(saved)
        val deletedIds = readDeletedIds()

        return List(array.length()) { index ->
            decodeResult(
                array.getJSONObject(index)
            ).also {
                require(validResult(it))
            }
        }
            .distinctBy { it.gameId }
            .filterNot { it.gameId in deletedIds }
    }

    private fun encodeHelp(
        help: HelpStatistics
    ): JSONObject =
        JSONObject().apply {
            put("logicalHints", help.logicalHints)
            put("notesHints", help.notesHints)
            put("automaticFills", help.automaticFills)
            put("usedBeforeTracking", help.usedBeforeTracking)
        }

    private fun decodeHelp(
        json: JSONObject
    ): HelpStatistics =
        HelpStatistics(
            logicalHints = json.getInt("logicalHints"),
            notesHints = json.getInt("notesHints"),
            automaticFills = json.getInt("automaticFills"),
            usedBeforeTracking = json.getBoolean(
                "usedBeforeTracking"
            )
        )

    private fun encodeResult(
        result: GameResult
    ): JSONObject =
        JSONObject().apply {
            put("gameId", result.gameId)
            put("difficulty", result.difficulty.name)
            put("elapsedMillis", result.elapsedMillis)
            put("completedAtMillis", result.completedAtMillis)
            put("clues", result.clues)
            put("help", encodeHelp(result.help))
            put("mistakeCount", result.mistakeCount)

            put(
                "mistakesTrackedFromStart",
                result.mistakesTrackedFromStart
            )

            put("mistakeMode", result.mistakeMode.name)
            put("isVictory", result.isVictory)
        }

    private fun decodeResult(
        json: JSONObject
    ): GameResult {
        val hasMistakeStatistics =
            json.has("mistakeCount") &&
                    json.has("mistakesTrackedFromStart")

        return GameResult(
            gameId = json.getString("gameId"),
            difficulty = Difficulty.valueOf(
                json.getString("difficulty")
            ),
            elapsedMillis = json.getLong("elapsedMillis"),
            completedAtMillis = json.getLong("completedAtMillis"),
            clues = json.getInt("clues"),
            help = decodeHelp(json.getJSONObject("help")),
            mistakeCount =
                if (hasMistakeStatistics) {
                    json.getInt("mistakeCount")
                } else {
                    0
                },
            mistakesTrackedFromStart =
                if (hasMistakeStatistics) {
                    json.getBoolean("mistakesTrackedFromStart")
                } else {
                    false
                },
            mistakeMode = MistakeMode.valueOf(
                json.optString(
                    "mistakeMode",
                    MistakeMode.UNLIMITED.name
                )
            ),

            // Старые результаты всегда были победами.
            isVictory = json.optBoolean("isVictory", true)
        )
    }

    private fun validHelp(help: HelpStatistics): Boolean =
        help.logicalHints >= 0 &&
                help.notesHints >= 0 &&
                help.automaticFills >= 0

    private fun validResult(result: GameResult): Boolean {
        if (
            result.gameId.isBlank() ||
            result.elapsedMillis < 0L ||
            result.completedAtMillis <= 0L ||
            result.clues !in 0..81 ||
            result.mistakeCount < 0 ||
            !validHelp(result.help)
        ) {
            return false
        }

        val limit = result.mistakeMode.allowedMistakes

        if (limit != null) {
            if (!result.mistakesTrackedFromStart) {
                return false
            }

            return if (result.isVictory) {
                result.mistakeCount <= limit
            } else {
                result.mistakeCount > limit
            }
        }

        return result.isVictory
    }

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
        const val DELETED_RESULTS_KEY = "sudoku_deleted_results"
        const val VERSION = 4
    }
}
