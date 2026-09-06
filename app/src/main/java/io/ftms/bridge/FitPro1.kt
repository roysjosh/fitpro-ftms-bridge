package io.ftms.bridge

/**
 * FitPro1 protocol core (transport-independent).
 *
 * USB response model: frame at byte 0 of the packet, 0xFF-padded to 64
 * bytes, no manager header (see [unwrapFrame]).
 */
object FitPro1 {
    const val DEV_MAIN = 2
    const val MAX_MSG = 64
    // A RESPONSE frame may span multiple USB packets (the 1 Hz periodic read
    // is 90 bytes). Requests are still capped at [MAX_MSG]; only the
    // response-side length check uses this larger bound.
    const val MAX_RESPONSE = 512

    // Command ids (the console's command table)
    const val CMD_READWRITE = 2
    const val CMD_SUPPORTED_DEVICES = 0x80 // 128
    const val CMD_DEVICE_INFO = 0x81 // 129
    const val CMD_SYSTEM_INFO = 0x82 // 130
    const val CMD_VERSION_INFO = 0x84 // 132
    const val CMD_SUPPORTED_COMMANDS = 0x88 // 136
    const val CMD_SERIAL_NUMBER = 0x95 // 149

    // CmdStatus (response byte 3)
    const val ST_DEV_NOT_SUPPORTED = 0
    const val ST_CMD_NOT_SUPPORTED = 1
    const val ST_DONE = 2
    const val ST_IN_PROGRESS = 3
    const val ST_FAILED = 4
    const val ST_TIME_LEFT = 5
    const val ST_UNKNOWN_FAILURE = 7
    const val ST_SECURITY_BLOCK = 8
    const val ST_COMM_FAILED = 9

    fun statusName(s: Int): String = when (s) {
        ST_DEV_NOT_SUPPORTED -> "DevNotSupported"
        ST_CMD_NOT_SUPPORTED -> "CmdNotSupported"
        ST_DONE -> "Done"
        ST_IN_PROGRESS -> "InProgress"
        ST_FAILED -> "Failed"
        ST_TIME_LEFT -> "TimeLeft"
        ST_UNKNOWN_FAILURE -> "UnknownFailure"
        ST_SECURITY_BLOCK -> "SecurityBlock"
        ST_COMM_FAILED -> "CommFailed"
        else -> "Status($s)"
    }

    /** ck = sum of the first [0, count) bytes & 0xFF. */
    fun checksum(bytes: ByteArray, count: Int): Int {
        var s = 0
        var i = 0
        while (i < count && i < bytes.size) {
            s = (s + (bytes[i].toInt() and 0xFF)) and 0xFF
            i++
        }
        return s
    }

    /** Request frame [dev len cmd content... ck]; len = 4 + content (incl. ck). */
    fun frame(cmd: Int, content: ByteArray): ByteArray {
        val len = 4 + content.size
        if (len > MAX_MSG) throw IllegalArgumentException("frame too large: $len > $MAX_MSG")
        val f = ByteArray(len)
        f[0] = DEV_MAIN.toByte()
        f[1] = len.toByte()
        f[2] = cmd.toByte()
        content.copyInto(f, 3)
        f[len - 1] = checksum(f, len - 1).toByte()
        return f
    }

    /**
     * USB response → frame. The raw USB console delivers the FitPro1 frame
     * starting at byte 0, padded with 0xFF out to 64 bytes. There is NO
     * 22-byte "response manager header" on this transport (that framing
     * belongs to the BLE/TCP variants). The frame is self-delimiting:
     * [0]=dev, [1]=len, [2]=cmd, [3..len-2]=content, [len-1]=ck (sum of
     * [0..len-2] & 0xFF). We simply take the first `len` bytes, dropping the
     * 0xFF padding.
     */
    fun unwrapFrame(raw: ByteArray): ByteArray {
        if (raw.size < 3) return ByteArray(0)
        if ((raw[0].toInt() and 0xFF) == 0xFF) return ByteArray(0) // keep-alive / no frame
        val len = raw[1].toInt() and 0xFF
        if (len <= 0) return ByteArray(0)
        if (len <= raw.size) return raw.copyOf(len)
        // Over-length: the frame declares more bytes than the USB packet
        // carried (the 90-byte periodic arrives as one 64-byte packet and the
        // rest is never sent). Return every received byte so [isValidResponse]
        // can accept it and the decoder can read the fields that fit.
        return raw
    }

    /**
     * Response validation — run against the unwrapped array.
     * Three cases:
     *  - complete (declared len ≤ received): verify the trailing checksum.
     *  - over-length (declared len > received AND > MAX_MSG): the frame is
     *    larger than a single USB packet, so its checksum sits in the missing
     *    tail and can't be verified; accept it on shape (plausible size,
     *    status not FF/FA). This is the 90-byte periodic in a 64-byte packet.
     *  - truncated (declared len > received but ≤ MAX_MSG): the frame fit in
     *    one packet, so a short read is corruption, not a split frame — reject.
     */
    fun isValidResponse(bytes: ByteArray, cmd: Int): Boolean {
        if (bytes.size < 3) return false
        if (bytes[0].toInt() == 0) return false
        val len = bytes[1].toInt() and 0xFF
        if (len <= 0) return false
        // `cmd` is an unsigned command id (0x80..0x95); bytes[2] is a signed
        // Byte, so `.toInt()` sign-extends (0x81 -> -127). Mask to unsigned
        // before comparing, or every real command is rejected.
        if ((bytes[2].toInt() and 0xFF) != cmd) return false
        if (len <= bytes.size) {
            val ck = bytes[len - 1].toInt() and 0xFF
            return ck == checksum(bytes, len - 1)
        }
        // len > bytes.size (arrived short). A frame whose declared length
        // still fits one USB packet (len ≤ MAX_MSG) must not arrive short —
        // that is a truncation/corruption, so reject it. Only a frame larger
        // than the packet (len > MAX_MSG) may legitimately lack its tail
        // (and thus its checksum); accept that on shape.
        if (len <= MAX_MSG) return false
        return bytes.size in 5..MAX_MSG &&
            bytes[3].toInt() and 0xFF != 0xFF &&
            bytes[3].toInt() and 0xFF != 0xFA
    }

    fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF
    fun u16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
    fun s16(b: ByteArray, off: Int): Int {
        var v = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
        if (v > 32767) v -= 65536
        return v
    }
    fun s32(b: ByteArray, off: Int): Int {
        var v = (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
        return v
    }
}
