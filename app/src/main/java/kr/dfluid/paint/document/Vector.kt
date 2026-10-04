package kr.dfluid.paint.document

import kr.dfluid.paint.brush.Brush
import kr.dfluid.paint.brush.StrokeBuilder
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 벡터 레이어의 선 하나. 그릴 때 만든 스탬프(StrokeBuilder 형식)와 브러시·색을 그대로 기억해
 * 지우거나 옮긴 뒤 같은 모양으로 다시 그릴 수 있습니다. [fill] = 도형 도구의 채우기 다각형 (x, y …).
 */
class VStroke(val stamps: FloatArray, val brush: Brush, val color: Int, val fill: FloatArray? = null) {
    /** 경계 (캔버스 px, 반지름 포함): l, t, r, b */
    val bounds: FloatArray = run {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i + 2 < stamps.size) {
            val rad = stamps[i + 2] + 2f
            l = min(l, stamps[i] - rad); t = min(t, stamps[i + 1] - rad)
            r = max(r, stamps[i] + rad); b = max(b, stamps[i + 1] + rad)
            i += StrokeBuilder.FLOATS
        }
        if (fill != null) {
            var k = 0
            while (k + 1 < fill.size) {
                l = min(l, fill[k] - 1); t = min(t, fill[k + 1] - 1)
                r = max(r, fill[k] + 1); b = max(b, fill[k + 1] + 1)
                k += 2
            }
        }
        floatArrayOf(l, t, r, b)
    }

    /** 지우개 스탬프 (x, y, 반지름)가 이 선에 닿는지. 채우기 도형은 경계 안이면 닿은 것으로 봅니다. */
    fun hits(x: Float, y: Float, r: Float): Boolean {
        if (x + r < bounds[0] || x - r > bounds[2] || y + r < bounds[1] || y - r > bounds[3]) return false
        if (fill != null) return true
        var i = 0
        while (i + 2 < stamps.size) {
            val dx = stamps[i] - x
            val dy = stamps[i + 1] - y
            // 스탬프 반지름의 절반 이내 (가장자리 흐린 부분만 스쳐서는 지워지지 않게)
            val reach = r + stamps[i + 2] * 0.5f
            if (dx * dx + dy * dy <= reach * reach) return true
            i += StrokeBuilder.FLOATS
        }
        return false
    }

    /** m = [a, b, c, d, tx, ty] : 새 좌표 = (a·x + c·y + tx, b·x + d·y + ty). 굵기는 넓이 배율의 제곱근만큼. */
    fun transformed(m: FloatArray): VStroke {
        val scale = sqrt(kotlin.math.abs(m[0] * m[3] - m[1] * m[2]))
        val rot = atan2(m[1], m[0])
        val out = stamps.copyOf()
        var i = 0
        while (i + 5 < out.size) {
            val x = stamps[i]; val y = stamps[i + 1]
            out[i] = m[0] * x + m[2] * y + m[4]
            out[i + 1] = m[1] * x + m[3] * y + m[5]
            out[i + 2] = stamps[i + 2] * scale
            out[i + 3] = stamps[i + 3] + rot
            i += StrokeBuilder.FLOATS
        }
        val f = fill?.let { src ->
            FloatArray(src.size).also { dst ->
                var k = 0
                while (k + 1 < src.size) {
                    dst[k] = m[0] * src[k] + m[2] * src[k + 1] + m[4]
                    dst[k + 1] = m[1] * src[k] + m[3] * src[k + 1] + m[5]
                    k += 2
                }
            }
        }
        return VStroke(out, brush, color, f)
    }

    /** 선 굵기만 [factor]배 (위치는 그대로) */
    fun widthScaled(factor: Float): VStroke {
        val out = stamps.copyOf()
        var i = 0
        while (i + 2 < out.size) {
            out[i + 2] = stamps[i + 2] * factor
            i += StrokeBuilder.FLOATS
        }
        return VStroke(out, brush, color, fill)
    }

    companion object {
        /** 벡터 레이어 저장 형식: [개수] 다음 선마다 (브러시 JSON, 색, 스탬프 수·값, 채우기 수·값). */
        fun encode(list: List<VStroke>): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(1) // 형식 버전
                out.writeInt(list.size)
                for (s in list) {
                    out.writeUTF(s.brush.toJson().toString())
                    out.writeInt(s.color)
                    out.writeInt(s.stamps.size)
                    for (v in s.stamps) out.writeFloat(v)
                    val f = s.fill
                    out.writeInt(f?.size ?: 0)
                    f?.forEach { out.writeFloat(it) }
                }
            }
            return bytes.toByteArray()
        }

        fun decode(data: ByteArray): List<VStroke> {
            val out = ArrayList<VStroke>()
            DataInputStream(ByteArrayInputStream(data)).use { inp ->
                inp.readInt()
                val n = inp.readInt()
                repeat(n) {
                    val brush = Brush.fromJson(JSONObject(inp.readUTF()))
                    val color = inp.readInt()
                    val st = FloatArray(inp.readInt()) { inp.readFloat() }
                    val fn = inp.readInt()
                    val fill = if (fn > 0) FloatArray(fn) { inp.readFloat() } else null
                    if (brush != null) out.add(VStroke(st, brush, color, fill))
                }
            }
            return out
        }
    }
}

/** 벡터 선 목록 변경 (그리기·지우기·변형). 픽셀 변경(TilesCommand)과 함께 CompoundCommand로 묶어 씁니다. */
class VectorCommand(private val layerId: Int, private val before: List<VStroke>, private val after: List<VStroke>) : HistoryCommand {
    override val bytes: Long get() = (before.sumOf { it.stamps.size } + after.sumOf { it.stamps.size }) * 4L
    override fun undo(s: LayerStore) = s.setVector(layerId, before)
    override fun redo(s: LayerStore) = s.setVector(layerId, after)
}
