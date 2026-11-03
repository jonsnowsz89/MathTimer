package com.mathteacher.timer

import com.mathteacher.timer.model.AlertKind
import com.mathteacher.timer.timer.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class EnginesTest {
    @Test fun exactFormulaScalesEachPhase() {
        val p = ClassPlan.build(40, false)
        assertEquals(3 * 60_000L * 40 / 50, p[0].durationMs)           // 3 min * 0.8
        assertEquals(10 * 60_000L * 40 / 50, p[2].durationMs)
    }

    @Test fun reserveModeKeepsSessionLength() {
        val p = ClassPlan.build(50, true)
        assertTrue(abs(p.sumOf { it.durationMs } - 50 * 60_000L) < 20)
        assertTrue(p.last().durationMs > 0)
    }

    @Test fun jumpKeepsSessionEndFixed() {
        val eng = ClassEngine(ClassPlan.build(50, true))
        assertTrue(eng.jump(+1, 60_000L))
        assertEquals(1, eng.stateAt(60_000L).phaseIndex)
        val last = generateSequence(eng.nextEvent(60_000L)) { eng.nextEvent(it.atMs) }.last()
        assertEquals(eng.totalMs, last.atMs)
        assertEquals(AlertKind.END, last.kind)
    }

    @Test fun undoRestoresPlan() {
        val eng = ClassEngine(ClassPlan.build(50, true))
        val before = eng.snapshot()
        eng.jump(+1, 60_000L)
        assertTrue(eng.undo(65_000L))
        assertEquals(before, eng.snapshot())
    }

    @Test fun snapshotRoundTrip() {
        val ref = ClassPlan.build(45, false)
        val a = ClassEngine(ref); a.jump(+1, 30_000L)
        val b = ClassEngine(ref, a.snapshot())
        assertEquals(a.stateAt(100_000L).phaseIndex, b.stateAt(100_000L).phaseIndex)
        assertEquals(a.stateAt(100_000L).phaseRemainingMs, b.stateAt(100_000L).phaseRemainingMs)
    }

    @Test fun examPingsAndWarnings() {
        val eng = ExamEngine(60 * 60_000L)
        val ev = generateSequence(eng.nextEvent(0)) { eng.nextEvent(it.atMs) }.toList()
        assertEquals(listOf(10L, 20, 30, 40, 50, 55, 59, 60), ev.map { it.atMs / 60_000 })
        assertEquals(AlertKind.END, ev.last().kind)
    }

    @Test fun examExtendMovesEnd() {
        val eng = ExamEngine(60 * 60_000L)
        eng.extend(5 * 60_000L, 10 * 60_000L)
        assertEquals(65 * 60_000L, eng.totalMs)
    }
}
