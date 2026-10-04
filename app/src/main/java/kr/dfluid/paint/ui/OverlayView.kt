package kr.dfluid.paint.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kr.dfluid.paint.engine.SelShape
import kr.dfluid.paint.engine.Viewport
import kr.dfluid.paint.input.SymMode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * GL 캔버스 위에 그리는 안내선과 손잡이:
 * - 선택 도구로 그리는 중인 도형
 * - 그라데이션 방향선
 * - 자유 변형 상자 (손잡이 드래그로 이동·크기·회전)
 *
 * 변형 중이 아니면 터치를 가로채지 않습니다.
 */
@SuppressLint("ViewConstructor")
class OverlayView(context: Context, private val viewport: Viewport) : View(context) {

    interface Listener {
        /** m = [a,b,c,d,tx,ty] */
        fun onTransformChanged(m: FloatArray)
        val keepAspect: Boolean
    }

    var listener: Listener? = null
    /** 스타일러스를 쓴 적이 있으면 손가락(손바닥)으로는 상자를 잡지 않습니다. */
    var stylusSeen: () -> Boolean = { false }

    // ---- 선택 / 그라데이션 미리보기 (캔버스 좌표) ----
    private var selShape: SelShape? = null
    private var selPts: FloatArray = FloatArray(0)
    private var gradient: FloatArray? = null

    // ---- 대칭 안내선 ----
    // 패널을 끌 때 스냅 안내선 (화면 좌표)
    private var layoutGuideX: Float? = null
    private var layoutGuideY: Float? = null
    private val layoutGuide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(context, 1.5f).toFloat()
        color = 0xFF2F6BD8.toInt()
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    fun showLayoutGuides(x: Float?, y: Float?) {
        if (x == layoutGuideX && y == layoutGuideY) return
        layoutGuideX = x
        layoutGuideY = y
        invalidate()
    }

    /** 원근 자·동심원 자 (MainActivity가 연결) */
    var ruler: kr.dfluid.paint.input.GuideRuler? = null

    private var symMode = SymMode.OFF
    private var symCount = 6

    // ---- 변형 상태 ----
    var transformActive = false
        private set
    private var tw = 1f
    private var th = 1f
    private var cx = 0f
    private var cy = 0f
    private var rot = 0f
    private var sx = 1f
    private var sy = 1f
    /** 자유 모서리(원근) 모드: 네 모서리를 캔버스 좌표로 따로 가짐 (왼위, 오위, 오아래, 왼아래) */
    var distort = false
        private set
    private val quad = FloatArray(8)
    private var gi = -1

    private enum class Grab { NONE, MOVE, ROTATE, CORNER, EDGE }

    private var grab = Grab.NONE
    private var gax = 0
    private var gay = 0
    private var lastX = 0f
    private var lastY = 0f
    private var lastAngle = 0f

    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(context, 1.5f).toFloat()
        color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(context, 3f).toFloat()
        color = 0x99000000.toInt()
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(context, 1.5f).toFloat()
        color = 0xFF2F6BD8.toInt()
    }
    private val symLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(context, 1f).toFloat()
        color = 0xCC35C4D8.toInt()
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val rulerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(context, 1f).toFloat()
        color = 0x6635C4D8
    }
    private val rulerMain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(context, 1.5f).toFloat()
        color = 0xCC35C4D8.toInt()
    }
    private val handleR = Ui.dp(context, 7f).toFloat()
    private val touchR = Ui.dp(context, 22f).toFloat()
    private val tmp = FloatArray(2)
    private val path = Path()

    // =====================================================================
    // 선택 / 그라데이션
    // =====================================================================

    fun showSelection(shape: SelShape, pts: FloatArray) {
        selShape = shape
        selPts = pts
        invalidate()
    }

    fun showGradient(x0: Float, y0: Float, x1: Float, y1: Float) {
        gradient = floatArrayOf(x0, y0, x1, y1)
        invalidate()
    }

    fun clearGuides() {
        selShape = null
        gradient = null
        invalidate()
    }

    /** 대칭 안내선 갱신. 중심·범위는 뷰포트의 캔버스 크기를 씁니다. */
    fun showSymmetry(mode: SymMode, count: Int) {
        symMode = mode
        symCount = count
        invalidate()
    }

    // =====================================================================
    // 변형
    // =====================================================================

    fun startTransform(w: Int, h: Int, m: FloatArray) {
        distort = false
        tw = w.toFloat(); th = h.toFloat()
        // m은 이동만 있는 초기 행렬이라 중심/배율/회전으로 쉽게 분해됩니다.
        sx = hypot(m[0], m[1]).coerceAtLeast(1e-3f)
        sy = hypot(m[2], m[3]).coerceAtLeast(1e-3f)
        rot = atan2(m[1], m[0])
        cx = m[0] * tw / 2 + m[2] * th / 2 + m[4]
        cy = m[1] * tw / 2 + m[3] * th / 2 + m[5]
        transformActive = true
        invalidate()
    }

    fun endTransform() {
        transformActive = false
        grab = Grab.NONE
        invalidate()
    }

    fun translateBy(dx: Float, dy: Float) {
        if (distort) {
            for (i in 0 until 4) { quad[i * 2] += dx; quad[i * 2 + 1] += dy }
        } else {
            cx += dx; cy += dy
        }
        publish()
    }

    /** 자유 모서리(원근) 모드 켜기/끄기. 켤 때 지금 상자의 네 모서리에서 시작합니다. 끄면 원래 상자로. */
    fun setDistort(on: Boolean) {
        if (on == distort) return
        if (on) {
            val m = matrix()
            val pts = floatArrayOf(0f, 0f, tw, 0f, tw, th, 0f, th)
            for (i in 0 until 4) {
                val u = pts[i * 2]; val v = pts[i * 2 + 1]
                quad[i * 2] = m[0] * u + m[2] * v + m[4]
                quad[i * 2 + 1] = m[1] * u + m[3] * v + m[5]
            }
        }
        distort = on
        publish()
    }

    private fun homography(): FloatArray = kr.dfluid.paint.engine.Homography.rectToQuad(tw, th, quad)

    /** 이동만 했을 때 정수 픽셀로 맞춰 다시 샘플링으로 흐려지지 않게 합니다. */
    fun snapTranslation() {
        if (distort) return
        if (abs(rot) > 1e-4f || abs(abs(sx) - 1f) > 1e-4f || abs(abs(sy) - 1f) > 1e-4f) return
        val m = matrix()
        cx += Math.round(m[4]) - m[4]
        cy += Math.round(m[5]) - m[5]
        publish()
    }

    fun flip(horizontal: Boolean) {
        if (distort) {
            // 모서리 순서를 바꿔 뒤집음
            val q = quad.copyOf()
            val order = if (horizontal) intArrayOf(1, 0, 3, 2) else intArrayOf(3, 2, 1, 0)
            for (i in 0 until 4) { quad[i * 2] = q[order[i] * 2]; quad[i * 2 + 1] = q[order[i] * 2 + 1] }
        } else if (horizontal) sx = -sx else sy = -sy
        publish()
    }

    fun rotateBy(rad: Float) {
        if (distort) {
            val ccx = (quad[0] + quad[2] + quad[4] + quad[6]) / 4f
            val ccy = (quad[1] + quad[3] + quad[5] + quad[7]) / 4f
            val c = cos(rad); val s = sin(rad)
            for (i in 0 until 4) {
                val x = quad[i * 2] - ccx; val y = quad[i * 2 + 1] - ccy
                quad[i * 2] = ccx + c * x - s * y; quad[i * 2 + 1] = ccy + s * x + c * y
            }
        } else rot += rad
        publish()
    }

    /** m = T(c)·R(rot)·S(sx,sy)·T(-w/2,-h/2) */
    fun matrix(): FloatArray {
        val c = cos(rot); val s = sin(rot)
        val a = c * sx; val b = s * sx
        val cc = -s * sy; val d = c * sy
        val tx = cx - (a * tw / 2 + cc * th / 2)
        val ty = cy - (b * tw / 2 + d * th / 2)
        return floatArrayOf(a, b, cc, d, tx, ty)
    }

    private fun publish() {
        listener?.onTransformChanged(if (distort) homography() else matrix())
        invalidate()
    }

    /** 로컬 (u,v) → 화면 */
    private fun localToScreen(u: Float, v: Float, out: FloatArray) {
        if (distort) {
            kr.dfluid.paint.engine.Homography.map(homography(), u, v, out)
            viewport.toScreen(out[0], out[1], out)
            return
        }
        val m = matrix()
        viewport.toScreen(m[0] * u + m[2] * v + m[4], m[1] * u + m[3] * v + m[5], out)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!transformActive) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && stylusSeen()) return false
                grab = if (distort) hitDistort(e.x, e.y) else hitTest(e.x, e.y)
                if (grab == Grab.NONE) return false
                lastX = e.x; lastY = e.y
                viewport.toScreen(cx, cy, tmp)
                lastAngle = atan2(e.y - tmp[1], e.x - tmp[0])
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                grab = Grab.NONE
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount > 1) return true
                drag(e.x, e.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                grab = Grab.NONE
                return true
            }
        }
        return true
    }

    private fun hitTest(x: Float, y: Float): Grab {
        // 모서리
        for (ax in intArrayOf(-1, 1)) for (ay in intArrayOf(-1, 1)) {
            localToScreen((ax + 1) / 2f * tw, (ay + 1) / 2f * th, tmp)
            if (hypot(x - tmp[0], y - tmp[1]) < touchR) {
                gax = ax; gay = ay; return Grab.CORNER
            }
        }
        // 변 가운데
        for ((ax, ay) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
            localToScreen((ax + 1) / 2f * tw, (ay + 1) / 2f * th, tmp)
            if (hypot(x - tmp[0], y - tmp[1]) < touchR) {
                gax = ax; gay = ay; return Grab.EDGE
            }
        }
        // 안쪽 = 이동
        val local = screenToLocal(x, y)
        if (local[0] in 0f..tw && local[1] in 0f..th) return Grab.MOVE
        // 상자 바깥 가까이 = 회전
        val mx = if (local[0] < 0) -local[0] else max(0f, local[0] - tw)
        val my = if (local[1] < 0) -local[1] else max(0f, local[1] - th)
        val distScreen = hypot(mx * abs(sx), my * abs(sy)) * viewport.scale
        return if (distScreen < Ui.dp(context, 80f)) Grab.ROTATE else Grab.NONE
    }

    /** 자유 모서리 모드: 모서리 = 그 점만, 변 가운데 = 두 모서리, 안쪽 = 전체 이동 */
    private fun hitDistort(x: Float, y: Float): Grab {
        for (i in 0 until 4) {
            viewport.toScreen(quad[i * 2], quad[i * 2 + 1], tmp)
            if (hypot(x - tmp[0], y - tmp[1]) < touchR) { gi = i; return Grab.CORNER }
        }
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            viewport.toScreen((quad[i * 2] + quad[j * 2]) / 2f, (quad[i * 2 + 1] + quad[j * 2 + 1]) / 2f, tmp)
            if (hypot(x - tmp[0], y - tmp[1]) < touchR) { gi = i; return Grab.EDGE }
        }
        val local = screenToLocal(x, y)
        return if (local[0] in 0f..tw && local[1] in 0f..th) Grab.MOVE else Grab.NONE
    }

    private fun screenToLocal(x: Float, y: Float): FloatArray {
        if (distort) {
            viewport.toCanvas(x, y, tmp)
            val inv = kr.dfluid.paint.engine.Homography.invert(homography()) ?: return floatArrayOf(-1f, -1f)
            val out = FloatArray(2)
            kr.dfluid.paint.engine.Homography.map(inv, tmp[0], tmp[1], out)
            return out
        }
        viewport.toCanvas(x, y, tmp)
        val m = matrix()
        val det = m[0] * m[3] - m[2] * m[1]
        val px = tmp[0] - m[4]
        val py = tmp[1] - m[5]
        return floatArrayOf((m[3] * px - m[2] * py) / det, (-m[1] * px + m[0] * py) / det)
    }

    private fun drag(x: Float, y: Float) {
        if (distort) {
            val a = FloatArray(2); val b = FloatArray(2)
            viewport.toCanvas(lastX, lastY, a)
            viewport.toCanvas(x, y, b)
            val dx = b[0] - a[0]; val dy = b[1] - a[1]
            when (grab) {
                Grab.MOVE -> for (i in 0 until 4) { quad[i * 2] += dx; quad[i * 2 + 1] += dy }
                Grab.CORNER -> { quad[gi * 2] += dx; quad[gi * 2 + 1] += dy }
                Grab.EDGE -> {
                    val j = (gi + 1) % 4
                    quad[gi * 2] += dx; quad[gi * 2 + 1] += dy
                    quad[j * 2] += dx; quad[j * 2 + 1] += dy
                }
                else -> return
            }
            lastX = x; lastY = y
            publish()
            return
        }
        when (grab) {
            Grab.MOVE -> {
                val a = FloatArray(2); val b = FloatArray(2)
                viewport.toCanvas(lastX, lastY, a)
                viewport.toCanvas(x, y, b)
                cx += b[0] - a[0]; cy += b[1] - a[1]
            }
            Grab.ROTATE -> {
                viewport.toScreen(cx, cy, tmp)
                val ang = atan2(y - tmp[1], x - tmp[0])
                var d = ang - lastAngle
                if (d > Math.PI) d -= (2 * Math.PI).toFloat()
                if (d < -Math.PI) d += (2 * Math.PI).toFloat()
                // 화면이 반전되어 있으면 캔버스 기준 회전 방향이 반대
                rot += if (viewport.flipped) -d else d
                lastAngle = ang
            }
            Grab.CORNER, Grab.EDGE -> scaleTo(x, y)
            Grab.NONE -> return
        }
        lastX = x; lastY = y
        publish()
    }

    /** 반대쪽 모서리/변을 고정한 채 크기 조절. */
    private fun scaleTo(x: Float, y: Float) {
        viewport.toCanvas(x, y, tmp)
        val c = cos(rot); val s = sin(rot)
        // 상자 중심 기준, 회전을 뺀 좌표
        val qx = c * (tmp[0] - cx) + s * (tmp[1] - cy)
        val qy = -s * (tmp[0] - cx) + c * (tmp[1] - cy)
        val hx = sx * tw / 2
        val hy = sy * th / 2
        val ox = -gax * hx
        val oy = -gay * hy
        var nsx = sx
        var nsy = sy
        val signX = if (sx < 0) -1f else 1f
        val signY = if (sy < 0) -1f else 1f
        if (gax != 0) nsx = max(1f / tw, (qx - ox) * gax * signX / tw) * signX
        if (gay != 0) nsy = max(1f / th, (qy - oy) * gay * signY / th) * signY
        if (grab == Grab.CORNER && listener?.keepAspect == true) {
            val k = max(abs(nsx / sx), abs(nsy / sy))
            nsx = sx * k; nsy = sy * k
        }
        // 새 중심 = 고정점 + 새 반폭
        val ncx = if (gax != 0) ox + gax * nsx * tw / 2 else 0f
        val ncy = if (gay != 0) oy + gay * nsy * th / 2 else 0f
        val keepX = if (gax == 0) 0f else ncx
        val keepY = if (gay == 0) 0f else ncy
        cx += c * keepX - s * keepY
        cy += s * keepX + c * keepY
        sx = nsx; sy = nsy
    }

    // =====================================================================
    // 그리기
    // =====================================================================

    /** 격자 간격 (캔버스 px, 0 = 끔). 4칸마다 진한 선 */
    var gridStep = 0
        set(v) { field = v; invalidate() }
    private val gridMinor = Paint().apply { color = 0x2A3D8BFF; strokeWidth = 1f }
    private val gridMajor = Paint().apply { color = 0x553D8BFF; strokeWidth = 1.5f }

    /** 캔버스 안에만 격자. 화면에서 선 간격이 6px보다 좁으면 진한 선만 */
    private fun drawGrid(canvas: Canvas) {
        val step = gridStep.toFloat()
        val w = viewport.canvasW
        val h = viewport.canvasH
        val dense = step * viewport.scale >= 6f
        val a = FloatArray(2); val b = FloatArray(2)
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, p: Paint) {
            viewport.toScreen(x0, y0, a); viewport.toScreen(x1, y1, b)
            canvas.drawLine(a[0], a[1], b[0], b[1], p)
        }
        var i = 0
        var x = 0f
        while (x <= w) {
            val major = i % 4 == 0
            if (major || dense) line(x, 0f, x, h, if (major) gridMajor else gridMinor)
            x += step; i++
        }
        i = 0
        var y = 0f
        while (y <= h) {
            val major = i % 4 == 0
            if (major || dense) line(0f, y, w, y, if (major) gridMajor else gridMinor)
            y += step; i++
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (gridStep > 0) drawGrid(canvas)
        layoutGuideX?.let { canvas.drawLine(it, 0f, it, height.toFloat(), layoutGuide) }
        layoutGuideY?.let { canvas.drawLine(0f, it, width.toFloat(), it, layoutGuide) }
        if (symMode != SymMode.OFF) drawSymmetry(canvas)
        ruler?.takeIf { it.on }?.let { drawRuler(canvas, it) }
        selShape?.let { drawSelectionGuide(canvas, it) }
        gradient?.let { g ->
            val a = FloatArray(2); val b = FloatArray(2)
            viewport.toScreen(g[0], g[1], a)
            viewport.toScreen(g[2], g[3], b)
            canvas.drawLine(a[0], a[1], b[0], b[1], shadow)
            canvas.drawLine(a[0], a[1], b[0], b[1], dash)
            canvas.drawCircle(a[0], a[1], handleR, handleFill)
            canvas.drawCircle(b[0], b[1], handleR, handleFill)
            canvas.drawCircle(b[0], b[1], handleR, handleStroke)
        }
        if (transformActive) drawTransform(canvas)
    }

    /** 자 안내선: 캔버스 안쪽에만 그리고, 손잡이(소실점·중심)는 어디에 있든 표시합니다. */
    private fun drawRuler(canvas: Canvas, r: kr.dfluid.paint.input.GuideRuler) {
        val w = viewport.canvasW
        val h = viewport.canvasH
        val diag = hypot(w, h)
        val a = FloatArray(2); val b = FloatArray(2)
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, p: Paint) {
            viewport.toScreen(x0, y0, a)
            viewport.toScreen(x1, y1, b)
            canvas.drawLine(a[0], a[1], b[0], b[1], p)
        }
        // 캔버스 모양으로 잘라서 그림
        path.reset()
        viewport.toScreen(0f, 0f, a); path.moveTo(a[0], a[1])
        viewport.toScreen(w, 0f, a); path.lineTo(a[0], a[1])
        viewport.toScreen(w, h, a); path.lineTo(a[0], a[1])
        viewport.toScreen(0f, h, a); path.lineTo(a[0], a[1])
        path.close()
        canvas.save()
        canvas.clipPath(path)
        when (r.kind) {
            kr.dfluid.paint.input.GuideRuler.Kind.PERSPECTIVE -> {
                val far = diag * 3f
                for (i in 0 until r.vpCount) {
                    val vx = r.vp[i * 2]; val vy = r.vp[i * 2 + 1]
                    for (k in 0 until 36) {
                        val t = (k * 10.0 * Math.PI / 180.0).toFloat()
                        line(vx, vy, vx + far * cos(t), vy + far * sin(t), rulerLine)
                    }
                }
                // 지평선 (1·2점)
                if (r.vpCount <= 2) {
                    val hy = if (r.vpCount == 1) r.vp[1] else (r.vp[1] + r.vp[3]) / 2f
                    if (r.vpCount == 1) line(-far, hy, far, hy, rulerMain)
                    else line(r.vp[0] - (r.vp[2] - r.vp[0]) * 4f, r.vp[1] - (r.vp[3] - r.vp[1]) * 4f,
                        r.vp[2] + (r.vp[2] - r.vp[0]) * 4f, r.vp[3] + (r.vp[3] - r.vp[1]) * 4f, rulerMain)
                }
            }
            kr.dfluid.paint.input.GuideRuler.Kind.CONCENTRIC -> {
                viewport.toScreen(r.cx, r.cy, a)
                val s = viewport.scale
                for (k in 1..12) canvas.drawCircle(a[0], a[1], diag / 12f * k * s, rulerLine)
            }
            kr.dfluid.paint.input.GuideRuler.Kind.PARALLEL -> {
                val vx = r.par[2] - r.par[0]; val vy = r.par[3] - r.par[1]
                val l = hypot(vx, vy).coerceAtLeast(1e-3f)
                val ux = vx / l * diag * 2f; val uy = vy / l * diag * 2f
                // 방향에 수직으로 간격을 두고 평행선
                val nx = -vy / l; val ny = vx / l
                val step = diag / 16f
                for (k in -24..24) {
                    val ox = r.par[0] + nx * step * k; val oy = r.par[1] + ny * step * k
                    line(ox - ux, oy - uy, ox + ux, oy + uy, if (k == 0) rulerMain else rulerLine)
                }
            }
            kr.dfluid.paint.input.GuideRuler.Kind.RADIAL -> {
                val far = diag * 3f
                for (k in 0 until 48) {
                    val t = (k * 7.5 * Math.PI / 180.0).toFloat()
                    line(r.cx, r.cy, r.cx + far * cos(t), r.cy + far * sin(t), rulerLine)
                }
            }
            kr.dfluid.paint.input.GuideRuler.Kind.OFF -> Unit
        }
        canvas.restore()
        val hs = r.handles()
        for (k in 0 until hs.size / 2) {
            viewport.toScreen(hs[k * 2], hs[k * 2 + 1], a)
            canvas.drawCircle(a[0], a[1], handleR * 1.4f, handleFill)
            canvas.drawCircle(a[0], a[1], handleR * 1.4f, handleStroke)
            canvas.drawCircle(a[0], a[1], handleR * 0.35f, handleStroke)
        }
    }

    private fun drawSymmetry(canvas: Canvas) {
        val w = viewport.canvasW
        val h = viewport.canvasH
        val cx = w / 2f
        val cy = h / 2f
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
            val a = FloatArray(2); val b = FloatArray(2)
            viewport.toScreen(x0, y0, a)
            viewport.toScreen(x1, y1, b)
            canvas.drawLine(a[0], a[1], b[0], b[1], symLine)
        }
        when (symMode) {
            SymMode.OFF -> Unit
            SymMode.VERTICAL -> line(cx, 0f, cx, h)
            SymMode.HORIZONTAL -> line(0f, cy, w, cy)
            SymMode.QUAD -> { line(cx, 0f, cx, h); line(0f, cy, w, cy) }
            SymMode.RADIAL -> {
                val r = hypot(w, h) / 2f
                val cnt = symCount.coerceIn(2, 16)
                for (k in 0 until cnt) {
                    val ang = (2.0 * Math.PI * k / cnt).toFloat()
                    line(cx, cy, cx + r * cos(ang), cy + r * sin(ang))
                }
            }
        }
    }

    private fun drawSelectionGuide(canvas: Canvas, shape: SelShape) {
        path.reset()
        val p = selPts
        if (p.size < 4) return
        when (shape) {
            SelShape.RECT, SelShape.ELLIPSE -> {
                val corners = if (shape == SelShape.RECT) {
                    floatArrayOf(p[0], p[1], p[2], p[1], p[2], p[3], p[0], p[3])
                } else {
                    // 타원을 다각형으로 근사
                    val n = 48
                    val ecx = (p[0] + p[2]) / 2; val ecy = (p[1] + p[3]) / 2
                    val rx = abs(p[2] - p[0]) / 2; val ry = abs(p[3] - p[1]) / 2
                    FloatArray(n * 2) { i ->
                        val t = (i / 2) * (2 * Math.PI / n)
                        if (i % 2 == 0) (ecx + rx * cos(t)).toFloat() else (ecy + ry * sin(t)).toFloat()
                    }
                }
                addPolygon(corners, true)
            }
            SelShape.LASSO -> addPolygon(p, false)
            SelShape.WAND -> return
        }
        canvas.drawPath(path, shadow)
        canvas.drawPath(path, dash)
    }

    private fun addPolygon(pts: FloatArray, close: Boolean) {
        var i = 0
        while (i + 1 < pts.size) {
            viewport.toScreen(pts[i], pts[i + 1], tmp)
            if (i == 0) path.moveTo(tmp[0], tmp[1]) else path.lineTo(tmp[0], tmp[1])
            i += 2
        }
        if (close) path.close()
    }

    private fun drawTransform(canvas: Canvas) {
        val corners = floatArrayOf(0f, 0f, tw, 0f, tw, th, 0f, th)
        path.reset()
        for (i in 0 until 4) {
            localToScreen(corners[i * 2], corners[i * 2 + 1], tmp)
            if (i == 0) path.moveTo(tmp[0], tmp[1]) else path.lineTo(tmp[0], tmp[1])
        }
        path.close()
        canvas.drawPath(path, shadow)
        canvas.drawPath(path, dash)
        val pts = listOf(0f to 0f, tw to 0f, tw to th, 0f to th, tw / 2 to 0f, tw to th / 2, tw / 2 to th, 0f to th / 2)
        for ((u, v) in pts) {
            localToScreen(u, v, tmp)
            canvas.drawCircle(tmp[0], tmp[1], handleR, handleFill)
            canvas.drawCircle(tmp[0], tmp[1], handleR, handleStroke)
        }
        if (!distort) {
            viewport.toScreen(cx, cy, tmp)
            canvas.drawCircle(tmp[0], tmp[1], handleR / 2, handleStroke)
        }
    }
}
