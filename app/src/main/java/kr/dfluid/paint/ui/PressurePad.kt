package kr.dfluid.paint.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.ln

/**
 * 필압 자동 조정용 시험 판. 펜으로 평소처럼 그으면 원래 필압(0~1)을 모으고,
 * [suggestGamma]가 "평소 필압의 가운데값 → 0.5"가 되는 감마를 돌려줍니다.
 * 선 굵기는 지금 감마를 적용한 필압으로 보여 줍니다.
 */
class PressurePad(context: Context, private val gamma: () -> Float) : View(context) {
    private val samples = ArrayList<Float>()
    private val segs = ArrayList<FloatArray>()
    private var lastX = 0f
    private var lastY = 0f
    private var strokeStart = 0
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT
        strokeCap = Paint.Cap.ROUND
    }
    private val maxW = Ui.dp(context, 14f).toFloat()

    /** 모은 획 수에 상관없이 표본 수 */
    val count: Int get() = samples.size
    var onChanged: (() -> Unit)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, Ui.dp(context, 220f))
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Ui.CARD_HEAD)
        for (s in segs) {
            ink.strokeWidth = s[4]
            canvas.drawLine(s[0], s[1], s[2], s[3], ink)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val t = e.getToolType(0)
        if (t != MotionEvent.TOOL_TYPE_STYLUS) return true
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                strokeStart = samples.size
            }
            MotionEvent.ACTION_MOVE -> {
                for (k in 0 until e.historySize) add(e.getHistoricalX(k), e.getHistoricalY(k), e.getHistoricalPressure(k))
                add(e.x, e.y, e.pressure)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 펜을 대고 떼는 순간의 약한 필압은 빼고 (양 끝 각 15%)
                val n = samples.size - strokeStart
                val cut = (n * 0.15f).toInt()
                if (n > 6) {
                    val keep = samples.subList(strokeStart + cut, samples.size - cut).toList()
                    while (samples.size > strokeStart) samples.removeAt(samples.size - 1)
                    samples.addAll(keep)
                } else {
                    while (samples.size > strokeStart) samples.removeAt(samples.size - 1)
                }
                onChanged?.invoke()
            }
        }
        return true
    }

    private fun add(x: Float, y: Float, p: Float) {
        val raw = p.coerceIn(0f, 1f)
        samples.add(raw)
        val shown = Math.pow(raw.toDouble(), gamma().toDouble()).toFloat()
        segs.add(floatArrayOf(lastX, lastY, x, y, 1f + shown * maxW))
        lastX = x; lastY = y
    }

    fun clear() {
        samples.clear()
        segs.clear()
        invalidate()
        onChanged?.invoke()
    }

    /** 가운데값 → 0.5 가 되는 감마 (0.3~3). 표본이 적으면 null */
    fun suggestGamma(): Float? {
        if (samples.size < 30) return null
        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2].coerceIn(0.03f, 0.97f)
        return (ln(0.5) / ln(median.toDouble())).toFloat().coerceIn(0.3f, 3f)
    }

    /** 표시용: 원래 필압의 가운데값 */
    fun median(): Float? = if (samples.isEmpty()) null else samples.sorted()[samples.size / 2]
}
