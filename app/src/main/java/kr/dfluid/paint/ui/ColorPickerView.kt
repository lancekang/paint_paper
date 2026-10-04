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
import android.view.MotionEvent
import android.view.View

/** HSV 색 선택기: 위 = 채도/명도 사각형, 아래 = 색상 막대. */
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

    var color: Int
        get() = Color.HSVToColor(hsv)
        set(value) {
            Color.colorToHSV(value, hsv)
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.95f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val gap = Ui.dp(context, 14f).toFloat()
        val hueH = Ui.dp(context, 28f).toFloat()
        svRect.set(0f, 0f, w.toFloat(), h - hueH - gap)
        hueRect.set(0f, h - hueH, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
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

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = if (e.y <= svRect.bottom + Ui.dp(context, 7f)) 1 else 2
                parent?.requestDisallowInterceptTouchEvent(true)
                update(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> update(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = 0
        }
        return true
    }

    private fun update(x: Float, y: Float) {
        if (dragging == 1) {
            hsv[1] = ((x - svRect.left) / svRect.width()).coerceIn(0f, 1f)
            hsv[2] = 1f - ((y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
        } else if (dragging == 2) {
            hsv[0] = ((x - hueRect.left) / hueRect.width() * 360f).coerceIn(0f, 359.9f)
        }
        invalidate()
        onColorChanged?.invoke(color)
    }
}
