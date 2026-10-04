package kr.dfluid.paint.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kr.dfluid.paint.engine.Viewport

/**
 * 내비게이터: 그림 전체 축소판 + 지금 화면에 보이는 영역(사각형).
 * 누르거나 끌면 그 지점이 화면 가운데로 옵니다. [onMoved]가 뷰를 다시 그리게 합니다.
 */
class NavigatorView(context: Context, private val viewport: Viewport) : View(context) {
    var onMoved: (() -> Unit)? = null
    private var bmp: Bitmap? = null
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(context, 2f).toFloat()
        color = 0xFFE53935.toInt()
    }
    private val shade = Paint().apply { color = 0x22000000 }
    private val path = Path()
    private val tmp = FloatArray(2)

    fun setImage(b: Bitmap) {
        val old = bmp
        bmp = b
        if (old !== b) old?.recycle()
        invalidate()
    }

    /** 축소판이 그려질 사각형 (캔버스 비율 유지, 가운데) */
    private fun layoutDst() {
        val cw = viewport.canvasW
        val ch = viewport.canvasH
        val s = minOf(width / cw, height / ch)
        val w = cw * s
        val h = ch * s
        dst.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        layoutDst()
        canvas.drawRect(dst, shade)
        bmp?.let { canvas.drawBitmap(it, null, dst, paint) }
        // 화면 네 모서리 → 캔버스 → 축소판 좌표
        val sx = dst.width() / viewport.canvasW
        val sy = dst.height() / viewport.canvasH
        path.reset()
        val corners = floatArrayOf(0f, 0f, viewport.screenW, 0f, viewport.screenW, viewport.screenH, 0f, viewport.screenH)
        for (i in 0 until 4) {
            viewport.toCanvas(corners[i * 2], corners[i * 2 + 1], tmp)
            val x = dst.left + tmp[0] * sx
            val y = dst.top + tmp[1] * sy
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        frame.color = Color.WHITE
        frame.strokeWidth = Ui.dp(context, 4f).toFloat()
        canvas.drawPath(path, frame)
        frame.color = 0xFFE53935.toInt()
        frame.strokeWidth = Ui.dp(context, 2f).toFloat()
        canvas.drawPath(path, frame)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                layoutDst()
                if (dst.width() <= 0f) return true
                // 누른 곳의 캔버스 좌표를 화면 가운데로
                val cx = ((e.x - dst.left) / dst.width() * viewport.canvasW).coerceIn(0f, viewport.canvasW)
                val cy = ((e.y - dst.top) / dst.height() * viewport.canvasH).coerceIn(0f, viewport.canvasH)
                viewport.toScreen(cx, cy, tmp)
                viewport.pan(viewport.screenW / 2f - tmp[0], viewport.screenH / 2f - tmp[1])
                onMoved?.invoke()
                invalidate()
            }
        }
        return true
    }
}
