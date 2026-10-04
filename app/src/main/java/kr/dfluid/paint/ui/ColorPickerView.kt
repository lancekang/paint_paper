package kr.dfluid.paint.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View

/**
 * HSV 색 선택기.
 * 사각형 모드: 위 = 채도/명도 사각형, 아래 = 색상 막대.
 * 서클 모드([wheel]): 바깥 고리 = 색상, 안쪽 사각형 = 채도/명도 (클립 스튜디오의 컬러 서클).
 */
class ColorPickerView(context: Context) : View(context) {
    private val hsv = floatArrayOf(0f, 0f, 0f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(context, 2f).toFloat()
    }
    private val svRect = RectF()
    private val hueRect = RectF()
    private var dragging = 0 // 1 = SV, 2 = Hue
    var onColorChanged: ((Int) -> Unit)? = null
    /** 손을 뗐을 때 (최근 색에 넣는 등 확정 동작용) */
    var onColorCommitted: ((Int) -> Unit)? = null
    /** 높이 = 너비 × 비율 (패널에 넣을 때는 낮게) */
    var heightRatio = 0.95f
    /** true = 컬러 서클 */
    var wheel = false
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }
    private var cx = 0f
    private var cy = 0f
    private var ringOuter = 0f
    private var ringInner = 0f

    var color: Int
        get() = Color.HSVToColor(hsv)
        set(value) {
            Color.colorToHSV(value, hsv)
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, if (wheel) (w * 0.5f).toInt() else (w * heightRatio).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val gap = Ui.dp(context, 14f).toFloat()
        val hueH = Ui.dp(context, 28f).toFloat()
        svRect.set(0f, 0f, w.toFloat(), h - hueH - gap)
        hueRect.set(0f, h - hueH, w.toFloat(), h.toFloat())
        layoutWheel(w, h)
    }

    private fun layoutWheel(w: Int, h: Int) {
        cx = w / 2f
        cy = h / 2f
        ringOuter = minOf(w, h) / 2f - Ui.dp(context, 2f)
        ringInner = ringOuter - Ui.dp(context, 18f)
        // 고리 안에 꼭 맞는 사각형 (조금 여유)
        if (wheel) {
            val half = ringInner / 1.414f - Ui.dp(context, 4f)
            svRect.set(cx - half, cy - half, cx + half, cy + half)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (wheel) {
            layoutWheel(width, height)
            drawWheel(canvas)
            return
        }
        val hueColor = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        val sat = LinearGradient(svRect.left, 0f, svRect.right, 0f, Color.WHITE, hueColor, Shader.TileMode.CLAMP)
        val value = LinearGradient(0f, svRect.top, 0f, svRect.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        paint.shader = ComposeShader(sat, value, PorterDuff.Mode.SRC_OVER)
        canvas.drawRoundRect(svRect, 12f, 12f, paint)

        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        paint.shader = LinearGradient(hueRect.left, 0f, hueRect.right, 0f, hues, null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(hueRect, 12f, 12f, paint)
        paint.shader = null

        val sx = svRect.left + hsv[1] * svRect.width()
        val sy = svRect.top + (1f - hsv[2]) * svRect.height()
        val r = Ui.dp(context, 8f).toFloat()
        marker.color = if (hsv[2] > 0.6f && hsv[1] < 0.5f) Color.BLACK else Color.WHITE
        canvas.drawCircle(sx, sy, r, marker)

        val hx = hueRect.left + hsv[0] / 360f * hueRect.width()
        marker.color = Color.WHITE
        canvas.drawRect(hx - r / 2, hueRect.top - 2, hx + r / 2, hueRect.bottom + 2, marker)
    }

    private fun drawWheel(canvas: Canvas) {
        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        // 0° = 오른쪽, 시계 방향으로 색상 증가
        paint.shader = SweepGradient(cx, cy, hues, null)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ringOuter - ringInner
        canvas.drawCircle(cx, cy, (ringOuter + ringInner) / 2f, paint)
        paint.style = Paint.Style.FILL
        val hueColor = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        val sat = LinearGradient(svRect.left, 0f, svRect.right, 0f, Color.WHITE, hueColor, Shader.TileMode.CLAMP)
        val value = LinearGradient(0f, svRect.top, 0f, svRect.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        paint.shader = ComposeShader(sat, value, PorterDuff.Mode.SRC_OVER)
        canvas.drawRect(svRect, paint)
        paint.shader = null
        val r = Ui.dp(context, 7f).toFloat()
        val sx = svRect.left + hsv[1] * svRect.width()
        val sy = svRect.top + (1f - hsv[2]) * svRect.height()
        marker.color = if (hsv[2] > 0.6f && hsv[1] < 0.5f) Color.BLACK else Color.WHITE
        canvas.drawCircle(sx, sy, r, marker)
        val a = Math.toRadians(hsv[0].toDouble())
        val rm = (ringOuter + ringInner) / 2f
        marker.color = Color.WHITE
        canvas.drawCircle(cx + rm * Math.cos(a).toFloat(), cy + rm * Math.sin(a).toFloat(), (ringOuter - ringInner) / 2f, marker)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = if (wheel) {
                    val d = Math.hypot((e.x - cx).toDouble(), (e.y - cy).toDouble())
                    if (d >= ringInner - Ui.dp(context, 4f)) 2 else 1
                } else if (e.y <= svRect.bottom + Ui.dp(context, 7f)) 1 else 2
                parent?.requestDisallowInterceptTouchEvent(true)
                update(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> update(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging != 0) onColorCommitted?.invoke(color)
                dragging = 0
            }
        }
        return true
    }

    private fun update(x: Float, y: Float) {
        if (dragging == 1) {
            hsv[1] = ((x - svRect.left) / svRect.width()).coerceIn(0f, 1f)
            hsv[2] = 1f - ((y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
        } else if (dragging == 2) {
            hsv[0] = if (wheel) {
                val deg = Math.toDegrees(Math.atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
                ((deg + 360f) % 360f).coerceIn(0f, 359.9f)
            } else ((x - hueRect.left) / hueRect.width() * 360f).coerceIn(0f, 359.9f)
        }
        invalidate()
        onColorChanged?.invoke(color)
    }
}
