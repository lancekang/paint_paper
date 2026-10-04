package kr.dfluid.paint.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import kr.dfluid.paint.engine.CpuTiles
import kr.dfluid.paint.engine.GlUtil
import kr.dfluid.paint.engine.TILE
import kr.dfluid.paint.engine.TILE_BYTES
import kr.dfluid.paint.engine.TileMath
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * .dfp 파일 = ZIP
 *   document.json  문서 정보 + 노드 트리 (부모 먼저, 형제는 아래 → 위)
 *   layers/<id>.png 래스터 레이어 (캔버스 크기, 투명 포함)
 *   layers/<id>_mask.png 레이어 마스크 (알파 = 가리는 정도)
 *   thumbnail.png  합성 결과, 긴 변 256px
 *
 * 버전 1(평면 레이어 목록)도 읽을 수 있습니다. 모든 함수는 백그라운드 스레드에서 호출합니다.
 */
object ProjectIO {
    const val FORMAT = "dfpaint"
    /** 경계 효과 최대 굵기 (px) */
    const val MAX_BORDER = 40f
    const val VERSION = 2
    private const val THUMB = 256

    fun writeDfp(out: OutputStream, data: DocumentData, composite: ByteBuffer?) {
        ZipOutputStream(BufferedOutputStream(out, 1 shl 16)).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            val nodes = JSONArray()
            for (n in data.nodes) {
                val o = JSONObject()
                    .put("id", n.id)
                    .put("parent", n.parentId)
                    .put("kind", n.kind.name)
                    .put("name", n.props.name)
                    .put("opacity", n.props.opacity.toDouble())
                    .put("blend", n.props.blend.name)
                    .put("visible", n.props.visible)
                    .put("alphaLock", n.props.alphaLock)
                    .put("clip", n.props.clip)
                    .put("expanded", n.props.expanded)
                    .put("reference", n.props.reference)
                n.props.text?.let { o.put("text", it.toJson()) }
                if (n.props.quickMask) o.put("quickMask", true)
                if (n.props.borderWidth > 0f) o.put("borderWidth", n.props.borderWidth.toDouble()).put("borderColor", n.props.borderColor)
                if (n.props.vector) o.put("vector", true).put("vectorFile", "layers/${n.id}.vec")
                if (n.props.layerColorOn) o.put("layerColorOn", true).put("layerColor", n.props.layerColor)
                if (n.props.toneCell > 0f) o.put("toneCell", n.props.toneCell.toDouble()).put("toneAngle", n.props.toneAngle.toDouble()).put("toneColor", n.props.toneColor)
                if (n.kind == NodeKind.RASTER) o.put("file", "layers/${n.id}.png")
                if (n.kind == NodeKind.RASTER && n.props.mask) {
                    o.put("mask", true).put("maskEnabled", n.props.maskEnabled).put("maskFile", "layers/${n.id}_mask.png")
                }
                nodes.put(o)
            }
            val json = JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("width", data.width)
                .put("height", data.height)
                .put("active", data.activeId)
                .put("nodes", nodes)
            zip.putNextEntry(ZipEntry("document.json"))
            zip.write(json.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            for (n in data.nodes) {
                if (n.kind != NodeKind.RASTER) continue
                zip.putNextEntry(ZipEntry("layers/${n.id}.png"))
                val bmp = assemble(data.width, data.height, n.tiles ?: emptyMap())
                bmp.compress(Bitmap.CompressFormat.PNG, 100, zip)
                bmp.recycle()
                zip.closeEntry()
                if (n.props.vector) {
                    zip.putNextEntry(ZipEntry("layers/${n.id}.vec"))
                    zip.write(VStroke.encode(n.vector ?: emptyList()))
                    zip.closeEntry()
                }
                if (n.props.mask) {
                    zip.putNextEntry(ZipEntry("layers/${n.id}_mask.png"))
                    val mb = assemble(data.width, data.height, n.maskTiles ?: emptyMap())
                    mb.compress(Bitmap.CompressFormat.PNG, 100, zip)
                    mb.recycle()
                    zip.closeEntry()
                }
            }

            if (composite != null) {
                val full = toBitmap(data.width, data.height, composite)
                val scale = THUMB.toFloat() / max(data.width, data.height)
                val tw = max(1, (data.width * scale).roundToInt())
                val th = max(1, (data.height * scale).roundToInt())
                val thumb = Bitmap.createScaledBitmap(full, tw, th, true)
                zip.putNextEntry(ZipEntry("thumbnail.png"))
                thumb.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
                if (thumb !== full) thumb.recycle()
                full.recycle()
            }
        }
    }

    fun readDfp(input: InputStream, maxTextureSize: Int): DocumentData {
        var json: JSONObject? = null
        val images = HashMap<String, Bitmap>()
        val vectors = HashMap<String, ByteArray>()
        try {
            ZipInputStream(BufferedInputStream(input, 1 shl 16)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.name == "document.json" -> {
                            val bytes = ByteArrayOutputStream()
                            zip.copyTo(bytes)
                            json = JSONObject(bytes.toString("UTF-8"))
                        }
                        entry.name.startsWith("layers/") && entry.name.endsWith(".vec") -> {
                            val bytes = ByteArrayOutputStream()
                            zip.copyTo(bytes)
                            vectors[entry.name] = bytes.toByteArray()
                        }
                        entry.name.startsWith("layers/") && entry.name.endsWith(".png") -> {
                            val bmp = decode(zip) ?: throw IOException("레이어 이미지를 읽을 수 없습니다: ${entry.name}")
                            images[entry.name] = bmp
                        }
                    }
                    zip.closeEntry()
                }
            }
            val doc = json ?: throw IOException("document.json이 없습니다. DFPaint 파일이 아닙니다.")
            if (doc.optString("format") != FORMAT) throw IOException("지원하지 않는 형식입니다.")
            val version = doc.optInt("version", 1)
            if (version > VERSION) throw IOException("더 새 버전의 앱에서 만든 파일입니다.")
            val w = doc.getInt("width")
            val h = doc.getInt("height")
            if (w <= 0 || h <= 0 || w > maxTextureSize || h > maxTextureSize) {
                throw IOException("이 기기에서 열 수 없는 크기입니다 (${w}x$h, 최대 $maxTextureSize).")
            }
            val nodes = ArrayList<NodeData>()
            var activeId: Int
            if (version == 1) {
                val arr = doc.getJSONArray("layers")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val bmp = images[o.optString("file", "layers/$i.png")]
                    nodes.add(NodeData(i + 1, NodeKind.RASTER, propsOf(o, "레이어 ${i + 1}"), ROOT_ID, bmp?.let { split(it, w, h) } ?: emptyMap()))
                }
                activeId = doc.optInt("activeLayer", nodes.lastIndex) + 1
            } else {
                val arr = doc.getJSONArray("nodes")
                val seen = HashSet<Int>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.getInt("id")
                    if (id <= 0 || !seen.add(id)) throw IOException("손상된 파일입니다 (노드 id).")
                    val parent = o.optInt("parent", ROOT_ID).let { if (it in seen) it else ROOT_ID }
                    val kind = if (o.optString("kind") == NodeKind.FOLDER.name) NodeKind.FOLDER else NodeKind.RASTER
                    val tiles = if (kind == NodeKind.RASTER) {
                        images[o.optString("file", "layers/$id.png")]?.let { split(it, w, h) } ?: emptyMap()
                    } else null
                    var props = propsOf(o, if (kind == NodeKind.FOLDER) "폴더" else "레이어")
                    if (kind != NodeKind.RASTER) props = props.copy(mask = false)
                    val maskTiles = if (props.mask) {
                        images[o.optString("maskFile", "layers/${id}_mask.png")]?.let { split(it, w, h) } ?: emptyMap()
                    } else null
                    val vec = if (props.vector) vectors[o.optString("vectorFile", "layers/$id.vec")]?.let {
                        try { VStroke.decode(it) } catch (e: Exception) { null }
                    } ?: emptyList() else null
                    nodes.add(NodeData(id, kind, props, parent, tiles, maskTiles, vec))
                }
                activeId = doc.optInt("active", nodes.firstOrNull { it.kind == NodeKind.RASTER }?.id ?: 0)
            }
            if (nodes.none { it.kind == NodeKind.RASTER }) {
                val id = (nodes.maxOfOrNull { it.id } ?: 0) + 1
                nodes.add(NodeData(id, NodeKind.RASTER, LayerProps("레이어 1"), ROOT_ID, emptyMap()))
                activeId = id
            }
            return DocumentData(w, h, activeId, nodes)
        } finally {
            images.values.forEach { it.recycle() }
        }
    }

    private fun propsOf(o: JSONObject, defaultName: String) = LayerProps(
        name = o.optString("name", defaultName),
        opacity = o.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
        blend = BlendMode.parse(o.optString("blend")),
        visible = o.optBoolean("visible", true),
        alphaLock = o.optBoolean("alphaLock", false),
        clip = o.optBoolean("clip", false),
        expanded = o.optBoolean("expanded", true),
        mask = o.optBoolean("mask", false),
        maskEnabled = o.optBoolean("maskEnabled", true),
        reference = o.optBoolean("reference", false),
        text = TextSpec.fromJson(o.optJSONObject("text")),
        quickMask = o.optBoolean("quickMask", false),
        borderWidth = o.optDouble("borderWidth", 0.0).toFloat().coerceIn(0f, MAX_BORDER),
        borderColor = o.optInt("borderColor", 0xFFFFFFFF.toInt()),
        toneCell = o.optDouble("toneCell", 0.0).toFloat().coerceIn(0f, 64f),
        toneAngle = o.optDouble("toneAngle", 45.0).toFloat(),
        toneColor = o.optInt("toneColor", 0xFF000000.toInt()),
        layerColorOn = o.optBoolean("layerColorOn", false),
        layerColor = o.optInt("layerColor", 0xFF3D8BFF.toInt()),
        vector = o.optBoolean("vector", false),
    )

    /** PNG/JPEG/WebP 이미지를 레이어 한 장짜리 새 문서로 엽니다. 너무 크면 줄입니다. */
    fun readImage(input: InputStream, maxTextureSize: Int): DocumentData {
        val src = decode(BufferedInputStream(input, 1 shl 16)) ?: throw IOException("이미지를 읽을 수 없습니다.")
        try {
            var w = src.width
            var h = src.height
            val limit = minOf(maxTextureSize, 8192)
            if (w > limit || h > limit) {
                val s = limit.toFloat() / max(w, h)
                w = max(1, (w * s).toInt())
                h = max(1, (h * s).toInt())
            }
            val scaled = if (w == src.width && h == src.height) src else {
                val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(dst).drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                dst
            }
            val tiles = split(scaled, w, h)
            if (scaled !== src) scaled.recycle()
            return DocumentData(w, h, 1, listOf(NodeData(1, NodeKind.RASTER, LayerProps("레이어 1"), ROOT_ID, tiles)))
        } finally {
            src.recycle()
        }
    }

    fun writePng(out: OutputStream, width: Int, height: Int, pixels: ByteBuffer) {
        val bmp = toBitmap(width, height, pixels)
        try {
            BufferedOutputStream(out, 1 shl 16).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bmp.recycle()
        }
    }

    private fun decode(input: InputStream): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = true
            inScaled = false
            // 광색역(P3 등) 이미지도 sRGB로 변환해서 받음
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        return BitmapFactory.decodeStream(input, null, opts)
    }

    /** 프리멀티플라이드 RGBA 버퍼 → Bitmap. ARGB_8888의 메모리 배치가 RGBA 바이트 순서라 그대로 복사됩니다. */
    fun toBitmap(width: Int, height: Int, pixels: ByteBuffer): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val b = pixels.duplicate()
        b.rewind()
        bmp.copyPixelsFromBuffer(b)
        return bmp
    }

    /** 타일 → 캔버스 크기 Bitmap. */
    fun assemble(w: Int, h: Int, tiles: CpuTiles): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (tiles.isEmpty()) return out
        val canvas = Canvas(out)
        val tileBmp = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        val cols = TileMath.cols(w)
        for ((key, buf) in tiles) {
            val b = buf.duplicate()
            b.rewind()
            tileBmp.copyPixelsFromBuffer(b)
            canvas.drawBitmap(tileBmp, ((key % cols) * TILE).toFloat(), ((key / cols) * TILE).toFloat(), null)
        }
        tileBmp.recycle()
        return out
    }

    /** 캔버스 크기 Bitmap → 비어 있지 않은 타일만. 크기가 다르면 왼쪽 위에 맞춰 늘이거나 줄입니다. */
    fun split(src: Bitmap, w: Int, h: Int): CpuTiles {
        val tiles = HashMap<Int, ByteBuffer>()
        val cols = TileMath.cols(w)
        val rows = TileMath.rows(h)
        val tileBmp = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        val c = Canvas(tileBmp)
        val sx = src.width.toFloat() / w
        val sy = src.height.toFloat() / h
        val same = src.width == w && src.height == h
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (ty in 0 until rows) for (tx in 0 until cols) {
            tileBmp.eraseColor(0)
            val x = tx * TILE
            val y = ty * TILE
            val tw = minOf(TILE, w - x)
            val th = minOf(TILE, h - y)
            if (same) {
                c.drawBitmap(src, Rect(x, y, x + tw, y + th), Rect(0, 0, tw, th), null)
            } else {
                c.drawBitmap(
                    src,
                    Rect((x * sx).toInt(), (y * sy).toInt(), ((x + tw) * sx).roundToInt(), ((y + th) * sy).roundToInt()),
                    Rect(0, 0, tw, th), paint
                )
            }
            val buf = GlUtil.byteBuffer(TILE_BYTES)
            tileBmp.copyPixelsToBuffer(buf)
            buf.rewind()
            if (!TileMath.isEmpty(buf)) tiles[ty * cols + tx] = buf
        }
        tileBmp.recycle()
        return tiles
    }
}
