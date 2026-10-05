package kr.dfluid.paint.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/**
 * 중간색: 네 모서리 색 사이를 [N]×[N] 칸으로 섞어 보여 줍니다 (클립 스튜디오의 "중간색").
 * 칸을 누르면 그 색을 고르고, 모서리 칸을 길게 누르면 그 모서리를 지금 주색으로 바꿉니다.
 */
class MixGridView(context: Context, private val corners: IntArray, private val current: () -> Int) : View(context) {
    var onPick: ((Int) -> Unit)? = null
    var onCornersChanged: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(context, 2f).toFloat()
    }
    private val cell = RectF()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val (i, j) = hit(e) ?: return false
            onPick?.invoke(colorAt(i, j))
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val (i, j) = hit(e) ?: return
            val k = when {
                i == 0 && j == 0 -> 0
                i == N - 1 && j == 0 -> 1
                i == 0 && j == N - 1 -> 2
                i == N - 1 && j == N - 1 -> 3
                else -> return
            }
            corners[k] = current()
            invalidate()
            onCornersChanged?.invoke()
        }
    })

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.4f).toInt())
    }

    private fun cellSize() = width.toFloat() / N to height.toFloat() / N

    private fun hit(e: MotionEvent): Pair<Int, Int>? {
        val (cw, ch) = cellSize()
        val i = (e.x / cw).toInt()
        val j = (e.y / ch).toInt()
        return if (i in 0 until N && j in 0 until N) i to j else null
    }

    /** 두 방향 선형 보간 (RGB). */
    fun colorAt(i: Int, j: Int): Int {
        val u = i / (N - 1f)
        val v = j / (N - 1f)
        fun ch(shift: Int): Int {
            fun c(k: Int) = (corners[k] shr shift) and 0xFF
            val top = c(0) + (c(1) - c(0)) * u
            val bot = c(2) + (c(3) - c(2)) * u
            return (top + (bot - top) * v).toInt().coerceIn(0, 255)
        }
        return Color.rgb(ch(16), ch(8), ch(0))
    }

    override fun onDraw(canvas: Canvas) {
        val (cw, ch) = cellSize()
        val gap = Ui.dp(context, 1.5f)
        for (j in 0 until N) for (i in 0 until N) {
            paint.color = colorAt(i, j)
            cell.set(i * cw + gap, j * ch + gap, (i + 1) * cw - gap, (j + 1) * ch - gap)
            canvas.drawRoundRect(cell, 4f, 4f, paint)
            val corner = (i == 0 || i == N - 1) && (j == 0 || j == N - 1)
            if (corner) {
                border.color = Ui.BUTTON_ON
                canvas.drawRoundRect(cell, 4f, 4f, border)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return gestures.onTouchEvent(e)
    }

    companion object {
        const val N = 7
    }
}

/**
 * 컬러 세트: 저장해 둔 색 견본. 첫 칸 "+" = 지금 주색 추가, 누르면 고르기, 길게 누르면 삭제.
 */
class SwatchGridView(context: Context, private val colors: MutableList<Int>, private val current: () -> Int) : View(context) {
    var onPick: ((Int) -> Unit)? = null
    var onChanged: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(context, 1f).toFloat()
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = Ui.dp(context, 18f).toFloat()
    }
    private val cell = RectF()
    private val cols = 8

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val k = hit(e) ?: return false
            if (k == 0) {
                val c = current()
                if (c !in colors) {
                    colors.add(c)
                    requestLayout()
                    invalidate()
                    onChanged?.invoke()
                }
            } else onPick?.invoke(colors[k - 1])
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val k = hit(e) ?: return
            if (k == 0) return
            colors.removeAt(k - 1)
            requestLayout()
            invalidate()
            onChanged?.invoke()
        }
    })

    private fun side() = width.toFloat() / cols

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rows = (colors.size + 1 + cols - 1) / cols
        setMeasuredDimension(w, (w / cols.toFloat() * maxOf(rows, 2)).toInt())
    }

    private fun hit(e: MotionEvent): Int? {
        val s = side()
        val k = (e.y / s).toInt() * cols + (e.x / s).toInt()
        return if (e.x >= 0 && k in 0..colors.size) k else null
    }

    override fun onDraw(canvas: Canvas) {
        val s = side()
        val gap = Ui.dp(context, 2f)
        for (k in 0..colors.size) {
            val x = (k % cols) * s
            val y = (k / cols) * s
            cell.set(x + gap, y + gap, x + s - gap, y + s - gap)
            if (k == 0) {
                paint.color = Ui.BUTTON
                canvas.drawRoundRect(cell, 6f, 6f, paint)
                text.color = Ui.TEXT
                canvas.drawText("+", cell.centerX(), cell.centerY() - (text.ascent() + text.descent()) / 2f, text)
            } else {
                paint.color = colors[k - 1]
                canvas.drawRoundRect(cell, 6f, 6f, paint)
            }
            // 견본 경계: 바탕과 3:1 이상 (흰색·검은색 견본도 보이게)
            edge.color = Ui.CONTROL
            canvas.drawRoundRect(cell, 6f, 6f, edge)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return gestures.onTouchEvent(e)
    }
}
