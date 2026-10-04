package kr.dfluid.paint.engine

import kotlin.math.ceil

/** 필터 값 하나: 슬라이더 범위(정수)와 셰이더에 넘길 배율. */
class FilterParam(val label: String, val min: Int, val max: Int, val default: Int, val suffix: String = "", val scale: Float = 1f)

/**
 * 필터·색조 보정 종류. [shaderId]는 Shaders.FILTER_FS의 u_kind.
 * 흐리기 계열([blurs])은 가로·세로 두 번 그리고, 선명하게는 합치는 단계에서 원본 − 흐림을 더합니다.
 */
enum class FilterKind(val label: String, val shaderId: Int, val params: List<FilterParam>, val forMask: Boolean = false) {
    HSL("색조 · 채도 · 명도", 1, listOf(
        FilterParam("색조", -180, 180, 0, "°"),
        FilterParam("채도", -100, 100, 0, "", 0.01f),
        FilterParam("명도", -100, 100, 0, "", 0.01f),
    )),
    BRIGHT_CONTRAST("밝기 · 대비", 2, listOf(
        FilterParam("밝기", -100, 100, 0, "", 0.01f),
        FilterParam("대비", -100, 100, 0, "", 0.01f),
    )),
    INVERT("색 반전", 3, emptyList()),
    /** 밝기 → 주색(어두움)~보조색(밝음). 두 색은 FilterSpec.values 뒤에 rgb rgb로 붙입니다. */
    GRADIENT_MAP("그라데이션 맵 (주색 → 보조색)", 6, listOf(FilterParam("강도", 0, 100, 100, "%", 0.01f))),
    POSTERIZE("포스터화", 5, listOf(FilterParam("단계", 2, 32, 4))),
    BLUR("가우시안 흐리기", 4, listOf(FilterParam("범위", 1, 64, 4, "px")), forMask = true),
    SHARPEN("선명하게 (언샤프 마스크)", 4, listOf(
        FilterParam("범위", 1, 20, 2, "px"),
        FilterParam("강도", 0, 500, 100, "%", 0.01f),
    ));

    val blurs: Boolean get() = this == BLUR || this == SHARPEN

    /** 흐리기가 읽는 최대 거리. 원본을 잘라 올 때 이만큼 둘레를 더 가져옵니다. */
    val pad: Int get() = if (blurs) taps(params[0].max.toFloat()) + 2 else 0

    fun defaultSpec() = FilterSpec(this, params.map { it.default * it.scale })

    companion object {
        /** 흐리기 반지름(px) → 한쪽 탭 수 (≈ 3σ, σ = 반지름 / 2) */
        fun taps(radius: Float): Int = ceil(radius * 1.5f).toInt()
    }
}

/** 필터 종류 + 값 (params 순서, 배율 적용 후). */
data class FilterSpec(val kind: FilterKind, val values: List<Float>) {
    operator fun get(i: Int): Float = values.getOrElse(i) { 0f }
}
