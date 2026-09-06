package io.ftms.bridge

import java.util.IdentityHashMap

/**
 * Fitness Machine Status (0x2ADA) value encoder: [Op Code][Parameter…],
 * parameter widths per FTMS v1.0.1 Table 4.26.
 *
 *  - N/A rows — 0x00 (RFU), 0x01 (Reset), 0x03, 0x04, 0xFF (Control
 *    Permission Lost) → op code only, even if [param] is given.
 *  - 0x02 (Control Information, Table 4.16), 0x09 (Target Heart Rate,
 *    UINT8), 0x14 (Spin Down Status, Table 4.27) → 1-octet parameter.
 *  - 0x05, 0x06, 0x07, 0x08, 0x0A, 0x0B, 0x0C, 0x0E, 0x13, 0x15 →
 *    2-octet little-endian; [param] is signed and the 2-octet fields carry
 *    its low 16 bits.
 *  - 0x0D (Targeted Distance, UINT24) → 3-octet little-endian.
 *  - 0x16–0xFE (RFU rows) → op code only (N/A), so the encoder never
 *    fabricates a parameter for a reserved op code.
 *  - 0x0F–0x12 (heart-rate-zone time arrays / indoor bike simulation
 *    parameters) and every other unlisted op code → defensive 1-octet
 *    parameter: this device never emits those in v1, the encoder just stays
 *    total.
 *
 * Device declaration for the 0x07 row (FTMS Test Specification): the TS
 * defines two alternative conformant behaviors — BV-08-C (parameter UINT8)
 * and BV-25-C (parameter SINT16). Table 4.15's primary UINT8 form is
 * superseded by this device's BV-25 declaration: the resistance scale is in
 * 0.1-level units and reaches maxLevel×10 (up to 1000 — see
 * [FtmsServer.buildResistanceRange]), which exceeds a UINT8. So the 0x07
 * parameter is SINT16 (2 octets); the 0x2AD6 range and 0x2AD2 data field
 * use the same 0.1-level units (the TS cross-checks all three).
 *
 * [param] == null → op code only (N/A rows).
 */
object MachineStatus {
    fun encode(op: Int, param: Int?): ByteArray {
        if (param == null) return byteArrayOf(op.toByte())
        val p = param
        return when (op) {
            // Table 4.26 "Parameter: N/A" rows — the op code stands alone.
            0x00, 0x01, 0x03, 0x04, 0xFF -> byteArrayOf(op.toByte())
            // 1-octet parameters.
            0x02, 0x09, 0x14 -> byteArrayOf(op.toByte(), (p and 0xFF).toByte())
            // 2-octet little-endian parameters (low 16 bits of [param]).
            0x05, 0x06, 0x07, 0x08, 0x0A, 0x0B, 0x0C, 0x0E, 0x13, 0x15 ->
                byteArrayOf(op.toByte(), (p and 0xFF).toByte(), ((p ushr 8) and 0xFF).toByte())
            // 3-octet little-endian (UINT24).
            0x0D -> byteArrayOf(op.toByte(), (p and 0xFF).toByte(), ((p ushr 8) and 0xFF).toByte(), ((p ushr 16) and 0xFF).toByte())
            // Table 4.26: 0x16–0xFE are RFU with parameter N/A.
            in 0x16..0xFE -> byteArrayOf(op.toByte())
            // Defensive: variable-length / unlisted op codes (incl. 0x12
            // Indoor Bike Simulation Parameters) are emitted via the raw-array
            // overload below, never through this Int-typed one.
            else -> byteArrayOf(op.toByte(), (p and 0xFF).toByte())
        }
    }

    /** Raw-array form for the variable-length Table 4.26 rows (the 0x12
     *  Indoor Bike Simulation Parameters array, and the heart-rate-zone time
     *  arrays): [Op Code] followed verbatim by [param]. */
    fun encode(op: Int, param: ByteArray): ByteArray =
        byteArrayOf(op.toByte()) + param
}

/**
 * Parses a SINT16 little-endian parameter at [off] of a Control Point write.
 * Table 4.15 defines the signed parameter forms (0x03 Set Target Inclination:
 * SINT16, resolution 0.1 %); Table 4.26 carries the same wire format back out
 * on Machine Status (0x06 Target Incline Changed: SINT16, 0.1 %). Returns
 * null when the write is too short to hold the 2-octet parameter — such a
 * write is answered with the Invalid Parameter result code (Table 4.24)
 * instead of being coerced to some value.
 */
fun decodeS16(value: ByteArray, off: Int): Int? {
    if (value.size < off + 2) return null
    val raw = (value[off].toInt() and 0xFF) or ((value[off + 1].toInt() and 0xFF) shl 8)
    return if (raw >= 0x8000) raw - 0x10000 else raw
}

/**
 * Latches the console's WorkoutMode for the 1 Hz FTMS notification pump.
 *
 * The hazard it guards: the pump runs at 1 Hz but the console is read over
 * USB at 10 Hz, so right after a Control Point mode write acks, the pump's
 * next sample may still be a read taken BEFORE the console applied the
 * write. Acting on such a sample would clobber the latch the write
 * optimistically set and re-fire the transition — duplicating the Machine
 * Status the control op already emitted (§4.17.1 client-driven update).
 *
 * [noteWrite] therefore stamps the optimistic latch with the
 * [FitPro1Client.readSeq] of the newest periodic decode at the moment the
 * write acked. A sample [process]ed with a sequence number <= that stamp
 * predates the console applying the write and is dropped as [Stale] (the
 * latch AND the pending stamp stay untouched, so every subsequent stale
 * read is dropped too). The first FRESHER sample clears the stamp and is
 * authoritative: it either confirms the write ([Unchanged] — silent, the
 * write already announced) or reveals the console did not apply it
 * ([Transition] — announcing the true state is the correct correction).
 *
 * Pure (no android.* types); thread-confined to the caller (in
 * [FtmsServer] both the GATT callbacks and the 1 Hz pump run on the main
 * thread; [FitPro1Client.readSeq] is @Volatile for the cross-thread read).
 */
class ModeLatch {

    /** Last announced/assumed WorkoutMode; -1 until the first observation. */
    var latched: Int = -1; private set
    /** readSeq of the newest periodic decode at the moment a Control Point
     *  mode write acked; null while no write is pending confirmation. */
    var pendingAck: Long? = null; private set

    /** The Control Point drove a mode change to [target] and already
     *  announced it; samples with readSeq <= [ackSeq] predate the console
     *  applying the write and must not move the latch. */
    fun noteWrite(target: Int, ackSeq: Long) {
        latched = target
        pendingAck = ackSeq
    }

    /** Drop all latched state (central disconnect / full teardown) so a
     *  (re)connection re-arms from a First instead of announcing a ghost
     *  transition to a stale pre-disconnect state. */
    fun reset() {
        latched = -1
        pendingAck = null
    }

    sealed interface TickResult {
        /** Sample read before the pending write's ack — ignore entirely. */
        object Stale : TickResult
        /** First observation (re)arming the latch. */
        data class First(val mode: Int) : TickResult
        /** A fresh sample showing a mode the latch does not hold. */
        data class Transition(val prev: Int, val mode: Int) : TickResult
        /** A fresh sample matching the latch (or the pending write). */
        object Unchanged : TickResult
    }

    /** Feed one console sample, sequence-stamped [seq], into the latch. */
    fun process(seq: Long, mode: Int): TickResult {
        val pending = pendingAck
        if (pending != null && seq <= pending) return TickResult.Stale
        pendingAck = null
        if (latched < 0) {
            latched = mode
            return TickResult.First(mode)
        }
        if (mode != latched) {
            val prev = latched
            latched = mode
            return TickResult.Transition(prev, mode)
        }
        return TickResult.Unchanged
    }
}

/**
 * Idempotency decisions for the Control Point Start or Resume (0x07) and
 * Stop or Pause (0x08) ops.
 *
 * The decisions read the MODE LATCH ([ModeLatch].latched) — never the raw
 * console sample that sits behind it: the console is read at 10 Hz, so the
 * latest sample may PREdate the very mode write a previously handled 0x07/
 * 0x08 op just issued, and deciding "already in this state" from that stale
 * sample is exactly what let a duplicate Start re-answer Success. The latch
 * is sequence-gated (samples read at or before the pending write's ack are
 * dropped — see [ModeLatch.process]), so it is the authoritative "current
 * mode" for these idempotency checks.
 *
 * An op that finds the machine ALREADY in the state it creates answers
 * "Operation Failed" (0x04, FTMS Table 4.24) with NO console write: the TS
 * test cases FTMS/SR/SPE/BV-01-C (Start or Resume) and BV-02-C (Stop or
 * Pause) require exactly this — after the first 0x07 answers
 * [0x80][0x07][0x01], the SECOND 0x07 must get the indication
 * [0x80][0x07][0x04] (exactly two response indications for the two
 * writes); likewise the duplicate 0x08 (Stop, Control Information 0x01)
 * after a successful 0x08 must get [0x80][0x08][0x04]. FTMS §4.16.2.x
 * states it directly: a Start or Resume (or Stop or Pause) Op Code "that
 * results in an error condition (e.g., the fitness machine has already
 * been started/stopped)" shall be indicated with the Result Code set to
 * "Operation Failed". A rejected duplicate performs no console round-trip
 * and emits no Machine/Training Status.
 *
 * The -1 sentinel ("no console sample observed yet") is not a mode:
 * [startDecision] treats it as the pre-start Idle state — a fresh start,
 * matching the historical "assume Idle" default — and [stopDecision] maps
 * it to 2 (Running), the historical "assume running, allow the stop"
 * default.
 *
 * Pure (no android.* types); thread-confined to the caller.
 */
object StartStop {

    /** WorkoutMode values: 1=Idle, 2=Running, 3=Pause, 13=Resume,
     *  20=PauseOverride (console wire modes, see [BitFields.modeName]). */

    /** Start or Resume (0x07): (target, result) — result != 0 means
     *  "answer with this result, write nothing to the console". */
    fun startDecision(latchedMode: Int): Pair<Int, Int> {
        // Explicit Pair(...) (not the (a, b) literal): the toolchain's
        // parser does not accept a parenthesized pair in a when-entry
        // position — it reads (a as a single parenthesized expression.
        return when (latchedMode) {
            // Already started/resumed: a second Start is an error condition
            // → 0x04 Operation Failed (Table 4.24), no console write.
            2, 13 -> Pair(-1, 0x04)
            // Paused (3) or PauseOverride (20): the op RESUMES the workout.
            3, 20 -> Pair(13, 0)
            // Idle (1), the -1 "never observed" sentinel, and anything else:
            // a fresh start.
            else -> Pair(2, 0)
        }
    }

    /** Stop or Pause (0x08, Control Information [subOp]: 0x01 Stop,
     *  0x02 Pause, Table 4.16): (target, result) — result != 0 means
     *  "answer with this result, write nothing to the console". */
    fun stopDecision(latchedMode: Int, subOp: Int): Pair<Int, Int> {
        // -1 "never observed" is not a mode — assume Running (2) so the
        // stop is still allowed (the historical default).
        val cur = if (latchedMode < 0) 2 else latchedMode
        val target = if (subOp == 0x02) 3 else 1
        // Already in exactly that state → 0x04 Operation Failed (Table
        // 4.24), no console write. (Explicit Pair(...), same parser
        // reason as [startDecision].)
        return if (cur == target) Pair(-1, 0x04) else Pair(target, 0)
    }
}

/**
 * The Control Information parameter (1 octet) of the Control Point Stop or
 * Pause (0x08) op, per FTMS Table 4.16:
 *
 *   0x00       Reserved for Future Use
 *   0x01       Stop
 *   0x02       Pause
 *   0x03–0xFF  Reserved for Future Use
 *
 * Only 0x01 and 0x02 are defined; every other octet — including 0x00 — is
 * RFU, so a Control Point write for 0x08 that does not carry the octet
 * (a 1-octet write) cannot be completed with any "default", and one that
 * carries a reserved value has no meaning: both are [Outcome.INVALID] and
 * are answered with the Invalid Parameter result code (Table 4.24) instead
 * of being coerced to Stop.
 *
 * Pure (no android.* types); thread-confined to the caller (in
 * [FtmsServer] the GATT write callbacks run on the main thread).
 */
object StopPause {

    enum class Outcome { STOP, PAUSE, INVALID }

    /** Table 4.16: 0x01 Stop, 0x02 Pause; 0x00 and 0x03–0xFF are RFU.
     *  A write without the parameter octet is INVALID. Trailing bytes
     *  beyond the 1-octet parameter are ignored (the spec does not define
     *  a longer form). [value] is the FULL Control Point write
     *  ([Op Code][param…]). */
    fun parse(value: ByteArray): Outcome {
        if (value.size < 2) return Outcome.INVALID
        // The octet is signed as a Java byte (0xFF is -1); the table is
        // defined on 0x00–0xFF, so mask.
        return when (value[1].toInt() and 0xFF) {
            0x01 -> Outcome.STOP
            0x02 -> Outcome.PAUSE
            else -> Outcome.INVALID
        }
    }
}

/**
 * The payload of the Set Indoor Bike Simulation Parameters Control Point op
 * (0x11), minus the leading op code — the "Simulation Parameter Array" of
 * FTMS v1.0.1 Table 4.20, six octets:
 *
 *   [Wind SINT16, 0.001 m/s][Grade SINT16, 0.01 %][Crr UINT8, 0.0001][Cw UINT8, 0.01]
 *
 * This is how virtual-trainer apps (Zwift, MyWhoosh) drive a bike: they push
 * the course grade + wind and expect the trainer to respond. The Grade field
 * is in 0.01 % units — the SAME unit the FitPro1 console reports its grade
 * in (×100 %) — so it maps to the console wire grade 1:1 (unlike 0x03's
 * 0.1 %, which needs ×10).
 *
 * Pure (no android.* types); thread-confined to the caller (in [FtmsServer]
 * the GATT write callbacks run on the main thread).
 */
object SimulationParams {

    /** The four decoded fields of a Simulation Parameter Array. [wind] and
     *  [grade] are signed (SINT16); [crr] and [cw] are unsigned (UINT8). */
    data class Params(val wind: Int, val grade: Int, val crr: Int, val cw: Int)

    /** Parse the six-octet array at [off] of a Control Point write ([value]
     *  is the full [Op Code][param…] write, so the array sits at offset 1).
     *  Returns null when the write is too short to hold all four fields —
     *  the caller answers Invalid Parameter (Table 4.24) instead of reading
     *  past the end or coercing a truncated write to some value. */
    fun parse(value: ByteArray, off: Int): Params? {
        if (value.size < off + 6) return null
        val wind = decodeS16(value, off) ?: return null
        val grade = decodeS16(value, off + 2) ?: return null
        val crr = value[off + 4].toInt() and 0xFF
        val cw = value[off + 5].toInt() and 0xFF
        return Params(wind, grade, crr, cw)
    }
}

/**
 * Monotonic procedure ids + a cancel watermark for the Control Point
 * console round-trips the FTMS server accepts on the main thread and
 * executes on the worker thread.
 *
 * Why ids at all: FTMS §4.16.4 starts the procedure when the Server
 * sends the Write Response, but the procedure's CONSOLE side completes
 * later — the worker performs the blocking USB transact and then posts
 * the result back to the main thread, where the state effects (mode
 * latch, announced baselines, Machine/Training Status emissions, the
 * [0x80][op][result] indication) are applied. If the server is torn down
 * (stop()/closeGatt) while such a procedure is in flight, the late
 * post-back must be a no-op: it must not re-latch the mode (closeGatt
 * already reset the latch — a noteWrite in the post-back would
 * resurrect it and ghost-announce a transition on the next start) and
 * it must not deliver indications into an already-closed GATT server.
 * A monotonic id plus a "cancelled up to here" watermark lets the
 * post-back recognize that it was cancelled: [cancelAll] (the full
 * teardown) bumps the watermark to the highest id ever issued, and
 * [isCancelled] / [end] then treat every id at or below the watermark
 * as dead. The ids are never recycled for the same reason — a recycled
 * id could pass the watermark check for a post-back of an OLDER,
 * cancelled procedure.
 *
 * [begin] is called on the main thread (the GATT write callback) before
 * the console work is posted to the worker; [end] and [isCancelled] are
 * called on the main thread (the post-back). [cancelAll] is called by
 * whichever thread tears the server down (the worker, via the service
 * stop; the main thread, on a start() failure) — hence the @Volatile
 * fields, and why [FtmsServer] keeps a @Volatile main-thread mirror of
 * [inFlight] it re-syncs after every gate call.
 *
 * Pure (no android.* types).
 */
class ProcedureGate {

    /** Next id to issue. Monotonic; never reset (recycling ids would
     *  let an old post-back pass the [cancelAll] watermark). */
    @Volatile private var nextId = 0

    /** Number of accepted procedures whose post-back has not run yet. */
    @Volatile private var pending = 0

    /** Highest procedure id cancelled by [cancelAll]; ids <= this are
     *  dead — their post-backs must no-op. -1 while no cancelAll ran. */
    @Volatile private var watermark = -1

    /** True while at least one accepted procedure awaits its post-back
     *  (the state FTMS §4.16.3's "procedure already in progress" error
     *  is defined against). */
    val inFlight: Boolean get() = pending > 0

    /** Accept a new procedure; returns its monotonic id. [op] is part
     *  of the signature for call-site symmetry and diagnostics; the
     *  gate's own state is id-count only. */
    fun begin(op: Int): Int {
        nextId += 1
        val id = nextId
        pending += 1
        return id
    }

    /** [id]'s console round-trip completed; drops it from the pending
     *  count. NO-OP when [id] is at or below the [cancelAll] watermark
     *  — the procedure was cancelled in the meantime and its post-back
     *  must neither throw nor flip the count. */
    fun end(id: Int) {
        if (id <= watermark) return
        if (pending > 0) pending -= 1
    }

    /** True when [id] was cancelled by a [cancelAll] — its post-back
     *  must return without touching any state. */
    fun isCancelled(id: Int): Boolean = id <= watermark

    /** Cancel every accepted procedure (full server teardown): bumps
     *  the watermark to the highest id ever issued and drops the
     *  pending count, so the in-flight post-backs no-op and a new
     *  client's writes are not blocked by a ghost procedure. */
    fun cancelAll() {
        watermark = nextId
        pending = 0
    }
}

/**
 * Per-characteristic CCCD (Client Characteristic Configuration) subscription
 * state. GATT delivers a notification/indication ONLY to clients that
 * enabled the specific characteristic's CCCD — not to "clients that are
 * connected" — so a shared connected-set would over-deliver telemetry to
 * centrals that never subscribed (and a Control Point write must not grant
 * any subscription at all).
 *
 * Pure (no android.* types): centrals are keyed by their address String.
 * [registerDescriptor] maps a descriptor INSTANCE to its characteristic key
 * by object identity (IdentityHashMap): AOSP's GattServer resolves the
 * descriptor handed to the server callbacks from the registered service
 * tree, so the callback's descriptor is expected to BE the registered
 * instance. A characteristic's CCCD is always registered for exactly one
 * key; re-registering an instance re-points it.
 *
 * Thread-confined to the caller (in [FtmsServer] both the GATT callbacks
 * and the 1 Hz notification pump run on the main thread); there are no
 * synchronization primitives here.
 */
class CccSubscriptions {

    // descriptor instance → characteristic key (identity semantics).
    private val descToKey = IdentityHashMap<Any, String>()

    // characteristic key → enabled central addresses; a key absent here
    // simply has no subscribers.
    private val enabled = HashMap<String, HashSet<String>>()

    /** All registered characteristic keys (for the fallback below). */
    private val charKeys = LinkedHashSet<String>()

    /** Bind descriptor [desc] to [charKey] — called once per descriptor in
     *  start(); one descriptor belongs to exactly one characteristic. */
    fun registerDescriptor(desc: Any, charKey: String) {
        descToKey[desc] = charKey
        charKeys.add(charKey)
    }

    /** The characteristic key this descriptor instance was registered for;
     *  null when the instance is unknown (a different object than the one
     *  that was registered). */
    fun charKeyFor(desc: Any): String? = descToKey[desc]

    /** Enable/disable [dev] for the characteristic that [desc] belongs to.
     *  DEFENSIVE FALLBACK: if the instance is unknown (charKeyFor == null),
     *  the write applies to ALL registered characteristics — the legacy
     *  shared-set behavior. That degradation still satisfies GATT's
     *  subscription rule (nothing is delivered without a CCC write); it only
     *  loses per-characteristic granularity for that one write. */
    fun setEnabled(desc: Any, dev: String, on: Boolean) {
        val key = charKeyFor(desc)
        if (key != null) {
            setFor(key, dev, on)
        } else {
            for (k in charKeys) setFor(k, dev, on)
        }
    }

    fun enabledFor(charKey: String, dev: String): Boolean =
        enabled[charKey]?.contains(dev) == true

    /** Snapshot copy of [charKey]'s enabled central addresses (a copy,
     *  not the live set, so a caller mutating its result cannot corrupt
     *  the state; the pump iterates these a few times per second, so keep
     *  this cheap — one HashSet copy). */
    fun subsFor(charKey: String): Set<String> {
        val set = enabled[charKey] ?: return setOf()
        return HashSet(set)
    }

    /** True if [dev] enabled the CCCD of ANY registered characteristic. */
    fun anyEnabledFor(dev: String): Boolean =
        enabled.values.any { it.contains(dev) }

    /** True if at least one central has at least one characteristic enabled. */
    fun hasAny(): Boolean = enabled.values.any { it.isNotEmpty() }

    /** Drop [dev] from every characteristic (central disconnected). */
    fun clearDevice(dev: String) {
        for (set in enabled.values) set.remove(dev)
    }

    /** Wipe all subscription state AND the descriptor registry (server
     *  closed); a fresh start() re-registers new descriptor instances. */
    fun clearAll() {
        descToKey.clear()
        charKeys.clear()
        enabled.clear()
    }

    private fun setFor(charKey: String, dev: String, on: Boolean) {
        if (on) {
            enabled.getOrPut(charKey) { HashSet() }.add(dev)
        } else {
            enabled[charKey]?.remove(dev)
        }
    }
}

/**
 * Baselines for the target values the Machine Status characteristic must
 * announce: ICS 4/22 ("FTM Status – Target Incline Changed", mandatoriness
 * C.11) and ICS 4/23 ("FTM Status – Target Resistance Level Changed",
 * C.12) make these status codes mandatory, and FTMS §4.17.1 requires the
 * characteristic to be notified "when new status information is available" —
 * a change the USER makes on the console's UI is such a (user-driven)
 * update and is notified to ALL connected clients, just like any other
 * status change (a client-driven one, by contrast, is excluded from the
 * writing client).
 *
 * The 1 Hz pump feeds each console sample through [onSample]. The FIRST
 * non-null sample only sets the baseline — announcing it would push a
 * stale boot value to a client that never saw the original change. Every
 * later sample that DIFFERS from the baseline is announced (0x06 Target
 * Incline Changed / 0x07 Target Resistance Level Changed, Table 4.26) and
 * re-baselines, so each value is announced exactly when it changes and
 * repeated samples stay silent.
 *
 * Grade is sampled in EVERY workout mode — the console reports the current
 * grade independently of workout state. Resistance is a target only while
 * a workout is active (the caller passes null outside [activeModes], and
 * the guard here keeps this class correct even if a non-null raw slipped
 * through): in Idle the console parks a non-level raw that is not a
 * target. Null samples never announce and never clear a baseline.
 *
 * [clientAnnouncedGrade] / [clientAnnouncedResistance] pin the baseline
 * from the bridge's own Control Point writes (0x03 Set Target Incline,
 * 0x04 Set Target Resistance, and both on 0x01 Reset) so the console's
 * echo of OUR write is not re-announced as a console-driven change.
 *
 * Pure (no android.* types); thread-confined to the caller (in
 * [FtmsServer] both the GATT callbacks and the 1 Hz pump run on the main
 * thread).
 */
class TargetAnnouncer {

    /** Last announced 0x06 value (0.1 %); null until the first baseline. */
    var announcedGradeTenths: Int? = null; private set
    /** Last announced 0x07 value (0.1 level); null until the first baseline. */
    var announcedResTenths: Int? = null; private set

    /** Pin the grade baseline to [tenths] after a client write (0x03, or
     *  0x01 Reset's Grade=0 default). */
    fun clientAnnouncedGrade(tenths: Int) {
        announcedGradeTenths = tenths
    }

    /** Pin the resistance baseline to [tenths] after a client write
     *  (0x04, or 0x01 Reset's Resistance=0 → level 1). */
    fun clientAnnouncedResistance(tenths: Int) {
        announcedResTenths = tenths
    }

    /** Returns the (op, param) pairs to announce for this console sample —
     *  0x06 Target Incline Changed first, then 0x07 Target Resistance
     *  Level Changed (deterministic order). Grade announces whenever it
     *  differs from the baseline, in any workout mode; resistance only
     *  when [mode] is in [activeModes]. See the class KDoc for the
     *  set-once / announce-on-change semantics. */
    fun onSample(mode: Int, gradeTenths: Int?, resTenths: Int?, activeModes: Set<Int>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(2)
        gradeTenths?.let { v ->
            val base = announcedGradeTenths
            if (base == null) {
                announcedGradeTenths = v
            } else if (base != v) {
                announcedGradeTenths = v
                out.add(0x06 to v)
            }
        }
        // Resistance is a target only while a workout is active — in any
        // other mode the console's raw value is not a level.
        if (mode in activeModes) {
            resTenths?.let { v ->
                val base = announcedResTenths
                if (base == null) {
                    announcedResTenths = v
                } else if (base != v) {
                    announcedResTenths = v
                    out.add(0x07 to v)
                }
            }
        }
        return out
    }
}

/**
 * The raw Attribute Protocol (ATT) error codes FTMS §4.16.3 requires the
 * Server to return for an invalid Fitness Machine Control Point write:
 * "Procedure Already In Progress" when an Op Code is written while the
 * Server is performing a previously triggered op, and "Client
 * Characteristic Configuration Descriptor Improperly Configured" when
 * the Control Point's CCCD is not configured for indications. Both apply
 * to ALL op codes, including 0x00 (the spec keys on "an Op Code is
 * written"); §4.16.4 adds that a write resulting in such an error
 * response starts no procedure and is not queued.
 *
 * The raw values come from the Bluetooth Core Spec, Vol 3 Part H, "ATT
 * Error Codes" table: Procedure_Already_In_Progress = 0x0C,
 * CCCD_Improperly_Configured = 0x0E. FTMS §1.6 defines no
 * service-specific error codes — these standard ATT codes are what
 * apply. Android exposes no constants for them;
 * [BluetoothGattServer.sendResponse] with a non-zero status emits an ATT
 * Error Response carrying that raw code.
 *
 * Pure (no android.* types).
 */
object AttError {
    /** Core Spec Vol 3 Part H: Procedure_Already_In_Progress. */
    const val PROCEDURE_ALREADY_IN_PROGRESS = 0x0C
    /** Core Spec Vol 3 Part H: CCCD_Improperly_Configured. */
    const val CCCD_IMPROPERLY_CONFIGURED = 0x0E
}

/**
 * The pure admission decision FTMS §4.16.3 requires for EVERY Fitness
 * Machine Control Point write (all op codes, including 0x00), applied on
 * the main thread BEFORE any per-op validation. The two preconditions,
 * in the order §4.16.3 lists them:
 *
 *   1. the bounded command queue is full → reject with
 *      [AttError.PROCEDURE_ALREADY_IN_PROGRESS] (0x0C);
 *   2. the writer's Control Point CCCD is not configured for
 *      indications → reject with [AttError.CCCD_IMPROPERLY_CONFIGURED]
 *      (0x0E).
 *
 * PAIP is checked FIRST — the spec's own listing order — and that order
 * is observable: the decision table's only order-sensitive case is
 * (queueFull=true, cccdEnabled=false), and it answers 0x0C, not 0x0E.
 * That is also the more informative answer for the client: a full queue
 * means the console is ALREADY saturated — the busier condition — and its
 * write would wait for the queue to drain either way.
 *
 * This is a documented compatibility refinement of the literal §4.16.3
 * wording ("while the Server is performing a previously triggered Op
 * Code"): the PAIP trigger is the bounded queue reaching its capacity, not
 * "any op is in flight." Client apps fire bursts of Control Point writes
 * back-to-back and do NOT retry a PAIP-rejected write, so a bare "reject
 * while anything is in flight" policy would drop a legitimate follow-up —
 * a workout Start (0x07) trailing a Simulation-Parameters (0x11) write —
 * entirely instead of applying it once the console catches up. Keying PAIP
 * on the queue means such a burst is buffered and applied in order; PAIP is
 * returned only on buffer overflow, the one condition that genuinely risks
 * overrunning the machine (what §4.16.3 exists to prevent). The machine
 * still executes at most one command at a time, and §4.16.4's "a write
 * that results in an error response is not queued" still holds for the two
 * reject paths here (a rejected write is never enqueued; an accepted one is).
 *
 * [admit] returns [ACCEPT] (0) when the write may proceed to per-op
 * validation; otherwise it returns the raw ATT error code itself, so a
 * caller can hand the result straight to
 * BluetoothGattServer.sendResponse. A non-[ACCEPT] result means the
 * write is answered with the ATT Error Response alone — no Write
 * Response, no indication — and per §4.16.4 the procedure is NOT
 * started and NOT queued, so the caller must not touch the procedure
 * gate.
 *
 * A central that disables its Control Point CCC AFTER subscribing is
 * rejected (0x0E) on its next write: the decision is stateless per
 * write, so the same check covers it — no special-casing needed.
 *
 * Pure (no android.* types); thread-confined to the caller (the main
 * thread in [FtmsServer]).
 */
object ControlWriteGate {

    /** "Admit": enqueue + proceed to per-op validation. 0 — the GATT
     *  success status — so the result doubles as the sendResponse status
     *  for the rejection codes. */
    const val ACCEPT = 0

    /** The §4.16.3 admission decision. [queueFull] is true when the
     *  bounded command queue already holds its capacity — a further write
     *  would overflow it, so it is rejected with PAIP rather than
     *  enqueued. See the object KDoc for the table + PAIP-before-CCCD
     *  ordering. */
    fun admit(queueFull: Boolean, cccdEnabled: Boolean): Int =
        if (queueFull) AttError.PROCEDURE_ALREADY_IN_PROGRESS
        else if (!cccdEnabled) AttError.CCCD_IMPROPERLY_CONFIGURED
        else ACCEPT
}

/**
 * The dispatch-time decision for a QUEUED Control Point op, evaluated on
 * the main thread at the moment the op leaves the queue for the console
 * worker — NOT at write-arrival. [ControlWriteGate] only decides whether a
 * write may be ENQUEUED; by the time a queued op is about to be executed
 * the console's real state (the [ModeLatch]) may have advanced past the
 * state that was current when the op was admitted (a preceding queued op
 * may have moved it). For the mode ops (Start or Resume 0x07, Stop or
 * Pause 0x08) the idempotency decision is therefore re-run against the
 * [ModeLatch] at dispatch: an op that was a genuine state change when
 * admitted but is now a no-op (a preceding op already put the machine in
 * that state) is answered [0x80][op][0x04] Operation Failed (Table 4.24)
 * with NO console write and NO status emissions, instead of re-issuing a
 * redundant console command that would answer Success. This is what keeps
 * the TS duplicate-op cases (BV-01-C / BV-02-C: a second 0x07 / 0x08 must
 * return [0x80][op][0x04]) correct under a back-to-back burst, where the
 * second op is admitted while the first is still in flight and the latch
 * has not yet caught up.
 *
 * Non-mode ops (0x01 Reset, 0x03, 0x04, 0x11) are never idempotent in
 * this sense — they always execute — so [decide] returns [Verdict.EXECUTE]
 * for them with no re-check.
 *
 * [Decision.target] in the [Verdict.EXECUTE] case is the freshly-decided
 * WorkoutMode — it may differ from the value captured at admission when
 * the latch advanced (an admitted "fresh start", target 2, re-decided
 * after a preceding op paused the machine becomes a Resume, target 13).
 * The caller MUST use [Decision.target], not the admission-time value.
 * [Decision.fail] (non-zero only in the [Verdict.NOOP] case) is the
 * Table 4.24 result code to send the writer.
 *
 * Pure (no android.* types); thread-confined to the caller (the main
 * thread in [FtmsServer]).
 */
object CommandDispatch {

    /** [EXECUTE]: dispatch the op to the console worker (using
     *  [Decision.target]). [NOOP]: answer the writer
     *  [0x80][op][[Decision.fail]] and skip the console write. */
    enum class Verdict { EXECUTE, NOOP }

    data class Decision(val verdict: Verdict, val target: Int, val fail: Int)

    /** Re-evaluate a queued op against [latchedMode] (the [ModeLatch]).
     *  See the object KDoc for why this runs at dispatch, not admission. */
    fun decide(op: Int, subOp: Int, latchedMode: Int): Decision {
        return when (op) {
            0x07 -> {
                val (t, f) = StartStop.startDecision(latchedMode)
                if (f != 0) Decision(Verdict.NOOP, -1, f) else Decision(Verdict.EXECUTE, t, f)
            }
            0x08 -> {
                val (t, f) = StartStop.stopDecision(latchedMode, subOp)
                if (f != 0) Decision(Verdict.NOOP, -1, f) else Decision(Verdict.EXECUTE, t, f)
            }
            else -> Decision(Verdict.EXECUTE, -1, 0)
        }
    }
}

/**
 * The bounded, in-order command queue behind the Fitness Machine Control
 * Point. [FtmsServer] is its sole owner and drives it entirely on the main
 * thread (the GATT write callbacks and the console post-backs both run
 * there), so no synchronization is needed.
 *
 * WHY a queue at all (and not the literal FTMS §4.16.3 "reject with PAIP
 * the moment any op is in flight"): client apps fire bursts of Control
 * Point writes back-to-back and do not retry a PAIP-rejected write, so a
 * hard reject would silently drop a legitimate follow-up — a workout Start
 * (0x07) trailing a Simulation-Parameters (0x11) write. A bounded FIFO
 * instead buffers the burst and applies it in order as the console frees
 * up: the machine still executes at most ONE command at a time ([busy]),
 * and PAIP is returned only when the buffer is already at [capacity] (a
 * genuine overflow) — the condition §4.16.3 exists to guard against.
 *
 * The queue stores each command's console parameters ([Payload]) so it can
 * rebuild the console action at dispatch time; [FtmsServer] maps
 * [Payload] ↔ its own console action at the boundary. Mode ops (0x07 /
 * 0x08) are re-decided against the mode latch AT DISPATCH (see
 * [CommandDispatch]) — the state may have advanced while the command waited
 * — so a command that is a no-op by then resolves to a
 * [DispatchEvent.Noop] (answer Operation Failed, no console write) rather
 * than being dispatched.
 *
 * Pure (no android.* types): a command's writer is keyed by address
 * [String], which [FtmsServer] resolves back to a device (and drops when
 * the writer has disconnected).
 */
class CommandQueue(private val capacity: Int) {

    /** The console parameters of one command — the [FtmsServer] console-
     *  action fields, decoupled into a pure type so this class has no
     *  android.* dependency. */
    data class Payload(
        val op: Int,
        val grade: Int,
        val param: Int,
        val raw: Int,
        val target: Int,
        val subOp: Int,
        val sim: ByteArray?,
    )

    /** One accepted command. [id] is the [ProcedureGate] id [FtmsServer]
     *  assigned (an opaque token this class only carries and echoes back
     *  in its events); [writer] is the writer's address. */
    private class QueuedCommand(val id: Int, val writer: String, val payload: Payload)

    /** What [FtmsServer] must do in response to [admit] / [completed]. */
    sealed interface DispatchEvent {
        /** Answer [0x80][op][fail] to [writer]; NO console write. */
        data class Noop(val id: Int, val writer: String, val op: Int, val fail: Int) : DispatchEvent

        /** Post the console round-trip for [payload] to the worker and
         *  report its completion via [completed]. */
        data class Execute(val id: Int, val writer: String, val payload: Payload) : DispatchEvent
    }

    /** [Rejected]: the queue was already at [capacity] — answer PAIP and
     *  enqueue nothing. [Accepted]: enqueued; [events] are the actions to
     *  perform now (a run of [DispatchEvent.Noop], optionally one
     *  [DispatchEvent.Execute]; empty while the console is still busy). */
    sealed interface AdmitResult {
        object Rejected : AdmitResult
        data class Accepted(val events: List<DispatchEvent>) : AdmitResult
    }

    @Volatile private var executing = false
    private val fifo = ArrayDeque<QueuedCommand>()

    /** Number of accepted commands currently buffered (not yet on the
     *  console). [FtmsServer] feeds this to [ControlWriteGate.admit] as
     *  the PAIP condition. */
    val queueSize: Int get() = fifo.size

    /** True while a command is on the console (its post-back has not yet
     *  run [completed]). */
    val busy: Boolean get() = executing

    /** Admit a validated command. [latched] is the current mode latch —
     *  the console's authoritative WorkoutMode — used to re-decide any mode
     *  op the drain reaches. See [AdmitResult]. */
    fun admit(id: Int, writer: String, payload: Payload, latched: Int): AdmitResult {
        if (fifo.size >= capacity) return AdmitResult.Rejected
        fifo.addLast(QueuedCommand(id, writer, payload))
        return AdmitResult.Accepted(drain(latched))
    }

    /** The in-flight console command finished (its [FtmsServer] post-back
     *  applied its effects and thus possibly advanced [latched]). Frees the
     *  console and returns the events to drain the queue. */
    fun completed(latched: Int): List<DispatchEvent> {
        executing = false
        return drain(latched)
    }

    /** Full teardown: drop the buffer and the in-flight flag so no
     *  post-back of a queued command can resurrect work. */
    fun cancelAll() {
        fifo.clear()
        executing = false
    }

    /** Advance the pipeline as far as the one-at-a-time console allows:
     *  resolve queued mode ops that are now no-ops ([DispatchEvent.Noop],
     *  which frees the console immediately and lets the drain continue),
     *  then dispatch at most one command ([DispatchEvent.Execute], which
     *  sets [executing] and stops the drain). [latched] is the latch value
     *  to decide mode ops against. */
    private fun drain(latched: Int): List<DispatchEvent> {
        val out = ArrayList<DispatchEvent>()
        while (!executing) {
            val cmd = fifo.firstOrNull() ?: break
            fifo.removeFirst()
            val d = CommandDispatch.decide(cmd.payload.op, cmd.payload.subOp, latched)
            if (d.verdict == CommandDispatch.Verdict.NOOP) {
                out += DispatchEvent.Noop(cmd.id, cmd.writer, cmd.payload.op, d.fail)
            } else {
                // A mode op's target is re-decided at dispatch (it may have
                // advanced while the command waited); non-mode ops keep the
                // admission-time target (which the console side ignores).
                val target =
                    if (cmd.payload.op == 0x07 || cmd.payload.op == 0x08) d.target
                    else cmd.payload.target
                executing = true
                out += DispatchEvent.Execute(cmd.id, cmd.writer, cmd.payload.copy(target = target))
                break
            }
        }
        return out
    }
}

/**
 * The 6-octet value layout both range characteristics share, per the
 * SIG attribute definitions:
 *
 *   [Sint16 Min][Sint16 Max][Uint16 Min-Increment]
 *
 * little-endian, two's-complement for the signed fields. The field widths
 * follow the SIG attribute definitions (sint16/sint16/uint16): sint16 is
 * what keeps a resistance range above 25.5 levels (maxLevel×10 > 255)
 * intact, and a signed min is what lets a negative incline minimum be
 * represented at all.
 *
 * [min] / [max] are clamped to the s16 bounds and [minInc] to the u16
 * bounds defensively: an out-of-band console configuration must not
 * overflow the fields.
 *
 * Pure (no android.* types); thread-confined to the caller (in
 * [FtmsServer] the GATT read callbacks run on the main thread).
 */
private fun rangeFields(min: Int, max: Int, minInc: Int): ByteArray {
    val lo = min.coerceIn(-0x8000, 0x7FFF)
    val hi = max.coerceIn(-0x8000, 0x7FFF)
    val inc = minInc.coerceIn(0, 0xFFFF)
    return byteArrayOf(
        (lo and 0xFF).toByte(), ((lo ushr 8) and 0xFF).toByte(),
        (hi and 0xFF).toByte(), ((hi ushr 8) and 0xFF).toByte(),
        (inc and 0xFF).toByte(), ((inc ushr 8) and 0xFF).toByte()
    )
}

/**
 * 0x2AD5 Supported Inclination Range (SIG attribute definition):
 * [Sint16 Min][Sint16 Max][Uint16 Min-Increment] — 6 octets, all in
 * 0.1 % (percentage, decimal exponent -1). The min/max fields are signed
 * (sint16), which is what lets a negative minimum inclination reach the
 * central.
 *
 * Pure (no android.* types).
 */
fun inclineRange(minTenths: Int, maxTenths: Int, minIncTenths: Int): ByteArray =
    rangeFields(minTenths, maxTenths, minIncTenths)

/**
 * 0x2AD6 Supported Resistance Level Range (SIG attribute definition):
 * [Sint16 Min][Sint16 Max][Uint16 Min-Increment] — 6 octets, all in
 * 0.1 level (unitless, decimal exponent -1) — the same 0.1-level unit
 * as the 0x2AD2 data field and the 0x04 control parameter (the TS
 * cross-checks all three). The sint16 min/max are what keep a maxLevel×10
 * above 255 (more than 25.5 levels) intact.
 *
 * Pure (no android.* types).
 */
fun resistanceRange(minTenths: Int, maxTenths: Int, minIncTenths: Int): ByteArray =
    rangeFields(minTenths, maxTenths, minIncTenths)
