package kr.dfluid.paint.ui

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ListAdapter
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * 앱 카드와 같은 모양의 대화상자 (시스템 기본 모양 대신).
 * 만들 때([create]) 내용을 먼저 세운 뒤 창 바탕·제목·버튼·목록·안의 위젯을 현재 테마 색으로 꾸밉니다.
 * 호출부는 평소처럼 AlertDialog.Builder API를 쓰면 됩니다 (Ui.dialog).
 */
class StyledDialogBuilder(ctx: Context, theme: Int) : AlertDialog.Builder(ctx, theme) {
    override fun create(): AlertDialog {
        val d = super.create()
        d.create() // 내용 뷰를 지금 만들어야 꾸밀 수 있음
        DialogStyle.apply(d)
        return d
    }
}

object DialogStyle {
    fun apply(d: AlertDialog) {
        val ctx = d.context
        fun dp(v: Float) = Ui.dp(ctx, v)
        d.window?.let { w ->
            // 카드: 둥근 모서리 + 얇은 테두리, 화면 가장자리와 띄움
            w.setBackgroundDrawable(InsetDrawable(Ui.rounded(Ui.CARD, dp(18f).toFloat(), dp(1f), Ui.BORDER), dp(12f)))
            w.setDimAmount(0.4f)
            // 태블릿에서 너무 넓지 않게 (내용이 더 넓으면 그만큼)
            val maxW = minOf(dp(620f), ctx.resources.displayMetrics.widthPixels - dp(48f))
            w.setLayout(maxW, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // 제목: 굵게 + 카드 머리글처럼 왼쪽 강조 막대
        val titleId = ctx.resources.getIdentifier("alertTitle", "id", "android")
        if (titleId != 0) d.findViewById<TextView>(titleId)?.let { t ->
            t.textSize = 18f
            t.typeface = Typeface.DEFAULT_BOLD
            t.setTextColor(Ui.TEXT)
            val bar = Ui.rounded(Ui.BUTTON_ON, dp(2f).toFloat()).apply { setSize(dp(4f), dp(20f)) }
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(bar, null, null, null)
            t.compoundDrawablePadding = dp(10f)
        }
        d.findViewById<TextView>(android.R.id.message)?.let { m ->
            m.textSize = 14.5f
            m.setTextColor(Ui.SUBTEXT)
            m.setLineSpacing(0f, 1.25f)
        }
        styleButton(d.getButton(DialogInterface.BUTTON_POSITIVE), primary = true)
        styleButton(d.getButton(DialogInterface.BUTTON_NEGATIVE), primary = false)
        styleButton(d.getButton(DialogInterface.BUTTON_NEUTRAL), primary = false, ghost = true)
        d.listView?.let { lv ->
            lv.divider = ColorDrawable(Color.TRANSPARENT)
            lv.dividerHeight = dp(6f)
            lv.selector = ColorDrawable(Color.TRANSPARENT)
            lv.clipToPadding = false
            lv.adapter?.let { orig -> lv.adapter = RowAdapter(orig) }
        }
        d.window?.decorView?.let { styleTree(it) }
        applySpacing(d)
    }

    /**
     * 간격 체계: 카드 안쪽 여백 위·아래·좌우 모두 [EDGE], 제목 → 내용 [GAP_TITLE], 내용 → 버튼 [GAP_BUTTONS],
     * 내용 안 항목끼리 최소 [GAP_ITEM]. 기본 틀은 위만 넉넉하고 버튼 아래가 붙어 있어서 다시 맞춥니다.
     */
    private fun applySpacing(d: AlertDialog) {
        val ctx = d.context
        fun dp(v: Float) = Ui.dp(ctx, v)
        fun id(name: String) = ctx.resources.getIdentifier(name, "id", "android")
        fun view(name: String): View? = id(name).takeIf { it != 0 }?.let { d.findViewById<View>(it) }
        val edge = dp(EDGE)
        val title = view("title_template")
        val hasTitle = view("topPanel")?.visibility == View.VISIBLE && title != null
        title?.setPadding(edge, edge, edge, 0)
        // 제목이 없을 때 위 여백을 대신 만드는 틈은 없앰 (아래에서 직접 맞춤)
        view("textSpacerNoTitle")?.visibility = View.GONE
        view("textSpacerNoButtons")?.visibility = View.GONE
        val hasButtons = view("buttonPanel")?.visibility == View.VISIBLE
        val top = if (hasTitle) dp(GAP_TITLE) else edge
        d.findViewById<TextView>(android.R.id.message)?.let { m ->
            m.setPadding(edge, top, edge, if (hasButtons) 0 else edge)
        }
        d.listView?.let { lv ->
            lv.setPadding(dp(16f), top, dp(16f), if (hasButtons) 0 else dp(16f))
        }
        // setView 내용: 좌우는 내용이 가진 여백 + 4dp ≈ 24dp, 위는 제목 아래 간격
        view("customPanel")?.setPadding(dp(4f), if (hasTitle) dp(GAP_TITLE - 8f) else dp(8f), dp(4f), 0)
        d.findViewById<ViewGroup>(android.R.id.custom)?.getChildAt(0)?.let { root ->
            if (root is android.widget.LinearLayout && root.orientation == android.widget.LinearLayout.VERTICAL) {
                for (i in 1 until root.childCount) {
                    val c = root.getChildAt(i)
                    (c.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                        if (lp.topMargin < dp(GAP_ITEM)) {
                            lp.topMargin = dp(GAP_ITEM)
                            c.layoutParams = lp
                        }
                    }
                }
            }
        }
        // 버튼 줄: 위 [GAP_BUTTONS], 아래·좌우 카드 여백과 같게
        view("buttonPanel")?.let { bp ->
            bp.setPadding(0, 0, 0, 0)
            val bar = (bp as? ViewGroup)?.getChildAt(0) ?: bp
            bar.setPadding(edge - dp(6f), dp(GAP_BUTTONS), edge - dp(6f), edge)
        }
    }

    private const val EDGE = 24f
    private const val GAP_TITLE = 14f
    private const val GAP_BUTTONS = 20f
    private const val GAP_ITEM = 12f

    private fun styleButton(b: Button?, primary: Boolean, ghost: Boolean = false) {
        b ?: return
        val ctx = b.context
        val r = Ui.dp(ctx, 10f).toFloat()
        b.isAllCaps = false
        b.textSize = 14f
        b.typeface = if (primary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        b.stateListAnimator = null
        b.minHeight = Ui.dp(ctx, 40f)
        b.minimumHeight = Ui.dp(ctx, 40f)
        b.minWidth = Ui.dp(ctx, 84f)
        b.minimumWidth = Ui.dp(ctx, 84f)
        b.setPadding(Ui.dp(ctx, 18f), 0, Ui.dp(ctx, 18f), 0)
        when {
            primary -> {
                b.setTextColor(Ui.ON_ACCENT)
                b.background = pressable(Ui.BUTTON_ON, darker(Ui.BUTTON_ON), r)
            }
            ghost -> {
                b.setTextColor(Ui.SUBTEXT)
                b.background = pressable(Color.TRANSPARENT, Ui.BUTTON, r)
            }
            else -> {
                b.setTextColor(Ui.TEXT)
                b.background = pressable(Ui.BUTTON, darker(Ui.BUTTON), r)
            }
        }
        (b.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.leftMargin = Ui.dp(ctx, 6f)
            lp.rightMargin = Ui.dp(ctx, 6f)
            b.layoutParams = lp
        }
    }

    private fun pressable(normal: Int, pressed: Int, r: Float) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), Ui.rounded(pressed, r))
        addState(intArrayOf(), Ui.rounded(normal, r))
    }

    private fun darker(c: Int): Int {
        val k = 0.82f
        return Color.argb(Color.alpha(c), (Color.red(c) * k).toInt(), (Color.green(c) * k).toInt(), (Color.blue(c) * k).toInt())
    }

    /** 대화상자 안 위젯을 강조색으로 (라디오·체크·스위치·슬라이더·입력칸) */
    private fun styleTree(v: View) {
        val accent = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Ui.BUTTON_ON, Ui.MUTED),
        )
        when (v) {
            is Switch -> {
                v.thumbTintList = accent
                v.trackTintList = accent
                v.setTextColor(Ui.TEXT)
            }
            is RadioButton, is CheckBox -> {
                (v as CompoundButton).buttonTintList = accent
                v.setTextColor(Ui.TEXT)
            }
            is SeekBar -> Ui.styleSeek(v)
            is EditText -> {
                val ctx = v.context
                v.setTextColor(Ui.TEXT)
                v.setHintTextColor(Ui.MUTED)
                v.background = Ui.rounded(Ui.BUTTON, Ui.dp(ctx, 8f).toFloat(), Ui.dp(ctx, 1f), Ui.BORDER)
                val p = Ui.dp(ctx, 10f)
                v.setPadding(p, p, p, p)
                // setView로 바로 넣은 입력칸은 카드 끝에 붙지 않게 여백
                if ((v.parent as? View)?.id == android.R.id.custom) {
                    (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                        lp.leftMargin = Ui.dp(ctx, 24f); lp.rightMargin = Ui.dp(ctx, 24f)
                        lp.topMargin = Ui.dp(ctx, 8f); lp.bottomMargin = Ui.dp(ctx, 4f)
                        v.layoutParams = lp
                    }
                }
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) styleTree(v.getChildAt(i))
    }

    /** setItems 목록: 넉넉한 여백의 둥근 행, 누르면 강조 행 색 */
    private class RowAdapter(private val orig: ListAdapter) : BaseAdapter() {
        override fun getCount() = orig.count
        override fun getItem(position: Int): Any? = orig.getItem(position)
        override fun getItemId(position: Int) = orig.getItemId(position)
        override fun isEnabled(position: Int) = orig.isEnabled(position)
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = orig.getView(position, convertView, parent)
            if (v is TextView) {
                val ctx = v.context
                v.setTextColor(Ui.TEXT)
                v.textSize = 15f
                v.minHeight = Ui.dp(ctx, 46f)
                v.setPadding(Ui.dp(ctx, 16f), Ui.dp(ctx, 10f), Ui.dp(ctx, 16f), Ui.dp(ctx, 10f))
                v.background = StateListDrawable().apply {
                    val r = Ui.dp(ctx, 10f).toFloat()
                    addState(intArrayOf(android.R.attr.state_pressed), Ui.rounded(Ui.ROW_ON, r))
                    addState(intArrayOf(), Ui.rounded(Ui.CARD_HEAD, r))
                }
            }
            return v
        }
    }
}
