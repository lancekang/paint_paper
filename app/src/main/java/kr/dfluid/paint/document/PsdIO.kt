package kr.dfluid.paint.document

import kr.dfluid.paint.engine.CpuTiles
import kr.dfluid.paint.engine.GlUtil
import kr.dfluid.paint.engine.TILE
import kr.dfluid.paint.engine.TILE_BYTES
import kr.dfluid.paint.engine.TileMath
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Photoshop .psd (버전 1, RGB 8비트) 읽기/쓰기. 백그라운드 스레드에서 호출합니다.
 *
 * 지원: 래스터 레이어, 폴더(통과/일반), 불투명도, 표시 여부, 합성 모드, 클리핑, 투명 픽셀 잠금, 한글 이름(luni).
 * 레이어 마스크(채널 -2): 우리 마스크는 "가림" 알파, PSD는 "보임" 회색값이라 255에서 빼서 바꿉니다.
 * 미지원(읽을 때 무시): 벡터 마스크, 조정 레이어, 텍스트/스마트 오브젝트의 편집 정보, 레이어 효과.
 * 읽기는 무압축·RLE·ZIP(예측 포함) 레이어 채널을 지원합니다. 병합 이미지는 레이어가 없을 때만 쓰며 ZIP이면 오류.
 *
 * 레이어 레코드는 아래 → 위 순서. 폴더는 [구분자(lsct 3)] 자식들… [폴더 레코드(lsct 1/2)] 로 표현됩니다.
 */
object PsdIO {

    private val blendKeys = mapOf(
        BlendMode.NORMAL to "norm",
        BlendMode.MULTIPLY to "mul ",
        BlendMode.SCREEN to "scrn",
        BlendMode.OVERLAY to "over",
        BlendMode.ADD to "lddg",
        BlendMode.DARKEN to "dark",
        BlendMode.LIGHTEN to "lite",
        BlendMode.DIFFERENCE to "diff",
        BlendMode.PASS_THROUGH to "pass",
    )

    private fun blendOf(key: String): BlendMode =
        blendKeys.entries.firstOrNull { it.value == key }?.key ?: BlendMode.NORMAL

    fun isPsd(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == '8'.code.toByte() && head[1] == 'B'.code.toByte() &&
            head[2] == 'P'.code.toByte() && head[3] == 'S'.code.toByte()

    // =====================================================================
    // 쓰기
    // =====================================================================

    private class Record(
        val name: String,
        val props: LayerProps,
        /** 0 = 일반 레이어, 1/2 = 폴더(펼침/접힘), 3 = 폴더 끝 구분자 */
        val section: Int,
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        /** 채널 -1(A), 0(R), 1(G), 2(B) 순서의 압축된 데이터 (압축 방식 2바이트 포함) */
        val channels: List<ByteArray>,
        /** 레이어 마스크 (채널 -2) */
        val mask: MaskOut? = null,
    )

    private class MaskOut(val left: Int, val top: Int, val right: Int, val bottom: Int, val disabled: Boolean, val data: ByteArray)

    /** [composite] = 캔버스 크기 프리멀티플라이드 RGBA (병합 이미지용). */
    fun write(out: OutputStream, data: DocumentData, composite: ByteBuffer) {
        val w = data.width
        val h = data.height
        val children = HashMap<Int, MutableList<NodeData>>()
        for (n in data.nodes) children.getOrPut(n.parentId) { ArrayList() }.add(n)

        val records = ArrayList<Record>()
        val empty = List(4) { byteArrayOf(0, 0) } // 무압축 + 데이터 없음
        fun emit(parentId: Int) {
            for (n in children[parentId].orEmpty()) {
                if (n.kind == NodeKind.FOLDER) {
                    records.add(Record("</Layer group>", LayerProps("</Layer group>"), 3, 0, 0, 0, 0, empty))
                    emit(n.id)
                    records.add(Record(n.props.name, n.props, if (n.props.expanded) 1 else 2, 0, 0, 0, 0, empty))
                } else {
                    records.add(layerRecord(n, w, h))
                }
            }
        }
        emit(ROOT_ID)

        val dos = DataOutputStream(BufferedOutputStream(out, 1 shl 16))
        // ---- 헤더 ----
        dos.writeBytes("8BPS")
        dos.writeShort(1)
        dos.write(ByteArray(6))
        dos.writeShort(3) // 병합 이미지 채널 수 (RGB)
        dos.writeInt(h)
        dos.writeInt(w)
        dos.writeShort(8)
        dos.writeShort(3) // RGB
        dos.writeInt(0) // 색상 모드 데이터
        dos.writeInt(0) // 이미지 리소스

        // ---- 레이어 정보 ----
        val recordBytes = records.map { recordHeader(it) }
        var layerInfoLen = 2L + recordBytes.sumOf { it.size.toLong() } +
            records.sumOf { r -> r.channels.sumOf { it.size.toLong() } + (r.mask?.data?.size ?: 0) }
        val pad = (layerInfoLen % 2).toInt()
        layerInfoLen += pad
        if (layerInfoLen + 8 > Int.MAX_VALUE) throw IOException("PSD 파일이 너무 큽니다 (2GB 초과).")
        dos.writeInt((layerInfoLen + 8).toInt()) // 레이어·마스크 섹션 = 레이어 정보(길이 4 + 내용) + 전역 마스크(4)
        dos.writeInt(layerInfoLen.toInt())
        dos.writeShort(records.size)
        recordBytes.forEach { dos.write(it) }
        for (r in records) {
            r.channels.forEach { dos.write(it) }
            r.mask?.let { dos.write(it.data) }
        }
        if (pad == 1) dos.write(0)
        dos.writeInt(0) // 전역 레이어 마스크 정보

        // ---- 병합 이미지 (흰 바탕에 합성, RLE) ----
        writeComposite(dos, w, h, composite)
        dos.flush()
    }

    private fun layerRecord(n: NodeData, w: Int, h: Int): Record {
        val tiles = n.tiles.orEmpty()
        val cols = TileMath.cols(w)
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = 0; var b = 0
        for (k in tiles.keys) {
            val x = (k % cols) * TILE
            val y = (k / cols) * TILE
            l = min(l, x); t = min(t, y)
            r = max(r, min(w, x + TILE)); b = max(b, min(h, y + TILE))
        }
        val mask = if (n.props.mask) maskOut(n, w, h) else null
        if (tiles.isEmpty() || r <= l || b <= t) {
            return Record(n.props.name, n.props, 0, 0, 0, 0, 0, List(4) { byteArrayOf(0, 0) }, mask)
        }
        val bw = r - l
        val bh = b - t
        // 채널 평면 (프리멀티플라이 해제)
        val planes = Array(4) { ByteArray(bw * bh) } // A, R, G, B
        val row = ByteArray(TILE * 4)
        for ((k, buf) in tiles) {
            val ox = (k % cols) * TILE
            val oy = (k / cols) * TILE
            val src = buf.duplicate()
            for (yy in 0 until TILE) {
                val cy = oy + yy
                if (cy >= b) break
                src.position(yy * TILE * 4)
                src.get(row)
                val pw = min(TILE, r - ox)
                val base = (cy - t) * bw + (ox - l)
                for (xx in 0 until pw) {
                    val a = row[xx * 4 + 3].toInt() and 0xFF
                    val i = base + xx
                    planes[0][i] = a.toByte()
                    if (a == 0) continue
                    for (c in 0 until 3) {
                        val v = row[xx * 4 + c].toInt() and 0xFF
                        planes[c + 1][i] = min(255, (v * 255 + a / 2) / a).toByte()
                    }
                }
            }
        }
        return Record(n.props.name, n.props, 0, l, t, r, b, planes.map { rleChannel(it, bw, bh) }, mask)
    }

    /** 마스크 타일(알파 = 가림) → PSD 마스크 채널(255 = 보임). 범위 밖 기본값은 255(보임). */
    private fun maskOut(n: NodeData, w: Int, h: Int): MaskOut {
        val tiles = n.maskTiles.orEmpty()
        val cols = TileMath.cols(w)
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = 0; var b = 0
        for (k in tiles.keys) {
            val x = (k % cols) * TILE
            val y = (k / cols) * TILE
            l = min(l, x); t = min(t, y)
            r = max(r, min(w, x + TILE)); b = max(b, min(h, y + TILE))
        }
        if (tiles.isEmpty() || r <= l || b <= t) return MaskOut(0, 0, 0, 0, !n.props.maskEnabled, byteArrayOf(0, 0))
        val bw = r - l
        val bh = b - t
        val plane = ByteArray(bw * bh) { 0xFF.toByte() }
        for ((k, buf) in tiles) {
            val ox = (k % cols) * TILE
            val oy = (k / cols) * TILE
            val src = buf.duplicate()
            for (yy in 0 until TILE) {
                val cy = oy + yy
                if (cy >= b) break
                val pw = min(TILE, r - ox)
                for (xx in 0 until pw) {
                    val a = src.get((yy * TILE + xx) * 4 + 3).toInt() and 0xFF
                    plane[(cy - t) * bw + (ox - l) + xx] = (255 - a).toByte()
                }
            }
        }
        return MaskOut(l, t, r, b, !n.props.maskEnabled, rleChannel(plane, bw, bh))
    }

    private fun recordHeader(r: Record): ByteArray {
        val bytes = ByteArrayOutputStream(256)
        val o = DataOutputStream(bytes)
        o.writeInt(r.top); o.writeInt(r.left); o.writeInt(r.bottom); o.writeInt(r.right)
        val m = r.mask
        o.writeShort(if (m != null) 5 else 4)
        val ids = intArrayOf(-1, 0, 1, 2)
        for (i in 0 until 4) {
            o.writeShort(ids[i])
            o.writeInt(r.channels[i].size)
        }
        if (m != null) {
            o.writeShort(-2)
            o.writeInt(m.data.size)
        }
        o.writeBytes("8BIM")
        val folderBlend = if (r.section == 1 || r.section == 2) r.props.blend else null
        val key = if (r.section == 3) "norm" else blendKeys[r.props.blend] ?: "norm"
        o.writeBytes(if (r.section == 0 && key == "pass") "norm" else key)
        o.writeByte((r.props.opacity.coerceIn(0f, 1f) * 255 + 0.5f).toInt())
        o.writeByte(if (r.section == 0 && r.props.clip) 1 else 0)
        var flags = 0
        if (r.section == 0 && r.props.alphaLock) flags = flags or 0x01
        if (!r.props.visible) flags = flags or 0x02
        if (r.section != 0) flags = flags or 0x18 // 폴더 레코드: 픽셀 데이터가 겉모습과 무관
        o.writeByte(flags)
        o.writeByte(0)

        val extra = ByteArrayOutputStream(128)
        val e = DataOutputStream(extra)
        if (m != null) {
            // 레이어 마스크 데이터 20바이트: 범위, 기본색(255 = 보임), 플래그(비트1 = 끔), 패딩 2
            e.writeInt(20)
            e.writeInt(m.top); e.writeInt(m.left); e.writeInt(m.bottom); e.writeInt(m.right)
            e.writeByte(255)
            e.writeByte(if (m.disabled) 0x02 else 0)
            e.writeShort(0)
        } else {
            e.writeInt(0) // 레이어 마스크
        }
        e.writeInt(0) // 블렌딩 범위
        // 파스칼 이름 (ASCII만, 4바이트 정렬). 실제 이름은 luni에.
        val ascii = r.name.map { if (it.code in 32..126) it else '_' }.joinToString("").take(255)
        e.writeByte(ascii.length)
        e.writeBytes(ascii)
        var nameLen = 1 + ascii.length
        while (nameLen % 4 != 0) { e.writeByte(0); nameLen++ }
        // luni: 유니코드 이름
        val uni = ByteArrayOutputStream()
        DataOutputStream(uni).apply {
            writeInt(r.name.length)
            writeChars(r.name)
            while (uni.size() % 4 != 0) writeByte(0)
        }
        e.writeBytes("8BIM"); e.writeBytes("luni"); e.writeInt(uni.size()); e.write(uni.toByteArray())
        // lspf: 잠금 (비트0 = 투명 픽셀 잠금). 포토샵 계열은 플래그 비트보다 이것을 봅니다.
        if (r.section == 0 && r.props.alphaLock) {
            e.writeBytes("8BIM"); e.writeBytes("lspf"); e.writeInt(4); e.writeInt(1)
        }
        // lsct: 폴더
        if (r.section != 0) {
            e.writeBytes("8BIM"); e.writeBytes("lsct")
            if (folderBlend != null) {
                e.writeInt(12)
                e.writeInt(r.section)
                e.writeBytes("8BIM")
                e.writeBytes(blendKeys[folderBlend] ?: "pass")
            } else {
                e.writeInt(4)
                e.writeInt(r.section)
            }
        }
        o.writeInt(extra.size())
        o.write(extra.toByteArray())
        return bytes.toByteArray()
    }

    /** 한 채널 평면 → [압축=1][행별 길이 u16 × h][PackBits 행들] */
    private fun rleChannel(plane: ByteArray, w: Int, h: Int): ByteArray {
        val counts = IntArray(h)
        val body = ByteArrayOutputStream(plane.size / 4 + 64)
        for (y in 0 until h) {
            val before = body.size()
            packBits(plane, y * w, w, body)
            counts[y] = body.size() - before
        }
        val out = ByteArrayOutputStream(2 + h * 2 + body.size())
        val o = DataOutputStream(out)
        o.writeShort(1)
        for (c in counts) o.writeShort(c)
        body.writeTo(out)
        return out.toByteArray()
    }

    private fun packBits(src: ByteArray, off: Int, len: Int, out: ByteArrayOutputStream) {
        var i = 0
        while (i < len) {
            var run = 1
            while (i + run < len && run < 128 && src[off + i + run] == src[off + i]) run++
            if (run >= 3) {
                out.write(1 - run) // -(run-1)
                out.write(src[off + i].toInt())
                i += run
                continue
            }
            // 리터럴: 3개 이상 반복이 시작되기 전까지
            val start = i
            var count = 0
            while (i < len && count < 128) {
                if (i + 2 < len && src[off + i] == src[off + i + 1] && src[off + i] == src[off + i + 2]) break
                i++; count++
            }
            out.write(count - 1)
            out.write(src, off + start, count)
        }
    }

    private fun writeComposite(dos: DataOutputStream, w: Int, h: Int, composite: ByteBuffer) {
        val planes = Array(3) { ByteArray(w * h) }
        val src = composite.duplicate()
        src.rewind()
        val px = ByteArray(4)
        for (i in 0 until w * h) {
            src.get(px)
            val inv = 255 - (px[3].toInt() and 0xFF)
            for (c in 0 until 3) planes[c][i] = min(255, (px[c].toInt() and 0xFF) + inv).toByte()
        }
        val counts = ArrayList<IntArray>(3)
        val bodies = ArrayList<ByteArrayOutputStream>(3)
        for (p in planes) {
            val cnt = IntArray(h)
            val body = ByteArrayOutputStream(p.size / 4 + 64)
            for (y in 0 until h) {
                val before = body.size()
                packBits(p, y * w, w, body)
                cnt[y] = body.size() - before
            }
            counts.add(cnt); bodies.add(body)
        }
        dos.writeShort(1)
        for (cnt in counts) for (c in cnt) dos.writeShort(c)
        for (b in bodies) b.writeTo(dos)
    }

    // =====================================================================
    // 읽기
    // =====================================================================

    private class Reader(input: InputStream) {
        private val s = BufferedInputStream(input, 1 shl 16)
        var pos = 0L
            private set

        fun u8(): Int {
            val v = s.read()
            if (v < 0) throw EOFException("PSD 파일이 중간에 끝났습니다.")
            pos++
            return v
        }

        fun u16(): Int = (u8() shl 8) or u8()
        fun i16(): Int = u16().toShort().toInt()
        fun i32(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun u32(): Long = i32().toLong() and 0xFFFFFFFFL
        fun str4(): String = String(bytes(4), Charsets.ISO_8859_1)

        fun bytes(n: Int): ByteArray {
            val b = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = s.read(b, off, n - off)
                if (r < 0) throw EOFException("PSD 파일이 중간에 끝났습니다.")
                off += r
            }
            pos += n
            return b
        }

        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val k = s.skip(left)
                if (k <= 0) {
                    if (s.read() < 0) throw EOFException("PSD 파일이 중간에 끝났습니다.")
                    left--; pos++
                } else {
                    left -= k; pos += k
                }
            }
        }

        fun skipTo(target: Long) {
            if (target > pos) skip(target - pos)
        }
    }

    private class LayerIn(
        val top: Int, val left: Int, val bottom: Int, val right: Int,
        val channelIds: IntArray, val channelLens: LongArray,
        val blendKey: String, val opacity: Int, val clipping: Int, val flags: Int,
        var name: String, var section: Int = 0, var sectionBlend: String? = null,
    ) {
        var locked = false
        var hasMask = false
        var maskTop = 0; var maskLeft = 0; var maskBottom = 0; var maskRight = 0
        var maskDefault = 255
        var maskFlags = 0
        var maskTiles: CpuTiles? = null
        var tiles: CpuTiles? = null
    }

    private class TreeNode(val layer: LayerIn?, val kind: NodeKind) {
        val children = ArrayList<TreeNode>()
    }

    fun read(input: InputStream, maxTextureSize: Int): DocumentData {
        val r = Reader(input)
        if (r.str4() != "8BPS") throw IOException("PSD 파일이 아닙니다.")
        val version = r.u16()
        if (version != 1) throw IOException("PSB(대용량 문서)는 지원하지 않습니다.")
        r.skip(6)
        val channels = r.u16()
        val h = r.i32()
        val w = r.i32()
        val depth = r.u16()
        val mode = r.u16()
        if (depth != 8) throw IOException("8비트 PSD만 열 수 있습니다 (이 파일: ${depth}비트).")
        if (mode != 3) throw IOException("RGB 색상 모드 PSD만 열 수 있습니다.")
        if (w <= 0 || h <= 0 || w > maxTextureSize || h > maxTextureSize) {
            throw IOException("이 기기에서 열 수 없는 크기입니다 (${w}x$h, 최대 $maxTextureSize).")
        }
        r.skip(r.u32()) // 색상 모드 데이터
        r.skip(r.u32()) // 이미지 리소스

        val lmLen = r.u32()
        val lmEnd = r.pos + lmLen
        val layers = ArrayList<LayerIn>()
        if (lmLen > 0) {
            val liLen = r.u32()
            val liEnd = r.pos + liLen
            if (liLen > 0) {
                val count = kotlin.math.abs(r.i16())
                repeat(count) { layers.add(readRecord(r)) }
                for (l in layers) readChannels(r, l, w, h)
            }
            r.skipTo(liEnd)
        }
        r.skipTo(lmEnd)

        if (layers.none { it.section == 0 }) {
            // 레이어가 없는 PSD: 병합 이미지를 레이어 한 장으로
            val tiles = readComposite(r, w, h, channels)
            return DocumentData(w, h, 1, listOf(NodeData(1, NodeKind.RASTER, LayerProps("배경"), ROOT_ID, tiles)))
        }

        // 아래 → 위 레코드를 트리로
        val stack = ArrayList<TreeNode>()
        stack.add(TreeNode(null, NodeKind.FOLDER))
        for (l in layers) {
            when (l.section) {
                3 -> stack.add(TreeNode(null, NodeKind.FOLDER))
                1, 2 -> {
                    val content = if (stack.size > 1) stack.removeAt(stack.lastIndex) else TreeNode(null, NodeKind.FOLDER)
                    val folder = TreeNode(l, NodeKind.FOLDER)
                    folder.children.addAll(content.children)
                    stack.last().children.add(folder)
                }
                else -> stack.last().children.add(TreeNode(l, NodeKind.RASTER))
            }
        }
        // 짝이 맞지 않은 구분자는 풀어서 바깥에 붙입니다.
        while (stack.size > 1) {
            val orphan = stack.removeAt(stack.lastIndex)
            stack.last().children.addAll(orphan.children)
        }

        val nodes = ArrayList<NodeData>()
        var nextId = 1
        var activeId = 0
        fun flatten(parentId: Int, list: List<TreeNode>) {
            for (t in list) {
                val l = t.layer ?: continue
                val id = nextId++
                if (t.kind == NodeKind.FOLDER) {
                    val key = l.sectionBlend ?: l.blendKey
                    val props = LayerProps(
                        name = l.name.ifBlank { "폴더" },
                        opacity = l.opacity / 255f,
                        blend = if (key == "pass") BlendMode.PASS_THROUGH else blendOf(key),
                        visible = l.flags and 0x02 == 0,
                        expanded = l.section == 1,
                    )
                    nodes.add(NodeData(id, NodeKind.FOLDER, props, parentId, null))
                    flatten(id, t.children)
                } else {
                    val props = LayerProps(
                        mask = l.hasMask,
                        maskEnabled = l.maskFlags and 0x02 == 0,
                        name = l.name.ifBlank { "레이어" },
                        opacity = l.opacity / 255f,
                        blend = blendOf(l.blendKey).let { if (it == BlendMode.PASS_THROUGH) BlendMode.NORMAL else it },
                        visible = l.flags and 0x02 == 0,
                        alphaLock = l.locked || l.flags and 0x01 != 0,
                        clip = l.clipping != 0,
                    )
                    nodes.add(NodeData(id, NodeKind.RASTER, props, parentId, l.tiles ?: emptyMap(), if (l.hasMask) l.maskTiles ?: emptyMap() else null))
                    activeId = id // 맨 위 레이어가 마지막에 남음
                }
            }
        }
        flatten(ROOT_ID, stack[0].children)
        return DocumentData(w, h, activeId, nodes)
    }

    private fun readRecord(r: Reader): LayerIn {
        val top = r.i32(); val left = r.i32(); val bottom = r.i32(); val right = r.i32()
        val nCh = r.u16()
        val ids = IntArray(nCh)
        val lens = LongArray(nCh)
        for (i in 0 until nCh) {
            ids[i] = r.i16()
            lens[i] = r.u32()
        }
        if (r.str4() != "8BIM") throw IOException("손상된 PSD입니다 (레이어 레코드).")
        val key = r.str4()
        val opacity = r.u8()
        val clipping = r.u8()
        val flags = r.u8()
        r.u8()
        val extraLen = r.u32()
        val extraEnd = r.pos + extraLen
        val maskLen = r.u32()
        val maskEnd = r.pos + maskLen
        var maskInfo: IntArray? = null
        if (maskLen >= 18) {
            maskInfo = intArrayOf(r.i32(), r.i32(), r.i32(), r.i32(), r.u8(), r.u8())
        }
        r.skipTo(maskEnd)
        r.skip(r.u32()) // 블렌딩 범위
        val nameLen = r.u8()
        var name = String(r.bytes(nameLen), Charsets.ISO_8859_1)
        var padded = 1 + nameLen
        while (padded % 4 != 0) { r.u8(); padded++ }
        val layer = LayerIn(top, left, bottom, right, ids, lens, key, opacity, clipping, flags, name)
        if (maskInfo != null && ids.contains(-2)) {
            layer.hasMask = true
            layer.maskTop = maskInfo[0]; layer.maskLeft = maskInfo[1]
            layer.maskBottom = maskInfo[2]; layer.maskRight = maskInfo[3]
            layer.maskDefault = maskInfo[4]
            layer.maskFlags = maskInfo[5]
        }
        while (r.pos + 12 <= extraEnd) {
            val sig = r.str4()
            if (sig != "8BIM" && sig != "8B64") break
            val k = r.str4()
            val len = r.u32()
            val end = r.pos + len
            when (k) {
                "luni" -> {
                    val n = r.i32()
                    if (n in 0..(len.toInt() - 4) / 2) {
                        val chars = CharArray(n) { r.u16().toChar() }
                        name = String(chars).trimEnd('\u0000')
                        layer.name = name
                    }
                }
                "lspf" -> if (len >= 4) layer.locked = r.i32() and 0x01 != 0
                "lsct", "lsdk" -> {
                    layer.section = r.i32()
                    if (len >= 12) {
                        r.str4()
                        layer.sectionBlend = r.str4()
                    }
                }
            }
            r.skipTo(end)
        }
        r.skipTo(extraEnd)
        return layer
    }

    private fun readChannels(r: Reader, l: LayerIn, docW: Int, docH: Int) {
        val lw = l.right - l.left
        val lh = l.bottom - l.top
        val planes = arrayOfNulls<ByteArray>(4) // A, R, G, B
        for (i in l.channelIds.indices) {
            val id = l.channelIds[i]
            val start = r.pos
            val end = start + l.channelLens[i]
            if (l.channelLens[i] >= 2 && id in -1..2 && lw > 0 && lh > 0) {
                val plane = readPlane(r, lw, lh, l.channelLens[i] - 2)
                planes[id + 1] = plane
            } else if (id == -2 && l.hasMask && l.section == 0 && l.channelLens[i] >= 2) {
                val mw = l.maskRight - l.maskLeft
                val mh = l.maskBottom - l.maskTop
                val plane = if (mw > 0 && mh > 0) readPlane(r, mw, mh, l.channelLens[i] - 2) else null
                l.maskTiles = maskToTiles(plane, l.maskLeft, l.maskTop, mw, mh, l.maskDefault, docW, docH)
            }
            r.skipTo(end)
        }
        if (l.section != 0 || lw <= 0 || lh <= 0) return
        l.tiles = toTiles(planes, l.left, l.top, lw, lh, docW, docH)
    }

    /** 압축 방식 2바이트부터 읽어 한 채널 평면을 돌려줍니다. [dataLen] = 압축 방식 뒤 데이터 길이. */
    private fun readPlane(r: Reader, w: Int, h: Int, dataLen: Long): ByteArray {
        return when (val comp = r.u16()) {
            0 -> r.bytes(w * h)
            1 -> {
                val counts = IntArray(h) { r.u16() }
                val out = ByteArray(w * h)
                for (y in 0 until h) unpackBits(r.bytes(counts[y]), out, y * w, w)
                out
            }
            2, 3 -> {
                if (dataLen < 0 || dataLen > Int.MAX_VALUE) throw IOException("손상된 PSD입니다 (ZIP 채널 길이).")
                inflatePlane(r.bytes(dataLen.toInt()), w, h, prediction = comp == 3)
            }
            else -> throw IOException("알 수 없는 압축 방식($comp)의 레이어입니다.")
        }
    }

    /**
     * ZIP(deflate) 채널. 방식 3(예측)은 각 행이 "앞 픽셀과의 차이"로 저장되어 있어 누적합으로 되돌립니다.
     * 데이터가 모자라면 남은 부분은 0(투명)으로 둡니다.
     */
    internal fun inflatePlane(packed: ByteArray, w: Int, h: Int, prediction: Boolean): ByteArray {
        val out = ByteArray(w * h)
        val inf = Inflater()
        try {
            inf.setInput(packed)
            var o = 0
            while (o < out.size && !inf.finished()) {
                val n = try {
                    inf.inflate(out, o, out.size - o)
                } catch (e: DataFormatException) {
                    throw IOException("손상된 ZIP 채널입니다.", e)
                }
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                o += n
            }
        } finally {
            inf.end()
        }
        if (prediction) {
            for (y in 0 until h) {
                val base = y * w
                for (x in 1 until w) out[base + x] = (out[base + x] + out[base + x - 1]).toByte()
            }
        }
        return out
    }

    private fun unpackBits(src: ByteArray, dst: ByteArray, off: Int, len: Int) {
        var i = 0
        var o = 0
        while (i < src.size && o < len) {
            val n = src[i++].toInt()
            when {
                n >= 0 -> {
                    val c = min(n + 1, min(len - o, src.size - i))
                    System.arraycopy(src, i, dst, off + o, c)
                    i += n + 1; o += c
                }
                n != -128 -> {
                    if (i >= src.size) break
                    val v = src[i++]
                    val c = min(1 - n, len - o)
                    java.util.Arrays.fill(dst, off + o, off + o + c, v)
                    o += c
                }
            }
        }
    }

    /**
     * PSD 마스크(255 = 보임) → 마스크 타일(알파 = 가림 = 255 − 값). 범위 밖은 [default]를 씁니다.
     * 기본값이 255(보임)면 범위 안 타일만, 아니면 캔버스 전체 타일을 만듭니다.
     */
    private fun maskToTiles(plane: ByteArray?, left: Int, top: Int, mw: Int, mh: Int, default: Int, docW: Int, docH: Int): CpuTiles {
        val tiles = HashMap<Int, ByteBuffer>()
        val cols = TileMath.cols(docW)
        val rows = TileMath.rows(docH)
        val outside = 255 - default
        val txs = if (outside == 0) max(0, left) / TILE..(min(docW, left + mw) - 1) / TILE else 0 until cols
        val tys = if (outside == 0) max(0, top) / TILE..(min(docH, top + mh) - 1) / TILE else 0 until rows
        if (outside == 0 && (plane == null || mw <= 0 || mh <= 0)) return tiles
        for (ty in tys) for (tx in txs) {
            val buf = GlUtil.byteBuffer(TILE_BYTES)
            var any = false
            for (yy in 0 until TILE) for (xx in 0 until TILE) {
                val cx = tx * TILE + xx
                val cy = ty * TILE + yy
                if (cx >= docW || cy >= docH) continue
                val mx = cx - left
                val my = cy - top
                val hide = if (plane != null && mx in 0 until mw && my in 0 until mh) 255 - (plane[my * mw + mx].toInt() and 0xFF) else outside
                if (hide != 0) {
                    buf.put((yy * TILE + xx) * 4 + 3, hide.toByte())
                    any = true
                }
            }
            buf.rewind()
            if (any) tiles[ty * cols + tx] = buf
        }
        return tiles
    }

    /** 채널 평면(비프리멀티플라이) → 캔버스 타일(프리멀티플라이드). 알파가 없으면 불투명으로 봅니다. */
    private fun toTiles(planes: Array<ByteArray?>, left: Int, top: Int, lw: Int, lh: Int, docW: Int, docH: Int): CpuTiles {
        val tiles = HashMap<Int, ByteBuffer>()
        val x0 = max(0, left); val y0 = max(0, top)
        val x1 = min(docW, left + lw); val y1 = min(docH, top + lh)
        if (x1 <= x0 || y1 <= y0) return tiles
        val cols = TileMath.cols(docW)
        val alpha = planes[0]
        val row = ByteArray(TILE * 4)
        for (ty in y0 / TILE..(y1 - 1) / TILE) for (tx in x0 / TILE..(x1 - 1) / TILE) {
            val buf = GlUtil.byteBuffer(TILE_BYTES)
            var any = false
            for (yy in 0 until TILE) {
                val cy = ty * TILE + yy
                java.util.Arrays.fill(row, 0)
                if (cy in y0 until y1) {
                    val sy = cy - top
                    for (xx in 0 until TILE) {
                        val cx = tx * TILE + xx
                        if (cx < x0 || cx >= x1) continue
                        val si = sy * lw + (cx - left)
                        val a = alpha?.let { it[si].toInt() and 0xFF } ?: 255
                        if (a == 0) continue
                        any = true
                        for (c in 0 until 3) {
                            val v = planes[c + 1]?.let { it[si].toInt() and 0xFF } ?: 0
                            row[xx * 4 + c] = ((v * a + 127) / 255).toByte()
                        }
                        row[xx * 4 + 3] = a.toByte()
                    }
                }
                buf.put(row)
            }
            buf.rewind()
            if (any) tiles[ty * cols + tx] = buf
        }
        return tiles
    }

    private fun readComposite(r: Reader, w: Int, h: Int, channels: Int): CpuTiles {
        val comp = r.u16()
        val planes = arrayOfNulls<ByteArray>(4)
        val n = min(channels, 4)
        when (comp) {
            0 -> for (c in 0 until channels) {
                val p = r.bytes(w * h)
                if (c < n) planes[if (c == 3) 0 else c + 1] = p
            }
            1 -> {
                val counts = Array(channels) { IntArray(h) { r.u16() } }
                for (c in 0 until channels) {
                    val p = ByteArray(w * h)
                    for (y in 0 until h) unpackBits(r.bytes(counts[c][y]), p, y * w, w)
                    if (c < n) planes[if (c == 3) 0 else c + 1] = p
                }
            }
            else -> throw IOException("ZIP 압축 병합 이미지는 지원하지 않습니다.")
        }
        return toTiles(planes, 0, 0, w, h, w, h)
    }
}
