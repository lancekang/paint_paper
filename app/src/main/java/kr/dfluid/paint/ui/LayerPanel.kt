package kr.dfluid.paint.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import kr.dfluid.paint.R
import kr.dfluid.paint.document.BlendMode
import kr.dfluid.paint.document.LayerProps
import kr.dfluid.paint.document.NodeInfo
import kr.dfluid.paint.document.NodeKind
import kr.dfluid.paint.engine.CanvasRenderer
import kr.dfluid.paint.shortcut.Action
import kotlin.math.roundToInt

/** 레이어 트리 + 선택 노드 속성. 목록은 위 = 맨 위 레이어, 폴더 안은 들여쓰기. */
class LayerPanel(private val ctx: Context, private val renderer: CanvasRenderer, private val tips: Ui.Tips) {

    private var nodes: List<NodeInfo> = emptyList()
    private var activeId = 0
    private var dragStartProps: LayerProps? = null
    private var updatingBlend = false
    private var blendChoices: List<BlendMode> = BlendMode.forLayers
    private val thumbs = HashMap<Int, Bitmap>()
    private val thumbViews = HashMap<Int, ImageView>()

    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val opacity = Ui.SliderRow(ctx, "", 100)
    private val blend = Spinner(ctx).apply { isFocusable = false }
    private val clipBtn = icon(R.drawable.ic_clip, "아래 레이어에서 클리핑", Action.LAYER_CLIP) { toggle { it.copy(clip = !it.clip) } }
    private val lockBtn = icon(R.drawable.ic_alpha_lock, "투명 픽셀 잠금", Action.LAYER_ALPHA_LOCK) { toggle { it.copy(alphaLock = !it.alphaLock) } }
    private val maskBtn = icon(R.drawable.ic_mask, "레이어 마스크 (없으면 추가, 있으면 메뉴)") { maskMenu() }

    private fun icon(res: Int, tip: String, action: Action? = null, onClick: () -> Unit) =
        tips.bind(Ui.iconButton(ctx, res, tip, 34f, ghost = true, onClick = onClick), tip, action)

    private val active: NodeInfo? get() = nodes.firstOrNull { it.id == activeId }

    /** 레이어 카드 머리글에 둘 버튼: 자주 쓰는 것만 (새 레이어, 새 폴더, 더보기). */
    val headerActions: List<ImageView> = listOf(
        icon(R.drawable.ic_layer_add, "새 레이어", Action.LAYER_NEW) { renderer.addLayer() },
        icon(R.drawable.ic_vector_add, "새 벡터 레이어 (선 단위로 지우고 옮기기)") { renderer.addLayer(vector = true) },
        icon(R.drawable.ic_folder_add, "새 폴더", Action.LAYER_FOLDER) { renderer.addFolder() },
        icon(R.drawable.ic_more, "더보기 (순서 이동, 지우기, 폴더로 묶기, 이름 바꾸기)") { moreMenu() },
    )

    /** 카드 본문: 합성 모드·불투명도 한 줄 → 목록 → 아래 도구줄. */
    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(blend, LinearLayout.LayoutParams(0, Ui.dp(ctx, 32f), 1f))
            addView(opacity.view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply {
                leftMargin = Ui.dp(ctx, 6f)
            })
        })
        addView(ScrollView(ctx).apply {
            isFillViewport = true
            addView(list)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = Ui.dp(ctx, 6f)
        })
        addView(View(ctx).apply { setBackgroundColor(Ui.DIVIDER) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 1f)).apply {
            topMargin = Ui.dp(ctx, 4f); bottomMargin = Ui.dp(ctx, 4f)
        })
        val tools = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (b in listOf(
            clipBtn, lockBtn, maskBtn,
            icon(R.drawable.ic_merge_down, "아래 레이어와 병합", Action.LAYER_MERGE_DOWN) { renderer.mergeDown() },
            icon(R.drawable.ic_duplicate, "복제", Action.LAYER_DUPLICATE) { renderer.duplicate() },
            icon(R.drawable.ic_trash, "삭제") { renderer.deleteNode() },
        )) {
            tools.addView(b, LinearLayout.LayoutParams(0, Ui.dp(ctx, 34f), 1f))
        }
        addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    init {
        setBlendChoices(BlendMode.forLayers)
        blend.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (updatingBlend) return
                val info = active ?: return
                val mode = blendChoices.getOrNull(position) ?: return
                if (mode != info.props.blend) renderer.setProps(info.id, info.props.copy(blend = mode), record = true)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        Ui.setTip(blend, "합성 모드")
        Ui.setTip(opacity.seek, "레이어 불투명도")
        opacity.onStart = { dragStartProps = active?.props }
        opacity.onChange = { p ->
            val base = dragStartProps ?: active?.props
            val info = active
            if (base != null && info != null) {
                renderer.setProps(info.id, base.copy(opacity = p / 100f), record = false)
                opacity.value.text = "$p%"
            }
        }
        opacity.onStop = {
            val before = dragStartProps
            val info = active
            if (before != null && info != null) {
                renderer.setProps(info.id, before.copy(opacity = opacity.seek.progress / 100f), record = true, before = before)
            }
            dragStartProps = null
        }
    }

    private fun setBlendChoices(choices: List<BlendMode>) {
        if (choices == blendChoices && blend.adapter != null) return
        blendChoices = choices
        Ui.styleSpinner(blend, choices.map { it.label })
    }

    /** 자주 쓰지 않는 레이어 동작. */
    private fun moreMenu() {
        val info = active ?: return
        val items = arrayOf(
            "위로 이동", "아래로 이동",
            tips.text("레이어 지우기 (선택 영역이 있으면 그 안만)", Action.LAYER_CLEAR),
            tips.text("폴더로 묶기", Action.LAYER_GROUP),
            "이름 바꾸기",
            if (info.props.reference) "참조 레이어 해제" else "참조 레이어로 지정 (채우기·자동 선택이 이 레이어의 선을 봄)",
            "불투명한 부분을 선택 영역으로",
            if (info.kind == NodeKind.RASTER) (if (info.props.borderWidth > 0f) "경계 효과 (테두리) · ${info.props.borderWidth.roundToInt()}px…" else "경계 효과 (테두리)…") else null,
            if (info.kind == NodeKind.RASTER) (if (info.props.toneCell > 0f) "톤 효과 (망점) · 켜짐…" else "톤 효과 (망점)…") else null,
            if (info.kind == NodeKind.RASTER) (if (info.props.layerColorOn) "레이어 컬러 끄기" else "레이어 컬러 (밑그림을 파랗게 등)…") else null,
            if (info.props.text != null || info.props.vector) "래스터화 (일반 레이어로)" else null,
        ).filterNotNull().toTypedArray()
        Ui.dialog(ctx)
            .setTitle(info.props.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> renderer.moveNode(+1)
                    1 -> renderer.moveNode(-1)
                    2 -> renderer.clearLayer()
                    3 -> renderer.groupActive()
                    4 -> rename(info)
                    5 -> renderer.setProps(info.id, info.props.copy(reference = !info.props.reference), record = true)
                    6 -> renderer.selectFromLayer(kr.dfluid.paint.engine.SelOp.REPLACE)
                    7 -> if (info.kind == NodeKind.RASTER) borderDialog(info) else Unit
                    8 -> if (info.kind == NodeKind.RASTER) toneDialog(info) else Unit
                    9 -> if (info.kind == NodeKind.RASTER) {
                        if (info.props.layerColorOn) renderer.setProps(info.id, info.props.copy(layerColorOn = false), record = true)
                        else layerColorDialog(info)
                    }
                    10 -> renderer.rasterizeText(info.id)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 경계 효과: 굵기(0 = 끔)와 색. 움직이는 동안 미리보기, 확인하면 실행취소 한 단계. */
    private fun borderDialog(info: NodeInfo) {
        val start = info.props
        var width = if (start.borderWidth > 0f) start.borderWidth else 4f
        var color = start.borderColor
        fun preview(record: Boolean) = renderer.setProps(info.id, start.copy(borderWidth = width, borderColor = color), record, if (record) start else null)
        val pad = Ui.dp(ctx, 20f)
        val row = Ui.SliderRow(ctx, "굵기", kr.dfluid.paint.document.ProjectIO.MAX_BORDER.toInt())
        row.set(width.roundToInt(), "${width.roundToInt()}px")
        row.onChange = { p ->
            width = p.toFloat().coerceAtLeast(1f)
            row.set(p, "${width.roundToInt()}px")
            preview(false)
        }
        val swatch = View(ctx)
        fun refresh() { swatch.background = Ui.rounded(color, Ui.dp(ctx, 4f).toFloat(), Ui.dp(ctx, 1f), android.graphics.Color.GRAY) }
        refresh()
        val colorRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(ctx, "색", 12f, Ui.SUBTEXT), LinearLayout.LayoutParams(Ui.dp(ctx, 64f), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(swatch, LinearLayout.LayoutParams(Ui.dp(ctx, 32f), Ui.dp(ctx, 26f)))
            addView(Ui.hspace(ctx, 8f))
            for ((label, c) in listOf("흰색" to android.graphics.Color.WHITE, "검정" to android.graphics.Color.BLACK)) {
                addView(Ui.button(ctx, label) { color = c; refresh(); preview(false) }, Ui.wrap())
                addView(Ui.hspace(ctx, 4f))
            }
            addView(Ui.button(ctx, "색 지정…") { Dialogs.colorPicker(ctx, color) { c -> color = c; refresh(); preview(false) } }, Ui.wrap())
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            minimumWidth = Ui.dp(ctx, 380f)
            addView(row.view)
            addView(colorRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8f) })
            addView(Ui.text(ctx, "불투명한 부분 둘레에 테두리를 그립니다. 레이어 픽셀은 바뀌지 않고, 내보낸 그림에는 들어갑니다.", 11.5f, Ui.MUTED).apply {
                setPadding(0, Ui.dp(ctx, 8f), 0, 0)
            })
        }
        var done = false
        val dlg = Ui.dialog(ctx)
            .setTitle("경계 효과 · ${info.props.name}")
            .setView(root)
            .setPositiveButton("확인") { _, _ -> done = true; preview(true) }
            .setNeutralButton("효과 끄기") { _, _ ->
                done = true
                renderer.setProps(info.id, start.copy(borderWidth = 0f), true, start)
            }
            .setNegativeButton("취소", null)
            .create()
        dlg.setOnDismissListener { if (!done) renderer.setProps(info.id, start, false) }
        dlg.window?.let { w ->
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setGravity(Gravity.BOTTOM)
        }
        dlg.show()
        preview(false)
    }

    /** 레이어 컬러: 미리 정한 색 몇 가지 + 색 지정. 고르면 바로 켜짐 (실행취소 한 단계). */
    private fun layerColorDialog(info: NodeInfo) {
        val presets = listOf("파랑" to 0xFF3D8BFF.toInt(), "빨강" to 0xFFE53935.toInt(), "초록" to 0xFF43A047.toInt(), "회색" to 0xFF9E9E9E.toInt())
        val items = (presets.map { it.first } + "색 지정…").toTypedArray()
        fun on(c: Int) = renderer.setProps(info.id, info.props.copy(layerColorOn = true, layerColor = c), record = true)
        Ui.dialog(ctx)
            .setTitle("레이어 컬러 · ${info.props.name}")
            .setItems(items) { _, which ->
                if (which < presets.size) on(presets[which].second)
                else Dialogs.colorPicker(ctx, info.props.layerColor) { c -> on(c) }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 톤 효과: 망점 간격·각도·색. 레이어의 농도(알파 × 어두움)가 망점 크기가 됩니다. */
    private fun toneDialog(info: NodeInfo) {
        val start = info.props
        var cell = if (start.toneCell > 0f) start.toneCell else 8f
        var angle = start.toneAngle
        var color = start.toneColor
        fun apply(record: Boolean) = renderer.setProps(info.id, start.copy(toneCell = cell, toneAngle = angle, toneColor = color), record, if (record) start else null)
        val pad = Ui.dp(ctx, 20f)
        val cellRow = Ui.SliderRow(ctx, "간격", 60)
        cellRow.set((cell - 4f).roundToInt().coerceIn(0, 60), "${cell.roundToInt()}px")
        cellRow.onChange = { p -> cell = 4f + p; cellRow.set(p, "${cell.roundToInt()}px"); apply(false) }
        val angleRow = Ui.SliderRow(ctx, "각도", 90)
        angleRow.set(angle.roundToInt().coerceIn(0, 90), "${angle.roundToInt()}°")
        angleRow.onChange = { p -> angle = p.toFloat(); angleRow.set(p, "$p°"); apply(false) }
        val swatch = View(ctx)
        fun refresh() { swatch.background = Ui.rounded(color, Ui.dp(ctx, 4f).toFloat(), Ui.dp(ctx, 1f), android.graphics.Color.GRAY) }
        refresh()
        val colorRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(ctx, "망점 색", 12f, Ui.SUBTEXT), LinearLayout.LayoutParams(Ui.dp(ctx, 64f), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(swatch, LinearLayout.LayoutParams(Ui.dp(ctx, 32f), Ui.dp(ctx, 26f)))
            addView(Ui.hspace(ctx, 8f))
            addView(Ui.button(ctx, "검정") { color = android.graphics.Color.BLACK; refresh(); apply(false) }, Ui.wrap())
            addView(Ui.hspace(ctx, 4f))
            addView(Ui.button(ctx, "색 지정…") { Dialogs.colorPicker(ctx, color) { c -> color = c; refresh(); apply(false) } }, Ui.wrap())
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            minimumWidth = Ui.dp(ctx, 380f)
            addView(cellRow.view)
            addView(angleRow.view)
            addView(colorRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8f) })
            addView(Ui.text(ctx, "레이어의 진한 정도(회색·불투명도)를 망점 크기로 바꿉니다. 회색으로 칠하면 톤이 됩니다. 픽셀은 그대로입니다.", 11.5f, Ui.MUTED).apply {
                setPadding(0, Ui.dp(ctx, 8f), 0, 0)
            })
        }
        var done = false
        val dlg = Ui.dialog(ctx)
            .setTitle("톤 효과 · ${info.props.name}")
            .setView(root)
            .setPositiveButton("확인") { _, _ -> done = true; apply(true) }
            .setNeutralButton("효과 끄기") { _, _ ->
                done = true
                renderer.setProps(info.id, start.copy(toneCell = 0f), true, start)
            }
            .setNegativeButton("취소", null)
            .create()
        dlg.setOnDismissListener { if (!done) renderer.setProps(info.id, start, false) }
        dlg.window?.let { w ->
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setGravity(Gravity.BOTTOM)
        }
        dlg.show()
        apply(false)
    }

    private fun toggle(change: (LayerProps) -> LayerProps) {
        val info = active ?: return
        renderer.setProps(info.id, change(info.props), record = true)
    }

    fun update(newNodes: List<NodeInfo>, newActive: Int, liveIds: Set<Int>) {
        nodes = newNodes
        activeId = newActive
        // 마스크 썸네일은 -id로 저장됩니다.
        thumbs.keys.toList().forEach { if (it !in liveIds && -it !in liveIds) thumbs.remove(it)?.recycle() }
        val cur = active
        if (cur != null && dragStartProps == null) {
            val pct = (cur.props.opacity * 100).roundToInt()
            opacity.set(pct, "$pct%")
        }
        if (cur != null) {
            setBlendChoices(if (cur.kind == NodeKind.FOLDER) BlendMode.entries.toList() else BlendMode.forLayers)
            updatingBlend = true
            blend.setSelection(blendChoices.indexOf(cur.props.blend).coerceAtLeast(0), false)
            updatingBlend = false
            val raster = cur.kind == NodeKind.RASTER
            Ui.setOn(clipBtn, cur.props.clip)
            Ui.setOn(lockBtn, cur.props.alphaLock)
            Ui.setEnabled(lockBtn, raster)
            Ui.setEnabled(maskBtn, raster)
            Ui.setOn(maskBtn, cur.props.mask)
        }
        rebuildList()
    }

    fun setThumbnail(id: Int, bmp: Bitmap) {
        thumbs.put(id, bmp)?.let { old -> if (old !== bmp) old.recycle() }
        thumbViews[id]?.setImageBitmap(bmp)
    }

    private fun rebuildList() {
        list.removeAllViews()
        thumbViews.clear()
        for (info in nodes) list.addView(makeRow(info), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = Ui.dp(ctx, 2f)
        })
    }

    private fun makeRow(info: NodeInfo): View {
        val p = info.props
        val folder = info.kind == NodeKind.FOLDER
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = Ui.dp(ctx, 6f)
            setPadding(pad + Ui.dp(ctx, 14f) * info.depth, pad, pad, pad)
            background = Ui.rounded(if (info.id == activeId) Ui.ROW_ON else 0x00000000, Ui.dp(ctx, 6f).toFloat())
            isClickable = true
            setOnClickListener { renderer.selectNode(info.id) }
            setOnLongClickListener { rename(info); true }
        }
        row.addView(ImageView(ctx).apply {
            setImageResource(if (p.visible) R.drawable.ic_eye else R.drawable.ic_eye_off)
            Ui.tint(this, if (p.visible) Ui.TEXT else Ui.MUTED)
            val pad = Ui.dp(ctx, 5f)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            Ui.setTip(this, if (p.visible) "숨기기" else "보이기")
            setOnClickListener { renderer.setProps(info.id, p.copy(visible = !p.visible), record = true) }
        }, Ui.square(ctx, 30f).apply { rightMargin = Ui.dp(ctx, 4f) })
        if (p.clip && !info.orphanClip) {
            row.addView(Ui.text(ctx, "↳", 14f, 0xFFE0A030.toInt()).apply { setPadding(0, 0, Ui.dp(ctx, 4f), 0) })
        }
        if (folder) {
            row.addView(ImageView(ctx).apply {
                setImageResource(if (p.expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right)
                Ui.tint(this, Ui.SUBTEXT)
                val pad = Ui.dp(ctx, 4f)
                setPadding(pad, pad, pad, pad)
                isClickable = true
                Ui.setTip(this, if (p.expanded) "접기" else "펼치기")
                setOnClickListener { renderer.setProps(info.id, p.copy(expanded = !p.expanded), record = false) }
            }, Ui.square(ctx, 28f).apply { rightMargin = Ui.dp(ctx, 4f) })
        } else {
            val size = Ui.dp(ctx, 36f)
            val img = ImageView(ctx).apply {
                background = Ui.rounded(Color.WHITE, 0f, Ui.dp(ctx, 1f), Ui.BORDER)
                val pad = Ui.dp(ctx, 1f)
                setPadding(pad, pad, pad, pad)
                scaleType = ImageView.ScaleType.FIT_CENTER
                thumbs[info.id]?.let { setImageBitmap(it) }
            }
            thumbViews[info.id] = img
            val editingMask = info.id == activeId && renderer.maskEditing && p.mask
            if (p.mask) {
                // 레이어 썸네일을 누르면 레이어 편집, 마스크 썸네일을 누르면 마스크 편집
                img.setOnClickListener { renderer.selectNode(info.id, mask = false) }
                Ui.setTip(img, "레이어 편집")
                if (info.id == activeId && !editingMask) img.background = Ui.rounded(Color.WHITE, 0f, Ui.dp(ctx, 2f), Ui.BUTTON_ON)
            }
            row.addView(img, LinearLayout.LayoutParams(size, size).apply { rightMargin = Ui.dp(ctx, if (p.mask) 4f else 8f) })
            if (p.mask) {
                val m = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    thumbs[-info.id]?.let { setImageBitmap(it) }
                    val pad = Ui.dp(ctx, 2f)
                    setPadding(pad, pad, pad, pad)
                    background = Ui.rounded(Color.WHITE, 0f, Ui.dp(ctx, 2f), if (editingMask) Ui.BUTTON_ON else Ui.BORDER)
                    alpha = if (p.maskEnabled) 1f else 0.4f
                    isClickable = true
                    Ui.setTip(this, if (p.maskEnabled) "마스크 편집 (흰색 = 보임, 검정 = 가림)" else "마스크 편집 (마스크 꺼짐)")
                    setOnClickListener { renderer.selectNode(info.id, mask = true) }
                }
                thumbViews[-info.id] = m
                row.addView(m, LinearLayout.LayoutParams(size, size).apply { rightMargin = Ui.dp(ctx, 8f) })
            }
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(ctx, if (folder) "[폴더] ${p.name}" else p.name, 13f).apply { maxLines = 1 })
        val detail = buildString {
            append((p.opacity * 100).roundToInt()).append('%')
            if (p.blend != BlendMode.NORMAL) append(" · ").append(p.blend.label)
            if (p.alphaLock) append(" · 잠금")
            if (p.reference) append(" · 참조")
            if (p.text != null) append(" · 텍스트")
            if (p.vector) append(" · 벡터")
            if (p.borderWidth > 0f) append(" · 경계")
            if (p.toneCell > 0f) append(" · 톤")
            if (p.layerColorOn) append(" · 레이어 컬러")
            if (p.mask) append(if (p.maskEnabled) " · 마스크" else " · 마스크 꺼짐")
            if (p.clip && info.orphanClip) append(" · 클리핑(기준 없음)")
        }
        texts.addView(Ui.text(ctx, detail, 11f, Ui.SUBTEXT))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** 마스크가 없으면 추가, 있으면 편집 전환·켜기/끄기·적용·삭제 메뉴. */
    private fun maskMenu() {
        val info = active ?: return
        if (info.kind != NodeKind.RASTER) {
            android.widget.Toast.makeText(ctx, "폴더가 아닌 레이어를 선택하세요.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val p = info.props
        if (!p.mask) {
            renderer.addMask()
            return
        }
        val editing = renderer.maskEditing
        val items = arrayOf(
            if (editing) "레이어 편집으로" else "마스크 편집",
            if (p.maskEnabled) "마스크 끄기" else "마스크 켜기",
            "마스크 적용 (가린 부분을 실제로 지움)",
            "마스크 삭제",
        )
        Ui.dialog(ctx)
            .setTitle("레이어 마스크")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> renderer.selectNode(info.id, mask = !editing)
                    1 -> renderer.setProps(info.id, p.copy(maskEnabled = !p.maskEnabled), record = true)
                    2 -> renderer.applyMask()
                    3 -> renderer.deleteMask()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun rename(info: NodeInfo) {
        val input = EditText(ctx).apply {
            setText(info.props.name)
            setSelectAllOnFocus(true)
        }
        Ui.dialog(ctx)
            .setTitle(if (info.kind == NodeKind.FOLDER) "폴더 이름" else "레이어 이름")
            .setView(input)
            .setPositiveButton("확인") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) renderer.setProps(info.id, info.props.copy(name = name), record = true)
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
