package kr.dfluid.paint.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import kotlin.math.roundToInt

object Dialogs {

    fun colorPicker(ctx: Context, initial: Int, onPick: (Int) -> Unit) {
        val pad = Ui.dp(ctx, 20f)
        val picker = ColorPickerView(ctx).apply { color = initial }
        val preview = View(ctx).apply { setBackgroundColor(initial) }
        val hex = EditText(ctx).apply {
            setText(String.format("%06X", initial and 0xFFFFFF))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            maxLines = 1
            textSize = 15f
        }
        var syncing = false
        picker.onColorChanged = { c ->
            preview.setBackgroundColor(c)
            syncing = true
            hex.setText(String.format("%06X", c and 0xFFFFFF))
            syncing = false
        }
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val t = s?.toString()?.trim()?.removePrefix("#") ?: return
                if (t.length == 6) {
                    t.toIntOrNull(16)?.let {
                        val c = Color.rgb(it shr 16 and 0xFF, it shr 8 and 0xFF, it and 0xFF)
                        picker.color = c
                        preview.setBackgroundColor(c)
                    }
                }
            }
        })
        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(preview, LinearLayout.LayoutParams(Ui.dp(ctx, 48f), Ui.dp(ctx, 32f)))
            addView(Ui.text(ctx, "  #", 15f), Ui.wrap())
            addView(hex, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(picker, LinearLayout.LayoutParams(Ui.dp(ctx, 300f), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 12f)
            })
        }
        Ui.dialog(ctx)
            .setTitle("색 선택")
            .setView(root)
            .setPositiveButton("확인") { _, _ -> onPick(picker.color) }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 새 캔버스. 배경 선택(흰색/투명/색 지정)은 [s]에 기억합니다. */
    fun newCanvas(ctx: Context, maxSize: Int, s: AppSettings, onCreate: (Int, Int, Int?) -> Unit) {
        val pad = Ui.dp(ctx, 20f)
        fun numberField(v: Int) = EditText(ctx).apply {
            setText(v.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            maxLines = 1
        }
        val w = numberField(2048)
        val h = numberField(2048)
        val presets = listOf(
            "정사각 2048" to (2048 to 2048),
            "FHD 1920×1080" to (1920 to 1080),
            "A4 150dpi" to (1240 to 1754),
            "A4 300dpi" to (2480 to 3508),
            "4K 3840×2160" to (3840 to 2160),
        )
        val presetRow = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        presets.chunked(3).forEach { chunk ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { (label, size) ->
                val b = Ui.button(ctx, label) {
                    w.setText(size.first.toString())
                    h.setText(size.second.toString())
                }
                row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = Ui.dp(ctx, 6f); bottomMargin = Ui.dp(ctx, 6f)
                })
            }
            presetRow.addView(row)
        }
        val sizeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(ctx, "가로", 14f), Ui.wrap())
            addView(w, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(ctx, "  세로", 14f), Ui.wrap())
            addView(h, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(ctx, " px", 14f), Ui.wrap())
        }
        // ---- 배경 ----
        // 0 = 흰색, 1 = 투명, 2 = 색 지정
        var bgChoice = when {
            s.canvasTransparent -> 1
            s.canvasBackground == Color.WHITE -> 0
            else -> 2
        }
        var customColor = if (bgChoice == 2) s.canvasBackground else Color.rgb(0xF4, 0xEE, 0xE0)
        val swatch = View(ctx)
        val bgButtons = ArrayList<View>()
        fun refreshBg() {
            bgButtons.forEachIndexed { i, b -> Ui.setOn(b, i == bgChoice) }
            swatch.background = Ui.rounded(customColor, Ui.dp(ctx, 4f).toFloat(), Ui.dp(ctx, 1f), Color.GRAY)
        }
        val bgRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(ctx, "배경", 14f), LinearLayout.LayoutParams(Ui.dp(ctx, 44f), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        listOf("흰색", "투명", "색 지정…").forEachIndexed { i, label ->
            val b = Ui.button(ctx, label) {
                if (i == 2) {
                    colorPicker(ctx, customColor) { c ->
                        customColor = c
                        bgChoice = 2
                        refreshBg()
                    }
                } else {
                    bgChoice = i
                    refreshBg()
                }
            }
            bgButtons.add(b)
            bgRow.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = Ui.dp(ctx, 6f)
            })
        }
        bgRow.addView(swatch, LinearLayout.LayoutParams(Ui.dp(ctx, 28f), Ui.dp(ctx, 28f)))
        refreshBg()
        val bgNote = Ui.text(ctx, "배경을 고르면 맨 아래에 색으로 채운 \"배경\" 레이어가 생깁니다. 투명을 고르면 배경 없이 시작합니다.", 12f, Ui.SUBTEXT)
        val note = Ui.text(ctx, "이 기기의 최대 크기: $maxSize px · 레이어 1장당 가로×세로×4바이트의 GPU 메모리를 씁니다.", 12f, Ui.SUBTEXT)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(presetRow)
            addView(sizeRow)
            addView(bgRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 12f)
            })
            addView(bgNote, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 4f); bottomMargin = Ui.dp(ctx, 8f)
            })
            addView(note)
        }
        Ui.dialog(ctx)
            .setTitle("새 캔버스")
            .setView(root)
            .setPositiveButton("만들기") { _, _ ->
                val ww = w.text.toString().toIntOrNull() ?: 0
                val hh = h.text.toString().toIntOrNull() ?: 0
                if (ww in 16..maxSize && hh in 16..maxSize) {
                    s.canvasTransparent = bgChoice == 1
                    if (bgChoice != 1) s.canvasBackground = if (bgChoice == 0) Color.WHITE else customColor
                    s.save()
                    onCreate(ww, hh, s.newCanvasBackground)
                } else Toast.makeText(ctx, "크기는 16 ~ $maxSize px 사이여야 합니다.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    fun settings(ctx: Context, s: AppSettings, onChanged: () -> Unit, onOpenShortcuts: () -> Unit, onThemeChanged: () -> Unit, onPanelsChanged: () -> Unit) {
        val pad = Ui.dp(ctx, 20f)
        // 감마 0.3 ~ 3.0 을 로그 스케일 슬라이더로
        val gamma = Ui.SliderRow(ctx, "필압 곡선", 100)
        fun gammaToP(g: Float) = ((Math.log(g.toDouble()) - Math.log(0.3)) / (Math.log(3.0) - Math.log(0.3)) * 100).roundToInt()
        fun pToGamma(p: Int) = Math.exp(Math.log(0.3) + p / 100.0 * (Math.log(3.0) - Math.log(0.3))).toFloat()
        fun gammaLabel(g: Float) = when {
            g < 0.9f -> String.format("%.2f 가볍게", g)
            g > 1.1f -> String.format("%.2f 무겁게", g)
            else -> String.format("%.2f", g)
        }
        gamma.set(gammaToP(s.pressureGamma), gammaLabel(s.pressureGamma))
        gamma.value.layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 88f), ViewGroup.LayoutParams.WRAP_CONTENT)
        gamma.onChange = { p ->
            s.pressureGamma = pToGamma(p)
            gamma.value.text = gammaLabel(s.pressureGamma)
            onChanged()
        }
        val finger = Switch(ctx).apply {
            text = "손가락으로 그리기 (끄면 스타일러스 사용 시 손가락은 이동·확대만)"
            isChecked = s.drawWithFinger
            setTextColor(Ui.TEXT)
            setOnCheckedChangeListener { _, checked ->
                s.drawWithFinger = checked
                onChanged()
            }
        }
        val timelapse = Switch(ctx).apply {
            text = "타임랩스 기록 (그리는 과정을 저장해 내보내기 → 타임랩스 MP4)"
            isChecked = s.timelapse
            setTextColor(Ui.TEXT)
            setOnCheckedChangeListener { _, checked ->
                s.timelapse = checked
                onChanged()
            }
        }
        val hint = Ui.text(
            ctx,
            "제스처: 두 손가락 탭 = 실행취소 · 세 손가락 탭 = 다시실행 · 펜 옆 버튼 = 스포이드 · 펜 뒤쪽(지우개) = 지우개",
            12f, Ui.SUBTEXT
        )
        // 화면 테마
        val themeButtons = ArrayList<View>()
        val themeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(ctx, "화면 테마", 12f, Ui.SUBTEXT), LinearLayout.LayoutParams(Ui.dp(ctx, 64f), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        listOf("시스템 따라가기", "라이트", "다크").forEachIndexed { i, label ->
            val b = Ui.button(ctx, label) {
                if (s.themeMode != i) {
                    s.themeMode = i
                    s.save()
                    themeButtons.forEachIndexed { j, v -> Ui.setOn(v, j == i) }
                    onThemeChanged()
                }
            }
            Ui.setOn(b, s.themeMode == i)
            themeButtons.add(b)
            themeRow.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = Ui.dp(ctx, 6f)
            })
        }
        // 패널 배치
        val panelRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(ctx, "패널", 12f, Ui.SUBTEXT), LinearLayout.LayoutParams(Ui.dp(ctx, 64f), ViewGroup.LayoutParams.WRAP_CONTENT))
            val lock = Ui.button(ctx, "") {}
            fun label() {
                lock.text = if (s.panelLock) "위치 잠금 켜짐" else "위치 잠금 꺼짐"
                Ui.setOn(lock, s.panelLock)
            }
            label()
            lock.setOnClickListener {
                s.panelLock = !s.panelLock
                s.save()
                label()
                onPanelsChanged()
            }
            addView(lock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = Ui.dp(ctx, 6f)
            })
            addView(Ui.button(ctx, "위치 초기화") {
                s.resetPanels()
                s.save()
                onPanelsChanged()
            }, Ui.wrap())
        }
        val panelHint = Ui.text(ctx, "패널의 손잡이(⠿)를 끌어 옮길 수 있고, 화면 가장자리·가운데 근처에서 붙습니다.", 11f, Ui.SUBTEXT)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(themeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(ctx, 8f)
            })
            addView(panelRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(panelHint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 2f); bottomMargin = Ui.dp(ctx, 12f)
            })
            addView(gamma.view)
            addView(finger, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 12f)
            })
            addView(timelapse, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 8f)
            })
            addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 12f)
            })
        }
        Ui.dialog(ctx)
            .setTitle("설정")
            .setView(root)
            .setPositiveButton("닫기", null)
            .setNeutralButton("단축키 설정…") { _, _ -> onOpenShortcuts() }
            .show()
    }

    fun confirm(ctx: Context, message: String, ok: String, onOk: () -> Unit) {
        Ui.dialog(ctx)
            .setMessage(message)
            .setPositiveButton(ok) { _, _ -> onOk() }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 숫자 입력 칸 두 개(가로·세로) 한 줄. */
    private fun sizeFields(ctx: Context, w0: Int, h0: Int): Triple<LinearLayout, EditText, EditText> {
        fun field(v: Int) = EditText(ctx).apply {
            setText(v.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            setTextColor(Ui.TEXT)
            setSelectAllOnFocus(true)
        }
        val w = field(w0)
        val h = field(h0)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(ctx, "가로", 14f), Ui.wrap())
            addView(w, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(ctx, "  세로", 14f), Ui.wrap())
            addView(h, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.text(ctx, " px", 14f), Ui.wrap())
        }
        return Triple(row, w, h)
    }

    /** 고친 쪽이 아닌 칸을 비율에 맞춰 따라 바꿉니다 (keep()가 true일 때). */
    private fun linkRatio(w: EditText, h: EditText, ratio: Float, keep: () -> Boolean) {
        var syncing = false
        fun watch(src: EditText, dst: EditText, f: Float) = src.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(e: Editable?) {
                if (syncing || !keep() || !src.hasFocus()) return
                val v = e?.toString()?.toIntOrNull() ?: return
                syncing = true
                dst.setText(maxOf(1, (v * f).roundToInt()).toString())
                syncing = false
            }
        })
        watch(w, h, 1f / ratio)
        watch(h, w, ratio)
    }

    /** 이미지 크기 변경 (그림을 늘이거나 줄임). */
    fun imageSize(ctx: Context, curW: Int, curH: Int, maxSize: Int, onOk: (Int, Int) -> Unit) {
        val pad = Ui.dp(ctx, 20f)
        val (row, w, h) = sizeFields(ctx, curW, curH)
        val keep = Switch(ctx).apply {
            text = "가로세로 비율 유지"
            isChecked = true
            setTextColor(Ui.TEXT)
        }
        linkRatio(w, h, curW.toFloat() / curH) { keep.isChecked }
        val percents = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(25, 50, 200).forEach { pct ->
            percents.addView(Ui.button(ctx, "$pct%") {
                w.setText(maxOf(1, curW * pct / 100).toString())
                h.setText(maxOf(1, curH * pct / 100).toString())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = Ui.dp(ctx, 6f)
            })
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(Ui.text(ctx, "지금 ${curW}×$curH px", 12f, Ui.SUBTEXT))
            addView(row)
            addView(keep)
            addView(percents, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(ctx, 6f)
            })
            addView(Ui.text(ctx, "모든 레이어를 새 크기로 늘이거나 줄입니다. 실행취소할 수 있습니다.", 12f, Ui.SUBTEXT).apply {
                setPadding(0, Ui.dp(ctx, 8f), 0, 0)
            })
        }
        Ui.dialog(ctx)
            .setTitle("이미지 크기 변경")
            .setView(root)
            .setPositiveButton("확인") { _, _ ->
                val ww = w.text.toString().toIntOrNull() ?: 0
                val hh = h.text.toString().toIntOrNull() ?: 0
                if (ww in 1..maxSize && hh in 1..maxSize) onOk(ww, hh)
                else Toast.makeText(ctx, "1~$maxSize px 사이로 입력하세요.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 캔버스 크기 변경 (그림은 그대로, 기준 위치를 정해 둘레를 넓히거나 자름). */
    fun canvasSize(ctx: Context, curW: Int, curH: Int, maxSize: Int, onOk: (Int, Int, Float, Float) -> Unit) {
        val pad = Ui.dp(ctx, 20f)
        val (row, w, h) = sizeFields(ctx, curW, curH)
        var ax = 0.5f
        var ay = 0.5f
        val anchors = ArrayList<Pair<View, Pair<Float, Float>>>()
        fun refresh() = anchors.forEach { (v, a) -> Ui.setOn(v, a.first == ax && a.second == ay) }
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (yy in 0..2) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (xx in 0..2) {
                val a = xx / 2f to yy / 2f
                val b = Ui.button(ctx, "●") {
                    ax = a.first; ay = a.second
                    refresh()
                }
                anchors.add(b to a)
                r.addView(b, LinearLayout.LayoutParams(Ui.dp(ctx, 40f), Ui.dp(ctx, 36f)).apply {
                    rightMargin = Ui.dp(ctx, 3f); bottomMargin = Ui.dp(ctx, 3f)
                })
            }
            grid.addView(r)
        }
        refresh()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(Ui.text(ctx, "지금 ${curW}×$curH px", 12f, Ui.SUBTEXT))
            addView(row)
            addView(Ui.text(ctx, "기준 위치", 13f).apply { setPadding(0, Ui.dp(ctx, 8f), 0, Ui.dp(ctx, 4f)) })
            addView(grid)
            addView(Ui.text(ctx, "그림 크기는 그대로 두고 기준 위치를 중심으로 둘레를 넓히거나 자릅니다. 넓힌 곳은 투명합니다.", 12f, Ui.SUBTEXT).apply {
                setPadding(0, Ui.dp(ctx, 8f), 0, 0)
            })
        }
        Ui.dialog(ctx)
            .setTitle("캔버스 크기 변경")
            .setView(root)
            .setPositiveButton("확인") { _, _ ->
                val ww = w.text.toString().toIntOrNull() ?: 0
                val hh = h.text.toString().toIntOrNull() ?: 0
                if (ww in 1..maxSize && hh in 1..maxSize) onOk(ww, hh, ax, ay)
                else Toast.makeText(ctx, "1~$maxSize px 사이로 입력하세요.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 텍스트 넣기·고치기. 크기·글꼴·세로쓰기는 다음 새 텍스트의 기본값으로도 기억합니다. 색은 주색(고칠 때는 원래 색 유지). */
    fun textEditor(ctx: Context, init: kr.dfluid.paint.document.TextSpec, s: AppSettings, onOk: (kr.dfluid.paint.document.TextSpec) -> Unit) {
        val pad = Ui.dp(ctx, 20f)
        val editing = init.text.isNotEmpty()
        var size = init.size
        var font = init.font
        var vertical = init.vertical
        var color = init.color
        val input = EditText(ctx).apply {
            setText(init.text)
            setTextColor(Ui.TEXT)
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            hint = "글을 입력하세요 (줄바꿈 가능)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val sizeRow = Ui.SliderRow(ctx, "크기", 1000)
        fun sizeToP(v: Float) = (kotlin.math.ln(v / 4f) / kotlin.math.ln(250f) * 1000).roundToInt().coerceIn(0, 1000)
        fun pToSize(p: Int) = (4f * Math.pow(250.0, p / 1000.0).toFloat()).roundToInt().toFloat()
        sizeRow.set(sizeToP(size), "${size.roundToInt()}px")
        sizeRow.onChange = { p -> size = pToSize(p); sizeRow.value.text = "${size.roundToInt()}px" }
        fun toggle(labels: List<String>, sel: Int, onPick: (Int) -> Unit): LinearLayout {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val bs = ArrayList<View>()
            labels.forEachIndexed { i, l ->
                val b = Ui.button(ctx, l) {
                    onPick(i)
                    bs.forEachIndexed { j, v -> Ui.setOn(v, j == i) }
                }
                Ui.setOn(b, i == sel)
                bs.add(b)
                row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Ui.dp(ctx, 4f) })
            }
            return row
        }
        val swatch = View(ctx)
        fun refreshSwatch() {
            swatch.background = Ui.rounded(color, Ui.dp(ctx, 4f).toFloat(), Ui.dp(ctx, 1f), Color.GRAY)
        }
        refreshSwatch()
        val colorRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(ctx, "색", 12f, Ui.SUBTEXT), LinearLayout.LayoutParams(Ui.dp(ctx, 64f), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(swatch, LinearLayout.LayoutParams(Ui.dp(ctx, 32f), Ui.dp(ctx, 26f)))
            addView(Ui.hspace(ctx, 8f))
            addView(Ui.button(ctx, "바꾸기…") { colorPicker(ctx, color) { c -> color = c; refreshSwatch() } }, Ui.wrap())
            if (editing) {
                addView(Ui.hspace(ctx, 6f))
                addView(Ui.button(ctx, "주색으로") { color = s.primaryColor; refreshSwatch() }, Ui.wrap())
            }
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            minimumWidth = Ui.dp(ctx, 420f)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(sizeRow.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8f) })
            addView(toggle(kr.dfluid.paint.document.TextSpec.FONT_LABELS, font) { font = it }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 6f) })
            addView(toggle(listOf("가로쓰기", "세로쓰기"), if (vertical) 1 else 0) { vertical = it == 1 }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 4f) })
            addView(colorRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 6f) })
        }
        val dlg = Ui.dialog(ctx)
            .setTitle(if (editing) "텍스트 고치기" else "텍스트 넣기")
            .setView(root)
            .setPositiveButton("확인") { _, _ ->
                val text = input.text.toString().trimEnd()
                if (text.isBlank()) return@setPositiveButton
                s.textSize = size; s.textFont = font; s.textVertical = vertical
                s.save()
                onOk(init.copy(text = text, size = size, font = font, vertical = vertical, color = color))
            }
            .setNegativeButton("취소", null)
            .create()
        dlg.setOnShowListener {
            input.requestFocus()
            dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dlg.show()
    }
}
