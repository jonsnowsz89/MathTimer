package com.mathteacher.timer.timer

import com.mathteacher.timer.model.*

data class Event(val atMs: Long, val kind: AlertKind)

/** Pure timeline logic (no Android). Times are "ms since session start", excluding paused time. */
interface Engine {
    val totalMs: Long
    fun stateAt(elapsed: Long): TimerState
    /** First alert strictly after [after], or null. Used to schedule the exact alarm. */
    fun nextEvent(after: Long): Event?
    /** Serialised mutable state, so a killed service can be restored. */
    fun snapshot(): String
}

class ClassEngine(private val ref: List<Phase>, snap: String? = null) : Engine {
    private val n = ref.size
    private val starts = LongArray(n)
    private val durs = LongArray(n)
    private var base = 0
    private var undo: Triple<Int, LongArray, LongArray>? = null
    private var undoUntil = 0L
    override val totalMs = ref.sumOf { it.durationMs }

    init {
        var t = 0L
        for (i in 0 until n) { starts[i] = t; durs[i] = ref[i].durationMs; t += durs[i] }
        snap?.split("|")?.takeIf { it.size == 3 }?.let { (b, s, d) ->
            base = b.toInt()
            s.split(",").forEachIndexed { i, v -> starts[i] = v.toLong() }
            d.split(",").forEachIndexed { i, v -> durs[i] = v.toLong() }
        }
    }

    @Synchronized override fun snapshot() = "$base|${starts.joinToString(",")}|${durs.joinToString(",")}"

    @Synchronized private fun indexAt(e: Long): Int {
        for (i in base until n) if (e < starts[i] + durs[i]) return i
        return n
    }

    @Synchronized override fun stateAt(elapsed: Long): TimerState {
        val i = indexAt(elapsed)
        if (i >= n) return TimerState(
            mode = Mode.CLASS, title = "انتهت الحصة", phaseIndex = n - 1, phaseCount = n,
            elapsedMs = elapsed, overtimeMs = elapsed - totalMs, finished = true, phaseProgress = 1f)
        val end = starts[i] + durs[i]
        return TimerState(
            mode = Mode.CLASS, title = ref[i].name, phaseIndex = i, phaseCount = n,
            phaseRemainingMs = end - elapsed, phaseDurationMs = durs[i], elapsedMs = elapsed,
            remainingMs = (totalMs - elapsed).coerceAtLeast(0), nextTitle = ref.getOrNull(i + 1)?.name,
            phaseProgress = (elapsed - starts[i]).toFloat() / durs[i],
            canUndo = undo != null && elapsed < undoUntil)
    }

    @Synchronized override fun nextEvent(after: Long): Event? {
        for (j in base until n) {
            if (durs[j] <= 0) continue
            val end = starts[j] + durs[j]
            if (end > after) return Event(end, if (end >= totalMs) AlertKind.END else AlertKind.PHASE)
        }
        return null
    }

    /** +1 next / -1 previous. Re-spreads the time left over this and later phases (weights = reference durations). */
    @Synchronized fun jump(delta: Int, e: Long): Boolean {
        val cur = indexAt(e)
        if (cur >= n) return false
        val target = (cur + delta).coerceIn(0, n - 1)
        val rem = totalMs - e
        if (target == cur || rem <= 0) return false
        undo = Triple(base, starts.copyOf(), durs.copyOf()); undoUntil = e + 15_000
        var sumW = 0L
        for (j in target until n) sumW += ref[j].durationMs
        var acc = 0L
        for (j in target until n) {
            val w = if (sumW > 0) ref[j].durationMs else if (j == target) 1L else 0L
            val den = if (sumW > 0) sumW else 1L
            starts[j] = e + rem * acc / den
            acc += w
            durs[j] = e + rem * acc / den - starts[j]
        }
        base = target
        return true
    }

    /** Reverts the last manual jump (valid for 15 s). */
    @Synchronized fun undo(e: Long): Boolean {
        val u = undo ?: return false
        if (e >= undoUntil) return false
        base = u.first; u.second.copyInto(starts); u.third.copyInto(durs); undo = null
        return true
    }
}

class ExamEngine(total: Long) : Engine {
    @Volatile override var totalMs: Long = total
    override fun snapshot() = totalMs.toString()

    /** Adds/removes time. If the exam already ran out, extra time counts from now. */
    fun extend(deltaMs: Long, elapsed: Long) {
        val b = if (deltaMs > 0 && elapsed >= totalMs) elapsed else totalMs
        totalMs = maxOf(elapsed + 60_000L, b + deltaMs)
    }

    override fun stateAt(elapsed: Long): TimerState {
        val t = totalMs
        val left = (t - elapsed).coerceAtLeast(0)
        val done = elapsed >= t
        return TimerState(
            mode = Mode.EXAM, title = if (done) "انتهى الوقت" else "اختبار جارٍ",
            phaseCount = 1, phaseRemainingMs = left, phaseDurationMs = t, elapsedMs = elapsed,
            remainingMs = left, overtimeMs = (elapsed - t).coerceAtLeast(0), finished = done,
            phaseProgress = (elapsed.toFloat() / t).coerceAtMost(1f))
    }

    /** Pings every 10 min, plus at 5 min and 1 min before the end; then the end alert. */
    override fun nextEvent(after: Long): Event? {
        val t = totalMs
        val c = sortedSetOf<Long>()
        var k = 1L
        while (k * PING_MS < t) { c += k * PING_MS; k++ }
        if (t > 5 * 60_000L) c += t - 5 * 60_000L
        if (t > 60_000L) c += t - 60_000L
        c.firstOrNull { it > after && it < t }?.let { return Event(it, AlertKind.PING) }
        return if (t > after) Event(t, AlertKind.END) else null
    }

    private companion object { const val PING_MS = 10 * 60_000L }
}
