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
        cx += dx; cy += dy
        publish()
    }

    /** 이동만 했을 때 정수 픽셀로 맞춰 다시 샘플링으로 흐려지지 않게 합니다. */
    fun snapTranslation() {
        if (abs(rot) > 1e-4f || abs(abs(sx) - 1f) > 1e-4f || abs(abs(sy) - 1f) > 1e-4f) return
        val m = matrix()
        cx += Math.round(m[4]) - m[4]
        cy += Math.round(m[5]) - m[5]
        publish()
    }

    fun flip(horizontal: Boolean) {
        if (horizontal) sx = -sx else sy = -sy
        publish()
    }

    fun rotateBy(rad: Float) {
        rot += rad
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
        listener?.onTransformChanged(matrix())
        invalidate()
    }

    /** 로컬 (u,v) → 화면 */
    private fun localToScreen(u: Float, v: Float, out: FloatArray) {
        val m = matrix()
        viewport.toScreen(m[0] * u + m[2] * v + m[4], m[1] * u + m[3] * v + m[5], out)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!transformActive) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && stylusSeen()) return false
                grab = hitTest(e.x, e.y)
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

    private fun screenToLocal(x: Float, y: Float): FloatArray {
        viewport.toCanvas(x, y, tmp)
        val m = matrix()
        val det = m[0] * m[3] - m[2] * m[1]
        val px = tmp[0] - m[4]
        val py = tmp[1] - m[5]
        return floatArrayOf((m[3] * px - m[2] * py) / det, (-m[1] * px + m[0] * py) / det)
    }

    private fun drag(x: Float, y: Float) {
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

    override fun onDraw(canvas: Canvas) {
        if (symMode != SymMode.OFF) drawSymmetry(canvas)
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
        viewport.toScreen(cx, cy, tmp)
        canvas.drawCircle(tmp[0], tmp[1], handleR / 2, handleStroke)
    }
}
