package io.ftms.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests (./gradlew :app:test) for [ModeLatch] — the
 * sequence-stamped WorkoutMode latch behind the 1 Hz FTMS pump. The latch
 * guards against a 1 Hz consumer sampling a 10 Hz console source one write
 * behind: after a Control Point mode write acks, the next periodic read may
 * still predate the console applying the write, and acting on it would
 * clobber the latch and duplicate the Machine Status the write already
 * emitted. Plain JUnit 4, no Android types.
 */
class ModeLatchTest {

    @Test
    fun `the first sample arms the latch and is a First`() {
        val l = ModeLatch()
        assertEquals(ModeLatch.TickResult.First(2), l.process(1, 2))
        assertEquals(2, l.latched)
    }

    @Test
    fun `a fresh console change is a Transition to the new mode`() {
        val l = ModeLatch()
        l.process(1, 2)
        assertEquals(ModeLatch.TickResult.Transition(2, 3), l.process(5, 3))
        assertEquals(3, l.latched)
    }

    @Test
    fun `samples at or before the write ack are stale and touch nothing`() {
        val l = ModeLatch()
        l.process(1, 2)
        l.noteWrite(2, 10)
        assertEquals(2, l.latched)
        assertEquals(10L, l.pendingAck)
        // The read taken AT the ack still predates the console applying
        // the write: dropped, and the stamp survives for the next read.
        assertEquals(ModeLatch.TickResult.Stale, l.process(10, 1))
        assertEquals(2, l.latched)
        assertEquals(10L, l.pendingAck)
        assertEquals(ModeLatch.TickResult.Stale, l.process(10, 2))
        assertEquals(2, l.latched)
        assertEquals(10L, l.pendingAck)
    }

    @Test
    fun `the first fresh sample after the ack confirms the write`() {
        val l = ModeLatch()
        l.process(1, 2)
        l.noteWrite(2, 10)
        assertEquals(ModeLatch.TickResult.Unchanged, l.process(11, 2))
        assertEquals(2, l.latched)
        assertEquals(null, l.pendingAck)
    }

    @Test
    fun `a fresh sample revealing the console did not apply the write is a Transition to the true state`() {
        val l = ModeLatch()
        l.noteWrite(2, 10)
        assertEquals(ModeLatch.TickResult.Transition(2, 1), l.process(11, 1))
        assertEquals(1, l.latched)
        assertEquals(null, l.pendingAck)
    }

    @Test
    fun `reset clears the latch and the next sample is a First again`() {
        val l = ModeLatch()
        l.noteWrite(13, 7)
        assertEquals(13, l.latched)
        assertEquals(7L, l.pendingAck)
        l.reset()
        assertEquals(-1, l.latched)
        assertEquals(null, l.pendingAck)
        assertEquals(ModeLatch.TickResult.First(20), l.process(1, 20))
        assertEquals(20, l.latched)
    }
}
