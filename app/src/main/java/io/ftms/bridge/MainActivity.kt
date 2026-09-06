package io.ftms.bridge

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A full-screen colour scheme. [LIGHT] mirrors the original look; [DARK] is a
 * high-contrast night scheme — every text element keeps ≥ 4.5:1 against its own
 * background (WCAG AA). Fields are the exact call sites the theme drives.
 */
private data class Palette(
    val window: Int,      // activity / pane background
    val card: Int,        // stat-tile card background
    val heading: Int,     // page title text
    val value: Int,       // large tile value text
    val muted: Int,       // small labels: tile title/units, hex label
    val statusOk: Int,    // "STREAMING" state colour
    val statusErr: Int,   // "ERROR" state colour
    val statusInfo: Int,  // connecting / starting / stopping state colour
    val identity: Int,    // handshake identity line (debug pane)
    val telemetry: Int,   // raw field list line (debug pane)
    val hex: Int,         // scrolling hex dump (debug pane)
    val btnBg: Int,       // action-button chip
    val btnText: Int,     // action-button text (also tints the theme glyph)
    val toggleBg: Int,    // the day/night toggle chip
)

private val LIGHT = Palette(
    window = Color.rgb(0xFF, 0xFF, 0xFF), card = Color.rgb(0xF2, 0xF2, 0xF2),
    heading = Color.rgb(0x1A, 0x1A, 0x1A), value = Color.rgb(0x18, 0x18, 0x18),
    muted = Color.rgb(0x80, 0x80, 0x80), statusOk = Color.rgb(0x00, 0xA0, 0x00),
    statusErr = Color.RED, statusInfo = Color.rgb(0x00, 0x5A, 0xC8),
    identity = Color.rgb(0x55, 0x55, 0x55), telemetry = Color.rgb(0x33, 0x33, 0x33),
    hex = Color.rgb(0x22, 0x22, 0x22), btnBg = Color.rgb(0xE0, 0xE0, 0xE0),
    btnText = Color.rgb(0x1A, 0x1A, 0x1A), toggleBg = Color.rgb(0xE0, 0xE0, 0xE0),
)

private val DARK = Palette(
    window = Color.rgb(0x12, 0x12, 0x12), card = Color.rgb(0x2A, 0x2A, 0x2A),
    heading = Color.rgb(0xEC, 0xEC, 0xEC), value = Color.WHITE,
    muted = Color.rgb(0x9E, 0x9E, 0x9E), statusOk = Color.rgb(0x69, 0xF0, 0xAE),
    statusErr = Color.rgb(0xFF, 0x6B, 0x6B), statusInfo = Color.rgb(0x64, 0xB5, 0xF6),
    identity = Color.rgb(0xB0, 0xB0, 0xB0), telemetry = Color.rgb(0xC8, 0xC8, 0xC8),
    hex = Color.rgb(0xD0, 0xD0, 0xD0), btnBg = Color.rgb(0x2E, 0x2E, 0x2E),
    btnText = Color.rgb(0xE8, 0xE8, 0xE8), toggleBg = Color.rgb(0x3A, 0x3A, 0x3A),
)

/**
 * Status screen. Main page: a live 2×4 stat grid plus Start/Stop, Restart and
 * a Debug button. The raw debug output (handshake identity, the raw field list,
 * the scrolling USB hex tail) lives on a separate pane reached from the Debug
 * button and returned from via its Back button.
 *
 * Each pane's header row carries the title (left), the live status (centred, on
 * the main page) and a day/night toggle (right). The toggle is a single global
 * setting (persisted in prefs): the chosen scheme is applied to every view in
 * every pane, so entering Debug keeps the current scheme — and its own header
 * toggle flips it back.
 *
 * Tapping the Speed tile toggles km/h ⇄ mph; tapping the Distance tile toggles
 * km ⇄ mi (each is an independent preference).
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    // The currently applied scheme (flipped by the theme toggle; read by
    // refresh()/toggleWorkout() so their state colours follow the theme).
    private var active: Palette = LIGHT
    private var darkMode = false

    // Root + main page
    private lateinit var container: FrameLayout
    private lateinit var mainRoot: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var stateView: TextView
    private lateinit var workoutBtn: Button
    private lateinit var restartBtn: Button
    private lateinit var debugBtn: Button
    private val themeToggles = ArrayList<Button>() // one per pane, all drive [darkMode]
    private val tileBoxes = ArrayList<LinearLayout>()

    // Stat tiles — the live value view per tile (unit views kept only where
    // they change: Speed and Distance).
    private lateinit var timeValue: TextView
    private lateinit var rpmValue: TextView
    private lateinit var wattsValue: TextView
    private lateinit var speedValue: TextView
    private lateinit var inclineValue: TextView
    private lateinit var resValue: TextView
    private lateinit var kcalValue: TextView
    private lateinit var distValue: TextView
    private lateinit var speedUnits: TextView
    private lateinit var distUnits: TextView
    private lateinit var speedTile: LinearLayout
    private lateinit var distTile: LinearLayout

    // Debug pane
    private lateinit var debugRoot: LinearLayout
    private lateinit var debugTitleView: TextView
    private lateinit var identityView: TextView
    private lateinit var telemetryView: TextView
    private lateinit var hexLabel: TextView
    private lateinit var hexView: TextView
    private lateinit var backBtn: Button

    // Unit-system toggles.
    private var metricSpeed = true
    private var metricDistance = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = FrameLayout(this)

        // --- Main page ------------------------------------------------------
        mainRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        // Header row: title on the left, the live status centred, the
        // day/night toggle on the right. A FrameLayout keeps the status pinned
        // to the exact middle regardless of how long the title or status are.
        val headerRow = FrameLayout(this)
        titleView = TextView(this)
        titleView.text = "Open FTMS Bridge"
        titleView.setTextSize(20f)
        titleView.setTypeface(Typeface.DEFAULT_BOLD)
        headerRow.addView(titleView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.CENTER_VERTICAL))

        stateView = TextView(this)
        stateView.gravity = Gravity.CENTER
        headerRow.addView(stateView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))

        addThemeToggle(headerRow)
        mainRoot.addView(headerRow, gap(12))

        // 2 rows × 4 columns; the grid (weight 1) fills the space between the
        // status line and the button row, and the two rows share it equally.
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        val gridParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        gridParams.bottomMargin = 8
        grid.layoutParams = gridParams
        val row1 = hRow()
        val row2 = hRow()
        grid.addView(row1)
        grid.addView(row2)
        mainRoot.addView(grid, gridParams)

        timeValue = tile(row1, "Time").value
        rpmValue = tile(row1, "RPM").value
        wattsValue = tile(row1, "Watts").value
        val tSpeed = tile(row1, "Speed"); speedValue = tSpeed.value; speedUnits = tSpeed.units; speedTile = tSpeed.box
        inclineValue = tile(row2, "Incline", "%").value
        resValue = tile(row2, "Resistance", "level").value
        kcalValue = tile(row2, "Kcal").value
        val tDist = tile(row2, "Distance"); distValue = tDist.value; distUnits = tDist.units; distTile = tDist.box

        speedTile.setOnClickListener {
            metricSpeed = !metricSpeed
            Log.i("EoS-UI", "speed unit -> " + (if (metricSpeed) "km/h" else "mph"))
            BridgeService.instance?.let { updateSpeed(it.client) }
        }
        distTile.setOnClickListener {
            metricDistance = !metricDistance
            Log.i("EoS-UI", "distance unit -> " + (if (metricDistance) "km" else "mi"))
            BridgeService.instance?.let { updateDistance(it.client) }
        }

        // Button row: Start/Stop Workout | Restart | Debug
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        workoutBtn = Button(this).apply { text = "Start Workout" }
        workoutBtn.setOnClickListener { toggleWorkout() }
        restartBtn = Button(this).apply { text = "Restart" }
        restartBtn.setOnClickListener { restartBridge() }
        debugBtn = Button(this).apply { text = "Debug" }
        debugBtn.setOnClickListener { showDebug() }
        row.addView(workoutBtn, btnParams(8))
        row.addView(restartBtn, btnParams(8))
        row.addView(debugBtn, btnParams(0))
        mainRoot.addView(row)

        // --- Debug pane (hidden until the Debug button is tapped) ----------
        debugRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            visibility = View.GONE
        }

        // Header row: "Debug" on the left, the same global toggle on the right.
        val debugHeaderRow = FrameLayout(this)
        debugTitleView = TextView(this)
        debugTitleView.text = "Debug"
        debugTitleView.setTextSize(20f)
        debugTitleView.setTypeface(Typeface.DEFAULT_BOLD)
        debugHeaderRow.addView(debugTitleView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.CENTER_VERTICAL))
        addThemeToggle(debugHeaderRow)
        debugRoot.addView(debugHeaderRow, gap(8))

        identityView = TextView(this).also {
            it.textSize = 12f
            it.setPadding(0, 8, 0, 0)
            debugRoot.addView(it)
        }
        telemetryView = TextView(this).also {
            it.textSize = 13f
            it.setPadding(0, 8, 0, 0)
            debugRoot.addView(it)
        }

        hexLabel = TextView(this)
        hexLabel.text = "Raw USB transfers (hex)"
        hexLabel.setTextSize(12f)
        hexLabel.setPadding(0, 12, 0, 4)
        debugRoot.addView(hexLabel)

        val hexScroll = ScrollView(this)
        hexScroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        hexView = TextView(this)
        hexView.textSize = 11f
        hexView.setTypeface(Typeface.MONOSPACE)
        hexScroll.addView(hexView)
        debugRoot.addView(hexScroll)

        backBtn = Button(this).apply { text = "Back" }
        backBtn.setOnClickListener { showMain() }
        debugRoot.addView(backBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8 })

        container.addView(mainRoot, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.addView(debugRoot, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(container)

        // Restore the saved scheme and apply it before the first refresh.
        darkMode = prefs.getBoolean("dark", false)
        applyTheme()

        // The scaffold already starts the service; ensure it (idempotent).
        val svc = BridgeService.instance
        if (svc == null) {
            startForegroundService(Intent(this, BridgeService::class.java))
        }
        ui.post { refresh() }
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacksAndMessages(null)
        ui.post { refresh() }
        ui.postDelayed(object : Runnable {
            override fun run() {
                if (!isFinishing) {
                    refresh()
                    ui.postDelayed(this, 500)
                }
            }
        }, 500)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacksAndMessages(null)
    }

    private fun showDebug() {
        Log.i("EoS-UI", "debug pane shown")
        mainRoot.visibility = View.GONE
        debugRoot.visibility = View.VISIBLE
        ui.post { refresh() }
    }

    private fun showMain() {
        Log.i("EoS-UI", "main page shown")
        debugRoot.visibility = View.GONE
        mainRoot.visibility = View.VISIBLE
    }

    private fun restartBridge() {
        stopService(Intent(this, BridgeService::class.java))
        ui.postDelayed({ startForegroundService(Intent(this, BridgeService::class.java)) }, 500)
    }

    /** Recolour every view in every pane according to the active scheme. */
    private fun applyTheme() {
        val p = if (darkMode) DARK else LIGHT
        active = p
        container.setBackgroundColor(p.window)
        mainRoot.setBackgroundColor(p.window)
        debugRoot.setBackgroundColor(p.window)
        titleView.setTextColor(p.heading)
        debugTitleView.setTextColor(p.heading)
        for (box in tileBoxes) {
            box.background = chip(p.card, 16f)
            (box.getChildAt(0) as TextView).setTextColor(p.muted) // title
            (box.getChildAt(1) as TextView).setTextColor(p.value) // value
            (box.getChildAt(2) as TextView).setTextColor(p.muted) // unit
        }
        identityView.setTextColor(p.identity)
        telemetryView.setTextColor(p.telemetry)
        hexLabel.setTextColor(p.muted)
        hexView.setTextColor(p.hex)
        for (b in arrayOf(workoutBtn, restartBtn, debugBtn, backBtn)) {
            b.background = chip(p.btnBg, 12f)
            b.setTextColor(p.btnText)
        }
        for (b in themeToggles) {
            b.background = chip(p.toggleBg, 12f)
            b.setTextColor(p.btnText)
            b.contentDescription =
                if (darkMode) "Switch to light mode" else "Switch to dark mode"
        }
    }

    /** Build a ◐ (half-sun) button at the right of a pane's header row. All
     *  instances share [darkMode] (and prefs), so every pane stays in sync. */
    private fun addThemeToggle(header: FrameLayout) {
        val toggle = Button(this)
        toggle.setPadding(0, 0, 0, 0)
        toggle.gravity = Gravity.CENTER
        toggle.text = "\u25D0" // ◐ half-sun / contrast
        toggle.setTextSize(20f)
        toggle.setOnClickListener { toggleTheme() }
        header.addView(toggle, FrameLayout.LayoutParams(
            48, 48, Gravity.END or Gravity.CENTER_VERTICAL))
        themeToggles.add(toggle)
    }

    private fun toggleTheme() {
        darkMode = !darkMode
        prefs.edit().putBoolean("dark", darkMode).apply()
        Log.i("EoS-UI", "theme -> " + (if (darkMode) "dark" else "light"))
        applyTheme()
    }

    private fun refresh() {
        val svc = BridgeService.instance ?: return
        val c = svc.client
        val mode = (c.latest[12] as? Number)?.toInt() ?: 1
        workoutBtn.text = if (mode == 2) "Stop Workout" else "Start Workout"

        stateView.text = when (svc.state) {
            BridgeService.State.NO_DEVICE -> "NO brainboard attached — plug the console in"
            BridgeService.State.NO_PERMISSION -> "Brainboard attached — USB not claimed (re-plug, or grant at install)"
            BridgeService.State.CONNECTING -> "Claiming USB interface…"
            BridgeService.State.HANDSHAKE -> "Handshake in progress…"
            BridgeService.State.STREAMING -> if (c.ready) "STREAMING (ready)" else "Connected — waiting for data"
            BridgeService.State.ERROR -> "ERROR — ${c.lastError} (retrying)"
        }
        stateView.setTextColor(
            when (svc.state) {
                BridgeService.State.STREAMING -> active.statusOk
                BridgeService.State.ERROR -> active.statusErr
                else -> active.statusInfo
            }
        )

        identityView.text = buildString {
            if (c.initialized || c.swVersion != 0) {
                append("sw=").append(c.swVersion)
                append(" hw=").append(c.hwVersion)
                append(" serial=").append(c.serialNo)
                append(" model=").append(c.modelNumber)
                append(" part=").append(c.partNumber)
                append(" mlv=").append(c.masterLibVersion)
                append("  |  supported fields: ").append(c.supportedBitFields.size)
                append(" (")
                append(c.supportedBitFields.sorted().joinToString(",") { it.toString() })
                append(")")
                append("  |  maxResLevel=").append(c.maxResistanceLevel)
                append("  |  RequireStartRequested supported: ")
                append(c.supportedBitFields.contains(108))
            } else {
                append("no identity yet (waiting on handshake)")
            }
        }

        telemetryView.text = buildString {
            if (c.latest.isEmpty()) {
                append("no telemetry yet")
            } else {
                val show = listOf(12, 0, 5, 3, 1, 2, 4, 21, 11, 10, 96, 98, 116)
                for (id in show) {
                    val v = c.latest[id] ?: continue
                    val f = BitFields.format(id, v, c.maxResistanceLevel)
                    append(BitFields.nameOf(id)).append("=")
                    append(f ?: v.toString())
                    append("  ")
                }
            }
        }

        // Stat tiles
        timeValue.text = (c.latest[11] as? Number)?.let { BitFields.format(11, it, 0) } ?: "—"
        rpmValue.text = (c.latest[5] as? Number)?.toInt()?.toString() ?: "—"
        wattsValue.text = (c.latest[3] as? Number)?.toInt()?.toString() ?: "—"
        inclineValue.text = (c.latest[1] as? Number)?.let { "%.1f".format(it.toInt() / 100.0) } ?: "—"
        resValue.text = (c.latest[2] as? Number)?.let { c.levelFromRaw(it.toInt()).toString() } ?: "—"
        kcalValue.text = (c.latest[21] as? Number)?.let { "%.1f".format(it.toDouble() * 1024.0 / 1e8) } ?: "—"
        updateSpeed(c)
        updateDistance(c)

        hexView.text = svc.hex.tail(40)
    }

    private fun updateSpeed(c: FitPro1Client) {
        val unit = if (metricSpeed) "km/h" else "mph"
        val n = c.latest[0] as? Number
        if (n == null) {
            speedValue.text = "—"
            speedUnits.text = unit
            return
        }
        val kmh = n.toInt() / 100.0
        speedValue.text = "%.2f".format(if (metricSpeed) kmh else kmh * 0.621371)
        speedUnits.text = unit
    }

    private fun updateDistance(c: FitPro1Client) {
        val unit = if (metricDistance) "km" else "mi"
        val n = c.latest[4] as? Number
        if (n == null) {
            distValue.text = "—"
            distUnits.text = unit
            return
        }
        val m = n.toLong().toDouble()
        distValue.text = "%.2f".format(if (metricDistance) m / 1000.0 else m / 1609.344)
        distUnits.text = unit
    }

    private fun toggleWorkout() {
        val c = BridgeService.instance?.client ?: return
        if (!c.ready) {
            stateView.setTextColor(active.statusErr)
            stateView.text = "Console not ready yet — wait for STREAMING"
            return
        }
        val mode = (c.latest[12] as? Number)?.toInt() ?: 1
        if (mode == 2) {
            stateView.setTextColor(active.statusInfo)
            stateView.text = "Stopping workout (WorkoutMode → Idle)…"
            c.stopWorkout()
        } else {
            stateView.setTextColor(active.statusInfo)
            stateView.text = "Starting workout (WorkoutMode → Running)…"
            c.startWorkout()
        }
    }

    // --- layout helpers ------------------------------------------------------

    private fun gap(bottom: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { this.bottomMargin = bottom }

    private fun btnParams(marginEnd: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { this.marginEnd = marginEnd }

    private fun hRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }

    private fun chip(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply { this.cornerRadius = radius; this.setColor(color) }

    /** One stat box: a small title on top, a large centred value, a small
     *  unit line at the bottom (empty where the title already names the unit).
     *  Returns handles to the value/unit views and the box (for tap handling).
     *  The box is also registered in [tileBoxes] so the theme can recolour it. */
    private data class Tile(val box: LinearLayout, val value: TextView, val units: TextView)

    private fun tile(parent: LinearLayout, title: String, units: String = ""): Tile {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.background = chip(LIGHT.card, 16f) // placeholder; applyTheme() recolors
        box.setPadding(10, 10, 10, 10)
        val boxParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        boxParams.setMargins(5, 5, 5, 5)
        box.layoutParams = boxParams

        val titleTv = TextView(this)
        titleTv.text = title
        titleTv.setTextSize(24f)
        titleTv.gravity = Gravity.CENTER
        titleTv.setTextColor(LIGHT.muted)
        box.addView(titleTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val valueTv = TextView(this)
        valueTv.text = "—"
        valueTv.setTextSize(48f)
        valueTv.setTypeface(Typeface.DEFAULT_BOLD)
        valueTv.gravity = Gravity.CENTER
        valueTv.setTextColor(LIGHT.value)
        box.addView(valueTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val unitsTv = TextView(this)
        unitsTv.text = units
        unitsTv.setTextSize(22f)
        unitsTv.gravity = Gravity.CENTER
        unitsTv.setTextColor(LIGHT.muted)
        box.addView(unitsTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        parent.addView(box)
        tileBoxes.add(box)
        return Tile(box, valueTv, unitsTv)
    }
}
