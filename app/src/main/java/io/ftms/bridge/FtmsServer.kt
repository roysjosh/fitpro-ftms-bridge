package io.ftms.bridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * The BLE half: the tablet advertises as a Bluetooth FTMS v1.0.1
 * indoor-bike (service 0x1826) and serves the console telemetry on a GATT
 * server. The data source is [FitPro1Client.latest] (the 10 Hz USB pump);
 * the 1 Hz notification loop below coalesces it and notifies each
 * characteristic only to the centrals that enabled THAT characteristic's
 * CCCD (GATT delivery is per-subscription, not per-connection — a central
 * that merely connected, or merely wrote the Control Point, receives no
 * telemetry until it configures a CCCD).
 *
 * Layout: the console's 90-byte periodic frame
 * is delivered as a single 64-byte packet (see [UsbTransport.read]), so only
 * the bitfields that fall inside the first 64 bytes are trustworthy: the
 * variable Indoor-Bike-Data record therefore declares ONLY those fields
 * (cadence, distance, resistance, power, HR, elapsed, remaining) and omits
 * the rest (average speed/cadence/power, energy, MET) per FTMS §4's
 * omit-when-absent rule.
 *
 * Security (accepted deviation from FTMS Table 4.1): the
 * Control Point — and the read-only data characteristics — are left open
 * (no ENCRYPT), even though the spec mandates encryption for the Control
 * Point. Two hard constraints force this: this tablet rotates the MAC
 * address it broadcasts from on a regular basis, so a long-term bond (and
 * thus an LTK for encrypted transport) is impossible; and the client apps
 * that matter in the field (Zwift, MyWhoosh) do not pair/bond to FTMS
 * servers at all — shipping ENCRYPT would simply make the bike invisible to
 * them. Mass-produced FTMS devices run open for the same reason. The
 * residual risk (any central in range can drive the bike) is accepted.
 */
class FtmsServer(private val ctx: Context, private val client: FitPro1Client, private val consoleWorker: Handler) {

    companion object {
        private const val TAG = "FtmsServer"

        // 16-bit-BASE UUIDs on the 0000...-0000-1000-8000-00805f9b34fb base.
        private fun uu(short: Int): UUID =
            UUID.fromString(String.format("0000%04X-0000-1000-8000-00805f9b34fb", short))
        val FTMS = uu(0x1826)
        val FEATURE = uu(0x2ACC)
        val INDOOR_BIKE = uu(0x2AD2)
        val TRAINING = uu(0x2AD3)
        val INCLINE_RANGE = uu(0x2AD5)
        val RESISTANCE_RANGE = uu(0x2AD6)
        val CONTROL = uu(0x2AD9)
        val MACHINE_STATUS = uu(0x2ADA)
        val CCCD = uu(0x2902)

        // [CccSubscriptions] keys — one per characteristic that carries a
        // CCCD. (The two range characteristics have no CCCD.)
        private const val KEY_FEATURE = "feature"
        private const val KEY_INDOOR_BIKE = "indoorBike"
        private const val KEY_TRAINING = "training"
        private const val KEY_CONTROL = "control"
        private const val KEY_MACHINE_STATUS = "machineStatus"

        // Advertising AD: Service Data AD 0x16 with
        // [FTMS UUID(LE)][Available flag][Machine Type(LE)]; bit5 = Indoor Bike.

        // 1 Hz Indoor Bike Data / Training Status (spec §4.9.1 "once per second").
        private const val NOTIFY_PERIOD_MS = 1000L

        // Bounded command-queue capacity (see [CommandQueue]): how many
        // accepted Control Point commands may be buffered while the console
        // works through them. Client apps fire a start-burst (sim-params,
        // sim-params, start) back-to-back and do not retry a dropped write,
        // so 4 comfortably absorbs one such burst; a 5th command arriving
        // while 4 are already queued is the overflow case answered PAIP.
        private const val QUEUE_CAPACITY = 4

        // WorkoutMode states in which the console reports a real, latched
        // resistance raw. In any other state (notably Idle(1)) the console parks
        // a non-level raw (e.g. 2500) that must NOT be mapped to an FTMS level.
        // 2=Running 3=Pause 10=WarmUp 11=CoolDown 13=Resume.
        private val ACTIVE_MODES = setOf(2, 3, 10, 11, 13)
    }

    @Volatile var advertising = false; private set
    @Volatile var available = false; private set
    @Volatile var clientConnected = false; private set

    private var gatt: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advCb: AdvertiseCallback? = null
    private var handler: Handler? = null
    private var notifyLoop: Runnable? = null

    private var featureChar: BluetoothGattCharacteristic? = null
    private var bikeChar: BluetoothGattCharacteristic? = null
    private var trainingChar: BluetoothGattCharacteristic? = null
    private var controlChar: BluetoothGattCharacteristic? = null
    private var machineStatusChar: BluetoothGattCharacteristic? = null

    // Per-characteristic CCCD state (GATT: notifications go only to the
    // centrals that enabled that characteristic's CCCD). Main-thread
    // confined: the GATT callbacks and the 1 Hz pump below both run there.
    private val ccc = CccSubscriptions()
    // The GATT API takes the BluetoothDevice object, while [ccc] is keyed by
    // address (address keys also survive the tablet's rotating address
    // across reconnections of the same central). Main-thread confined.
    private val addressToDevice = HashMap<String, BluetoothDevice>()
    // Baselines for the console-driven target announcements (ICS 4/22,
    // 4/23). Main-thread confined (GATT callbacks + the 1 Hz pump).
    // Deliberately NOT cleared in closeGatt/on disconnect: it tracks the
    // CONSOLE's state, which outlives a central disconnect — a central that
    // (re)subscribes later gets the current value via the one-shot
    // late-joiner sync in onDescriptorWriteRequest, and if a baseline is
    // still null the first sample after any reconnection re-baselines
    // silently.
    private val announcer = TargetAnnouncer()
    @Volatile private var controlGranted = false
    @Volatile private var controlHolder: BluetoothDevice? = null
    // WorkoutMode latch (see [ModeLatch]): the 1 Hz pump announces a
    // transition only from a FRESH console sample (readSeq > the stamp of a
    // pending Control Point mode write), so a sample read BEFORE the console
    // applied the write can neither clobber the latch nor re-fire the
    // Machine Status that write already emitted (spec 4.16.4). Main-thread
    // confined (GATT callbacks + the 1 Hz pump).
    private val modeLatch = ModeLatch()
    // Read accessor so pre-latch call sites keep compiling (the pump now
    // drives [modeLatch] directly).
    private val lastMode: Int get() = modeLatch.latched
    // Procedure ids + cancel watermark for the console round-trips this
    // server queues on [consoleWorker] (see [ProcedureGate]): a
    // post-back arriving after a full teardown must be a no-op.
    private val gate = ProcedureGate()
    // The bounded FIFO of accepted Control Point console commands (see
    // [CommandQueue]). Client apps fire bursts of Control Point writes
    // back-to-back and do NOT retry a PAIP-rejected write, so an accepted
    // command is buffered here and applied in order as the console frees up
    // instead of being dropped; a [CommandQueue.AdmitResult.Rejected]
    // overflow is the only write still answered PAIP. [gate] still owns
    // each command's id + the cancel watermark; the two stay in lockstep on
    // the main thread (queue ops, the GATT callbacks and the post-backs all
    // run there).
    private val queue = CommandQueue(QUEUE_CAPACITY)

    private fun log(s: String) = Log.e(TAG, s)   // E-level: survives this ROM's filter

    /** Open the GATT server, register the FTMS service, start advertising. */
    fun start() {
        if (gatt != null) return
        val btm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter = btm?.adapter ?: run {
            log("no BluetoothManager/adapter — FTMS disabled")
            return
        }
        if (!adapter.isEnabled) {
            log("Bluetooth adapter off — FTMS disabled")
            return
        }
        try {
            val server = btm?.openGattServer(ctx, gattCallback)
            if (server == null) {
                log("openGattServer returned null — FTMS disabled")
                return
            }
            gatt = server
            val svc = BluetoothGattService(FTMS, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            val feature = BluetoothGattCharacteristic(FEATURE,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_READ)
            feature.value = buildFeature()
            val featureCccd = cccd()
            feature.addDescriptor(featureCccd)
            ccc.registerDescriptor(featureCccd, KEY_FEATURE)
            svc.addCharacteristic(feature)
            featureChar = feature

            bikeChar = BluetoothGattCharacteristic(INDOOR_BIKE,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ)
            val bikeCccd = cccd()
            bikeChar!!.addDescriptor(bikeCccd)
            ccc.registerDescriptor(bikeCccd, KEY_INDOOR_BIKE)
            svc.addCharacteristic(bikeChar!!)

            trainingChar = BluetoothGattCharacteristic(TRAINING,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ)
            val trainingCccd = cccd()
            trainingChar!!.addDescriptor(trainingCccd)
            ccc.registerDescriptor(trainingCccd, KEY_TRAINING)
            svc.addCharacteristic(trainingChar!!)

            if (client.gradeSupported) {
                val incl = BluetoothGattCharacteristic(INCLINE_RANGE,
                    BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
                incl.value = buildInclineRange()
                svc.addCharacteristic(incl)
            }
            val resRange = BluetoothGattCharacteristic(RESISTANCE_RANGE,
                BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
            resRange.value = buildResistanceRange()
            svc.addCharacteristic(resRange)

            // TEST-ONLY (temporary): the Control Point is left OPEN (no
            // encryption) so a non-bonded host can drive it for bring-up.
            // The tablet advertises a ROTATING random address (Android 9 has
            // no API to pin the public one) and the host's BlueZ store holds
            // no LTK for it, so a bonded write fails with "Not paired". Must
            // be reverted to PERMISSION_WRITE_ENCRYPTED (spec C.1: bonded-only)
            // before release.
            controlChar = BluetoothGattCharacteristic(CONTROL,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_WRITE)
            val controlCccd = cccd()
            controlChar!!.addDescriptor(controlCccd)
            ccc.registerDescriptor(controlCccd, KEY_CONTROL)
            svc.addCharacteristic(controlChar!!)

            machineStatusChar = BluetoothGattCharacteristic(MACHINE_STATUS,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ)
            val msCccd = cccd()
            machineStatusChar!!.addDescriptor(msCccd)
            ccc.registerDescriptor(msCccd, KEY_MACHINE_STATUS)
            svc.addCharacteristic(machineStatusChar!!)

            if (!server.addService(svc)) {
                log("addService(0x1826) failed")
                closeGatt()
                return
            }
            Log.e(TAG, "FTMS service 0x1826 registered")
            // [DEBUG] Baseline of exactly what a client will read for the
            // target-setting capability + the accepted command ranges, so a
            // later "REJECT range" line can be judged against it.
            log(">>> CAPABILITIES FEATURE=${hex(feature.value)} RES=${hex(resRange.value)}" +
                (if (client.gradeSupported) " INCLINE=${hex(buildInclineRange())}" else " (no incline)") +
                " | maxResLvl=${client.maxResistanceLevel} (→ maxTenths ${client.maxResistanceLevel.coerceIn(1, 100) * 10})" +
                " grade=${client.gradeSupported} minGrade=${client.minGrade} maxGrade=${client.maxGrade}")

            handler = Handler(Looper.getMainLooper())
            startAdvertise(adapter)
            startNotifyLoop()
        } catch (e: Exception) {
            log("FTMS start failed: ${e.message}")
            closeGatt()
        }
    }

    fun stop() {
        stopAdvertise()
        stopNotifyLoop()
        closeGatt()
        clientConnected = false
        available = false
    }

    // --- advertising -----------------------------------------------------

    private fun startAdvertise(adapter: BluetoothAdapter) {
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            log("no BluetoothLeAdvertiser — not discoverable")
            return
        }
        val h = handler ?: return
        // Always-on fallback: the machine is
        // advertised whenever the bridge is streaming, and the Available flag
        // is set while it is ready. (A cadence-gated policy would
        // leave a non-pedaling bike dark.)
        available = client.ready
        if (!available) {
            log("advertising skipped: not ready yet")
            return
        }
        // Service Data AD (0x16): [FTMS UUID(LE)][Available][Machine Type(LE)].
        // The Available flag (bit0) is 1 while the machine is ready.
        // Machine Type bit5 = Indoor Bike (0x2000 LE => 20 00).
        // We keep the advertisement minimal (service UUID + service data, no
        // device name) so it always fits the 31-byte cap regardless of the
        // adapter's name; discovery keys on the 0x1826 UUID.
        val availableFlag = (if (available) 1 else 0).toByte()
        val sd = byteArrayOf(availableFlag, 0x20.toByte(), 0x00)
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(FTMS))
            .addServiceData(ParcelUuid(FTMS), sd)
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                advertising = true
                Log.e(TAG, "ADVERTISING as FTMS indoor bike (0x1826) — discoverable")
            }
            override fun onStartFailure(errorCode: Int) {
                advertising = false
                Log.e(TAG, "advertising start FAILED (code $errorCode)")
            }
        }
        advCb = cb
        advertiser = adv
        try {
            adv.startAdvertising(settings, data, cb)
        } catch (e: Exception) {
            advertising = false
            log("startAdvertising threw: ${e.message}")
        }
    }

    private fun stopAdvertise() {
        val a = advertiser
        val cb = advCb
        advertiser = null
        advCb = null
        if (a != null && cb != null) {
            try { a.stopAdvertising(cb) } catch (_: Exception) {}
        }
        advertising = false
    }

    private fun closeGatt() {
        stopAdvertise()
        stopNotifyLoop()
        val s = gatt
        gatt = null
        handler?.let { h ->
            bikeChar = null; trainingChar = null; controlChar = null; machineStatusChar = null
            if (s != null) h.post { try { s.close() } catch (_: Exception) {} }
        } ?: run { if (s != null) try { s.close() } catch (_: Exception) {} }
        ccc.clearAll()
        addressToDevice.clear()
        controlGranted = false
        controlHolder = null
        // Full teardown: also drop the mode latch so the next start()
        // re-arms from the first fresh sample, not a stale one.
        modeLatch.reset()
        // A WRITER disconnect is NOT a cancellation (see
        // onConnectionStateChange: its in-flight procedure's console side
        // still completes and its post-back's emissions simply reach only
        // the remaining subscribers). A FULL teardown is: bump the
        // gate's cancel watermark so any post-back still queued for the
        // superseded procedure no-ops instead of re-latching the mode
        // [modeLatch.reset] just cleared (which would ghost-announce a
        // transition on the next start) or delivering into a closed
        // server.
        gate.cancelAll()
        queue.cancelAll()
    }

    // --- 1 Hz notification loop ------------------------------------------

    private fun startNotifyLoop() {
        val h = handler ?: return
        if (notifyLoop != null) return
        val r = object : Runnable {
            override fun run() {
                if (gatt == null) return
                pushNotifications()
                h?.postDelayed(this, NOTIFY_PERIOD_MS)
                notifyLoop = this
            }
        }
        notifyLoop = r
        h.postDelayed(r, NOTIFY_PERIOD_MS)
    }

    private fun stopNotifyLoop() {
        handler?.removeCallbacks(notifyLoop ?: return)
        notifyLoop = null
    }

    private fun pushNotifications() {
        val g = gatt ?: return
        if (!ccc.hasAny()) return
        if (!client.ready) return
        // One mode read per tick, shared by the target announcer below and
        // the mode-latch block: -1 is the "no console sample yet" sentinel
        // and is NOT a state — the latch must not be fed it (doing so would
        // latch -1 and ghost-announce a First in the narrow ready-but-
        // not-yet-sampled window).
        val mode = (client.latest[12] as? Number)?.toInt() ?: -1
        // Indoor Bike Data (the main record) — latest sample wins. Gated on
        // its OWN subscribers: a central that only subscribed to e.g.
        // Machine Status must not receive the 30-byte bike record.
        val bike = bikeChar
        if (bike != null) {
            val data = buildBikeData()
            for (a in ccc.subsFor(KEY_INDOOR_BIKE)) {
                val d = addressToDevice[a] ?: continue
                try {
                    bike.value = data
                    g.notifyCharacteristicChanged(d, bike, false)
                } catch (_: Exception) {}
            }
        }
        // ICS 4/22 (C.11) / ICS 4/23 (C.12): Machine Status "shall be
        // notified whenever the value is changed" — including a change the
        // USER makes on the console, not just a Control Point write
        // (§4.17.1: a user-driven update goes to ALL connected clients, so
        // NO exclusion here). The announcer compares each sample against
        // the last announced value and emits 0x06/0x07 only on a real
        // change, so a steady console stays silent.
        // The raw grade (bitfield 1, ×100 %) is stored UNSIGNED in
        // [FitPro1Client.latest] (sign-extension there covers only the
        // handshake range fields) — extend to SINT16 first, then scale to
        // the 0.1 % FTMS unit (Kotlin integer division truncates toward
        // zero; the wire grade is a whole number of 100ths, so this only
        // matters for sub-tenth values, which are not representable anyway).
        val gradeTenths = (client.latest[1] as? Number)?.toInt()?.let {
            (if (it in 0x8000..0xFFFF) it - 0x10000 else it) / 10
        }
        // Same raw→level mapping buildBikeData uses for the Resistance
        // field; in a non-active mode the console parks a non-level raw
        // that is not a target, so sample null there (a null never
        // announces and never clears the baseline).
        val resRaw = (client.latest[2] as? Number)?.toInt() ?: 0
        val resTenths = if (mode in ACTIVE_MODES) client.levelFromRaw(resRaw) * 10 else null
        for ((op, prm) in announcer.onSample(mode, gradeTenths, resTenths, ACTIVE_MODES)) {
            emitMachineStatus(op, prm)
        }
        // Training Status + Machine Status — push on a WorkoutMode
        // transition, decided by the sequence-stamped latch. Feeding it the
        // raw -1 sentinel would ghost-announce a First(-1) in the narrow
        // ready-but-not-yet-sampled window, so only a real observation
        // (mode >= 0) reaches process().
        val training = trainingChar
        if (mode >= 0) {
            when (val r = modeLatch.process(client.readSeq, mode)) {
                ModeLatch.TickResult.Stale -> {
                    // This sample predates the last control-point mode write's
                    // ack — it must not clobber the latch or fire any
                    // transition (which would duplicate the Machine Status
                    // that write already emitted).
                }
                is ModeLatch.TickResult.First -> {
                    // First observation re-arming the latch: announce the
                    // Training Status, but NO Machine Status — there is no
                    // prior observed state, so there is no change to report.
                    if (training != null) {
                        for (a in ccc.subsFor(KEY_TRAINING)) {
                            val d = addressToDevice[a] ?: continue
                            try {
                                training.value = buildTrainingStatus(mode)
                                g.notifyCharacteristicChanged(d, training, false)
                            } catch (_: Exception) {}
                        }
                    }
                }
                is ModeLatch.TickResult.Transition -> {
                    if (training != null) {
                        for (a in ccc.subsFor(KEY_TRAINING)) {
                            val d = addressToDevice[a] ?: continue
                            try {
                                training.value = buildTrainingStatus(mode)
                                g.notifyCharacteristicChanged(d, training, false)
                            } catch (_: Exception) {}
                        }
                        // Machine Status (Table 4.26): 0x02 Stop/Pause (with
                        // Control Information param) or 0x04 Start/Resume —
                        // a fresh sample is authoritative, so this also covers
                        // a pending client write the console did not apply.
                        if (mode in intArrayOf(1, 3, 20, 2, 13)) {
                            val (op, prm) = when (mode) {
                                1 -> 0x02 to 0x01
                                3, 20 -> 0x02 to 0x02
                                else -> 0x04 to -1
                            }
                            for (a in ccc.subsFor(KEY_MACHINE_STATUS)) {
                                val d = addressToDevice[a] ?: continue
                                val ms = machineStatusChar ?: continue
                                try {
                                    ms.value = MachineStatus.encode(op, if (prm >= 0) prm else null)
                                    g.notifyCharacteristicChanged(d, ms, false)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
                ModeLatch.TickResult.Unchanged -> {
                    // A fresh sample confirmed the latched mode: either the
                    // console caught up to a pending control-point write (which
                    // already announced) or nothing changed — no announcement.
                }
            }
        }
    }

    // --- GATT server callback --------------------------------------------

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (device == null) return
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                clientConnected = true
                log("central connected: ${addr(device)}")
                addressToDevice[addr(device)] = device
                controlGranted = false
                controlHolder = null
                // NOTE: the 30-byte Indoor Bike record rides in one notification
                // with moreData(bit0)=0. That needs the central's negotiated MTU
                // >= 33. The GATT *server* API cannot request an MTU (only the
                // central can), so we rely on the central (Zwift/MyWhoosh all
                // negotiate a large MTU on connect). See onMtuChanged log.
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                // Drop every characteristic's subscription for this central —
                // it is gone, and its stale address must not keep a block
                // delivering into a dead connection.
                ccc.clearDevice(addr(device))
                addressToDevice.remove(addr(device))
                if (controlHolder == device) { controlGranted = false; controlHolder = null }
                if (!ccc.hasAny()) clientConnected = false
                // Drop the mode latch so a (re)connect re-arms from a First
                // instead of announcing a ghost transition to the stale
                // pre-disconnect state.
                modeLatch.reset()
                log("central disconnected: ${addr(device)}")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, status: Int) {
            log("central ${addr(device)} exchanged MTU (status $status)")
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic?) {
            val g = gatt ?: return
            val c = characteristic ?: return
            val v: ByteArray? = when (c.uuid) {
                FEATURE -> buildFeature()
                INDOOR_BIKE -> buildBikeData()
                TRAINING -> buildTrainingStatus((client.latest[12] as? Number)?.toInt() ?: 0)
                INCLINE_RANGE -> buildInclineRange()
                RESISTANCE_RANGE -> buildResistanceRange()
                else -> null
            }
            if (v == null) {
                g.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            // [DEBUG] Capability discovery: confirms the central is reading
            // our feature bitmap / ranges, and shows it byte-for-byte.
            log(">>> READ ${charName(c.uuid)} from ${addr(device)} → ${hex(v)}")
            g.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                if (offset < v.size) v.copyOfRange(offset, v.size) else ByteArray(0))
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val g = gatt ?: return
            if (characteristic?.uuid != CONTROL) {
                if (responseNeeded) g.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            // [DEBUG] Fires on EVERY Control Point write, BEFORE any gate.
            // This is the single line that answers "is the central sending
            // the command at all, and what are its full contents?" — and it
            // also prints the two gate inputs (in-flight, control-CCCD) plus
            // the current holder, so the next line tells us exactly why (or
            // that it will) be dropped.
            log(">>> CTLP WRITE from ${addr(device)} resp=$responseNeeded bytes=[${hex(value)}] | queued=${queue.queueSize}/${QUEUE_CAPACITY} busy=${queue.busy} cccdArmed=${ccc.enabledFor(KEY_CONTROL, addr(device))} holder=${addr(controlHolder)}")
            // FTMS §4.16.3 preconditions, applied to EVERY op code
            // (including 0x00 — the spec keys on "an Op Code is
            // written"), in the order the spec lists them ([
            // ControlWriteGate] documents why PAIP wins the corner where
            // both fail). The PAIP half is the bounded [queue] being FULL
            // (an overflow, not "anything is in flight" — see the queue's
            // KDoc for why that refinement is what keeps client bursts from
            // being dropped); the CCCD half reads the per-characteristic CCC
            // registry for the Control Point.
            val admission = ControlWriteGate.admit(
                queueFull = queue.queueSize >= QUEUE_CAPACITY,
                cccdEnabled = ccc.enabledFor(KEY_CONTROL, addr(device))
            )
            if (admission == AttError.PROCEDURE_ALREADY_IN_PROGRESS) {
                // §4.16.4: a write that results in an ATT error response
                // is NOT started and NOT queued — [gate] and [queue] must
                // stay untouched (the command is never enqueued), and the
                // error response IS the answer: no Write Response, no
                // [0x80][op][result] indication.
                if (responseNeeded) g.sendResponse(device, requestId, AttError.PROCEDURE_ALREADY_IN_PROGRESS, 0, null)
                log(">>> REJECT 0x0C (PAIP) op=${opHex(value)} from ${addr(device)}: command queue full (${queue.queueSize}/$QUEUE_CAPACITY) — write not queued (4.16.3/4.16.4)")
                return
            }
            if (admission == AttError.CCCD_IMPROPERLY_CONFIGURED) {
                // Same §4.16.4 rule. A central that disables its Control
                // Point CCC after subscribing is caught by this very
                // check on its NEXT write — the decision is stateless
                // per write, so no special-casing is needed.
                if (responseNeeded) g.sendResponse(device, requestId, AttError.CCCD_IMPROPERLY_CONFIGURED, 0, null)
                log(">>> REJECT 0x0E (CCCD) op=${opHex(value)} from ${addr(device)}: Control Point CCCD not armed for indications — write not queued (4.16.3/4.16.4)")
                return
            }
            // A Control Point write must NOT grant any subscription: GATT
            // delivery is per-CCCD, and auto-subscribing a writer here would
            // start full telemetry streaming to a central that never
            // configured a CCCD.
            //
            // Fast validator: everything main-thread state can decide
            // (control permission, parameter presence/range, Start/Stop
            // idempotency) answers the write SYNCHRONOUSLY — Write
            // Response first, then the [0x80][op][result] indication.
            // Only an op that must round-trip to the console returns a
            // non-null [ConsoleAction].
            val action = validateControlWrite(g, device, requestId, responseNeeded, value ?: ByteArray(0)) ?: return
            // [DEBUG] Survived both the §4.16.3 gate and per-op validation:
            // the command is genuine and is now being forwarded to the
            // console (the eventual [0x80][op][result] rides the post-back).
            log(">>> ACCEPT op=0x${action.op.toString(16)} from ${addr(device)}: validated → enqueued")
            // Accepted: the Write Response is already out — per FTMS
            // §4.16.4 the procedure STARTS at that moment ("a procedure
            // is started when a write … is successfully completed (i.e.,
            // the Server sends a Write Response)"). Its console side is
            // ENQUEUED in [queue] (never run inline here — that would be an
            // 80–200 ms main-thread stall, an ANR vector) and dispatched to
            // [consoleWorker] as the console frees up, so a client burst is
            // buffered and applied in order rather than dropped. The
            // [0x80][op][result] indication rides the post-back, once the
            // console actually applied the write.
            val id = gate.begin(action.op)
            when (val r = queue.admit(id, addr(device), action.toPayload(), modeLatch.latched)) {
                // Unreachable on this thread (the admit check above already
                // confirmed room in the queue) — kept only for exhaustiveness
                // of the sealed type; if it ever fired, the Write Response
                // was already sent, so there is nothing left to answer with.
                is CommandQueue.AdmitResult.Rejected ->
                    log(">>> QUEUE overflow after admit (defensive) op=0x${action.op.toString(16)} from ${addr(device)}")
                is CommandQueue.AdmitResult.Accepted ->
                    r.events.forEach { perform(it) }
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val g = gatt ?: return
            if (device == null) return
            if (descriptor?.uuid == CCCD) {
                val on = value != null &&
                    (java.util.Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                        java.util.Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE))
                // API 28 exposes no attribute-handle accessor, so the CCCD's
                // characteristic is resolved from the descriptor INSTANCE
                // (AOSP hands the server callback the object it was
                // registered from).
                val key = ccc.charKeyFor(descriptor)
                if (key == null) {
                    log("CCCD write from ${addr(device)} did not resolve to a registered " +
                        "descriptor instance — per-characteristic gating degraded to " +
                        "all-characteristics for this write")
                }
                ccc.setEnabled(descriptor, addr(device), on)
                // [DEBUG] Per-spec, a Control Point write is rejected 0x0E
                // unless this very descriptor was enabled, so this line is the
                // definitive answer to "did the central arm the control CCCD?".
                log(">>> CCCD ${key ?: "UNRESOLVED"} ${if (on) "ENABLED" else "DISABLED"} by ${addr(device)} (controlPointArmed=${ccc.enabledFor(KEY_CONTROL, addr(device))})")
                // Late-joiner sync (ICS 4/22, 4/23): Machine Status is
                // notify-only (Table 4.1 declares no Read property), so a
                // central that subscribes AFTER a target change was already
                // announced has no other way to learn the current value —
                // send it the pinned baselines as one-shot targeted
                // notifications. Legal: from THIS central's perspective the
                // value just changed (it had none), which is exactly what
                // conditions a notification (ICS 5/1). The
                // unresolved-descriptor fallback (key == null) cannot know
                // which characteristic was enabled, so it skips the sync —
                // the degradation log above already covers that path.
                if (on && key == KEY_MACHINE_STATUS) {
                    announcer.announcedGradeTenths?.let { sendTo(device, MachineStatus.encode(0x06, it)) }
                    announcer.announcedResTenths?.let { sendTo(device, MachineStatus.encode(0x07, it)) }
                }
                if (responseNeeded) {
                    g.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0,
                        if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                }
            } else if (responseNeeded) {
                g.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor?) {
            val g = gatt ?: return
            if (descriptor?.uuid == CCCD && device != null) {
                // Report THIS descriptor's own state (a central reading one
                // characteristic's CCCD must not see another's). Unresolved
                // instance → the same all-characteristics degradation as the
                // write fallback.
                val a = addr(device)
                val key = ccc.charKeyFor(descriptor)
                val on = if (key != null) ccc.enabledFor(key, a) else ccc.anyEnabledFor(a)
                g.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                    if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            } else if (device != null) {
                g.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }
    }

    private fun addr(d: BluetoothDevice?): String = try { d?.address ?: "?" } catch (_: Exception) { "?" }

    /** The write's op-code byte for log lines: "0xNN"; "(empty)" when
     *  the write carries no op code at all. */
    private fun opHex(value: ByteArray?): String =
        if (value == null || value.isEmpty()) "(empty)" else "0x${(value[0].toInt() and 0xFF).toString(16)}"

    /** Full uppercase hex dump of a raw ATT value for the ">>>" debug
     *  lines: "00 03 05 DC"; "(null)"/"(empty)" when absent. Every line
     *  prefixed ">>>" uses this so a single `logcat | grep '>>>'` shows
     *  each Control Point write, CCCD write and capability read
     *  byte-for-byte. */
    private fun hex(value: ByteArray?): String =
        if (value == null) "(null)"
        else if (value.isEmpty()) "(empty)"
        else value.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /** Short FTMS name for a characteristic UUID (read-path debug logs). */
    private fun charName(u: UUID?): String = when (u) {
        FEATURE -> "FitnessMachineFeature"
        INDOOR_BIKE -> "IndoorBikeData"
        TRAINING -> "TrainingStatus"
        INCLINE_RANGE -> "SupportedInclineRange"
        RESISTANCE_RANGE -> "SupportedResistanceRange"
        CONTROL -> "ControlPoint"
        MACHINE_STATUS -> "MachineStatus"
        else -> u?.toString() ?: "?"
    }

    // --- value builders ---------------------------------------------------

    private fun cccd(): BluetoothGattDescriptor {
        val d = BluetoothGattDescriptor(CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        d.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        return d
    }

    /** 0x2ACC: [LSO data features u32][MSO target features u32].
     *
     *  MSO "Target Setting Features" (Table 4.3.1.2) bits set here:
     *   bit2 Resistance Target Setting (always — the bike has a resistance)
     *   bit1 Inclination Target Setting  (if the console reports a grade)
     *   bit13 Indoor Bike Simulation Parameters (if the console reports a
     *        grade) — this is the op virtual-trainer apps (Zwift, MyWhoosh)
     *        actually use to drive a bike: they push course grade + wind and
     *        expect the trainer to respond. Without bit13 they send one 0x11,
     *        see "Not Supported", and never send another. */
    private fun buildFeature(): ByteArray {
        val grade = client.gradeSupported
        var lso = (1 shl 1) or (1 shl 2) or (1 shl 7) or (1 shl 10) or (1 shl 12) or (1 shl 13) or (1 shl 14)
        if (grade) lso = lso or (1 shl 3)
        var mso = (1 shl 2)
        if (grade) mso = mso or (1 shl 1) or (1 shl 13)
        return byteArrayOf(
            lso.toByte(), (lso ushr 8).toByte(), (lso ushr 16).toByte(), (lso ushr 24).toByte(),
            mso.toByte(), (mso ushr 8).toByte(), (mso ushr 16).toByte(), (mso ushr 24).toByte()
        )
    }

    /**
     * 0x2AD5 Supported Inclination Range (SIG attribute definition):
     * [Sint16 Min][Sint16 Max][Uint16 Min-Increment] — 6 octets, all
      * 0.1 %. Wire grades are ×100 %, so ÷10 for the 0.1 % fields. The
      * min is SIGNED (sint16), which is what lets a negative minimum
      * reach the central.
     */
    private fun buildInclineRange(): ByteArray {
        val min = client.minGrade / 10
        val max = client.maxGrade / 10
        return inclineRange(min, max, 1)
    }

    /**
     * 0x2AD6 Supported Resistance Level Range (SIG attribute definition):
     * [Sint16 Min][Sint16 Max][Uint16 Min-Increment] — 6 octets, 0.1
     * level — the SAME unit as the 0x2AD2 data field and the 0x04
      * control param (the TS cross-checks all three). Min level 1.0 →
      * 10; max = maxLevel×10, and the sint16 fields keep a maxLevel×10
      * above 255 (more than 25.5 levels) intact.
     */
    private fun buildResistanceRange(): ByteArray {
        val maxLvl = client.maxResistanceLevel.coerceIn(1, 100)
        return resistanceRange(10, maxLvl * 10, 10)
    }

    /**
     * 0x2AD2: Indoor Bike Data. FTMS mandates a COMPACT record (spec §4.9): a
     * field occupies bytes **iff** its Flags bit is 1 — when a bit is 0 the
     * field is NOT present and occupies NO bytes. There is no DNV "padding" to
     * hold absent slots; a conformant central walks the Flags and reads each
     * present field back-to-back.
     *
     * Present (Flags 0x1A74): Speed (bit0 MoreData=0 → always), Cadence(2),
     * Total Distance(4), Resistance(5), Power(6), Heart Rate(9), Elapsed(11),
     * Remaining(12). Absent (no FitPro1 source): Avg Speed(1), Avg Cadence(3),
     * Avg Power(7), Expended Energy(8), MET(10). These Flags exactly match the
     * Fitness Machine Feature bits declared in [buildFeature].
     *
     * Scales (FitPro1 wire → FTMS): Speed Kph is already 0.01 km/h → sent
     * directly (no scale — the FTMS Speed field is the same unit); Cadence
     * Rpm → ×2 (0.5 rpm); Distance metres → ×1000 (mm); Resistance
     * raw → level → ×10 (0.1 level); Power Watts direct (W); HR bpm; times in
     * seconds. All verified against the SIG spec and the observed console encodings.
     */
    private fun buildBikeData(): ByteArray {
        val latest = client.latest
        val kph = (latest[0] as? Number)?.toInt() ?: 0
        val rpm = (latest[5] as? Number)?.toInt() ?: 0
        val distM = (latest[4] as? Number)?.toLong() ?: 0L
        val resRaw = (latest[2] as? Number)?.toInt() ?: 0
        val watts = (latest[3] as? Number)?.toInt() ?: 0
        val hr = (latest[10] as? Number)?.toInt() ?: 0   // Pulse (contact HR; the console has no chest-strap source)
        val elapsed = (latest[11] as? Number)?.toInt() ?: 0
        val remaining = (latest[22] as? Number)?.toInt() ?: 0
        val mode = (latest[12] as? Number)?.toInt() ?: 1

        val flags = 0x1A74

        val o = ByteArrayOutputStream()
        w16(o, flags)                                              // [0-1]  Flags
        w16(o, kph.coerceIn(0, 65535))                            // [2-3]  Speed (0.01 km/h)
        w16(o, (rpm * 2).coerceIn(0, 65535))                      // [4-5]  Cadence (0.5 rpm)
        // [6-8]  Total Distance (mm, u24)
        val mm = (distM * 1000L).coerceIn(0L, 0x7FFFFFL)
        o.write((mm and 0xFF).toInt()); o.write(((mm ushr 8) and 0xFF).toInt()); o.write(((mm ushr 16) and 0xFF).toInt())
        // 0-based: level 1 → 10, top level → maxLvl×10. Map raw→level only while
        // a workout is active — in Idle the console parks a non-level raw (e.g.
        // 2500) that must not read as a mid-range level, so report the range
        // minimum (10) instead. (Per-model full-scale lives in the client.)
        w16(o, (if (mode in ACTIVE_MODES) client.levelFromRaw(resRaw) else 1) * 10) // [9-10] Resistance (0.1 level)
        w16(o, watts.coerceIn(0, 65535))                          // [11-12] Power (W)
        o.write(if (hr > 0) (hr and 0xFF) else 0xFF)             // [13]   Heart Rate (bpm; DNV 0xFF if none)
        w16(o, elapsed.coerceIn(0, 65535))                        // [14-15] Elapsed Time (s)
        w16(o, if (remaining > 0) remaining.coerceIn(0, 65535) else 0xFFFF) // [16-17] Remaining Time (s)
        return o.toByteArray()
    }

    /** 0x2AD3: [Flags u8][Status u8] (no optional string in v1). */
    private fun buildTrainingStatus(mode: Int): ByteArray {
        val status = when (mode) {
            1, 12 -> 0x01 // Idle / Sleep
            2 -> 0x0D      // Running → Manual Mode (Quick Start)
            10 -> 0x02     // WarmUp
            11 -> 0x0B     // CoolDown
            else -> 0x00   // Other (Pause/Results/Locked/Debug…)
        }
        return byteArrayOf(0, status.toByte())
    }

    /** [0x80][op][result] Control Point indication (spec Table 4.23). */
    private fun indication(op: Int, result: Int) =
        byteArrayOf(0x80.toByte(), op.toByte(), result.toByte())

    /** Little-endian u16 from [value] at [off] (0xFFFF if the write is short). */
    private fun u16(value: ByteArray, off: Int): Int =
        if (value.size > off + 1)
            (value[off].toInt() and 0xFF) or ((value[off + 1].toInt() and 0xFF) shl 8)
        else 0xFFFF

    /** Send [bytes] on Machine Status to [device] (used for 0xFF control-lost). */
    private fun sendTo(device: BluetoothDevice, bytes: ByteArray) {
        val ms = machineStatusChar ?: return
        val g = gatt ?: return
        try { ms.value = bytes; g.notifyCharacteristicChanged(device, ms, false) } catch (_: Exception) {}
    }

    /** Machine Status [Op][Parameter…] (Table 4.26) to the Machine Status
     *  subscribers, minus [exclude] when set. */
    private fun emitMachineStatus(op: Int, param: Int?, exclude: BluetoothDevice? = null) {
        val g = gatt ?: return
        val ms = machineStatusChar ?: return
        val bytes = MachineStatus.encode(op, param)
        val excluded = exclude?.let { addr(it) }
        for (a in ccc.subsFor(KEY_MACHINE_STATUS)) {
            if (a == excluded) continue
            val d = addressToDevice[a] ?: continue
            try { ms.value = bytes; g.notifyCharacteristicChanged(d, ms, false) } catch (_: Exception) {}
        }
    }

    /** Machine Status with a variable-length parameter (Table 4.26) — used
     *  for 0x12 Indoor Bike Simulation Parameters Changed (the 6-octet array).
     *  Same per-subscriber delivery as the Int overload. */
    private fun emitMachineStatus(op: Int, param: ByteArray, exclude: BluetoothDevice? = null) {
        val g = gatt ?: return
        val ms = machineStatusChar ?: return
        val bytes = MachineStatus.encode(op, param)
        val excluded = exclude?.let { addr(it) }
        for (a in ccc.subsFor(KEY_MACHINE_STATUS)) {
            if (a == excluded) continue
            val d = addressToDevice[a] ?: continue
            try { ms.value = bytes; g.notifyCharacteristicChanged(d, ms, false) } catch (_: Exception) {}
        }
    }

    /** Push Training Status for [mode] to [device] (null ⇒ all Training
     *  Status subscribers). */
    private fun emitTrainingStatus(device: BluetoothDevice?, mode: Int) {
        val g = gatt ?: return
        val tr = trainingChar ?: return
        if (device != null) {
            try { tr.value = buildTrainingStatus(mode); g.notifyCharacteristicChanged(device, tr, false) } catch (_: Exception) {}
            return
        }
        for (a in ccc.subsFor(KEY_TRAINING)) {
            val d = addressToDevice[a] ?: continue
            try { tr.value = buildTrainingStatus(mode); g.notifyCharacteristicChanged(d, tr, false) } catch (_: Exception) {}
        }
    }

    /** The console-side parameters of an accepted Control Point op,
     *  precomputed on the MAIN thread at validation time and handed to
     *  the worker as plain ints: the worker must only do I/O, never
     *  re-parse the write or re-run a validation decision. (The write's
     *  byte array itself is safe to capture — the GATT layer hands a
     *  fresh array per write — but re-parsing it off-main would just
     *  duplicate the validator.) 0x01 carries no parameter; 0x03
      *  [grade] (×100 % wire grade) + [param] (0.1 %); 0x04 [param]
      *  (0.1 level) + [raw]; 0x07/0x08 [target] (the WorkoutMode the
      *  latch decision picked), 0x08 also [subOp]; 0x11 [grade] (the
      *  0.01 % sim grade, which is already the wire grade) + [sim] (the
      *  6-octet Simulation Parameter Array echoed in the 0x12 status). */
    private data class ConsoleAction(
        val op: Int,
        val grade: Int = -1,   // 0x03: wire grade = param×10 / 0x11: wire grade = sim grade (0.01 %)
        val param: Int = -1,    // 0x03: 0.1 % / 0x04: 0.1 level (the announced value)
        val raw: Int = -1,      // 0x04: the console raw for that level
        val target: Int = -1,   // 0x07/0x08: the decided WorkoutMode
        val subOp: Int = 0x01,  // 0x08: 0x01 Stop / 0x02 Pause (Table 4.16)
        val sim: ByteArray? = null // 0x11: [Wind s16][Grade s16][Crr u8][Cw u8] (Table 4.20)
    )

    /**
     * Fast main-thread validator for a Control Point write (driven by
     * onCharacteristicWriteRequest). Decides everything main-thread
     * state can decide — control permission (spec 4.16.2.1), parameter
     * presence/range (Table 4.15; Table 4.24 result codes), Start/Stop
     * idempotency (from the mode latch, never from the stale 10 Hz
     * sample) — and answers the write SYNCHRONOUSLY: the Write
     * Response first, then the [0x80][op][result] indication to the
     * writer (no console round-trip, no in-flight state touched).
     *
     * Returns a [ConsoleAction] ONLY for an op that must actually
     * round-trip to the console (0x01, and 0x03/0x04/0x07/0x08 that
     * pass validation): by the time the action is returned, its Write
     * Response has ALREADY been sent — FTMS §4.16.4 starts the
     * procedure at that moment ("a procedure is started when a write …
     * is successfully completed (i.e., the Server sends a Write
     * Response)") — and the caller then begins the procedure in
     * [gate] and queues [runConsoleProcedure] on the worker. The two
     * FTMS §4.16.3 preconditions are applied by the CALLER before this
     * validator runs: a write arriving while a procedure is in flight
     * is rejected with the 0x0C "Procedure Already In Progress" ATT
     * error response and never queued, and a write from a central
     * whose Control Point CCCD is not configured for indications is
     * rejected with the 0x0E "CCCD Improperly Configured" error — per
     * §4.16.4 a write that results in an ATT error response neither
     * starts a procedure nor is queued in the Server, so this
     * validator only ever sees admissible writes.
     */
    private fun validateControlWrite(g: BluetoothGattServer, device: BluetoothDevice?, requestId: Int, responseNeeded: Boolean, value: ByteArray): ConsoleAction? {
        if (value.isEmpty()) {
            answerWrite(g, device, requestId, responseNeeded, indication(0x00, 0x02))
            return null
        }
        val op = value[0].toInt() and 0xFF

        // Control permission (spec 4.16.2.1): every op except 0x00 requires
        // that this central is the current holder. Non-holders get 0x05 with
        // NO console side-effect (this is exactly what qdomyos observes from
        // Zwift against a real bike).
        if (op != 0x00 && device != controlHolder) {
            log(">>> REJECT 0x05 (not holder) op=0x${op.toString(16)} from ${addr(device)} ≠ holder ${addr(controlHolder)}")
            answerWrite(g, device, requestId, responseNeeded, indication(op, 0x05))
            return null
        }

        when (op) {
            0x00 -> { // Request Control — always permitted; single-control model.
                if (device == null) {
                    answerWrite(g, device, requestId, responseNeeded, indication(op, 0x04))
                } else {
                    val prev = controlHolder
                    controlHolder = device
                    controlGranted = true
                    if (prev != null && prev != device) {
                        log("control moved ${addr(prev)} → ${addr(device)}; 0xFF to old holder")
                        sendTo(prev, byteArrayOf(0xFF.toByte()))
                    }
                    log("control granted to ${addr(device)}")
                    answerWrite(g, device, requestId, responseNeeded, indication(op, 0x01))
                }
                // 0x00 never touches the console (a pure state change),
                // so it is fully synchronous on the main thread.
                return null
            }
            0x01 -> { // Reset: no parameter to validate — straight to the console.
                answerWrite(g, device, requestId, responseNeeded, null)
                return ConsoleAction(op)
            }
            0x03 -> { // Set Target Incline: SINT16 @ 0.1 % (Table 4.15) → Grade (×100 %) = param×10.
                // A write shorter than the 2-octet parameter is malformed, not
                // a value: answer Invalid Parameter (Table 4.24) and touch
                // neither the console nor Machine Status.
                val param = decodeS16(value, 1) ?: run {
                    log(">>> REJECT 0x03 (bad param) op=0x03 SetTargetIncline: write shorter than the 2-octet parameter → 0x03")
                    answerWrite(g, device, requestId, responseNeeded, indication(op, 0x03))
                    return null
                }
                // Precompute the wire grade on main so the worker only
                // does I/O.
                val grade = param * 10
                if (grade < client.minGrade || grade > client.maxGrade) {
                    log(">>> REJECT 0x03 (out of range) op=0x03 SetTargetIncline param=$param (0.1 %) → grade $grade ∉ [${client.minGrade},${client.maxGrade}] → 0x03")
                    answerWrite(g, device, requestId, responseNeeded, indication(op, 0x03))
                    return null
                }
                answerWrite(g, device, requestId, responseNeeded, null)
                return ConsoleAction(op, grade = grade, param = param)
            }
            0x04 -> { // Set Target Resistance: u16 @ 0.1 level (same unit as 0x2AD6).
                val param = u16(value, 1)
                val maxLvl = client.maxResistanceLevel.coerceIn(1, 100)
                // The 0x2AD6 range is advertised in the same 0.1-level
                // units via the SIG 6-octet form (no 255 cap), so the
                // accepted bound is the advertised bound; the s16 clamp
                // is paranoia only (maxLvl ≤ 100).
                val maxTenths = (maxLvl * 10).coerceIn(-0x8000, 0x7FFF)
                if (param < 10 || param > maxTenths) {
                    log(">>> REJECT 0x03 (out of range) op=0x04 SetTargetResistance param=$param (0.1 lvl) ∉ [10,$maxTenths] → 0x03")
                    answerWrite(g, device, requestId, responseNeeded, indication(op, 0x03))
                    return null
                }
                // Precompute the console raw on main (a pure mapping —
                // no USB) so the worker only does I/O.
                val raw = client.rawFromLevel(param / 10)
                answerWrite(g, device, requestId, responseNeeded, null)
                return ConsoleAction(op, param = param, raw = raw)
            }
            0x07 -> { // Start or Resume.
                // "Already started/resumed?" is decided from the MODE LATCH
                // (sequence-gated, see [modeLatch]), never from the raw
                // console sample: the latest sample may predate the mode
                // write a PREVIOUS 0x07 just issued, and acting on that
                // stale sample let a duplicate Start answer Success again.
                // The duplicate must get 0x04 Operation Failed (Table 4.24)
                // with no console write and no status emissions (SPE
                // BV-01-C: exactly two response indications for the two
                // writes; §4.16.2.x).
                val (target, fail) = StartStop.startDecision(lastMode)
                if (fail != 0) {
                    log("Start: already in mode $lastMode → 0x04, no console write")
                    answerWrite(g, device, requestId, responseNeeded, indication(op, fail))
                    return null
                }
                answerWrite(g, device, requestId, responseNeeded, null)
                return ConsoleAction(op, target = target)
            }
            0x08 -> { // Stop or Pause (param 0x01 Stop / 0x02 Pause, Table 4.16).
                // Strict Table 4.16 parse: the write must carry the Control
                // Info octet and only 0x01/0x02 are defined (0x00 and
                // 0x03–0xFF are RFU). A missing or reserved octet is
                // answered Invalid Parameter (Table 4.24) — no console
                // write, no emission, no in-flight. (Coercing such a write
                // to Stop would invent a meaning the spec explicitly leaves
                // undefined.)
                val subOp: Int = when (StopPause.parse(value)) {
                    StopPause.Outcome.STOP -> 0x01
                    StopPause.Outcome.PAUSE -> 0x02
                    StopPause.Outcome.INVALID -> {
                        val info = if (value.size < 2)
                            "(empty param)" else "0x${(value[1].toInt() and 0xFF).toString(16)}"
                        log("Stop/Pause: missing or reserved Control Info $info (Table 4.16) → 0x03 Invalid Parameter")
                        answerWrite(g, device, requestId, responseNeeded, indication(op, 0x03))
                        return null
                    }
                }
                // Same latch-not-sample rationale as the 0x07 branch above
                // (SPE BV-02-C: a duplicate 0x08 after a successful 0x08
                // must get [0x80][0x08][0x04] with no console round-trip
                // and no status emissions; §4.16.2.x).
                val (target, fail) = StartStop.stopDecision(lastMode, subOp)
                if (fail != 0) {
                    log("0x08 (0x${subOp.toString(16)}): already mode $lastMode → 0x04, no console write")
                    answerWrite(g, device, requestId, responseNeeded, indication(op, fail))
                    return null
                }
                answerWrite(g, device, requestId, responseNeeded, null)
                return ConsoleAction(op, target = target, subOp = subOp)
            }
            0x11 -> { // Set Indoor Bike Simulation Parameters (Table 4.20).
                // This is how virtual-trainer apps (Zwift, MyWhoosh) drive a
                // bike: they push the course grade + wind each time the route
                // changes and expect the trainer to keep up. A bike that
                // answers 0x11 with "Not Supported" is treated as dead — the
                // app sends one 0x11, sees the 0x02, and never sends another.
                //
                // Simulation Parameter Array (7 octets incl. the op code):
                //   [Wind SINT16 @1]  0.001 m/s
                //   [Grade SINT16 @3] 0.01 %
                //   [Crr  UINT8  @5]  rolling-resistance coeff, 0.0001
                //   [Cw   UINT8  @6]  wind-resistance coeff, 0.01
                //
                // SCOPE: we only act on the GRADE here. The console's wire
                // grade is in 0.01 % units, so the 0x11 Grade parameter maps
                // to it 1:1 (unlike 0x03, which is 0.1 % and needs ×10). We
                // deliberately do NOT compute a resistance from Wind/Crr/Cw —
                // that needs a physics model (speed, mass, drag) that is out
                // of scope for now. The two resistance coefficients (Crr, Cw)
                // are parsed and logged but currently do NOTHING: no console
                // resistance write is issued from them.
                val sim = SimulationParams.parse(value, 1) ?: run {
                    log(">>> REJECT 0x03 (bad param) op=0x11 SetSimParams: write ${value.size}B < the 6-octet array → 0x03")
                    answerWrite(g, device, requestId, responseNeeded, indication(op, 0x03))
                    return null
                }
                if (sim.grade < client.minGrade || sim.grade > client.maxGrade) {
                    log(">>> REJECT 0x03 (out of range) op=0x11 SetSimParams grade=${sim.grade} (0.01 %) ∉ [${client.minGrade},${client.maxGrade}] → 0x03")
                    answerWrite(g, device, requestId, responseNeeded, indication(op, 0x03))
                    return null
                }
                log("op=0x11 SetSimParams wind=${sim.wind} mps grade=${sim.grade} (0.01 %) crr=${sim.crr} cw=${sim.cw} → apply grade; Crr/Cw parsed but UNUSED (no physics yet)")
                answerWrite(g, device, requestId, responseNeeded, null)
                return ConsoleAction(op, grade = sim.grade, sim = value.copyOfRange(1, 7))
            }
            // 0x02 speed, 0x05 power, 0x06 HR, 0x0A..0x10, 0x12..0x7F — not supported in v1.
            else -> {
                answerWrite(g, device, requestId, responseNeeded, indication(op, 0x02))
                return null
            }
        }
    }

    /** Send the Write Response for a Control Point write — and, when
     *  [bytes] is non-null, the [0x80][op][result] indication to the
     *  WRITING central (the synchronous answers; an accepted op's
     *  indication arrives later, with the [runConsoleProcedure]
     *  post-back, so [bytes] is null there). The response always goes
     *  FIRST: on an accepted write it is what STARTS the procedure
     *  (FTMS §4.16.4), on a rejected one the write never becomes a
     *  procedure at all. */
    private fun answerWrite(g: BluetoothGattServer, device: BluetoothDevice?, requestId: Int, responseNeeded: Boolean, bytes: ByteArray?) {
        if (responseNeeded) g.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        if (bytes != null) sendIndication(device, bytes)
    }

    /** Deliver the [0x80][op][result] indication to the WRITING central —
     *  it is THE answer to that central's write, never to other
     *  (non-writer) centrals. A null [device] or an already-closed
     *  server simply drops it (there is nothing left to answer). */
    private fun sendIndication(device: BluetoothDevice?, bytes: ByteArray) {
        val g = gatt ?: return
        val cc = controlChar ?: return
        if (device != null) {
            try { cc.value = bytes; g.notifyCharacteristicChanged(device, cc, true) } catch (_: Exception) {}
        }
    }

    /** [ConsoleAction] → the queue's pure [CommandQueue.Payload] (the same
     *  fields, decoupled so [CommandQueue] has no android.* dependency). */
    private fun ConsoleAction.toPayload() =
        CommandQueue.Payload(op, grade, param, raw, target, subOp, sim)

    /** The reverse of [toPayload]: rebuild the console action from a queue
     *  [Payload]. The dispatch-time [CommandQueue.Payload.target] may have
     *  been re-decided for a mode op; non-mode ops keep the admission-time
     *  value (which the console side ignores). */
    private fun CommandQueue.Payload.toAction() =
        ConsoleAction(op, grade, param, raw, target, subOp, sim)

    /** Perform one of the [queue]'s [CommandQueue.DispatchEvent]s on the
     *  main thread. [CommandQueue.DispatchEvent.Noop]: release the
     *  command's [gate] slot and answer [0x80][op][fail] — no console
     *  write (its Write Response was already sent at admission; only the
     *  indication is owed now). [CommandQueue.DispatchEvent.Execute]:
     *  rebuild the action and post the console round-trip to the worker
     *  (its gate slot is released by the post-back). The writer is resolved
     *  from its address; a disconnected writer simply gets no indication
     *  (an Execute's console side still runs — a writer disconnect is not a
     *  cancellation). */
    private fun perform(e: CommandQueue.DispatchEvent) {
        when (e) {
            is CommandQueue.DispatchEvent.Noop -> {
                gate.end(e.id)
                val d = addressToDevice[e.writer]
                if (d != null) sendIndication(d, indication(e.op, e.fail))
                else log(">>> QUEUE no-op 0x${e.op.toString(16)} for ${e.writer} dropped (writer disconnected)")
            }
            is CommandQueue.DispatchEvent.Execute -> {
                val d = addressToDevice[e.writer]
                val action = e.payload.toAction()
                log(">>> DISPATCH op=0x${action.op.toString(16)} to console (from queue; target=${action.target} subOp=${action.subOp})")
                consoleWorker.post { runConsoleProcedure(e.id, action, d) }
            }
        }
    }

    /**
     * The console side of an accepted Control Point op, on the
     * dedicated [consoleWorker] thread. FTMS §4.16.4 started the
     * procedure when the main thread sent the Write Response; this
     * transact is what the procedure IS on the console side — a
     * blocking USB round-trip (synchronized on the same lock the
     * 10 Hz pump holds, plus an 80 ms settle), and it must never run
     * on the main looper (80–200 ms of work per write would be an ANR
     * vector).
     *
     * It deliberately does NOT run on the engine/worker thread either:
     * that thread is occupied by the state machine + pump's blocking
     * while-loop, which never yields to its Looper — a round-trip
     * posted there would sit un-dequeued for the whole session, latching
     * the in-flight flag forever (see [BridgeService.onCreate]). The
     * dedicated thread keeps the I/O off-main (no ANR) yet runnable, and
     * it still serializes on the pump's USB lock, now cross-thread.
     *
     * Only the console I/O happens here. The [FitPro1Client.readSeq]
     * stamp is captured here as a @Volatile cross-thread read: the pump
     * advances the counter on the worker thread, so the value seen here
     * is the newest sample at capture time — equal to, or a tick or two
     * newer than, the same-thread value would be. That is the
     * conservative direction [modeLatch] already tolerates (a higher
     * stamp only drops a couple extra pre-confirmation samples as Stale;
     * it never re-announces). Everything else (latch, baselines,
     * emissions, the indication) is posted back to the main thread in
     * [onConsoleProcedureResult] — it owns all of that state.
     */
    private fun runConsoleProcedure(id: Int, action: ConsoleAction, device: BluetoothDevice?) {
        val ok = when (action.op) {
            0x01 -> client.writeReset()
            0x03 -> client.writeGrade(action.grade)
            // 0x11 Set Indoor Bike Simulation Parameters: we only act on the
            // sim's Grade (already the 0.01 % wire grade — see the validator).
            // The Wind/Crr/Cw coefficients are intentionally NOT turned into a
            // resistance here (no physics model in v1) — see [onConsoleProcedureResult].
            0x11 -> client.writeGrade(action.grade)
            0x04 -> client.writeResistance(action.raw)
            0x07 -> client.writeWorkoutMode(action.target)
            0x08 -> client.writeWorkoutMode(action.target)
            else -> false
        }
        // The staleness boundary for the mode ops (see [ModeLatch]):
        // the readSeq of the newest periodic decode at the moment the
        // write acked. Only meaningful on success.
        val ackSeq = if (ok) client.readSeq else 0L
        handler?.post { onConsoleProcedureResult(id, action, device, ok, ackSeq) }
    }

    /**
     * The main-thread half of [runConsoleProcedure]: apply the per-op
     * effects once the console side has finished. Runs on the main
     * looper because everything it touches is main-thread-confined —
     * the mode latch and the 1 Hz pump, the announced baselines, the
     * CCC subscription registry, the GATT server.
     */
    private fun onConsoleProcedureResult(id: Int, action: ConsoleAction, device: BluetoothDevice?, ok: Boolean, ackSeq: Long) {
        // A full teardown (stop()/closeGatt) may have bumped the
        // gate's cancel watermark past this id after the worker
        // captured it: the GATT server is closed, and the subscription
        // registry and the mode latch are already reset. The post-back
        // must return without touching ANY of that state — a noteWrite
        // here would re-latch the mode closeGatt just cleared and
        // ghost-announce a transition on the next start.
        // (A WRITER disconnect is NOT such a cancellation: the console
        // side still completed, the machine state genuinely changed,
        // and the emissions below naturally reach only the remaining
        // subscribers.)
        if (gate.isCancelled(id)) return
        gate.end(id)
        if (!ok) {
            // No latch / baseline effects on failure — the console did
            // not apply the write, so the machine state (and the pump's
            // view of it) is untouched.
            val name = when (action.op) {
                0x01 -> "Reset"
                0x03 -> "SetTargetIncline"
                0x04 -> "SetTargetResistance"
                0x07 -> "Start/Resume"
                0x08 -> "Stop/Pause"
                0x11 -> "SetSimParams"
                else -> "0x${action.op.toString(16)}"
            }
            log(">>> CONSOLE-FAIL op=0x${action.op.toString(16)} ${name} ${addr(device)}: ${client.lastError}")
            sendIndication(device, indication(action.op, 0x04))
            // A failure frees the console too — drain what is queued (the
            // latch is unchanged on failure, so no mode op re-decides).
            queue.completed(modeLatch.latched).forEach { perform(it) }
            return
        }
        // [DEBUG] The USB round-trip succeeded: the console genuinely
        // applied the command. Distinguishes "MyWhoosh's write never
        // reached the console" from "the console accepted it but the
        // machine didn't obey".
        log(">>> CONSOLE-OK op=0x${action.op.toString(16)} ${addr(device)}: applied (param=${action.param} raw=${action.raw} grade=${action.grade} target=${action.target} subOp=${action.subOp})")
        when (action.op) {
            0x01 -> { // Reset: targets → defaults, WorkoutMode → Idle(1).
                // The readSeq captured at ack time is the staleness
                // boundary: pump samples sequence-stamped <= it predate
                // the console applying this write and must not move the
                // latch (doing so would re-fire this transition and
                // duplicate the Machine Status below). The capture
                // happens in [runConsoleProcedure], on the worker
                // thread that performed the write.
                modeLatch.noteWrite(1, ackSeq)
                // Reset moves BOTH targets to their defaults
                // (writeReset: Grade=0, Resistance=0 → level 1): pin
                // both baselines (ICS 4/22, 4/23) so the console's
                // echo of OUR write is not re-announced as a
                // console-driven change. §4.16.2.2 obliges the client
                // to adopt the declared defaults on a Reset
                // indication; if the console does NOT actually move a
                // target, the pump's next divergent sample announces
                // the true value — a truthful correction.
                announcer.clientAnnouncedGrade(0)
                announcer.clientAnnouncedResistance(10)
                // §4.17.1: client-driven → the OTHER clients only.
                emitMachineStatus(0x01, null, exclude = device)
                sendIndication(device, indication(action.op, 0x01))
            }
            0x03 -> {
                // The client commanded this incline (§4.17.1:
                // client-driven): pin the baseline to the value we
                // just announced so the console's echo of OUR write
                // is not re-announced as a console-driven change
                // (ICS 4/22).
                announcer.clientAnnouncedGrade(action.param)
                // §4.17.1: client-driven → the OTHER clients only.
                emitMachineStatus(0x06, action.param, exclude = device)
                sendIndication(device, indication(action.op, 0x01))
            }
            0x04 -> {
                // The client commanded this resistance level
                // (ICS 4/23): pin the baseline so the console's
                // echo of OUR write is not re-announced as a
                // console-driven change.
                announcer.clientAnnouncedResistance(action.param)
                // §4.17.1: client-driven → the OTHER clients only.
                emitMachineStatus(0x07, action.param, exclude = device)
                sendIndication(device, indication(action.op, 0x01))
            }
            0x07 -> {
                // Same staleness boundary as the 0x01 path above: the
                // readSeq at ack time; pump samples stamped <= it
                // predate the console applying this write.
                modeLatch.noteWrite(action.target, ackSeq)
                // No baseline pinning: a Start write is MODE-ONLY
                // (ICS 4/22, 4/23 — it sets no target). The
                // console's own reaction is not bridge-commanded,
                // so the pump announces any resulting target
                // change as user-driven to all clients (§4.17.1)
                // — a truthful over-notification beats guessing
                // the console's internal defaults.
                emitTrainingStatus(null, action.target)
                // §4.17.1: client-driven → the OTHER clients only.
                emitMachineStatus(0x04, null, exclude = device)
                sendIndication(device, indication(action.op, 0x01))
            }
            0x08 -> {
                // Same staleness boundary as the 0x01 path above.
                modeLatch.noteWrite(action.target, ackSeq)
                // No baseline pinning: a Stop/Pause write is MODE-ONLY
                // (ICS 4/22, 4/23 — it sets no target). The console's
                // own teardown (it may zero the grade) is not
                // bridge-commanded, so the pump announces any
                // resulting target change as user-driven to all
                // clients (§4.17.1) — a truthful over-notification
                // beats guessing the console's internal defaults.
                emitTrainingStatus(null, action.target)
                // §4.17.1: client-driven → the OTHER clients only.
                emitMachineStatus(0x02, if (action.subOp == 0x02) 0x02 else 0x01, exclude = device)
                sendIndication(device, indication(action.op, 0x01))
            }
            0x11 -> {
                // The client pushed new sim parameters (§4.17.1:
                // client-driven). We acted only on the Grade (the 0x11
                // sim grade is already the 0.01 % console wire grade), so
                // pin the grade baseline to it in TENTHS (the announcer's
                // unit) to stop the 1 Hz pump re-announcing the same change
                // as a 0x06 console-driven incline.
                announcer.clientAnnouncedGrade(action.grade / 10)
                // Tell the OTHER clients the sim parameters changed
                // (Machine Status 0x12, Table 4.26) — the writing client is
                // excluded per §4.17.1. The parameter is the full 6-octet
                // Simulation Parameter Array (Table 4.20) echoed back.
                // (The Wind/Crr/Cw coefficients in that array are only
                // echoed, never acted on — v1 has no resistance physics.)
                action.sim?.let { emitMachineStatus(0x12, it, exclude = device) }
                sendIndication(device, indication(action.op, 0x01))
            }
            // Defensive arm: the validator only ever queues the ops above,
            // so this is unreachable — but a procedure no effect-handler
            // knows must still answer its writer.
            else -> sendIndication(device, indication(action.op, 0x04))
        }
        // The console is free again: drain whatever is queued, re-deciding
        // any queued mode op against the now-current latch so a command a
        // preceding one made redundant is answered Operation Failed instead
        // of being re-issued to the console.
        queue.completed(modeLatch.latched).forEach { perform(it) }
    }

    private fun w16(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF); o.write((v ushr 8) and 0xFF)
    }
}
