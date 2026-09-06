package io.ftms.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests (./gradlew :app:test) for [ControlWriteGate] — the pure
 * admission decision applied on the main thread to EVERY Fitness Machine
 * Control Point write (both preconditions apply to ALL op codes, including
 * 0x00 — the spec keys on "an Op Code is written").
 *
 * The PAIP input is [ControlWriteGate.admit]'s [queueFull] argument — the
 * bounded [CommandQueue] being at capacity — NOT "any op is in flight."
 * That is the compatibility refinement that keeps a client's back-to-back
 * burst from being dropped: a write is rejected with PAIP only when the
 * buffer overflows, and buffered (applied in order) while there is room.
 * Plain JUnit 4, no Android types.
 */
class ControlWriteGateTest {

    // The spec's decision table, (queueFull, cccdEnabled) → admission.

    @Test
    fun `queue not full and CCC not armed → 0x0E CCCD improperly configured`() {
        assertEquals(
            "room in the queue; the CCCD check is the one that fails",
            AttError.CCCD_IMPROPERLY_CONFIGURED,
            ControlWriteGate.admit(queueFull = false, cccdEnabled = false))
    }

    @Test
    fun `queue not full and CCC armed → accept and buffer it`() {
        assertEquals(
            "no precondition violated → enqueue + proceed to per-op validation",
            ControlWriteGate.ACCEPT,
            ControlWriteGate.admit(queueFull = false, cccdEnabled = true))
    }

    @Test
    fun `queue full and CCC armed → 0x0C PAIP on overflow`() {
        assertEquals(
            "the buffer is at capacity → PAIP, the write is not enqueued",
            AttError.PROCEDURE_ALREADY_IN_PROGRESS,
            ControlWriteGate.admit(queueFull = true, cccdEnabled = true))
    }

    @Test
    fun `PAIP wins when both conditions hold`() {
        // The table's only order-sensitive case: §4.16.3 lists the PAIP
        // precondition FIRST, so the answer is 0x0C, not 0x0E — and a full
        // queue is the busier condition (the console is saturated; the
        // write would wait for it to drain either way).
        assertEquals(
            "the spec lists PAIP before the CCCD check — it must win",
            AttError.PROCEDURE_ALREADY_IN_PROGRESS,
            ControlWriteGate.admit(queueFull = true, cccdEnabled = false))
    }

    @Test
    fun `the raw ATT codes carry the Core Spec values`() {
        // Core Spec Vol 3 Part H, "ATT Error Codes" table (FTMS §1.6
        // defines no service-specific codes — these standard codes
        // apply): Procedure_Already_In_Progress = 0x0C,
        // CCCD_Improperly_Configured = 0x0E.
        assertEquals(0x0C, AttError.PROCEDURE_ALREADY_IN_PROGRESS)
        assertEquals(0x0E, AttError.CCCD_IMPROPERLY_CONFIGURED)
    }

    @Test
    fun `ACCEPT is the zero status, never an error code`() {
        // The admission result doubles as the sendResponse status on a
        // rejection; ACCEPT must be the (GATT_SUCCESS) zero so a caller
        // can pass the result straight to sendResponse.
        assertEquals(0, ControlWriteGate.ACCEPT)
    }
}
