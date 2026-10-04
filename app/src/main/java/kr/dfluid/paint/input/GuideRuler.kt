package kr.dfluid.paint.input

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 그리기 자: 획을 원근선이나 동심원에 붙입니다. UI 스레드 전용.
 *
 * - 원근 자: 소실점 1~3개. 획을 시작하고 조금 움직이면(방향이 정해지면) 그 방향과 가장 가까운
 *   "시작점 → 소실점" 직선을 고르고, 이후 점들을 그 직선 위로 투영합니다.
 *   1점은 수평·수직선도, 2점은 수직선도 함께 후보로 둡니다 (일반적인 원근 작도 방식).
 * - 동심원 자: 중심에서 시작점까지 거리를 반지름으로 하는 원 위로 투영합니다.
 * - 평행선 자: 두 손잡이가 정한 방향과 평행한 직선만 (빗금·속도선).
 * - 방사선 자: 중심을 지나는 직선만 (만화 집중선).
 *
 * 좌표는 캔버스 px. 소실점·중심은 캔버스 밖에 있어도 됩니다.
 */
class GuideRuler {
    enum class Kind(val label: String) { OFF("끄기"), PERSPECTIVE("원근 자"), CONCENTRIC("동심원 자"), PARALLEL("평행선 자"), RADIAL("방사선 자") }

    var kind = Kind.OFF
    /** 원근 자 소실점 개수 1..3 */
    var vpCount = 1
    /** 소실점 [x0,y0,x1,y1,x2,y2] */
    val vp = FloatArray(6)
    /** 동심원 중심 */
    var cx = 0f
    var cy = 0f
    /** 평행선 자 두 손잡이 [ax, ay, bx, by] (방향 = b − a) */
    val par = FloatArray(4)

    val on: Boolean get() = kind != Kind.OFF

    /** 점들이 기준으로 삼는 캔버스 크기 (캔버스 크기가 바뀌면 비율대로 옮김) */
    var docW = 0f
        private set
    var docH = 0f
        private set

    /** 캔버스 크기가 바뀌면 점들을 같은 비율 자리로 옮깁니다. 처음이면 기본 위치. */
    fun fitCanvas(w: Float, h: Float) {
        if (docW <= 0f || docH <= 0f) { reset(w, h); return }
        if (w == docW && h == docH) return
        val sx = w / docW
        val sy = h / docH
        for (i in 0 until 3) { vp[i * 2] *= sx; vp[i * 2 + 1] *= sy }
        cx *= sx; cy *= sy
        par[0] *= sx; par[1] *= sy; par[2] *= sx; par[3] *= sy
        docW = w; docH = h
    }

    /** 설정 저장용 문자열 */
    fun encode(): String = buildString {
        append(kind.name).append(';').append(vpCount).append(';').append(docW).append(';').append(docH)
        for (v in vp) append(';').append(v)
        append(';').append(cx).append(';').append(cy)
        for (v in par) append(';').append(v)
    }

    /** [encode] 결과에서 복원. 실패하면 false. */
    fun decode(s: String?): Boolean {
        val p = s?.split(';') ?: return false
        if (p.size != 12 && p.size != 16) return false
        return try {
            kind = Kind.valueOf(p[0])
            vpCount = p[1].toInt().coerceIn(1, 3)
            docW = p[2].toFloat(); docH = p[3].toFloat()
            for (i in 0 until 6) vp[i] = p[4 + i].toFloat()
            cx = p[10].toFloat(); cy = p[11].toFloat()
            if (p.size == 16) for (i in 0 until 4) par[i] = p[12 + i].toFloat()
            else { par[0] = docW * 0.3f; par[1] = docH * 0.5f; par[2] = docW * 0.7f; par[3] = docH * 0.4f }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 캔버스 크기에 맞춰 기본 위치로. */
    fun reset(w: Float, h: Float) {
        docW = w; docH = h
        val horizon = h * 0.42f
        when (vpCount) {
            1 -> { vp[0] = w * 0.5f; vp[1] = horizon }
            2 -> { vp[0] = w * 0.08f; vp[1] = horizon; vp[2] = w * 0.92f; vp[3] = horizon }
            else -> { vp[0] = w * 0.08f; vp[1] = horizon * 0.6f; vp[2] = w * 0.92f; vp[3] = horizon * 0.6f; vp[4] = w * 0.5f; vp[5] = h * 0.98f }
        }
        cx = w / 2f
        cy = h / 2f
        par[0] = w * 0.3f; par[1] = h * 0.5f; par[2] = w * 0.7f; par[3] = h * 0.4f
    }

    /** 끌어 옮길 수 있는 점들 [x,y,...] (원근: 소실점들, 동심원: 중심). */
    fun handles(): FloatArray = when (kind) {
        Kind.OFF -> FloatArray(0)
        Kind.PERSPECTIVE -> vp.copyOf(vpCount * 2)
        Kind.CONCENTRIC, Kind.RADIAL -> floatArrayOf(cx, cy)
        Kind.PARALLEL -> par.copyOf()
    }

    fun moveHandle(i: Int, x: Float, y: Float) {
        when (kind) {
            Kind.PERSPECTIVE -> if (i in 0 until vpCount) { vp[i * 2] = x; vp[i * 2 + 1] = y }
            Kind.CONCENTRIC, Kind.RADIAL -> { cx = x; cy = y }
            Kind.PARALLEL -> if (i in 0..1) { par[i * 2] = x; par[i * 2 + 1] = y }
            Kind.OFF -> Unit
        }
    }

    /** 한 획 동안 고정되는 제약. */
    sealed class Constraint {
        abstract fun project(x: Float, y: Float, out: FloatArray)

        /** 점 (sx,sy)를 지나고 방향 (ux,uy)(단위벡터)인 직선 */
        class Line(private val sx: Float, private val sy: Float, private val ux: Float, private val uy: Float) : Constraint() {
            override fun project(x: Float, y: Float, out: FloatArray) {
                val t = (x - sx) * ux + (y - sy) * uy
                out[0] = sx + ux * t
                out[1] = sy + uy * t
            }
        }

        class Circle(private val cx: Float, private val cy: Float, private val r: Float) : Constraint() {
            override fun project(x: Float, y: Float, out: FloatArray) {
                val dx = x - cx
                val dy = y - cy
                val d = hypot(dx, dy)
                if (d < 1e-3f) { out[0] = x; out[1] = y; return }
                out[0] = cx + dx / d * r
                out[1] = cy + dy / d * r
            }
        }
    }

    /**
     * 시작점 (sx,sy)에서 (dx,dy) 방향으로 움직이기 시작했을 때의 제약.
     * 고를 수 없으면(시작점이 소실점과 거의 같음 등) null = 자유롭게.
     */
    fun constraintFor(sx: Float, sy: Float, dx: Float, dy: Float): Constraint? {
        when (kind) {
            Kind.OFF -> return null
            Kind.PARALLEL -> {
                val vx = par[2] - par[0]; val vy = par[3] - par[1]
                val l = hypot(vx, vy)
                return if (l < 1f) null else Constraint.Line(sx, sy, vx / l, vy / l)
            }
            Kind.RADIAL -> {
                val vx = cx - sx; val vy = cy - sy
                val l = hypot(vx, vy)
                return if (l < 2f) null else Constraint.Line(sx, sy, vx / l, vy / l)
            }
            Kind.CONCENTRIC -> {
                val r = hypot(sx - cx, sy - cy)
                return if (r < 2f) null else Constraint.Circle(cx, cy, r)
            }
            Kind.PERSPECTIVE -> {
                val len = hypot(dx, dy)
                if (len < 1e-3f) return null
                val mx = dx / len
                val my = dy / len
                // 후보 방향들 (단위벡터)
                val cands = ArrayList<FloatArray>()
                for (i in 0 until vpCount) {
                    val vx = vp[i * 2] - sx
                    val vy = vp[i * 2 + 1] - sy
                    val l = hypot(vx, vy)
                    if (l > 2f) cands.add(floatArrayOf(vx / l, vy / l))
                }
                if (vpCount <= 2) cands.add(floatArrayOf(0f, 1f)) // 수직
                if (vpCount == 1) cands.add(floatArrayOf(1f, 0f)) // 수평
                // 방향이 가장 비슷한 직선 (부호 무시: |cos|가 가장 큼)
                val best = cands.maxByOrNull { abs(it[0] * mx + it[1] * my) } ?: return null
                return Constraint.Line(sx, sy, best[0], best[1])
            }
        }
    }
}
