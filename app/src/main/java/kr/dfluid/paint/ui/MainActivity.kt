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
import kr.dfluid.paint.document.CanvasEdit
import kr.dfluid.paint.document.DocumentData
import kr.dfluid.paint.document.NodeInfo
import kr.dfluid.paint.document.ProjectIO
import kr.dfluid.paint.document.PsdIO
import kr.dfluid.paint.engine.CanvasRenderer
import kr.dfluid.paint.engine.FilterKind
import kr.dfluid.paint.engine.FilterSpec
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
    override val ruler = kr.dfluid.paint.input.GuideRuler()
    private lateinit var rulerBtn: ImageView
    private var rulerPlaced = false
    override fun onRulerChanged() = overlay.invalidate()
    private lateinit var symBtn: ImageView
    private lateinit var quickMaskBtn: ImageView
    private var straightLineOn = false
    override val straightLine: Boolean get() = straightLineOn
    override val postSmoothing: Float get() = settings.postSmoothing
    override val shapeKind: Int get() = settings.shapeKind
    override val balloonFillColor: Int get() = settings.secondaryColor
    override val shapeFill: Int get() = settings.shapeFill
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
    /** 열려 있는 필터 대화상자 (미리보기 중) */
    private var filterDialog: android.app.AlertDialog? = null
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
    private var distortBtnRef: ImageView? = null
    // ---- 애니메이션 타임라인 ----
    private lateinit var animBar: LinearLayout
    /** 선택 범위 런처: 선택 영역이 있을 때 아래에 뜨는 빠른 동작 */
    private lateinit var selBar: LinearLayout
    // ---- 내비게이터 ----
    private lateinit var colorSliders: ColorSliders
    private lateinit var grayBtn: ImageView
    private lateinit var gridBtn: ImageView
    // ---- 퀵 액세스 ----
    private lateinit var quickPanel: LinearLayout
    private lateinit var quickGrid: LinearLayout
    private lateinit var quickBtn: ImageView
    private lateinit var navPanel: LinearLayout
    private lateinit var navView: NavigatorView
    private lateinit var navBtn: ImageView
    // ---- 서브 뷰 ----
    private lateinit var subPanel: LinearLayout
    private lateinit var subImage: RefImageView
    private lateinit var subPickBtn: ImageView
    private lateinit var subBtn: ImageView
    /** 다시 만들어도 남는 참고 이미지 (테마를 바꿔 UI를 다시 만들 때) */
    private var subBitmap: android.graphics.Bitmap? = null
    private lateinit var frameStrip: LinearLayout
    private lateinit var frameLabel: TextView
    private lateinit var playBtn: ImageView
    private lateinit var onionBtn: ImageView
    private lateinit var animBtn: ImageView
    private var animExists = false
    private var animFrame = 0
    private var animCount = 0
    private var animPlaying = false
    /** 타임라인을 보이게 할지 (애니메이션 폴더가 있을 때) */
    private var timelineOpen = true
    private val playTick = object : Runnable {
        override fun run() {
            if (!animPlaying || animCount <= 0) return
            renderer.setFrame(animFrame + 1, playing = true)
            ui.postDelayed(this, (1000f / settings.animFps).toLong())
        }
    }
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
    private lateinit var rootView: FrameLayout
    private var panels: FloatingPanels? = null
    // 패널 접기: 접으면 숨길 뷰들과 접기/펼치기 버튼
    private val topBarBody = ArrayList<View>()
    private val toolBarBody = ArrayList<View>()
    private lateinit var topToggle: ImageView
    private lateinit var toolToggle: ImageView
    private lateinit var panelToggle: ImageView
    private lateinit var toolCard: Ui.Card
    private lateinit var colorCard: Ui.Card
    private lateinit var layerCard: Ui.Card
    private lateinit var colorPicker: ColorPickerView
    private var lastLiveIds: Set<Int> = emptySet()
    private var lastCanUndo = false
    private var lastCanRedo = false
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
        Ui.applyTheme(resolveDark())
        renderer = CanvasRenderer(this)
        renderer.setBackdrop(Ui.CANVAS_BG)
        renderer.tipProvider = { id -> library.tip(id) }
        renderer.pickFromLayer = settings.pickFromLayer
        renderer.defaultBackground = settings.newCanvasBackground
        canvasView = CanvasView(this, renderer)
        canvasView.host = this
        overlay = OverlayView(this, canvasView.viewport)
        overlay.symmetry = symmetry
        overlay.listener = this
        overlay.stylusSeen = { canvasView.stylusSeen }
        overlay.ruler = ruler
        rulerPlaced = ruler.decode(settings.rulerState)

        // 캔버스와 오버레이는 한 번만 붙이고, 둘레 UI(buildChrome)만 테마 바뀔 때 다시 만듭니다.
        rootView = FrameLayout(this)
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        rootView.addView(canvasView, match)
        rootView.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(rootView)
        buildChrome()
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
        ui.removeCallbacks(autosaveTick)
        ui.postDelayed(autosaveTick, AUTOSAVE_CHECK_MS)
    }

    // ---- 주기적 자동 저장 ----
    // 앱이 백그라운드로 갈 때만 저장하면, 그리는 중 앱이 죽었을 때 그 사이 작업을 잃습니다.
    // 손을 뗀 채 잠시 쉬고 있을 때(펜·손가락이 닿아 있지 않고 IDLE_MS 이상 입력 없음) 주기적으로 저장합니다.

    private var lastInteraction = 0L
    private var pointerDown = false
    private var lastAutosaveAt = 0L
    private var autosaving = false

    private val autosaveTick = object : Runnable {
        override fun run() {
            maybeAutosave()
            ui.postDelayed(this, AUTOSAVE_CHECK_MS)
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        lastInteraction = android.os.SystemClock.uptimeMillis()
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> pointerDown = true
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> pointerDown = false
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun maybeAutosave() {
        val now = android.os.SystemClock.uptimeMillis()
        if (autosaving && now - lastAutosaveAt > 60_000) autosaving = false // 콜백이 오지 않은 경우 대비
        if (autosaving || pointerDown || transforming || moveSession) return
        if (lastInteraction == 0L || renderer.version == autosavedVersion) return
        if (now - lastInteraction < IDLE_MS || now - lastAutosaveAt < AUTOSAVE_INTERVAL_MS) return
        autosaving = true
        lastAutosaveAt = now
        val saved = savedVersion
        renderer.captureDocument { data, composite, version ->
            io.execute {
                writeAutosave(data, composite, version, clean = version == saved)
                ui.post { autosaving = false }
            }
        }
    }

    override fun onPause() {
        ui.removeCallbacks(autosaveTick)
        dispatcher.releaseAll()
        settings.rulerState = if (rulerPlaced) ruler.encode() else null
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

    /** 테마: 설정(시스템/라이트/다크)과 시스템 다크 모드로 결정 */
    private fun resolveDark(): Boolean = when (settings.themeMode) {
        AppSettings.THEME_LIGHT -> false
        AppSettings.THEME_DARK -> true
        else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (settings.themeMode == AppSettings.THEME_SYSTEM && resolveDark() != Ui.dark) rebuildUi()
    }

    /**
     * 테마가 바뀌면 캔버스(GL)는 그대로 두고 주변 UI만 다시 만듭니다.
     * GLSurfaceView를 떼었다 붙이면 GL 컨텍스트를 잃으므로 절대 떼지 않습니다.
     */
    private fun rebuildUi() {
        val perfOn = perfPanel.visibility == View.VISIBLE
        val bench = benchText.text
        Ui.applyTheme(resolveDark())
        renderer.setBackdrop(Ui.CANVAS_BG)
        for (i in rootView.childCount - 1 downTo 0) {
            val v = rootView.getChildAt(i)
            if (v !== canvasView && v !== overlay) rootView.removeViewAt(i)
        }
        buildChrome()
        perfPanel.visibility = if (perfOn) View.VISIBLE else View.GONE
        Ui.setOn(perfBtn, perfOn)
        benchText.text = bench
        if (!uiVisible) {
            uiVisible = true
            toggleUi()
        }
        setTool(tool)
        updateSwatches()
        updateTitle()
        updateSymmetryButton()
        Ui.setOn(lineBtn, straightLineOn)
        updateRulerButton()
        onHistoryChanged(lastCanUndo, lastCanRedo)
        layerPanel.update(lastNodes, lastActiveId, lastLiveIds)
        renderer.requestThumbnails()
        onViewChanged()
        transformBar.visibility = if (transforming && !moveSession) View.VISIBLE else View.GONE
        onAnimation(animExists, animFrame, animCount)
    }

    private fun buildChrome() {
        val root = rootView
        root.setBackgroundColor(Ui.BG)
        tips = Ui.Tips { a -> shortcuts.get(a).firstOrNull()?.label() }
        toolButtons.clear()
        layerPanel = LayerPanel(this, renderer, tips)
        toolOptions = ToolOptions(this, this)
        val ctx = this
        val m = Ui.dp(ctx, 8f)

        // ---- 상단 바: 기능별 묶음 (테두리 + 아래 이름표) ----
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(m, m / 2 + 2, m, m / 2)
            background = Ui.rounded(Ui.PANEL, Ui.dp(ctx, 12f).toFloat(), Ui.dp(ctx, 1f), Ui.BORDER)
        }
        titleLabel = Ui.text(ctx, "", 13f, Ui.TEXT, bold = true).apply {
            setPadding(Ui.dp(ctx, 4f), 0, Ui.dp(ctx, 12f), Ui.dp(ctx, 16f))
            maxWidth = Ui.dp(ctx, 160f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val topGrip = FloatingPanels.Grip(ctx, horizontal = false)
        bar.addView(topGrip, LinearLayout.LayoutParams(Ui.dp(ctx, 16f), Ui.dp(ctx, 40f)).apply {
            gravity = Gravity.CENTER_VERTICAL
            rightMargin = Ui.dp(ctx, 2f)
        })
        topToggle = Ui.iconButton(ctx, R.drawable.ic_chevron_left, "상단 바 접기/펼치기", 32f, ghost = true) {
            settings.topBarCollapsed = !settings.topBarCollapsed
            settings.save()
            applyCollapse()
        }
        bar.addView(topToggle, LinearLayout.LayoutParams(Ui.dp(ctx, 32f), Ui.dp(ctx, 40f)).apply {
            gravity = Gravity.CENTER_VERTICAL
            rightMargin = Ui.dp(ctx, 4f)
        })
        val topBodyStart = bar.childCount
        bar.addView(titleLabel)
        var group = LinearLayout(ctx)
        fun group(label: String): TextView {
            val icons = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                val p = Ui.dp(ctx, 2f)
                setPadding(p, p, p, p)
                background = Ui.rounded(Ui.CARD, Ui.dp(ctx, 8f).toFloat(), Ui.dp(ctx, 1f), Ui.BORDER)
            }
            val name = Ui.text(ctx, label, 10.5f, Ui.MUTED).apply { setPadding(Ui.dp(ctx, 5f), Ui.dp(ctx, 2f), 0, 0) }
            bar.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(icons)
                addView(name)
            }, Ui.wrap().apply { rightMargin = Ui.dp(ctx, 8f) })
            group = icons
            return name
        }
        fun barBtn(icon: Int, tip: String, action: Action? = null, onClick: () -> Unit): ImageView {
            val b = tips.bind(Ui.iconButton(ctx, icon, tip, 36f, ghost = true, onClick = onClick), tip, action)
            group.addView(b, Ui.square(ctx, 36f))
            return b
        }
        fun act(icon: Int, tip: String, action: Action) = barBtn(icon, tip, action) { onShortcut(action) }

        group("파일")
        act(R.drawable.ic_file_new, "새 캔버스", Action.FILE_NEW)
        barBtn(R.drawable.ic_file_open, "열기 (파일 · 자동 저장 기록)", Action.FILE_OPEN) {
            Ui.dialog(this)
                .setTitle("열기")
                .setItems(arrayOf(tips.text("파일 열기…", Action.FILE_OPEN), "자동 저장 기록에서 열기 (최근 5개, 10분 간격)…")) { _, i ->
                    if (i == 0) onShortcut(Action.FILE_OPEN) else chooseAutosaveHistory()
                }
                .setNegativeButton("닫기", null)
                .show()
        }
        act(R.drawable.ic_file_save, "저장", Action.FILE_SAVE)
        barBtn(R.drawable.ic_image_add, "이미지를 레이어로 가져오기 (가져온 뒤 크기·위치 맞추기)") { importImageLayer() }
        barBtn(R.drawable.ic_file_export, "내보내기 (PNG · JPEG · PSD · GIF)") { chooseExport() }
        group("편집")
        undoBtn = barBtn(R.drawable.ic_undo, "실행취소", Action.UNDO) { renderer.undo() }
        redoBtn = barBtn(R.drawable.ic_redo, "다시실행", Action.REDO) { renderer.redo() }
        barBtn(R.drawable.ic_canvas, "캔버스 (이미지 크기 · 캔버스 크기 · 회전 · 반전)") { chooseCanvasEdit() }
        barBtn(R.drawable.ic_adjust, "필터 · 색조 보정 (색조/채도/명도, 밝기/대비, 흐리기 등)") { chooseFilter() }
        group("선택")
        act(R.drawable.ic_transform, "자유 변형", Action.TRANSFORM)
        act(R.drawable.ic_deselect, "선택 해제", Action.SELECT_NONE)
        quickMaskBtn = barBtn(R.drawable.ic_quick_mask, "퀵 마스크 (선택 영역을 붓·지우개로 칠해서 고침)", Action.SELECT_QUICK_MASK) { toggleQuickMask() }
        Ui.setOn(quickMaskBtn, lastNodes.any { it.props.quickMask })
        viewLabel = group("보기")
        act(R.drawable.ic_view_fit, "화면에 맞춤", Action.VIEW_FIT)
        act(R.drawable.ic_view_rotate_reset, "회전 초기화", Action.VIEW_ROTATE_RESET)
        act(R.drawable.ic_view_flip, "화면 좌우 반전", Action.VIEW_FLIP)
        gridBtn = act(R.drawable.ic_grid, "격자 표시 (간격 고르기)", Action.VIEW_GRID)
        Ui.setOn(gridBtn, settings.gridStep > 0)
        overlay.gridStep = settings.gridStep
        grayBtn = act(R.drawable.ic_view_gray, "흑백 보기 (명암 확인, 그림은 그대로)", Action.VIEW_GRAY)
        Ui.setOn(grayBtn, renderer.grayView)
        subBtn = barBtn(R.drawable.ic_subview, "서브 뷰 (참고 이미지 창)") { toggleSubView() }
        navBtn = barBtn(R.drawable.ic_navigator, "내비게이터 (전체 그림, 눌러서 이동)") { toggleNavigator() }
        group("그리기 보조")
        lineBtn = barBtn(R.drawable.ic_ruler, "직선 자 (시작점에서 끝점까지 곧은 선)") { toggleStraightLine() }
        symBtn = barBtn(R.drawable.ic_sym_vertical, "대칭") { cycleSymmetry() }
        rulerBtn = barBtn(R.drawable.ic_ruler_persp, "원근 자 · 동심원 자") { chooseRuler() }
        animBtn = barBtn(R.drawable.ic_film, "애니메이션 타임라인 (없으면 애니메이션 폴더를 만듦)") {
            if (!animExists) {
                timelineOpen = true
                renderer.createAnimation()
            } else {
                timelineOpen = !timelineOpen
                onAnimation(animExists, animFrame, animCount)
            }
        }
        updateRulerButton()
        group("앱")
        perfBtn = barBtn(R.drawable.ic_gauge, "성능 측정 (FPS·펜 지연·부하 테스트)") { togglePerf() }
        quickBtn = barBtn(R.drawable.ic_quick, "퀵 액세스 (자주 쓰는 기능 모음, + 로 추가 · 길게 눌러 빼기)") { toggleQuick() }
        barBtn(R.drawable.ic_keyboard, "단축키 설정") { openShortcutSettings() }
        barBtn(R.drawable.ic_settings, "설정 (테마·필압 등)") { showSettings() }
        panelBtn = barBtn(R.drawable.ic_panel, "오른쪽 패널 접기/펼치기") {
            settings.rightPanelCollapsed = !settings.rightPanelCollapsed
            settings.save()
            applyCollapse()
        }
        topBarBody.clear()
        for (i in topBodyStart until bar.childCount) topBarBody.add(bar.getChildAt(i))
        Ui.setEnabled(undoBtn, false)
        Ui.setEnabled(redoBtn, false)
        updateSymmetryButton()

        topBar = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        // 위치는 FloatingPanels가 view.x/y로 정합니다 (기본: 가로 가운데, 위).
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        // ---- 왼쪽 도구 막대: 그리기 / 선택·이동 / 칠하기 / 기타로 나눔 ----
        val tools = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(m / 2, m / 2 + 2, m / 2, m)
            background = Ui.rounded(Ui.PANEL, Ui.dp(ctx, 12f).toFloat(), Ui.dp(ctx, 1f), Ui.BORDER)
        }
        val toolGrip = FloatingPanels.Grip(ctx, horizontal = true)
        tools.addView(toolGrip, LinearLayout.LayoutParams(Ui.dp(ctx, 40f), Ui.dp(ctx, 16f)))
        toolToggle = Ui.iconButton(ctx, R.drawable.ic_chevron_up, "도구 막대 접기/펼치기 (접으면 지금 도구만 표시)", 28f, ghost = true) {
            settings.toolBarCollapsed = !settings.toolBarCollapsed
            settings.save()
            applyCollapse()
        }
        toolToggle.setPadding(Ui.dp(ctx, 8f), Ui.dp(ctx, 2f), Ui.dp(ctx, 8f), Ui.dp(ctx, 2f))
        tools.addView(toolToggle, LinearLayout.LayoutParams(Ui.dp(ctx, 40f), Ui.dp(ctx, 26f)).apply { bottomMargin = Ui.dp(ctx, 2f) })
        val toolBodyStart = tools.childCount
        val groups = listOf(
            listOf(Tool.PEN, Tool.PENCIL, Tool.AIRBRUSH, Tool.MARKER, Tool.ERASER, Tool.BLEND, Tool.LINEFIX),
            listOf(Tool.SELECT, Tool.MOVE),
            listOf(Tool.FILL, Tool.GRADIENT, Tool.SHAPE, Tool.TEXT),
            listOf(Tool.EYEDROPPER, Tool.HAND),
        )
        groups.forEachIndexed { gi, list ->
            if (gi > 0) tools.addView(Ui.shortDivider(ctx, 28f))
            for (t in list) {
                val b = tips.bind(Ui.iconButton(ctx, toolIcon(t), t.label, 42f, ghost = true) { setTool(t) }, t.label, toolAction(t))
                toolButtons[t] = b
                tools.addView(b, Ui.square(ctx, 42f).apply { bottomMargin = Ui.dp(ctx, 2f) })
            }
        }
        tools.addView(Ui.shortDivider(ctx, 28f))
        val swatchSize = Ui.dp(ctx, 30f)
        val swatches = FrameLayout(ctx)
        secondarySwatch = View(ctx).apply { setOnClickListener { onShortcut(Action.COLOR_SWAP) } }
        primarySwatch = View(ctx).apply {
            setOnClickListener { Dialogs.colorPicker(ctx, settings.primaryColor) { setPrimary(it) } }
        }
        tips.bind(secondarySwatch, "보조색 (누르면 주색과 바꾸기)", Action.COLOR_SWAP)
        tips.bind(primarySwatch, "주색 (누르면 색 선택)")
        swatches.addView(secondarySwatch, FrameLayout.LayoutParams(swatchSize, swatchSize, Gravity.BOTTOM or Gravity.END))
        swatches.addView(primarySwatch, FrameLayout.LayoutParams(swatchSize, swatchSize, Gravity.TOP or Gravity.START))
        tools.addView(swatches, LinearLayout.LayoutParams(swatchSize + Ui.dp(ctx, 12f), swatchSize + Ui.dp(ctx, 12f)))
        toolBarBody.clear()
        for (i in toolBodyStart until tools.childCount) toolBarBody.add(tools.getChildAt(i))
        toolBar = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(tools)
        }
        // 기본: 왼쪽, 세로 가운데
        root.addView(toolBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        // ---- 오른쪽 패널: 카드 3장 (도구 속성 / 색 / 레이어) ----
        val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val panelGrip = FloatingPanels.Grip(ctx, horizontal = true)
        panelToggle = Ui.iconButton(ctx, R.drawable.ic_chevron_up, "오른쪽 패널 접기/펼치기", 24f, ghost = true) {
            settings.rightPanelCollapsed = !settings.rightPanelCollapsed
            settings.save()
            applyCollapse()
        }.apply { val p = Ui.dp(ctx, 3f); setPadding(p, p, p, p) }
        panel.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 6f), 0, Ui.dp(ctx, 2f), 0)
            background = Ui.rounded(Ui.PANEL, Ui.dp(ctx, 8f).toFloat(), Ui.dp(ctx, 1f), Ui.BORDER)
            addView(panelGrip, LinearLayout.LayoutParams(Ui.dp(ctx, 40f), Ui.dp(ctx, 22f)))
            addView(panelToggle, Ui.square(ctx, 24f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = Ui.dp(ctx, 4f)
        })
        toolCard = Ui.Card(ctx, "도구 속성", settings.cardToolOpen) { open -> settings.cardToolOpen = open; relayoutCards() }
        toolOptions.titleView = toolCard.titleView
        toolCard.body.addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(toolOptions.view)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        colorCard = Ui.Card(ctx, "색", settings.cardColorOpen) { open -> settings.cardColorOpen = open; relayoutCards() }
        colorPicker = ColorPickerView(ctx).apply {
            heightRatio = 0.42f
            color = settings.primaryColor
            onColorChanged = { c -> livePrimary(c) }
            onColorCommitted = { c -> setPrimary(c) }
        }
        // 탭: 서클 / 사각형 / 중간색 / 컬러 세트
        val mixView = MixGridView(ctx, settings.mixCorners) { settings.primaryColor }.apply {
            onPick = { c -> setPrimary(c) }
            onCornersChanged = { settings.save(); showHud("중간색 모서리를 주색으로 바꿨습니다") }
        }
        val setView = SwatchGridView(ctx, settings.palette) { settings.primaryColor }.apply {
            onPick = { c -> setPrimary(c) }
            onChanged = { settings.save() }
        }
        colorSliders = ColorSliders(ctx).apply {
            color = settings.primaryColor
            onLive = { c -> livePrimary(c) }
            onCommit = { c -> setPrimary(c) }
        }
        val colorTabs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val tabButtons = ArrayList<TextView>()
        fun showColorTab(t: Int) {
            settings.colorTab = t
            colorPicker.wheel = t == 0
            colorPicker.visibility = if (t <= 1) View.VISIBLE else View.GONE
            mixView.visibility = if (t == 2) View.VISIBLE else View.GONE
            setView.visibility = if (t == 3) View.VISIBLE else View.GONE
            colorSliders.view.visibility = if (t == 4) View.VISIBLE else View.GONE
            tabButtons.forEachIndexed { i, b -> Ui.setOn(b, i == t) }
        }
        listOf("서클", "사각형", "중간색", "세트", "슬라이더").forEachIndexed { i, label ->
            val b = Ui.button(ctx, label) { showColorTab(i); settings.save() }
            tabButtons.add(b)
            colorTabs.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Ui.dp(ctx, 3f) })
        }
        tips.bind(tabButtons[2], "중간색: 칸을 누르면 그 색, 네 모서리를 길게 누르면 주색으로 바꿈")
        tips.bind(tabButtons[3], "컬러 세트: + = 주색 추가, 누르면 고르기, 길게 누르면 삭제")
        colorCard.body.addView(colorTabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = Ui.dp(ctx, 6f)
        })
        colorCard.body.addView(colorPicker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        colorCard.body.addView(mixView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        colorCard.body.addView(setView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        colorCard.body.addView(colorSliders.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        showColorTab(settings.colorTab)
        colorCard.body.addView(toolOptions.recentView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(ctx, 8f)
        })

        layerCard = Ui.Card(ctx, "레이어", settings.cardLayerOpen) { open -> settings.cardLayerOpen = open; relayoutCards() }
        layerPanel.headerActions.forEach { layerCard.addAction(it) }
        layerCard.body.addView(layerPanel.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        panel.addView(toolCard.view)
        panel.addView(colorCard.view)
        panel.addView(layerCard.view)
        rightPanel = panel
        relayoutCards()
        // 오른쪽 패널은 가로로만 옮깁니다 (세로는 상단 바 아래 ~ 화면 아래).
        root.addView(rightPanel, FrameLayout.LayoutParams(Ui.dp(ctx, 300f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.START).apply {
            setMargins(0, Ui.dp(ctx, 66f), 0, m)
        })

        // ---- 패널 끌어 옮기기 + 스냅 ----
        panels?.detach()
        val fp = FloatingPanels(root, guides = { x, y -> overlay.showLayoutGuides(x, y) }, onChanged = { fitRightPanel() })
        panels = fp
        fp.add(topBar, topGrip, moveY = true, settings.topBarFx, settings.topBarFy) { fx, fy ->
            settings.topBarFx = fx; settings.topBarFy = fy; settings.save()
        }
        fp.add(toolBar, toolGrip, moveY = true, settings.toolBarFx, settings.toolBarFy) { fx, fy ->
            settings.toolBarFx = fx; settings.toolBarFy = fy; settings.save()
        }
        fp.add(rightPanel, panelGrip, moveY = false, settings.rightPanelFx, 0f) { fx, _ ->
            settings.rightPanelFx = fx; settings.save()
        }
        if (settings.panelLock) listOf(topGrip, toolGrip, panelGrip).forEach { it.visibility = View.GONE }
        applyCollapse()

        // ---- 변형 확정/취소 바 ----
        val tb = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(m, m / 2, m, m / 2)
            background = Ui.rounded(Ui.PANEL, Ui.dp(ctx, 12f).toFloat(), Ui.dp(ctx, 1f), Ui.BORDER)
            visibility = View.GONE
        }
        fun tbBtn(icon: Int, tip: String, onClick: () -> Unit): ImageView {
            val b = Ui.iconButton(ctx, icon, tip, ghost = true, onClick = onClick)
            tb.addView(b, Ui.square(ctx, 40f).apply { rightMargin = Ui.dp(ctx, 4f) })
            return b
        }
        tb.addView(Ui.text(ctx, "자유 변형", 13f, bold = true).apply { setPadding(m, 0, m * 2, 0) })
        tbBtn(R.drawable.ic_flip_h, "좌우 반전") { overlay.flip(true) }
        tbBtn(R.drawable.ic_flip_v, "상하 반전") { overlay.flip(false) }
        tbBtn(R.drawable.ic_rotate_90, "90° 회전") { overlay.rotateBy((Math.PI / 2).toFloat()) }
        lateinit var distortBtn: ImageView
        distortBtn = tbBtn(R.drawable.ic_distort, "자유 모서리 (원근 변형: 모서리를 따로 끌기)") {
            overlay.setDistort(!overlay.distort)
            Ui.setOn(distortBtn, overlay.distort)
        }
        distortBtnRef = distortBtn
        tb.addView(Ui.hspace(ctx, 8f))
        Ui.setOn(tbBtn(R.drawable.ic_check, "확정 (Enter)") { commitTransform() }, true)
        tbBtn(R.drawable.ic_close, "취소 (Esc)") { cancelTransform() }
        transformBar = tb
        root.addView(transformBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Ui.dp(ctx, 16f)
        })

        // ---- 퀵 액세스 ----
        val quickGrip = FloatingPanels.Grip(ctx, horizontal = true)
        quickGrid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        quickPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(ctx, 6f)
            setPadding(p, p / 2, p, p)
            background = Ui.rounded(Ui.PANEL, Ui.dp(ctx, 10f).toFloat(), Ui.dp(ctx, 1f), Ui.BORDER)
            visibility = if (settings.quickOpen) View.VISIBLE else View.GONE
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(quickGrip, LinearLayout.LayoutParams(Ui.dp(ctx, 36f), Ui.dp(ctx, 22f)))
                addView(Ui.text(ctx, "퀵 액세스", 12.5f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(tips.bind(Ui.iconButton(ctx, R.drawable.ic_close, "닫기", 28f, ghost = true) { toggleQuick() }, "닫기"), Ui.square(ctx, 28f))
            })
            addView(quickGrid, LinearLayout.LayoutParams(Ui.dp(ctx, 252f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 4f) })
        }
        rebuildQuick()
        root.addView(quickPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        fp.add(quickPanel, quickGrip, moveY = true, settings.quickFx, settings.quickFy) { fx, fy ->
            settings.quickFx = fx; settings.quickFy = fy; settings.save()
        }
        Ui.setOn(quickBtn, settings.quickOpen)

        // ---- 내비게이터 ----
        val navGrip = FloatingPanels.Grip(ctx, horizontal = true)
        navView = NavigatorView(ctx, canvasView.viewport).apply {
            onMoved = { canvasView.pushView() }
        }
        navPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(ctx, 6f)
            setPadding(p, p / 2, p, p)
            background = Ui.rounded(Ui.PANEL, Ui.dp(ctx, 10f).toFloat(), Ui.dp(ctx, 1f), Ui.BORDER)
            visibility = if (settings.navOpen) View.VISIBLE else View.GONE
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(navGrip, LinearLayout.LayoutParams(Ui.dp(ctx, 36f), Ui.dp(ctx, 22f)))
                addView(Ui.text(ctx, "내비게이터", 12.5f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(tips.bind(Ui.iconButton(ctx, R.drawable.ic_view_fit, "화면에 맞춤", 28f, ghost = true) { onShortcut(Action.VIEW_FIT) }, "화면에 맞춤"), Ui.square(ctx, 28f))
                addView(tips.bind(Ui.iconButton(ctx, R.drawable.ic_close, "닫기", 28f, ghost = true) { toggleNavigator() }, "닫기"), Ui.square(ctx, 28f))
            })
            addView(navView, LinearLayout.LayoutParams(Ui.dp(ctx, 220f), Ui.dp(ctx, 170f)).apply { topMargin = Ui.dp(ctx, 4f) })
        }
        root.addView(navPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        fp.add(navPanel, navGrip, moveY = true, settings.navFx, settings.navFy) { fx, fy ->
            settings.navFx = fx; settings.navFy = fy; settings.save()
        }
        Ui.setOn(navBtn, settings.navOpen)
        renderer.navigatorOn = settings.navOpen

        // ---- 선택 범위 런처 ----
        val sb = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = Ui.dp(ctx, 6f)
            setPadding(p, p / 2, p, p / 2)
            background = Ui.rounded(Ui.PANEL, Ui.dp(ctx, 12f).toFloat(), Ui.dp(ctx, 1f), Ui.BORDER)
            visibility = View.GONE
        }
        fun sbBtn(icon: Int, tip: String, action: Action?, f: () -> Unit) {
            val b = tips.bind(Ui.iconButton(ctx, icon, tip, 38f, ghost = true, onClick = f), tip, action)
            sb.addView(b, Ui.square(ctx, 38f).apply { rightMargin = Ui.dp(ctx, 2f) })
        }
        fun sbText(label: String, tip: String, action: Action?, f: () -> Unit) {
            val b = tips.bind(Ui.button(ctx, label) { f() }, tip, action)
            sb.addView(b, Ui.wrap().apply { rightMargin = Ui.dp(ctx, 3f) })
        }
        sbBtn(R.drawable.ic_deselect, "선택 해제", Action.SELECT_NONE) { onShortcut(Action.SELECT_NONE) }
        sbText("반전", "선택 반전", Action.SELECT_INVERT) { onShortcut(Action.SELECT_INVERT) }
        sbText("확장", "선택 영역 확장 (선택 도구의 범위만큼)", null) { modifySelection(kr.dfluid.paint.engine.SelModify.GROW, settings.selModifyPx) }
        sbText("축소", "선택 영역 축소", null) { modifySelection(kr.dfluid.paint.engine.SelModify.SHRINK, settings.selModifyPx) }
        sb.addView(Ui.shortDivider(ctx, 1f), LinearLayout.LayoutParams(Ui.dp(ctx, 1f), Ui.dp(ctx, 26f)).apply { rightMargin = Ui.dp(ctx, 4f); leftMargin = Ui.dp(ctx, 2f) })
        sbText("복사", "복사", Action.EDIT_COPY) { onShortcut(Action.EDIT_COPY) }
        sbText("잘라내기", "잘라내기", Action.EDIT_CUT) { onShortcut(Action.EDIT_CUT) }
        sbText("붙여넣기", "붙여넣기 (새 레이어)", Action.EDIT_PASTE) { onShortcut(Action.EDIT_PASTE) }
        sb.addView(Ui.shortDivider(ctx, 1f), LinearLayout.LayoutParams(Ui.dp(ctx, 1f), Ui.dp(ctx, 26f)).apply { rightMargin = Ui.dp(ctx, 4f); leftMargin = Ui.dp(ctx, 2f) })
        sbBtn(R.drawable.ic_layer_clear, "지우기", Action.LAYER_CLEAR) { onShortcut(Action.LAYER_CLEAR) }
        sbBtn(R.drawable.ic_tool_fill, "주색으로 채우기", Action.FILL_SELECTION) { onShortcut(Action.FILL_SELECTION) }
        sbBtn(R.drawable.ic_transform, "자유 변형", Action.TRANSFORM) { onShortcut(Action.TRANSFORM) }
        sbBtn(R.drawable.ic_quick_mask, "퀵 마스크", Action.SELECT_QUICK_MASK) { onShortcut(Action.SELECT_QUICK_MASK) }
        sbText("컷", "컷 나누기: 선택 범위로 컷 폴더 만들기 (컷 영역 + 클리핑된 그림 + 테두리)", null) { chooseFrame() }
        sbText("저장", "선택 영역 저장 (나중에 불러오기)", null) {
            renderer.storeSelection { names -> showHud("${names.last()}(으)로 저장했습니다") }
        }
        selBar = sb
        root.addView(selBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = Ui.dp(ctx, 16f)
            leftMargin = Ui.dp(ctx, 90f)
        })
        updateSelBar()

        // ---- 서브 뷰 (참고 이미지) ----
        val subGrip = FloatingPanels.Grip(ctx, horizontal = true)
        subImage = RefImageView(ctx).apply {
            onPick = { c -> setPrimary(c); showHud("서브 뷰에서 색을 가져왔습니다") }
            subBitmap?.let { setImage(it) }
        }
        val subHead = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(subGrip, LinearLayout.LayoutParams(Ui.dp(ctx, 36f), Ui.dp(ctx, 22f)))
            addView(Ui.text(ctx, "서브 뷰", 12.5f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        fun subAct(icon: Int, tip: String, f: () -> Unit) = tips.bind(Ui.iconButton(ctx, icon, tip, 30f, ghost = true, onClick = f), tip).also {
            subHead.addView(it, Ui.square(ctx, 30f).apply { leftMargin = Ui.dp(ctx, 2f) })
        }
        subAct(R.drawable.ic_file_open, "이미지 열기") { openSubImage() }
        subPickBtn = subAct(R.drawable.ic_tool_eyedropper, "스포이드 (누른 곳의 색을 주색으로)") {
            subImage.picking = !subImage.picking
            Ui.setOn(subPickBtn, subImage.picking)
        }
        subAct(R.drawable.ic_view_fit, "창에 맞춤 (두 번 탭)") { subImage.fit() }
        subAct(R.drawable.ic_close, "닫기") { toggleSubView() }
        val subResize = View(ctx).apply {
            background = Ui.rounded(Ui.BORDER, Ui.dp(ctx, 3f).toFloat())
            Ui.setTip(this, "끌어서 크기 조절")
        }
        subPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(ctx, 6f)
            setPadding(p, p / 2, p, p)
            background = Ui.rounded(Ui.PANEL, Ui.dp(ctx, 10f).toFloat(), Ui.dp(ctx, 1f), Ui.BORDER)
            visibility = if (settings.subOpen) View.VISIBLE else View.GONE
            addView(subHead, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(subImage, LinearLayout.LayoutParams(Ui.dp(ctx, settings.subW.toFloat()), Ui.dp(ctx, settings.subH.toFloat())).apply {
                topMargin = Ui.dp(ctx, 4f)
            })
            addView(subResize, LinearLayout.LayoutParams(Ui.dp(ctx, 28f), Ui.dp(ctx, 8f)).apply {
                gravity = Gravity.END
                topMargin = Ui.dp(ctx, 4f)
            })
        }
        // 오른쪽 아래 막대를 끌면 이미지 창 크기가 바뀝니다.
        var rsX = 0f; var rsY = 0f; var rsW = 0; var rsH = 0
        subResize.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    rsX = e.rawX; rsY = e.rawY
                    rsW = subImage.layoutParams.width; rsH = subImage.layoutParams.height
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val lp = subImage.layoutParams
                    lp.width = (rsW + (e.rawX - rsX)).toInt().coerceIn(Ui.dp(ctx, 160f), Ui.dp(ctx, 900f))
                    lp.height = (rsH + (e.rawY - rsY)).toInt().coerceIn(Ui.dp(ctx, 120f), Ui.dp(ctx, 900f))
                    subImage.layoutParams = lp
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val d = resources.displayMetrics.density
                    settings.subW = (subImage.layoutParams.width / d).toInt()
                    settings.subH = (subImage.layoutParams.height / d).toInt()
                    settings.save()
                }
            }
            true
        }
        root.addView(subPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        fp.add(subPanel, subGrip, moveY = true, settings.subFx, settings.subFy) { fx, fy ->
            settings.subFx = fx; settings.subFy = fy; settings.save()
        }
        Ui.setOn(subBtn, settings.subOpen)
        if (settings.subOpen && subBitmap == null) loadSubImage()

        // ---- 애니메이션 타임라인 바 ----
        val ab = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(m, m / 2, m, m / 2)
            background = Ui.rounded(Ui.PANEL, Ui.dp(ctx, 12f).toFloat(), Ui.dp(ctx, 1f), Ui.BORDER)
            visibility = View.GONE
        }
        fun abBtn(icon: Int, tip: String, onClick: () -> Unit): ImageView {
            val b = tips.bind(Ui.iconButton(ctx, icon, tip, 38f, ghost = true, onClick = onClick), tip)
            ab.addView(b, Ui.square(ctx, 38f).apply { rightMargin = Ui.dp(ctx, 2f) })
            return b
        }
        abBtn(R.drawable.ic_frame_prev, "이전 프레임") { stopPlayback(); renderer.setFrame(animFrame - 1) }
        playBtn = abBtn(R.drawable.ic_play, "재생 / 정지") { togglePlayback() }
        abBtn(R.drawable.ic_frame_next, "다음 프레임") { stopPlayback(); renderer.setFrame(animFrame + 1) }
        frameLabel = Ui.text(ctx, "1 / 1", 13f, bold = true).apply { setPadding(Ui.dp(ctx, 6f), 0, Ui.dp(ctx, 8f), 0) }
        ab.addView(frameLabel)
        frameStrip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        ab.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(frameStrip)
        }, LinearLayout.LayoutParams(Ui.dp(ctx, 200f), ViewGroup.LayoutParams.WRAP_CONTENT))
        ab.addView(Ui.hspace(ctx, 6f))
        abBtn(R.drawable.ic_layer_add, "새 프레임 (현재 프레임 뒤)") { stopPlayback(); renderer.addFrame(duplicate = false) }
        abBtn(R.drawable.ic_duplicate, "프레임 복제") { stopPlayback(); renderer.addFrame(duplicate = true) }
        abBtn(R.drawable.ic_trash, "프레임 삭제") { stopPlayback(); renderer.deleteFrame() }
        onionBtn = abBtn(R.drawable.ic_onion, "어니언 스킨 (앞 프레임 빨강 · 뒤 프레임 파랑)") {
            renderer.setOnionSkin(!renderer.onionSkin)
            Ui.setOn(onionBtn, !renderer.onionSkin)
        }
        Ui.setOn(onionBtn, renderer.onionSkin)
        val fpsBtn = Ui.button(ctx, "${settings.animFps}fps") {}
        fpsBtn.setOnClickListener {
            val list = intArrayOf(6, 8, 12, 15, 24)
            val next = list[(list.indexOf(settings.animFps) + 1).mod(list.size)]
            settings.animFps = next
            settings.save()
            fpsBtn.text = "${next}fps"
        }
        tips.bind(fpsBtn, "재생 속도 (누를 때마다 6 → 8 → 12 → 15 → 24)")
        ab.addView(fpsBtn, Ui.wrap().apply { leftMargin = Ui.dp(ctx, 4f) })
        animBar = ab
        // 도구 막대 오른쪽, 아래에 (오른쪽 패널과 겹치지 않게 왼쪽 기준)
        root.addView(animBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = Ui.dp(ctx, 16f)
            leftMargin = Ui.dp(ctx, 90f)
        })

        // ---- 성능 측정 패널 ----
        perfText = Ui.text(ctx, "펜으로 그리면 측정합니다.", 12f).apply { typeface = android.graphics.Typeface.MONOSPACE }
        benchText = Ui.text(ctx, "", 12f, Ui.SUBTEXT).apply { typeface = android.graphics.Typeface.MONOSPACE }
        perfPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(ctx, 10f)
            setPadding(p, p, p, p)
            background = Ui.rounded(Ui.PANEL, Ui.dp(ctx, 12f).toFloat(), Ui.dp(ctx, 1f), Ui.BORDER)
            visibility = View.GONE
            addView(Ui.text(ctx, "성능 측정", 13f, bold = true))
            addView(perfText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 4f)
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Ui.button(ctx, "부하 테스트 문서 (A4 300dpi · 50장)") { loadStressDocument() }, Ui.wrap())
                addView(Ui.hspace(ctx, 6f))
                addView(Ui.button(ctx, "합성 벤치마크") {
                    benchText.text = "측정 중… (몇 초 걸립니다)"
                    renderer.runBenchmark()
                }, Ui.wrap())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 8f)
            })
            addView(benchText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 6f)
            })
        }
        root.addView(perfPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(Ui.dp(ctx, 76f), Ui.dp(ctx, 72f), 0, 0)
        })

        // ---- 중앙 HUD (단축키 피드백) — 두 테마 모두 어두운 말풍선 ----
        hud = Ui.text(ctx, "", 16f, 0xFFF2F2F4.toInt(), bold = true).apply {
            val p = Ui.dp(ctx, 14f)
            setPadding(p, p / 2, p, p / 2)
            background = Ui.rounded(0xDD000000.toInt(), Ui.dp(ctx, 8f).toFloat())
            visibility = View.GONE
        }
        root.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /**
     * 패널 접힘 상태를 화면에 반영합니다.
     * 상단 바: 손잡이·펼치기 버튼만 / 도구 막대: 손잡이·펼치기·지금 도구만 / 오른쪽 패널: 손잡이 줄만.
     */
    private fun applyCollapse() {
        val topC = settings.topBarCollapsed
        topBarBody.forEach { it.visibility = if (topC) View.GONE else View.VISIBLE }
        topToggle.setImageResource(if (topC) R.drawable.ic_chevron_right else R.drawable.ic_chevron_left)
        Ui.tint(topToggle)

        val toolC = settings.toolBarCollapsed
        val current = toolButtons[tool]
        toolBarBody.forEach { it.visibility = if (!toolC || it === current) View.VISIBLE else View.GONE }
        toolToggle.setImageResource(if (toolC) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up)
        Ui.tint(toolToggle)

        val panelC = settings.rightPanelCollapsed
        listOf(toolCard.view, colorCard.view, layerCard.view).forEach { it.visibility = if (panelC) View.GONE else View.VISIBLE }
        panelToggle.setImageResource(if (panelC) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up)
        Ui.tint(panelToggle)
        Ui.setOn(panelBtn, !panelC)
    }

    /**
     * 오른쪽 패널 세로 범위: 상단 바와 가로로 겹치면 상단 바 아래(바가 위쪽일 때) 또는 위(아래쪽일 때)까지만.
     * 겹치지 않으면 화면 위아래 끝까지.
     */
    private fun fitRightPanel() {
        if (!::rightPanel.isInitialized || !::topBar.isInitialized) return
        val lp = rightPanel.layoutParams as? FrameLayout.LayoutParams ?: return
        val m = Ui.dp(this, 8f)
        val gap = Ui.dp(this, 6f)
        val h = rootView.height
        var top = m
        var bottom = m
        if (h > 0 && topBar.visibility == View.VISIBLE && topBar.width > 0) {
            val overlapX = topBar.x < rightPanel.x + rightPanel.width && rightPanel.x < topBar.x + topBar.width
            if (overlapX) {
                if (topBar.y + topBar.height / 2f < h / 2f) top = (topBar.y + topBar.height).toInt() + gap
                else bottom = (h - topBar.y).toInt() + gap
            }
        }
        if (lp.topMargin != top || lp.bottomMargin != bottom) {
            lp.topMargin = top
            lp.bottomMargin = bottom
            rightPanel.layoutParams = lp
        }
    }

    /** 카드 접힘에 따라 높이를 다시 나눔: 펼친 도구·레이어 카드가 남은 공간을 나눠 가짐. */
    private fun relayoutCards() {
        val gap = Ui.dp(this, 8f)
        fun lp(open: Boolean, weight: Float, last: Boolean) =
            (if (open && weight > 0f) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                .apply { if (!last) bottomMargin = gap }
        // 레이어 목록이 조금 더 길게 (도구 카드는 스크롤).
        toolCard.view.layoutParams = lp(toolCard.open, 1f, false)
        colorCard.view.layoutParams = lp(colorCard.open, 0f, false)
        layerCard.view.layoutParams = lp(layerCard.open, 1.2f, true)
        rightPanel.requestLayout()
    }

    // =====================================================================
    // 상태 → UI
    // =====================================================================

    private fun activeBrush(): Brush? = if (tool.isBrush) library.active(tool) else null

    private fun updateToolButtons() {
        toolButtons.forEach { (t, b) -> Ui.setOn(b, t == tool) }
        if (settings.toolBarCollapsed && toolBarBody.isNotEmpty()) applyCollapse()
    }

    /** 색 카드에서 드래그 중: 최근 색에는 넣지 않고 주색만 바꿈. */
    private fun livePrimary(c: Int) {
        settings.primaryColor = c
        updateSwatches(syncPicker = false)
    }

    private fun updateSwatches(syncPicker: Boolean = true) {
        if (syncPicker && colorPicker.color != settings.primaryColor) colorPicker.color = settings.primaryColor
        if (::colorSliders.isInitialized && colorSliders.color != (settings.primaryColor or 0xFF000000.toInt())) colorSliders.color = settings.primaryColor
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
        // 끌 때 중심을 가운데로 되돌림
        if (!symmetry.on) { symmetry.cx = -1f; symmetry.cy = -1f }
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

    /** 자 고르기: 끄기 / 원근 1·2·3점 / 동심원 / 위치 초기화 */
    private fun chooseRuler() {
        val items = arrayOf("끄기", "원근 자 · 1점", "원근 자 · 2점", "원근 자 · 3점", "동심원 자", "평행선 자 (빗금·속도선)", "방사선 자 (집중선)", "손잡이 위치 초기화")
        Ui.dialog(this)
            .setTitle("자")
            .setItems(items) { _, which ->
                val v = canvasView.viewport
                val oldKind = ruler.kind
                val oldCount = ruler.vpCount
                when (which) {
                    0 -> ruler.kind = kr.dfluid.paint.input.GuideRuler.Kind.OFF
                    1, 2, 3 -> { ruler.kind = kr.dfluid.paint.input.GuideRuler.Kind.PERSPECTIVE; ruler.vpCount = which }
                    4 -> ruler.kind = kr.dfluid.paint.input.GuideRuler.Kind.CONCENTRIC
                    5 -> ruler.kind = kr.dfluid.paint.input.GuideRuler.Kind.PARALLEL
                    6 -> ruler.kind = kr.dfluid.paint.input.GuideRuler.Kind.RADIAL
                    7 -> rulerPlaced = false
                }
                // 처음 켤 때, 소실점 개수가 바뀔 때, 초기화를 고를 때 기본 위치로
                val P = kr.dfluid.paint.input.GuideRuler.Kind.PERSPECTIVE
                val countChanged = ruler.kind == P && (oldKind != P || oldCount != ruler.vpCount)
                if (ruler.on && (!rulerPlaced || countChanged)) {
                    ruler.reset(v.canvasW, v.canvasH)
                    rulerPlaced = true
                }
                updateRulerButton()
                overlay.invalidate()
                if (ruler.on) showHud("손잡이를 끌어 옮길 수 있습니다")
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun updateRulerButton() {
        val circle = ruler.kind == kr.dfluid.paint.input.GuideRuler.Kind.CONCENTRIC
        rulerBtn.setImageResource(if (circle) R.drawable.ic_ruler_circle else R.drawable.ic_ruler_persp)
        Ui.setOn(rulerBtn, ruler.on)
        val label = when (ruler.kind) {
            kr.dfluid.paint.input.GuideRuler.Kind.OFF -> "원근 자 · 동심원 자 (꺼짐)"
            kr.dfluid.paint.input.GuideRuler.Kind.PERSPECTIVE -> "원근 자 ${ruler.vpCount}점 (손잡이를 끌어 소실점 이동)"
            kr.dfluid.paint.input.GuideRuler.Kind.CONCENTRIC -> "동심원 자 (손잡이를 끌어 중심 이동)"
            kr.dfluid.paint.input.GuideRuler.Kind.PARALLEL -> "평행선 자 (두 손잡이로 방향 정하기)"
            kr.dfluid.paint.input.GuideRuler.Kind.RADIAL -> "방사선 자 (손잡이를 끌어 중심 이동)"
        }
        tips.relabel(rulerBtn, label)
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
        Tool.BLEND -> R.drawable.ic_tool_blend
        Tool.LINEFIX -> R.drawable.ic_tool_linefix
        Tool.SHAPE -> R.drawable.ic_tool_shape
        Tool.TEXT -> R.drawable.ic_tool_text
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
        Tool.BLEND -> Action.TOOL_BLEND
        Tool.LINEFIX -> Action.TOOL_LINEFIX
        Tool.SHAPE -> Action.TOOL_SHAPE
        Tool.TEXT -> Action.TOOL_TEXT
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
    /** 퀵 마스크 레이어에 그릴 때는 주색과 상관없이 빨강 (선택 정도 = 알파) */
    override val brushColor: Int
        get() = if (lastNodes.firstOrNull { it.id == lastActiveId }?.props?.quickMask == true) 0xFFF22633.toInt() else settings.primaryColor

    private fun toggleQuickMask() {
        if (transforming) commitTransform()
        val on = lastNodes.any { it.props.quickMask }
        showHud(if (on) "퀵 마스크 끔 · 칠한 곳이 선택 영역이 됩니다" else "퀵 마스크 · 칠하면 선택, 지우면 해제")
        renderer.toggleQuickMask()
    }
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

    override fun onNavigator(bitmap: Bitmap) {
        if (::navView.isInitialized && settings.navOpen) navView.setImage(bitmap) else bitmap.recycle()
    }

    private fun chooseGrid() {
        val steps = intArrayOf(0, 32, 64, 100, 128, 256)
        Ui.dialog(this)
            .setTitle("격자")
            .setItems(steps.map { if (it == 0) "끄기" else "${it}px" }.toTypedArray()) { _, i ->
                settings.gridStep = steps[i]
                settings.save()
                overlay.gridStep = steps[i]
                Ui.setOn(gridBtn, steps[i] > 0)
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun toggleQuick() {
        settings.quickOpen = !settings.quickOpen
        settings.save()
        quickPanel.visibility = if (settings.quickOpen) View.VISIBLE else View.GONE
        Ui.setOn(quickBtn, settings.quickOpen)
    }

    /** 퀵 액세스 버튼들 (3열) + 마지막에 "+" */
    private fun rebuildQuick() {
        quickGrid.removeAllViews()
        val actions = settings.quickActions.mapNotNull { n -> Action.entries.firstOrNull { it.name == n } }
        var row: LinearLayout? = null
        val cells = actions.map { it as Action? } + listOf(null)
        cells.forEachIndexed { i, a ->
            if (i % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                quickGrid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.dp(this@MainActivity, 3f) })
            }
            val b = if (a == null) Ui.button(this, "+") { addQuickAction() }
            else Ui.button(this, a.label) { runQuick(a) }.apply {
                maxLines = 2
                textSize = 11.5f
                setOnLongClickListener {
                    settings.quickActions.remove(a.name)
                    settings.save()
                    rebuildQuick()
                    showHud("퀵 액세스에서 뺐습니다: ${a.label}")
                    true
                }
            }
            tips.bind(b, a?.let { tips.text(it.label, it) } ?: "기능 추가")
            row!!.addView(b, LinearLayout.LayoutParams(0, Ui.dp(this, 40f), 1f).apply { rightMargin = Ui.dp(this@MainActivity, 3f) })
        }
        val rest = (3 - cells.size % 3) % 3
        repeat(rest) { row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
    }

    private fun runQuick(a: Action) {
        when (a.kind) {
            kr.dfluid.paint.shortcut.ActionKind.TOOL -> { onToolKey(a, true, false); onToolKey(a, false, false) }
            kr.dfluid.paint.shortcut.ActionKind.HOLD -> showHud("누르고 있는 동안만 쓰는 기능은 키보드로 쓰세요")
            else -> onShortcut(a)
        }
    }

    /** 담을 기능 고르기 (분류별, 이미 담은 것·누르고 있는 기능은 빼고) */
    private fun addQuickAction() {
        val list = Action.entries.filter { it.kind != kr.dfluid.paint.shortcut.ActionKind.HOLD && it.name !in settings.quickActions }
        val labels = list.map { "${it.category} · ${it.label}" }.toTypedArray()
        Ui.dialog(this)
            .setTitle("퀵 액세스에 추가")
            .setItems(labels) { _, i ->
                settings.quickActions.add(list[i].name)
                settings.save()
                rebuildQuick()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun toggleNavigator() {
        settings.navOpen = !settings.navOpen
        settings.save()
        navPanel.visibility = if (settings.navOpen) View.VISIBLE else View.GONE
        Ui.setOn(navBtn, settings.navOpen)
        renderer.navigatorOn = settings.navOpen
    }

    override fun onViewChanged() {
        if (::navView.isInitialized && settings.navOpen) navView.invalidate()
        val v = canvasView.viewport
        viewLabel.text = "보기 · ${(v.scale * 100).roundToInt()}% · ${v.rotationDegrees}°${if (v.flipped) " · 반전" else ""}"
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

    /** 활성 레이어가 텍스트이고 그 글 상자 안을 누르면 고치기, 아니면 그 자리에 새 텍스트. */
    override fun onTextTap(x: Float, y: Float) {
        val active = lastNodes.firstOrNull { it.id == lastActiveId }
        val t = active?.props?.text
        if (active != null && t != null) {
            val b = t.bounds()
            val slop = 12f / canvasView.viewport.scale
            if (x >= b.left - slop && x <= b.right + slop && y >= b.top - slop && y <= b.bottom + slop) {
                Dialogs.textEditor(this, t, settings) { spec -> renderer.updateText(active.id, spec) }
                return
            }
        }
        val init = kr.dfluid.paint.document.TextSpec(
            "", x, y, settings.textSize, settings.primaryColor, settings.textFont, settings.textVertical,
        )
        Dialogs.textEditor(this, init, settings) { spec -> renderer.createText(spec) }
    }

    override val fillEnclose: Boolean get() = settings.fillEnclose

    override fun onFillLasso(pts: FloatArray) {
        renderer.fillEnclosed(pts, settings.fillOptions, settings.primaryColor, settings.fillOpacity)
        settings.pushRecent(settings.primaryColor)
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

    /** 내보낼 보조 도구 (파일 위치를 고르는 동안) */
    private var pendingBrushExport: Brush? = null

    override fun exportBrushFile(brush: Brush) {
        pendingBrushExport = brush
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, brush.name.map { if (it.isLetterOrDigit() || it == ' ' || it == '-') it else '_' }.joinToString("") + ".dfbrush")
        }
        startActivityForResult(intent, REQ_BRUSH_EXPORT)
    }

    override fun loadStoredSelection() = chooseStoredSelection()

    override fun onPickModeChanged() {
        renderer.pickFromLayer = settings.pickFromLayer
    }

    override fun importBrushFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_BRUSH_IMPORT)
    }

    override fun editBrush(tool: Tool, brush: Brush) {
        BrushEditor.show(this, brush, library, pickImage = { cb -> pickTipImage(cb) }) {
            toolOptions.show(this.tool)
        }
    }

    override fun selectAll() = renderer.selectAll()
    override fun deselect() = renderer.deselect()
    override fun invertSelection() = renderer.invertSelection()
    override fun modifySelection(kind: kr.dfluid.paint.engine.SelModify, px: Int) {
        showHud("선택 영역 ${kind.label} ${px}px")
        renderer.modifySelection(kind, px)
    }
    override fun selectFromLayer() = renderer.selectFromLayer(settings.selectMode)
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
        // 캔버스 크기가 바뀌면 자 위치도 새 캔버스 기준으로
        if (rulerPlaced) ruler.fitCanvas(width.toFloat(), height.toFloat())
        overlay.invalidate()
        canvasView.onDocumentSize(width, height, refit)
        updateTitle()
    }

    private var lastMaskEditing = false

    override fun onLayersChanged(nodes: List<NodeInfo>, activeId: Int, liveRasterIds: Set<Int>) {
        lastNodes = nodes
        lastActiveId = activeId
        lastLiveIds = liveRasterIds
        val maskEditing = renderer.maskEditing && nodes.firstOrNull { it.id == activeId }?.props?.mask == true
        if (maskEditing != lastMaskEditing) {
            lastMaskEditing = maskEditing
            showHud(if (maskEditing) "마스크 편집 · 그리기 = 보이기, 지우개 = 가리기" else "레이어 편집")
        }
        layerPanel.update(nodes, activeId, liveRasterIds)
        if (::quickMaskBtn.isInitialized) Ui.setOn(quickMaskBtn, nodes.any { it.props.quickMask })
        updateTitle()
    }

    override fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {
        lastCanUndo = canUndo
        lastCanRedo = canRedo
        Ui.setEnabled(undoBtn, canUndo)
        Ui.setEnabled(redoBtn, canRedo)
        updateTitle()
    }

    override fun onSelectionChanged(hasSelection: Boolean) {
        this.hasSelection = hasSelection
        updateSelBar()
    }

    /** 저장해 둔 선택 영역 고르기 (새로 / 추가 / 빼기) */
    private fun chooseStoredSelection() {
        renderer.listStoredSelections { names ->
            if (names.isEmpty()) {
                showHud("저장한 선택 영역이 없습니다 (선택 범위 런처의 \"저장\")")
                return@listStoredSelections
            }
            Ui.dialog(this)
                .setTitle("선택 영역 불러오기")
                .setItems(names.toTypedArray()) { _, i ->
                    val ops = arrayOf("새로 (바꾸기)", "추가", "빼기", "교차")
                    Ui.dialog(this)
                        .setTitle(names[i])
                        .setItems(ops) { _, k -> renderer.loadStoredSelection(i, kr.dfluid.paint.engine.SelOp.entries[k]) }
                        .setNegativeButton("취소", null)
                        .show()
                }
                .setNegativeButton("닫기", null)
                .show()
        }
    }

    /** 컷 테두리 굵기를 고르고 만들기 */
    private fun chooseFrame() {
        val widths = intArrayOf(4, 8, 12, 20)
        Ui.dialog(this)
            .setTitle("컷 테두리 굵기")
            .setItems(widths.map { "${it}px" }.toTypedArray()) { _, i ->
                renderer.createFrame(widths[i].toFloat())
                renderer.deselect()
                showHud("컷을 만들었습니다. \"그림\" 레이어에 그리면 컷 밖으로 나가지 않습니다")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun updateSelBar() {
        if (!::selBar.isInitialized) return
        selBar.visibility = if (hasSelection && !transforming && settings.selLauncher) View.VISIBLE else View.GONE
        // 애니메이션 타임라인이 보이면 그 위로
        (selBar.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            val want = Ui.dp(this, if (::animBar.isInitialized && animBar.visibility == View.VISIBLE) 76f else 16f)
            if (lp.bottomMargin != want) { lp.bottomMargin = want; selBar.layoutParams = lp }
        }
    }

    override fun onThumbnail(id: Int, bitmap: Bitmap) = layerPanel.setThumbnail(id, bitmap)

    override fun onTransformStarted(width: Int, height: Int, matrix: FloatArray) {
        transforming = true
        updateSelBar()
        onAnimation(animExists, animFrame, animCount)
        distortBtnRef?.let { Ui.setOn(it, false) }
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
        updateSelBar()
        onAnimation(animExists, animFrame, animCount)
        moveSession = false
        commitWhenStarted = false
        overlay.endTransform()
        transformBar.visibility = View.GONE
    }

    override fun onPerfStats(stats: PerfMonitor.Stats, memory: String) {
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
        val rt = Runtime.getRuntime()
        val heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        perfText.append("\n메모리  $memory · 앱(Java) ${heapMb}MB / 최대 ${rt.maxMemory() / (1024 * 1024)}MB")
    }

    override fun onBenchmarkDone(report: String) {
        benchText.text = report + String.format("\n(관문 2: 브러시 영역 합성이 %.1fms 이내면 %.0f fps 유지)", 1000f / 60f, 60f)
        Log.i(TAG, "benchmark\n$report")
    }

    override fun onAnimation(exists: Boolean, frame: Int, count: Int) {
        animExists = exists
        animFrame = frame
        animCount = count
        if (!::animBar.isInitialized) return
        if (!exists) stopPlayback()
        Ui.setOn(animBtn, exists && timelineOpen)
        animBar.visibility = if (exists && timelineOpen && !transforming) View.VISIBLE else View.GONE
        frameLabel.text = "${frame + 1} / $count"
        updateSelBar()
        // 프레임 칸: 번호 버튼 (누르면 그 프레임으로)
        if (frameStrip.childCount != count) {
            frameStrip.removeAllViews()
            for (i in 0 until count) {
                val b = Ui.button(this, "${i + 1}") { stopPlayback(); renderer.setFrame(i) }
                frameStrip.addView(b, LinearLayout.LayoutParams(Ui.dp(this, 40f), Ui.dp(this, 34f)).apply { rightMargin = Ui.dp(this@MainActivity, 3f) })
            }
        }
        for (i in 0 until frameStrip.childCount) Ui.setOn(frameStrip.getChildAt(i), i == frame)
    }

    // =====================================================================
    // 서브 뷰
    // =====================================================================

    private fun toggleSubView() {
        settings.subOpen = !settings.subOpen
        settings.save()
        subPanel.visibility = if (settings.subOpen) View.VISIBLE else View.GONE
        Ui.setOn(subBtn, settings.subOpen)
        if (settings.subOpen && subBitmap == null) {
            if (settings.subUri != null) loadSubImage() else openSubImage()
        }
    }

    private fun openSubImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQ_SUB_IMAGE)
    }

    /** settings.subUri의 이미지를 백그라운드에서 읽어 서브 뷰에 띄웁니다. */
    private fun loadSubImage() {
        val s = settings.subUri ?: return
        val uri = Uri.parse(s)
        io.execute {
            val b = RefImageView.load(this, uri)
            ui.post {
                if (b == null) {
                    Toast.makeText(this, "참고 이미지를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                    return@post
                }
                subBitmap = b
                subImage.setImage(b)
            }
        }
    }

    private fun togglePlayback() {
        if (animPlaying) stopPlayback() else {
            if (animCount <= 1) {
                showHud("프레임이 2장 이상이어야 재생합니다")
                return
            }
            animPlaying = true
            playBtn.setImageResource(R.drawable.ic_pause)
            Ui.tint(playBtn)
            ui.postDelayed(playTick, (1000f / settings.animFps).toLong())
        }
    }

    private fun stopPlayback() {
        if (!animPlaying) return
        animPlaying = false
        ui.removeCallbacks(playTick)
        if (::playBtn.isInitialized) {
            playBtn.setImageResource(R.drawable.ic_play)
            Ui.tint(playBtn)
        }
        renderer.setFrame(animFrame) // 어니언 스킨 다시 표시
    }

    override fun onFilterEnded() {
        // 적용·취소 외의 이유(시작 실패, 앱 일시정지 등)로 끝났으면 대화상자도 닫습니다.
        val dlg = filterDialog ?: return
        filterDialog = null
        if (dlg.isShowing) dlg.dismiss()
    }

    // =====================================================================
    // 필터 · 색조 보정
    // =====================================================================

    // =====================================================================
    // 캔버스 편집
    // =====================================================================

    private fun chooseCanvasEdit() {
        if (transforming) commitTransform()
        val v = canvasView.viewport
        val w = v.canvasW.toInt()
        val h = v.canvasH.toInt()
        val items = arrayOf(
            "이미지 크기 변경…", "캔버스 크기 변경…",
            "시계 방향 90° 회전", "반시계 방향 90° 회전", "180° 회전",
            "좌우 반전", "상하 반전",
        )
        Ui.dialog(this)
            .setTitle("캔버스 · ${w}×$h px")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> Dialogs.imageSize(this, w, h, maxTex) { nw, nh -> runCanvasEdit(CanvasEdit.Resample(nw, nh)) }
                    1 -> Dialogs.canvasSize(this, w, h, maxTex) { nw, nh, ax, ay -> runCanvasEdit(CanvasEdit.Resize(nw, nh, ax, ay)) }
                    2 -> runCanvasEdit(CanvasEdit.Rotate(1))
                    3 -> runCanvasEdit(CanvasEdit.Rotate(3))
                    4 -> runCanvasEdit(CanvasEdit.Rotate(2))
                    5 -> runCanvasEdit(CanvasEdit.Flip(true))
                    6 -> runCanvasEdit(CanvasEdit.Flip(false))
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun runCanvasEdit(e: CanvasEdit) {
        showHud("${e.label}…")
        renderer.editCanvas(e)
    }

    private fun chooseFilter() {
        if (transforming) commitTransform()
        val kinds = FilterKind.entries
        Ui.dialog(this)
            .setTitle("필터 · 색조 보정")
            .setItems(kinds.map { it.label }.toTypedArray()) { _, which -> showFilter(kinds[which]) }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 값 슬라이더 대화상자. 움직이는 동안 캔버스에 미리보기, 적용하면 실행취소 한 단계. */
    private fun showFilter(kind: FilterKind) {
        val ctx = this
        val values = IntArray(kind.params.size) { kind.params[it].default }
        var curvePts = floatArrayOf(0f, 0f, 1f, 1f)
        fun spec(): FilterSpec {
            val v = kind.params.mapIndexed { i, p -> values[i] * p.scale }
            if (kind == FilterKind.TONE_CURVE) {
                return FilterSpec(kind, List(8) { 0f } + kr.dfluid.paint.brush.PressureCurve.lut(curvePts, FilterKind.TONE_LUT).toList())
            }
            if (kind != FilterKind.GRADIENT_MAP) return FilterSpec(kind, v)
            // 그라데이션 맵: 주색(어두운 곳) → 보조색(밝은 곳)
            fun rgb(c: Int) = listOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
            return FilterSpec(kind, v + rgb(settings.primaryColor) + rgb(settings.secondaryColor))
        }
        fun label(i: Int): String {
            val p = kind.params[i]
            val v = values[i]
            return (if (p.min < 0 && v > 0) "+" else "") + v + p.suffix
        }
        val pad = Ui.dp(ctx, 20f)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(ctx, 8f), pad, 0)
            minimumWidth = Ui.dp(ctx, 380f)
        }
        val rows = kind.params.mapIndexed { i, p ->
            Ui.SliderRow(ctx, p.label, p.max - p.min).also { row ->
                row.set(values[i] - p.min, label(i))
                row.onChange = { prog ->
                    values[i] = prog + p.min
                    row.set(prog, label(i))
                    renderer.setFilter(spec())
                }
                body.addView(row.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = Ui.dp(ctx, 4f)
                })
            }
        }
        if (kind == FilterKind.INVERT) body.addView(Ui.text(ctx, "색을 반전합니다.", 13f))
        val curveView = if (kind == FilterKind.TONE_CURVE) PressureCurveView(ctx).also { cv ->
            cv.points = curvePts
            cv.live = true
            cv.onChanged = { pts ->
                curvePts = pts.copyOf()
                renderer.setFilter(spec())
            }
            body.addView(Ui.text(ctx, "가로 = 원래 밝기, 세로 = 바뀐 밝기. 빈 곳을 누르면 점 추가, 점을 두 번 누르면 삭제", 11.5f, Ui.MUTED))
            body.addView(cv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 4f)
            })
        } else null
        body.addView(Ui.text(ctx, if (renderer.maskEditing) "마스크에 적용합니다." else "선택 영역이 있으면 그 안에만 적용합니다.", 11.5f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(ctx, 6f), 0, 0)
        })
        var finished = false
        val dlg = Ui.dialog(ctx)
            .setTitle(kind.label)
            .setView(body)
            .setPositiveButton("적용") { _, _ ->
                finished = true
                renderer.commitFilter()
            }
            .setNegativeButton("취소", null)
            .apply { if (kind.params.isNotEmpty() || curveView != null) setNeutralButton("초기화", null) }
            .create()
        dlg.setOnDismissListener {
            if (!finished) {
                finished = true
                renderer.cancelFilter()
            }
            if (filterDialog === dlg) filterDialog = null
        }
        dlg.setOnShowListener {
            // 초기화는 대화상자를 닫지 않게 직접 처리
            dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                kind.params.forEachIndexed { i, p ->
                    values[i] = p.default
                    rows[i].set(p.default - p.min, label(i))
                }
                curvePts = floatArrayOf(0f, 0f, 1f, 1f)
                curveView?.points = curvePts
                renderer.setFilter(spec())
            }
        }
        // 미리보기가 보이게: 배경을 어둡게 하지 않고 화면 아래쪽에 띄움
        dlg.window?.let { w ->
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setGravity(Gravity.BOTTOM)
        }
        filterDialog = dlg
        dlg.show()
        renderer.beginFilter(spec())
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
        lastInteraction = android.os.SystemClock.uptimeMillis()
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
            Action.SELECT_QUICK_MASK -> toggleQuickMask()
            Action.EDIT_COPY -> renderer.copySelection(cut = false)
            Action.EDIT_CUT -> renderer.copySelection(cut = true)
            Action.EDIT_PASTE -> renderer.paste()
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
            Action.VIEW_GRID -> chooseGrid()
            Action.VIEW_GRAY -> {
                renderer.grayView = !renderer.grayView
                if (::grayBtn.isInitialized) Ui.setOn(grayBtn, renderer.grayView)
                showHud(if (renderer.grayView) "흑백 보기 (명암 확인)" else "흑백 보기 끔")
            }
            Action.TOGGLE_UI -> toggleUi()
            Action.FILE_NEW -> confirmIfDirty { Dialogs.newCanvas(this, maxTex, settings) { w, h, bg -> newCanvas(w, h, bg) } }
            Action.FILE_OPEN -> confirmIfDirty { openDocument() }
            Action.FILE_SAVE -> save()
            Action.FILE_SAVE_AS -> saveAs()
            Action.FILE_EXPORT_PNG -> { exportFormat = 0; exportScale = 100; exportPng() }
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
            Action.TOOL_BLEND -> Tool.BLEND
            Action.TOOL_LINEFIX -> Tool.LINEFIX
            Action.TOOL_SHAPE -> Tool.SHAPE
            Action.TOOL_TEXT -> Tool.TEXT
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
        // 떠 있는 창들도 함께 (다시 보일 때는 열려 있던 것만)
        if (::quickPanel.isInitialized) quickPanel.visibility = if (uiVisible && settings.quickOpen) View.VISIBLE else View.GONE
        if (::navPanel.isInitialized) navPanel.visibility = if (uiVisible && settings.navOpen) View.VISIBLE else View.GONE
        if (::subPanel.isInitialized) subPanel.visibility = if (uiVisible && settings.subOpen) View.VISIBLE else View.GONE
    }

    private fun openShortcutSettings() {
        startActivity(Intent(this, ShortcutSettingsActivity::class.java))
    }

    private fun showSettings() {
        Dialogs.settings(this, settings, onChanged = { settings.save() }, onOpenShortcuts = { openShortcutSettings() }, onThemeChanged = { rebuildUi() }, onPanelsChanged = { rebuildUi() })
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
        val list = arrayListOf(png, psd, "JPEG / 크기 바꿔 내보내기…")
        if (animExists && animCount > 0) {
            list.add("애니메이션 GIF (${animCount}프레임 · ${settings.animFps}fps)")
            list.add("애니메이션 MP4 동영상 (${animCount}프레임 · ${settings.animFps}fps)")
        }
        Ui.dialog(this)
            .setTitle("내보내기")
            .setItems(list.toTypedArray()) { _, which ->
                when (which) {
                    0 -> { exportFormat = 0; exportScale = 100; exportPng() }
                    1 -> exportPsd()
                    2 -> chooseImageExport()
                    3 -> exportGif()
                    else -> exportMp4()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun importImageLayer() {
        if (transforming) commitTransform()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQ_IMPORT_LAYER)
    }

    /** 캔버스보다 크면 캔버스에 맞게 줄여서 새 레이어로 */
    private fun readImageLayer(uri: Uri) {
        val cw = canvasView.viewport.canvasW.toInt()
        val ch = canvasView.viewport.canvasH.toInt()
        val name = displayName(uri)?.substringBeforeLast('.') ?: "가져온 이미지"
        showHud("이미지를 가져오는 중…")
        io.execute {
            try {
                var bmp = RefImageView.load(this, uri, maxOf(cw, ch) * 2) ?: throw IllegalStateException("이미지를 읽을 수 없습니다.")
                val s = minOf(1f, cw.toFloat() / bmp.width, ch.toFloat() / bmp.height)
                if (s < 1f) {
                    val sc = android.graphics.Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * s).toInt()), maxOf(1, (bmp.height * s).toInt()), true)
                    if (sc !== bmp) bmp.recycle()
                    bmp = sc
                }
                if (bmp.config != android.graphics.Bitmap.Config.ARGB_8888) {
                    val c = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    bmp.recycle()
                    bmp = c
                }
                // Bitmap 메모리는 프리멀티플라이드 RGBA 순서라 그대로 씁니다.
                val buf = java.nio.ByteBuffer.allocateDirect(bmp.width * bmp.height * 4).order(java.nio.ByteOrder.nativeOrder())
                bmp.copyPixelsToBuffer(buf)
                buf.rewind()
                val w = bmp.width; val h = bmp.height
                bmp.recycle()
                ui.post { renderer.importImage(w, h, buf, name) }
            } catch (e: OutOfMemoryError) {
                ui.post { Toast.makeText(this, "메모리가 부족해 이미지를 가져오지 못했습니다.", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                ui.post { Toast.makeText(this, "이미지를 가져오지 못했습니다: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun exportGif() {
        stopPlayback()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/gif"
            putExtra(Intent.EXTRA_TITLE, (currentUri?.let { displayName(it)?.substringBeforeLast('.') } ?: "애니메이션") + ".gif")
        }
        startActivityForResult(intent, REQ_EXPORT_GIF)
    }

    /**
     * 프레임을 하나씩 받아 줄이고(긴 변 최대 GIF_MAX px) GIF에 씁니다.
     * 렌더러 → (GL 스레드) 픽셀 → io 스레드에서 인코딩 → 다음 프레임 요청 순으로, 한 번에 한 장만 메모리에 둡니다.
     */
    private fun writeGif(uri: Uri) {
        val count = animCount
        if (count <= 0) return
        showHud("GIF로 내보내는 중… 0 / $count")
        val stream = try {
            contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("파일을 열 수 없습니다.")
        } catch (e: Exception) {
            Toast.makeText(this, "GIF로 내보내지 못했습니다: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        val out = java.io.BufferedOutputStream(stream, 1 shl 16)
        var encoder: kr.dfluid.paint.document.GifEncoder? = null
        val delay = maxOf(2, Math.round(100f / settings.animFps))
        fun fail(msg: String) {
            try { out.close() } catch (_: Exception) {}
            ui.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }
        fun step(i: Int) {
            renderer.captureFrame(i) { w, h, buf ->
                if (buf == null) { fail("GIF로 내보내지 못했습니다 (메모리 부족이거나 애니메이션이 없습니다)."); return@captureFrame }
                io.execute {
                    try {
                        val full = ProjectIO.toBitmap(w, h, buf)
                        val s = minOf(1f, GIF_MAX.toFloat() / maxOf(w, h))
                        val gw = maxOf(1, Math.round(w * s))
                        val gh = maxOf(1, Math.round(h * s))
                        val small = if (s < 1f) android.graphics.Bitmap.createScaledBitmap(full, gw, gh, true) else full
                        val px = IntArray(gw * gh)
                        small.getPixels(px, 0, gw, 0, 0, gw, gh)
                        if (small !== full) small.recycle()
                        full.recycle()
                        val enc = encoder ?: kr.dfluid.paint.document.GifEncoder(out, gw, gh, delay).also { encoder = it }
                        enc.addFrame(px)
                        if (i + 1 < count) {
                            ui.post { showHud("GIF로 내보내는 중… ${i + 1} / $count") }
                            step(i + 1)
                        } else {
                            enc.finish()
                            out.close()
                            ui.post { showHud("GIF로 내보냈습니다 (${gw}×$gh · ${count}프레임)") }
                        }
                    } catch (e: OutOfMemoryError) {
                        fail("메모리가 부족해 GIF로 내보내지 못했습니다.")
                    } catch (e: Exception) {
                        Log.e(TAG, "gif export failed", e)
                        fail("GIF로 내보내지 못했습니다: ${e.message}")
                    }
                }
            }
        }
        step(0)
    }

    private fun exportMp4() {
        stopPlayback()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/mp4"
            putExtra(Intent.EXTRA_TITLE, (currentUri?.let { displayName(it)?.substringBeforeLast('.') } ?: "애니메이션") + ".mp4")
        }
        startActivityForResult(intent, REQ_EXPORT_MP4)
    }

    /** GIF와 같은 방식으로 한 장씩: 흰 바탕에 합치고 긴 변 최대 MP4_MAX, 짝수 크기로 맞춰 H.264로 */
    private fun writeMp4(uri: Uri) {
        val count = animCount
        if (count <= 0) return
        showHud("MP4로 내보내는 중… 0 / $count")
        val pfd = try {
            contentResolver.openFileDescriptor(uri, "rwt") ?: throw IllegalStateException("파일을 열 수 없습니다.")
        } catch (e: Exception) {
            Toast.makeText(this, "MP4로 내보내지 못했습니다: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        var encoder: kr.dfluid.paint.document.Mp4Encoder? = null
        val fps = settings.animFps
        fun fail(msg: String) {
            encoder?.abort()
            try { pfd.close() } catch (_: Exception) {}
            ui.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }
        fun step(i: Int) {
            renderer.captureFrame(i) { w, h, buf ->
                if (buf == null) { fail("MP4로 내보내지 못했습니다 (메모리 부족이거나 애니메이션이 없습니다)."); return@captureFrame }
                io.execute {
                    try {
                        val full = ProjectIO.toBitmap(w, h, buf)
                        val s = minOf(1f, MP4_MAX.toFloat() / maxOf(w, h))
                        // H.264는 짝수 크기여야 함
                        val vw = maxOf(2, (Math.round(w * s) / 2) * 2)
                        val vh = maxOf(2, (Math.round(h * s) / 2) * 2)
                        val frame = android.graphics.Bitmap.createBitmap(vw, vh, android.graphics.Bitmap.Config.ARGB_8888)
                        android.graphics.Canvas(frame).apply {
                            drawColor(Color.WHITE)
                            drawBitmap(full, null, android.graphics.Rect(0, 0, vw, vh), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                        }
                        full.recycle()
                        val px = IntArray(vw * vh)
                        frame.getPixels(px, 0, vw, 0, 0, vw, vh)
                        frame.recycle()
                        val enc = encoder ?: kr.dfluid.paint.document.Mp4Encoder(pfd.fileDescriptor, vw, vh, fps).also { encoder = it }
                        enc.addFrame(px)
                        if (i + 1 < count) {
                            ui.post { showHud("MP4로 내보내는 중… ${i + 1} / $count") }
                            step(i + 1)
                        } else {
                            enc.finish()
                            pfd.close()
                            ui.post { showHud("MP4로 내보냈습니다 (${vw}×$vh · ${count}프레임 · ${fps}fps)") }
                        }
                    } catch (e: OutOfMemoryError) {
                        fail("메모리가 부족해 MP4로 내보내지 못했습니다.")
                    } catch (e: Exception) {
                        Log.e(TAG, "mp4 export failed", e)
                        fail("MP4로 내보내지 못했습니다: ${e.message}")
                    }
                }
            }
        }
        step(0)
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
        renderer.captureForExport { data, composite ->
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

    /** 이미지 내보내기 형식: 0 PNG, 1 JPEG (흰 배경에 합침) */
    private var exportFormat = 0
    /** 크기 배율 (%) */
    private var exportScale = 100

    private fun exportPng() {
        val jpg = exportFormat == 1
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (jpg) "image/jpeg" else "image/png"
            putExtra(Intent.EXTRA_TITLE, (currentUri?.let { displayName(it)?.substringBeforeLast('.') } ?: "그림") + if (jpg) ".jpg" else ".png")
        }
        startActivityForResult(intent, REQ_EXPORT)
    }

    /** 형식(PNG/JPEG)과 크기(%)를 골라 내보내기 */
    private fun chooseImageExport() {
        val ctx = this
        val pad = Ui.dp(ctx, 20f)
        var fmt = 1
        var scale = 100
        val w = canvasView.viewport.canvasW.toInt()
        val h = canvasView.viewport.canvasH.toInt()
        val fmtRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val fmtBtns = ArrayList<TextView>()
        listOf("PNG (투명 유지)", "JPEG (흰 배경)").forEachIndexed { i, l ->
            val b = Ui.button(ctx, l) { fmt = i; fmtBtns.forEachIndexed { j, v -> Ui.setOn(v, j == i) } }
            Ui.setOn(b, i == fmt)
            fmtBtns.add(b)
            fmtRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Ui.dp(ctx, 4f) })
        }
        val sizeText = Ui.text(ctx, "", 12f, Ui.SUBTEXT)
        val row = Ui.SliderRow(ctx, "크기", 190)
        fun refresh() {
            row.set(scale - 10, "$scale%")
            sizeText.text = "${maxOf(1, w * scale / 100)} × ${maxOf(1, h * scale / 100)} px"
        }
        row.onChange = { p -> scale = p + 10; refresh() }
        refresh()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            minimumWidth = Ui.dp(ctx, 380f)
            addView(fmtRow)
            addView(row.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8f) })
            addView(sizeText)
        }
        Ui.dialog(ctx)
            .setTitle("이미지로 내보내기")
            .setView(root)
            .setPositiveButton("다음") { _, _ ->
                exportFormat = fmt
                exportScale = scale
                exportPng()
            }
            .setNegativeButton("취소", null)
            .show()
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
            REQ_EXPORT_GIF -> writeGif(uri)
            REQ_EXPORT_MP4 -> writeMp4(uri)
            REQ_IMPORT_LAYER -> readImageLayer(uri)
            REQ_BRUSH_EXPORT -> {
                val b = pendingBrushExport ?: return
                pendingBrushExport = null
                io.execute {
                    try {
                        val bytes = library.exportBrush(b)
                        contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw IllegalStateException("파일을 열 수 없습니다.")
                        ui.post { showHud("보조 도구를 내보냈습니다") }
                    } catch (e: Exception) {
                        ui.post { Toast.makeText(this, "내보내지 못했습니다: ${e.message}", Toast.LENGTH_LONG).show() }
                    }
                }
            }
            REQ_BRUSH_IMPORT -> {
                val bytes = try {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (e: Exception) { null }
                val b = bytes?.takeIf { it.size < 8 * 1024 * 1024 }?.let { library.importBrush(it) }
                if (b == null) {
                    Toast.makeText(this, "보조 도구 파일(.dfbrush)이 아니거나 읽을 수 없습니다.", Toast.LENGTH_LONG).show()
                } else {
                    setTool(b.tool)
                    onBrushChanged()
                    showHud("${b.tool.label} · ${b.name} 을(를) 가져왔습니다")
                }
            }
            REQ_SUB_IMAGE -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    Log.w(TAG, "sub view permission", e)
                }
                settings.subUri = uri.toString()
                settings.save()
                subBitmap = null
                loadSubImage()
            }
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
        val jpg = exportFormat == 1
        val scale = exportScale
        renderer.captureFlattened { w, h, pixels ->
            io.execute {
                try {
                    contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        if (!jpg && scale == 100) {
                            ProjectIO.writePng(out, w, h, pixels)
                        } else {
                            var bmp = ProjectIO.toBitmap(w, h, pixels)
                            if (scale != 100) {
                                val s = android.graphics.Bitmap.createScaledBitmap(bmp, maxOf(1, w * scale / 100), maxOf(1, h * scale / 100), true)
                                if (s !== bmp) bmp.recycle()
                                bmp = s
                            }
                            if (jpg) {
                                // JPEG는 투명이 없으므로 흰 배경에 합침
                                val flat = android.graphics.Bitmap.createBitmap(bmp.width, bmp.height, android.graphics.Bitmap.Config.ARGB_8888)
                                android.graphics.Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
                                bmp.recycle()
                                bmp = flat
                            }
                            bmp.compress(if (jpg) android.graphics.Bitmap.CompressFormat.JPEG else android.graphics.Bitmap.CompressFormat.PNG, 92, out)
                            bmp.recycle()
                        }
                    } ?: throw IllegalStateException("파일을 열 수 없습니다.")
                    ui.post { showHud(if (jpg) "JPEG로 내보냈습니다" else "PNG로 내보냈습니다") }
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
            keepAutosaveHistory()
            if (!tmp.renameTo(autosaveFile)) {
                autosaveFile.delete()
                tmp.renameTo(autosaveFile)
            }
            // 자동 저장본이 마지막으로 저장한 파일과 같은 내용인지 기록 (복원 시 * 표시 판단)
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean(KEY_AUTOSAVE_CLEAN, clean).commit()
            ui.post { autosavedVersion = version }
            Log.i(TAG, "자동 저장 완료 (버전 $version)")
        } catch (e: Exception) {
            Log.e(TAG, "autosave failed", e)
        }
    }

    // ---- 자동 저장 기록 (잘못 덮어써도 이전 상태로) ----

    private val historyDir: File get() = File(filesDir, "autosave_history").apply { mkdirs() }

    /**
     * io 스레드. 지금 자동 저장본을 기록 폴더로 옮겨 둡니다 (가장 최근 기록보다 10분 이상 지났을 때만).
     * 최대 [AUTOSAVE_KEEP]개, 오래된 것부터 지움.
     */
    private fun keepAutosaveHistory() {
        val cur = autosaveFile
        if (!cur.exists()) return
        val files = historyDir.listFiles { f -> f.name.endsWith(".dfp") }?.sortedBy { it.lastModified() } ?: emptyList()
        val newest = files.lastOrNull()?.lastModified() ?: 0L
        if (cur.lastModified() - newest < 10 * 60 * 1000L) return
        val name = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(cur.lastModified())) + ".dfp"
        try {
            cur.copyTo(File(historyDir, name), overwrite = true)
        } catch (e: Exception) {
            Log.w(TAG, "autosave history copy failed", e)
            return
        }
        val all = historyDir.listFiles { f -> f.name.endsWith(".dfp") }?.sortedBy { it.lastModified() } ?: return
        for (i in 0 until all.size - AUTOSAVE_KEEP) all[i].delete()
    }

    /** 자동 저장 기록 목록에서 골라 엽니다 (지금 그림은 바뀌기 전에 자동 저장됨). */
    private fun chooseAutosaveHistory() {
        val files = (historyDir.listFiles { f -> f.name.endsWith(".dfp") }?.sortedByDescending { it.lastModified() } ?: emptyList())
        if (files.isEmpty()) {
            Toast.makeText(this, "아직 자동 저장 기록이 없습니다 (그리는 동안 10분 간격으로 남깁니다).", Toast.LENGTH_LONG).show()
            return
        }
        val fmt = java.text.SimpleDateFormat("M월 d일 HH:mm", java.util.Locale.KOREA)
        val labels = files.map { "${fmt.format(java.util.Date(it.lastModified()))} · ${it.length() / 1024}KB" }.toTypedArray()
        Ui.dialog(this)
            .setTitle("자동 저장 기록에서 열기")
            .setItems(labels) { _, i ->
                confirmIfDirty {
                    val f = files[i]
                    showHud("여는 중…")
                    val max = maxTex
                    io.execute {
                        try {
                            val doc = f.inputStream().use { ProjectIO.readDfp(it, max) }
                            ui.post {
                                renderer.loadDocument(doc) { version ->
                                    currentUri = null
                                    currentName = null
                                    savedVersion = -1L
                                    updateTitle()
                                    showHud("자동 저장 기록을 열었습니다. 필요하면 다른 이름으로 저장하세요")
                                }
                            }
                        } catch (e: Exception) {
                            ui.post { Toast.makeText(this, "열지 못했습니다: ${e.message}", Toast.LENGTH_LONG).show() }
                        }
                    }
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 자동 저장본을 여는 중: 이때 그린 획은 복원된 문서에 덮여 사라지므로 캔버스 입력을 막습니다. */
    private var restoring = false
    private val restoreTimeout = Runnable { restoring = false }
    override val inputBlocked: Boolean
        get() {
            if (restoring) showHud("그림을 불러오는 중입니다…")
            return restoring
        }

    private fun restoreAutosave() {
        val f = autosaveFile
        if (!f.exists()) return
        restoring = true
        // 렌더러가 메모리 부족 등으로 열지 못해도 영영 막히지 않게
        ui.postDelayed(restoreTimeout, 20_000)
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
                        restoring = false
                        ui.removeCallbacks(restoreTimeout)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "autosave restore failed", e)
                ui.post { restoring = false }
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
        private const val REQ_EXPORT_GIF = 15
        private const val REQ_SUB_IMAGE = 16
        private const val REQ_IMPORT_LAYER = 17
        private const val REQ_BRUSH_EXPORT = 18
        private const val REQ_BRUSH_IMPORT = 19
        private const val REQ_EXPORT_MP4 = 20
        /** MP4 긴 변 최대 크기 (px) */
        private const val MP4_MAX = 1280
        /** 자동 저장 기록 개수 */
        private const val AUTOSAVE_KEEP = 5
        /** GIF 긴 변 최대 크기 (px) */
        private const val GIF_MAX = 800
        private const val KEY_AUTOSAVE_CLEAN = "autosaveClean"
        /** 자동 저장 확인 주기 / 저장 간격 / 이만큼 입력이 없어야 저장 */
        private const val AUTOSAVE_CHECK_MS = 10_000L
        private const val AUTOSAVE_INTERVAL_MS = 120_000L
        private const val IDLE_MS = 3_000L
        private val ROTATE_STEP = (Math.PI / 12).toFloat()
    }
}
