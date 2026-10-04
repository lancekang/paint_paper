package kr.dfluid.paint.brush

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.random.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * 입력 샘플(캔버스 좌표) → 스탬프 목록.
 *
 * 1. 지수 이동평균으로 손떨림을 보정하고
 * 2. 보정된 경로를 "현재 지름 × 간격" 거리마다 샘플링해 스탬프를 만듭니다.
 *
 * 스탬프 한 개 = [x, y, 반지름, 회전각, 단축 비율, 알파] (FLOATS 개 float)
 * UI 스레드에서만 사용합니다.
 */
class StrokeBuilder {
    companion object {
        const val FLOATS = 6
    }

    private lateinit var brush: Brush
    private var smoothing = 0f

    // 보정된 현재 위치
    private var fx = 0f; private var fy = 0f; private var fp = 0f; private var ft = 0f; private var fa = 0f
    // 직전 세그먼트 끝점
    private var px = 0f; private var py = 0f; private var pp = 0f; private var pt = 0f; private var pa = 0f
    // 마지막 원시 입력
    private var rx = 0f; private var ry = 0f

    private var carry = 0f
    private var dirAngle = 0f
    private val rnd = Random(System.nanoTime())
    private var out = FloatArray(FLOATS * 512)
    private var count = 0

    /** tilt: 0 = 수직, π/2 = 눕힘. angle: 캔버스 좌표계에서 펜이 기운 방향(rad). */
    fun begin(brush: Brush, smoothing: Float, x: Float, y: Float, pressure: Float, tilt: Float, angle: Float) {
        this.brush = brush
        this.smoothing = smoothing.coerceIn(0f, 0.95f)
        val pressure = brush.mapPressure(pressure)
        fx = x; fy = y; fp = pressure; ft = tilt; fa = angle
        px = x; py = y; pp = pressure; pt = tilt; pa = angle
        rx = x; ry = y
        carry = 0f
        count = 0
        dirAngle = 0f
        emit(x, y, pressure, tilt, angle)
    }

    fun add(x: Float, y: Float, rawPressure: Float, tilt: Float, angle: Float) {
        val pressure = brush.mapPressure(rawPressure)
        rx = x; ry = y
        val k = 1f - smoothing
        fx += (x - fx) * k
        fy += (y - fy) * k
        fp += (pressure - fp) * (1f - smoothing * 0.5f)
        ft += (tilt - ft) * k
        fa = lerpAngle(fa, angle, k)
        segmentTo(fx, fy, fp, ft, fa)
    }

    /** 펜을 뗄 때: 보정 때문에 뒤처진 거리를 끝점까지 채웁니다. */
    fun finish() {
        if (smoothing > 0f) segmentTo(rx, ry, fp, ft, fa)
    }

    /** 쌓인 스탬프를 꺼냅니다. 없으면 null. */
    fun drain(): FloatArray? {
        if (count == 0) return null
        val result = out.copyOf(count * FLOATS)
        count = 0
        return result
    }

    private fun segmentTo(x: Float, y: Float, p: Float, t: Float, a: Float) {
        val dx = x - px
        val dy = y - py
        val len = hypot(dx, dy)
        if (!(len >= 1e-4f)) { // NaN 방지 포함
            pp = p; pt = t; pa = a
            return
        }
        dirAngle = atan2(dy, dx)
        var pos = 0f
        while (true) {
            val f = pos / len
            val step = stepFor(lerp(pp, p, f), lerp(pt, t, f))
            val need = max(0f, step - carry)
            if (pos + need > len) {
                carry += len - pos
                break
            }
            pos += need
            carry = 0f
            val g = pos / len
            emit(px + dx * g, py + dy * g, lerp(pp, p, g), lerp(pt, t, g), lerpAngle(pa, a, g))
        }
        px = x; py = y; pp = p; pt = t; pa = a
    }

    private fun radiusFor(p: Float, t: Float): Float {
        val b = brush
        val sizeF = 1f - b.pressureSize * (1f - (b.minSizeRatio + (1f - b.minSizeRatio) * p))
        val tiltF = 1f + b.tiltSize * sin(t)
        return max(0.25f, b.size * 0.5f * sizeF * tiltF)
    }

    private fun aspectFor(t: Float): Float =
        (1f - brush.tiltShape * (1f - cos(t))).coerceIn(0.25f, 1f)

    private fun stepFor(p: Float, t: Float): Float =
        max(0.5f, brush.spacing * 2f * radiusFor(p, t) * aspectFor(t))

    private fun emit(x: Float, y: Float, p: Float, t: Float, a: Float) {
        var r = radiusFor(p, t)
        val b = brush
        if (b.sizeJitter > 0f) r *= 1f - b.sizeJitter * rnd.nextFloat()
        val baseDeg = Math.toRadians(b.angleDeg.toDouble()).toFloat()
        var rot = when (b.tipRotation) {
            TipRotation.FIXED -> baseDeg + (if (b.tiltShape > 0f && t > 0.05f) a else 0f)
            TipRotation.DIRECTION -> dirAngle + baseDeg
            TipRotation.RANDOM -> rnd.nextFloat() * (2 * PI).toFloat()
        }
        if (b.angleJitter > 0f) rot += (rnd.nextFloat() - 0.5f) * (2 * PI).toFloat() * b.angleJitter
        var alpha = brush.flow * (1f - brush.pressureOpacity * (1f - p))
        // 아주 가는 선은 반지름 대신 알파를 줄여 표현
        if (r < 0.7f) {
            alpha *= r / 0.7f
            r = 0.7f
        }
        if ((count + 1) * FLOATS > out.size) out = out.copyOf(out.size * 2)
        val i = count * FLOATS
        out[i] = x
        out[i + 1] = y
        out[i + 2] = r
        out[i + 3] = rot
        out[i + 4] = aspectFor(t)
        out[i + 5] = alpha.coerceIn(0f, 1f)
        count++
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun lerpAngle(a: Float, b: Float, t: Float): Float {
        var d = b - a
        while (d > PI) d -= (2 * PI).toFloat()
        while (d < -PI) d += (2 * PI).toFloat()
        return a + d * t
    }
}
