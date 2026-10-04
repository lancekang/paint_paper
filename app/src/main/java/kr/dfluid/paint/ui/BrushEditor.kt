package kr.dfluid.paint.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import kr.dfluid.paint.brush.Brush
import kr.dfluid.paint.brush.BrushLibrary
import kr.dfluid.paint.brush.TipRotation
import java.io.InputStream
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 브러시(보조 도구) 설정 창. 사본을 고치다가 "저장"을 누르면 원본에 반영합니다.
 * pickImage: 팁 이미지를 고르게 하고 스트림을 돌려주는 함수 (MainActivity가 SAF로 처리)
 */
object BrushEditor {

    fun show(
        ctx: Context,
        original: Brush,
        library: BrushLibrary,
        pickImage: ((InputStream?) -> Unit) -> Unit,
        onSaved: () -> Unit,
    ) {
        val b = original.deepCopy()
        val pad = Ui.dp(ctx, 16f)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun section(title: String) {
            root.addView(Ui.text(ctx, title, 13f, 0xFF3D8BFF.toInt(), bold = true), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(ctx, 14f); bottomMargin = Ui.dp(ctx, 4f) })
        }
        /** 선형 슬라이더 (0..1000 → min..max) */
        fun param(label: String, min: Float, max: Float, get: () -> Float, set: (Float) -> Unit, fmt: (Float) -> String) {
            val row = Ui.SliderRow(ctx, label, 1000)
            row.value.layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 60f), ViewGroup.LayoutParams.WRAP_CONTENT)
            fun sync() {
                val v = get()
                row.set(((v - min) / (max - min) * 1000).roundToInt().coerceIn(0, 1000), fmt(v))
            }
            row.onChange = { p ->
                set(min + (max - min) * p / 1000f)
                row.value.text = fmt(get())
            }
            sync()
            root.addView(row.view)
        }
        val pct = { v: Float -> "${(v * 100).roundToInt()}%" }

        // ---- 이름 ----
        val name = EditText(ctx).apply {
            setText(b.name)
            maxLines = 1
            textSize = 15f
        }
        root.addView(name)

        // ---- 기본 ----
        section("기본")
        val sizeRow = Ui.SliderRow(ctx, "크기", 1000)
        sizeRow.value.layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 60f), ViewGroup.LayoutParams.WRAP_CONTENT)
        sizeRow.set((ln(b.size) / ln(Brush.MAX_SIZE) * 1000).roundToInt(), "${b.size.roundToInt()}px")
        sizeRow.onChange = { p ->
            b.size = Brush.MAX_SIZE.pow(p / 1000f).coerceIn(Brush.MIN_SIZE, Brush.MAX_SIZE)
            sizeRow.value.text = "${b.size.roundToInt()}px"
        }
        root.addView(sizeRow.view)
        param("불투명도", 0.01f, 1f, { b.opacity }, { b.opacity = it }, pct)
        param("흐름", 0.01f, 1f, { b.flow }, { b.flow = it }, pct)
        param("경도", 0f, 1f, { b.hardness }, { b.hardness = it }, pct)
        param("간격", 0.02f, 2f, { b.spacing }, { b.spacing = it }, pct)
        root.addView(Switch(ctx).apply {
            text = "겹칠수록 진하게 (에어브러시식)"
            setTextColor(Ui.TEXT)
            isChecked = b.buildUp
            setOnCheckedChangeListener { _, c -> b.buildUp = c }
        })

        // ---- 필압 ----
        section("필압")
        param("크기 영향", 0f, 1f, { b.pressureSize }, { b.pressureSize = it }, pct)
        param("최소 크기", 0f, 1f, { b.minSizeRatio }, { b.minSizeRatio = it }, pct)
        param("농도 영향", 0f, 1f, { b.pressureOpacity }, { b.pressureOpacity = it }, pct)
        root.addView(Ui.text(ctx, "필압 곡선 · 점을 끌어 옮기고, 빈 곳을 누르면 추가, 두 번 누르면 삭제", 11f, Ui.SUBTEXT))
        val curve = PressureCurveView(ctx).apply {
            points = b.curve
            onChanged = { pts ->
                b.curve = pts
                b.invalidateCurve()
            }
        }
        root.addView(curve, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(ctx, 4f)
        })
        val presets = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun curveBtn(label: String, pts: FloatArray) {
            presets.addView(Ui.button(ctx, label) {
                b.curve = pts
                b.invalidateCurve()
                curve.points = pts
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Ui.dp(ctx, 4f) })
        }
        curveBtn("선형", floatArrayOf(0f, 0f, 1f, 1f))
        curveBtn("부드럽게", floatArrayOf(0f, 0f, 0.5f, 0.25f, 1f, 1f))
        curveBtn("강하게", floatArrayOf(0f, 0f, 0.4f, 0.7f, 1f, 1f))
        root.addView(presets, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(ctx, 4f)
        })

        // ---- 기울기 ----
        section("펜 기울기")
        param("모양 영향", 0f, 1f, { b.tiltShape }, { b.tiltShape = it }, pct)
        param("크기 영향", 0f, 3f, { b.tiltSize }, { b.tiltSize = it }, pct)

        // ---- 질감 ----
        section("질감")
        param("그레인", 0f, 1f, { b.grain }, { b.grain = it }, pct)
        param("그레인 크기", 0.25f, 8f, { b.grainScale }, { b.grainScale = it }) { String.format("%.1fx", it) }

        // ---- 팁 ----
        section("브러시 끝 모양")
        val tipPreview = ImageView(ctx).apply {
            setBackgroundColor(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        fun refreshTip() {
            val id = b.tipId
            val bmp = if (id != null) library.tipPreview(id) else null
            if (bmp != null) {
                tipPreview.setImageBitmap(bmp)
                tipPreview.visibility = View.VISIBLE
            } else {
                tipPreview.visibility = View.GONE
            }
        }
        val invert = Switch(ctx).apply {
            text = "불러올 때 반전"
            setTextColor(Ui.TEXT)
        }
        val tipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tipPreview, LinearLayout.LayoutParams(Ui.dp(ctx, 56f), Ui.dp(ctx, 56f)).apply { rightMargin = Ui.dp(ctx, 8f) })
            addView(Ui.button(ctx, "이미지 불러오기") {
                pickImage { stream ->
                    if (stream != null) {
                        val id = stream.use { library.importTip(it, invert.isChecked) }
                        if (id != null) {
                            b.tipId = id
                            refreshTip()
                        }
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Ui.dp(ctx, 4f) })
            addView(Ui.button(ctx, "원형으로") {
                b.tipId = null
                refreshTip()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tipRow)
        root.addView(invert)
        refreshTip()
        val rotation = Spinner(ctx)
        rotation.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, TipRotation.entries.map { it.label }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        rotation.setSelection(b.tipRotation.ordinal, false)
        rotation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                b.tipRotation = TipRotation.entries[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(ctx, "회전", 12f, Ui.SUBTEXT), LinearLayout.LayoutParams(Ui.dp(ctx, 64f), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(rotation, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        param("각도", 0f, 360f, { b.angleDeg }, { b.angleDeg = it }) { "${it.roundToInt()}°" }
        param("각도 흔들림", 0f, 1f, { b.angleJitter }, { b.angleJitter = it }, pct)
        param("크기 흔들림", 0f, 1f, { b.sizeJitter }, { b.sizeJitter = it }, pct)

        val scroll = ScrollView(ctx).apply { addView(root) }
        AlertDialog.Builder(ctx)
            .setTitle("브러시 설정 · ${original.tool.label}")
            .setView(scroll)
            .setPositiveButton("저장") { _, _ ->
                val n = name.text.toString().trim()
                original.name = if (n.isEmpty()) original.name else n
                original.size = b.size
                original.opacity = b.opacity
                original.flow = b.flow
                original.hardness = b.hardness
                original.spacing = b.spacing
                original.buildUp = b.buildUp
                original.pressureSize = b.pressureSize
                original.minSizeRatio = b.minSizeRatio
                original.pressureOpacity = b.pressureOpacity
                original.curve = b.curve.copyOf()
                original.invalidateCurve()
                original.tiltShape = b.tiltShape
                original.tiltSize = b.tiltSize
                original.grain = b.grain
                original.grainScale = b.grainScale
                original.tipId = b.tipId
                original.tipRotation = b.tipRotation
                original.angleDeg = b.angleDeg
                original.angleJitter = b.angleJitter
                original.sizeJitter = b.sizeJitter
                library.save()
                onSaved()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
