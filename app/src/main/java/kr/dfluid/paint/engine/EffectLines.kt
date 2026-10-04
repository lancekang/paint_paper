package kr.dfluid.paint.engine

import kr.dfluid.paint.brush.StrokeBuilder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * 효과선 설정. [kind] 0 = 집중선(가운데로 모이는 선), 1 = 유선(한 방향으로 나란한 선).
 * [jitter] 0..1 = 길이·굵기 흔들림, [angleDeg] = 유선 방향.
 */
data class EffectLineSpec(
    val kind: Int,
    val count: Int,
    val width: Float,
    val jitter: Float,
    val angleDeg: Float,
    val taper: Boolean,
    val seed: Long,
)

/** 효과선 → 선마다 스탬프 배열 (StrokeBuilder 형식: x, y, 반지름, 회전, 단축 비율, 알파) */
object EffectLines {
    /**
     * [area] = 기준 범위 (선택 영역 경계, 없으면 캔버스). 집중선은 [area]에 내접한 타원 바깥에서 시작해
     * 캔버스 밖까지, 유선은 [area] 안을 [angleDeg] 방향으로 가로지릅니다.
     */
    fun generate(spec: EffectLineSpec, area: IRect, canvasW: Int, canvasH: Int, alpha: Float): List<FloatArray> {
        val rnd = Random(spec.seed)
        val out = ArrayList<FloatArray>()
        val n = spec.count.coerceIn(1, 2000)
        if (spec.kind == 0) {
            val cx = area.x + area.w / 2f
            val cy = area.y + area.h / 2f
            val whole = area.w >= canvasW && area.h >= canvasH
            // 선택이 없으면 가운데 빈 곳 = 짧은 변의 30%
            val rx = if (whole) minOf(canvasW, canvasH) * 0.3f else area.w / 2f
            val ry = if (whole) minOf(canvasW, canvasH) * 0.3f else area.h / 2f
            // 캔버스의 가장 먼 모서리보다 바깥에서 시작
            val far = maxOf(
                hypot(cx, cy), hypot(canvasW - cx, cy), hypot(cx, canvasH - cy), hypot(canvasW - cx, canvasH - cy),
            ) + spec.width * 2
            for (i in 0 until n) {
                val a = (2 * PI * (i + (rnd.nextFloat() - 0.5f) * 0.8f) / n).toFloat()
                val dx = cos(a); val dy = sin(a)
                // 안쪽 끝: 타원 위 + 흔들림만큼 바깥으로
                val inner = 1f + spec.jitter * rnd.nextFloat() * 0.8f
                val ix = cx + dx * rx * inner
                val iy = cy + dy * ry * inner
                val ox = cx + dx * far
                val oy = cy + dy * far
                val w = spec.width * (1f - spec.jitter * 0.5f * rnd.nextFloat())
                // 바깥(t = 0)이 굵고 안쪽(t = 1)으로 가늘게
                out.add(line(ox, oy, ix, iy, alpha) { t -> w / 2f * (if (spec.taper) (1f - t).pow(0.7f) else 1f) })
            }
        } else {
            val ang = Math.toRadians(spec.angleDeg.toDouble()).toFloat()
            val dx = cos(ang); val dy = sin(ang)
            val nx = -dy; val ny = dx
            val cx = area.x + area.w / 2f
            val cy = area.y + area.h / 2f
            // 범위를 방향/수직 축에 투영한 반폭
            val halfAlong = (kotlin.math.abs(dx) * area.w + kotlin.math.abs(dy) * area.h) / 2f
            val halfAcross = (kotlin.math.abs(nx) * area.w + kotlin.math.abs(ny) * area.h) / 2f
            for (i in 0 until n) {
                val off = (rnd.nextFloat() * 2f - 1f) * halfAcross
                val len = halfAlong * 2f * (1f - spec.jitter * 0.7f * rnd.nextFloat())
                val start = (rnd.nextFloat() * 2f - 1f) * (halfAlong * 2f - len) / 2f - len / 2f
                val bx = cx + nx * off + dx * start
                val by = cy + ny * off + dy * start
                val w = spec.width * (1f - spec.jitter * 0.5f * rnd.nextFloat())
                // 가운데가 굵고 양 끝이 가늘게
                out.add(line(bx, by, bx + dx * len, by + dy * len, alpha) { t ->
                    w / 2f * (if (spec.taper) sin(PI.toFloat() * t).coerceAtLeast(0f).pow(0.6f) else 1f)
                })
            }
        }
        return out
    }

    /** (x0,y0)→(x1,y1) 직선을 반지름 함수 [radius](t ∈ 0..1)로 스탬프 */
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, alpha: Float, radius: (Float) -> Float): FloatArray {
        val len = hypot(x1 - x0, y1 - y0)
        var buf = FloatArray(StrokeBuilder.FLOATS * 256)
        var n = 0
        var d = 0f
        while (d <= len) {
            val t = if (len > 0f) d / len else 0f
            var r = radius(t)
            var a = alpha
            // 아주 가는 끝은 반지름 대신 알파로 (StrokeBuilder와 같게)
            if (r < 0.7f) { a *= max(0f, r) / 0.7f; r = 0.7f }
            if (n + StrokeBuilder.FLOATS > buf.size) buf = buf.copyOf(buf.size * 2)
            buf[n] = x0 + (x1 - x0) * t; buf[n + 1] = y0 + (y1 - y0) * t
            buf[n + 2] = r; buf[n + 3] = 0f; buf[n + 4] = 1f; buf[n + 5] = a
            n += StrokeBuilder.FLOATS
            d += max(0.5f, r * 0.25f)
        }
        return buf.copyOf(n)
    }
}
