package kr.dfluid.paint.brush

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Tool(val label: String, val short: String, val isBrush: Boolean) {
    PEN("펜", "펜", true),
    PENCIL("연필", "연필", true),
    AIRBRUSH("에어브러시", "에어", true),
    MARKER("마커", "마커", true),
    ERASER("지우개", "지우개", true),
    BLEND("색 혼합", "혼합", true),
    SELECT("선택", "선택", false),
    MOVE("이동", "이동", false),
    FILL("채우기", "채우기", false),
    GRADIENT("그라데이션", "그라데", false),
    EYEDROPPER("스포이드", "스포이드", false),
    HAND("손바닥", "손", false),
}

enum class TipRotation(val label: String) {
    FIXED("고정"),
    DIRECTION("진행 방향"),
    RANDOM("무작위"),
}

/**
 * 브러시 프리셋 (= 클립 스튜디오의 "보조 도구").
 * 도구마다 여러 개를 가질 수 있고, 크기·불투명도도 프리셋별로 기억됩니다.
 *
 * @property size 지름(px)
 * @property opacity 스트로크 전체 불투명도 (커밋 시 적용)
 * @property flow 스탬프 하나의 알파
 * @property spacing 스탬프 간격 (지름 대비 비율)
 * @property pressureSize 필압이 크기에 주는 영향 0..1
 * @property pressureOpacity 필압이 스탬프 알파에 주는 영향 0..1
 * @property minSizeRatio 필압 0일 때의 크기 비율
 * @property grain 그레인(종이 질감) 강도, grainScale 그 크기
 * @property tiltShape 기울이면 스탬프가 납작해지는 정도, tiltSize 커지는 정도
 * @property buildUp true면 한 획 안에서 겹칠수록 진해짐(에어브러시), false면 최댓값 유지(펜)
 * @property tipId 사용자 팁 이미지 id (null = 원형)
 * @property curve 필압 곡선 제어점 [x0,y0,x1,y1,...] (0..1, x 오름차순)
 * @property taperIn 입: 시작부터 이 길이(캔버스 px) 동안 가늘게 시작 (0 = 끔)
 * @property taperOut 출: 끝에서 이 길이 동안 가늘게 끝남 (0 = 끔)
 * @property mixMode 색 혼합 도구만: MIX_SMUDGE(손끝, 지나온 색을 끌고 감) / MIX_BLUR(흐리기)
 */
data class Brush(
    val id: String,
    val tool: Tool,
    var name: String,
    var size: Float,
    var opacity: Float,
    var flow: Float,
    var hardness: Float,
    var spacing: Float,
    var pressureSize: Float,
    var pressureOpacity: Float,
    var minSizeRatio: Float,
    var grain: Float = 0f,
    var grainScale: Float = 1f,
    var tiltShape: Float = 0f,
    var tiltSize: Float = 0f,
    var buildUp: Boolean = false,
    var tipId: String? = null,
    var tipRotation: TipRotation = TipRotation.FIXED,
    var angleDeg: Float = 0f,
    var angleJitter: Float = 0f,
    var sizeJitter: Float = 0f,
    var curve: FloatArray = floatArrayOf(0f, 0f, 1f, 1f),
    var taperIn: Float = 0f,
    var taperOut: Float = 0f,
    var mixMode: Int = MIX_SMUDGE,
) {
    val isEraser: Boolean get() = tool == Tool.ERASER
    /** 색을 칠하지 않고 레이어의 색을 섞는 도구 */
    val isBlend: Boolean get() = tool == Tool.BLEND

    /** 필압 곡선 룩업 테이블 (256). 곡선을 바꾸면 invalidateCurve() */
    @Transient
    private var lut: FloatArray? = null

    fun invalidateCurve() {
        lut = null
    }

    fun mapPressure(p: Float): Float {
        val t = lut ?: PressureCurve.lut(curve).also { lut = it }
        val f = p.coerceIn(0f, 1f) * 255f
        val i = f.toInt().coerceAtMost(254)
        val r = f - i
        return t[i] + (t[i + 1] - t[i]) * r
    }

    fun deepCopy(): Brush = copy(curve = curve.copyOf())

    fun duplicate(newName: String): Brush = deepCopy().copy(id = UUID.randomUUID().toString(), name = newName)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("tool", tool.name)
        .put("name", name)
        .put("size", size.toDouble())
        .put("opacity", opacity.toDouble())
        .put("flow", flow.toDouble())
        .put("hardness", hardness.toDouble())
        .put("spacing", spacing.toDouble())
        .put("pressureSize", pressureSize.toDouble())
        .put("pressureOpacity", pressureOpacity.toDouble())
        .put("minSizeRatio", minSizeRatio.toDouble())
        .put("grain", grain.toDouble())
        .put("grainScale", grainScale.toDouble())
        .put("tiltShape", tiltShape.toDouble())
        .put("tiltSize", tiltSize.toDouble())
        .put("buildUp", buildUp)
        .put("tipId", tipId ?: JSONObject.NULL)
        .put("tipRotation", tipRotation.name)
        .put("angleDeg", angleDeg.toDouble())
        .put("angleJitter", angleJitter.toDouble())
        .put("sizeJitter", sizeJitter.toDouble())
        .put("curve", JSONArray(curve.map { it.toDouble() }))
        .put("taperIn", taperIn.toDouble())
        .put("taperOut", taperOut.toDouble())
        .put("mixMode", mixMode)

    override fun equals(other: Any?): Boolean = other is Brush && other.toJson().toString() == toJson().toString()
    override fun hashCode(): Int = id.hashCode()

    companion object {
        const val MIN_SIZE = 1f
        const val MAX_SIZE = 1000f
        /** 입·출 최대 길이 (캔버스 px) */
        const val TAPER_MAX = 400f
        const val MIX_SMUDGE = 0
        const val MIX_BLUR = 1

        fun fromJson(o: JSONObject): Brush? {
            val tool = Tool.entries.firstOrNull { it.name == o.optString("tool") } ?: return null
            val base = BrushPresets.create(tool)
            fun f(k: String, d: Float) = o.optDouble(k, d.toDouble()).toFloat()
            val curveArr = o.optJSONArray("curve")
            val curve = if (curveArr != null && curveArr.length() >= 4 && curveArr.length() % 2 == 0) {
                FloatArray(curveArr.length()) { curveArr.optDouble(it, 0.0).toFloat().coerceIn(0f, 1f) }
            } else base.curve
            return base.copy(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", base.name),
                size = f("size", base.size).coerceIn(MIN_SIZE, MAX_SIZE),
                opacity = f("opacity", base.opacity).coerceIn(0.01f, 1f),
                flow = f("flow", base.flow).coerceIn(0.01f, 1f),
                hardness = f("hardness", base.hardness).coerceIn(0f, 1f),
                spacing = f("spacing", base.spacing).coerceIn(0.02f, 2f),
                pressureSize = f("pressureSize", base.pressureSize).coerceIn(0f, 1f),
                pressureOpacity = f("pressureOpacity", base.pressureOpacity).coerceIn(0f, 1f),
                minSizeRatio = f("minSizeRatio", base.minSizeRatio).coerceIn(0f, 1f),
                grain = f("grain", base.grain).coerceIn(0f, 1f),
                grainScale = f("grainScale", base.grainScale).coerceIn(0.25f, 8f),
                tiltShape = f("tiltShape", base.tiltShape).coerceIn(0f, 1f),
                tiltSize = f("tiltSize", base.tiltSize).coerceIn(0f, 3f),
                buildUp = o.optBoolean("buildUp", base.buildUp),
                tipId = if (o.isNull("tipId")) null else o.optString("tipId"),
                tipRotation = TipRotation.entries.firstOrNull { it.name == o.optString("tipRotation") } ?: TipRotation.FIXED,
                angleDeg = f("angleDeg", 0f),
                angleJitter = f("angleJitter", 0f).coerceIn(0f, 1f),
                sizeJitter = f("sizeJitter", 0f).coerceIn(0f, 1f),
                curve = curve,
                taperIn = f("taperIn", 0f).coerceIn(0f, TAPER_MAX),
                taperOut = f("taperOut", 0f).coerceIn(0f, TAPER_MAX),
                mixMode = o.optInt("mixMode", base.mixMode).coerceIn(MIX_SMUDGE, MIX_BLUR),
            )
        }
    }
}

object BrushPresets {
    private fun id() = UUID.randomUUID().toString()

    fun create(tool: Tool): Brush = when (tool) {
        Tool.PEN -> Brush(
            id(), tool, "G펜", size = 12f, opacity = 1f, flow = 1f, hardness = 0.85f, spacing = 0.08f,
            pressureSize = 1f, pressureOpacity = 0f, minSizeRatio = 0.12f,
        )
        Tool.PENCIL -> Brush(
            id(), tool, "진한 연필", size = 6f, opacity = 1f, flow = 1f, hardness = 0.55f, spacing = 0.1f,
            pressureSize = 0.4f, pressureOpacity = 1f, minSizeRatio = 0.3f, grain = 0.55f,
            tiltShape = 0.6f, tiltSize = 1.5f,
        )
        Tool.AIRBRUSH -> Brush(
            id(), tool, "부드러움", size = 120f, opacity = 1f, flow = 0.06f, hardness = 0f, spacing = 0.08f,
            pressureSize = 0f, pressureOpacity = 1f, minSizeRatio = 1f, buildUp = true,
        )
        Tool.MARKER -> Brush(
            id(), tool, "마커", size = 30f, opacity = 0.5f, flow = 1f, hardness = 0.95f, spacing = 0.06f,
            pressureSize = 0.3f, pressureOpacity = 0f, minSizeRatio = 0.5f, tiltShape = 0.5f,
        )
        Tool.ERASER -> Brush(
            id(), tool, "딱딱함", size = 40f, opacity = 1f, flow = 1f, hardness = 0.8f, spacing = 0.08f,
            pressureSize = 0.7f, pressureOpacity = 0f, minSizeRatio = 0.3f,
        )
        Tool.BLEND -> Brush(
            id(), tool, "손끝", size = 40f, opacity = 0.8f, flow = 1f, hardness = 0.3f, spacing = 0.12f,
            pressureSize = 0.5f, pressureOpacity = 1f, minSizeRatio = 0.5f, mixMode = Brush.MIX_SMUDGE,
        )
        else -> Brush(
            id(), tool, tool.label, size = 1f, opacity = 1f, flow = 1f, hardness = 1f, spacing = 0.1f,
            pressureSize = 0f, pressureOpacity = 0f, minSizeRatio = 1f,
        )
    }

    /** 처음 설치했을 때 도구별로 넣어 줄 프리셋 묶음. */
    fun defaults(tool: Tool): List<Brush> = when (tool) {
        Tool.PEN -> listOf(
            create(tool),
            create(tool).copy(id = id(), name = "둥근 펜", hardness = 0.95f, minSizeRatio = 0.35f),
            create(tool).copy(id = id(), name = "마커 펜(일정)", pressureSize = 0f, hardness = 0.9f),
        )
        Tool.PENCIL -> listOf(
            create(tool),
            create(tool).copy(id = id(), name = "샤프", size = 3f, grain = 0.35f, hardness = 0.75f, tiltSize = 0.5f),
        )
        Tool.AIRBRUSH -> listOf(
            create(tool),
            create(tool).copy(id = id(), name = "강하게", flow = 0.15f, hardness = 0.3f),
        )
        Tool.MARKER -> listOf(create(tool))
        Tool.BLEND -> listOf(
            create(tool),
            create(tool).copy(id = id(), name = "색 늘이기", opacity = 1f, hardness = 0.6f, pressureOpacity = 0.4f),
            create(tool).copy(id = id(), name = "흐리기", size = 60f, opacity = 0.6f, hardness = 0f, mixMode = Brush.MIX_BLUR),
        )
        Tool.ERASER -> listOf(
            create(tool),
            create(tool).copy(id = id(), name = "부드러움", hardness = 0f, size = 120f, pressureSize = 0f, pressureOpacity = 1f, flow = 0.3f, buildUp = true),
        )
        else -> emptyList()
    }
}
