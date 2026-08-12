package com.fliptle.app

import kotlin.random.Random

/** A basic arithmetic question validated app-side. */
data class MathQuestion(val text: String, val answer: Int)

object MathQuestions {

    const val PER_DAY = 10

    fun generateSet(): List<MathQuestion> = List(PER_DAY) { generate() }

    private fun generate(): MathQuestion = when (Random.nextInt(3)) {
        0 -> {
            val a = Random.nextInt(2, 60)
            val b = Random.nextInt(2, 60)
            MathQuestion("$a + $b = ?", a + b)
        }
        1 -> {
            val a = Random.nextInt(10, 90)
            val b = Random.nextInt(1, a)
            MathQuestion("$a − $b = ?", a - b)
        }
        else -> {
            val a = Random.nextInt(2, 13)
            val b = Random.nextInt(2, 13)
            MathQuestion("$a × $b = ?", a * b)
        }
    }
}
