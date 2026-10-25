package com.example.sudoku.domain.model

enum class Difficulty(
    val title: String,
    val targetClues: Int
) {
    EASY("Лёгкая", 40),
    MEDIUM("Средняя", 34),
    HARD("Сложная", 28),
    EXPERT("Эксперт", 26),
    MASTER("Мастер", 24),
    EXTREME("Экстрим", 22)
}