package kr.dfluid.paint.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kr.dfluid.paint.brush.PressureCurve
import kotlin.math.hypot

/**
 * 필압 곡선 편집기. x = 펜 필압, y = 브러시에 전달되는 값.
 * 점을 끌어 옮기고, 빈 곳을 누르면 점을 추가, 점을 두 번 누르면 삭제 (양 끝점 제외).
 */
class PressureCurveView(context: Context) : View(context) {
    var points: FloatArray = floatArrayOf(0f, 0f, 1f, 1f)
        set(value) {
            field = value.copyOf()
            invalidate()
        }
    var onChanged: ((FloatArray) -> Unit)? = null

    private val grid = Paint().apply { color = 0xFF3A3C41.toInt(); strokeWidth = 1f }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3D8BFF.toInt(); strokeWidth = Ui.dp(context, 2.5f).toFloat(); style = Paint.Style.STROKE
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pad = Ui.dp(context, 12f).toFloat()
    private val hit = Ui.dp(context, 20f).toFloat()
    private var dragging = -1
    private var lastTapIndex = -1
    private var lastTapTime = 0L

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.75f).toInt())
    }

    private fun sx(x: Float) = pad + x * (width - 2 * pad)
    private fun sy(y: Float) = height - pad - y * (height - 2 * pad)
    private fun vx(px: Float) = ((px - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
    private fun vy(py: Float) = ((height - pad - py) / (height - 2 * pad)).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF1F2023.toInt())
        for (i in 0..4) {
            val t = i / 4f
            canvas.drawLine(sx(t), sy(0f), sx(t), sy(1f), grid)
            canvas.drawLine(sx(0f), sy(t), sx(1f), sy(t), grid)
        }
        val lut = PressureCurve.lut(points, 64)
        val path = Path()
        for (i in lut.indices) {
            val x = sx(i / 63f)
            val y = sy(lut[i])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, line)
        val r = Ui.dp(context, 6f).toFloat()
        for (i in 0 until points.size / 2) canvas.drawCircle(sx(points[i * 2]), sy(points[i * 2 + 1]), r, dot)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val i = nearest(e.x, e.y)
                if (i >= 0) {
                    val now = e.eventTime
                    if (i == lastTapIndex && now - lastTapTime < 350 && i != 0 && i != points.size / 2 - 1) {
                        removePoint(i)
                        lastTapIndex = -1
                        return true
                    }
                    lastTapIndex = i
                    lastTapTime = now
                    dragging = i
                } else {
                    dragging = addPoint(vx(e.x), vy(e.y))
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragging >= 0) movePoint(dragging, vx(e.x), vy(e.y))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging >= 0) onChanged?.invoke(points.copyOf())
                dragging = -1
            }
        }
        return true
    }

    private fun nearest(x: Float, y: Float): Int {
        var best = -1
        var bestD = hit
        for (i in 0 until points.size / 2) {
            val d = hypot(sx(points[i * 2]) - x, sy(points[i * 2 + 1]) - y)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun addPoint(x: Float, y: Float): Int {
        if (points.size >= 16) return -1
        val list = (0 until points.size / 2).map { points[it * 2] to points[it * 2 + 1] }.toMutableList()
        var idx = list.indexOfFirst { it.first > x }
        if (idx <= 0) idx = list.size - 1
        list.add(idx, x to y)
        points = list.flatMap { listOf(it.first, it.second) }.toFloatArray()
        onChanged?.invoke(points.copyOf())
        return idx
    }

    private fun removePoint(i: Int) {
        val list = (0 until points.size / 2).map { points[it * 2] to points[it * 2 + 1] }.toMutableList()
        list.removeAt(i)
        points = list.flatMap { listOf(it.first, it.second) }.toFloatArray()
        onChanged?.invoke(points.copyOf())
    }

    private fun movePoint(i: Int, x: Float, y: Float) {
        val n = points.size / 2
        val p = points.copyOf()
        // 양 끝점은 x 고정, 가운데 점은 이웃 사이에서만
        val nx = when (i) {
            0 -> 0f
            n - 1 -> 1f
            else -> x.coerceIn(p[(i - 1) * 2] + 0.01f, p[(i + 1) * 2] - 0.01f)
        }
        p[i * 2] = nx
        p[i * 2 + 1] = y
        points = p
    }
}
