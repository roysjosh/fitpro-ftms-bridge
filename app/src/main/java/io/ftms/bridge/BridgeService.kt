package io.ftms.bridge

import io.ftms.bridge.BuildConfig
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.widget.Toast

/**
 * The bridge: a FitPro1-over-USB engine — attach → claim → handshake (device
 * capability discovery) → 10 Hz telemetry pump — plus the FTMS GATT server
 * (0x1826) mapped onto the console, which carries the write path.
 *
 * Foreground + START_STICKY so the process (and the USB claim) survive.
 *
 * State machine (all transitions executed on [worker]):
 *   NO_DEVICE → NO_PERMISSION → CONNECTING → HANDSHAKE → STREAMING
 *        ↑             (re-request)              │
 *        └──────────── onDetached / 5×read-fail ┘  (reconnect loop)
 */
class BridgeService : Service(), UsbTransport.Listener {

    enum class State {
        NO_DEVICE,          // brainboard not attached
        NO_PERMISSION,      // attached, waiting on the system "allow USB"
        CONNECTING,         // permission granted, claiming interface
        HANDSHAKE,          // claimed; DeviceInfo→…→Startup (capability discovery)
        STREAMING,          // ready; 100 ms periodic pump
        ERROR               // claim/handshake failed; retrying
    }

    companion object {
        const val CHANNEL = "bridge"
        const val TAG = "BridgeService"
        const val DEBUG_PORT = 18765 // adb forward tcp:18765 tcp:18765
        const val DUMP_ACTION = "io.ftms.bridge.DUMP"
        const val MAX_LOST_BEFORE_RECONNECT = 5

        // Set by the service so the (non-bound) activity can poll status.
        @Volatile var instance: BridgeService? = null
    }

    @Volatile var state: State = State.NO_DEVICE
        private set

    val hex = HexLog() // context-free — safe as an eager field

    // Context-dependent: the Service's base context is null until
    // attachBaseContext(), which happens AFTER Class.newInstance() — so these
    // are constructed in onCreate(), never as field initializers.
    private lateinit var transport: UsbTransport
    lateinit var client: FitPro1Client // public: the activity polls its telemetry
    private lateinit var debugSocket: DebugSocket
    private lateinit var ftms: FtmsServer

    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var consoleThread: HandlerThread? = null
    private var running = false
    private var created = false
    private var lostCount = 0

    /**
     * Observation channel for ROMs whose adbd breaks the adb-forward data path
     * (this tablet): `adb shell am broadcast -a io.ftms.bridge.DUMP`
     * writes status + engine log + hex ring to the app's external files dir;
     * pull it with adb. Logs the path at E level (this ROM drops D/I).
     */
    private val dumpReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent?) {
            if (intent?.action == DUMP_ACTION) {
                post { writeDump() }
            }
        }
    }

    // ------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        // E-level (this ROM drops D/I from logcat) + in the debug status, so
        // we always know which build is actually running on the device.
        Log.e(TAG, "Open FTMS Bridge starting: build ${BuildConfig.BUILD_STAMP} (v${BuildConfig.VERSION_NAME})")
        createChannel()
        transport = UsbTransport(this)
        client = FitPro1Client(transport, hex)
        client.log = { line -> Log.d(TAG, line) }
        // The engine (state machine + 10 Hz pump) runs on the worker thread
        // as a blocking while-loop that never returns to its Looper, so a
        // Runnable posted to [worker]'s Handler sits behind that loop and is
        // never dequeued while streaming. The FTMS server must therefore
        // queue its accepted Control Point console round-trips on a SEPARATE
        // thread ([consoleThread]): posted to [worker] they would starve and
        // the procedure's in-flight latch would be set but never released,
        // and the §4.16.3 PAIP gate would then reject every subsequent
        // Control Point write (the MyWhoosh "bike won't start" regression).
        // The worker (and the [handler] built on it) still exists first, so
        // the [post] helper above it is valid; [handler] is non-null here.
        val wt = HandlerThread("bridge-worker").also { worker = it }
        wt.start()
        Handler(wt.looper).also { handler = it }
        val ct = HandlerThread("bridge-console").also { consoleThread = it }
        ct.start()
        ftms = FtmsServer(this, client, Handler(ct.looper))
        transport.listener = this
        debugSocket = DebugSocket(this, client, hex)
        debugSocket.start()
        startForeground(1, buildNotification())

        try {
            registerReceiver(dumpReceiver, IntentFilter(DUMP_ACTION))
        } catch (_: Exception) {
        }
        // Publish last: the UI polls statusSnapshot(), which now touches the
        // BLE half, so every field must be initialised before instance is set.
        instance = this
        created = true
        running = true
        post { runStateMachine() }
    }

    private fun post(block: () -> Unit) {
        if (handler == null) block() else handler!!.post(Runnable(block))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // (re)started — e.g. after a process death. The worker picks the
        // device up again via its state machine.
        startForeground(1, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (!created) return // destroyed before onCreate completed — nothing to release
        Log.w(TAG, "onDestroy — releasing USB, stopping pump")
        running = false
        instance = null
        transport.listener = null
        post {
            try { ftms.stop() } catch (_: Exception) {}
            try { debugSocket.stop() } catch (_: Exception) {}
            try { unregisterReceiver(dumpReceiver) } catch (_: Exception) {}
            transport.disconnect()
            transport.stopWatching()
            client.reset()
        }
        // Drop the console thread: it owns the in-flight USB round-trip.
        // ftms.stop() (posted to the worker above) cancels the gate and
        // clears the in-flight latch, so a round-trip still queued here
        // no-ops via the isCancelled guard in onConsoleProcedureResult.
        consoleThread?.quitSafely()
        consoleThread = null
        worker?.quitSafely()
        worker = null
        handler = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // UsbTransport.Listener (broadcast-thread callbacks; dispatched to
    // the worker before touching engine state)
    // ------------------------------------------------------------------

    override fun onDeviceAttached(device: UsbDevice) = post { onAttached(device) }
    override fun onDeviceDetached(device: UsbDevice?) = post { onDetached() }
    override fun onPermissionResult(device: UsbDevice?, granted: Boolean) = post { onPermission(device, granted) }

    private fun onAttached(device: UsbDevice) {
        Log.i(TAG, "device attached: ${device.deviceName} (${device.deviceClass})")
        if (state == State.STREAMING || state == State.HANDSHAKE) {
            // Re-attach mid-session: tear down and reconnect from scratch.
            Log.w(TAG, "re-attach during session — reconnecting")
            transport.disconnect()
            client.reset()
            lostCount = 0
        }
        if (transport.hasPermission(device)) {
            state = State.CONNECTING
        } else {
            state = State.NO_PERMISSION
            transport.requestPermission(device)
            Log.i(TAG, "requested USB permission (dialog shown)")
        }
        syncFtms()
        updateNotification()
        // Nudge the loop if it was parked in NO_DEVICE.
        wake()
    }

    private fun onDetached() {
        Log.w(TAG, "device detached — releasing claim")
        if (state == State.STREAMING || state == State.HANDSHAKE || state == State.CONNECTING) {
            transport.disconnect()
            client.reset()
            state = State.NO_DEVICE
            syncFtms()
            updateNotification()
        }
    }

    private fun onPermission(device: UsbDevice?, granted: Boolean) {
        if (granted && device != null && transport.isOurs(device)) {
            Log.i(TAG, "USB permission granted")
            state = State.CONNECTING
            wake()
        } else if (!granted) {
            Log.w(TAG, "USB permission denied")
            state = State.NO_PERMISSION
            updateNotification()
        }
    }

    // ------------------------------------------------------------------
    // State machine (worker thread)
    // ------------------------------------------------------------------

    private fun runStateMachine() {
        transport.startWatching()
        while (running) {
            val device = transport.findDevice()
            when {
                device == null -> {
                    if (state != State.NO_DEVICE && state != State.NO_PERMISSION) {
                        state = State.NO_DEVICE
                        syncFtms()
                        updateNotification()
                    }
                    // The broadcast receiver stays registered (detach/attach/
                    // permission events); the poll above is the backstop.
                    sleep(1000)
                    continue
                }
                !transport.hasPermission(device) -> {
                    if (state != State.NO_PERMISSION) {
                        state = State.NO_PERMISSION
                        updateNotification()
                    }
                    // (Re)request, then block until the user answers.
                    transport.requestPermission(device)
                    val deadline = System.currentTimeMillis() + 120_000
                    while (running && !transport.hasPermission(device)
                        && !transport.permissionGranted
                        && System.currentTimeMillis() < deadline) {
                        sleep(300)
                    }
                    continue
                }
                state == State.STREAMING || state == State.HANDSHAKE -> {
                    // Already live — let the pump run; loop re-checks for
                    // detach/re-attach.
                    runPump()
                    continue
                }
                else -> {
                    state = State.CONNECTING
                    updateNotification()
                    if (!transport.connect(device)) {
                        state = State.ERROR
                        syncFtms()
                        updateNotification()
                        Log.e(TAG, "claim failed — retrying in 2 s")
                        sleep(2000)
                        continue
                    }
                    state = State.HANDSHAKE
                    updateNotification()
                    if (!client.handshake()) {
                        state = State.ERROR
                        syncFtms()
                        updateNotification()
                        Log.e(TAG, "handshake failed — tearing down, retry in 3 s")
                        transport.disconnect()
                        client.reset()
                        sleep(3000)
                        continue
                    }
                    state = State.STREAMING
                    lostCount = 0
                    syncFtms()
                    updateNotification()
                    runPump()
                    continue
                }
            }
        }
        transport.stopWatching()
    }

    /**
     * The 100 ms periodic pump. Returns when the service is stopping, the
     * device detaches, or [MAX_LOST_BEFORE_RECONNECT] consecutive reads fail
     * (console sleep / NAK → reconnect).
     */
    private fun runPump() {
        while (running && state == State.STREAMING && transport.connected) {
            val ok = client.tick()
            if (ok) {
                lostCount = 0
            } else {
                lostCount++
                Log.w(TAG, "read lost #$lostCount: ${client.lastError}")
                if (lostCount >= MAX_LOST_BEFORE_RECONNECT) {
                    Log.e(TAG, "$lostCount consecutive read failures — reconnecting")
                    transport.disconnect()
                    client.reset()
                    state = State.NO_DEVICE
                    syncFtms()
                    updateNotification()
                    return
                }
            }
            if (!client.ready && client.initialized) {
                // Lost the ready state (e.g. comms drop) → full reconnect.
                transport.disconnect()
                client.reset()
                state = State.NO_DEVICE
                syncFtms()
                updateNotification()
                return
            }
            updateNotification()
            sleep(100)
        }
    }

    /** Kick a parked NO_DEVICE waiter (no-op if the loop is active). */
    private fun wake() {
        // The main loop polls findDevice() each pass; nothing to signal.
    }

    /**
     * Reconcile the FTMS BLE half with the bridge state. Advertising is
     * always-on while streaming (a cadence-gated policy would leave a
     * non-pedaling bike dark). Both FtmsServer.start()/stop() are
     * idempotent, so this is safe to call on every state change.
     */
    private fun syncFtms() {
        if (state == State.STREAMING) {
            if (!ftms.advertising) ftms.start()
        } else {
            if (ftms.advertising) ftms.stop()
        }
        if (ftms.clientConnected) updateNotification()
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    // ------------------------------------------------------------------
    // Foreground notification
    // ------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Open FTMS Bridge", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val sub = when (state) {
            State.NO_DEVICE -> "waiting for brainboard"
            State.NO_PERMISSION -> "tap the system 'allow USB' dialog"
            State.CONNECTING -> "claiming USB…"
            State.HANDSHAKE -> "handshake in progress…"
            State.STREAMING -> streamingSubline()
            State.ERROR -> "error — retrying"
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Open FTMS Bridge")
            .setContentText(sub)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun streamingSubline(): String {
        val c = client
        val mode = (c.latest[12] as? Number)?.let { BitFields.modeName(it.toInt()) } ?: "?"
        val kph = (c.latest[0] as? Number)?.let { String.format("%.1f", it.toInt() / 100.0) } ?: "?"
        return if (c.ready) "running · $mode · ${kph} km/h · ${c.swVersion} sw" else "connected — waiting for data"
    }

    fun updateNotification() {
        if (running) startForeground(1, buildNotification())
    }

    // ------------------------------------------------------------------
    // Activity-facing helpers (main thread)
    // ------------------------------------------------------------------

    /** Returns a status snapshot string for the UI / debug socket. */
    fun statusSnapshot(): Map<String, Any> {
        val c = client
        return mapOf(
            "build" to BuildConfig.BUILD_STAMP,
            "state" to state.name,
            "connected" to transport.connected,
            "device" to (transport.current?.deviceName ?: "none"),
            "sw" to c.swVersion,
            "hw" to c.hwVersion,
            "serial" to c.serialNo,
            "model" to c.modelNumber,
            "part" to c.partNumber,
            "mlv" to c.masterLibVersion,
            "ready" to c.ready,
            "initialized" to c.initialized,
            "supportedFields" to c.supportedBitFields.size,
            "supportedCommands" to c.supportedCommands.toList(),
            "maxResistanceLevel" to c.maxResistanceLevel,
            "periodicFields" to c.periodicIds,
            "expectedPeriodicLen" to c.expectedPeriodicResponseLen,
            "lastStatus" to c.lastStatus,
            "lastStatusName" to (if (c.lastStatus >= 0) FitPro1.statusName(c.lastStatus) else "-"),
            "lastError" to c.lastError,
            "lostCount" to lostCount,
            "ftmsAdvertising" to ftms.advertising,
            "ftmsAvailable" to ftms.available,
            "ftmsClientConnected" to ftms.clientConnected,
            "ftmsGradeSupported" to c.gradeSupported,
            "ftmsMinGrade" to c.minGrade,
            "ftmsMaxGrade" to c.maxGrade
        )
    }

    fun requestPermissionFromUi() {
        post {
            val d = transport.findDevice()
            if (d == null) {
                toast("No brainboard attached")
                return@post
            }
            if (!transport.hasPermission(d)) {
                state = State.NO_PERMISSION
                transport.requestPermission(d)
                updateNotification()
            }
        }
    }

    /**
     * Write a full observation snapshot (status + engine log + raw hex) to the
     * app's external files dir. Triggered from the shell:
     *   adb shell am broadcast -a io.ftms.bridge.DUMP
     *   adb pull <getExternalFilesDir>/bridge_dump.txt
     * E-level so it survives this ROM's log filtering.
     */
    fun writeDump() {
        val dir = getExternalFilesDir(null) ?: return
        val f = java.io.File(dir, "bridge_dump.txt")
        try {
            val sb = StringBuilder()
            sb.append("== dump ").append(System.currentTimeMillis()).append(" ==\n")
            sb.append("-- status --\n")
            for ((k, v) in statusSnapshot()) sb.append(k).append("= ").append(v).append('\n')
            sb.append("\n-- engine log (tail 200) --\n").append(client.logRing.tail(200)).append('\n')
            sb.append("\n-- raw USB hex (tail 60) --\n").append(hex.tail(60)).append('\n')
            f.writeText(sb.toString())
            Log.e(TAG, "DUMP WRITTEN: " + f.path + " (" + f.length() + " bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "dump failed: ${e.message}")
        }
    }

    private fun toast(s: String) {
        post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    }
}
