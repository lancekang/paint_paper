package kr.dfluid.paint.brush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** 필압 곡선: 제어점을 지나는 단조 3차 보간(Fritsch–Carlson). 곡선이 출렁여 역전되지 않습니다. */
object PressureCurve {
    fun lut(points: FloatArray, size: Int = 256): FloatArray {
        val n = points.size / 2
        val xs = FloatArray(n) { points[it * 2] }
        val ys = FloatArray(n) { points[it * 2 + 1] }
        val out = FloatArray(size)
        if (n < 2) {
            for (i in 0 until size) out[i] = i / (size - 1f)
            return out
        }
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / max(1e-5f, xs[it + 1] - xs[it]) }
        val m = FloatArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) {
                m[i] = 0f; m[i + 1] = 0f
            } else {
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / kotlin.math.sqrt(s)
                    m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]
                }
            }
        }
        for (k in 0 until size) {
            val x = k / (size - 1f)
            var i = 0
            while (i < n - 2 && x > xs[i + 1]) i++
            val y = when {
                x <= xs[0] -> ys[0]
                x >= xs[n - 1] -> ys[n - 1]
                else -> {
                    val h = max(1e-5f, xs[i + 1] - xs[i])
                    val t = ((x - xs[i]) / h).coerceIn(0f, 1f)
                    val t2 = t * t; val t3 = t2 * t
                    (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] +
                        (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1]
                }
            }
            out[k] = y.coerceIn(0f, 1f)
        }
        return out
    }
}

/** 브러시 팁 이미지. R8, 정사각형. */
class TipImage(val id: String, val size: Int, val pixels: ByteBuffer)

/**
 * 도구별 브러시 프리셋 목록 + 팁 이미지. UI 스레드 전용.
 * filesDir/brushes.json, filesDir/tips/<id>.png
 */
class BrushLibrary(context: Context) {
    private val dir = context.applicationContext.filesDir
    private val file = File(dir, "brushes.json")
    private val tipDir = File(dir, "tips").apply { mkdirs() }

    val presets: Map<Tool, MutableList<Brush>> = Tool.entries.filter { it.isBrush }.associateWith { ArrayList<Brush>() }
    private val activeId = HashMap<Tool, String>()
    private val tipCache = HashMap<String, TipImage>()

    /** 저장본 판 (기본 보조 도구를 나중에 추가할 때 한 번만 넣으려고). init보다 먼저 선언해야 load()가 바꾼 값이 남음 */
    private var rev = 0

    init {
        load()
    }

    fun list(tool: Tool): MutableList<Brush> = presets[tool] ?: ArrayList()

    fun active(tool: Tool): Brush? {
        val l = presets[tool] ?: return null
        return l.firstOrNull { it.id == activeId[tool] } ?: l.firstOrNull()
    }

    fun setActive(tool: Tool, id: String) {
        activeId[tool] = id
    }

    fun add(tool: Tool, b: Brush) {
        list(tool).add(b)
        activeId[tool] = b.id
        save()
    }

    fun remove(tool: Tool, id: String): Boolean {
        val l = list(tool)
        if (l.size <= 1) return false
        l.removeAll { it.id == id }
        if (activeId[tool] == id) activeId[tool] = l.first().id
        save()
        return true
    }

    fun move(tool: Tool, id: String, delta: Int) {
        val l = list(tool)
        val i = l.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in l.indices) return
        val b = l.removeAt(i)
        l.add(j, b)
        save()
    }

    fun resetTool(tool: Tool) {
        val l = list(tool)
        l.clear()
        l.addAll(BrushPresets.defaults(tool))
        activeId[tool] = l.first().id
        save()
    }

    fun load() {
        presets.values.forEach { it.clear() }
        activeId.clear()
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                val arr = o.optJSONArray("brushes") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val b = Brush.fromJson(arr.getJSONObject(i)) ?: continue
                    presets[b.tool]?.add(b)
                }
                rev = o.optInt("rev", 0)
                val act = o.optJSONObject("active")
                if (act != null) for (k in act.keys()) {
                    val t = Tool.entries.firstOrNull { it.name == k } ?: continue
                    activeId[t] = act.getString(k)
                }
            }
        } catch (_: Exception) {
        }
        presets.forEach { (tool, l) ->
            if (l.isEmpty()) l.addAll(BrushPresets.defaults(tool))
        }
        // 예전 저장본에는 유동화 보조 도구가 없으므로 한 번만 넣어 줌
        if (rev < 1) {
            presets[Tool.BLEND]?.let { l -> if (l.none { it.mixMode in Brush.MIX_PUSH..Brush.MIX_PINCH }) l.addAll(BrushPresets.liquifyPresets()) }
            rev = 1
            save()
        }
        if (rev < 2) {
            presets[Tool.BLEND]?.let { l -> if (l.none { it.mixMode == Brush.MIX_PAINT }) l.addAll(0, BrushPresets.paintPresets()) }
            rev = 2
            save()
        }
    }


    fun save() {
        val arr = JSONArray()
        presets.values.forEach { l -> l.forEach { arr.put(it.toJson()) } }
        val act = JSONObject()
        presets.keys.forEach { t -> active(t)?.let { act.put(t.name, it.id) } }
        val tmp = File(dir, "brushes.json.tmp")
        tmp.writeText(JSONObject().put("brushes", arr).put("active", act).put("rev", rev).toString())
        if (!tmp.renameTo(file)) {
            file.delete(); tmp.renameTo(file)
        }
    }

    // ---- 팁 이미지 ----

    /**
     * 이미지에서 팁을 만듭니다. 투명 배경 PNG면 알파를, 불투명 이미지면 어두운 곳을 잉크로 봅니다.
     * invert = true 면 반대로.
     */
    fun importTip(input: InputStream, invert: Boolean): String? {
        val src = BitmapFactory.decodeStream(input) ?: return null
        try {
            val size = TIP_SIZE
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val s = size.toFloat() / max(src.width, src.height)
            val w = (src.width * s).roundToInt().coerceAtLeast(1)
            val h = (src.height * s).roundToInt().coerceAtLeast(1)
            val dst = Rect((size - w) / 2, (size - h) / 2, (size - w) / 2 + w, (size - h) / 2 + h)
            Canvas(bmp).drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            val px = IntArray(size * size)
            bmp.getPixels(px, 0, size, 0, 0, size, size)
            var anyTransparent = false
            for (c in px) if (Color.alpha(c) < 250) { anyTransparent = true; break }
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val gray = IntArray(size * size)
            for (i in px.indices) {
                val c = px[i]
                val a = Color.alpha(c) / 255f
                val lum = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
                var v = if (anyTransparent) a else 1f - lum
                if (invert) v = 1f - v
                val g = (v * 255).roundToInt().coerceIn(0, 255)
                gray[i] = Color.argb(255, g, g, g)
            }
            out.setPixels(gray, 0, size, 0, 0, size, size)
            val id = UUID.randomUUID().toString()
            File(tipDir, "$id.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out.recycle(); bmp.recycle()
            return id
        } finally {
            src.recycle()
        }
    }

    /** 보조 도구 파일(.dfbrush): JSON {format, version, brush, tip(PNG base64 또는 없음)} */
    fun exportBrush(b: Brush): ByteArray {
        val o = org.json.JSONObject()
            .put("format", "dfbrush")
            .put("version", 1)
            .put("brush", b.toJson())
        b.tipId?.let { id ->
            val f = File(tipDir, "$id.png")
            if (f.exists()) o.put("tip", android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
        }
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    /** [exportBrush] 파일을 읽어 그 도구의 보조 도구로 추가합니다. 새 id를 붙이고 팁도 새 id로 저장. 실패하면 null. */
    fun importBrush(bytes: ByteArray): Brush? {
        val o = try { org.json.JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { return null }
        if (o.optString("format") != "dfbrush") return null
        val bj = o.optJSONObject("brush") ?: return null
        var b = Brush.fromJson(bj) ?: return null
        var tipId: String? = null
        o.optString("tip", "").takeIf { it.isNotEmpty() }?.let { s ->
            try {
                val png = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
                // 그림인지 확인한 뒤 저장
                val bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
                if (bmp != null) {
                    bmp.recycle()
                    val id = UUID.randomUUID().toString()
                    File(tipDir, "$id.png").writeBytes(png)
                    tipId = id
                }
            } catch (e: Exception) {
            }
        }
        b = b.copy(id = UUID.randomUUID().toString(), tipId = tipId)
        add(b.tool, b)
        setActive(b.tool, b.id)
        save()
        return b
    }

    /** UI 스레드와 GL 스레드(벡터 선 다시 그리기)에서 불림 */
    @Synchronized
    fun tip(id: String): TipImage? {
        tipCache[id]?.let { return it }
        val f = File(tipDir, "$id.png")
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        val size = bmp.width
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, minOf(size, bmp.height))
        bmp.recycle()
        val buf = ByteBuffer.allocateDirect(size * size).order(ByteOrder.nativeOrder())
        for (c in px) buf.put(Color.red(c).toByte())
        buf.rewind()
        return TipImage(id, size, buf).also { tipCache[id] = it }
    }

    /** 팁 미리보기용 Bitmap (흰 바탕 검은 잉크). */
    fun tipPreview(id: String): Bitmap? {
        val t = tip(id) ?: return null
        val px = IntArray(t.size * t.size)
        val b = t.pixels.duplicate(); b.rewind()
        for (i in px.indices) {
            val v = 255 - (b.get().toInt() and 0xFF)
            px[i] = Color.argb(255, v, v, v)
        }
        return Bitmap.createBitmap(px, t.size, t.size, Bitmap.Config.ARGB_8888)
    }

    companion object {
        const val TIP_SIZE = 128
    }
}
