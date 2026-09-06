package io.ftms.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests (./gradlew :app:test) for [ProcedureGate] — the
 * monotonic procedure ids + cancel watermark behind the Control Point
 * console round-trips. FTMS §4.16.4 starts a procedure when the Server
 * sends the Write Response (main thread), but the procedure's console
 * side completes later on the worker thread and posts its effects back
 * to the main thread; a full server teardown in between must make that
 * late post-back a no-op, and the ids + watermark are how the post-back
 * recognizes that it was cancelled. Plain JUnit 4, no Android types.
 */
class ProcedureGateTest {

    @Test
    fun `begin marks the procedure in flight`() {
        val gate = ProcedureGate()
        assertFalse("an empty gate has no in-flight procedure", gate.inFlight)
        gate.begin(0x01)
        assertTrue("begin marks a procedure in flight", gate.inFlight)
    }

    @Test
    fun `a second begin keeps inFlight until both procedures end`() {
        val gate = ProcedureGate()
        val first = gate.begin(0x07)
        val second = gate.begin(0x08)
        assertTrue("two accepted procedures → still in flight", gate.inFlight)
        gate.end(first)
        assertTrue("the second is still pending", gate.inFlight)
        gate.end(second)
        assertFalse("both ended → free", gate.inFlight)
    }

    @Test
    fun `cancelAll while procedures are pending clears inFlight`() {
        val gate = ProcedureGate()
        gate.begin(0x03)
        gate.begin(0x04)
        gate.cancelAll()
        assertFalse("cancelAll drops the pending count", gate.inFlight)
    }

    @Test
    fun `a stale end at or below the cancel watermark is a no-op`() {
        val gate = ProcedureGate()
        val first = gate.begin(0x01)
        val second = gate.begin(0x07)
        gate.cancelAll()
        // Both ids sit at the watermark, and 0 below every issued id:
        // each must be a no-op — neither throw nor flip the flag.
        gate.end(first)
        gate.end(second)
        gate.end(0)
        assertFalse("stale ends must not flip the flag", gate.inFlight)
    }

    @Test
    fun `a later begin after cancelAll works again`() {
        val gate = ProcedureGate()
        val first = gate.begin(0x01)
        gate.cancelAll()
        val later = gate.begin(0x08)
        assertTrue("a new procedure past the watermark is in flight", gate.inFlight)
        assertTrue("ids keep growing past the cancel watermark", later > first)
        gate.end(later)
        assertFalse(gate.inFlight)
    }
}
