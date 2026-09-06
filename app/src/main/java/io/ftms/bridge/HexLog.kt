package io.ftms.bridge

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.LinkedList

/**
 * Ring buffer of raw USB transfers — the byte-level observation store: every
 * request/reply frame exactly as sent on the wire, for checking the protocol
 * model against the device. Served by the debug socket (`hex [n]`) and tailed
 * in the UI.
 */
class HexLog(private val capacity: Int = 400) {

    class Entry(val direction: String, val label: String, val raw: ByteArray, val timeMs: Long) {
        val text: String by lazy {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timeMs))
            val hex = raw.joinToString(" ") { b -> String.format(Locale.US, "%02X", b.toInt() and 0xFF) }
            "$ts $direction ${if (label.isNotEmpty()) label + " " else ""}(${raw.size}b) $hex"
        }
    }

    private val entries = LinkedList<Entry>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun record(direction: String, label: String, raw: ByteArray) {
        entries.addLast(Entry(direction, label, raw.copyOf(), System.currentTimeMillis()))
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun tail(n: Int): String {
        val out = StringBuilder()
        val list = entries.toList()
        val from = (list.size - n).coerceAtLeast(0)
        for (e in list.subList(from, list.size)) {
            out.append(e.text).append('\n')
        }
        return out.toString()
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
