package io.ftms.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests (./gradlew :app:test) for the 0x2AD5 / 0x2AD6 range
 * characteristic encoders — the SIG 6-octet
 * [Sint16 Min][Sint16 Max][Uint16 Min-Increment] layouts. No device
 * needed. Bytes are signed in Kotlin, so high-valued octets need an
 * explicit .toByte().
 */
class RangeCharsTest {

    @Test
    fun `incline range encodes a negative minimum and a sub-0xFF maximum`() {
        // -50 = 0xFFCE, 250 = 0x00FA, increment 1 = 0x0001.
        assertArrayEquals(
            byteArrayOf(0xCE.toByte(), 0xFF.toByte(), 0xFA.toByte(), 0x00.toByte(), 0x01, 0x00),
            inclineRange(-50, 250, 1)
        )
    }

    @Test
    fun `incline range keeps a maximum above 0xFF in its own 2-octet field`() {
        // -300 = 0xFED4 (SINT16 two's complement, per the SIG attribute
        // definition — the spec doc's [0x34, 0xFF] would decode to -204),
        // 320 = 0x0140.
        assertArrayEquals(
            byteArrayOf(0xD4.toByte(), 0xFE.toByte(), 0x40.toByte(), 0x01, 0x01, 0x00),
            inclineRange(-300, 320, 1)
        )
    }

    @Test
    fun `resistance range encodes maxLevel×10 = 1000 without truncation`() {
        // 10 = 0x000A, 1000 = 0x03E8, increment 10 = 0x000A.
        assertArrayEquals(
            byteArrayOf(0x0A, 0x00, 0xE8.toByte(), 0x03, 0x0A, 0x00),
            resistanceRange(10, 1000, 10)
        )
    }

    @Test
    fun `resistance range encodes a 0xFF maximum as two octets`() {
        // 10 = 0x000A, 255 = 0x00FF.
        assertArrayEquals(
            byteArrayOf(0x0A, 0x00, 0xFF.toByte(), 0x00, 0x0A, 0x00),
            resistanceRange(10, 255, 10)
        )
    }

    @Test
    fun `out-of-bounds values clamp to the s16 minimum and maximum`() {
        // -40000 → s16 min -32768 = 0x8000; 40000 → s16 max 32767 = 0x7FFF.
        assertArrayEquals(
            byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F.toByte(), 0x01, 0x00),
            inclineRange(-40000, 40000, 1)
        )
    }

    @Test
    fun `both range characteristics are exactly 6 octets`() {
        assertEquals(6, inclineRange(-50, 250, 1).size)
        assertEquals(6, inclineRange(-300, 320, 1).size)
        assertEquals(6, inclineRange(-40000, 40000, 1).size)
        assertEquals(6, resistanceRange(10, 1000, 10).size)
        assertEquals(6, resistanceRange(10, 255, 10).size)
    }
}
