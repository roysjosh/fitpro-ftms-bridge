package io.ftms.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests (./gradlew :app:test) for [CommandDispatch] — the
 * dispatch-time re-decision for a QUEUED Control Point op. The decision is
 * re-run against the mode latch at the moment the op leaves the queue (not
 * at write-arrival), because a preceding queued op may have advanced the
 * console state in the meantime. Plain JUnit 4, no Android types.
 */
class CommandDispatchTest {

    // --- Start or Resume (0x07) -------------------------------------------

    @Test
    fun `a Start dispatched from Idle is a fresh start to Running`() {
        val d = CommandDispatch.decide(0x07, 0x01, latchedMode = 1)
        assertEquals(CommandDispatch.Verdict.EXECUTE, d.verdict)
        assertEquals(2, d.target)
        assertEquals(0, d.fail)
    }

    @Test
    fun `a Start dispatched while already Running is a no-op — Operation Failed`() {
        // THE regression: a 0x07 that was admitted as a genuine "fresh start"
        // (the latch was still Idle at write-arrival) but is re-decided after
        // a preceding queued op already moved the machine to Running must be
        // answered 0x04 with no console write — not dispatched as a second,
        // redundant start.
        val d = CommandDispatch.decide(0x07, 0x01, latchedMode = 2)
        assertEquals(CommandDispatch.Verdict.NOOP, d.verdict)
        assertEquals(0x04, d.fail)
    }

    @Test
    fun `a Start dispatched while already Resumed is a no-op — Operation Failed`() {
        val d = CommandDispatch.decide(0x07, 0x01, latchedMode = 13)
        assertEquals(CommandDispatch.Verdict.NOOP, d.verdict)
        assertEquals(0x04, d.fail)
    }

    @Test
    fun `a Start dispatched while Paused is a Resume, target re-decided not the admission value`() {
        // An op admitted "from Idle" carries target 2 (fresh start). If a
        // preceding queued op paused the machine before this one dispatches,
        // the re-decision must yield a Resume (13), and the caller uses THAT.
        val d = CommandDispatch.decide(0x07, 0x01, latchedMode = 3)
        assertEquals(CommandDispatch.Verdict.EXECUTE, d.verdict)
        assertEquals(13, d.target)
    }

    @Test
    fun `a Start dispatched before any console sample assumes Idle and starts`() {
        val d = CommandDispatch.decide(0x07, 0x01, latchedMode = -1)
        assertEquals(CommandDispatch.Verdict.EXECUTE, d.verdict)
        assertEquals(2, d.target)
    }

    // --- Stop or Pause (0x08) ---------------------------------------------

    @Test
    fun `a Stop dispatched while Running goes to Idle`() {
        val d = CommandDispatch.decide(0x08, 0x01, latchedMode = 2)
        assertEquals(CommandDispatch.Verdict.EXECUTE, d.verdict)
        assertEquals(1, d.target)
    }

    @Test
    fun `a Stop dispatched while already Idle is a no-op — Operation Failed`() {
        val d = CommandDispatch.decide(0x08, 0x01, latchedMode = 1)
        assertEquals(CommandDispatch.Verdict.NOOP, d.verdict)
        assertEquals(0x04, d.fail)
    }

    @Test
    fun `a Pause dispatched while Running goes to Pause`() {
        val d = CommandDispatch.decide(0x08, 0x02, latchedMode = 2)
        assertEquals(CommandDispatch.Verdict.EXECUTE, d.verdict)
        assertEquals(3, d.target)
    }

    @Test
    fun `a Pause dispatched while already Paused is a no-op — Operation Failed`() {
        val d = CommandDispatch.decide(0x08, 0x02, latchedMode = 3)
        assertEquals(CommandDispatch.Verdict.NOOP, d.verdict)
        assertEquals(0x04, d.fail)
    }

    @Test
    fun `a Stop dispatched before any console sample assumes Running and is allowed`() {
        val d = CommandDispatch.decide(0x08, 0x01, latchedMode = -1)
        assertEquals(CommandDispatch.Verdict.EXECUTE, d.verdict)
        assertEquals(1, d.target)
    }

    // --- non-mode ops are never idempotent ---------------------------------

    @Test
    fun `non-mode ops always execute, whatever the latch`() {
        for (op in intArrayOf(0x01, 0x03, 0x04, 0x11)) {
            for (mode in intArrayOf(-1, 1, 2, 3, 13)) {
                val d = CommandDispatch.decide(op, 0x01, mode)
                assertEquals("op 0x${op.toString(16)} in mode $mode must execute",
                    CommandDispatch.Verdict.EXECUTE, d.verdict)
                assertEquals(0, d.fail)
            }
        }
    }
}
