package kr.dfluid.paint.document

import java.io.OutputStream

/**
 * 애니메이션 GIF 쓰기 (외부 라이브러리 없음).
 * 프레임마다 중앙값 분할로 255색 팔레트를 만들고 0번은 투명색으로 씁니다. 무한 반복.
 * [delayCs] = 프레임 간격 (1/100초).
 */
class GifEncoder(private val out: OutputStream, private val w: Int, private val h: Int, private val delayCs: Int) {

    init {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        short(w); short(h)
        out.write(0x00) // 전역 팔레트 없음
        out.write(0); out.write(0)
        // NETSCAPE2.0 반복 (0 = 무한)
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    private fun short(v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
    }

    /** [argb] = w*h 픽셀 (알파 포함, 프리멀티플라이드 아님). 알파 < 128은 투명. */
    fun addFrame(argb: IntArray, delay: Int = delayCs) {
        val palette = Quantizer.palette(argb, 255)
        val indices = Quantizer.map(argb, palette)
        // 그래픽 제어 확장: 이전 프레임을 지우고(2) 그림, 투명색 0
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, ((2 shl 2) or 1).toByte()))
        short(delay)
        out.write(0); out.write(0)
        // 이미지 서술자 + 지역 팔레트 256색
        out.write(0x2C)
        short(0); short(0); short(w); short(h)
        out.write(0x80 or 7)
        val table = ByteArray(256 * 3)
        palette.forEachIndexed { i, c ->
            val k = (i + 1) * 3
            table[k] = (c shr 16).toByte(); table[k + 1] = (c shr 8).toByte(); table[k + 2] = c.toByte()
        }
        out.write(table)
        Lzw.write(out, indices, 8)
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    /** 중앙값 분할 팔레트 + 가까운 색 찾기 (15비트 캐시). */
    private object Quantizer {
        fun palette(argb: IntArray, max: Int): IntArray {
            val step = maxOf(1, argb.size / 60000)
            val samples = ArrayList<Int>()
            var i = 0
            while (i < argb.size) {
                val c = argb[i]
                if ((c ushr 24) >= 128) samples.add(c and 0xFFFFFF)
                i += step
            }
            if (samples.isEmpty()) return intArrayOf(0)
            var boxes = mutableListOf(samples.toIntArray())
            while (boxes.size < max) {
                // 가장 넓은 범위를 가진 상자를 반으로
                var best = -1; var bestRange = -1; var bestCh = 0
                boxes.forEachIndexed { bi, b ->
                    if (b.size < 2) return@forEachIndexed
                    for (ch in 0..2) {
                        val sh = 16 - ch * 8
                        var lo = 255; var hi = 0
                        for (c in b) { val v = (c shr sh) and 0xFF; if (v < lo) lo = v; if (v > hi) hi = v }
                        if (hi - lo > bestRange) { bestRange = hi - lo; best = bi; bestCh = ch }
                    }
                }
                if (best < 0 || bestRange <= 0) break
                val b = boxes.removeAt(best)
                val sh = 16 - bestCh * 8
                val sorted = b.sortedBy { (it shr sh) and 0xFF }
                val mid = sorted.size / 2
                boxes.add(sorted.subList(0, mid).toIntArray())
                boxes.add(sorted.subList(mid, sorted.size).toIntArray())
                boxes = boxes.filter { it.isNotEmpty() }.toMutableList()
            }
            return IntArray(boxes.size) { k ->
                val b = boxes[k]
                var r = 0L; var g = 0L; var bl = 0L
                for (c in b) { r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bl += c and 0xFF }
                val n = b.size
                (((r / n).toInt()) shl 16) or (((g / n).toInt()) shl 8) or (bl / n).toInt()
            }
        }

        /** 픽셀 → 팔레트 번호 (+1, 0 = 투명) */
        fun map(argb: IntArray, pal: IntArray): ByteArray {
            val cache = IntArray(32768) { -1 }
            val out = ByteArray(argb.size)
            for (i in argb.indices) {
                val c = argb[i]
                if ((c ushr 24) < 128) { out[i] = 0; continue }
                val key = (((c shr 19) and 31) shl 10) or (((c shr 11) and 31) shl 5) or ((c shr 3) and 31)
                var idx = cache[key]
                if (idx < 0) {
                    val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                    var bestD = Int.MAX_VALUE
                    idx = 0
                    for (k in pal.indices) {
                        val p = pal[k]
                        val dr = r - ((p shr 16) and 0xFF); val dg = g - ((p shr 8) and 0xFF); val db = b - (p and 0xFF)
                        val dist = dr * dr * 3 + dg * dg * 4 + db * db * 2
                        if (dist < bestD) { bestD = dist; idx = k }
                    }
                    cache[key] = idx
                }
                out[i] = (idx + 1).toByte()
            }
            return out
        }
    }

    /** GIF용 가변 길이 LZW (최대 12비트, 꽉 차면 지움 코드). */
    private object Lzw {
        fun write(out: OutputStream, data: ByteArray, minCodeSize: Int) {
            out.write(minCodeSize)
            val clear = 1 shl minCodeSize
            val eoi = clear + 1
            val block = ByteArray(255)
            var blockLen = 0
            var bitBuf = 0
            var bitCount = 0
            var codeSize = minCodeSize + 1
            fun flushBlock() {
                if (blockLen > 0) {
                    out.write(blockLen); out.write(block, 0, blockLen); blockLen = 0
                }
            }
            fun emit(code: Int) {
                bitBuf = bitBuf or (code shl bitCount)
                bitCount += codeSize
                while (bitCount >= 8) {
                    block[blockLen++] = (bitBuf and 0xFF).toByte()
                    if (blockLen == 255) flushBlock()
                    bitBuf = bitBuf ushr 8
                    bitCount -= 8
                }
            }
            // 사전: (접두 코드 << 8 | 다음 바이트) → 코드
            val dict = HashMap<Int, Int>(8192)
            var next = eoi + 1
            emit(clear)
            if (data.isEmpty()) {
                emit(eoi)
            } else {
                var prefix = data[0].toInt() and 0xFF
                for (i in 1 until data.size) {
                    val k = data[i].toInt() and 0xFF
                    val key = (prefix shl 8) or k
                    val found = dict[key]
                    if (found != null) {
                        prefix = found
                        continue
                    }
                    emit(prefix)
                    if (next < 4096) {
                        dict[key] = next
                        // 다음 코드가 현재 폭을 넘으면 폭을 늘림
                        if (next == (1 shl codeSize) && codeSize < 12) codeSize++
                        next++
                    } else {
                        emit(clear)
                        dict.clear()
                        next = eoi + 1
                        codeSize = minCodeSize + 1
                    }
                    prefix = k
                }
                emit(prefix)
                emit(eoi)
            }
            if (bitCount > 0) {
                block[blockLen++] = (bitBuf and 0xFF).toByte()
                if (blockLen == 255) flushBlock()
            }
            flushBlock()
            out.write(0) // 블록 끝
        }
    }
}
