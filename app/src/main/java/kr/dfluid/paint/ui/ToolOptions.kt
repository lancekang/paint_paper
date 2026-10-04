package kr.dfluid.paint.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kr.dfluid.paint.brush.Brush
import kr.dfluid.paint.brush.BrushLibrary
import kr.dfluid.paint.brush.Tool
import kr.dfluid.paint.engine.SelOp
import kr.dfluid.paint.engine.SelShape
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** 오른쪽 패널 위쪽: 현재 도구의 옵션 (브러시면 보조 도구 목록 + 크기/불투명도 등). */
class ToolOptions(private val ctx: Context, private val host: Host) {

    interface Host {
        val library: BrushLibrary
        val settings: AppSettings
        fun onBrushChanged()
        fun editBrush(tool: Tool, brush: Brush)
        fun selectAll()
        fun deselect()
        fun invertSelection()
        fun startTransform()
        fun onRecentColor(color: Int)
    }

    val view = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var tool = Tool.PEN
    private var sizeRow: Ui.SliderRow? = null
    private var opacityRow: Ui.SliderRow? = null
    private var hardnessRow: Ui.SliderRow? = null
    private var smoothingRow: Ui.SliderRow? = null
    private val recentRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    /** 카드 머리글 제목 (MainActivity가 연결). 없으면 본문 맨 위에 제목을 넣습니다. */
    var titleView: TextView? = null

    /** 경도·손떨림 보정 같은 세부 설정 펼침 */
    private var detailsOpen = false

    /** 최근 색: 옵션이 길어져도 가려지지 않게 스크롤 밖(패널에 고정)에 둡니다. */
    val recentView: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(Ui.text(ctx, "최근 색", 11f, Ui.SUBTEXT))
        addView(recentRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(ctx, 2f)
        })
    }

    fun show(t: Tool) {
        tool = t
        rebuild()
    }

    private fun lp(top: Float = 4f) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = Ui.dp(ctx, top)
    }

    private fun rebuild() {
        view.removeAllViews()
        sizeRow = null; opacityRow = null; hardnessRow = null; smoothingRow = null
        val title = if (tool.isBrush) "보조 도구 · ${tool.label}" else "도구 속성 · ${tool.label}"
        titleView?.let { it.text = title } ?: view.addView(Ui.text(ctx, title, 14f, bold = true))
        when {
            tool.isBrush -> buildBrush()
            tool == Tool.SELECT -> buildSelect()
            tool == Tool.FILL -> buildFill()
            tool == Tool.GRADIENT -> buildGradient()
            tool == Tool.MOVE -> {
                view.addView(hint("드래그하면 레이어(선택 영역이 있으면 그 안)를 옮깁니다."), lp())
                view.addView(Ui.button(ctx, "자유 변형 (Ctrl+T)") { host.startTransform() }, lp(8f))
            }
            tool == Tool.EYEDROPPER -> view.addView(hint("누른 곳의 색을 주색으로 가져옵니다. Alt를 누르고 있으면 다른 도구에서도 스포이드가 됩니다."), lp())
            tool == Tool.HAND -> view.addView(hint("드래그해 화면을 옮깁니다. Space를 누르고 있어도 됩니다."), lp())
        }
        refreshRecent()
    }

    private fun hint(s: String) = Ui.text(ctx, s, 12f, Ui.SUBTEXT)

    private fun toggleRow(labels: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = ArrayList<TextView>()
        labels.forEachIndexed { i, label ->
            val b = Ui.button(ctx, label) {
                onPick(i)
                buttons.forEachIndexed { j, bb -> Ui.setOn(bb, j == i) }
            }
            Ui.setOn(b, i == selected)
            buttons.add(b)
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Ui.dp(ctx, 4f) })
        }
        return row
    }

    // ---- 브러시 ----

    private fun buildBrush() {
        val lib = host.library
        val list = lib.list(tool)
        val active = lib.active(tool)
        // 보조 도구: 3열 칩 (선택 = 강조색). 길게 누르면 메뉴.
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var line: LinearLayout? = null
        list.forEachIndexed { i, b ->
            if (i % 3 == 0) {
                line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                box.addView(line, lp(if (i == 0) 0f else 4f))
            }
            val on = b.id == active?.id
            val chip = Ui.text(ctx, b.name, 12f, if (on) Ui.ON_ACCENT else Ui.TEXT).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                val p = Ui.dp(ctx, 6f)
                setPadding(p, Ui.dp(ctx, 7f), p, Ui.dp(ctx, 7f))
                background = if (on) Ui.rounded(Ui.BUTTON_ON, Ui.dp(ctx, 6f).toFloat())
                else Ui.rounded(Ui.CARD, Ui.dp(ctx, 6f).toFloat(), Ui.dp(ctx, 1f), Ui.BORDER)
                Ui.setTip(this, b.name + " (길게 누르면 메뉴)")
                isClickable = true
                setOnClickListener {
                    lib.setActive(tool, b.id)
                    lib.save()
                    host.onBrushChanged()
                    rebuild()
                }
                setOnLongClickListener { presetMenu(b); true }
            }
            line!!.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i % 3 != 2) rightMargin = Ui.dp(ctx, 4f)
            })
        }
        // 마지막 줄 빈 칸 채우기 (칩 너비를 맞춤)
        val rest = (3 - list.size % 3) % 3
        repeat(rest) { k -> line?.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { if (k < rest - 1) rightMargin = Ui.dp(ctx, 4f) }) }
        view.addView(box, lp(2f))
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun act(label: String, f: () -> Unit) = actions.addView(Ui.button(ctx, label, onClick = f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = Ui.dp(ctx, 4f)
        })
        act("+ 복제") {
            val src = lib.active(tool) ?: return@act
            lib.add(tool, src.duplicate(src.name + " 2"))
            host.onBrushChanged()
            rebuild()
        }
        act("설정…") { lib.active(tool)?.let { host.editBrush(tool, it) } }
        view.addView(actions, lp(6f))

        val size = Ui.SliderRow(ctx, "크기", 1000)
        val op = Ui.SliderRow(ctx, "불투명도", 100)
        val hard = Ui.SliderRow(ctx, "경도", 100)
        val smooth = Ui.SliderRow(ctx, "손떨림 보정", 100)
        sizeRow = size; opacityRow = op; hardnessRow = hard; smoothingRow = smooth
        size.onChange = { p -> lib.active(tool)?.let { it.size = sliderToSize(p); refresh(); host.onBrushChanged() } }
        op.onChange = { p -> lib.active(tool)?.let { it.opacity = (p / 100f).coerceAtLeast(0.01f); refresh() } }
        hard.onChange = { p -> lib.active(tool)?.let { it.hardness = p / 100f; refresh() } }
        smooth.onChange = { p -> host.settings.smoothing = p / 100f * 0.95f; refresh() }
        listOf(size, op, hard).forEach { r -> r.onStop = { lib.save() } }
        for (r in listOf(size, op)) view.addView(r.view, lp())
        // 세부 설정은 접어 둡니다.
        val details = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (detailsOpen) View.VISIBLE else View.GONE
            for (r in listOf(hard, smooth)) addView(r.view, lp())
        }
        val toggle = Ui.text(ctx, "", 12f, Ui.SUBTEXT).apply {
            val p = Ui.dp(ctx, 4f)
            setPadding(0, p, 0, p)
            isClickable = true
        }
        fun label() { toggle.text = if (detailsOpen) "세부 설정 ▴" else "세부 설정 (경도, 손떨림 보정) ▾" }
        label()
        toggle.setOnClickListener {
            detailsOpen = !detailsOpen
            details.visibility = if (detailsOpen) View.VISIBLE else View.GONE
            label()
        }
        view.addView(toggle, lp(2f))
        view.addView(details)
        refresh()
    }

    private fun presetMenu(b: Brush) {
        val lib = host.library
        val items = arrayOf("이름 바꾸기", "설정…", "복제", "위로", "아래로", "삭제", "이 도구 기본값으로 되돌리기")
        Ui.dialog(ctx)
            .setTitle(b.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(ctx).apply { setText(b.name); setSelectAllOnFocus(true) }
                        Ui.dialog(ctx).setTitle("이름").setView(input)
                            .setPositiveButton("확인") { _, _ ->
                                val n = input.text.toString().trim()
                                if (n.isNotEmpty()) { b.name = n; lib.save(); rebuild() }
                            }
                            .setNegativeButton("취소", null).show()
                    }
                    1 -> host.editBrush(tool, b)
                    2 -> { lib.add(tool, b.duplicate(b.name + " 2")); host.onBrushChanged(); rebuild() }
                    3 -> { lib.move(tool, b.id, -1); rebuild() }
                    4 -> { lib.move(tool, b.id, +1); rebuild() }
                    5 -> {
                        if (!lib.remove(tool, b.id)) android.widget.Toast.makeText(ctx, "마지막 보조 도구는 지울 수 없습니다.", android.widget.Toast.LENGTH_SHORT).show()
                        host.onBrushChanged(); rebuild()
                    }
                    6 -> Dialogs.confirm(ctx, "${tool.label}의 보조 도구를 기본값으로 되돌릴까요? 직접 만든 보조 도구는 사라집니다.", "되돌리기") {
                        lib.resetTool(tool); host.onBrushChanged(); rebuild()
                    }
                }
            }
            .show()
    }

    /** 키보드 단축키 등으로 값이 바뀌었을 때 슬라이더만 갱신. */
    fun refresh() {
        val b = host.library.active(tool)
        if (b != null) {
            sizeRow?.set(sizeToSlider(b.size), formatSize(b.size))
            val op = (b.opacity * 100).roundToInt()
            opacityRow?.set(op, "$op%")
            val hd = (b.hardness * 100).roundToInt()
            hardnessRow?.set(hd, "$hd%")
        }
        val sm = (host.settings.smoothing / 0.95f * 100).roundToInt()
        smoothingRow?.set(sm, "$sm")
    }

    // ---- 선택 ----

    private fun buildSelect() {
        val s = host.settings
        view.addView(toggleRow(listOf("사각형", "타원", "올가미", "자동"), s.selectShape.ordinal) {
            val wasWand = s.selectShape == SelShape.WAND
            s.selectShape = SelShape.entries[it]
            s.save()
            // 자동 선택 옵션을 보이거나 숨김
            if (wasWand != (s.selectShape == SelShape.WAND)) view.post { rebuild() }
        }, lp(6f))
        view.addView(toggleRow(listOf("새로", "추가", "빼기", "교차"), s.selectMode.ordinal) { s.selectMode = SelOp.entries[it]; s.save() }, lp(4f))
        if (s.selectShape == SelShape.WAND) {
            view.addView(toggleRow(listOf("모든 레이어 참조", "현재 레이어"), if (s.wandReferenceAll) 0 else 1) {
                s.wandReferenceAll = it == 0; s.save()
            }, lp(6f))
            intSlider("허용 오차", 0, 100, { s.wandTolerance * 100 / 255 }, { s.wandTolerance = it * 255 / 100 }) { "$it" }
            intSlider("틈 메우기", 0, 10, { s.wandGap }, { s.wandGap = it }) { if (it == 0) "끔" else "${it}px" }
            intSlider("영역 확장", 0, 6, { s.wandExpand }, { s.wandExpand = it }) { "${it}px" }
            view.addView(hint("누른 곳과 비슷한 색으로 이어진 영역을 선택합니다. 선화 안쪽을 누르면 칸 하나가 선택됩니다. Shift = 추가, Alt = 빼기."), lp())
        } else {
            view.addView(hint("Shift = 추가, Alt = 빼기. 짧게 누르면 선택 해제."), lp())
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, f) in listOf<kotlin.Pair<String, () -> Unit>>(
            "모두" to { host.selectAll() }, "해제" to { host.deselect() }, "반전" to { host.invertSelection() }, "변형" to { host.startTransform() }
        )) {
            row.addView(Ui.button(ctx, label, onClick = f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = Ui.dp(ctx, 4f)
            })
        }
        view.addView(row, lp(8f))
    }

    // ---- 채우기 ----

    private fun buildFill() {
        val s = host.settings
        view.addView(toggleRow(listOf("모든 레이어 참조", "현재 레이어"), if (s.fillReferenceAll) 0 else 1) {
            s.fillReferenceAll = it == 0; s.save()
        }, lp(6f))
        intSlider("허용 오차", 0, 100, { s.fillTolerance * 100 / 255 }, { s.fillTolerance = it * 255 / 100 }) { "$it" }
        intSlider("틈 메우기", 0, 10, { s.fillGap }, { s.fillGap = it }) { if (it == 0) "끔" else "${it}px" }
        intSlider("영역 확장", 0, 6, { s.fillExpand }, { s.fillExpand = it }) { "${it}px" }
        intSlider("불투명도", 1, 100, { (s.fillOpacity * 100).roundToInt() }, { s.fillOpacity = it / 100f }) { "$it%" }
        view.addView(hint("Alt+Backspace = 선택 영역을 주색으로 채우기"), lp())
    }

    private fun intSlider(label: String, min: Int, max: Int, get: () -> Int, set: (Int) -> Unit, fmt: (Int) -> String) {
        val row = Ui.SliderRow(ctx, label, max - min)
        row.set(get() - min, fmt(get()))
        row.onChange = { p ->
            set(p + min)
            row.value.text = fmt(p + min)
        }
        row.onStop = { host.settings.save() }
        view.addView(row.view, lp())
    }

    // ---- 그라데이션 ----

    private fun buildGradient() {
        val s = host.settings
        view.addView(toggleRow(listOf("선형", "원형"), if (s.gradientRadial) 1 else 0) { s.gradientRadial = it == 1; s.save() }, lp(6f))
        view.addView(toggleRow(listOf("주색→보조색", "주색→투명"), if (s.gradientToTransparent) 1 else 0) {
            s.gradientToTransparent = it == 1; s.save()
        }, lp(4f))
        intSlider("불투명도", 1, 100, { (s.gradientOpacity * 100).roundToInt() }, { s.gradientOpacity = it / 100f }) { "$it%" }
        view.addView(hint("시작점에서 끝점까지 드래그합니다. 선택 영역이 있으면 그 안에만 칠합니다."), lp())
    }

    // ---- 최근 색 ----

    fun refreshRecent() {
        recentRow.removeAllViews()
        val size = Ui.dp(ctx, 20f)
        for (c in host.settings.recentColors) {
            recentRow.addView(View(ctx).apply {
                background = Ui.rounded(c, Ui.dp(ctx, 4f).toFloat(), 1, 0x55FFFFFF)
                setOnClickListener { host.onRecentColor(c) }
            }, LinearLayout.LayoutParams(size, size).apply { rightMargin = Ui.dp(ctx, 3f) })
        }
        recentRow.gravity = Gravity.START
    }

    companion object {
        fun sliderToSize(p: Int): Float = Brush.MAX_SIZE.pow(p / 1000f).coerceIn(Brush.MIN_SIZE, Brush.MAX_SIZE)
        fun sizeToSlider(s: Float): Int = (ln(s) / ln(Brush.MAX_SIZE) * 1000f).roundToInt()
        fun formatSize(s: Float) = if (s < 10f) String.format("%.1fpx", s) else "${s.roundToInt()}px"
    }
}
