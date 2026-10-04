package kr.dfluid.paint.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 캔버스 ↔ 화면 변환. UI 스레드 전용.
 *
 * screen = T + R·S·F·(canvas − center)
 *   F = 좌우 반전, S = 배율, R = 회전, T = 캔버스 중심의 화면 위치
 */
class Viewport {
    var canvasW = 1f; private set
    var canvasH = 1f; private set
    var screenW = 1f; private set
    var screenH = 1f; private set
    var scale = 1f; private set
    var rotation = 0f; private set
    var flipped = false; private set
    private var tx = 0f
    private var ty = 0f
    private val tmp = FloatArray(2)

    fun setCanvas(w: Int, h: Int) {
        canvasW = w.toFloat(); canvasH = h.toFloat()
    }

    fun setScreen(w: Int, h: Int) {
        // 화면 크기가 바뀌면(회전 등) 화면 중심에 있던 캔버스 점을 유지
        val cx = screenW / 2; val cy = screenH / 2
        toCanvas(cx, cy, tmp)
        screenW = w.toFloat(); screenH = h.toFloat()
        anchor(tmp[0], tmp[1], screenW / 2, screenH / 2)
    }

    private fun m00() = cos(rotation) * scale * (if (flipped) -1f else 1f)
    private fun m01() = -sin(rotation) * scale
    private fun m10() = sin(rotation) * scale * (if (flipped) -1f else 1f)
    private fun m11() = cos(rotation) * scale

    fun toCanvas(sx: Float, sy: Float, out: FloatArray) {
        val dx = sx - tx
        val dy = sy - ty
        val c = cos(rotation)
        val s = sin(rotation)
        var ux = (c * dx + s * dy) / scale
        val uy = (-s * dx + c * dy) / scale
        if (flipped) ux = -ux
        out[0] = ux + canvasW / 2
        out[1] = uy + canvasH / 2
    }

    fun toScreen(cx: Float, cy: Float, out: FloatArray) {
        val ux = cx - canvasW / 2
        val uy = cy - canvasH / 2
        out[0] = tx + m00() * ux + m01() * uy
        out[1] = ty + m10() * ux + m11() * uy
    }

    /** 캔버스 점 (cx, cy)가 화면 (sx, sy)에 오도록 이동량을 맞춥니다. */
    private fun anchor(cx: Float, cy: Float, sx: Float, sy: Float) {
        val ux = cx - canvasW / 2
        val uy = cy - canvasH / 2
        tx = sx - (m00() * ux + m01() * uy)
        ty = sy - (m10() * ux + m11() * uy)
    }

    fun pan(dx: Float, dy: Float) {
        tx += dx; ty += dy
    }

    fun zoomAt(factor: Float, sx: Float, sy: Float) {
        toCanvas(sx, sy, tmp)
        scale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        anchor(tmp[0], tmp[1], sx, sy)
    }

    fun zoomCentered(factor: Float) = zoomAt(factor, screenW / 2, screenH / 2)

    fun rotateAt(delta: Float, sx: Float, sy: Float) {
        toCanvas(sx, sy, tmp)
        rotation = normalize(rotation + delta)
        anchor(tmp[0], tmp[1], sx, sy)
    }

    fun rotateCentered(delta: Float) = rotateAt(delta, screenW / 2, screenH / 2)

    fun resetRotation() = rotateCentered(-rotation)

    fun toggleFlip() {
        toCanvas(screenW / 2, screenH / 2, tmp)
        flipped = !flipped
        anchor(tmp[0], tmp[1], screenW / 2, screenH / 2)
    }

    /** 화면에 꽉 차게 (회전은 초기화, 반전은 유지). */
    fun fit() {
        scale = (min(screenW / canvasW, screenH / canvasH) * 0.9f).coerceIn(MIN_SCALE, MAX_SCALE)
        rotation = 0f
        tx = screenW / 2
        ty = screenH / 2
    }

    fun actualSize() = zoomCentered(1f / scale)

    /** [m00, m10, m01, m11, t0, t1, scale] — 캔버스 px → 화면 px. */
    fun toGl(): FloatArray {
        val a = m00(); val b = m10(); val c = m01(); val d = m11()
        val hx = canvasW / 2; val hy = canvasH / 2
        return floatArrayOf(a, b, c, d, tx - (a * hx + c * hy), ty - (b * hx + d * hy), scale)
    }

    val rotationDegrees: Int get() = Math.round(Math.toDegrees(rotation.toDouble())).toInt()

    private fun normalize(r: Float): Float {
        var v = r
        val twoPi = (2 * PI).toFloat()
        while (v > PI) v -= twoPi
        while (v <= -PI) v += twoPi
        // 0°, 90° 근처는 스냅 (키보드 15° 회전이 누적 오차 없이 정확히 돌아오도록)
        val step = (PI / 12).toFloat()
        val snapped = Math.round(v / step) * step
        return if (kotlin.math.abs(v - snapped) < 0.0005f) snapped else v
    }

    companion object {
        const val MIN_SCALE = 0.02f
        const val MAX_SCALE = 64f
    }
}
