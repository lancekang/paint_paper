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

    /**
     * 덧칠: 펜으로 문지른 자리의 씨앗점([seeds] = x,y 쌍)마다 [run]으로 칸을 찾아 모두 합칩니다.
     * 이미 칠해진 칸 안의 씨앗과 선(진하고 불투명한 픽셀) 위의 씨앗은 건너뜁니다 (선 전체가 칠해지지 않게).
     */
    fun paintOver(
        ref: ByteBuffer, w: Int, h: Int, seeds: IntArray,
        tolerance: Int, gap: Int, expand: Int, selection: ByteBuffer?,
    ): Result? {
        val px = ref.duplicate()
        var acc: ByteArray? = null
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        var runs = 0
        var i = 0
        while (i + 1 < seeds.size && runs < MAX_PAINT_RUNS) {
            val x = seeds[i]; val y = seeds[i + 1]
            i += 2
            if (x !in 0 until w || y !in 0 until h) continue
            val k = y * w + x
            if (acc != null && acc[k].toInt() != 0) continue
            if (isInk(px, k * 4)) continue
            val r = run(ref, w, h, x, y, tolerance, gap, expand, selection) ?: continue
            runs++
            val a = acc ?: ByteArray(w * h).also { acc = it }
            val b = r.bounds
            for (yy in b.y until b.bottom) {
                val row = yy * w
                for (xx in b.x until b.right) {
                    val v = r.mask[row + xx].toInt() and 0xFF
                    if (v > (a[row + xx].toInt() and 0xFF)) a[row + xx] = v.toByte()
                }
            }
            if (b.x < minX) minX = b.x
            if (b.y < minY) minY = b.y
            if (b.right - 1 > maxX) maxX = b.right - 1
            if (b.bottom - 1 > maxY) maxY = b.bottom - 1
        }
        val out = acc ?: return null
        if (maxX < 0) return null
        return Result(out, IRect(minX, minY, maxX - minX + 1, maxY - minY + 1))
    }

    /** 선으로 볼 픽셀: 반 이상 불투명하고 어두움 (프리멀티플라이드 RGBA) */
    private fun isInk(px: ByteBuffer, o: Int): Boolean {
        val a = px.get(o + 3).toInt() and 0xFF
        if (a < 128) return false
        val lum = 0.299f * (px.get(o).toInt() and 0xFF) + 0.587f * (px.get(o + 1).toInt() and 0xFF) + 0.114f * (px.get(o + 2).toInt() and 0xFF)
        return lum / a < 0.35f
    }

    /** 덧칠 한 번에 찾는 칸의 최대 수 (씨앗마다 캔버스 전체를 훑으므로) */
    private const val MAX_PAINT_RUNS = 64

    /**
     * 둘러싸고 칠하기: [lasso](w*h, 0 아니면 안쪽) 안에서, 선으로 닫혀 밖과 이어지지 않은 영역을 모두 채웁니다.
     * 선 = 참조 이미지의 "잉크"(흰 바탕에 올렸을 때의 어두움)가 기준보다 진한 곳. 올가미 밖에서 닿을 수 있는 칸은 뺍니다.
     */
    fun enclose(
        ref: ByteBuffer, w: Int, h: Int, lasso: ByteArray,
        tolerance: Int, gap: Int, expand: Int, selection: ByteBuffer?,
    ): Result? {
        var bx0 = w; var by0 = h; var bx1 = -1; var by1 = -1
        for (y in 0 until h) for (x in 0 until w) if (lasso[y * w + x].toInt() != 0) {
            if (x < bx0) bx0 = x
            if (x > bx1) bx1 = x
            if (y < by0) by0 = y
            if (y > by1) by1 = y
        }
        if (bx1 < 0) return null
        val pad = gap + expand + 2
        bx0 = max(0, bx0 - pad); by0 = max(0, by0 - pad)
        bx1 = minOf(w - 1, bx1 + pad); by1 = minOf(h - 1, by1 + pad)
        val bw = bx1 - bx0 + 1
        val bh = by1 - by0 + 1
        val n = bw * bh
        val px = ref.duplicate()
        // 1. 선: 허용 오차가 클수록 옅은 선도 벽으로 봄
        val limit = (255 - tolerance.coerceIn(0, 254)) * 0.5f
        var walls = ByteArray(n)
        for (y in 0 until bh) for (x in 0 until bw) {
            val o = ((by0 + y) * w + bx0 + x) * 4
            val a = px.get(o + 3).toInt() and 0xFF
            if (a == 0) continue
            // 프리멀티플라이드를 흰 바탕에 올린 밝기 = rgb + (255 − a)
            val rr = (px.get(o).toInt() and 0xFF) + 255 - a
            val gg = (px.get(o + 1).toInt() and 0xFF) + 255 - a
            val bb = (px.get(o + 2).toInt() and 0xFF) + 255 - a
            val ink = 255f - (0.299f * rr + 0.587f * gg + 0.114f * bb)
            if (ink >= limit) walls[y * bw + x] = 1
        }
        val rawWalls = walls
        if (gap > 0) walls = dilate(walls, bw, bh, gap)
        // 2. 밖(올가미 밖 + 상자 테두리)에서 닿는 칸
        val open = ByteArray(n) { if (walls[it].toInt() == 0) 1 else 0 }
        val reach = ByteArray(n)
        val stack = IntArrayStack()
        for (y in 0 until bh) for (x in 0 until bw) {
            val i = y * bw + x
            val inside = lasso[(by0 + y) * w + bx0 + x].toInt() != 0
            val edge = x == 0 || y == 0 || x == bw - 1 || y == bh - 1
            if ((!inside || edge) && open[i].toInt() != 0 && reach[i].toInt() == 0) {
                reach[i] = 1
                stack.push(x, y)
                while (stack.isNotEmpty()) {
                    val cy = stack.popY()
                    val cx = stack.popX()
                    for (k in 0 until 4) {
                        val nx = cx + DX[k]
                        val ny = cy + DY[k]
                        if (nx < 0 || ny < 0 || nx >= bw || ny >= bh) continue
                        val j = ny * bw + nx
                        if (open[j].toInt() != 0 && reach[j].toInt() == 0) {
                            reach[j] = 1
                            stack.push(nx, ny)
                        }
                    }
                }
            }
        }
        // 3. 안쪽의 닫힌 칸
        var region = ByteArray(n)
        for (y in 0 until bh) for (x in 0 until bw) {
            val i = y * bw + x
            if (open[i].toInt() != 0 && reach[i].toInt() == 0 && lasso[(by0 + y) * w + bx0 + x].toInt() != 0) region[i] = 1
        }
        // 4. 틈 메우기로 줄어든 만큼 되돌리기 (원래 선 픽셀은 제외) + 확장
        if (gap > 0) {
            val grown = dilate(region, bw, bh, gap)
            for (i in 0 until n) if (rawWalls[i].toInt() != 0 && region[i].toInt() == 0) grown[i] = 0
            // 올가미 밖이나 밖과 이어진 칸으로는 넘치지 않게 (선 픽셀 위로만 자람)
            for (y in 0 until bh) for (x in 0 until bw) {
                val i = y * bw + x
                if (grown[i].toInt() != 0 && region[i].toInt() == 0 &&
                    (lasso[(by0 + y) * w + bx0 + x].toInt() == 0 || reach[i].toInt() != 0)) grown[i] = 0
            }
            region = grown
        }
        if (expand > 0) region = dilate(region, bw, bh, expand)
        // 5. 캔버스 크기 마스크로 + 선택 영역과 교차
        val sel = selection?.duplicate()
        val out = ByteArray(w * h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until bh) for (x in 0 until bw) {
            if (region[y * bw + x].toInt() == 0) continue
            val cx = bx0 + x
            val cy = by0 + y
            val i = cy * w + cx
            val v = if (sel != null) sel.get(i).toInt() and 0xFF else 255
            if (v == 0) continue
            out[i] = v.toByte()
            if (cx < minX) minX = cx
            if (cx > maxX) maxX = cx
            if (cy < minY) minY = cy
            if (cy > maxY) maxY = cy
        }
        if (maxX < 0) return null
        return Result(out, IRect(minX, minY, maxX - minX + 1, maxY - minY + 1))
    }

    private val DX = intArrayOf(1, -1, 0, 0)
    private val DY = intArrayOf(0, 0, 1, -1)

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
