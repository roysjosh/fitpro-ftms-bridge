package io.ftms.bridge

/**
 * FitPro1-over-USB protocol engine: handshake (device capability discovery),
 * a 100 ms telemetry pump, and the console's workout-control write path.
 * The handshake follows the console's standard initialization sequence:
 *
 *   DeviceInfo(129)
 *     → SupportedDevices(128) + SupportedCommands(136)
 *     → [if listed] SystemInfo(130), VersionInfo(132), SerialNumber(149)
 *     → ReadWriteData(read: StartupFields ∩ supported)
 *     → 100 ms periodic: ReadWriteData(read: PeriodicFields ∩ supported)
 *
 * The client is ready as soon as discovery succeeds.
 */
class FitPro1Client(private val transport: UsbTransport, private val hex: HexLog) {

    var log: ((String) -> Unit)? = null

    // ---- identity (filled by handshake) ----
    @Volatile var swVersion = 0; private set
    @Volatile var hwVersion = 0; private set
    @Volatile var serialNo = 0L; private set
    @Volatile var manufacturerId = 0; private set
    @Volatile var modelNumber = 0; private set
    @Volatile var partNumber = 0; private set
    @Volatile var masterLibVersion = 0; private set
    @Volatile var maxResistanceLevel = -1; private set
    /** Raw 16-bit value the brainboard reports at the TOP resistance level.
     *  FitPro1 exposes no such field — the vendor hard-codes 10000, which does
     *  not match every brainboard. Set per-model at handshake (see
     *  [resistanceFullScaleFor]). */
    @Volatile var resistanceFullScale = 10000; private set
    // FTMS inputs (populated from the one-shot Startup read at handshake):
    @Volatile var gradeSupported = false; private set
    @Volatile var minGrade = 0; private set
    @Volatile var maxGrade = 0; private set
    @Volatile var ready = false
    @Volatile var initialized = false; private set
    @Volatile var lastStatus = -1
    @Volatile var lastError = ""
    @Volatile var latest: Map<Int, Any> = emptyMap()
    /** Monotonically increasing count of successful periodic decodes — a
     *  sequence stamp on every [latest] sample. The FTMS layer uses it to
     *  tell a sample read BEFORE a Control Point mode write acked from one
     *  read after: the write-path transact never advances it (only the
     *  periodic tick does), so a stamp captured at write-ack time is the
     *  staleness boundary for the console samples that are in flight. It is
     *  a sequence number, not per-connection state — [reset] does NOT
     *  clear it. */
    @Volatile var readSeq: Long = 0
        private set

    val supportedCommands = mutableSetOf<Int>()
    val supportedBitFields = mutableSetOf<Int>()

    /** Serializes every USB exchange: the 100 ms read pump and the control
     *  writes share one request/response channel and must never interleave. */
    private val usbLock = Any()

    /** PeriodicFields filtered by the device's capability bitmaps (the app's rule). */
    @Volatile var periodicIds: List<Int> = emptyList()
        private set

    /** ReadWriteData read-section size (frame total = 5 + Σ field sizes). */
    val expectedPeriodicResponseLen: Int
        get() = 5 + periodicIds.sumBy { BitFields.sizeOf(it) }

    // ------------------------------------------------------------------
    // Low-level transact: write frame → settle → one 64-byte read → clean
    // ------------------------------------------------------------------

    fun transact(cmd: Int, label: String, content: ByteArray, retries: Int = 3): ByteArray? {
        for (attempt in 1..retries) {
            if (!transport.connected) {
                lastError = "not connected"
                return null
            }
        val req = FitPro1.frame(cmd, content)
        hex.record("OUT", label, req)
        val raw: ByteArray? = synchronized(usbLock) {
            if (transport.write(req) < 0) {
                ln("write failed: $label (attempt $attempt)")
                null
            } else {
                Thread.sleep(READ_DELAY_MS) // 80 ms: the console's response settle time
                val r = transport.read()
                if (r == null) ln("read failed: $label (attempt $attempt)")
                r
            }
        }
        if (raw == null) continue
        hex.record("IN", label, raw)
            val clean = FitPro1.unwrapFrame(raw)
            if (FitPro1.isValidResponse(clean, cmd)) {
                val len = clean[1].toInt() and 0xFF
                val frame = clean.copyOf(len) // keep just the declared frame length
                lastStatus = if (frame.size > 3) frame[3].toInt() and 0xFF else -1
                lastError = ""
                return frame
            }
            ln("bad response: $label (attempt $attempt) — raw dumped to hex log")
        }
        lastError = "transact($label) failed after $retries attempts"
        return null
    }

    /**
     * Settles the device input path before the first real command is sent.
     *
     * The device spontaneously emits 64-byte frames filled with 0xFF as
     * keep-alive / no-data signals. When the host writes one such all-0xFF
     * frame, the device answers with a 64-byte frame that is 0xFF at every
     * position except index 3, which carries a device-dependent value and is
     * treated as a wildcard when matching.
     *
     * After (re)attachment the device's input path may still hold stale
     * or in-flight replies from before the attach. A single confirming reply
     * could be one of those stale frames; two consecutive confirmations are
     * required before we are confident the path is empty and the device is
     * answering our own probes.
     *
     * The routine is bounded to a fixed round budget so it cannot block the
     * handshake indefinitely. Giving up is NOT fatal to the caller: the
     * handshake proceeds either way, so false here is advisory.
     *
     * @return true once two consecutive probe replies have confirmed the link
     *         is settled; false if the connection dropped or the budget ran
     *         out before two consecutive confirmations were seen.
     */
    fun flushConsoleInput(): Boolean {
        if (!transport.connected) {
            ln("flushConsoleInput: not connected, skipping settle routine")
            return false
        }
        ln("flushConsoleInput: settling input path (max $FLUSH_MAX_ROUNDS rounds)")

        val probe = ByteArray(FLUSH_PROBE_FRAME_SIZE) { 0xFF.toByte() }
        var streak = 0

        for (round in 1..FLUSH_MAX_ROUNDS) {
            if (!transport.connected) {
                ln("flushConsoleInput: connection lost during round $round, giving up")
                return false
            }

            val written = transport.write(probe)
            if (written < 0) {
                streak = 0
                ln("flushConsoleInput: round $round: probe write failed")
            } else {
                val reply = transport.read()
                when {
                    reply == null -> {
                        streak = 0
                        ln("flushConsoleInput: round $round: no reply (read timeout)")
                    }
                    isConfirmingReply(reply) -> {
                        streak++
                        ln("flushConsoleInput: round $round: confirming reply (streak=$streak)")
                        if (streak >= 2) {
                            ln("flushConsoleInput: input path settled after $round rounds")
                            return true
                        }
                    }
                    else -> {
                        streak = 0
                        ln("flushConsoleInput: round $round: non-confirming reply, streak reset")
                    }
                }
            }

            if (round < FLUSH_MAX_ROUNDS) {
                Thread.sleep(FLUSH_INTER_ROUND_DELAY_MS)
            }
        }

        ln("flushConsoleInput: budget exhausted ($FLUSH_MAX_ROUNDS rounds), final streak=$streak, giving up")
        return false
    }

    /**
     * A reply confirms the round when it is exactly 64 bytes long and matches
     * the all-0xFF probe at every index except index 3. Index 3 is wildcarded
     * because the device fills that position with a device-dependent value in
     * its reply, so we cannot expect 0xFF there.
     */
    private fun isConfirmingReply(reply: ByteArray): Boolean {
        if (reply.size != FLUSH_PROBE_FRAME_SIZE) return false
        for (i in reply.indices) {
            if (i == 3) continue
            if (reply[i] != 0xFF.toByte()) return false
        }
        return true
    }

    // ------------------------------------------------------------------
    // Handshake
    // ------------------------------------------------------------------

    fun handshake(): Boolean {
        initialized = false
        ready = false
        supportedCommands.clear()
        supportedBitFields.clear()
        periodicIds = emptyList()
        ln("== handshake begin ==")

        // 0. Console input settle — runs immediately after the USB connection
        //    is up, before the first command. Failure is logged but not fatal:
        //    the handshake proceeds either way.
        flushConsoleInput()

        // 1. DeviceInfo: [sw][hw][serial u32][mfr u16][sections u8][sections×bitmap]
        val di = transact(FitPro1.CMD_DEVICE_INFO, "DeviceInfo", ByteArray(0)) ?: return false
        val p = di
        // Content starts at frame[3]: [sw][hw u16][serial u32][mfr u16][sections][bitmaps]
        swVersion = FitPro1.u8(p, 3)
        hwVersion = FitPro1.u16(p, 4)
        serialNo = FitPro1.u32(p, 6)
        manufacturerId = FitPro1.u16(p, 10)
        val sections = FitPro1.u8(p, 12)
        for (s in 0 until sections) {
            if (13 + s >= p.size) break
            val bmp = FitPro1.u8(p, 13 + s)
            for (b in 0..7) if (bmp and (1 shl b) != 0) supportedBitFields.add(s * 8 + b)
        }
        ln("DeviceInfo: sw=$swVersion hw=$hwVersion serial=$serialNo mfr=$manufacturerId " +
                "sections=$sections supportedFields=${supportedBitFields.size} " +
                "bitfields=${supportedBitFields.sorted().joinToString(",")}")

        // 2. SupportedDevices + SupportedCommands (the app queues both, awaits both)
        if (transact(FitPro1.CMD_SUPPORTED_DEVICES, "SupportedDevices", ByteArray(0)) == null) return false
        val sc = transact(FitPro1.CMD_SUPPORTED_COMMANDS, "SupportedCommands", ByteArray(0)) ?: return false
        val scLen = sc[1].toInt() and 0xFF
        for (i in 4 until scLen.coerceAtMost(sc.size)) {
            supportedCommands.add(sc[i].toInt() and 0xFF)
        }
        ln("SupportedCommands(${supportedCommands.size}): " +
            supportedCommands.sorted().joinToString(",") { String.format("%02X", it) })

        // 3. SystemInfo (content [fetchMcuName=1][0]) — model + part number
        if (FitPro1.CMD_SYSTEM_INFO in supportedCommands) {
            val si = transact(FitPro1.CMD_SYSTEM_INFO, "SystemInfo", byteArrayOf(1, 0))
            if (si != null) {
                modelNumber = FitPro1.u32(si, 7).toInt()
                partNumber = FitPro1.u32(si, 11).toInt()
                ln("SystemInfo: model=$modelNumber part=$partNumber")
            }
        }

        // 4. VersionInfo (content [fetchMcuName=0][fetchConsoleName=1]) — MLV
        if (FitPro1.CMD_VERSION_INFO in supportedCommands) {
            val vi = transact(FitPro1.CMD_VERSION_INFO, "VersionInfo", byteArrayOf(0, 1))
            if (vi != null) {
                masterLibVersion = FitPro1.u8(vi, 4)
                ln("VersionInfo: masterLibVersion=$masterLibVersion")
            }
        }

        // 5. SerialNumber (echo check + log)
        if (FitPro1.CMD_SERIAL_NUMBER in supportedCommands) {
            val sn = transact(FitPro1.CMD_SERIAL_NUMBER, "SerialNumber", ByteArray(0))
            if (sn != null && sn.size > 5) {
                val n = minOf(FitPro1.u8(sn, 4), sn.size - 5)
                if (n > 0) ln("SerialNumber(ascii): ${String(sn, 5, n)}")
            }
        }

        // The client is ready once the discovery above succeeds.
        ready = true

        // 7. Policy input (read-only here: the console writes RequireStart
        //    requested itself; we only observe it)
        val requireStart = supportedBitFields.contains(108)
        ln("RequireStartRequested(108) supported=$requireStart ← decides the advertising policy")

        // 8. Startup read (StartupFields ∩ supported)
        val startup = BitFields.startupIds.filter { it in supportedBitFields }
        ln("StartupFields: ${startup.size}/${BitFields.startupIds.size} supported")
        if (startup.isNotEmpty()) {
            val r = transact(FitPro1.CMD_READWRITE, "StartupData", BitFields.readContent(startup))
            if (r != null) {
                val values = BitFields.decode(startup, r.copyOfRange(4, r.size))
                for ((id, v) in values) ln("  ${BitFields.nameOf(id)}($id) = ${render(id, v)}")
                maxResistanceLevel = (values[42] as? Number)?.toInt() ?: -1
                resistanceFullScale = resistanceFullScaleFor(modelNumber, partNumber)
                ln("Resistance full-scale = $resistanceFullScale raw units (model $modelNumber / part $partNumber, maxLvl $maxResistanceLevel)")
                // The startup frame is ≤64 bytes (46 for this device) so it is
                // NOT truncated — the grade range is trustworthy for FTMS.
                gradeSupported = supportedBitFields.contains(1)
                // Grades are SIGNED on the wire (the bike can be downhill). The
                // decoder reads them as u16; sign-extend to the true s16 value so
                // the FTMS inclination range (u8, clamped 0..255) is correct.
                minGrade = (values[28] as? Number)?.toInt()?.let { if (it in 0x8000..0xFFFF) it - 0x10000 else it } ?: 0
                maxGrade = (values[27] as? Number)?.toInt()?.let { if (it in 0x8000..0xFFFF) it - 0x10000 else it } ?: 0
                ln("FTMS ranges: gradeSupported=$gradeSupported minGrade=$minGrade maxGrade=$maxGrade (×100 % on wire)")
            }
        }

        // 9. Periodic list (the app's exact rule)
        periodicIds = BitFields.periodicIds.filter { it in supportedBitFields }
        ln("PeriodicFields: ${periodicIds.size}/${BitFields.periodicIds.size} supported, " +
                "expected response len = $expectedPeriodicResponseLen bytes (transport max 64)")
        if (expectedPeriodicResponseLen > 64) {
            ln("WARNING: periodic frame exceeds the 64-byte transport — live capture will show how " +
                    "the device delivers it (hex log)")
        }

        initialized = true
        ln("== handshake done: ready=$ready ==")
        return true
    }

    // ------------------------------------------------------------------
    // Periodic pump (one 100 ms round)
    // ------------------------------------------------------------------

    private var parked: ByteArray? = null
    private var parkedLabel = ""

    /**
     * One periodic round. Returns false on transport failure (the service
     * counts 5 consecutive failures as comms loss → re-attach).
     */
    fun tick(): Boolean {
        if (!initialized) return false
        val fields = periodicIds
        if (fields.isEmpty()) return false

        val expected = expectedPeriodicResponseLen
        val req = FitPro1.frame(FitPro1.CMD_READWRITE, BitFields.readContent(fields))
        hex.record("OUT", "Periodic", req)
        val raw: ByteArray? = synchronized(usbLock) {
            if (transport.write(req) < 0) {
                lastError = "periodic write failed"
                null
            } else {
                Thread.sleep(READ_DELAY_MS)
                val r = transport.read()
                if (r == null) lastError = "periodic read failed"
                r
            }
        }
        if (raw == null) return false
        hex.record("IN", "Periodic", raw)

        val clean = FitPro1.unwrapFrame(raw)
        if (!FitPro1.isValidResponse(clean, FitPro1.CMD_READWRITE)) {
            lastError = "bad periodic response (hex-dumped)"
            return false
        }

        // Pipeline-drift recovery: a slow
        // response from the previous round may arrive tagged to this one.
        val parkedNow = parked
        parked = null
        val frame = if (parkedNow != null &&
            (parkedNow[1].toInt() and 0xFF) == expected &&
            (clean[1].toInt() and 0xFF) != expected
        ) {
            parked = clean.copyOf(clean[1].toInt() and 0xFF)
            parkedLabel = "Periodic(prev)"
            ln("late response matched (parked len=${parkedNow[1].toInt()}) — using it")
            hex.record("PARK", parkedLabel, raw)
            parkedNow
        } else {
            if (clean[1].toInt() and 0xFF != expected) {
                parked = clean.copyOf(clean[1].toInt() and 0xFF)
                parkedLabel = "Periodic"
                lastError = "response len ${clean[1].toInt()} != expected $expected (parked)"
                return false
            }
            clean
        }
        val f = frame.copyOf(frame[1].toInt() and 0xFF)
        lastStatus = f[3].toInt() and 0xFF

        if (lastStatus != FitPro1.ST_DONE && lastStatus != FitPro1.ST_IN_PROGRESS) {
            lastError = "periodic status ${FitPro1.statusName(lastStatus)}"
            return false
        }
        // Stamp this successful decode before publishing it: the FTMS layer
        // compares this sequence number against the stamp captured at a
        // Control Point mode-write's ack to reject samples that predate the
        // console applying that write.
        readSeq += 1
        latest = BitFields.decode(fields, f.copyOfRange(4, f.size))
        lastError = ""
        return true
    }

    /** Drop parsed state on disconnect / re-attach. */
    fun reset() {
        initialized = false
        ready = false
        latest = emptyMap()
        periodicIds = emptyList()
        parked = null
        gradeSupported = false
        minGrade = 0
        maxGrade = 0
    }

    // ------------------------------------------------------------------
    // Control Point → console writes (ReadWriteData write section)
    // Each is serialized on usbLock with the read pump; all are reversible,
    // normal operational writes (no flash / bootloader / reset persona).
    // ------------------------------------------------------------------

    /** Issue a console write (ReadWriteData write-section). True if acked Done. */
    private fun writeBitFields(writeIds: List<Int>, values: Map<Int, Int>, label: String): Boolean {
        if (!initialized) { lastError = "$label: not initialized"; return false }
        if (!ready) { lastError = "$label: not ready"; return false }
        val r = transact(FitPro1.CMD_READWRITE, label, BitFields.writeContent(writeIds, values))
        return r != null && lastError.isEmpty()
    }

    /** Grade (u16, ×100 %). The caller (FTMS layer) clamps to
     *  [minGrade, maxGrade] before calling; the console rejects out-of-range. */
    fun writeGrade(grade: Int): Boolean {
        ln("write Grade=$grade (×100 %)")
        return writeBitFields(listOf(1), mapOf(1 to grade), "Grade")
    }

    /** Resistance (u16 raw, 0..resistanceFullScale). */
    fun writeResistance(raw: Int): Boolean {
        ln("write Resistance=$raw (0..$resistanceFullScale)")
        return writeBitFields(listOf(2), mapOf(2 to raw), "Resistance")
    }

    /**
     * The raw resistance full-scale for a given model — the raw 16-bit value the
     * brainboard reports at its TOP resistance level. FitPro1 declares only the
     * level *count* (MaxResistanceLevel, bitfield 42), never this raw span, so it
     * is a per-model constant we must supply.
     *
      *  - S15i (model 5121): the `+` key steps raw 0,284,…,5964 = levels 1..22
      *    (step 284), so its full scale is 5964. The vendor app's hard-coded
      *    10000 would read this bike's max (5964) as level 13, so it is wrong
      *    for the S15i.
      *  - Every other model falls back to the FitPro1 vendor default, 10000:
      *    other vendor models report different level counts and scales, so the
      *    default stays in place until a model's own full-scale value is known.
      *    Add an entry here for each model with its full-scale value.
      */
    private fun resistanceFullScaleFor(model: Int, part: Int): Int = when (model) {
        5121 -> 5964   // S15i (22 levels, step 284)
        else -> 10000  // FitPro1 vendor default
    }

    /** 1-based console level for a raw value (0..resistanceFullScale).
     *  0-based on the wire: level 1 is raw 0, the top level is raw full-scale. */
    fun levelFromRaw(raw: Int): Int {
        val maxLvl = maxResistanceLevel
        if (maxLvl <= 1) return 1
        val fs = if (resistanceFullScale > 0) resistanceFullScale else 10000
        return (1 + raw.toLong() * (maxLvl - 1) / fs).toInt().coerceIn(1, maxLvl)
    }

    /** Raw value to WRITE to the brainboard for a 1-based console level.
     *  Inverse of [levelFromRaw]; level below 1 clamps to the raw 0 minimum. */
    fun rawFromLevel(level: Int): Int {
        val maxLvl = maxResistanceLevel
        if (maxLvl <= 1) return 0
        val fs = if (resistanceFullScale > 0) resistanceFullScale else 10000
        return ((level - 1).toLong() * fs / (maxLvl - 1)).toInt().coerceIn(0, fs)
    }

    /** Write WorkoutMode (u8) only. (StartRequested 96 is read-only on FitPro1 —
     *  the console sets it when its own START key is pressed; we never write it.) */
    fun writeWorkoutMode(target: Int): Boolean {
        ln("write WorkoutMode=$target")
        return writeBitFields(listOf(12), mapOf(12 to target), "WorkoutMode")
    }

    /** Start a workout, matching the vendor app: WorkoutMode→Running(2) plus a
     *  re-assert of the current targets so they latch once the console is active.
     *  (A spin-bike start packet is Mode + Resistance; Gear is a packed field we
     *  don't re-synthesize here.) */
    fun startWorkout(): Boolean {
        val cur = (latest[12] as? Number)?.toInt() ?: 1
        val resume = (cur == 3 || cur == 20)
        // Resuming from Pause keeps the held level. Starting fresh from Idle the
        // parked raw (e.g. 2500) is NOT a real level, so begin at the minimum.
        val res = if (resume) ((latest[2] as? Number)?.toInt() ?: 0) else 0
        val target = if (resume) 13 else 2
        ln("START workout: WorkoutMode=$target (${if (resume) "Resume(13)" else "Running(2)"}), Resistance=$res")
        return writeBitFields(listOf(2, 12), mapOf(2 to res, 12 to target), "StartWorkout")
    }

    /** Stop a workout: targets → 0, WorkoutMode→Idle(1) (the vendor's teardown). */
    fun stopWorkout(): Boolean {
        ln("STOP workout: WorkoutMode=1 (Idle), Grade=0")
        return writeBitFields(listOf(1, 12), mapOf(1 to 0, 12 to 1), "StopWorkout")
    }

    /** Reset (Control Point op 0x01): targets → defaults, WorkoutMode → Idle(1). */
    fun writeReset(): Boolean {
        ln("write Reset: Resistance=0 Grade=0 WorkoutMode=1(Idle)")
        return writeBitFields(listOf(2, 1, 12), mapOf(2 to 0, 1 to 0, 12 to 1), "Reset")
    }

    /** Keep-awake (spec §7): IdleModeLockout(95)=1 on FTMS connect, 0 on drop.
     *  A benign, reversible operational write that stops the idle-timeout from
     *  NAK-silencing the link while a central is attached. */
    fun setIdleLockout(on: Boolean) {
        if (!supportedBitFields.contains(95)) { ln("CTRL IdleLockout skipped (95 not supported)"); return }
        writeBitFields(listOf(95), mapOf(95 to (if (on) 1 else 0)), "IdleLockout=${if (on) 1 else 0}")
    }

    private fun render(id: Int, v: Any): String =
        BitFields.format(id, v, maxResistanceLevel) ?: v.toString()

    private fun ln(s: String) {
        logRing.d(TAG, s)
        log?.invoke(s)
    }

    /** Engine log ring (also tailed by the debug socket). */
    val logRing = SimpleLog()

    companion object {
        private const val TAG = "FitPro1Client"
        const val READ_DELAY_MS: Long = 80 // 80 ms: the console's response settle time

        // Input settle (pre-handshake): probe frame size, round budget, and the
        // pause between rounds.
        const val FLUSH_PROBE_FRAME_SIZE = 64
        const val FLUSH_MAX_ROUNDS = 10
        const val FLUSH_INTER_ROUND_DELAY_MS = 500L
    }
}

/** Tiny tag-log that the debug socket can also tail (avoids a Log dependency). */
class SimpleLog {
    private val lines = java.util.LinkedList<String>()

    fun d(tag: String, msg: String) {
        val line = "${tag}: $msg"
        android.util.Log.d(tag, msg)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > 500) lines.removeFirst()
        }
    }

    fun tail(n: Int): String = synchronized(lines) {
        val list = lines.toList()
        list.subList((list.size - n).coerceAtLeast(0), list.size).joinToString("\n")
    }
}
