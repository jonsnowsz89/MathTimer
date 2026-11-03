package com.mathteacher.timer.timer

import com.mathteacher.timer.model.Phase

/**
 * Initial plan for a session with [remainingMin] minutes left.
 * New Phase Duration = (remainingMin / 50) * original duration.
 */
object ClassPlan {
    const val BENCHMARK_MIN = 50
    private val base = listOf(                       // original durations, minutes
        "دخول أستاذ وعنوان الدرس" to 3,
        "تهيئة" to 5,
        "الوضعية التعلمية – محاولة" to 10,
        "الوضعية التعلمية – حل على السبورة" to 10,
        "حوصلة" to 10,
        "مثال" to 5,
        "إعادة استثمار – محاولة" to 5,
        "إعادة استثمار – حل على السبورة" to 5
    )
    private const val APPLICATION = "تمرين تطبيقي"
    private const val RESERVE_FRACTION = 0.10

    /** NOTE: the fixed phases sum to 53 min, so with the exact formula the application exercise gets 0 min. */
    fun build(remainingMin: Int, reserveApp: Boolean): List<Phase> {
        val remMs = remainingMin * 60_000L
        val scale = if (reserveApp) remMs * (1 - RESERVE_FRACTION) / (base.sumOf { it.second } * 60_000.0)
                    else remainingMin.toDouble() / BENCHMARK_MIN
        val phases = base.map { (n, m) -> Phase(n, (m * 60_000 * scale).toLong()) }.toMutableList()
        phases += Phase(APPLICATION, (remMs - phases.sumOf { it.durationMs }).coerceAtLeast(0))
        return phases
    }
}
