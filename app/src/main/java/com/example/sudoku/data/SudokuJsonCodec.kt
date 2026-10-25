package com.example.sudoku.data

import com.example.sudoku.domain.game.SudokuRules
import com.example.sudoku.domain.model.Difficulty
import com.example.sudoku.domain.model.GameResult
import com.example.sudoku.domain.model.HelpStatistics
import com.example.sudoku.domain.model.MistakeMode
import com.example.sudoku.domain.model.MoveSnapshot
import com.example.sudoku.domain.model.SudokuGame
import com.example.sudoku.domain.model.SudokuPuzzle
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object SudokuJsonCodec {

    private const val VERSION = 4

    fun validateGame(game: SudokuGame) {
        require(SudokuRules.isValidGame(game))
        require(game.gameId.isNotBlank())
        require(validHelp(game.help))
        require(game.mistakeCount >= 0)

        game.result?.let { result ->
            require(validResult(result))
            require(result.gameId == game.gameId)
            require(result.mistakeMode == game.mistakeMode)
        }
    }

    fun decodeGame(saved: String): SudokuGame {
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

        validateGame(game)

        if (result != null) {
            require(result.difficulty == puzzle.difficulty)
            require(
                result.clues == puzzle.givens.count { it != 0 }
            )
        }

        return game
    }

    fun encodeGame(game: SudokuGame): JSONObject =
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

    fun encodeResult(
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

    fun decodeResult(
        json: JSONObject
    ): GameResult {
        val hasMistakeStatistics =
            json.has("mistakeCount") &&
                    json.has("mistakesTrackedFromStart")

        val result = GameResult(
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

        require(validResult(result))

        return result
    }

    /**
     * Читаем только ID результата без декодирования всей партии.
     * Это позволяет защитить историю от повторного добавления
     * результата даже при повреждении других полей сохранения.
     */
    fun readStoredResultGameId(saved: String): String? {
        return try {
            JSONObject(saved)
                .optJSONObject("result")
                ?.optString("gameId")
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
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
}
