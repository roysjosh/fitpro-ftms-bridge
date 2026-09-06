package io.ftms.bridge

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local debug server — the runtime observation tool.
 *
 *   adb forward tcp:18765 tcp:18765                    # stock Android (TCP)
 *   adb forward tcp:18765 localabstract:ftms-debug   # ROMs that deny TCP bind
 *   nc localhost 18765
 *
 * Commands (one per line):
 *   help            list commands
 *   status          bridge state + handshake identity (JSON)
 *   fields          latest decoded bitfields (JSON)
 *   hex [n]         raw USB transfers, most recent n (default 50)
 *   log [n]         engine log tail (default 50)
 *   watch           stream status + fields every second until the client leaves
 *   clear           clear the hex ring
 */
class DebugSocket(private val service: BridgeService, private val client: FitPro1Client, private val hex: HexLog) {

    companion object {
        private const val TAG = "DebugSocket"
        const val ABSTRACT_NAME = "ftms-debug"
    }

    private var tcpServer: ServerSocket? = null
    private var unixServer: LocalServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var running = false

    fun start(port: Int = BridgeService.DEBUG_PORT) {
        if (tcpServer != null || unixServer != null) return
        running = true
        // Stock Android: plain TCP. Some ROMs (this tablet's MTK build) deny
        // untrusted apps EVERY TCP bind (EACCES on 127.0.0.1 and 0.0.0.0) —
        // fall back to the abstract-namespace Unix socket, which adb can
        // bridge:  adb forward tcp:PORT localabstract:ftms-debug
        val tcp: ServerSocket? = try {
            val s = ServerSocket()
            s.bind(InetSocketAddress(port), 0)
            tcpServer = s
            s
        } catch (_: Exception) {
            null
        }
        val unix: LocalServerSocket? = if (tcp == null) try {
            val ls = LocalServerSocket(ABSTRACT_NAME)
            unixServer = ls
            ls
        } catch (_: Exception) {
            null
        } else null

        if (tcp == null && unix == null) {
            running = false
            Log.w(TAG, "debug socket not started: TCP bind denied and Unix abstract socket also failed")
            return
        }
        acceptThread = Thread({
            while (running) {
                val inStream: InputStream
                val outStream: OutputStream
                val alive: () -> Boolean
                val close: () -> Unit
                try {
                    if (tcp != null) {
                        val sock = tcp!!.accept()
                        inStream = sock.getInputStream()
                        outStream = sock.getOutputStream()
                        alive = { !sock.isClosed }
                        close = { runCatching { sock.close() } }
                    } else {
                        val ls = unix!!.accept()
                        inStream = ls.getInputStream()
                        outStream = ls.getOutputStream()
                        alive = { !ls.isClosed }
                        close = { runCatching { ls.close() } }
                    }
                } catch (_: Exception) {
                    break
                }
                Thread({ handleConn(inStream, outStream, alive, close) }, "debug-client").start()
            }
        }, "debug-accept").also { it.start() }
        Log.i(TAG, if (tcp != null) "debug socket listening on TCP :$port" else "debug socket listening on localabstract:$ABSTRACT_NAME")
    }

    fun stop() {
        running = false
        runCatching { tcpServer?.close() }
        runCatching { unixServer?.close() }
        tcpServer = null
        unixServer = null
        acceptThread = null
    }

    private fun handleConn(
        inStream: InputStream,
        outStream: OutputStream,
        alive: () -> Boolean,
        close: () -> Unit
    ) {
        try {
            val reader = BufferedReader(InputStreamReader(inStream))
            val out = PrintWriter(outStream, true)
            out.println("Open FTMS Bridge debug console. Type 'help'.")
            // This tablet's adbd does not forward client->device data, so the
            // interactive commands may never arrive. Push a full snapshot on
            // connect so observation works over a one-way bridge:
            //   connect -> receive dump -> disconnect
            out.println("== auto-dump ==")
            out.println("-- status --")
            out.println(statusJson())
            out.println("-- fields --")
            out.println(fieldsJson())
            out.println("-- engine log (tail 100) --")
            out.println(client.logRing.tail(100))
            out.println("-- raw hex (tail 40) --")
            out.println(hex.tail(40))
            out.println("== end auto-dump ==")
            while (alive()) {
                val line = reader.readLine() ?: break
                val parts = line.trim().split(" ")
                when (parts[0]) {
                    "help" -> out.println(
                        "status [json] | fields [json] | hex [n] | log [n] | watch | clear | quit")
                    "status" -> out.println(statusJson())
                    "fields" -> out.println(fieldsJson())
                    "hex" -> out.println(hex.tail(parts.getOrNull(1)?.toIntOrNull() ?: 50))
                    "log" -> out.println(client.logRing.tail(parts.getOrNull(1)?.toIntOrNull() ?: 50))
                    "watch" -> {
                        while (alive()) {
                            out.println(statusJson())
                            out.println(fieldsJson())
                            out.println("----")
                            out.flush()
                            Thread.sleep(1000)
                        }
                    }
                    "clear" -> { hex.clear(); out.println("ok") }
                    "quit", "exit" -> break
                    "" -> continue
                    else -> out.println("unknown command: ${parts[0]} (try 'help')")
                }
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    private fun statusJson(): String {
        val s = service.statusSnapshot()
        val o = JSONObject()
        for ((k, v) in s) o.put(k, v)
        return o.toString(2)
    }

    private fun fieldsJson(): String {
        val latest = client.latest
        val arr = JSONArray()
        for ((id, raw) in latest.toSortedMap()) {
            val o = JSONObject()
            o.put("id", id)
            o.put("name", BitFields.nameOf(id))
            o.put("raw", raw)
            val f = BitFields.format(id, raw, client.maxResistanceLevel)
            if (f != null) o.put("value", f)
            arr.put(o)
        }
        return arr.toString(2)
    }
}
