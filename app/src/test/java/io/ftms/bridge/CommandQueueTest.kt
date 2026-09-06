package io.ftms.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests (./gradlew :app:test) for [CommandQueue] — the bounded,
 * in-order FIFO behind the Fitness Machine Control Point. Plain JUnit 4, no
 * Android types (a writer is keyed by an address string).
 *
 * These pin the behavior that the PAIP-on-in-flight design got wrong: a
 * client's back-to-back burst must be BUFFERED and applied in order, with
 * PAIP reserved for a genuine overflow — and a mode op that a preceding
 * command made redundant must resolve to a no-op at dispatch, not a second
 * console write.
 */
class CommandQueueTest {

    private fun payload(op: Int, target: Int = -1, subOp: Int = 0x01): CommandQueue.Payload =
        CommandQueue.Payload(op, grade = -1, param = -1, raw = -1, target = target, subOp = subOp, sim = null)

    // --- overflow: PAIP only when the buffer is at capacity ----------------

    @Test
    fun `a full buffer rejects with PAIP, and only at capacity`() {
        val q = CommandQueue(capacity = 2)
        val a = q.admit(1, "A", payload(0x03), 1)
        val b = q.admit(2, "B", payload(0x04), 1)
        val c = q.admit(3, "C", payload(0x03), 1)
        val d = q.admit(4, "D", payload(0x04), 1)
        assertTrue("the first command dispatches (console frees)", a is CommandQueue.AdmitResult.Accepted)
        assertTrue("A executing, B buffered", b is CommandQueue.AdmitResult.Accepted)
        assertTrue("A executing, B+C = capacity buffered", c is CommandQueue.AdmitResult.Accepted)
        assertTrue("a fifth command would exceed capacity → PAIP", d is CommandQueue.AdmitResult.Rejected)
        assertEquals("the rejected write is not enqueued", 2, q.queueSize)
        assertTrue("the first command is still on the console", q.busy)
    }

    // --- THE reported bug: a 0x07 trailing two 0x11s -----------------------

    @Test
    fun `a 0x07 trailing two 0x11s is buffered and then dispatched, not dropped`() {
        val q = CommandQueue(capacity = 4)
        val idle = 1
        // The connect-time start-burst a client fires: two sim-params writes
        // then a Start, all within the first console round-trip's window.
        val r1 = q.admit(1, "W", payload(0x11), idle)
        val r2 = q.admit(2, "W", payload(0x11), idle)
        val r3 = q.admit(3, "W", payload(0x07, target = 2), idle)

        assertTrue("first 0x11 accepted", r1 is CommandQueue.AdmitResult.Accepted)
        assertTrue("second 0x11 buffered, not PAIP", r2 is CommandQueue.AdmitResult.Accepted)
        assertTrue("the Start is buffered, not PAIP (the reported bug)", r3 is CommandQueue.AdmitResult.Accepted)
        assertEquals("both trailing commands wait in the queue", 2, q.queueSize)
        assertTrue("the first 0x11 is on the console", q.busy)

        // The first 0x11 runs. It does not change the mode, so the latch
        // is still Idle and the second 0x11 dispatches.
        val after1 = q.completed(idle)
        assertTrue("the second 0x11 dispatches", after1.singleOrNull() is CommandQueue.DispatchEvent.Execute)

        // The second 0x11 runs; still Idle, so the Start dispatches as a
        // genuine fresh start (target 2 = Running).
        val after2 = q.completed(idle)
        val exec = after2.singleOrNull()
        assertTrue("the Start dispatches once the 0x11s have completed", exec is CommandQueue.DispatchEvent.Execute)
        assertEquals(0x07, (exec as CommandQueue.DispatchEvent.Execute).payload.op)
        assertEquals(2, exec.payload.target)
    }

    // --- duplicate Start in the queue → no-op at dispatch ------------------

    @Test
    fun `a second 0x07 in the queue resolves to a no-op once the first has run`() {
        val q = CommandQueue(capacity = 4)
        val idle = 1
        q.admit(1, "W", payload(0x07, target = 2), idle)   // first Start: dispatched
        q.admit(2, "W", payload(0x07, target = 2), idle)   // duplicate Start: buffered

        // The first Start completes → the machine is now Running (2). The
        // buffered duplicate must resolve to a no-op, not a second write.
        val events = q.completed(2)
        val noop = events.singleOrNull()
        assertTrue("the duplicate resolves to exactly one Noop", noop is CommandQueue.DispatchEvent.Noop)
        assertEquals(0x07, (noop as CommandQueue.DispatchEvent.Noop).op)
        assertEquals(0x04, noop.fail)
        assertEquals("the no-op leaves the queue empty and the console idle", 0, q.queueSize)
        assertFalse(q.busy)
    }

    // --- target re-decided at dispatch -------------------------------------

    @Test
    fun `a 0x07 dispatched after a preceding pause is a Resume, not the admission target`() {
        val q = CommandQueue(capacity = 4)
        val idle = 1
        q.admit(1, "W", payload(0x08, subOp = 0x02), idle)   // Pause first (dispatched)
        q.admit(2, "W", payload(0x07, target = 2), idle)     // Start admitted "from Idle" (target 2)

        // The Pause completes → the machine is now Paused (3).
        val events = q.completed(3)
        val exec = events.singleOrNull()
        assertTrue(exec is CommandQueue.DispatchEvent.Execute)
        // Re-decided against the Paused latch, the Start becomes a Resume
        // (13), overriding the admission-time fresh-start target (2).
        assertEquals(13, (exec as CommandQueue.DispatchEvent.Execute).payload.target)
    }

    // --- drain resolves a run of no-ops in order, then dispatches ----------

    @Test
    fun `drain resolves queued no-ops in order, then dispatches the next live op`() {
        val q = CommandQueue(capacity = 4)
        // A Start is in flight (Idle → Running). Behind it, two more Starts
        // (already-satisfied once the machine is Running) and a Pause.
        q.admit(1, "W", payload(0x07, target = 2), 1)
        q.admit(2, "W", payload(0x07, target = 2), 1)
        q.admit(3, "W", payload(0x07, target = 2), 1)
        q.admit(4, "W", payload(0x08, subOp = 0x02), 1)

        // The in-flight Start completes → the machine is Running (2). The
        // drain must resolve the two redundant Starts as no-ops (in order)
        // and then dispatch the Pause.
        val events = q.completed(2)
        assertEquals(3, events.size)
        assertTrue("first redundant Start → no-op", events[0] is CommandQueue.DispatchEvent.Noop)
        assertTrue("second redundant Start → no-op", events[1] is CommandQueue.DispatchEvent.Noop)
        val exec = events[2]
        assertTrue("the Pause then dispatches", exec is CommandQueue.DispatchEvent.Execute)
        assertEquals(0x08, (exec as CommandQueue.DispatchEvent.Execute).payload.op)
        assertEquals(3, exec.payload.target)
        assertTrue("the Pause is now on the console", q.busy)
    }

    // --- teardown ------------------------------------------------------------

    @Test
    fun `cancelAll empties the queue and a later completed is a no-op`() {
        val q = CommandQueue(capacity = 4)
        q.admit(1, "W", payload(0x07, target = 2), 1)
        q.admit(2, "W", payload(0x08, subOp = 0x02), 1)
        assertTrue(q.busy)
        assertTrue(q.queueSize > 0)
        q.cancelAll()
        assertFalse("teardown clears the in-flight flag", q.busy)
        assertEquals("teardown drops the buffered commands", 0, q.queueSize)
        assertTrue("a post-teardown completion dispatches nothing", q.completed(1).isEmpty())
    }

    @Test
    fun `completing with an empty queue returns no events`() {
        val q = CommandQueue(capacity = 4)
        q.admit(1, "W", payload(0x03), 1)   // dispatches, nothing left queued
        assertTrue(q.completed(1).isEmpty())
    }
}
