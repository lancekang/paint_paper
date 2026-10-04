package kr.dfluid.paint.engine

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

/**
 * 채우기(버킷) 영역 계산. CPU, 백그라운드 스레드에서 실행합니다.
 *
 * @param ref 참조 이미지, 프리멀티플라이드 RGBA w*h*4
 * @param tolerance 0..255, 채널별 최대 차이
 * @param gap 틈 메우기(px). 선의 작은 틈으로 새지 않도록 선을 gap만큼 두껍게 본 뒤 채웁니다.
 * @param expand 영역 확장(px). 선 밑까지 칠해 흰 테두리가 남지 않게 합니다.
 * @param selection 선택 마스크 w*h (없으면 null)
 * @return 마스크 w*h (0 또는 255)와 경계. 채울 곳이 없으면 null.
 */
object FloodFill {
    class Result(val mask: ByteArray, val bounds: IRect)

    fun run(
        ref: ByteBuffer, w: Int, h: Int, sx: Int, sy: Int,
        tolerance: Int, gap: Int, expand: Int, selection: ByteBuffer?,
    ): Result? {
        if (sx !in 0 until w || sy !in 0 until h) return null
        val n = w * h
        val px = ref.duplicate()
        val s = (sy * w + sx) * 4
        val r0 = px.get(s).toInt() and 0xFF
        val g0 = px.get(s + 1).toInt() and 0xFF
        val b0 = px.get(s + 2).toInt() and 0xFF
        val a0 = px.get(s + 3).toInt() and 0xFF

        // 1. 씨앗 색과 비슷한 픽셀
        val match = ByteArray(n)
        for (i in 0 until n) {
            val o = i * 4
            val d = max(
                max(abs((px.get(o).toInt() and 0xFF) - r0), abs((px.get(o + 1).toInt() and 0xFF) - g0)),
                max(abs((px.get(o + 2).toInt() and 0xFF) - b0), abs((px.get(o + 3).toInt() and 0xFF) - a0))
            )
            if (d <= tolerance) match[i] = 1
        }

        // 2. 틈 메우기: 선(=match 아닌 곳)을 gap만큼 두껍게
        var fillable = match
        if (gap > 0) {
            val walls = ByteArray(n) { if (match[it].toInt() == 0) 1 else 0 }
            val thick = dilate(walls, w, h, gap)
            val f = ByteArray(n) { if (thick[it].toInt() == 0) 1 else 0 }
            if (f[sy * w + sx].toInt() == 1) fillable = f
        }

        // 3. 스캔라인 채우기
        var region = scanlineFill(fillable, w, h, sx, sy)

        // 4. 틈 메우기로 줄어든 만큼 되돌리기 (선 픽셀은 제외)
        if (gap > 0 && fillable !== match) {
            val grown = dilate(region, w, h, gap)
            for (i in 0 until n) if (match[i].toInt() == 0) grown[i] = 0
            for (i in 0 until n) if (region[i].toInt() != 0) grown[i] = 1
            region = grown
        }

        // 5. 확장
        if (expand > 0) region = dilate(region, w, h, expand)

        // 6. 선택 영역과 교차 + 경계 계산
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        val sel = selection?.duplicate()
        val out = ByteArray(n)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                if (region[i].toInt() == 0) continue
                val v = if (sel != null) sel.get(i).toInt() and 0xFF else 255
                if (v == 0) continue
                out[i] = v.toByte()
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return null
        return Result(out, IRect(minX, minY, maxX - minX + 1, maxY - minY + 1))
    }

    private fun scanlineFill(fillable: ByteArray, w: Int, h: Int, sx: Int, sy: Int): ByteArray {
        val out = ByteArray(w * h)
        if (fillable[sy * w + sx].toInt() == 0) return out
        val stack = IntArrayStack()
        stack.push(sx, sy)
        while (stack.isNotEmpty()) {
            val y = stack.popY()
            var x = stack.popX()
            val row = y * w
            while (x > 0 && fillable[row + x - 1].toInt() != 0 && out[row + x - 1].toInt() == 0) x--
            var spanUp = false
            var spanDown = false
            while (x < w && fillable[row + x].toInt() != 0 && out[row + x].toInt() == 0) {
                out[row + x] = 1
                if (y > 0) {
                    val up = row - w + x
                    val ok = fillable[up].toInt() != 0 && out[up].toInt() == 0
                    if (ok && !spanUp) { stack.push(x, y - 1); spanUp = true } else if (!ok) spanUp = false
                }
                if (y < h - 1) {
                    val dn = row + w + x
                    val ok = fillable[dn].toInt() != 0 && out[dn].toInt() == 0
                    if (ok && !spanDown) { stack.push(x, y + 1); spanDown = true } else if (!ok) spanDown = false
                }
                x++
            }
        }
        return out
    }

    /** 정사각형 커널(반지름 r) 이진 팽창. 가로/세로 두 번, 슬라이딩 윈도우 개수 세기라 O(n). */
    fun dilate(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val tmp = ByteArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var count = 0
            for (x in 0..minOf(r, w - 1)) if (src[row + x].toInt() != 0) count++
            for (x in 0 until w) {
                if (count > 0) tmp[row + x] = 1
                val add = x + r + 1
                val rem = x - r
                if (add < w && src[row + add].toInt() != 0) count++
                if (rem >= 0 && src[row + rem].toInt() != 0) count--
            }
        }
        val out = ByteArray(w * h)
        for (x in 0 until w) {
            var count = 0
            for (y in 0..minOf(r, h - 1)) if (tmp[y * w + x].toInt() != 0) count++
            for (y in 0 until h) {
                if (count > 0) out[y * w + x] = 1
                val add = y + r + 1
                val rem = y - r
                if (add < h && tmp[add * w + x].toInt() != 0) count++
                if (rem >= 0 && tmp[rem * w + x].toInt() != 0) count--
            }
        }
        return out
    }

    private class IntArrayStack {
        private var data = IntArray(4096)
        private var size = 0
        fun push(x: Int, y: Int) {
            if (size + 2 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = x
            data[size++] = y
        }
        fun isNotEmpty() = size > 0
        fun popY(): Int = data[--size]
        fun popX(): Int = data[--size]
    }
}
