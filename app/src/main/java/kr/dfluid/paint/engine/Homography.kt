package kr.dfluid.paint.engine

import kotlin.math.abs

/**
 * 3×3 원근 행렬 (행 우선 9개): X = (h0·u + h1·v + h2) / w, Y = (h3·u + h4·v + h5) / w, w = h6·u + h7·v + h8.
 * 자유 변형의 아핀 행렬 [a, b, c, d, tx, ty] (X = a·u + c·v + tx, Y = b·u + d·v + ty)도 이 형식으로 바꿔 씁니다.
 */
object Homography {
    fun fromAffine(m: FloatArray): FloatArray = floatArrayOf(m[0], m[2], m[4], m[1], m[3], m[5], 0f, 0f, 1f)

    fun isAffine(h: FloatArray) = abs(h[6]) < 1e-9f && abs(h[7]) < 1e-9f

    fun map(h: FloatArray, u: Float, v: Float, out: FloatArray) {
        val w = h[6] * u + h[7] * v + h[8]
        out[0] = (h[0] * u + h[1] * v + h[2]) / w
        out[1] = (h[3] * u + h[4] * v + h[5]) / w
    }

    /** 0..w × 0..h 사각형의 네 모서리(왼위, 오위, 오아래, 왼아래) → 사각형 [q] (x,y × 4)로 보내는 행렬. */
    fun rectToQuad(w: Float, hgt: Float, q: FloatArray): FloatArray {
        val x0 = q[0]; val y0 = q[1]; val x1 = q[2]; val y1 = q[3]
        val x2 = q[4]; val y2 = q[5]; val x3 = q[6]; val y3 = q[7]
        val sx = x0 - x1 + x2 - x3
        val sy = y0 - y1 + y2 - y3
        var g = 0f; var hh = 0f
        if (abs(sx) > 1e-6f || abs(sy) > 1e-6f) {
            val dx1 = x1 - x2; val dx2 = x3 - x2; val dy1 = y1 - y2; val dy2 = y3 - y2
            val den = dx1 * dy2 - dx2 * dy1
            if (abs(den) > 1e-9f) {
                g = (sx * dy2 - dx2 * sy) / den
                hh = (dx1 * sy - sx * dy1) / den
            }
        }
        val a = x1 - x0 + g * x1; val b = x3 - x0 + hh * x3
        val d = y1 - y0 + g * y1; val e = y3 - y0 + hh * y3
        // 단위 사각형 → 픽셀: 열 0은 1/w, 열 1은 1/h 배
        return floatArrayOf(a / w, b / hgt, x0, d / w, e / hgt, y0, g / w, hh / hgt, 1f)
    }

    fun invert(m: FloatArray): FloatArray? {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
        val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
        val det = a * A + b * B + c * C
        if (abs(det) < 1e-12f) return null
        val s = 1f / det
        return floatArrayOf(
            A * s, -(b * i - c * h) * s, (b * f - c * e) * s,
            B * s, (a * i - c * g) * s, -(a * f - c * d) * s,
            C * s, -(a * h - b * g) * s, (a * e - b * d) * s,
        )
    }

    /** h · T(dx, dy): 로컬 좌표를 (dx, dy)만큼 민 뒤 h를 적용 */
    fun preTranslate(h: FloatArray, dx: Float, dy: Float): FloatArray = floatArrayOf(
        h[0], h[1], h[0] * dx + h[1] * dy + h[2],
        h[3], h[4], h[3] * dx + h[4] * dy + h[5],
        h[6], h[7], h[6] * dx + h[7] * dy + h[8],
    )
}
