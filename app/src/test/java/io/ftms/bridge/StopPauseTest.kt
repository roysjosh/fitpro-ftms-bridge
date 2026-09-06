package io.ftms.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests (./gradlew :app:test) for [StopPause] — the strict parser
 * for the Control Information parameter (FTMS Table 4.16: 0x00 RFU, 0x01
 * Stop, 0x02 Pause, 0x03–0xFF RFU) of the Control Point Stop or Pause
 * (0x08) write. Only 0x01 and 0x02 are defined, so a write without the
 * parameter octet, or one carrying a reserved value, is INVALID and must
 * be answered Invalid Parameter (Table 4.24) instead of being coerced to
 * Stop. Trailing bytes beyond the 1-octet parameter are ignored (the
 * spec defines no longer form). Plain JUnit 4, no Android types.
 */
class StopPauseTest {

    @Test
    fun `a write with Control Info 0x01 parses as Stop`() {
        assertEquals(StopPause.Outcome.STOP, StopPause.parse(byteArrayOf(0x08, 0x01)))
    }

    @Test
    fun `a write with Control Info 0x02 parses as Pause`() {
        assertEquals(StopPause.Outcome.PAUSE, StopPause.parse(byteArrayOf(0x08, 0x02)))
    }

    @Test
    fun `a write without the Control Info octet is Invalid`() {
        assertEquals(StopPause.Outcome.INVALID, StopPause.parse(byteArrayOf(0x08)))
    }

    @Test
    fun `Control Info 0x00 is Reserved and Invalid`() {
        assertEquals(StopPause.Outcome.INVALID, StopPause.parse(byteArrayOf(0x08, 0x00)))
    }

    @Test
    fun `Control Info 0x03 is Reserved and Invalid`() {
        assertEquals(StopPause.Outcome.INVALID, StopPause.parse(byteArrayOf(0x08, 0x03)))
    }

    @Test
    fun `Control Info 0xFF is Reserved and Invalid`() {
        assertEquals(StopPause.Outcome.INVALID, StopPause.parse(byteArrayOf(0x08, 0xFF.toByte())))
    }

    @Test
    fun `trailing bytes beyond the 1-octet parameter are ignored`() {
        assertEquals(StopPause.Outcome.STOP, StopPause.parse(byteArrayOf(0x08, 0x01, 0x55)))
    }

    @Test
    fun `an empty write is Invalid`() {
        assertEquals(StopPause.Outcome.INVALID, StopPause.parse(ByteArray(0)))
    }
}
