package io.ftms.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests (./gradlew :app:test) — no device needed.
 *
 * [LIVE_DEVICE_INFO] is a DeviceInfo response frame from an S15i brainboard,
 * with the serial-number bytes replaced by a synthetic value (0x1234,
 * checksum recomputed) so no real device identifier ships in the fixture.
 * The response model
 * (frame at byte 0, no header strip, ck = sum of [0..len-2]) must keep
 * these green; if a model change breaks them, the test fails on the host
 * without any device being involved.
 */
class FitPro1Test {

    companion object {
        private fun hex(s: String): ByteArray = ByteArray(s.length / 2) {
            s.substring(2 * it, 2 * it + 2).toInt(16).toByte()
        }

        // 29-byte DeviceInfo frame + 0xFF keep-alive padding to 64.
        val LIVE_DEVICE_INFO: ByteArray = hex(
            "081D8102" + // 00: dev len cmd sw
            "53013412" + // 04: hw(2) + serial[0..1] (synthetic)
            "000000FF" + // 08: serial[2..3] (synthetic) + mfr(2)
            "0FFFFFFF" + // 0C: mfr[1] sections bitmap0..2
            "DFFCFFFB" + // 10: bitmap3..6
            "BCE71FC0" + // 14: bitmap7..10
            "C0D51098" + // 18: bitmap11..13 + ?
            "E1" + // 1C: ck (recomputed for the synthetic serial)
            "F".repeat(70) // 1D..3F: keep-alive padding
        )
    }

    // ------------------------------------------------------------------
    // The live capture
    // ------------------------------------------------------------------

    @Test
    fun `live capture is a self-delimiting frame at byte 0`() {
        val clean = FitPro1.unwrapFrame(LIVE_DEVICE_INFO)
        assertEquals("frame length byte", 29, clean.size)
        assertEquals("dev", 0x08, clean[0].toInt() and 0xFF)
        assertEquals("len", 29, clean[1].toInt() and 0xFF)
        assertEquals("cmd", FitPro1.CMD_DEVICE_INFO, clean[2].toInt() and 0xFF)
    }

    @Test
    fun `live capture checksum validates from byte 0`() {
        val clean = FitPro1.unwrapFrame(LIVE_DEVICE_INFO)
        val ck = clean[clean.size - 1].toInt() and 0xFF
        assertEquals("ck must equal the sum of all preceding bytes", ck, FitPro1.checksum(clean, clean.size - 1))
    }

    @Test
    fun `isValidResponse accepts the live DeviceInfo frame`() {
        val clean = FitPro1.unwrapFrame(LIVE_DEVICE_INFO)
        assertTrue(
            "the recorded DeviceInfo frame must validate " +
                "(a failure here while the sibling checksum test passes would " +
                "indicate a model discrepancy, not data corruption)",
            FitPro1.isValidResponse(clean, FitPro1.CMD_DEVICE_INFO)
        )
    }

    @Test
    fun `DeviceInfo content layout matches the live capture`() {
        val p = FitPro1.unwrapFrame(LIVE_DEVICE_INFO)
        assertEquals("sw @3", 2, FitPro1.u8(p, 3))
        assertEquals("hw @4", 0x0153, FitPro1.u16(p, 4))
        assertEquals("serial @6", 0x1234L, FitPro1.u32(p, 6))
        assertEquals("sections @12", 15, FitPro1.u8(p, 12))
        assertTrue("15 bitmap bytes must fit in the frame", 13 + 15 <= p.size)
    }

    // ------------------------------------------------------------------
    // Request framing
    // ------------------------------------------------------------------

    @Test
    fun `request frame matches the format the device accepted in the capture`() {
        // The device answered this exact 4-byte OUT (seen in the hex log).
        assertArrayEquals(
            byteArrayOf(0x02, 0x04, 0x81.toByte(), 0x87.toByte()),
            FitPro1.frame(FitPro1.CMD_DEVICE_INFO, ByteArray(0))
        )
    }

    // ------------------------------------------------------------------
    // Negative cases
    // ------------------------------------------------------------------

    @Test
    fun `all-0xFF keep-alive yields an empty frame and fails validation`() {
        val ff = ByteArray(64) { 0xFF.toByte() }
        assertEquals(0, FitPro1.unwrapFrame(ff).size)
        assertFalse(FitPro1.isValidResponse(FitPro1.unwrapFrame(ff), FitPro1.CMD_DEVICE_INFO))
    }

    @Test
    fun `a single corrupted byte fails validation`() {
        val clean = FitPro1.unwrapFrame(LIVE_DEVICE_INFO)
        val corrupted = clean.copyOf()
        corrupted[5] = (corrupted[5].toInt() xor 0x01).toByte()
        assertFalse(FitPro1.isValidResponse(corrupted, FitPro1.CMD_DEVICE_INFO))
    }

    @Test
    fun `truncated raw buffer fails validation`() {
        val clean = FitPro1.unwrapFrame(LIVE_DEVICE_INFO.copyOf(20))
        assertFalse(FitPro1.isValidResponse(clean, FitPro1.CMD_DEVICE_INFO))
    }

    @Test
    fun `wrong command id fails validation`() {
        val clean = FitPro1.unwrapFrame(LIVE_DEVICE_INFO)
        assertFalse(FitPro1.isValidResponse(clean, FitPro1.CMD_SYSTEM_INFO))
    }
}
