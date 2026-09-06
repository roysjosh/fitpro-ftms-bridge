package io.ftms.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests (./gradlew :app:test) for the Machine Status encoder
 * (FTMS v1.0.1 Table 4.26) and the Control Point SINT16 decoder
 * (Table 4.15) — no device needed. Bytes are signed in Kotlin, so
 * high-valued octets need an explicit .toByte().
 */
class FtmsProtocolTest {

    @Test
    fun `an op code without a parameter encodes to the bare op code`() {
        assertArrayEquals(byteArrayOf(0x01), MachineStatus.encode(0x01, null))
    }

    @Test
    fun `0x02 carries the 1-octet Control Information parameter`() {
        assertArrayEquals(byteArrayOf(0x02, 0x01), MachineStatus.encode(0x02, 1))
    }

    @Test
    fun `0x05 target speed is a 2-octet little-endian value`() {
        assertArrayEquals(byteArrayOf(0x05, 0x88.toByte(), 0x13), MachineStatus.encode(0x05, 5000))
    }

    @Test
    fun `0x06 positive incline is a 2-octet little-endian value`() {
        assertArrayEquals(byteArrayOf(0x06, 0x32, 0x00), MachineStatus.encode(0x06, 50))
    }

    @Test
    fun `0x06 negative incline is two's-complement little-endian`() {
        assertArrayEquals(byteArrayOf(0x06, 0xCE.toByte(), 0xFF.toByte()), MachineStatus.encode(0x06, -50))
    }

    @Test
    fun `0x06 SINT16 minimum survives the low-16-bits encoding`() {
        assertArrayEquals(byteArrayOf(0x06, 0x00, 0x80.toByte()), MachineStatus.encode(0x06, -32768))
    }

    @Test
    fun `0x07 resistance level is the device-declared SINT16 (2 octets)`() {
        assertArrayEquals(byteArrayOf(0x07, 0x0A, 0x00), MachineStatus.encode(0x07, 10))
        assertArrayEquals(byteArrayOf(0x07, 0xE8.toByte(), 0x03), MachineStatus.encode(0x07, 1000))
    }

    @Test
    fun `0x09 target heart rate is 1 octet`() {
        assertArrayEquals(byteArrayOf(0x09, 0x8C.toByte()), MachineStatus.encode(0x09, 140))
    }

    @Test
    fun `0x14 spin down status is 1 octet`() {
        assertArrayEquals(byteArrayOf(0x14, 0x01), MachineStatus.encode(0x14, 1))
    }

    @Test
    fun `0x0D targeted distance is a 3-octet UINT24 little-endian value`() {
        // 123456 = 0x01E240 → LE octets 40 E2 01.
        assertArrayEquals(byteArrayOf(0x0D, 0x40, 0xE2.toByte(), 0x01), MachineStatus.encode(0x0D, 123456))
    }

    @Test
    fun `reserved (RFU) op codes 0x16-0xFE encode to the bare op code`() {
        assertArrayEquals(byteArrayOf(0x7F), MachineStatus.encode(0x7F, 0x2A))
        assertArrayEquals(byteArrayOf(0x16), MachineStatus.encode(0x16, 1))
    }

    @Test
    fun `an op code outside the 1-octet range falls back to a 1-octet parameter`() {
        val out = MachineStatus.encode(0x100, 0x2A)
        assertEquals(2, out.size)
        assertArrayEquals(byteArrayOf(0x00, 0x2A), out)
    }

    @Test
    fun `decodeS16 reads a positive SINT16 little-endian value`() {
        assertEquals(50, decodeS16(byteArrayOf(0x32, 0x00), 0))
    }

    @Test
    fun `decodeS16 sign-extends two's-complement values`() {
        assertEquals(-50, decodeS16(byteArrayOf(0xCE.toByte(), 0xFF.toByte()), 0))
        assertEquals(-1, decodeS16(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), 0))
    }

    @Test
    fun `decodeS16 decodes the SINT16 minimum`() {
        assertEquals(-32768, decodeS16(byteArrayOf(0x00, 0x80.toByte()), 0))
    }

    @Test
    fun `decodeS16 returns null when the write is missing the 2 octets`() {
        assertNull(decodeS16(byteArrayOf(0x01), 0))
        assertNull(decodeS16(ByteArray(0), 0))
        assertNull(decodeS16(byteArrayOf(0x32, 0x00), 1)) // offset past the end
    }

    @Test
    fun `decodeS16 round-trips with the 0x06 Machine Status encoder`() {
        // bytes = [0x06][v LE] — the full Control Point write shape.
        for (v in listOf(50, -50, -32768, 32767)) {
            val bytes = byteArrayOf(0x06, (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
            assertArrayEquals(bytes, MachineStatus.encode(0x06, decodeS16(bytes, 1)!!))
        }
    }

    // --- 0x11 Set Indoor Bike Simulation Parameters (Table 4.20) ----------

    @Test
    fun `0x12 simulation-parameters status is the op code followed by the raw 6-octet array`() {
        // [Wind=0][Grade=500 → 5.00 %][Crr=100][Cw=5]
        val arr = byteArrayOf(0, 0, 0xF4.toByte(), 0x01, 100.toByte(), 5)
        assertArrayEquals(
            byteArrayOf(0x12, 0, 0, 0xF4.toByte(), 0x01, 100.toByte(), 5),
            MachineStatus.encode(0x12, arr)
        )
    }

    @Test
    fun `SimulationParams parse decodes wind and grade as SINT16 and crr cw as UINT8`() {
        // [op 0x11][Wind 0x01F4=500][Grade 0xFFCE=-50][Crr 0x80=128][Cw 0xFF=255]
        val write = byteArrayOf(0x11, 0xF4.toByte(), 0x01, 0xCE.toByte(), 0xFF.toByte(), 0x80.toByte(), 0xFF.toByte())
        val p = SimulationParams.parse(write, 1)!!
        assertEquals(500, p.wind)
        assertEquals(-50, p.grade)
        assertEquals(128, p.crr)
        assertEquals(255, p.cw)
    }

    @Test
    fun `SimulationParams grade is in hundredths of a percent, the console wire unit (identity not times 10)`() {
        // 5.00 % → 500 in the 0.01 % units the 0x11 Grade uses → 500 on the
        // ×100 % console wire. (A 0x03 Set Target Incline for the same 5.00 %
        // would carry 50, i.e. 0.1 % units.)
        val write = byteArrayOf(0x11, 0, 0, 0xF4.toByte(), 0x01, 0, 0)
        assertEquals(500, SimulationParams.parse(write, 1)!!.grade)
    }

    @Test
    fun `SimulationParams parse returns null unless the write holds the full 6-octet array`() {
        assertNull(SimulationParams.parse(byteArrayOf(0x11), 1))                        // op only
        assertNull(SimulationParams.parse(byteArrayOf(0x11, 0, 0, 0, 0, 0), 1))          // op + 5
        assertNotNull(SimulationParams.parse(byteArrayOf(0x11, 0, 0, 0, 0, 0, 0), 1))    // op + 6
    }

    @Test
    fun `parsing a sim array and rebuilding it from the fields is lossless`() {
        // MyWhoosh-style write: 5.00 % grade, 3.5 m/s tailwind, Crr 100, Cw 5.
        // 3500 = 0x0DAC (LE bytes AC 0D); 500 = 0x01F4 (LE bytes F4 01).
        val write = byteArrayOf(0x11, 0xAC.toByte(), 0x0D, 0xF4.toByte(), 0x01, 100.toByte(), 5)
        val p = SimulationParams.parse(write, 1)!!
        assertEquals(3500, p.wind)   // 3.5 m/s at 0.001 m/s
        assertEquals(500, p.grade)   // 5.00 % at 0.01 %
        assertEquals(100, p.crr)
        assertEquals(5, p.cw)
        // Re-serialize the four decoded fields; must reproduce the exact
        // 6-octet array the client sent (the parser is lossless).
        val rebuilt = byteArrayOf(
            (p.wind and 0xFF).toByte(), ((p.wind ushr 8) and 0xFF).toByte(),
            (p.grade and 0xFF).toByte(), ((p.grade ushr 8) and 0xFF).toByte(),
            p.crr.toByte(), p.cw.toByte()
        )
        assertArrayEquals(write.copyOfRange(1, 7), rebuilt)
    }
}
