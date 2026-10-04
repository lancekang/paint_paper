package kr.dfluid.paint.ui

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * 색 슬라이더: HSV(색상·채도·명도)와 RGB 값을 숫자로 맞춥니다 (클립 스튜디오의 "색 슬라이더").
 * 끄는 동안 [onLive], 손을 떼면 [onCommit].
 */
class ColorSliders(private val ctx: Context) {
    var onLive: ((Int) -> Unit)? = null
    var onCommit: ((Int) -> Unit)? = null
    private val hsv = FloatArray(3)
    private var syncing = false

    private fun row(label: String, max: Int) = Ui.SliderRow(ctx, label, max)
    private val h = row("색상", 359)
    private val s = row("채도", 100)
    private val v = row("명도", 100)
    private val r = row("R", 255)
    private val g = row("G", 255)
    private val b = row("B", 255)
    private val hex = Ui.text(ctx, "", 12f, Ui.SUBTEXT)

    val view = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        for (x in listOf(h, s, v, r, g, b)) addView(x.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(hex)
    }

    var color: Int = Color.BLACK
        set(c) {
            val nc = c or 0xFF000000.toInt()
            // 같은 색이면 hsv를 유지 (회색에서 색상·채도 값이 0으로 튀지 않게)
            val tmp = FloatArray(3)
            Color.colorToHSV(nc, tmp)
            if (Color.HSVToColor(hsv) != nc) {
                hsv[0] = tmp[0]; hsv[1] = tmp[1]; hsv[2] = tmp[2]
            }
            field = nc
            refresh()
        }

    init {
        fun hsvChanged() {
            if (syncing) return
            // hsv를 다시 계산하지 않음 (채도 0에서 색상이 사라지지 않게)
            color = Color.HSVToColor(hsv)
            onLive?.invoke(color)
        }
        h.onChange = { p -> hsv[0] = p.toFloat(); hsvChanged() }
        s.onChange = { p -> hsv[1] = p / 100f; hsvChanged() }
        v.onChange = { p -> hsv[2] = p / 100f; hsvChanged() }
        fun rgbChanged() {
            if (syncing) return
            color = Color.rgb(r.seek.progress, g.seek.progress, b.seek.progress)
            onLive?.invoke(color)
        }
        r.onChange = { rgbChanged() }
        g.onChange = { rgbChanged() }
        b.onChange = { rgbChanged() }
        for (x in listOf(h, s, v, r, g, b)) x.onStop = { onCommit?.invoke(color) }
    }

    private fun refresh() {
        syncing = true
        val hv = hsv[0].roundToInt().coerceIn(0, 359)
        val sv = (hsv[1] * 100).roundToInt()
        val vv = (hsv[2] * 100).roundToInt()
        h.set(hv, "$hv°")
        s.set(sv, "$sv%")
        v.set(vv, "$vv%")
        r.set(Color.red(color), "${Color.red(color)}")
        g.set(Color.green(color), "${Color.green(color)}")
        b.set(Color.blue(color), "${Color.blue(color)}")
        hex.text = String.format("#%06X", color and 0xFFFFFF)
        syncing = false
    }
}
