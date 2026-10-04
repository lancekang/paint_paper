package kr.dfluid.paint.engine

import android.opengl.GLES20
import java.nio.ByteBuffer

const val TILE = 256
const val TILE_BYTES = TILE * TILE * 4

/** CPU 쪽 희소 타일 묶음: 타일 키 → 256×256 프리멀티플라이드 RGBA. 비어 있는 타일은 없습니다. */
typealias CpuTiles = Map<Int, ByteBuffer>

object TileMath {
    fun cols(w: Int) = (w + TILE - 1) / TILE
    fun rows(h: Int) = (h + TILE - 1) / TILE

    fun isEmpty(buf: ByteBuffer): Boolean {
        val b = buf.duplicate()
        b.rewind()
        var i = 3
        val n = b.limit()
        while (i < n) {
            if (b.get(i).toInt() != 0) return false
            i += 4
        }
        return true
    }
}

/** 256×256 텍스처 + FBO 한 장. */
class Tile(val tex: Int, val fbo: Int)

/** 타일 재사용 풀. 지우개로 비워진 타일 등을 다시 할당 비용 없이 씁니다. GL 스레드 전용. */
class TilePool(private val keepFree: Int = 96) {
    private val free = ArrayDeque<Tile>()
    var live = 0
        private set

    fun acquire(): Tile {
        live++
        val t = free.removeLastOrNull() ?: create()
        GlState.bindFbo(t.fbo)
        GLES20.glViewport(0, 0, TILE, TILE)
        GlState.noScissor()
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        return t
    }

    fun release(t: Tile) {
        live--
        if (free.size < keepFree) free.addLast(t) else delete(t)
    }

    fun clearFree() {
        while (free.isNotEmpty()) delete(free.removeLast())
    }

    /** GL 컨텍스트를 잃었을 때: 객체는 이미 무효이므로 지우지 않고 잊습니다. */
    fun forget() {
        free.clear()
        live = 0
    }

    private fun create(): Tile {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, android.opengl.GLES30.GL_RGBA8, TILE, TILE, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        try {
            GlUtil.check("tile")
        } catch (e: OutOfMemoryError) {
            GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            live--
            throw e
        }
        GLES20.glGenFramebuffers(1, ids, 0)
        val fbo = ids[0]
        GlState.bindFbo(fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
        return Tile(tex, fbo)
    }

    private fun delete(t: Tile) {
        GlState.forgetFbo(t.fbo)
        GLES20.glDeleteFramebuffers(1, intArrayOf(t.fbo), 0)
        GLES20.glDeleteTextures(1, intArrayOf(t.tex), 0)
    }
}

/**
 * 레이어 픽셀 = 256px 희소 타일. 아무것도 그리지 않은 타일은 메모리를 쓰지 않습니다.
 *
 * 캔버스 좌표계 셰이더를 그대로 쓰기 위해, 타일에 그릴 때 뷰포트를
 * (-ox, -oy, 캔버스 폭, 캔버스 높이)로 잡습니다. 그러면 캔버스 전체 사각형이 그 타일 영역에 정확히 겹칩니다.
 */
class TileSurface(val width: Int, val height: Int, private val pool: TilePool) {
    val cols = TileMath.cols(width)
    val rows = TileMath.rows(height)
    val tiles = HashMap<Int, Tile>()

    fun key(tx: Int, ty: Int) = ty * cols + tx
    fun tx(key: Int) = key % cols
    fun ty(key: Int) = key / cols
    fun originX(key: Int) = tx(key) * TILE
    fun originY(key: Int) = ty(key) * TILE

    fun tileRect(key: Int) = IRect(originX(key), originY(key), TILE, TILE)

    fun getOrCreate(key: Int): Tile = tiles.getOrPut(key) { pool.acquire() }

    /** 캔버스 좌표계로 그릴 수 있게 타일 FBO와 오프셋 뷰포트를 설정합니다. */
    fun bindCanvasSpace(key: Int, t: Tile) {
        GlState.bindFbo(t.fbo)
        GLES20.glViewport(-originX(key), -originY(key), width, height)
    }

    /** 타일 FBO를 타일 좌표계(0..256)로 바인딩. */
    fun bindLocal(t: Tile) {
        GlState.bindFbo(t.fbo)
        GLES20.glViewport(0, 0, TILE, TILE)
    }

    /** 캔버스 사각형을 이 타일의 FBO(창) 좌표로 바꿔 시저를 겁니다. */
    fun scissorCanvasRect(key: Int, r: IRect) {
        GlState.scissor(r.x - originX(key), r.y - originY(key), r.w, r.h)
    }

    fun keysIntersecting(r: IRect): List<Int> {
        val tx0 = (r.x / TILE).coerceIn(0, cols - 1)
        val ty0 = (r.y / TILE).coerceIn(0, rows - 1)
        val tx1 = ((r.right - 1) / TILE).coerceIn(0, cols - 1)
        val ty1 = ((r.bottom - 1) / TILE).coerceIn(0, rows - 1)
        val out = ArrayList<Int>((tx1 - tx0 + 1) * (ty1 - ty0 + 1))
        for (y in ty0..ty1) for (x in tx0..tx1) out.add(key(x, y))
        return out
    }

    fun read(key: Int): ByteBuffer? {
        val t = tiles[key] ?: return null
        val buf = GlUtil.byteBuffer(TILE_BYTES)
        bindLocal(t)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, TILE, TILE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.rewind()
        return buf
    }

    /** null 또는 빈 데이터면 타일을 없앱니다. */
    fun write(key: Int, data: ByteBuffer?) {
        if (data == null || TileMath.isEmpty(data)) {
            remove(key)
            return
        }
        val t = getOrCreate(key)
        val b = data.duplicate()
        b.rewind()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.tex)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, TILE, TILE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
    }

    fun remove(key: Int) {
        tiles.remove(key)?.let { pool.release(it) }
    }

    /** 읽어 봐서 비어 있으면 반납 (지우개 뒤 정리). */
    fun dropIfEmpty(key: Int) {
        val data = read(key) ?: return
        if (TileMath.isEmpty(data)) remove(key)
    }

    fun clear() {
        tiles.values.forEach { pool.release(it) }
        tiles.clear()
    }

    fun toCpu(): HashMap<Int, ByteBuffer> {
        val out = HashMap<Int, ByteBuffer>(tiles.size * 2)
        for (k in tiles.keys.toList()) {
            val b = read(k) ?: continue
            if (!TileMath.isEmpty(b)) out[k] = b
        }
        return out
    }

    fun loadCpu(data: CpuTiles) {
        clear()
        data.forEach { (k, b) -> write(k, b) }
    }

    fun copyFrom(other: TileSurface) {
        clear()
        for (k in other.tiles.keys.toList()) write(k, other.read(k))
    }

    /** 내용이 있는 타일들의 합집합 사각형 (타일 단위). */
    fun tileBounds(): IRect? {
        var r: IRect? = null
        for (k in tiles.keys) {
            val t = tileRect(k).intersect(IRect(0, 0, width, height)) ?: continue
            r = t.union(r)
        }
        return r
    }

    val tileCount: Int get() = tiles.size
}
