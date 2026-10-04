package kr.dfluid.paint.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max

/**
 * 떠 있는 패널(상단 바·도구 막대·오른쪽 패널)을 손잡이로 끌어 옮기고, 가장자리·가운데에 스냅합니다.
 *
 * 위치는 "남는 공간 중 몇 %" (0 = 왼쪽/위, 0.5 = 가운데, 1 = 오른쪽/아래)로 저장하므로
 * 화면 크기·방향이 바뀌거나 패널 크기가 바뀌어도 가운데는 가운데에 남습니다.
 * 패널의 LayoutParams는 TOP|START에 두고 view.x / view.y로만 위치를 정합니다.
 */
class FloatingPanels(
    private val root: FrameLayout,
    /** 끄는 중 스냅된 안내선 (화면 x, 화면 y). null = 없음 */
    private val guides: (Float?, Float?) -> Unit,
    /** 패널 위치나 크기가 바뀐 뒤 (다른 패널을 맞출 때) */
    private val onChanged: () -> Unit = {},
) {
    private val ctx = root.context
    private val margin = Ui.dp(ctx, 8f).toFloat()
    private val snap = Ui.dp(ctx, 28f).toFloat()

    private class Item(val view: View, val moveY: Boolean, var fx: Float, var fy: Float, val save: (Float, Float) -> Unit)

    private val items = ArrayList<Item>()

    private val rootListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (r - l != or - ol || b - t != ob - ot) items.forEach { place(it) }
    }

    init {
        root.addOnLayoutChangeListener(rootListener)
    }

    /** UI를 다시 만들기 전에 (루트는 그대로 남으므로 리스너를 떼어야 함) */
    fun detach() {
        root.removeOnLayoutChangeListener(rootListener)
        items.clear()
    }

    /**
     * [moveY] = false면 가로로만 움직입니다 (세로 위치는 LayoutParams 여백 그대로).
     * [save]는 손을 뗄 때 새 비율로 불립니다.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun add(view: View, grip: View, moveY: Boolean, fx: Float, fy: Float, save: (Float, Float) -> Unit) {
        val item = Item(view, moveY, fx, fy, save)
        items.add(item)
        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) place(item)
        }
        var downX = 0f; var downY = 0f; var startX = 0f; var startY = 0f
        grip.setOnTouchListener { g, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    g.parent?.requestDisallowInterceptTouchEvent(true)
                    downX = e.rawX; downY = e.rawY
                    startX = view.x; startY = view.y
                    view.alpha = 0.85f
                    // 겹쳐 있을 때 지금 옮기는 패널이 위로 오게
                    view.bringToFront()
                }
                MotionEvent.ACTION_MOVE -> {
                    val (x, gx) = snapAxis(startX + e.rawX - downX, view.width.toFloat(), root.width.toFloat())
                    view.x = x
                    var gy: Float? = null
                    if (moveY) {
                        val (y, sy) = snapAxis(startY + e.rawY - downY, view.height.toFloat(), root.height.toFloat())
                        view.y = y
                        gy = sy
                    }
                    guides(gx, gy)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.alpha = 1f
                    guides(null, null)
                    item.fx = fraction(view.x, view.width.toFloat(), root.width.toFloat())
                    if (moveY) item.fy = fraction(view.y, view.height.toFloat(), root.height.toFloat())
                    item.save(item.fx, item.fy)
                    onChanged()
                }
            }
            true
        }
    }

    fun placeAll() = items.forEach { place(it) }

    private fun place(it: Item) {
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        if (w <= 0f || it.view.width == 0) return
        it.view.x = margin + it.fx * max(0f, w - it.view.width - 2 * margin)
        if (it.moveY) it.view.y = margin + it.fy * max(0f, h - it.view.height - 2 * margin)
        onChanged()
    }

    private fun fraction(pos: Float, size: Float, total: Float): Float {
        val room = total - size - 2 * margin
        return if (room <= 0f) 0f else ((pos - margin) / room).coerceIn(0f, 1f)
    }

    /** 한 축: 범위 안으로 제한하고, 시작·가운데·끝 근처면 붙입니다. 반환 (위치, 안내선 화면 좌표 또는 null) */
    private fun snapAxis(pos: Float, size: Float, total: Float): Pair<Float, Float?> {
        val lo = margin
        val hi = max(lo, total - size - margin)
        val p = pos.coerceIn(lo, hi)
        val center = (total - size) / 2f
        return when {
            abs(p - center) < snap -> center to total / 2f
            abs(p - lo) < snap -> lo to lo
            abs(p - hi) < snap -> hi to hi + size
            else -> p to null
        }
    }

    /** 손잡이 (점 6개). [horizontal] = 가로로 긴 모양 */
    class Grip(ctx: Context, private val horizontal: Boolean) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED }
        private val r = Ui.dp(ctx, 1.6f).toFloat()
        private val gap = Ui.dp(ctx, 5f).toFloat()

        init {
            Ui.setTip(this, "끌어서 위치 옮기기 (가장자리·가운데에 붙음)")
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            for (i in -1..1) for (j in 0..1) {
                val a = i * gap
                val b = (j - 0.5f) * gap
                if (horizontal) canvas.drawCircle(cx + a, cy + b, r, paint)
                else canvas.drawCircle(cx + b, cy + a, r, paint)
            }
        }
    }
}
