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
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.widget.ArrayAdapter
import android.widget.Spinner

/**
 * 코드로 UI를 만드는 작은 도우미 모음 (XML 레이아웃 없이 프레임워크 위젯만 사용).
 *
 * 색은 라이트/다크 테마에 따라 [applyTheme]이 바꿉니다. 화면을 만들 때 그 시점의 값을 읽으므로,
 * 테마를 바꾸면 UI를 다시 만들어야 합니다 (MainActivity.rebuildUi).
 */
object Ui {
    var dark = true
        private set

    /** 앱 바탕 (캔버스 둘레) */
    var BG = 0; private set
    /** 떠 있는 막대·패널 바탕 (약간 투명) */
    var PANEL = 0; private set
    /** 패널 안 카드 */
    var CARD = 0; private set
    /** 카드 머리글 */
    var CARD_HEAD = 0; private set
    var TEXT = 0; private set
    var SUBTEXT = 0; private set
    var MUTED = 0; private set
    /** 채운 버튼 바탕 */
    var BUTTON = 0; private set
    /** 켜짐/선택 (강조색) */
    var BUTTON_ON = 0; private set
    /** 강조색 위 글자·아이콘 */
    var ON_ACCENT = 0; private set
    var DIVIDER = 0; private set
    var BORDER = 0; private set
    /** 선택된 목록 행 */
    var ROW_ON = 0; private set
    /** GL이 캔버스 밖을 칠하는 색 */
    var CANVAS_BG = 0; private set

    init {
        applyTheme(true)
    }

    fun applyTheme(dark: Boolean) {
        this.dark = dark
        if (dark) {
            BG = 0xFF1B1C1E.toInt(); PANEL = 0xF2202124.toInt(); CARD = 0xFF292B2F.toInt(); CARD_HEAD = 0xFF2F3136.toInt()
            TEXT = 0xFFE9E9EB.toInt(); SUBTEXT = 0xFFA3A5AA.toInt(); MUTED = 0xFF75777D.toInt()
            BUTTON = 0xFF35373C.toInt(); BUTTON_ON = 0xFF2F6BD8.toInt(); ON_ACCENT = 0xFFFFFFFF.toInt()
            DIVIDER = 0xFF393B40.toInt(); BORDER = 0xFF3E4046.toInt(); ROW_ON = 0xFF263A5E.toInt(); CANVAS_BG = 0xFF303236.toInt()
        } else {
            BG = 0xFFE3E4E7.toInt(); PANEL = 0xF2F4F5F7.toInt(); CARD = 0xFFFFFFFF.toInt(); CARD_HEAD = 0xFFF3F4F6.toInt()
            TEXT = 0xFF1E1F22.toInt(); SUBTEXT = 0xFF5C5F66.toInt(); MUTED = 0xFF8E9198.toInt()
            BUTTON = 0xFFE9EAED.toInt(); BUTTON_ON = 0xFF2F6BD8.toInt(); ON_ACCENT = 0xFFFFFFFF.toInt()
            DIVIDER = 0xFFDCDEE2.toInt(); BORDER = 0xFFD2D4D9.toInt(); ROW_ON = 0xFFDCE7FB.toInt(); CANVAS_BG = 0xFFC8CACE.toInt()
        }
    }

    /** 현재 테마에 맞는 AlertDialog. */
    fun dialog(ctx: Context): AlertDialog.Builder = AlertDialog.Builder(
        ctx, if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
    )

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

    /** 바탕 없는(ghost) 아이콘 버튼 표시. [setOn]이 꺼짐일 때 바탕을 비웁니다. */
    private val GHOST = Any()

    /**
     * 아이콘 버튼. 길게 누르거나 펜을 올려 두면 [tip] 말풍선이 뜹니다 (View.tooltipText, API 26+).
     * [ghost] = 바탕 없이 아이콘만 (묶음 안에 둘 때). 단축키를 함께 보여주려면 [Tips.bind]로 등록하세요.
     */
    fun iconButton(ctx: Context, icon: Int, tip: String, sizeDp: Float = 40f, ghost: Boolean = false, onClick: () -> Unit): ImageView =
        ImageView(ctx).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = dp(ctx, 8f)
            setPadding(p, p, p, p)
            minimumWidth = dp(ctx, sizeDp)
            minimumHeight = dp(ctx, sizeDp)
            if (ghost) tag = GHOST
            isFocusable = false
            isClickable = true
            setTip(this, tip)
            setOnClickListener { onClick() }
            setOn(this, false)
        }

    /** 아이콘 색을 테마 글자색으로. */
    fun tint(v: ImageView, color: Int = TEXT) {
        v.imageTintList = ColorStateList.valueOf(color)
    }

    /** 말풍선 + 접근성 설명. */
    fun setTip(v: View, tip: String) {
        v.tooltipText = tip
        v.contentDescription = tip
    }

    fun setOn(v: View, on: Boolean) {
        val r = dp(v.context, 6f).toFloat()
        v.background = when {
            on -> rounded(BUTTON_ON, r)
            v.tag === GHOST -> rounded(Color.TRANSPARENT, r)
            else -> rounded(BUTTON, r)
        }
        when (v) {
            is ImageView -> tint(v, if (on) ON_ACCENT else TEXT)
            is TextView -> v.setTextColor(if (on) ON_ACCENT else TEXT)
        }
    }

    fun setEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.35f
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

    /** 세로 막대 안의 짧은 가로 구분선 (묶음 사이). */
    fun shortDivider(ctx: Context, widthDp: Float) = View(ctx).apply {
        setBackgroundColor(DIVIDER)
        layoutParams = LinearLayout.LayoutParams(dp(ctx, widthDp), dp(ctx, 1f)).apply {
            topMargin = dp(ctx, 5f); bottomMargin = dp(ctx, 6f)
        }
    }

    fun square(ctx: Context, sizeDp: Float) = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))

    fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** 슬라이더 색을 테마에 맞춤. */
    fun styleSeek(seek: SeekBar) {
        seek.progressTintList = ColorStateList.valueOf(BUTTON_ON)
        seek.thumbTintList = ColorStateList.valueOf(BUTTON_ON)
        seek.progressBackgroundTintList = ColorStateList.valueOf(MUTED)
    }

    /** 테마 글자색을 쓰는 드롭다운 (프레임워크 기본 항목은 액티비티 테마 색이라 라이트 모드에서 안 보임). */
    fun styleSpinner(spinner: Spinner, items: List<String>) {
        val ctx = spinner.context
        spinner.adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, items) {
            // 닫힌 상태에는 ▾를 붙여 드롭다운임을 보여 줍니다 (바탕을 바꾸면 기본 화살표가 사라짐).
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(TEXT)
                    textSize = 13f
                    text = "${getItem(position)}  ▾"
                }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(TEXT)
                    setBackgroundColor(CARD)
                    val p = dp(ctx, 12f)
                    setPadding(p, p, p, p)
                }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.setPopupBackgroundDrawable(rounded(CARD, dp(ctx, 8f).toFloat(), dp(ctx, 1f), BORDER))
        spinner.background = rounded(BUTTON, dp(ctx, 6f).toFloat())
    }

    /**
     * 접을 수 있는 카드: 강조 막대 + 제목 + (머리글 버튼들) + 접기 화살표.
     * [body]를 넣고, 접힘 상태가 바뀌면 [onToggle]이 불립니다 (부모가 무게를 다시 나눔).
     */
    class Card(ctx: Context, title: String, open: Boolean, private val onToggle: (Boolean) -> Unit) {
        val titleView = text(ctx, title, 13f, TEXT, bold = true)
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        private val chevron = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = dp(ctx, 6f)
            setPadding(p, p, p, p)
            tint(this, SUBTEXT)
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 10f)
            setPadding(p, dp(ctx, 8f), p, dp(ctx, 10f))
        }
        var open = open
            private set

        val header: LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 10f), dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f))
            minimumHeight = dp(ctx, 40f)
            val radius = dp(ctx, 10f).toFloat()
            background = GradientDrawable().apply {
                setColor(CARD_HEAD)
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            addView(View(ctx).apply { setBackgroundColor(BUTTON_ON) }, LinearLayout.LayoutParams(dp(ctx, 3f), dp(ctx, 15f)).apply {
                rightMargin = dp(ctx, 8f)
            })
            addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(actions)
            addView(chevron, square(ctx, 30f))
            isClickable = true
            setOnClickListener { toggle() }
        }

        val view: LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, dp(ctx, 10f).toFloat(), dp(ctx, 1f), BORDER)
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        init {
            apply()
        }

        /** 머리글 오른쪽의 작은 아이콘 버튼 (누르면 카드가 접히지 않음). */
        fun addAction(v: ImageView) {
            actions.addView(v, square(v.context, 32f).apply { leftMargin = dp(v.context, 2f) })
        }

        private fun toggle() {
            open = !open
            apply()
            onToggle(open)
        }

        private fun apply() {
            body.visibility = if (open) View.VISIBLE else View.GONE
            chevron.setImageResource(if (open) kr.dfluid.paint.R.drawable.ic_chevron_down else kr.dfluid.paint.R.drawable.ic_chevron_right)
            tint(chevron, SUBTEXT)
        }
    }

    /** 라벨 + 슬라이더 + 값 표시 한 줄. */
    class SliderRow(ctx: Context, label: String, maxValue: Int) {
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val seek = SeekBar(ctx).apply {
            max = maxValue
            isFocusable = false
            styleSeek(this)
        }
        val value = text(ctx, "", 12f, TEXT)
        var onChange: ((Int) -> Unit)? = null
        var onStart: (() -> Unit)? = null
        var onStop: (() -> Unit)? = null

        init {
            if (label.isNotEmpty()) {
                view.addView(text(ctx, label, 12f, SUBTEXT), LinearLayout.LayoutParams(dp(ctx, 64f), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            view.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            view.addView(value, LinearLayout.LayoutParams(dp(ctx, 48f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(ctx, 2f)
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
