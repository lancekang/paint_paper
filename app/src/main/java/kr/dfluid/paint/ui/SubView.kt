package kr.dfluid.paint.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 서브 뷰 (참고 이미지). 한 손가락·펜 = 이동, 두 손가락 = 확대, 두 번 탭 = 맞춤.
 * [picking]이면 탭한 곳의 색을 [onPick]으로 넘깁니다 (스포이드).
 */
class RefImageView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        private set
    var picking = false
    var onPick: ((Int) -> Unit)? = null
    private val m = Matrix()
    private val inv = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var lastX = 0f
    private var lastY = 0f

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            m.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
            invalidate()
            return true
        }
    })

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!picking) return false
            pick(e.x, e.y)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            fit()
            return true
        }
    })

    fun setImage(b: Bitmap?) {
        bitmap?.recycle()
        bitmap = b
        fit()
    }

    /** 이미지를 창에 맞춤 */
    fun fit() {
        val b = bitmap ?: return invalidate()
        if (width == 0 || height == 0) {
            post { fit() }
            return
        }
        val s = min(width.toFloat() / b.width, height.toFloat() / b.height)
        m.setScale(s, s)
        m.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (oldw == 0) fit()
    }

    private fun pick(x: Float, y: Float) {
        val b = bitmap ?: return
        m.invert(inv)
        val p = floatArrayOf(x, y)
        inv.mapPoints(p)
        val ix = p[0].toInt()
        val iy = p[1].toInt()
        if (ix !in 0 until b.width || iy !in 0 until b.height) return
        val c = b.getPixel(ix, iy)
        if (Color.alpha(c) == 0) return
        onPick?.invoke(c or 0xFF000000.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap
        if (b == null) {
            hint.color = Ui.SUBTEXT
            hint.textSize = Ui.dp(context, 13f).toFloat()
            canvas.drawText("이미지 열기를 눌러 참고 이미지를 띄우세요", width / 2f, height / 2f, hint)
            return
        }
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawBitmap(b, m, paint)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && e.pointerCount == 1 && !picking) {
                m.postTranslate(e.x - lastX, e.y - lastY)
                lastX = e.x; lastY = e.y
                invalidate()
            } else if (picking && e.pointerCount == 1) {
                pick(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(i); lastY = e.getY(i)
            }
        }
        return true
    }

    companion object {
        /** 긴 변을 [maxSide] 이하로 줄여 읽습니다. */
        fun load(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap? = try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (max(o.outWidth, o.outHeight) / sample > maxSide) sample *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
        } catch (e: Exception) {
            null
        }
    }
}
