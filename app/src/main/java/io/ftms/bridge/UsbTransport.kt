package io.ftms.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.app.PendingIntent
import java.io.IOException

/**
 * Android USB-host transport for the brainboard (213c:0002 "ICON Generic
 * HID"). The console's USB persona:
 *  - claim interface 0 with force=true
 *  - read endpoint = IN, write endpoint = OUT
 *  - writes: the raw frame, one bulk OUT, 50 ms
 *  - reads: one 64-byte bulk IN, retried up to 5x while byte 0 == 0xFF
 *    (0xFF-prefilled buffers are the device's keep-alive / "no data")
 */
class UsbTransport(private val context: Context) {

    interface Listener {
        fun onDeviceAttached(device: UsbDevice)
        fun onDeviceDetached(device: UsbDevice?)
        fun onPermissionResult(device: UsbDevice?, granted: Boolean)
    }

    companion object {
        const val TAG = "UsbTransport"
        const val VENDOR_ID = 0x213c
        const val PRODUCT_ID = 0x0002
        const val TRANSFER_TIMEOUT_MS = 50
        // One USB transfer pulls one 64-byte packet (the endpoint is a
        // 64-byte HID interrupt). A frame's declared length may exceed 64
        // (the 1 Hz periodic read declares 90) but the device sends only the
        // first 64 bytes; [read] returns that single packet and the protocol
        // layer tolerates the over-length declaration.
        const val READ_BUFFER_SIZE = 64
        const val MAX_READ_RETRIES = 5
        // App-defined action for the requestPermission dialog result
        // (developer.android.com USB host guide pattern; not a framework constant).
        const val USB_PERMISSION_ACTION = "io.ftms.bridge.USB_PERMISSION"
    }

    var listener: Listener? = null
    @Volatile var current: UsbDevice? = null
    @Volatile var connected: Boolean = false
        private set
    @Volatile var permissionGranted: Boolean = false

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var readEndpoint: UsbEndpoint? = null
    private var writeEndpoint: UsbEndpoint? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val d = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (d != null && isOurs(d)) {
                        current = d
                        listener?.onDeviceAttached(d)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (d == null || isOurs(d)) {
                        if (connected) listener?.onDeviceDetached(d)
                        current = null
                    }
                }
                USB_PERMISSION_ACTION -> {
                    val d = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    permissionGranted = ok
                    listener?.onPermissionResult(d, ok)
                }
            }
        }
    }

    fun isOurs(d: UsbDevice): Boolean = d.vendorId == VENDOR_ID && d.productId == PRODUCT_ID

    fun findDevice(): UsbDevice? {
        current?.let { if (isOurs(it)) return it }
        for (d in usbManager.deviceList.values) {
            if (isOurs(d)) return d
        }
        return null
    }

    fun hasPermission(d: UsbDevice): Boolean = usbManager.hasPermission(d)

    /** One-time system "allow" dialog; result arrives via the receiver. */
    fun requestPermission(d: UsbDevice) {
        permissionGranted = false
        val pi = PendingIntent.getBroadcast(
            context, 0,
            Intent(USB_PERMISSION_ACTION).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(d, pi)
    }

    fun startWatching() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(USB_PERMISSION_ACTION)
        }
        try {
            context.registerReceiver(receiver, filter)
        } catch (_: Exception) {
            // already registered
        }
    }

    fun stopWatching() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun connect(device: UsbDevice): Boolean {
        if (connected) return true
        val conn = usbManager.openDevice(device) ?: return false
        val ifc = device.getInterface(0) ?: run { conn.close(); return false }
        if (!conn.claimInterface(ifc, true)) {
            conn.close()
            return false
        }
        var re: UsbEndpoint? = null
        var we: UsbEndpoint? = null
        for (i in 0 until ifc.endpointCount) {
            val e = ifc.getEndpoint(i) ?: continue
            // The stock app selects by position, not type (endpoint 0 =
            // read/IN, endpoint 1 = write/OUT) and drives both with bulk
            // transfers even though the descriptors declare them type 3 (HID
            // interrupt). We select on direction only — equivalent on this
            // device, robust to endpoint reordering.
            if ((e.direction.toInt() and UsbConstants.USB_ENDPOINT_DIR_MASK) != 0) re = e else we = e
        }
        if (re == null || we == null) {
            runCatching { conn.releaseInterface(ifc) }
            conn.close()
            return false
        }
        connection = conn
        usbInterface = ifc
        readEndpoint = re
        writeEndpoint = we
        current = device
        connected = true
        // E-level (this ROM drops D/I from logcat): the max packet size decides
        // whether a >64-byte frame arrives as one packet (not reassemblable) or
        // several 64-byte packets (reassemblable in read()).
        android.util.Log.e(TAG, "USB endpoints: IN(0x${Integer.toHexString(re?.address ?: 0)}) maxPacket=${re?.maxPacketSize} " +
                "OUT(0x${Integer.toHexString(we?.address ?: 0)}) maxPacket=${we?.maxPacketSize}")
        return true
    }

    @Synchronized
    fun disconnect() {
        connected = false
        readEndpoint = null
        writeEndpoint = null
        runCatching { usbInterface?.let { connection?.releaseInterface(it) } }
        runCatching { connection?.close() }
        connection = null
        usbInterface = null
    }

    /** One bulk OUT of the whole frame. */
    fun write(bytes: ByteArray): Int {
        val ep = writeEndpoint ?: return -1
        val conn = connection ?: return -1
        return try {
            conn.bulkTransfer(ep, bytes, bytes.size, TRANSFER_TIMEOUT_MS)
        } catch (_: IOException) {
            -1
        }
    }

    /**
     * Read one FitPro1 frame = a single 64-byte USB interrupt packet.
     * Each bulk IN moves at most one 64-byte packet (the endpoint is a
     * 64-byte HID interrupt; a larger single transfer times out). The first
     * real (non-keep-alive) packet is returned; keep-alives (byte 0 == 0xFF)
     * are retried up to [MAX_READ_RETRIES]. Null = transport-level failure.
     *
     * A frame is self-delimiting (`[1]` = total length) but the brainboard
     * delivers the 90-byte periodic read as ONE 64-byte packet and never
     * sends the rest (a follow-up read times out). The protocol layer
     * tolerates the over-length declaration and decodes the fields that fit —
     * see [FitPro1.unwrapFrame] / [isValidResponse].
     */
    fun read(): ByteArray? {
        val ep = readEndpoint ?: return null
        val conn = connection ?: return null

        // 1) Grab the first 64-byte packet of a real (non-keep-alive) frame.
        //    The endpoint is a 64-byte HID interrupt, so each bulkTransfer
        //    moves at most one packet; a frame larger than 64 bytes arrives
        //    as several of these and is reassembled in step 2.
        var first: ByteArray? = null
        var lastKeepAlive: ByteArray? = null
        for (attempt in 1..MAX_READ_RETRIES) {
            val chunk = ByteArray(READ_BUFFER_SIZE)
            val result = try {
                conn.bulkTransfer(ep, chunk, READ_BUFFER_SIZE, TRANSFER_TIMEOUT_MS)
            } catch (_: IOException) {
                -1
            }
            if (result < 0) continue
            if (chunk[0].toInt() and 0xFF == 0xFF) {
                lastKeepAlive = chunk.copyOf(result)
                if (attempt == MAX_READ_RETRIES) return lastKeepAlive
                continue
            }
            first = chunk.copyOf(result)
            break
        }
        if (first == null) return null
        return first
    }
}
