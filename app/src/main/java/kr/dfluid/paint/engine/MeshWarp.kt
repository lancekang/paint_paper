package kr.dfluid.paint.engine

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * 메시 변형: (N+1)×(N+1) 조절점(캔버스 px, 행 우선 x,y)을 지나는 Catmull-Rom 곡면으로 떠 있는 픽셀을 휩니다.
 * 칸마다 [SUB]×[SUB] 작은 사각형(삼각형 2개)으로 나눠 그립니다.
 */
object MeshWarp {
    /** 한 변의 칸 수 (조절점은 N+1개) */
    const val N = 4
    const val SUB = 8
    const val POINTS = (N + 1) * (N + 1)

    /** 조절점 배열인지 (아핀 6개·원근 9개와 구분) */
    fun isMesh(m: FloatArray) = m.size == POINTS * 2

    /** w×h 상자를 [map] (로컬 u,v → 캔버스)으로 옮긴 격자 */
    fun grid(w: Float, h: Float, map: (Float, Float, FloatArray) -> Unit): FloatArray {
        val out = FloatArray(POINTS * 2)
        val p = FloatArray(2)
        for (j in 0..N) for (i in 0..N) {
            map(i * w / N, j * h / N, p)
            out[(j * (N + 1) + i) * 2] = p[0]
            out[(j * (N + 1) + i) * 2 + 1] = p[1]
        }
        return out
    }

    /** 가장자리 밖 점은 직선으로 늘려 씀 (끝에서 곡면이 휘지 않게) */
    private fun pt(g: FloatArray, i: Int, j: Int, c: Int): Float {
        val ci = i.coerceIn(0, N)
        val cj = j.coerceIn(0, N)
        val base = g[(cj * (N + 1) + ci) * 2 + c]
        if (ci == i && cj == j) return base
        val ii = (2 * ci - i).coerceIn(0, N)
        val jj = (2 * cj - j).coerceIn(0, N)
        return 2 * base - g[(jj * (N + 1) + ii) * 2 + c]
    }

    private fun cr(t: Float, w: FloatArray) {
        val t2 = t * t
        val t3 = t2 * t
        w[0] = (-t3 + 2 * t2 - t) * 0.5f
        w[1] = (3 * t3 - 5 * t2 + 2) * 0.5f
        w[2] = (-3 * t3 + 4 * t2 + t) * 0.5f
        w[3] = (t3 - t2) * 0.5f
    }

    /** 곡면 위 점: 칸 (ci, cj) 안의 (s, t) ∈ [0,1] */
    fun eval(g: FloatArray, ci: Int, cj: Int, s: Float, t: Float, out: FloatArray) {
        val ws = FloatArray(4)
        val wt = FloatArray(4)
        cr(s, ws)
        cr(t, wt)
        var x = 0f
        var y = 0f
        for (b in 0 until 4) for (a in 0 until 4) {
            val k = ws[a] * wt[b]
            x += k * pt(g, ci - 1 + a, cj - 1 + b, 0)
            y += k * pt(g, ci - 1 + a, cj - 1 + b, 1)
        }
        out[0] = x
        out[1] = y
    }

    /** 전체 곡면을 [steps] 간격 점들로 (테두리·격자선 그리기용): 칸 u → 점 */
    fun sample(g: FloatArray, gu: Float, gv: Float, out: FloatArray) {
        val ci = min(N - 1, gu.toInt().coerceAtLeast(0))
        val cj = min(N - 1, gv.toInt().coerceAtLeast(0))
        eval(g, ci, cj, gu - ci, gv - cj, out)
    }

    /** 삼각형 목록 (x, y, u, v) — u,v는 떠 있는 텍스처 좌표 0..1 */
    fun triangles(g: FloatArray): FloatArray {
        val n = N * SUB
        val vx = FloatArray((n + 1) * (n + 1))
        val vy = FloatArray((n + 1) * (n + 1))
        val p = FloatArray(2)
        for (j in 0..n) for (i in 0..n) {
            sample(g, i.toFloat() / SUB, j.toFloat() / SUB, p)
            vx[j * (n + 1) + i] = p[0]
            vy[j * (n + 1) + i] = p[1]
        }
        val out = FloatArray(n * n * 6 * 4)
        var o = 0
        fun put(i: Int, j: Int) {
            val k = j * (n + 1) + i
            out[o++] = vx[k]; out[o++] = vy[k]
            out[o++] = i.toFloat() / n; out[o++] = j.toFloat() / n
        }
        for (j in 0 until n) for (i in 0 until n) {
            put(i, j); put(i + 1, j); put(i, j + 1)
            put(i + 1, j); put(i + 1, j + 1); put(i, j + 1)
        }
        return out
    }

    /** 삼각형들이 덮는 범위 [l, t, r, b] */
    fun bounds(tri: FloatArray): FloatArray {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i < tri.size) {
            l = min(l, tri[i]); r = max(r, tri[i])
            t = min(t, tri[i + 1]); b = max(b, tri[i + 1])
            i += 4
        }
        return floatArrayOf(l, t, r, b)
    }
}

/** 메시 삼각형을 올려 그리는 버퍼 (속성 0 = 캔버스 px, 1 = 텍스처 좌표). GL 스레드 전용 */
class MeshBuffer {
    private val vao: Int
    private val vbo: Int
    private var data: FloatBuffer? = null

    init {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, 8)
        GLES30.glBindVertexArray(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun draw(tri: FloatArray) {
        val buf = data?.takeIf { it.capacity() >= tri.size } ?: GlUtil.floatBuffer(tri.size).also { data = it }
        buf.clear()
        buf.put(tri)
        buf.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, tri.size * 4, buf, GLES20.GL_STREAM_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(vao)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, tri.size / 4)
        GLES30.glBindVertexArray(0)
    }
}
