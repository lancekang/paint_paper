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

enum class SelShape { RECT, ELLIPSE, LASSO }

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
