package kr.dfluid.paint.input

import kr.dfluid.paint.brush.StrokeBuilder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 대칭 그리기 모드. */
enum class SymMode(val label: String) {
    OFF("끄기"),
    VERTICAL("좌우"),
    HORIZONTAL("상하"),
    QUAD("사방"),
    RADIAL("방사");

    fun next(): SymMode = entries[(ordinal + 1) % entries.size]
}

/**
 * 대칭 그리기. 캔버스 중심을 축으로 스탬프를 미러링(반사)하거나 회전 복제합니다.
 * 스탬프는 [StrokeBuilder] 형식(x, y, 반지름, 회전각, 단축비, 알파). UI 스레드 전용.
 */
class Symmetry {
    var mode = SymMode.OFF
    var radialCount = 6

    val on: Boolean get() = mode != SymMode.OFF

    /**
     * 원본 스탬프 배열을 받아 "추가로 그려야 할" 미러/회전 복제본들을 돌려줍니다.
     * 원본은 포함하지 않습니다(호출부가 이미 그렸음).
     * (cx, cy) = 대칭 중심(캔버스 좌표).
     */
    fun mirror(stamps: FloatArray, cx: Float, cy: Float): List<FloatArray> {
        if (!on || stamps.isEmpty()) return emptyList()
        val f = StrokeBuilder.FLOATS
        val n = stamps.size / f
        return when (mode) {
            SymMode.OFF -> emptyList()
            SymMode.VERTICAL -> listOf(reflect(stamps, n, f, cx, cy, flipX = true, flipY = false))
            SymMode.HORIZONTAL -> listOf(reflect(stamps, n, f, cx, cy, flipX = false, flipY = true))
            SymMode.QUAD -> listOf(
                reflect(stamps, n, f, cx, cy, flipX = true, flipY = false),
                reflect(stamps, n, f, cx, cy, flipX = false, flipY = true),
                reflect(stamps, n, f, cx, cy, flipX = true, flipY = true),
            )
            SymMode.RADIAL -> {
                val cnt = radialCount.coerceIn(2, 16)
                (1 until cnt).map { k -> rotate(stamps, n, f, cx, cy, (2.0 * PI * k / cnt).toFloat()) }
            }
        }
    }

    private fun reflect(s: FloatArray, n: Int, f: Int, cx: Float, cy: Float, flipX: Boolean, flipY: Boolean): FloatArray {
        val o = s.copyOf()
        val pi = PI.toFloat()
        for (i in 0 until n) {
            val b = i * f
            if (flipX) o[b] = 2f * cx - s[b]
            if (flipY) o[b + 1] = 2f * cy - s[b + 1]
            // 회전각(rot): x반사 → π−rot, y반사 → −rot, 둘 다 → π+rot
            o[b + 3] = when {
                flipX && flipY -> pi + s[b + 3]
                flipX -> pi - s[b + 3]
                flipY -> -s[b + 3]
                else -> s[b + 3]
            }
            // 반지름(b+2), 단축비(b+4), 알파(b+5)는 그대로.
        }
        return o
    }

    private fun rotate(s: FloatArray, n: Int, f: Int, cx: Float, cy: Float, ang: Float): FloatArray {
        val o = s.copyOf()
        val c = cos(ang); val sn = sin(ang)
        for (i in 0 until n) {
            val b = i * f
            val dx = s[b] - cx; val dy = s[b + 1] - cy
            o[b] = cx + dx * c - dy * sn
            o[b + 1] = cy + dx * sn + dy * c
            o[b + 3] = s[b + 3] + ang
        }
        return o
    }
}
