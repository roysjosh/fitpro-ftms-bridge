package io.ftms.bridge

/**
 * FitPro1 bitfield registry + ReadWriteData section encoding.
 * The registry lists the console's fields (id, name, size, read/write);
 * the startup/periodic lists are the standard read sets, filtered at
 * runtime against the device's capability bitmaps.
 */
object BitFields {

    data class FieldDef(val id: Int, val name: String, val size: Int, val writable: Boolean)

    // ids 32/33/50/62/97/99/101-102/104-106/108-114/117-118 have no converter in the registry
    val registry: List<FieldDef> = listOf(
        FieldDef(0, "Kph", 2, true),
        FieldDef(1, "Grade", 2, true),
        FieldDef(2, "Resistance", 2, true),
        FieldDef(3, "Watts", 2, false),
        FieldDef(4, "CurrentDistance", 4, false),
        FieldDef(5, "Rpm", 2, false),
        FieldDef(6, "Distance", 4, false),
        FieldDef(7, "KeyObject", 14, false),
        FieldDef(8, "FanSpeed", 1, true),
        FieldDef(9, "Volume", 1, true),
        FieldDef(10, "Pulse", 4, true),
        FieldDef(11, "RunningTime", 4, false),
        FieldDef(12, "WorkoutMode", 1, true),
        FieldDef(13, "Calories", 4, false),
        FieldDef(14, "AudioSource", 3, true),
        FieldDef(15, "LapTime", 2, false),
        FieldDef(16, "ActualKph", 2, false),
        FieldDef(17, "ActualIncline", 2, false),
        FieldDef(18, "ActualResistance", 2, false),
        FieldDef(19, "ActualDistance", 4, false),
        FieldDef(20, "CurrentTime", 4, false),
        FieldDef(21, "CurrentCalories", 4, false),
        FieldDef(22, "GoalTime", 4, true),
        FieldDef(23, "IntervalKph", 4, false),
        FieldDef(24, "Age", 1, true),
        FieldDef(25, "Weight", 2, true),
        FieldDef(26, "Gear", 8, true),
        FieldDef(27, "MaxGrade", 2, false),
        FieldDef(28, "MinGrade", 2, false),
        FieldDef(29, "TransMax", 2, true),
        FieldDef(30, "MaxKph", 2, false),
        FieldDef(31, "MinKph", 2, false),
        FieldDef(34, "IdleTimeout", 2, true),
        FieldDef(35, "PauseTimeout", 2, true),
        FieldDef(36, "SystemUnits", 1, true),
        FieldDef(37, "Gender", 1, true),
        FieldDef(38, "FirstName", 45, true),
        FieldDef(39, "LastName", 45, true),
        FieldDef(40, "UserName", 45, true),
        FieldDef(41, "Height", 2, true),
        FieldDef(42, "MaxResistanceLevel", 1, false),
        FieldDef(43, "MaxWeight", 2, false),
        FieldDef(44, "WarmupDistance", 4, true),
        FieldDef(45, "WarmupTime", 2, true),
        FieldDef(46, "WarmupTimeout", 2, true),
        FieldDef(47, "WarmupCalories", 4, true),
        FieldDef(48, "IntervalGrade", 2, false),
        FieldDef(49, "MaxPulse", 1, false),
        FieldDef(51, "WtMaxKph", 2, true),
        FieldDef(52, "AverageGrade", 2, false),
        FieldDef(53, "WtMaxGrade", 2, true),
        FieldDef(54, "AverageWatts", 2, false),
        FieldDef(55, "MaxWatts", 2, false),
        FieldDef(56, "AverageRpm", 2, false),
        FieldDef(57, "MaxRpm", 2, false),
        FieldDef(58, "KphGoal", 2, true),
        FieldDef(59, "GradeGoal", 2, true),
        FieldDef(60, "ResistanceGoal", 2, true),
        FieldDef(61, "WattGoal", 2, true),
        FieldDef(63, "RpmGoal", 2, true),
        FieldDef(64, "DistanceGoal", 4, true),
        FieldDef(65, "PulseGoal", 1, true),
        FieldDef(66, "StartUpTime", 4, false),
        FieldDef(67, "BeltTotalTime", 4, false),
        FieldDef(68, "BeltTotalMeters", 4, false),
        FieldDef(69, "MotorTotalDistance", 4, false),
        FieldDef(70, "TotalTime", 4, false),
        FieldDef(71, "CoolDownTimeout", 2, true),
        FieldDef(72, "CoolDownTime", 2, false),
        FieldDef(73, "CoolDownDistance", 4, false),
        FieldDef(74, "CoolDownCalories", 4, false),
        FieldDef(75, "VerticalMeterNet", 4, false),
        FieldDef(76, "VerticalMeterGain", 4, false),
        FieldDef(77, "Reps", 2, false),
        FieldDef(78, "LeftReps", 2, false),
        FieldDef(79, "RightReps", 2, false),
        FieldDef(80, "RepLength", 2, true),
        FieldDef(81, "RepLeftLength", 2, true),
        FieldDef(82, "RepRightLength", 2, true),
        FieldDef(83, "BurnRate", 2, true),
        FieldDef(84, "AvgBurnRate", 2, false),
        FieldDef(85, "MaxBurnRate", 2, false),
        FieldDef(86, "IntervalRpm", 4, false),
        FieldDef(87, "IntervalResistance", 4, false),
        FieldDef(94, "GoalCalories", 4, true),
        FieldDef(95, "IdleModeLockout", 1, true),
        FieldDef(96, "StartRequested", 1, true),
        FieldDef(98, "FanState", 1, true),
        FieldDef(100, "ActivationLock", 1, true),
        FieldDef(103, "PausedTime", 4, false),
        FieldDef(107, "SleepTimerState", 1, false),
        FieldDef(108, "RequireStartRequested", 1, false),
        FieldDef(109, "Strokes", 2, true),
        FieldDef(110, "StrokesPerMin", 1, true),
        FieldDef(111, "FiveHundredSplit", 2, false),
        FieldDef(112, "AvgFiveHundredSplit", 2, false),
        FieldDef(115, "IsClubUnit", 1, false),
        FieldDef(116, "IsReadyToDisconnect", 1, false),
        FieldDef(119, "IsConstantWattsMode", 1, true)
    )

    val byId: Map<Int, FieldDef> = registry.associateBy { it.id }

    fun sizeOf(id: Int): Int = byId[id]?.size ?: throw IllegalArgumentException("unknown bitfield $id")
    fun nameOf(id: Int): String = byId[id]?.name ?: "field$id"

    /** Fields read once, at the start of a session. */
    val startupIds = listOf(
        36, // SystemUnits
        30, // MaxKph
        31, // MinKph
        27, // MaxGrade
        28, // MinGrade
        43, // MaxWeight
        34, // IdleTimeout
        35, // PauseTimeout
        71, // CoolDownTimeout
        46, // WarmupTimeout
        42, // MaxResistanceLevel
        26, // Gear
        12, // WorkoutMode
        100, // ActivationLock
        115, // IsClubUnit
        69, // MotorTotalDistance
        70 // TotalTime
    )

    /** Fields re-read every 100 ms, filtered by capability bitmaps at runtime. */
    val periodicIds = listOf(
        12, // WorkoutMode
        1, // Grade
        20, // CurrentTime
        4, // CurrentDistance
        21, // CurrentCalories
        2, // Resistance
        26, // Gear
        5, // Rpm
        15, // LapTime
        52, // AverageGrade
        3, // Watts
        54, // AverageWatts
        75, // VerticalMeterNet
        76, // VerticalMeterGain
        10, // Pulse
        46, // WarmupTimeout
        71, // CoolDownTimeout
        0, // Kph
        16, // ActualKph
        96, // StartRequested
        98, // FanState
        9, // Volume
        103, // PausedTime
        11, // RunningTime
        109, // Strokes
        110, // StrokesPerMin
        111, // FiveHundredSplit
        112, // AvgFiveHundredSplit
        22, // GoalTime
        7, // KeyObject
        116 // IsReadyToDisconnect
    )

    /**
     * ReadWriteData content for a read-only round: empty write section
     * (single 0x00 byte) + read section [count][count × bitmap].
     */
    fun readContent(readIds: List<Int>): ByteArray {
        val content = java.util.ArrayList<Byte>()
        content.add(0.toByte()) // wCount = 0 → single 0x00 section byte
        content.addAll(sectionHeader(readIds))
        return content.toByteArray()
    }

    /** [count][count × section bitmap]; empty → single 0x00 byte. */
    fun sectionHeader(ids: List<Int>): List<Byte> {
        if (ids.isEmpty()) return listOf(0.toByte())
        val count = (ids.max() / 8) + 1
        val out = ArrayList<Byte>(1 + count)
        out.add(count.toByte())
        repeat(count) { out.add(0.toByte()) }
        for (id in ids) {
            out[1 + id / 8] = (out[1 + id / 8].toInt() or (1 shl (id % 8))).toByte()
        }
        return out
    }

    /**
     * ReadWriteData content for a write round: [wCount][wCount × bitmap]
     * [values in ascending bitfield-id order], followed by a single 0x00 byte
     * for the (empty) read section. Each value is the raw wire integer, written
     * little-endian at its field width.
     */
    fun writeContent(writeIds: List<Int>, values: Map<Int, Int>): ByteArray {
        val content = ArrayList<Byte>()
        val sorted = writeIds.sorted()
        if (sorted.isEmpty()) {
            content.add(0.toByte()); content.add(0.toByte())
            return content.toByteArray()
        }
        val count = (sorted.max() / 8) + 1
        content.add(count.toByte())
        repeat(count) { content.add(0.toByte()) }
        for (id in sorted) {
            content[1 + id / 8] = (content[1 + id / 8].toInt() or (1 shl (id % 8))).toByte()
        }
        for (id in sorted) {
            val size = byId[id]?.size ?: continue
            val v = values[id] ?: continue
            repeat(size) { i -> content.add(((v ushr (8 * i)) and 0xFF).toByte()) }
        }
        content.add(0.toByte()) // trailing empty read section
        return content.toByteArray()
    }

    /**
     * Decode a ReadWriteData response payload (frame[4..]) for the given read
     * list — values arrive in ascending bitfield-id order.
     * Returns id → raw value (Int for 1/2/4-byte widths; hex string for wide).
     */
    fun decode(readIds: List<Int>, payload: ByteArray): Map<Int, Any> {
        val sorted = readIds.sorted()
        val out = HashMap<Int, Any>(sorted.size)
        var off = 0
        for (id in sorted) {
            val size = byId[id]?.size ?: continue
            if (off + size > payload.size) break
            when (size) {
                1 -> out[id] = payload[off].toInt() and 0xFF
                2 -> out[id] = FitPro1.u16(payload, off)
                4 -> out[id] = FitPro1.s32(payload, off).toLong()
                else -> out[id] = payload.copyOfRange(off, off + size).toHexString()
            }
            off += size
        }
        return out
    }

    /** Human-readable one-liners for the fields we actually display. */
    fun format(id: Int, v: Any, maxResistanceLevel: Int): String? {
        val n = v as? Number ?: return v.toString()
        return when (id) {
            12 -> "mode=" + modeName(n.toInt())
            0 -> "%.2f km/h".format(n.toInt() / 100.0)
            16 -> "%.2f km/h (act)".format(n.toInt() / 100.0)
            5 -> "${n} rpm"
            3 -> "${n} W"
            1 -> "%+.1f%%".format(n.toDouble() / 100.0)
            2 -> lvlString(n.toInt(), maxResistanceLevel)
            4 -> "${n} m"
            21 -> "%.1f kcal".format(n.toDouble() * 1024.0 / 1e8)
            13 -> "%.1f kcal".format(n.toDouble() * 1024.0 / 1e8)
            11 -> fmtTime(n.toLong())
            20 -> fmtTime(n.toLong())
            22 -> fmtTime(n.toLong())
            10 -> "HR ${n} bpm"
            96 -> "startReq=" + n
            98 -> "fan=" + n
            116 -> "readyDisc=" + n
            7 -> {
                val s = v as? String ?: return null
                val code = if (s.length >= 4) s.substring(0, 4) else s
                "key=$code"
            }
            else -> null
        }
    }

    private fun lvlString(raw: Int, maxLevel: Int): String =
        if (maxLevel in 1..100) "${raw} (lvl ${(raw * 100.0 / (10000.0 / maxLevel)).toString().toFloat() / 1.0}/100)"
        else "$raw"

    private fun fmtTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun modeName(v: Int): String = when (v) {
        0 -> "Unknown"
        1 -> "Idle"
        2 -> "Running"
        3 -> "Pause"
        4 -> "Results"
        5 -> "Debug"
        6 -> "Log"
        7 -> "Maintenance"
        8 -> "Dmk (safety key out)"
        9 -> "Demo"
        10 -> "WarmUp"
        11 -> "CoolDown"
        12 -> "Sleep"
        13 -> "Resume"
        14 -> "Locked"
        20 -> "PauseOverride"
        else -> "Mode($v)"
    }
}

private fun ByteArray.toHexString(): String =
    joinToString("") { b -> String.format("%02X", b.toInt() and 0xFF) }
