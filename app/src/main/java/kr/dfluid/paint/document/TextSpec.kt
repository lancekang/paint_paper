package kr.dfluid.paint.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kr.dfluid.paint.engine.CpuTiles
import kr.dfluid.paint.engine.GlUtil
import kr.dfluid.paint.engine.TILE
import kr.dfluid.paint.engine.TILE_BYTES
import kr.dfluid.paint.engine.TileMath
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * 텍스트 레이어 내용. 레이어 픽셀은 이 값으로 그린 결과이고, 텍스트 도구로 다시 고칠 수 있습니다.
 * (x, y) = 글 상자의 왼쪽 위 (캔버스 px). 세로쓰기는 첫 줄이 오른쪽 끝, 줄은 왼쪽으로 이어집니다.
 */
data class TextSpec(
    val text: String,
    val x: Float,
    val y: Float,
    val size: Float,
    val color: Int,
    val font: Int = FONT_SANS,
    val vertical: Boolean = false,
    val lineSpacing: Float = 1.25f,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("text", text).put("x", x.toDouble()).put("y", y.toDouble()).put("size", size.toDouble())
        .put("color", color).put("font", font).put("vertical", vertical).put("lineSpacing", lineSpacing.toDouble())

    private fun paint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = this@TextSpec.color
        textSize = size
        typeface = when (font) {
            FONT_SERIF -> Typeface.SERIF
            FONT_BOLD -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            else -> Typeface.SANS_SERIF
        }
    }

    private val lines: List<String> get() = text.split('\n')

    /** 글 상자 크기 (캔버스 px, 여유 포함) */
    fun bounds(): RectF {
        val p = paint()
        val lineH = size * lineSpacing
        val ls = lines
        return if (!vertical) {
            val w = ls.maxOf { p.measureText(it) }
            RectF(x, y, x + max(w, size * 0.5f) + PAD * 2, y + lineH * ls.size + PAD * 2)
        } else {
            val maxChars = ls.maxOf { it.codePointCount(0, it.length) }
            RectF(x, y, x + lineH * ls.size + PAD * 2, y + size * 1.05f * max(1, maxChars) + PAD * 2)
        }
    }

    /** 글 상자를 그린 비트맵 (프리멀티플라이드 ARGB). */
    private fun drawBlock(): Bitmap? {
        val b = bounds()
        val w = ceil(b.width()).toInt()
        val h = ceil(b.height()).toInt()
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = paint()
        val fm = p.fontMetrics
        val lineH = size * lineSpacing
        val ls = lines
        if (!vertical) {
            ls.forEachIndexed { i, line ->
                // 줄 높이 안에서 글자 높이를 가운데로
                val base = PAD + i * lineH + (lineH - (fm.descent - fm.ascent)) / 2f - fm.ascent
                c.drawText(line, PAD, base, p)
            }
        } else {
            p.textAlign = Paint.Align.CENTER
            val step = size * 1.05f
            ls.forEachIndexed { i, line ->
                val cx = w - PAD - lineH * i - lineH / 2f
                var k = 0
                var off = 0
                while (off < line.length) {
                    val cp = line.codePointAt(off)
                    val ch = String(Character.toChars(cp))
                    val base = PAD + k * step + (step - (fm.descent - fm.ascent)) / 2f - fm.ascent
                    c.drawText(ch, cx, base, p)
                    off += Character.charCount(cp)
                    k++
                }
            }
        }
        return bmp
    }

    /** 캔버스 [cw]×[ch] 의 타일로 그립니다 (비어 있지 않은 타일만). */
    fun render(cw: Int, ch: Int): CpuTiles {
        val out = HashMap<Int, ByteBuffer>()
        val block = drawBlock() ?: return out
        val l = floor(x).toInt()
        val t = floor(y).toInt()
        val cols = TileMath.cols(cw)
        val rows = TileMath.rows(ch)
        val tx0 = max(0, l / TILE)
        val ty0 = max(0, t / TILE)
        val tx1 = minOf(cols - 1, (l + block.width) / TILE)
        val ty1 = minOf(rows - 1, (t + block.height) / TILE)
        val tile = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        val tc = Canvas(tile)
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            tile.eraseColor(0)
            tc.drawBitmap(block, x - tx * TILE, y - ty * TILE, null)
            // 캔버스 밖(오른쪽·아래 끝 타일의 남는 부분)은 비웁니다.
            val vw = cw - tx * TILE
            val vh = ch - ty * TILE
            if (vw < TILE) tc.clipOutRectCompat(vw, 0, TILE, TILE)
            if (vh < TILE) tc.clipOutRectCompat(0, vh, TILE, TILE)
            val buf = GlUtil.byteBuffer(TILE_BYTES)
            tile.copyPixelsToBuffer(buf)
            buf.rewind()
            if (!TileMath.isEmpty(buf)) out[ty * cols + tx] = buf
        }
        tile.recycle()
        block.recycle()
        return out
    }

    private fun Canvas.clipOutRectCompat(l: Int, t: Int, r: Int, b: Int) {
        drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        })
    }

    companion object {
        const val FONT_SANS = 0
        const val FONT_SERIF = 1
        const val FONT_BOLD = 2
        val FONT_LABELS = listOf("고딕", "명조", "굵은 고딕")
        private const val PAD = 4f

        fun fromJson(o: JSONObject?): TextSpec? {
            if (o == null) return null
            return TextSpec(
                text = o.optString("text", ""),
                x = o.optDouble("x", 0.0).toFloat(),
                y = o.optDouble("y", 0.0).toFloat(),
                size = o.optDouble("size", 48.0).toFloat().coerceIn(2f, 2000f),
                color = o.optInt("color", 0xFF000000.toInt()),
                font = o.optInt("font", FONT_SANS),
                vertical = o.optBoolean("vertical", false),
                lineSpacing = o.optDouble("lineSpacing", 1.25).toFloat().coerceIn(0.5f, 4f),
            )
        }
    }
}
