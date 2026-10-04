package kr.dfluid.paint.input

import kr.dfluid.paint.brush.StrokeBuilder
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 선 입·출 처리: 획의 시작(입)과 끝(출)을 필압과 상관없이 가늘게 만듭니다.
 *
 * - 입: 시작점부터 [inLen] px 동안 반지름을 0 → 1로 키웁니다. 그리는 중에 바로 적용합니다.
 * - 출: 끝에서 [outLen] px 동안 1 → 0으로 줄입니다. 끝이 어디인지는 펜을 뗄 때 알 수 있으므로
 *   [finish]가 획 전체를 다시 계산해 돌려주고, 호출부가 스트로크 버퍼를 통째로 다시 그립니다.
 * - 가늘어진 구간은 원래 크기 기준 간격이라 점점이 보이므로 스탬프를 보충합니다.
 *
 * 원래(가늘게 하기 전) 스탬프와 시작점부터의 거리를 기록해 두고, 출력은 언제나 기록에서 다시 만듭니다.
 * 스탬프 형식은 [StrokeBuilder] (x, y, 반지름, 회전각, 단축비, 알파). UI 스레드 전용.
 */
class StrokeTaper {
    private val f = StrokeBuilder.FLOATS
    private var inLen = 0f
    private var outLen = 0f
    private var raw = FloatArray(f * 256)
    private var dist = FloatArray(256)
    private var n = 0
    /** 후보정: 펜을 뗄 때 획 전체를 이 거리(캔버스 px, σ)로 매끄럽게 (0 = 끔) */
    private var post = 0f

    val enabled: Boolean get() = inLen > 0f || outLen > 0f || post > 0f

    fun begin(taperIn: Float, taperOut: Float, postSmooth: Float = 0f) {
        inLen = max(0f, taperIn)
        outLen = max(0f, taperOut)
        post = max(0f, postSmooth)
        n = 0
    }

    /** 새 스탬프를 기록하고, 입 처리한 그릴 스탬프를 돌려줍니다 (꺼져 있으면 그대로). */
    fun push(stamps: FloatArray): FloatArray {
        if (!enabled) return stamps
        val start = n
        var i = 0
        while (i < stamps.size) {
            ensure(n + 1)
            System.arraycopy(stamps, i, raw, n * f, f)
            dist[n] = if (n == 0) 0f else dist[n - 1] + hypot(raw[n * f] - raw[(n - 1) * f], raw[n * f + 1] - raw[(n - 1) * f + 1])
            n++
            i += f
        }
        return render(start, n, total = -1f)
    }

    /** 펜을 뗄 때: 출이 있으면 획 전체(입·출 모두 적용)를 돌려줍니다. 없으면 null (다시 그릴 필요 없음). */
    fun finish(): FloatArray? {
        if ((outLen <= 0f && post <= 0f) || n == 0) return null
        if (post > 0f) smoothRaw()
        return render(0, n, total = dist[n - 1])
    }

    /**
     * 후보정: 시작점부터 거리 기준 가우시안으로 위치를 고릅니다. 양 끝은 고정
     * (끝에 가까울수록 σ를 끝까지 거리로 줄임). 반지름·각도는 그대로.
     */
    private fun smoothRaw() {
        if (n < 3) return
        val total = dist[n - 1]
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        var lo = 0
        for (i in 0 until n) {
            val sigma = min(post, min(dist[i], total - dist[i]))
            if (sigma < 0.5f) {
                xs[i] = raw[i * f]; ys[i] = raw[i * f + 1]
                continue
            }
            val reach = sigma * 3f
            while (lo < n && dist[lo] < dist[i] - reach) lo++
            var sx = 0f; var sy = 0f; var sw = 0f
            var k = lo
            val inv = -0.5f / (sigma * sigma)
            while (k < n && dist[k] <= dist[i] + reach) {
                val dd = dist[k] - dist[i]
                val w = kotlin.math.exp(dd * dd * inv)
                sx += raw[k * f] * w; sy += raw[k * f + 1] * w; sw += w
                k++
            }
            xs[i] = sx / sw; ys[i] = sy / sw
        }
        for (i in 0 until n) {
            raw[i * f] = xs[i]; raw[i * f + 1] = ys[i]
            if (i > 0) dist[i] = dist[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        }
    }

    /** 기록 전체를 입·출 모두 적용해 돌려줍니다 (직선 자처럼 매번 통째로 다시 그릴 때). */
    fun renderAll(): FloatArray = if (n == 0) FloatArray(0) else render(0, n, total = dist[n - 1])

    /** 0..1 → 0..1, 끝이 너무 뾰족해 사라지지 않게 최솟값을 둡니다. */
    private fun ease(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return max(MIN_SCALE, c * c * (3f - 2f * c))
    }

    /** 시작점부터 거리 d에서의 크기 배율. total < 0 이면 출은 적용하지 않음. */
    private fun scale(d: Float, total: Float): Float {
        var s = 1f
        if (inLen > 0f && d < inLen) s = min(s, ease(d / inLen))
        if (total >= 0f && outLen > 0f && total - d < outLen) s = min(s, ease((total - d) / outLen))
        return s
    }

    /** raw[from until to]를 그릴 스탬프로. 가늘어진 구간은 바로 앞 스탬프와의 사이를 채웁니다. */
    private fun render(from: Int, to: Int, total: Float): FloatArray {
        val out = Builder()
        for (k in from until to) {
            val sk = scale(dist[k], total)
            if (k > 0) {
                val sp = scale(dist[k - 1], total)
                if (sk < 0.999f || sp < 0.999f) {
                    // 두 스탬프 사이 간격이 작은 쪽 반지름의 40%보다 넓으면 보충
                    val gap = dist[k] - dist[k - 1]
                    val rMin = max(0.5f, min(raw[(k - 1) * f + 2] * sp, raw[k * f + 2] * sk))
                    val extra = min(64, ceil(gap / (rMin * 0.4f)).toInt() - 1)
                    for (j in 1..extra) {
                        val t = j / (extra + 1f)
                        val d = dist[k - 1] + gap * t
                        out.lerp(raw, (k - 1) * f, k * f, t, scale(d, total))
                    }
                }
            }
            out.lerp(raw, k * f, k * f, 0f, sk)
        }
        return out.toArray()
    }

    private fun ensure(count: Int) {
        if (count * f > raw.size) {
            raw = raw.copyOf(raw.size * 2)
            dist = dist.copyOf(dist.size * 2)
        }
    }

    private inner class Builder {
        var a = FloatArray(f * 64)
        var size = 0

        /** raw[i0]와 raw[i1] 사이 t 위치의 스탬프를 반지름 × s로 추가. */
        fun lerp(src: FloatArray, i0: Int, i1: Int, t: Float, s: Float) {
            if (size + f > a.size) a = a.copyOf(a.size * 2)
            for (c in 0 until f) a[size + c] = src[i0 + c] + (src[i1 + c] - src[i0 + c]) * t
            a[size + 2] *= s
            size += f
        }

        fun toArray(): FloatArray = a.copyOf(size)
    }

    companion object {
        /** 끝에서도 이만큼은 남김 (반지름 배율) */
        const val MIN_SCALE = 0.06f
    }
}
