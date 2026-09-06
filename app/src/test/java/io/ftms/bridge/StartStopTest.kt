package io.ftms.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests (./gradlew :app:test) for [StartStop] — the idempotency
 * decisions behind the Control Point Start or Resume (0x07) and Stop or
 * Pause (0x08) handlers. The decisions read the sequence-gated mode latch,
 * never the raw console sample, so a duplicate op issued right after the
 * first one's write acks (while the pump still holds the PRE-write sample)
 * is still seen as "already in this state" and answered with 0x04
 * Operation Failed (Table 4.24) instead of a second Success — as FTMS
 * test cases FTMS/SR/SPE/BV-01-C (second 0x07 → [0x80][0x07][0x04]) and
 * BV-02-C (second 0x08 → [0x80][0x08][0x04]) require. Plain JUnit 4, no
 * Android types.
 */
class StartStopTest {

    // --- startDecision ----------------------------------------------------

    @Test
    fun `a Start while already Running is Operation Failed`() {
        assertEquals(-1 to 0x04, StartStop.startDecision(2))
    }

    @Test
    fun `a Start while already Resumed is Operation Failed`() {
        assertEquals(-1 to 0x04, StartStop.startDecision(13))
    }

    @Test
    fun `a Start from Idle is a fresh start to Running`() {
        assertEquals(2 to 0, StartStop.startDecision(1))
    }

    @Test
    fun `a Start while Paused is a Resume`() {
        assertEquals(13 to 0, StartStop.startDecision(3))
    }

    @Test
    fun `a Start while in PauseOverride is a Resume`() {
        assertEquals(13 to 0, StartStop.startDecision(20))
    }

    @Test
    fun `a Start before any console sample is observed assumes Idle and starts`() {
        assertEquals(2 to 0, StartStop.startDecision(-1))
    }

    // --- stopDecision -------------------------------------------------------

    @Test
    fun `a Stop while already Idle is Operation Failed`() {
        assertEquals(-1 to 0x04, StartStop.stopDecision(1, 0x01))
    }

    @Test
    fun `a Pause while already Paused is Operation Failed`() {
        assertEquals(-1 to 0x04, StartStop.stopDecision(3, 0x02))
    }

    @Test
    fun `a Stop while Running goes to Idle`() {
        assertEquals(1 to 0, StartStop.stopDecision(2, 0x01))
    }

    @Test
    fun `a Pause while Running goes to Pause`() {
        assertEquals(3 to 0, StartStop.stopDecision(2, 0x02))
    }

    @Test
    fun `a Stop before any console sample is observed assumes Running and is allowed`() {
        assertEquals(1 to 0, StartStop.stopDecision(-1, 0x01))
    }

    @Test
    fun `a Pause before any console sample is observed assumes Running and is allowed`() {
        assertEquals(3 to 0, StartStop.stopDecision(-1, 0x02))
    }
}
