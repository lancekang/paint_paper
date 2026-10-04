package kr.dfluid.paint.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** 코드로 UI를 만드는 작은 도우미 모음 (XML 레이아웃 없이 프레임워크 위젯만 사용). */
object Ui {
    const val PANEL = 0xEE232427.toInt()
    const val TEXT = 0xFFE9E9EB.toInt()
    const val SUBTEXT = 0xFF9C9EA3.toInt()
    const val BUTTON = 0xFF33353A.toInt()
    const val BUTTON_ON = 0xFF2F6BD8.toInt()
    const val DIVIDER = 0xFF3A3C41.toInt()
    const val ROW_ON = 0xFF2A3E62.toInt()

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (stroke > 0) setStroke(stroke, strokeColor)
        }

    fun text(ctx: Context, s: String, size: Float = 13f, color: Int = TEXT, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            isFocusable = false
        }

    fun button(ctx: Context, label: String, minWidthDp: Float = 0f, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            textSize = 13f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            val p = dp(ctx, 10f)
            setPadding(p, dp(ctx, 6f), p, dp(ctx, 6f))
            minWidth = dp(ctx, minWidthDp)
            background = rounded(BUTTON, dp(ctx, 6f).toFloat())
            isFocusable = false
            isClickable = true
            setOnClickListener { onClick() }
        }

    /**
     * 아이콘 버튼. 길게 누르거나 펜을 올려 두면 [tip] 말풍선이 뜹니다 (View.tooltipText, API 26+).
     * 단축키를 함께 보여주려면 [Tips.bind]로 등록하세요.
     */
    fun iconButton(ctx: Context, icon: Int, tip: String, sizeDp: Float = 40f, onClick: () -> Unit): ImageView =
        ImageView(ctx).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = dp(ctx, 8f)
            setPadding(p, p, p, p)
            minimumWidth = dp(ctx, sizeDp)
            minimumHeight = dp(ctx, sizeDp)
            background = rounded(BUTTON, dp(ctx, 6f).toFloat())
            isFocusable = false
            isClickable = true
            setTip(this, tip)
            setOnClickListener { onClick() }
        }

    /** 말풍선 + 접근성 설명. */
    fun setTip(v: View, tip: String) {
        v.tooltipText = tip
        v.contentDescription = tip
    }

    fun setOn(v: View, on: Boolean) {
        v.background = rounded(if (on) BUTTON_ON else BUTTON, dp(v.context, 6f).toFloat())
    }

    fun setEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.4f
    }

    fun hspace(ctx: Context, dpW: Float) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(ctx, dpW), 1)
    }

    fun divider(ctx: Context) = View(ctx).apply {
        setBackgroundColor(DIVIDER)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1f)).apply {
            topMargin = dp(ctx, 8f); bottomMargin = dp(ctx, 8f)
        }
    }

    fun square(ctx: Context, sizeDp: Float) = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))

    fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** 라벨 + 슬라이더 + 값 표시 한 줄. */
    class SliderRow(ctx: Context, label: String, maxValue: Int) {
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val seek = SeekBar(ctx).apply {
            max = maxValue
            isFocusable = false
        }
        val value = text(ctx, "", 12f, SUBTEXT)
        var onChange: ((Int) -> Unit)? = null
        var onStart: (() -> Unit)? = null
        var onStop: (() -> Unit)? = null

        init {
            view.addView(text(ctx, label, 12f, SUBTEXT), LinearLayout.LayoutParams(dp(ctx, 64f), ViewGroup.LayoutParams.WRAP_CONTENT))
            view.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            view.addView(value, LinearLayout.LayoutParams(dp(ctx, 52f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(ctx, 4f)
            })
            value.gravity = Gravity.END
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) onChange?.invoke(progress)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    onStart?.invoke()
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    onStop?.invoke()
                }
            })
        }

        fun set(progress: Int, label: String) {
            if (seek.progress != progress) seek.progress = progress
            value.text = label
        }
    }

    /**
     * 말풍선 문구에 현재 단축키를 붙여 줍니다. 단축키 설정이 바뀌면 [refresh]로 다시 계산합니다.
     * [keyLabel]은 동작의 단축키 표시(없으면 null)를 돌려줍니다.
     */
    class Tips(private val keyLabel: (kr.dfluid.paint.shortcut.Action) -> String?) {
        private class Entry(val view: View, var label: String, val action: kr.dfluid.paint.shortcut.Action?)
        private val entries = ArrayList<Entry>()

        fun text(label: String, action: kr.dfluid.paint.shortcut.Action?): String {
            val key = action?.let(keyLabel)
            return if (key.isNullOrEmpty()) label else "$label ($key)"
        }

        fun <T : View> bind(view: T, label: String, action: kr.dfluid.paint.shortcut.Action? = null): T {
            entries.removeAll { it.view === view }
            entries.add(Entry(view, label, action))
            setTip(view, text(label, action))
            return view
        }

        /** 같은 버튼의 문구만 바꿀 때 (예: 대칭 모드). */
        fun relabel(view: View, label: String) {
            val e = entries.firstOrNull { it.view === view } ?: return
            e.label = label
            setTip(view, text(label, e.action))
        }

        fun refresh() = entries.forEach { setTip(it.view, text(it.label, it.action)) }
    }
}
