package kr.dfluid.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

enum class SelOp { REPLACE, ADD, SUBTRACT, INTERSECT }

/** 선택 영역 편집: 확장 / 축소 / 경계 흐리기 */
enum class SelModify(val label: String) { GROW("확장"), SHRINK("축소"), FEATHER("경계 흐리기") }

/** WAND = 자동 선택(누른 곳과 비슷한 색의 영역). 도형이 아니라 [SelectionMask.applyMask]로 반영합니다. */
enum class SelShape { RECT, ELLIPSE, LASSO, WAND }

/**
 * 선택 영역 마스크 (CPU 원본, 캔버스 크기 ALPHA_8).
 * 도형 래스터화는 android.graphics.Canvas에 맡기고, 결과를 GPU R8 텍스처로 올립니다.
 * GL 스레드에서만 사용합니다.
 */
class SelectionMask(val width: Int, val height: Int) {
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
    private val canvas = Canvas(bitmap)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }

    fun clear() = bitmap.eraseColor(0)

    fun selectAll() = bitmap.eraseColor(0xFF000000.toInt())

    fun apply(path: Path, op: SelOp) {
        when (op) {
            SelOp.REPLACE -> {
                clear()
                canvas.drawPath(path, fill)
            }
            SelOp.ADD -> canvas.drawPath(path, fill)
            SelOp.SUBTRACT -> canvas.drawPath(path, Paint(fill).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            SelOp.INTERSECT -> {
                val tmp = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
                Canvas(tmp).drawPath(path, fill)
                canvas.drawBitmap(tmp, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
                tmp.recycle()
            }
        }
    }

    /** w*h 바이트 마스크(0..255)를 선택 영역에 합칩니다 (자동 선택). */
    fun applyMask(mask: ByteArray, op: SelOp) {
        val tmp = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val rb = tmp.rowBytes
        val buf = ByteBuffer.allocate(rb * height)
        if (rb == width) buf.put(mask, 0, width * height)
        else for (y in 0 until height) {
            buf.position(y * rb)
            buf.put(mask, y * width, width)
        }
        buf.rewind()
        tmp.copyPixelsFromBuffer(buf)
        when (op) {
            SelOp.REPLACE -> {
                clear()
                canvas.drawBitmap(tmp, 0f, 0f, null)
            }
            SelOp.ADD -> canvas.drawBitmap(tmp, 0f, 0f, null)
            SelOp.SUBTRACT -> canvas.drawBitmap(tmp, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            SelOp.INTERSECT -> canvas.drawBitmap(tmp, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        }
        tmp.recycle()
    }

    fun invert() {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
            color = 0xFF000000.toInt()
            xfermode = PorterDuffXfermode(PorterDuff.Mode.XOR)
        })
    }

    /** 선택 영역 자체를 변형 (자유 변형 확정 시). */
    fun transform(m: Matrix) {
        val src = bitmap.copy(Bitmap.Config.ALPHA_8, false)
        clear()
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        src.recycle()
    }

    /** 빈틈없이 채운 w*h 바이트 (행 패딩 제거). */
    fun toBuffer(): ByteBuffer {
        val raw = ByteBuffer.allocate(bitmap.rowBytes * height)
        bitmap.copyPixelsToBuffer(raw)
        val out = GlUtil.byteBuffer(width * height)
        val rb = bitmap.rowBytes
        if (rb == width) {
            raw.rewind(); out.put(raw)
        } else {
            val arr = raw.array()
            for (y in 0 until height) out.put(arr, y * rb, width)
        }
        out.rewind()
        return out
    }

    fun fromBuffer(buf: ByteBuffer) {
        val rb = bitmap.rowBytes
        val raw = ByteBuffer.allocate(rb * height)
        val src = buf.duplicate()
        src.rewind()
        if (rb == width) {
            raw.put(src)
        } else {
            val row = ByteArray(width)
            for (y in 0 until height) {
                src.get(row)
                raw.position(y * rb)
                raw.put(row)
            }
        }
        raw.rewind()
        bitmap.copyPixelsFromBuffer(raw)
    }

    /** 0이 아닌 픽셀의 경계. 없으면 null. */
    fun bounds(): IRect? = boundsOf(toBuffer(), width, height)

    companion object {
        fun boundsOf(buf: ByteBuffer, w: Int, h: Int): IRect? {
            var minX = w; var minY = h; var maxX = -1; var maxY = -1
            val b = buf.duplicate()
            for (y in 0 until h) {
                val row = y * w
                var any = false
                for (x in 0 until w) {
                    if (b.get(row + x).toInt() != 0) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        any = true
                    }
                }
                if (any) {
                    if (y < minY) minY = y
                    maxY = y
                }
            }
            return if (maxX < 0) null else IRect(minX, minY, maxX - minX + 1, maxY - minY + 1)
        }

        /** 실행취소용 RLE: [w(4)][h(4)] 다음 (값, 개수-1) 쌍. */
        fun encode(buf: ByteBuffer, w: Int, h: Int): ByteArray {
            val out = ByteArrayOutputStream(64 * 1024)
            fun int(v: Int) {
                out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v)
            }
            int(w); int(h)
            val b = buf.duplicate()
            b.rewind()
            val n = w * h
            var i = 0
            while (i < n) {
                val v = b.get(i)
                var run = 1
                while (run < 256 && i + run < n && b.get(i + run) == v) run++
                out.write(v.toInt() and 0xFF)
                out.write(run - 1)
                i += run
            }
            return out.toByteArray()
        }

        fun decode(data: ByteArray): Pair<Int, ByteBuffer> {
            fun int(o: Int) = ((data[o].toInt() and 0xFF) shl 24) or ((data[o + 1].toInt() and 0xFF) shl 16) or
                ((data[o + 2].toInt() and 0xFF) shl 8) or (data[o + 3].toInt() and 0xFF)
            val w = int(0)
            val h = int(4)
            val out = GlUtil.byteBuffer(w * h)
            var p = 8
            while (p + 1 < data.size) {
                val v = data[p]
                val run = (data[p + 1].toInt() and 0xFF) + 1
                for (k in 0 until run) out.put(v)
                p += 2
            }
            out.rewind()
            return w to out
        }

        fun shapePath(shape: SelShape, pts: FloatArray): Path {
            val p = Path()
            when (shape) {
                SelShape.RECT -> p.addRect(rectOf(pts), Path.Direction.CW)
                SelShape.ELLIPSE -> p.addOval(rectOf(pts), Path.Direction.CW)
                SelShape.WAND -> Unit
                SelShape.LASSO -> {
                    if (pts.size >= 2) {
                        p.moveTo(pts[0], pts[1])
                        var i = 2
                        while (i + 1 < pts.size) {
                            p.lineTo(pts[i], pts[i + 1]); i += 2
                        }
                        p.close()
                    }
                }
            }
            return p
        }

        private fun rectOf(pts: FloatArray) = RectF(
            minOf(pts[0], pts[2]), minOf(pts[1], pts[3]), maxOf(pts[0], pts[2]), maxOf(pts[1], pts[3])
        )
    }
}

/**
 * 선택 마스크(w*h 바이트, 0..255) 편집. 순수 계산이라 백그라운드 스레드에서 돌립니다.
 * 확장/축소는 챔퍼(3-4) 거리 변환으로 둥근 모서리를, 경계 흐리기는 상자 흐림 3번(≈ 가우시안)을 씁니다.
 */
object SelectionOps {
    fun apply(mask: ByteArray, w: Int, h: Int, kind: SelModify, px: Int): ByteArray = when (kind) {
        SelModify.GROW -> grow(mask, w, h, px)
        SelModify.SHRINK -> shrink(mask, w, h, px)
        SelModify.FEATHER -> feather(mask, w, h, px)
    }

    /**
     * 선택 경계를 따라 굵기 [px]의 띠 (0..255). [where] 0 = 바깥, 1 = 가운데, 2 = 안쪽.
     * 결과 경계를 [bounds]에 (l, t, r, b, 없으면 r < l)
     */
    fun border(mask: ByteArray, w: Int, h: Int, px: Int, where: Int, bounds: IntArray): ByteArray {
        val p = px.coerceAtLeast(1)
        val outer = when (where) {
            0 -> grow(mask, w, h, p)
            1 -> if (p / 2 > 0) grow(mask, w, h, p / 2) else mask
            else -> mask
        }
        val inner = when (where) {
            0 -> mask
            1 -> shrink(mask, w, h, p - p / 2)
            else -> shrink(mask, w, h, p)
        }
        val out = ByteArray(w * h)
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val v = minOf(outer[i].toInt() and 0xFF, 255 - (inner[i].toInt() and 0xFF))
            if (v <= 0) continue
            out[i] = v.toByte()
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        bounds[0] = l; bounds[1] = t; bounds[2] = r; bounds[3] = b
        return out
    }

    /** 선택 안(>= 128)에서 거리 (px × 3). inside = true면 반대로 선택 밖에서의 거리. */
    private fun distance(mask: ByteArray, w: Int, h: Int, fromOutside: Boolean): IntArray {
        val inf = Int.MAX_VALUE / 2
        val d = IntArray(w * h)
        for (i in d.indices) {
            val sel = (mask[i].toInt() and 0xFF) >= 128
            d[i] = if (sel != fromOutside) 0 else inf
        }
        // 앞으로: 왼쪽, 왼쪽 위, 위, 오른쪽 위
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                var v = d[i]
                if (v == 0) continue
                if (x > 0) v = minOf(v, d[i - 1] + 3)
                if (y > 0) {
                    val up = i - w
                    v = minOf(v, d[up] + 3)
                    if (x > 0) v = minOf(v, d[up - 1] + 4)
                    if (x < w - 1) v = minOf(v, d[up + 1] + 4)
                }
                d[i] = v
            }
        }
        // 뒤로: 오른쪽, 오른쪽 아래, 아래, 왼쪽 아래
        for (y in h - 1 downTo 0) {
            val row = y * w
            for (x in w - 1 downTo 0) {
                val i = row + x
                var v = d[i]
                if (v == 0) continue
                if (x < w - 1) v = minOf(v, d[i + 1] + 3)
                if (y < h - 1) {
                    val dn = i + w
                    v = minOf(v, d[dn] + 3)
                    if (x < w - 1) v = minOf(v, d[dn + 1] + 4)
                    if (x > 0) v = minOf(v, d[dn - 1] + 4)
                }
                d[i] = v
            }
        }
        return d
    }

    private fun grow(mask: ByteArray, w: Int, h: Int, px: Int): ByteArray {
        val d = distance(mask, w, h, fromOutside = false)
        val out = ByteArray(w * h)
        for (i in out.indices) {
            // 붙어 있는 픽셀 = 거리 1. 거리 px까지 채우고 그다음 1px은 부드럽게.
            val add = ((px + 1f - d[i] / 3f).coerceIn(0f, 1f) * 255f).toInt()
            out[i] = maxOf(mask[i].toInt() and 0xFF, add).toByte()
        }
        return out
    }

    private fun shrink(mask: ByteArray, w: Int, h: Int, px: Int): ByteArray {
        val d = distance(mask, w, h, fromOutside = true)
        val out = ByteArray(w * h)
        for (i in out.indices) {
            val keep = ((d[i] / 3f - px).coerceIn(0f, 1f) * 255f).toInt()
            out[i] = minOf(mask[i].toInt() and 0xFF, keep).toByte()
        }
        return out
    }

    private fun feather(mask: ByteArray, w: Int, h: Int, px: Int): ByteArray {
        val r = maxOf(1, (px + 1) / 2)
        var a = IntArray(w * h) { mask[it].toInt() and 0xFF }
        var b = IntArray(w * h)
        repeat(3) {
            boxH(a, b, w, h, r)
            boxV(b, a, w, h, r)
        }
        return ByteArray(w * h) { a[it].coerceIn(0, 255).toByte() }
    }

    /** 가로 상자 흐림. 캔버스 밖은 0 (선택 안 됨). */
    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val n = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (x in 0..minOf(r, w - 1)) sum += src[row + x]
            for (x in 0 until w) {
                dst[row + x] = (sum + n / 2) / n
                val add = x + r + 1
                val sub = x - r
                if (add < w) sum += src[row + add]
                if (sub >= 0) sum -= src[row + sub]
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val n = 2 * r + 1
        for (x in 0 until w) {
            var sum = 0
            for (y in 0..minOf(r, h - 1)) sum += src[y * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = (sum + n / 2) / n
                val add = y + r + 1
                val sub = y - r
                if (add < h) sum += src[add * w + x]
                if (sub >= 0) sum -= src[sub * w + x]
            }
        }
    }
}
