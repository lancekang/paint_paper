package kr.dfluid.paint.input

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.KeyEvent
import android.view.MotionEvent
import kr.dfluid.paint.brush.Brush
import kr.dfluid.paint.brush.StrokeBuilder
import kr.dfluid.paint.brush.Tool
import kr.dfluid.paint.brush.TipImage
import kr.dfluid.paint.engine.CanvasRenderer
import kr.dfluid.paint.engine.SelShape
import kr.dfluid.paint.engine.Viewport
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow

enum class HoldMode { NONE, PAN, ROTATE, ZOOM, EYEDROPPER }

/**
 * GL 캔버스 + 모든 포인터 입력.
 *
 * - 스타일러스/마우스: 그리기, 스포이드, 홀드 키(이동/회전/확대)
 * - 손가락: 스타일러스를 쓴 적이 있으면 그리지 않고 제스처만 (팜 리젝션)
 *   1개 = 이동, 2개 = 이동·확대·회전, 2개 탭 = 실행취소, 3개 탭 = 다시실행
 */
@SuppressLint("ViewConstructor")
class CanvasView(context: Context, private val renderer: CanvasRenderer) : GLSurfaceView(context) {

    interface Host {
        val currentTool: Tool
        val holdMode: HoldMode
        val brushColor: Int
        val smoothing: Float
        val pressureGamma: Float
        val drawWithFinger: Boolean
        val symmetry: Symmetry
        val straightLine: Boolean
        /** 후보정 강도 (캔버스 px, 0 = 끔) */
        val postSmoothing: Float
        /** true면 캔버스 입력을 받지 않음 (문서를 불러오는 중) */
        val inputBlocked: Boolean
        /** 도형 도구: 종류(0 직선, 1 사각형, 2 타원, 3 올가미 채우기, 4 말풍선)와 채우기(0 선, 1 채우기, 2 둘 다) */
        val shapeKind: Int
        val shapeFill: Int
        /** 말풍선 안쪽 색 (보조색) */
        val balloonFillColor: Int
        /** 원근 자·동심원 자 */
        val ruler: GuideRuler
        /** 자 손잡이(소실점·중심)를 옮겼을 때 (안내선 다시 그리기) */
        fun onRulerChanged()
        fun brushFor(tool: Tool): Brush
        fun onStrokeStarted()
        fun onColorPicked(color: Int)
        fun onViewChanged()
        fun onUndoGesture()
        fun onRedoGesture()

        val transformActive: Boolean
        val selectShape: SelShape
        fun tipFor(brush: Brush): TipImage?
        fun onSelectPreview(shape: SelShape, pts: FloatArray)
        /** tiny = 거의 움직이지 않은 탭 (선택 해제로 처리) */
        fun onSelectDone(shape: SelShape, pts: FloatArray, tiny: Boolean)
        fun onFillTap(x: Float, y: Float)
        /** 채우기 도구가 둘러싸고 칠하기 모드인지 / 그 올가미가 끝났을 때 */
        val fillEnclose: Boolean
        fun onFillLasso(pts: FloatArray)
        /** 텍스트 도구로 누름 (캔버스 좌표) */
        fun onTextTap(x: Float, y: Float)
        /** 자동 선택 도구로 누름 (캔버스 좌표) */
        fun onWandTap(x: Float, y: Float)
        /** phase 0 = 진행, 1 = 확정, 2 = 취소 */
        fun onGradient(x0: Float, y0: Float, x1: Float, y1: Float, phase: Int)
        fun onMoveStart()
        fun onMoveDrag(dx: Float, dy: Float)
        fun onMoveEnd()
    }

    var host: Host? = null
    val viewport = Viewport()

    private enum class Mode { NONE, DRAW, PICK, DRAG_PAN, DRAG_ROTATE, DRAG_ZOOM, GESTURE, IGNORE, SELECT, GRADIENT, MOVE, RULER, SHAPE }

    // ---- 도형 ----
    private var shapeBrush: Brush? = null
    /** 지금 올가미가 둘러싸고 칠하기용인지 */
    private var fillLasso = false
    private val lassoPts = ArrayList<Float>()
    /** 마지막 도형 계산 결과 (말풍선은 펜을 뗄 때 채우기·선을 따로 확정) */
    private var lastLine = FloatArray(0)
    private var lastFill: FloatArray? = null

    // ---- 원근 자·동심원 자 ----
    // 획을 시작하고 RULER_DECIDE_DP 만큼 움직여야 방향을 알 수 있으므로, 그 전까지의 점은 모아 둡니다.
    private var rulerPending = false
    private var rulerC: GuideRuler.Constraint? = null
    private val pendingPts = ArrayList<FloatArray>() // [x, y, 필압, 기울기, 방향]
    private var strokeBrush: Brush? = null
    private var rulerHandle = -1
    private val rp = FloatArray(2)
    private val density = context.resources.displayMetrics.density

    /** 화면 좌표 (x,y) 가까이에 있는 자 손잡이 번호 (없으면 -1) */
    private fun rulerHandleAt(x: Float, y: Float, h: Host): Int {
        if (!h.ruler.on) return -1
        val hs = h.ruler.handles()
        val p = FloatArray(2)
        var best = -1
        var bestD = 26f * density
        for (k in 0 until hs.size / 2) {
            viewport.toScreen(hs[k * 2], hs[k * 2 + 1], p)
            val d = hypot(p[0] - x, p[1] - y)
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    /** 자가 켜져 있으면 획 시작을 미룹니다. 점 하나를 받아 빌더에 넣거나(방향 정해짐) 모읍니다. */
    private fun feedPoint(x: Float, y: Float, p: Float, tl: Float, an: Float, h: Host) {
        if (rulerPending) {
            pendingPts.add(floatArrayOf(x, y, p, tl, an))
            val s = pendingPts[0]
            if (hypot(x - s[0], y - s[1]) * viewport.scale >= RULER_DECIDE_DP * density) resolveRuler(h, free = false)
            return
        }
        val c = rulerC
        if (c != null) {
            c.project(x, y, rp)
            builder.add(rp[0], rp[1], p, tl, an)
        } else {
            builder.add(x, y, p, tl, an)
        }
    }

    /** 방향을 정하고 모아 둔 점들로 획을 시작합니다. free = true면 자 없이 (짧은 탭 등). */
    private fun resolveRuler(h: Host, free: Boolean) {
        val brush = strokeBrush ?: return
        val s = pendingPts.firstOrNull() ?: return
        val last = pendingPts.last()
        rulerC = if (free) null else h.ruler.constraintFor(s[0], s[1], last[0] - s[0], last[1] - s[1])
        rulerPending = false
        builder.begin(brush, h.smoothing, s[0], s[1], s[2], s[3], s[4])
        // 자에 붙은 선은 후보정하면 휘므로 끔
        taper.begin(brush.taperIn, brush.taperOut, if (rulerC == null) h.postSmoothing / viewport.scale else 0f)
        for (k in 1 until pendingPts.size) {
            val q = pendingPts[k]
            feedPoint(q[0], q[1], q[2], q[3], q[4], h)
        }
        pendingPts.clear()
    }

    private var mode = Mode.NONE
    private var primaryId = -1
    private var primaryIsPen = false
    /** 스타일러스를 한 번이라도 썼는지 (팜 리젝션 기준). */
    var stylusSeen = false
        private set
    private val builder = StrokeBuilder()
    // 선 입·출 처리
    private val taper = StrokeTaper()

    // 직선 자
    private var lineMode = false
    private var lineBrush: Brush? = null
    private var lineStartX = 0f
    private var lineStartY = 0f
    private var linePressure = 1f
    private var lineTilt = 0f
    private var lineAngle = 0f
    private val tmp = FloatArray(2)
    private var lastX = 0f
    private var lastY = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var sizedOnce = false
    private var selShape = SelShape.RECT
    private val selPts = ArrayList<Float>()
    private var downCx = 0f
    private var downCy = 0f

    // 두 손가락 제스처
    private var g1 = -1
    private var g2 = -1
    private var gcx = 0f
    private var gcy = 0f
    private var gDist = 1f
    private var gAngle = 0f

    // 손가락 탭 (실행취소/다시실행)
    private var tapStart = 0L
    private var tapMaxFingers = 0
    private var tapMoved = false
    private var tapPenInvolved = false
    private val downPos = HashMap<Int, FloatArray>()

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        renderer.requestRender = { requestRender() }
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewport.setScreen(w, h)
        if (!sizedOnce) {
            sizedOnce = true
            viewport.fit()
        }
        pushView()
    }

    /** 새 문서가 들어왔을 때. */
    fun onDocumentSize(w: Int, h: Int, refit: Boolean) {
        viewport.setCanvas(w, h)
        if (refit) viewport.fit()
        pushView()
    }

    fun pushView() {
        renderer.setView(viewport.toGl())
        host?.onViewChanged()
    }

    // =====================================================================
    // 터치
    // =====================================================================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val h = host ?: return false
        renderer.hideCursor()
        if (e.actionMasked == MotionEvent.ACTION_DOWN && h.inputBlocked) {
            mode = Mode.IGNORE
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapStart = e.eventTime
                tapMaxFingers = 0
                tapMoved = false
                tapPenInvolved = false
                downPos.clear()
                pointerDown(e, 0, h)
            }
            MotionEvent.ACTION_POINTER_DOWN -> pointerDown(e, e.actionIndex, h)
            MotionEvent.ACTION_MOVE -> move(e, h)
            MotionEvent.ACTION_POINTER_UP -> pointerUp(e, e.actionIndex, h)
            MotionEvent.ACTION_UP -> {
                pointerUp(e, 0, h)
                val quick = e.eventTime - tapStart < TAP_MS
                if (quick && !tapMoved && !tapPenInvolved) {
                    if (tapMaxFingers == 2) h.onUndoGesture()
                    else if (tapMaxFingers == 3) h.onRedoGesture()
                }
                mode = Mode.NONE
                primaryId = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                when (mode) {
                    Mode.DRAW -> { lineMode = false; lineBrush = null; renderer.cancelStroke() }
                    Mode.SHAPE -> { shapeBrush = null; renderer.cancelStroke() }
                    Mode.GRADIENT -> h.onGradient(0f, 0f, 0f, 0f, 2)
                    Mode.SELECT -> h.onSelectPreview(selShape, FloatArray(0))
                    Mode.MOVE -> h.onMoveEnd()
                    else -> Unit
                }
                mode = Mode.NONE
                primaryId = -1
            }
        }
        return true
    }

    private fun isPenLike(e: MotionEvent, i: Int): Boolean {
        val t = e.getToolType(i)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER || t == MotionEvent.TOOL_TYPE_MOUSE
    }

    private fun fingerCount(e: MotionEvent, excludeIndex: Int = -1): Int {
        var n = 0
        for (i in 0 until e.pointerCount) {
            if (i != excludeIndex && e.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER) n++
        }
        return n
    }

    private fun pointerDown(e: MotionEvent, i: Int, h: Host) {
        val id = e.getPointerId(i)
        downPos[id] = floatArrayOf(e.getX(i), e.getY(i))
        if (isPenLike(e, i)) {
            tapPenInvolved = true
            if (e.getToolType(i) != MotionEvent.TOOL_TYPE_MOUSE) stylusSeen = true
            requestUnbufferedDispatch(e)
            if (primaryIsPen && penBusy()) return
            // 손바닥이 먼저 닿아 있었다면 그 동작을 취소하고 펜을 우선
            when (mode) {
                Mode.DRAW, Mode.SHAPE -> renderer.cancelStroke()
                Mode.GRADIENT -> h.onGradient(0f, 0f, 0f, 0f, 2)
                Mode.SELECT -> h.onSelectPreview(selShape, FloatArray(0))
                Mode.MOVE -> h.onMoveEnd()
                else -> Unit
            }
            startPrimary(e, i, pen = true, h = h)
            return
        }

        // 손가락
        val fingers = fingerCount(e)
        tapMaxFingers = maxOf(tapMaxFingers, fingers)
        if (primaryIsPen && penBusy()) return // 펜 사용 중 손바닥
        if (mode == Mode.IGNORE) return
        if (fingers >= 2) {
            when (mode) {
                Mode.DRAW, Mode.SHAPE -> renderer.cancelStroke()
                Mode.GRADIENT -> h.onGradient(0f, 0f, 0f, 0f, 2)
                Mode.SELECT -> h.onSelectPreview(selShape, FloatArray(0))
                Mode.MOVE -> h.onMoveEnd()
                else -> Unit
            }
            if (mode != Mode.GESTURE) startGesture(e)
            return
        }
        if (mode == Mode.NONE) {
            if (h.drawWithFinger || !stylusSeen) {
                startPrimary(e, i, pen = false, h = h)
            } else {
                mode = Mode.DRAG_PAN
                primaryId = id
                primaryIsPen = false
                lastX = e.getX(i); lastY = e.getY(i)
            }
        }
    }

    private fun penBusy() = mode != Mode.NONE && mode != Mode.IGNORE && mode != Mode.GESTURE && primaryId >= 0

    private fun startPrimary(e: MotionEvent, i: Int, pen: Boolean, h: Host) {
        // 취소된 이전 올가미의 표시가 남지 않게 매번 새로 정함
        fillLasso = false
        primaryId = e.getPointerId(i)
        primaryIsPen = pen
        val x = e.getX(i)
        val y = e.getY(i)
        lastX = x; lastY = y
        anchorX = x; anchorY = y
        val stylusButton = pen && (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val tool = h.currentTool
        viewport.toCanvas(x, y, tmp)
        downCx = tmp[0]; downCy = tmp[1]
        mode = when {
            h.holdMode == HoldMode.PAN || tool == Tool.HAND -> Mode.DRAG_PAN
            h.holdMode == HoldMode.ROTATE -> Mode.DRAG_ROTATE
            h.holdMode == HoldMode.ZOOM -> Mode.DRAG_ZOOM
            // 변형 중에는 상자 밖을 눌러도 그리거나 선택하지 않습니다.
            h.transformActive && tool != Tool.MOVE -> Mode.IGNORE
            // 자 손잡이 위에서 시작하면 그리지 않고 손잡이를 옮깁니다.
            rulerHandleAt(x, y, h).also { rulerHandle = it } >= 0 -> Mode.RULER
            // 선택 도구에서 Alt는 "빼기"라서 스포이드로 바꾸지 않습니다.
            tool == Tool.SELECT && h.selectShape == SelShape.WAND -> {
                h.onWandTap(downCx, downCy)
                Mode.IGNORE
            }
            tool == Tool.SELECT -> Mode.SELECT
            h.holdMode == HoldMode.EYEDROPPER || tool == Tool.EYEDROPPER || stylusButton -> Mode.PICK
            tool == Tool.MOVE -> Mode.MOVE
            tool == Tool.GRADIENT -> Mode.GRADIENT
            tool == Tool.FILL && h.fillEnclose -> {
                fillLasso = true
                Mode.SELECT
            }
            tool == Tool.FILL -> {
                h.onFillTap(downCx, downCy)
                Mode.IGNORE
            }
            tool == Tool.TEXT -> {
                h.onTextTap(downCx, downCy)
                Mode.IGNORE
            }
            tool == Tool.SHAPE && e.getToolType(i) != MotionEvent.TOOL_TYPE_ERASER -> Mode.SHAPE
            tool.isBrush || e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER -> Mode.DRAW
            else -> Mode.IGNORE
        }
        when (mode) {
            Mode.SELECT -> {
                selShape = if (fillLasso) SelShape.LASSO else h.selectShape
                selPts.clear()
                selPts.add(downCx); selPts.add(downCy)
                if (selShape != SelShape.LASSO) { selPts.add(downCx); selPts.add(downCy) }
                h.onSelectPreview(selShape, selPts.toFloatArray())
            }
            Mode.MOVE -> h.onMoveStart()
            Mode.SHAPE -> {
                val brush = h.brushFor(Tool.SHAPE).deepCopy()
                shapeBrush = brush
                lassoPts.clear()
                lassoPts.add(downCx); lassoPts.add(downCy)
                renderer.beginStroke(brush, h.brushColor, h.tipFor(brush))
                h.onStrokeStarted()
            }
            Mode.PICK -> pick(x, y, h)
            Mode.DRAW -> {
                val eraserTip = e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER
                val brush = (if (eraserTip) h.brushFor(Tool.ERASER) else h.brushFor(tool)).deepCopy()
                viewport.toCanvas(x, y, tmp)
                val p = pressure(e, i, -1, h); val tl = tilt(e, i, -1); val an = angle(e, i, -1)
                renderer.beginStroke(brush, h.brushColor, h.tipFor(brush))
                lineMode = h.straightLine
                if (lineMode) {
                    lineBrush = brush
                    lineStartX = tmp[0]; lineStartY = tmp[1]
                    linePressure = p; lineTilt = tl; lineAngle = an
                    updateLine(tmp[0], tmp[1], h)
                } else if (h.ruler.on) {
                    // 방향이 정해질 때까지 모읍니다.
                    strokeBrush = brush
                    rulerPending = true
                    rulerC = null
                    pendingPts.clear()
                    pendingPts.add(floatArrayOf(tmp[0], tmp[1], p, tl, an))
                } else {
                    rulerPending = false
                    rulerC = null
                    builder.begin(brush, h.smoothing, tmp[0], tmp[1], p, tl, an)
                    taper.begin(brush.taperIn, brush.taperOut, h.postSmoothing / viewport.scale)
                    builder.drain()?.let { emitStamps(taper.push(it), h) }
                }
                h.onStrokeStarted()
            }
            else -> Unit
        }
    }

    /** 스탬프를 스트로크 버퍼에 보냅니다. 대칭이 켜져 있으면 미러/회전 복제본도 함께 보냅니다. */
    private fun emitStamps(stamps: FloatArray, h: Host) {
        renderer.addStamps(stamps)
        val sym = h.symmetry
        if (sym.on) {
            val cx = viewport.canvasW / 2f
            val cy = viewport.canvasH / 2f
            sym.mirror(stamps, cx, cy).forEachIndexed { i, m -> renderer.addStamps(m, i + 1) }
        }
    }

    /** 직선 자: 시작점→(cx,cy) 직선을 만들어 스트로크 버퍼를 통째로 갱신합니다. 대칭 복제본·입출 포함. */
    private fun updateLine(cx: Float, cy: Float, h: Host) {
        val brush = lineBrush ?: return
        var ex = cx
        var ey = cy
        // 자가 켜져 있으면 끝점을 원근선/동심원 위로 (동심원은 원호 대신 시작점과 같은 반지름의 점으로)
        h.ruler.constraintFor(lineStartX, lineStartY, cx - lineStartX, cy - lineStartY)?.let {
            it.project(cx, cy, rp); ex = rp[0]; ey = rp[1]
        }
        builder.begin(brush, 0f, lineStartX, lineStartY, linePressure, lineTilt, lineAngle)
        builder.add(ex, ey, linePressure, lineTilt, lineAngle)
        var base = builder.drain() ?: FloatArray(0)
        if (brush.taperIn > 0f || brush.taperOut > 0f) {
            taper.begin(brush.taperIn, brush.taperOut)
            taper.push(base)
            base = taper.renderAll()
        }
        replaceStroke(base, h)
    }

    /** 스트로크 버퍼 전체를 [base] (+ 대칭 복제본)로 바꿉니다. */
    private fun replaceStroke(base: FloatArray, h: Host) {
        val mirrors = if (h.symmetry.on) h.symmetry.mirror(base, viewport.canvasW / 2f, viewport.canvasH / 2f) else emptyList()
        if (mirrors.isEmpty()) {
            renderer.setStrokeLine(base)
        } else {
            var total = base.size
            for (m in mirrors) total += m.size
            val combined = FloatArray(total)
            var off = 0
            System.arraycopy(base, 0, combined, off, base.size); off += base.size
            for (m in mirrors) { System.arraycopy(m, 0, combined, off, m.size); off += m.size }
            renderer.setStrokeLine(combined)
        }
    }

    /**
     * 도형 미리보기: 시작점(downCx, downCy) → (cx, cy). Shift = 정사각형·정원·45°, Alt = 시작점이 중심.
     * 선은 스탬프로, 채우기는 다각형으로 렌더러에 넘깁니다 (스트로크 버퍼를 통째로 바꿈).
     */
    private fun updateShape(cx: Float, cy: Float, meta: Int, h: Host) {
        val brush = shapeBrush ?: return
        val kind = h.shapeKind
        val shift = (meta and KeyEvent.META_SHIFT_ON) != 0
        val alt = (meta and KeyEvent.META_ALT_ON) != 0
        var x1 = cx
        var y1 = cy
        val outline: FloatArray
        val closed: Boolean
        when (kind) {
            0 -> {
                if (shift) {
                    val len = hypot(x1 - downCx, y1 - downCy)
                    val a = (Math.round(atan2(y1 - downCy, x1 - downCx) / (PI / 4)) * (PI / 4)).toFloat()
                    x1 = downCx + len * kotlin.math.cos(a)
                    y1 = downCy + len * kotlin.math.sin(a)
                }
                outline = floatArrayOf(downCx, downCy, x1, y1)
                closed = false
            }
            3 -> {
                val n = lassoPts.size
                if (hypot(cx - lassoPts[n - 2], cy - lassoPts[n - 1]) * viewport.scale > 2f) {
                    lassoPts.add(cx); lassoPts.add(cy)
                }
                outline = lassoPts.toFloatArray()
                closed = true
            }
            else -> {
                var dx = x1 - downCx
                var dy = y1 - downCy
                if (shift) {
                    val m = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    dx = if (dx < 0) -m else m
                    dy = if (dy < 0) -m else m
                }
                val l = if (alt) downCx - kotlin.math.abs(dx) else minOf(downCx, downCx + dx)
                val r = if (alt) downCx + kotlin.math.abs(dx) else maxOf(downCx, downCx + dx)
                val t = if (alt) downCy - kotlin.math.abs(dy) else minOf(downCy, downCy + dy)
                val b = if (alt) downCy + kotlin.math.abs(dy) else maxOf(downCy, downCy + dy)
                outline = when (kind) {
                    1 -> floatArrayOf(l, t, r, t, r, b, l, b)
                    4 -> balloonPoints(l, t, r, b)
                    else -> ellipsePoints(l, t, r, b)
                }
                closed = true
            }
        }
        val fillMode = h.shapeFill
        val wantFill = kind == 3 || (kind != 0 && fillMode >= 1)
        val wantLine = kind == 0 || (kind != 3 && fillMode != 1)
        var stamps = FloatArray(0)
        if (wantLine && outline.size >= 4) {
            builder.begin(brush, 0f, outline[0], outline[1], 1f, 0f, 0f)
            var k = 2
            while (k + 1 < outline.size) {
                builder.add(outline[k], outline[k + 1], 1f, 0f, 0f); k += 2
            }
            if (closed) builder.add(outline[0], outline[1], 1f, 0f, 0f)
            builder.finish()
            stamps = builder.drain() ?: FloatArray(0)
            if (!closed && (brush.taperIn > 0f || brush.taperOut > 0f)) {
                taper.begin(brush.taperIn, brush.taperOut)
                taper.push(stamps)
                stamps = taper.renderAll()
            }
        }
        val mirrors = if (h.symmetry.on) h.symmetry.mirror(stamps, viewport.canvasW / 2f, viewport.canvasH / 2f) else emptyList()
        var combined = stamps
        if (mirrors.isNotEmpty()) {
            combined = FloatArray(stamps.size + mirrors.sumOf { it.size })
            System.arraycopy(stamps, 0, combined, 0, stamps.size)
            var off = stamps.size
            for (m in mirrors) { System.arraycopy(m, 0, combined, off, m.size); off += m.size }
        }
        lastLine = combined
        lastFill = if (wantFill && outline.size >= 6) outline else null
        // 말풍선은 미리보기에서 채우기를 빼고 선만 (채우기 색이 다르므로 확정 때 따로)
        renderer.setStrokeLine(combined, if (kind == 4) null else lastFill)
    }

    /** 말풍선: 타원 + 아래 왼쪽으로 뾰족한 꼬리. 꼬리가 붙는 둘레 구간을 꼭짓점으로 바꿉니다. */
    private fun balloonPoints(l: Float, t: Float, r: Float, b: Float): FloatArray {
        val e = ellipsePoints(l, t, r, b)
        val n = e.size / 2
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        val rx = (r - l) / 2f; val ry = (b - t) / 2f
        // 각도는 오른쪽 0, 아래쪽 90° (화면 좌표). 꼬리는 100°~125° 사이에서 나감
        val a0 = 100.0 * PI / 180.0; val a1 = 125.0 * PI / 180.0
        val tipX = cx - rx * 0.55f; val tipY = cy + ry * 1.55f
        val out = ArrayList<Float>(e.size + 2)
        var tipAdded = false
        for (k in 0 until n) {
            val a = 2 * PI * k / n
            if (a in a0..a1) {
                if (!tipAdded) { out.add(tipX); out.add(tipY); tipAdded = true }
                continue
            }
            out.add(e[k * 2]); out.add(e[k * 2 + 1])
        }
        return out.toFloatArray()
    }

    /** 타원 둘레 점 (화면에서 약 3px 간격). */
    private fun ellipsePoints(l: Float, t: Float, r: Float, b: Float): FloatArray {
        val rx = (r - l) / 2f
        val ry = (b - t) / 2f
        val perim = (PI * (3 * (rx + ry) - kotlin.math.sqrt(((3 * rx + ry) * (rx + 3 * ry)).toDouble()))).toFloat()
        val n = (perim * viewport.scale / 3f).toInt().coerceIn(24, 720)
        val out = FloatArray(n * 2)
        for (k in 0 until n) {
            val a = 2 * PI * k / n
            out[k * 2] = l + rx + rx * kotlin.math.cos(a).toFloat()
            out[k * 2 + 1] = t + ry + ry * kotlin.math.sin(a).toFloat()
        }
        return out
    }

    private fun move(e: MotionEvent, h: Host) {
        for (i in 0 until e.pointerCount) {
            val p = downPos[e.getPointerId(i)] ?: continue
            if (hypot(e.getX(i) - p[0], e.getY(i) - p[1]) > TAP_SLOP) tapMoved = true
        }
        when (mode) {
            Mode.DRAW -> {
                val i = e.findPointerIndex(primaryId)
                if (i < 0) return
                renderer.perf.markInput(e.eventTime)
                if (lineMode) {
                    viewport.toCanvas(e.getX(i), e.getY(i), tmp)
                    updateLine(tmp[0], tmp[1], h)
                } else {
                    for (k in 0 until e.historySize) {
                        viewport.toCanvas(e.getHistoricalX(i, k), e.getHistoricalY(i, k), tmp)
                        feedPoint(tmp[0], tmp[1], pressure(e, i, k, h), tilt(e, i, k), angle(e, i, k), h)
                    }
                    viewport.toCanvas(e.getX(i), e.getY(i), tmp)
                    feedPoint(tmp[0], tmp[1], pressure(e, i, -1, h), tilt(e, i, -1), angle(e, i, -1), h)
                    if (!rulerPending) builder.drain()?.let { emitStamps(taper.push(it), h) }
                }
            }
            Mode.SHAPE -> {
                val i = e.findPointerIndex(primaryId)
                if (i < 0) return
                viewport.toCanvas(e.getX(i), e.getY(i), tmp)
                updateShape(tmp[0], tmp[1], e.metaState, h)
            }
            Mode.RULER -> {
                val i = e.findPointerIndex(primaryId)
                if (i >= 0 && rulerHandle >= 0) {
                    viewport.toCanvas(e.getX(i), e.getY(i), tmp)
                    h.ruler.moveHandle(rulerHandle, tmp[0], tmp[1])
                    h.onRulerChanged()
                }
            }
            Mode.PICK -> {
                val i = e.findPointerIndex(primaryId)
                if (i >= 0) pick(e.getX(i), e.getY(i), h)
            }
            Mode.DRAG_PAN -> {
                val i = e.findPointerIndex(primaryId)
                if (i < 0) return
                viewport.pan(e.getX(i) - lastX, e.getY(i) - lastY)
                lastX = e.getX(i); lastY = e.getY(i)
                pushView()
            }
            Mode.DRAG_ROTATE -> {
                val i = e.findPointerIndex(primaryId)
                if (i < 0) return
                val cx = width / 2f
                val cy = height / 2f
                val a0 = atan2(lastY - cy, lastX - cx)
                val a1 = atan2(e.getY(i) - cy, e.getX(i) - cx)
                viewport.rotateAt(wrap(a1 - a0), cx, cy)
                lastX = e.getX(i); lastY = e.getY(i)
                pushView()
            }
            Mode.DRAG_ZOOM -> {
                val i = e.findPointerIndex(primaryId)
                if (i < 0) return
                val dy = e.getY(i) - lastY
                val dx = e.getX(i) - lastX
                viewport.zoomAt(exp((dx - dy) * 0.006f), anchorX, anchorY)
                lastX = e.getX(i); lastY = e.getY(i)
                pushView()
            }
            Mode.GESTURE -> gestureMove(e)
            Mode.SELECT -> {
                val i = e.findPointerIndex(primaryId)
                if (i < 0) return
                viewport.toCanvas(e.getX(i), e.getY(i), tmp)
                if (selShape == SelShape.LASSO) {
                    val n = selPts.size
                    if (n < 2 || hypot(tmp[0] - selPts[n - 2], tmp[1] - selPts[n - 1]) * viewport.scale > 3f) {
                        selPts.add(tmp[0]); selPts.add(tmp[1])
                    }
                } else {
                    selPts[2] = tmp[0]; selPts[3] = tmp[1]
                }
                h.onSelectPreview(selShape, selPts.toFloatArray())
            }
            Mode.GRADIENT -> {
                val i = e.findPointerIndex(primaryId)
                if (i < 0) return
                viewport.toCanvas(e.getX(i), e.getY(i), tmp)
                h.onGradient(downCx, downCy, tmp[0], tmp[1], 0)
            }
            Mode.MOVE -> {
                val i = e.findPointerIndex(primaryId)
                if (i < 0) return
                viewport.toCanvas(lastX, lastY, tmp)
                val ax = tmp[0]; val ay = tmp[1]
                viewport.toCanvas(e.getX(i), e.getY(i), tmp)
                h.onMoveDrag(tmp[0] - ax, tmp[1] - ay)
                lastX = e.getX(i); lastY = e.getY(i)
            }
            else -> Unit
        }
    }

    private fun pointerUp(e: MotionEvent, i: Int, h: Host) {
        val id = e.getPointerId(i)
        if (mode == Mode.GESTURE && (id == g1 || id == g2)) {
            // 남은 손가락이 2개 이상이면 그 둘로 계속, 아니면 모두 뗄 때까지 무시
            if (fingerCount(e, excludeIndex = i) >= 2) startGesture(e, excludeIndex = i) else mode = Mode.IGNORE
            return
        }
        if (id != primaryId) return
        when (mode) {
            Mode.DRAW -> {
                if (lineMode) {
                    lineMode = false
                    lineBrush = null
                } else {
                    // 거의 움직이지 않았으면(점 찍기) 자 없이 그립니다.
                    if (rulerPending) resolveRuler(h, free = true)
                    builder.finish()
                    builder.drain()?.let { emitStamps(taper.push(it), h) }
                    // 출: 끝이 정해졌으니 획 전체를 끝이 가늘어지게 다시 그립니다.
                    taper.finish()?.let { replaceStroke(it, h) }
                }
                renderer.endStroke()
            }
            Mode.SELECT -> {
                val pts = selPts.toFloatArray()
                val tiny = if (selShape == SelShape.LASSO) pts.size < 6
                else hypot(pts[2] - pts[0], pts[3] - pts[1]) * viewport.scale < 4f
                if (fillLasso) {
                    fillLasso = false
                    h.onSelectPreview(selShape, FloatArray(0))
                    if (!tiny) h.onFillLasso(pts)
                } else h.onSelectDone(selShape, pts, tiny)
            }
            Mode.GRADIENT -> {
                viewport.toCanvas(e.getX(i), e.getY(i), tmp)
                // 거의 움직이지 않은 탭이면 전체가 한 색으로 칠해지므로 취소합니다.
                val tiny = hypot(tmp[0] - downCx, tmp[1] - downCy) * viewport.scale < 4f
                h.onGradient(downCx, downCy, tmp[0], tmp[1], if (tiny) 2 else 1)
            }
            Mode.MOVE -> h.onMoveEnd()
            Mode.SHAPE -> {
                viewport.toCanvas(e.getX(i), e.getY(i), tmp)
                val tiny = hypot(tmp[0] - downCx, tmp[1] - downCy) * viewport.scale < 4f
                if (tiny) renderer.cancelStroke()
                else {
                    updateShape(tmp[0], tmp[1], e.metaState, h)
                    val fill = lastFill
                    val brush = shapeBrush
                    if (h.shapeKind == 4 && fill != null && brush != null) {
                        // 말풍선: 안쪽(보조색)을 먼저, 테두리(주색)를 그 위에 따로 확정
                        renderer.cancelStroke()
                        renderer.beginStroke(brush, h.balloonFillColor, h.tipFor(brush))
                        renderer.setStrokeLine(FloatArray(0), fill)
                        renderer.endStroke()
                        if (lastLine.isNotEmpty()) {
                            renderer.beginStroke(brush, h.brushColor, h.tipFor(brush))
                            renderer.setStrokeLine(lastLine, null)
                            renderer.endStroke()
                        }
                    } else renderer.endStroke()
                }
                shapeBrush = null
            }
            else -> Unit
        }
        mode = if (e.actionMasked == MotionEvent.ACTION_UP) Mode.NONE else Mode.IGNORE
        primaryId = -1
    }

    private fun startGesture(e: MotionEvent, excludeIndex: Int = -1) {
        var a = -1
        var b = -1
        for (i in 0 until e.pointerCount) {
            if (i == excludeIndex || e.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) continue
            if (a < 0) a = i else if (b < 0) b = i
        }
        if (a < 0 || b < 0) return
        g1 = e.getPointerId(a)
        g2 = e.getPointerId(b)
        mode = Mode.GESTURE
        primaryId = -1
        readGesture(e, a, b)
    }

    private fun readGesture(e: MotionEvent, a: Int, b: Int) {
        val x1 = e.getX(a); val y1 = e.getY(a)
        val x2 = e.getX(b); val y2 = e.getY(b)
        gcx = (x1 + x2) / 2; gcy = (y1 + y2) / 2
        gDist = hypot(x2 - x1, y2 - y1).coerceAtLeast(1f)
        gAngle = atan2(y2 - y1, x2 - x1)
    }

    private fun gestureMove(e: MotionEvent) {
        val a = e.findPointerIndex(g1)
        val b = e.findPointerIndex(g2)
        if (a < 0 || b < 0) return
        val pcx = gcx; val pcy = gcy; val pd = gDist; val pa = gAngle
        readGesture(e, a, b)
        viewport.pan(gcx - pcx, gcy - pcy)
        viewport.zoomAt(gDist / pd, gcx, gcy)
        viewport.rotateAt(wrap(gAngle - pa), gcx, gcy)
        pushView()
    }

    private fun pick(x: Float, y: Float, h: Host) {
        viewport.toCanvas(x, y, tmp)
        renderer.pickColor(tmp[0].toInt(), tmp[1].toInt()) { h.onColorPicked(it) }
    }

    // =====================================================================
    // 필압 / 틸트
    // =====================================================================

    /** k = -1 이면 현재 샘플, 아니면 히스토리 인덱스 */
    private fun pressure(e: MotionEvent, i: Int, k: Int, h: Host): Float {
        val t = e.getToolType(i)
        if (t != MotionEvent.TOOL_TYPE_STYLUS && t != MotionEvent.TOOL_TYPE_ERASER) return 1f
        val raw = if (k < 0) e.getPressure(i) else e.getHistoricalPressure(i, k)
        return raw.coerceIn(0f, 1f).pow(h.pressureGamma)
    }

    private fun tilt(e: MotionEvent, i: Int, k: Int): Float {
        val raw = if (k < 0) e.getAxisValue(MotionEvent.AXIS_TILT, i)
        else e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, k)
        return raw.coerceIn(0f, 1.4f)
    }

    /** 펜이 기운 방향을 캔버스 좌표계 각도로. AXIS_ORIENTATION 0 = 화면 위쪽, 시계방향 +. */
    private fun angle(e: MotionEvent, i: Int, k: Int): Float {
        val o = if (k < 0) e.getAxisValue(MotionEvent.AXIS_ORIENTATION, i)
        else e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, i, k)
        var a = o - (PI / 2).toFloat() - viewport.rotation
        if (viewport.flipped) a = PI.toFloat() - a
        return a
    }

    // =====================================================================
    // 호버(브러시 크기 원) & 마우스 휠
    // =====================================================================

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val h = host ?: return false
        val tt = e.getToolType(0)
        if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) stylusSeen = true
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val tool = if (e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) Tool.ERASER else h.currentTool
                if (tool.isBrush && h.holdMode == HoldMode.NONE) {
                    val r = h.brushFor(tool).size / 2f * viewport.scale
                    renderer.setCursor(e.x, e.y, r)
                } else {
                    renderer.hideCursor()
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> renderer.hideCursor()
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                if ((e.metaState and KeyEvent.META_CTRL_ON) != 0 || host?.holdMode == HoldMode.ROTATE) {
                    viewport.rotateAt(v * (PI / 12).toFloat(), e.x, e.y)
                } else {
                    viewport.zoomAt(1.15f.pow(v), e.x, e.y)
                }
                pushView()
                return true
            }
        }
        return super.onGenericMotionEvent(e)
    }

    private fun wrap(a: Float): Float {
        var d = a
        while (d > PI) d -= (2 * PI).toFloat()
        while (d < -PI) d += (2 * PI).toFloat()
        return d
    }

    companion object {
        /** 자 방향을 정하기 위해 움직여야 하는 거리 (화면 dp) */
        const val RULER_DECIDE_DP = 10f
        private const val TAP_MS = 280L
        private const val TAP_SLOP = 24f
    }
}
