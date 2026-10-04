package kr.dfluid.paint.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kr.dfluid.paint.R
import kr.dfluid.paint.brush.Brush
import kr.dfluid.paint.brush.BrushLibrary
import kr.dfluid.paint.brush.TipImage
import kr.dfluid.paint.brush.Tool
import kr.dfluid.paint.document.DocumentData
import kr.dfluid.paint.document.NodeInfo
import kr.dfluid.paint.document.ProjectIO
import kr.dfluid.paint.document.PsdIO
import kr.dfluid.paint.engine.CanvasRenderer
import kr.dfluid.paint.engine.PerfMonitor
import kr.dfluid.paint.engine.SelOp
import kr.dfluid.paint.engine.SelShape
import kr.dfluid.paint.input.CanvasView
import kr.dfluid.paint.input.HoldMode
import kr.dfluid.paint.input.SymMode
import kr.dfluid.paint.input.Symmetry
import kr.dfluid.paint.shortcut.Action
import kr.dfluid.paint.shortcut.ShortcutDispatcher
import kr.dfluid.paint.shortcut.ShortcutSettingsActivity
import kr.dfluid.paint.shortcut.ShortcutStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity(), CanvasRenderer.Listener, CanvasView.Host, ShortcutDispatcher.Handler,
    ToolOptions.Host, OverlayView.Listener {

    override lateinit var settings: AppSettings
        private set
    override lateinit var library: BrushLibrary
        private set
    private lateinit var shortcuts: ShortcutStore
    private lateinit var dispatcher: ShortcutDispatcher
    private lateinit var renderer: CanvasRenderer
    private lateinit var canvasView: CanvasView
    private lateinit var overlay: OverlayView
    private lateinit var layerPanel: LayerPanel
    private lateinit var toolOptions: ToolOptions

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var tool = Tool.PEN
    private var toolBeforeTemp: Tool? = null
    override val symmetry = Symmetry()
    private lateinit var symBtn: ImageView
    private var straightLineOn = false
    override val straightLine: Boolean get() = straightLineOn
    private lateinit var lineBtn: ImageView
    private val holds = ArrayList<Action>()
    private var shiftHeld = false
    private var altHeld = false

    private var currentUri: Uri? = null
    private var savedVersion = 0L
    private var autosavedVersion = 0L // 0 = 손대지 않은 기본 빈 캔버스 → 자동 저장하지 않음
    private var currentName: String? = null
    private var maxTex = 4096
    private var uiVisible = true
    private var hasSelection = false

    // 변형 / 이동 도구
    private var transforming = false
    private var moveSession = false
    private var pendingMoveX = 0f
    private var pendingMoveY = 0f
    private var commitWhenStarted = false

    private var pendingTipPick: ((InputStream?) -> Unit)? = null

    // 뷰
    private lateinit var topBar: View
    private lateinit var toolBar: View
    private lateinit var rightPanel: View
    private lateinit var transformBar: View
    private lateinit var hud: TextView
    private lateinit var viewLabel: TextView
    private lateinit var titleLabel: TextView
    private lateinit var undoBtn: ImageView
    private lateinit var redoBtn: ImageView
    private lateinit var panelBtn: ImageView
    private lateinit var perfBtn: ImageView
    private lateinit var perfPanel: View
    private lateinit var perfText: TextView
    private lateinit var benchText: TextView
    private lateinit var primarySwatch: View
    private lateinit var secondarySwatch: View
    private val toolButtons = HashMap<Tool, ImageView>()
    private lateinit var tips: Ui.Tips
    private val hideHud = Runnable { hud.visibility = View.GONE }

    private val autosaveFile: File get() = File(filesDir, "autosave.dfp")

    // =====================================================================
    // 생명주기
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        library = BrushLibrary(this)
        shortcuts = ShortcutStore(this)
        dispatcher = ShortcutDispatcher(shortcuts, this)
        tips = Ui.Tips { a -> shortcuts.get(a).firstOrNull()?.label() }
        renderer = CanvasRenderer(this)
        renderer.defaultBackground = settings.newCanvasBackground
        canvasView = CanvasView(this, renderer)
        canvasView.host = this
        overlay = OverlayView(this, canvasView.viewport)
        overlay.listener = this
        overlay.stylusSeen = { canvasView.stylusSeen }
        layerPanel = LayerPanel(this, renderer, tips)
        toolOptions = ToolOptions(this, this)

        setContentView(buildLayout())
        enterImmersive()
        setTool(Tool.PEN)
        updateSwatches()
        updateTitle()

        currentUri = settings.lastUri?.let { Uri.parse(it) }
        restoreAutosave()
    }

    override fun onResume() {
        super.onResume()
        renderer.notifyResumed()
        canvasView.onResume()
        shortcuts.load() // 설정 화면에서 바뀌었을 수 있음
        tips.refresh()
        enterImmersive()
    }

    override fun onPause() {
        dispatcher.releaseAll()
        settings.save()
        library.save()
        // 일시정지 전에 GL 스레드에서 픽셀을 CPU로 복사 → 컨텍스트 손실 대비 + 자동 저장
        val saved = savedVersion
        canvasView.queueEvent {
            val snap = renderer.capturePauseSnapshot() ?: return@queueEvent
            val (data, composite, version) = snap
            if (version != autosavedVersion) {
                io.execute { writeAutosave(data, composite, version, clean = version == saved) }
            }
        }
        canvasView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            dispatcher.releaseAll()
            shiftHeld = false; altHeld = false
        } else enterImmersive()
    }

    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    // =====================================================================
    // 레이아웃
    // =====================================================================

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(0x1B, 0x1C, 0x1E)) }
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(canvasView, match)
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val m = Ui.dp(this, 8f)

        // ---- 상단 바 ----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(m, m / 2, m, m / 2)
            background = Ui.rounded(Ui.PANEL, Ui.dp(this@MainActivity, 10f).toFloat())
        }
        fun barBtn(icon: Int, tip: String, action: Action? = null, onClick: () -> Unit): ImageView {
            val b = tips.bind(Ui.iconButton(this, icon, tip, onClick = onClick), tip, action)
            bar.addView(b, Ui.square(this, 40f).apply { rightMargin = Ui.dp(this@MainActivity, 4f) })
            return b
        }
        fun act(icon: Int, tip: String, action: Action) = barBtn(icon, tip, action) { onShortcut(action) }
        titleLabel = Ui.text(this, "", 13f, Ui.TEXT, bold = true).apply { setPadding(m, 0, m * 2, 0) }
        bar.addView(titleLabel)
        act(R.drawable.ic_file_new, "새 캔버스", Action.FILE_NEW)
        act(R.drawable.ic_file_open, "열기", Action.FILE_OPEN)
        act(R.drawable.ic_file_save, "저장", Action.FILE_SAVE)
        barBtn(R.drawable.ic_file_export, "내보내기 (PNG · PSD)") { chooseExport() }
        bar.addView(Ui.hspace(this, 12f))
        undoBtn = barBtn(R.drawable.ic_undo, "실행취소", Action.UNDO) { renderer.undo() }
        redoBtn = barBtn(R.drawable.ic_redo, "다시실행", Action.REDO) { renderer.redo() }
        bar.addView(Ui.hspace(this, 12f))
        act(R.drawable.ic_transform, "자유 변형", Action.TRANSFORM)
        act(R.drawable.ic_deselect, "선택 해제", Action.SELECT_NONE)
        bar.addView(Ui.hspace(this, 12f))
        act(R.drawable.ic_view_fit, "화면에 맞춤", Action.VIEW_FIT)
        act(R.drawable.ic_view_rotate_reset, "회전 초기화", Action.VIEW_ROTATE_RESET)
        act(R.drawable.ic_view_flip, "화면 좌우 반전", Action.VIEW_FLIP)
        bar.addView(Ui.hspace(this, 12f))
        lineBtn = barBtn(R.drawable.ic_ruler, "직선 자 (시작점에서 끝점까지 곧은 선)") { toggleStraightLine() }
        symBtn = barBtn(R.drawable.ic_sym_vertical, "대칭") { cycleSymmetry() }
        bar.addView(Ui.hspace(this, 12f))
        viewLabel = Ui.text(this, "", 12f, Ui.SUBTEXT).apply { setPadding(m, 0, m, 0) }
        bar.addView(viewLabel)
        perfBtn = barBtn(R.drawable.ic_gauge, "성능 측정 (FPS·펜 지연·부하 테스트)") { togglePerf() }
        barBtn(R.drawable.ic_keyboard, "단축키 설정") { openShortcutSettings() }
        barBtn(R.drawable.ic_settings, "설정") { showSettings() }
        panelBtn = barBtn(R.drawable.ic_panel, "오른쪽 패널 보이기/숨기기") {
            rightPanel.visibility = if (rightPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            Ui.setOn(panelBtn, rightPanel.visibility == View.VISIBLE)
        }
        Ui.setOn(panelBtn, true)
        undoBtn.alpha = 0.4f
        redoBtn.alpha = 0.4f
        updateSymmetryButton()

        topBar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(m, m, m, 0)
        })

        // ---- 왼쪽 도구 막대 ----
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(m / 2, m, m / 2, m)
            background = Ui.rounded(Ui.PANEL, Ui.dp(this@MainActivity, 10f).toFloat())
        }
        Tool.entries.forEach { t ->
            val b = tips.bind(Ui.iconButton(this, toolIcon(t), t.label, 44f) { setTool(t) }, t.label, toolAction(t))
            toolButtons[t] = b
            tools.addView(b, Ui.square(this, 44f).apply {
                bottomMargin = Ui.dp(this@MainActivity, 3f)
            })
        }
        val swatchSize = Ui.dp(this, 40f)
        val swatches = FrameLayout(this)
        secondarySwatch = View(this).apply { setOnClickListener { onShortcut(Action.COLOR_SWAP) } }
        primarySwatch = View(this).apply {
            setOnClickListener { Dialogs.colorPicker(this@MainActivity, settings.primaryColor) { setPrimary(it) } }
        }
        tips.bind(secondarySwatch, "보조색 (누르면 주색과 바꾸기)", Action.COLOR_SWAP)
        tips.bind(primarySwatch, "주색 (누르면 색 선택)")
        swatches.addView(secondarySwatch, FrameLayout.LayoutParams(swatchSize, swatchSize, Gravity.BOTTOM or Gravity.END))
        swatches.addView(primarySwatch, FrameLayout.LayoutParams(swatchSize, swatchSize, Gravity.TOP or Gravity.START))
        tools.addView(swatches, LinearLayout.LayoutParams(swatchSize + Ui.dp(this, 16f), swatchSize + Ui.dp(this, 16f)).apply {
            topMargin = Ui.dp(this@MainActivity, 6f)
        })
        toolBar = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(tools)
        }
        root.addView(toolBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            setMargins(m, Ui.dp(this@MainActivity, 64f), 0, m)
        })

        // ---- 오른쪽 패널: 도구 옵션 + 레이어 ----
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@MainActivity, 12f)
            setPadding(p, p, p, p)
            background = Ui.rounded(Ui.PANEL, Ui.dp(this@MainActivity, 10f).toFloat())
        }
        panel.addView(ScrollView(this).apply {
            addView(toolOptions.view)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.9f))
        panel.addView(toolOptions.recentView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@MainActivity, 6f)
        })
        panel.addView(Ui.divider(this))
        panel.addView(layerPanel.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.1f))
        rightPanel = panel
        root.addView(rightPanel, FrameLayout.LayoutParams(Ui.dp(this, 310f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply {
            setMargins(m, Ui.dp(this@MainActivity, 64f), m, m)
        })

        // ---- 변형 확정/취소 바 ----
        val tb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(m, m / 2, m, m / 2)
            background = Ui.rounded(Ui.PANEL, Ui.dp(this@MainActivity, 10f).toFloat())
            visibility = View.GONE
        }
        fun tbBtn(icon: Int, tip: String, onClick: () -> Unit): ImageView {
            val b = Ui.iconButton(this, icon, tip, onClick = onClick)
            tb.addView(b, Ui.square(this, 40f).apply { rightMargin = Ui.dp(this@MainActivity, 4f) })
            return b
        }
        tb.addView(Ui.text(this, "자유 변형", 13f, bold = true).apply { setPadding(m, 0, m * 2, 0) })
        tbBtn(R.drawable.ic_flip_h, "좌우 반전") { overlay.flip(true) }
        tbBtn(R.drawable.ic_flip_v, "상하 반전") { overlay.flip(false) }
        tbBtn(R.drawable.ic_rotate_90, "90° 회전") { overlay.rotateBy((Math.PI / 2).toFloat()) }
        tb.addView(Ui.hspace(this, 8f))
        Ui.setOn(tbBtn(R.drawable.ic_check, "확정 (Enter)") { commitTransform() }, true)
        tbBtn(R.drawable.ic_close, "취소 (Esc)") { cancelTransform() }
        transformBar = tb
        root.addView(transformBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Ui.dp(this@MainActivity, 16f)
        })

        // ---- 성능 측정 패널 ----
        perfText = Ui.text(this, "펜으로 그리면 측정합니다.", 12f).apply { typeface = android.graphics.Typeface.MONOSPACE }
        benchText = Ui.text(this, "", 12f, Ui.SUBTEXT).apply { typeface = android.graphics.Typeface.MONOSPACE }
        perfPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@MainActivity, 10f)
            setPadding(p, p, p, p)
            background = Ui.rounded(Ui.PANEL, Ui.dp(this@MainActivity, 10f).toFloat())
            visibility = View.GONE
            addView(Ui.text(this@MainActivity, "성능 측정", 13f, bold = true))
            addView(perfText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(this@MainActivity, 4f)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Ui.button(this@MainActivity, "부하 테스트 문서 (A4 300dpi · 50장)") { loadStressDocument() }, Ui.wrap())
                addView(Ui.hspace(this@MainActivity, 6f))
                addView(Ui.button(this@MainActivity, "합성 벤치마크") {
                    benchText.text = "측정 중… (몇 초 걸립니다)"
                    renderer.runBenchmark()
                }, Ui.wrap())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(this@MainActivity, 8f)
            })
            addView(benchText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(this@MainActivity, 6f)
            })
        }
        root.addView(perfPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(Ui.dp(this@MainActivity, 76f), Ui.dp(this@MainActivity, 64f), 0, 0)
        })

        // ---- 중앙 HUD (단축키 피드백) ----
        hud = Ui.text(this, "", 16f, Ui.TEXT, bold = true).apply {
            val p = Ui.dp(this@MainActivity, 14f)
            setPadding(p, p / 2, p, p / 2)
            background = Ui.rounded(0xDD000000.toInt(), Ui.dp(this@MainActivity, 8f).toFloat())
            visibility = View.GONE
        }
        root.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return root
    }

    // =====================================================================
    // 상태 → UI
    // =====================================================================

    private fun activeBrush(): Brush? = if (tool.isBrush) library.active(tool) else null

    private fun updateToolButtons() {
        toolButtons.forEach { (t, b) -> Ui.setOn(b, t == tool) }
    }

    private fun updateSwatches() {
        val r = Ui.dp(this, 6f).toFloat()
        val stroke = Ui.dp(this, 2f)
        primarySwatch.background = Ui.rounded(settings.primaryColor, r, stroke, Color.WHITE)
        secondarySwatch.background = Ui.rounded(settings.secondaryColor, r, stroke, Color.GRAY)
    }

    private fun updateTitle() {
        val uri = currentUri
        if (uri == null) currentName = null
        else if (currentName == null) currentName = displayName(uri)
        val name = currentName ?: "제목 없음"
        val dirty = renderer.version != savedVersion
        titleLabel.text = if (dirty) "$name *" else name
    }

    private fun showHud(text: String) {
        hud.text = text
        hud.visibility = View.VISIBLE
        ui.removeCallbacks(hideHud)
        ui.postDelayed(hideHud, 900)
    }

    private fun setTool(t: Tool) {
        if (t != tool && transforming && !moveSession && t != Tool.MOVE) commitTransform()
        tool = t
        updateToolButtons()
        toolOptions.show(t)
        renderer.hideCursor()
    }

    private fun setPrimary(c: Int) {
        settings.primaryColor = c
        settings.pushRecent(c)
        toolOptions.refreshRecent()
        updateSwatches()
    }

    private fun cycleSymmetry() {
        symmetry.mode = symmetry.mode.next()
        overlay.showSymmetry(symmetry.mode, symmetry.radialCount)
        updateSymmetryButton()
    }

    private fun updateSymmetryButton() {
        val mode = symmetry.mode
        symBtn.setImageResource(
            when (mode) {
                SymMode.HORIZONTAL -> R.drawable.ic_sym_horizontal
                SymMode.QUAD -> R.drawable.ic_sym_quad
                SymMode.RADIAL -> R.drawable.ic_sym_radial
                else -> R.drawable.ic_sym_vertical
            }
        )
        Ui.setOn(symBtn, symmetry.on)
        symBtn.alpha = if (symmetry.on) 1f else 0.6f
        tips.relabel(symBtn, "대칭: ${mode.label} (누르면 다음: ${mode.next().label})")
    }

    private fun toggleStraightLine() {
        straightLineOn = !straightLineOn
        Ui.setOn(lineBtn, straightLineOn)
        showHud(if (straightLineOn) "직선 자 켬" else "직선 자 끔")
    }

    private fun toolIcon(t: Tool): Int = when (t) {
        Tool.PEN -> R.drawable.ic_tool_pen
        Tool.PENCIL -> R.drawable.ic_tool_pencil
        Tool.AIRBRUSH -> R.drawable.ic_tool_airbrush
        Tool.MARKER -> R.drawable.ic_tool_marker
        Tool.ERASER -> R.drawable.ic_tool_eraser
        Tool.SELECT -> R.drawable.ic_tool_select
        Tool.MOVE -> R.drawable.ic_tool_move
        Tool.FILL -> R.drawable.ic_tool_fill
        Tool.GRADIENT -> R.drawable.ic_tool_gradient
        Tool.EYEDROPPER -> R.drawable.ic_tool_eyedropper
        Tool.HAND -> R.drawable.ic_tool_hand
    }

    private fun toolAction(t: Tool): Action = when (t) {
        Tool.PEN -> Action.TOOL_PEN
        Tool.PENCIL -> Action.TOOL_PENCIL
        Tool.AIRBRUSH -> Action.TOOL_AIRBRUSH
        Tool.MARKER -> Action.TOOL_MARKER
        Tool.ERASER -> Action.TOOL_ERASER
        Tool.SELECT -> Action.TOOL_SELECT
        Tool.MOVE -> Action.TOOL_MOVE
        Tool.FILL -> Action.TOOL_FILL
        Tool.GRADIENT -> Action.TOOL_GRADIENT
        Tool.EYEDROPPER -> Action.TOOL_EYEDROPPER
        Tool.HAND -> Action.TOOL_HAND
    }

    // =====================================================================
    // CanvasView.Host
    // =====================================================================

    override val currentTool: Tool get() = tool
    override val holdMode: HoldMode
        get() = when (holds.lastOrNull()) {
            Action.HOLD_PAN -> HoldMode.PAN
            Action.HOLD_ROTATE -> HoldMode.ROTATE
            Action.HOLD_ZOOM -> HoldMode.ZOOM
            Action.HOLD_EYEDROPPER -> HoldMode.EYEDROPPER
            else -> HoldMode.NONE
        }
    override val brushColor: Int get() = settings.primaryColor
    override val smoothing: Float get() = settings.smoothing
    override val pressureGamma: Float get() = settings.pressureGamma
    override val drawWithFinger: Boolean get() = settings.drawWithFinger
    override fun brushFor(tool: Tool): Brush = library.active(tool) ?: kr.dfluid.paint.brush.BrushPresets.create(tool)
    override fun tipFor(brush: Brush): TipImage? = brush.tipId?.let { library.tip(it) }
    override fun onStrokeStarted() {
        dispatcher.markUsed()
        settings.pushRecent(settings.primaryColor)
    }

    override fun onColorPicked(color: Int) = setPrimary(color)
    override fun onUndoGesture() {
        renderer.undo(); showHud("실행취소")
    }

    override fun onRedoGesture() {
        renderer.redo(); showHud("다시실행")
    }

    override fun onViewChanged() {
        val v = canvasView.viewport
        viewLabel.text = "${(v.scale * 100).roundToInt()}% · ${v.rotationDegrees}°${if (v.flipped) " · 반전" else ""}"
        overlay.invalidate()
    }

    override val transformActive: Boolean get() = transforming
    override val selectShape: SelShape get() = settings.selectShape

    override fun onSelectPreview(shape: SelShape, pts: FloatArray) {
        if (pts.isEmpty()) overlay.clearGuides() else overlay.showSelection(shape, pts)
    }

    override fun onSelectDone(shape: SelShape, pts: FloatArray, tiny: Boolean) {
        overlay.clearGuides()
        if (tiny) {
            renderer.deselect()
            return
        }
        val op = when {
            shiftHeld -> SelOp.ADD
            altHeld -> SelOp.SUBTRACT
            else -> settings.selectMode
        }
        renderer.select(shape, pts, op)
    }

    override fun onWandTap(x: Float, y: Float) {
        val op = when {
            shiftHeld -> SelOp.ADD
            altHeld -> SelOp.SUBTRACT
            else -> settings.selectMode
        }
        renderer.selectByColor(x.toInt(), y.toInt(), settings.wandOptions, op)
    }

    override fun onFillTap(x: Float, y: Float) {
        renderer.fillAt(x.toInt(), y.toInt(), settings.fillOptions, settings.primaryColor, settings.fillOpacity)
        settings.pushRecent(settings.primaryColor)
    }

    override fun onGradient(x0: Float, y0: Float, x1: Float, y1: Float, phase: Int) {
        when (phase) {
            0 -> {
                overlay.showGradient(x0, y0, x1, y1)
                renderer.gradientPreview(x0, y0, x1, y1, settings.gradientSpec, settings.primaryColor, settings.secondaryColor, settings.gradientOpacity)
            }
            1 -> {
                overlay.clearGuides()
                renderer.gradientPreview(x0, y0, x1, y1, settings.gradientSpec, settings.primaryColor, settings.secondaryColor, settings.gradientOpacity)
                renderer.gradientCommit()
            }
            else -> {
                overlay.clearGuides()
                renderer.gradientCancel()
            }
        }
    }

    override fun onMoveStart() {
        if (transforming) return // 이미 변형 중이면 그 상자를 옮김
        if (moveSession) {
            // 변형 시작 응답을 기다리는 중에 다시 드래그: 이어서 같은 세션으로
            commitWhenStarted = false
            return
        }
        moveSession = true
        pendingMoveX = 0f; pendingMoveY = 0f
        commitWhenStarted = false
        renderer.beginTransform()
    }

    override fun onMoveDrag(dx: Float, dy: Float) {
        if (transforming) overlay.translateBy(dx, dy)
        else if (moveSession) {
            pendingMoveX += dx; pendingMoveY += dy
        }
    }

    override fun onMoveEnd() {
        if (!moveSession) return
        if (transforming) {
            moveSession = false
            overlay.snapTranslation()
            renderer.commitTransform()
        } else {
            commitWhenStarted = true
        }
    }

    // =====================================================================
    // OverlayView.Listener / ToolOptions.Host
    // =====================================================================

    override fun onTransformChanged(m: FloatArray) = renderer.setTransform(m)
    override val keepAspect: Boolean get() = shiftHeld

    override fun onBrushChanged() {
        renderer.hideCursor()
    }

    override fun editBrush(tool: Tool, brush: Brush) {
        BrushEditor.show(this, brush, library, pickImage = { cb -> pickTipImage(cb) }) {
            toolOptions.show(this.tool)
        }
    }

    override fun selectAll() = renderer.selectAll()
    override fun deselect() = renderer.deselect()
    override fun invertSelection() = renderer.invertSelection()
    override fun startTransform() {
        if (!transforming) renderer.beginTransform()
    }

    override fun onRecentColor(color: Int) = setPrimary(color)

    private fun commitTransform() {
        if (transforming) renderer.commitTransform()
    }

    private fun cancelTransform() {
        if (transforming) renderer.cancelTransform()
    }

    private fun pickTipImage(cb: (InputStream?) -> Unit) {
        pendingTipPick = cb
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQ_TIP)
    }

    // =====================================================================
    // CanvasRenderer.Listener (메인 스레드)
    // =====================================================================

    override fun onGlReady(maxTextureSize: Int) {
        maxTex = maxTextureSize
    }

    override fun onDocumentReplaced(width: Int, height: Int, refit: Boolean) {
        canvasView.onDocumentSize(width, height, refit)
        updateTitle()
    }

    private var lastMaskEditing = false

    override fun onLayersChanged(nodes: List<NodeInfo>, activeId: Int, liveRasterIds: Set<Int>) {
        lastNodes = nodes
        lastActiveId = activeId
        val maskEditing = renderer.maskEditing && nodes.firstOrNull { it.id == activeId }?.props?.mask == true
        if (maskEditing != lastMaskEditing) {
            lastMaskEditing = maskEditing
            showHud(if (maskEditing) "마스크 편집 · 그리기 = 보이기, 지우개 = 가리기" else "레이어 편집")
        }
        layerPanel.update(nodes, activeId, liveRasterIds)
        updateTitle()
    }

    override fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {
        Ui.setEnabled(undoBtn, canUndo)
        Ui.setEnabled(redoBtn, canRedo)
        updateTitle()
    }

    override fun onSelectionChanged(hasSelection: Boolean) {
        this.hasSelection = hasSelection
    }

    override fun onThumbnail(id: Int, bitmap: Bitmap) = layerPanel.setThumbnail(id, bitmap)

    override fun onTransformStarted(width: Int, height: Int, matrix: FloatArray) {
        transforming = true
        overlay.startTransform(width, height, matrix)
        if (moveSession && (pendingMoveX != 0f || pendingMoveY != 0f)) overlay.translateBy(pendingMoveX, pendingMoveY)
        pendingMoveX = 0f; pendingMoveY = 0f
        if (moveSession && commitWhenStarted) {
            moveSession = false
            commitWhenStarted = false
            overlay.snapTranslation()
            renderer.commitTransform()
        }
        transformBar.visibility = if (moveSession) View.GONE else View.VISIBLE
    }

    override fun onTransformEnded() {
        transforming = false
        moveSession = false
        commitWhenStarted = false
        overlay.endTransform()
        transformBar.visibility = View.GONE
    }

    override fun onPerfStats(stats: PerfMonitor.Stats) {
        if (perfPanel.visibility != View.VISIBLE) return
        val hz = refreshRate()
        val budget = 1000f / hz
        val lat = if (stats.latencySamples == 0) "펜 지연: 그리면 측정합니다"
        else String.format(
            "펜 지연  평균 %.1fms · 95%% %.1fms  (= %.1f프레임)",
            stats.latencyAvg, stats.latencyP95, stats.latencyAvg / budget
        )
        perfText.text = String.format(
            "화면 %.0fHz (1프레임 %.1fms)\nFPS %.0f · 프레임 처리 평균 %.1fms · 95%% %.1fms\n%s\n펜 지연 = 펜 이벤트 → 그 입력을 그린 프레임 완료. 화면 표시는 보통 1프레임 더.",
            hz, budget, stats.fps, stats.frameAvg, stats.frameP95, lat
        )
    }

    override fun onBenchmarkDone(report: String) {
        benchText.text = report + String.format("\n(관문 2: 브러시 영역 합성이 %.1fms 이내면 %.0f fps 유지)", 1000f / 60f, 60f)
        Log.i(TAG, "benchmark\n$report")
    }

    @Suppress("DEPRECATION")
    private fun refreshRate(): Float = windowManager.defaultDisplay.refreshRate.takeIf { it > 1f } ?: 60f

    private fun togglePerf() {
        val on = perfPanel.visibility != View.VISIBLE
        perfPanel.visibility = if (on) View.VISIBLE else View.GONE
        Ui.setOn(perfBtn, on)
        renderer.perf.enabled = on
        if (on) renderer.perf.reset()
    }

    private fun loadStressDocument() = confirmIfDirty {
        showHud("부하 테스트 문서를 만드는 중…")
        currentUri = null
        currentName = null
        settings.lastUri = null
        renderer.loadStressDocument(2480, 3508, 50) { version ->
            savedVersion = version
            updateTitle()
            benchText.text = "준비됐습니다. 합성 벤치마크를 누르거나 직접 그려 보세요."
        }
    }

    override fun onRendererError(message: String) {
        if (moveSession && !transforming) {
            moveSession = false // 옮길 픽셀이 없었음
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // =====================================================================
    // 단축키
    // =====================================================================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        shiftHeld = event.isShiftPressed
        altHeld = event.isAltPressed
        if (transforming && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { commitTransform(); return true }
                KeyEvent.KEYCODE_ESCAPE -> { cancelTransform(); return true }
            }
        }
        if (dispatcher.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onShortcut(action: Action) {
        val v = canvasView.viewport
        when (action) {
            Action.BRUSH_SIZE_DOWN, Action.BRUSH_SIZE_UP -> activeBrush()?.let {
                val up = action == Action.BRUSH_SIZE_UP
                val next = if (up) max(it.size + 1f, it.size * 1.12f) else minOf(it.size - 1f, it.size / 1.12f)
                it.size = next.coerceIn(Brush.MIN_SIZE, Brush.MAX_SIZE)
                toolOptions.refresh()
                showHud("크기 ${ToolOptions.formatSize(it.size)}")
            }
            Action.BRUSH_OPACITY_DOWN, Action.BRUSH_OPACITY_UP -> activeBrush()?.let {
                val d = if (action == Action.BRUSH_OPACITY_UP) 0.1f else -0.1f
                it.opacity = ((it.opacity + d) * 10).roundToInt().div(10f).coerceIn(0.1f, 1f)
                toolOptions.refresh()
                showHud("불투명도 ${(it.opacity * 100).roundToInt()}%")
            }
            Action.BRUSH_PREV, Action.BRUSH_NEXT -> if (tool.isBrush) {
                val list = library.list(tool)
                val cur = list.indexOfFirst { it.id == library.active(tool)?.id }
                if (list.isNotEmpty()) {
                    val next = list[((cur + if (action == Action.BRUSH_NEXT) 1 else -1) + list.size) % list.size]
                    library.setActive(tool, next.id)
                    toolOptions.show(tool)
                    showHud(next.name)
                }
            }
            Action.BRUSH_EDIT -> activeBrush()?.let { editBrush(tool, it) }
            Action.COLOR_SWAP -> {
                val p = settings.primaryColor
                settings.primaryColor = settings.secondaryColor
                settings.secondaryColor = p
                updateSwatches()
            }
            Action.COLOR_RESET -> {
                settings.primaryColor = Color.BLACK
                settings.secondaryColor = Color.WHITE
                updateSwatches()
            }
            Action.UNDO -> renderer.undo()
            Action.REDO -> renderer.redo()
            Action.TRANSFORM -> startTransform()
            Action.FILL_SELECTION -> renderer.fillSelection(settings.primaryColor)
            Action.SELECT_ALL -> renderer.selectAll()
            Action.SELECT_NONE -> renderer.deselect()
            Action.SELECT_INVERT -> renderer.invertSelection()
            Action.LAYER_NEW -> renderer.addLayer()
            Action.LAYER_FOLDER -> renderer.addFolder()
            Action.LAYER_GROUP -> renderer.groupActive()
            Action.LAYER_DUPLICATE -> renderer.duplicate()
            Action.LAYER_MERGE_DOWN -> renderer.mergeDown()
            Action.LAYER_CLEAR -> renderer.clearLayer()
            Action.LAYER_CLIP -> layerToggle { it.copy(clip = !it.clip) }
            Action.LAYER_ALPHA_LOCK -> layerToggle { it.copy(alphaLock = !it.alphaLock) }
            Action.LAYER_SELECT_UP -> renderer.selectAdjacent(+1)
            Action.LAYER_SELECT_DOWN -> renderer.selectAdjacent(-1)
            Action.VIEW_ZOOM_IN -> { v.zoomCentered(1.25f); canvasView.pushView() }
            Action.VIEW_ZOOM_OUT -> { v.zoomCentered(1 / 1.25f); canvasView.pushView() }
            Action.VIEW_FIT -> { v.fit(); canvasView.pushView() }
            Action.VIEW_ACTUAL -> { v.actualSize(); canvasView.pushView() }
            Action.VIEW_ROTATE_LEFT -> { v.rotateCentered(-ROTATE_STEP); canvasView.pushView(); showHud("${v.rotationDegrees}°") }
            Action.VIEW_ROTATE_RIGHT -> { v.rotateCentered(ROTATE_STEP); canvasView.pushView(); showHud("${v.rotationDegrees}°") }
            Action.VIEW_ROTATE_RESET -> { v.resetRotation(); canvasView.pushView() }
            Action.VIEW_FLIP -> { v.toggleFlip(); canvasView.pushView(); showHud(if (v.flipped) "좌우 반전" else "반전 해제") }
            Action.TOGGLE_UI -> toggleUi()
            Action.FILE_NEW -> confirmIfDirty { Dialogs.newCanvas(this, maxTex, settings) { w, h, bg -> newCanvas(w, h, bg) } }
            Action.FILE_OPEN -> confirmIfDirty { openDocument() }
            Action.FILE_SAVE -> save()
            Action.FILE_SAVE_AS -> saveAs()
            Action.FILE_EXPORT_PNG -> exportPng()
            Action.FILE_EXPORT_PSD -> exportPsd()
            else -> Unit
        }
    }

    /** 레이어 패널에 보이는 현재 노드의 속성을 토글 (클리핑/잠금 단축키). */
    private fun layerToggle(change: (kr.dfluid.paint.document.LayerProps) -> kr.dfluid.paint.document.LayerProps) {
        val info = lastNodes.firstOrNull { it.id == lastActiveId } ?: return
        val next = change(info.props)
        renderer.setProps(info.id, next, record = true)
        showHud(
            when {
                next.clip != info.props.clip -> if (next.clip) "클리핑 켬" else "클리핑 끔"
                else -> if (next.alphaLock) "투명 픽셀 잠금" else "잠금 해제"
            }
        )
    }

    private var lastNodes: List<NodeInfo> = emptyList()
    private var lastActiveId = 0

    override fun onToolKey(action: Action, down: Boolean, temporary: Boolean) {
        val target = when (action) {
            Action.TOOL_PEN -> Tool.PEN
            Action.TOOL_PENCIL -> Tool.PENCIL
            Action.TOOL_AIRBRUSH -> Tool.AIRBRUSH
            Action.TOOL_MARKER -> Tool.MARKER
            Action.TOOL_ERASER -> Tool.ERASER
            Action.TOOL_EYEDROPPER -> Tool.EYEDROPPER
            Action.TOOL_HAND -> Tool.HAND
            Action.TOOL_SELECT -> Tool.SELECT
            Action.TOOL_MOVE -> Tool.MOVE
            Action.TOOL_FILL -> Tool.FILL
            Action.TOOL_GRADIENT -> Tool.GRADIENT
            else -> return
        }
        if (down) {
            if (toolBeforeTemp == null) toolBeforeTemp = tool
            setTool(target)
            showHud(target.label)
        } else {
            val prev = toolBeforeTemp
            toolBeforeTemp = null
            if (temporary && prev != null && tool == target) setTool(prev)
        }
    }

    override fun onHoldKey(action: Action, down: Boolean) {
        holds.remove(action)
        if (down) holds.add(action)
        renderer.hideCursor()
    }

    private fun toggleUi() {
        uiVisible = !uiVisible
        val vis = if (uiVisible) View.VISIBLE else View.GONE
        topBar.visibility = vis
        toolBar.visibility = vis
        rightPanel.visibility = vis
    }

    private fun openShortcutSettings() {
        startActivity(Intent(this, ShortcutSettingsActivity::class.java))
    }

    private fun showSettings() {
        Dialogs.settings(this, settings, onChanged = { settings.save() }, onOpenShortcuts = { openShortcutSettings() })
    }

    // =====================================================================
    // 파일
    // =====================================================================

    private fun confirmIfDirty(action: () -> Unit) {
        if (renderer.version != savedVersion) {
            Dialogs.confirm(this, "저장하지 않은 변경 사항이 있습니다. 계속할까요?", "계속") { action() }
        } else {
            action()
        }
    }

    private fun newCanvas(w: Int, h: Int, background: Int?) {
        currentUri = null
        currentName = null
        settings.lastUri = null
        renderer.defaultBackground = background
        renderer.newDocument(w, h, background) { version ->
            savedVersion = version
            updateTitle()
        }
    }

    private fun openDocument() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/octet-stream", "application/zip", "image/png", "image/jpeg", "image/webp",
                    "image/vnd.adobe.photoshop", "application/x-photoshop", "application/photoshop", "image/x-photoshop", "image/psd",
                )
            )
        }
        startActivityForResult(intent, REQ_OPEN)
    }

    private fun save() {
        val uri = currentUri
        if (uri != null && displayName(uri)?.endsWith(".dfp", ignoreCase = true) == true) writeDocument(uri) else saveAs()
    }

    private fun saveAs() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, (currentUri?.let { displayName(it)?.substringBeforeLast('.') } ?: "그림") + ".dfp")
        }
        startActivityForResult(intent, REQ_SAVE)
    }

    private fun chooseExport() {
        val png = tips.text("PNG 이미지 (한 장으로 합침)", Action.FILE_EXPORT_PNG)
        val psd = tips.text("PSD (레이어·폴더 유지 · 클립 스튜디오/포토샵)", Action.FILE_EXPORT_PSD)
        android.app.AlertDialog.Builder(this)
            .setTitle("내보내기")
            .setItems(arrayOf(png, psd)) { _, which -> if (which == 0) exportPng() else exportPsd() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun exportPsd() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/vnd.adobe.photoshop"
            putExtra(Intent.EXTRA_TITLE, (currentUri?.let { displayName(it)?.substringBeforeLast('.') } ?: "그림") + ".psd")
        }
        startActivityForResult(intent, REQ_EXPORT_PSD)
    }

    private fun writePsd(uri: Uri) {
        showHud("PSD로 내보내는 중…")
        renderer.captureDocument { data, composite, _ ->
            io.execute {
                try {
                    contentResolver.openOutputStream(uri, "wt")?.use { PsdIO.write(it, data, composite) }
                        ?: throw IllegalStateException("파일을 열 수 없습니다.")
                    ui.post { showHud("PSD로 내보냈습니다") }
                } catch (e: OutOfMemoryError) {
                    ui.post { Toast.makeText(this, "메모리가 부족해 PSD로 내보내지 못했습니다.", Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {
                    Log.e(TAG, "psd export failed", e)
                    ui.post { Toast.makeText(this, "PSD 내보내기 실패: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    private fun exportPng() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/png"
            putExtra(Intent.EXTRA_TITLE, (currentUri?.let { displayName(it)?.substringBeforeLast('.') } ?: "그림") + ".png")
        }
        startActivityForResult(intent, REQ_EXPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_TIP) {
            val cb = pendingTipPick
            pendingTipPick = null
            val stream = if (resultCode == RESULT_OK && uri != null) {
                try { contentResolver.openInputStream(uri) } catch (e: Exception) { null }
            } else null
            cb?.invoke(stream)
            return
        }
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_SAVE -> {
                takePermission(uri)
                writeDocument(uri)
            }
            REQ_OPEN -> {
                takePermission(uri)
                readDocument(uri)
            }
            REQ_EXPORT -> writePng(uri)
            REQ_EXPORT_PSD -> writePsd(uri)
        }
    }

    private fun takePermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "persistable permission not granted", e)
        }
    }

    private fun writeDocument(uri: Uri) {
        showHud("저장 중…")
        renderer.captureDocument { data, composite, version ->
            io.execute {
                try {
                    contentResolver.openOutputStream(uri, "wt")?.use { ProjectIO.writeDfp(it, data, composite) }
                        ?: throw IllegalStateException("파일을 열 수 없습니다.")
                    // 자동 저장본도 최신으로
                    writeAutosave(data, composite, version, clean = true)
                    ui.post {
                        currentUri = uri
                        currentName = null
                        settings.lastUri = uri.toString()
                        settings.save()
                        savedVersion = version
                        updateTitle()
                        showHud("저장했습니다")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "save failed", e)
                    ui.post { Toast.makeText(this, "저장 실패: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    private fun readDocument(uri: Uri) {
        showHud("여는 중…")
        val max = maxTex
        io.execute {
            try {
                val head = contentResolver.openInputStream(uri)?.use { s ->
                    val b = ByteArray(4)
                    var n = 0
                    while (n < 4) { val r = s.read(b, n, 4 - n); if (r < 0) break; n += r }
                    b
                } ?: throw IllegalStateException("파일을 열 수 없습니다.")
                val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
                val isPsd = PsdIO.isPsd(head)
                val doc = contentResolver.openInputStream(uri)!!.use {
                    when {
                        isZip -> ProjectIO.readDfp(it, max)
                        isPsd -> PsdIO.read(it, max)
                        else -> ProjectIO.readImage(it, max)
                    }
                }
                ui.post {
                    renderer.loadDocument(doc) { version ->
                        currentUri = if (isZip) uri else null
                        currentName = null
                        settings.lastUri = currentUri?.toString()
                        savedVersion = version
                        updateTitle()
                    }
                }
            } catch (e: OutOfMemoryError) {
                ui.post { Toast.makeText(this, "메모리가 부족해 열 수 없습니다.", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                Log.e(TAG, "open failed", e)
                ui.post { Toast.makeText(this, "열기 실패: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun writePng(uri: Uri) {
        showHud("내보내는 중…")
        renderer.captureFlattened { w, h, pixels ->
            io.execute {
                try {
                    contentResolver.openOutputStream(uri, "wt")?.use { ProjectIO.writePng(it, w, h, pixels) }
                        ?: throw IllegalStateException("파일을 열 수 없습니다.")
                    ui.post { showHud("PNG로 내보냈습니다") }
                } catch (e: Exception) {
                    Log.e(TAG, "export failed", e)
                    ui.post { Toast.makeText(this, "내보내기 실패: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    /** io 스레드에서 호출. 임시 파일에 쓴 뒤 교체해 중간에 죽어도 이전 자동 저장본이 남습니다. */
    private fun writeAutosave(data: DocumentData, composite: java.nio.ByteBuffer, version: Long, clean: Boolean) {
        try {
            val tmp = File(filesDir, "autosave.tmp")
            FileOutputStream(tmp).use { ProjectIO.writeDfp(it, data, composite) }
            if (!tmp.renameTo(autosaveFile)) {
                autosaveFile.delete()
                tmp.renameTo(autosaveFile)
            }
            // 자동 저장본이 마지막으로 저장한 파일과 같은 내용인지 기록 (복원 시 * 표시 판단)
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean(KEY_AUTOSAVE_CLEAN, clean).commit()
            ui.post { autosavedVersion = version }
        } catch (e: Exception) {
            Log.e(TAG, "autosave failed", e)
        }
    }

    private fun restoreAutosave() {
        val f = autosaveFile
        if (!f.exists()) return
        io.execute {
            try {
                // 최대 텍스처 크기를 아직 모르면 넉넉히 두고, 렌더러가 실패 시 오류를 알립니다.
                val doc = f.inputStream().use { ProjectIO.readDfp(it, 16384) }
                ui.post {
                    renderer.loadDocument(doc) { version ->
                        // 자동 저장본은 "마지막으로 저장한 파일"과 같다고 보지 않습니다.
                        // 파일로 저장한 적이 없다면 제목에 * 가 남아 저장을 유도합니다.
                        val clean = getSharedPreferences("settings", MODE_PRIVATE).getBoolean(KEY_AUTOSAVE_CLEAN, false)
                        if (currentUri != null && clean) savedVersion = version
                        autosavedVersion = version
                        updateTitle()
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "autosave restore failed", e)
            }
        }
    }

    private fun displayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }

    companion object {
        private const val TAG = "DFPaint"
        private const val REQ_OPEN = 10
        private const val REQ_SAVE = 11
        private const val REQ_EXPORT = 12
        private const val REQ_TIP = 13
        private const val REQ_EXPORT_PSD = 14
        private const val KEY_AUTOSAVE_CLEAN = "autosaveClean"
        private val ROTATE_STEP = (Math.PI / 12).toFloat()
    }
}
