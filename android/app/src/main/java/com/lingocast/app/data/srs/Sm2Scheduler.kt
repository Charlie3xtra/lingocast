package com.lingocast.app.data.srs

import com.lingocast.app.data.db.SavedWordEntity
import kotlin.math.max
import kotlin.math.roundToInt

enum class ReviewGrade(val quality: Int, val labelZh: String) {
    AGAIN(1, "再记一遍"),
    HARD(3, "困难"),
    GOOD(4, "良好"),
    EASY(5, "熟练")
}

object Sm2Scheduler {
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun review(word: SavedWordEntity, grade: ReviewGrade, nowMs: Long = System.currentTimeMillis()): SavedWordEntity {
        val q = grade.quality
        var repetitions = word.repetitions
        var interval = word.intervalDays
        var ef = word.easeFactor

        if (q < 3) {
            repetitions = 0
            interval = 0
        } else {
            interval = when (repetitions) {
                0 -> 1
                1 -> 6
                else -> max(1, (interval * ef).roundToInt())
            }
            if (grade == ReviewGrade.EASY && interval == 1) {
                interval = 3
            }
            repetitions += 1
        }

        val delta = 0.1f - (5 - q) * (0.08f + (5 - q) * 0.02f)
        ef = max(1.3f, ef + delta)

        val nextReviewAt = if (interval == 0) {
            nowMs + 10L * 60L * 1000L // 10 min short-term re-queue
        } else {
            nowMs + interval * DAY_MS
        }

        val mastery = when {
            repetitions == 0 -> 1
            interval >= 14 -> 3
            interval >= 3 -> 2
            else -> 1
        }

        return word.copy(
            intervalDays = interval,
            easeFactor = ef,
            repetitions = repetitions,
            nextReviewAt = nextReviewAt,
            masteryLevel = mastery
        )
    }
}
