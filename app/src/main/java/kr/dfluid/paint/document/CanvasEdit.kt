package kr.dfluid.paint.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kr.dfluid.paint.engine.CpuTiles

/**
 * 캔버스 전체를 바꾸는 편집 (모든 레이어·마스크에 같은 변환).
 * 계산은 CPU(Bitmap)에서 레이어 하나씩 합니다. 픽셀은 프리멀티플라이드 그대로 다룹니다.
 */
sealed class CanvasEdit {
    /** 이미지 크기: 그림을 새 크기로 늘이거나 줄임 */
    data class Resample(val width: Int, val height: Int) : CanvasEdit()

    /** 캔버스 크기: 그림 크기는 그대로, 둘레를 넓히거나 자름. ax/ay = 기준 위치 (0 = 왼쪽/위, 0.5 = 가운데, 1 = 오른쪽/아래) */
    data class Resize(val width: Int, val height: Int, val ax: Float, val ay: Float) : CanvasEdit()

    /** 시계 방향 90° × quarter (1, 2, 3) */
    data class Rotate(val quarter: Int) : CanvasEdit()

    data class Flip(val horizontal: Boolean) : CanvasEdit()

    val label: String
        get() = when (this) {
            is Resample -> "이미지 크기 ${width}×$height"
            is Resize -> "캔버스 크기 ${width}×$height"
            is Rotate -> when (quarter) { 1 -> "시계 방향 90° 회전"; 2 -> "180° 회전"; else -> "반시계 방향 90° 회전" }
            is Flip -> if (horizontal) "캔버스 좌우 반전" else "캔버스 상하 반전"
        }

    /** 새 캔버스 크기 */
    fun newSize(w: Int, h: Int): Pair<Int, Int> = when (this) {
        is Resample -> width to height
        is Resize -> width to height
        is Rotate -> if (quarter % 2 == 1) h to w else w to h
        is Flip -> w to h
    }

    companion object {
        /** 문서 전체에 적용한 새 문서. 레이어 트리·속성·활성 레이어는 그대로입니다. */
        fun apply(data: DocumentData, e: CanvasEdit): DocumentData {
            val (nw, nh) = e.newSize(data.width, data.height)
            fun conv(t: CpuTiles?): CpuTiles? = t?.let { transform(it, data.width, data.height, nw, nh, e) }
            // 텍스트 레이어: 캔버스 크기 변경(평행이동)은 위치만 옮기고, 그 밖에는 픽셀로 굳힙니다.
            fun props(p: LayerProps): LayerProps {
                val t = p.text ?: return p
                return if (e is Resize) p.copy(text = t.copy(x = t.x + Math.round((nw - data.width) * e.ax), y = t.y + Math.round((nh - data.height) * e.ay)))
                else p.copy(text = null)
            }
            val nodes = data.nodes.map { n -> NodeData(n.id, n.kind, props(n.props), n.parentId, conv(n.tiles), conv(n.maskTiles)) }
            return DocumentData(nw, nh, data.activeId, nodes)
        }

        private fun transform(tiles: CpuTiles, w: Int, h: Int, nw: Int, nh: Int, e: CanvasEdit): CpuTiles {
            if (tiles.isEmpty()) return tiles
            var src = ProjectIO.assemble(w, h, tiles)
            if (e is Resample) src = shrinkHalves(src, nw, nh)
            val dst = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
            val m = Matrix()
            when (e) {
                is Resample -> m.setScale(nw.toFloat() / src.width, nh.toFloat() / src.height)
                is Resize -> m.setTranslate(Math.round((nw - w) * e.ax).toFloat(), Math.round((nh - h) * e.ay).toFloat())
                is Rotate -> {
                    m.setRotate(90f * e.quarter)
                    // 회전 뒤 왼쪽 위가 (0, 0)이 되도록
                    when (e.quarter) {
                        1 -> m.postTranslate(h.toFloat(), 0f)
                        2 -> m.postTranslate(w.toFloat(), h.toFloat())
                        else -> m.postTranslate(0f, w.toFloat())
                    }
                }
                is Flip -> if (e.horizontal) m.setScale(-1f, 1f, w / 2f, 0f) else m.setScale(1f, -1f, 0f, h / 2f)
            }
            // 크기 변경만 보간, 나머지는 픽셀을 그대로 옮김
            val paint = if (e is Resample) Paint(Paint.FILTER_BITMAP_FLAG) else null
            Canvas(dst).drawBitmap(src, m, paint)
            src.recycle()
            val out = ProjectIO.split(dst, nw, nh)
            dst.recycle()
            return out
        }

        /** 많이 줄일 때 쌍선형 보간의 계단 현상을 줄이려고 절반씩 먼저 줄입니다. */
        private fun shrinkHalves(src: Bitmap, nw: Int, nh: Int): Bitmap {
            var b = src
            while (b.width / 2 >= nw && b.height / 2 >= nh && b.width >= 2 && b.height >= 2) {
                val half = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
                if (b !== half) b.recycle()
                b = half
            }
            return b
        }
    }
}
