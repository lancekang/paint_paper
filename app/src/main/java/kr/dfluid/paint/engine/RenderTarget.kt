package kr.dfluid.paint.engine

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer

/**
 * 텍스처 + FBO. 합성 버퍼, 스트로크 버퍼, 선택 마스크, 타일 모두 이것입니다.
 * mask = true 이면 단일 채널(R8) — 스트로크 커버리지와 선택 영역에 씁니다.
 */
class RenderTarget(val width: Int, val height: Int, val mask: Boolean = false) {
    val tex: Int
    val fbo: Int
    val bytesPerPixel: Int get() = if (mask) 1 else 4
    private val format: Int get() = if (mask) GLES30.GL_RED else GLES20.GL_RGBA

    init {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        tex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, if (mask) GLES30.GL_R8 else GLES30.GL_RGBA8, width, height, 0,
            format, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        try {
            GlUtil.check("texImage ${width}x$height")
        } catch (e: OutOfMemoryError) {
            GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            throw e
        }

        GLES20.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GlState.bindFbo(fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            release()
            throw IllegalStateException("FBO 생성 실패 (0x${Integer.toHexString(status)})")
        }
        clear()
    }

    fun bind() {
        GlState.bindFbo(fbo)
        GLES20.glViewport(0, 0, width, height)
    }

    fun clear() {
        bind()
        GlState.noScissor()
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    fun clear(r: IRect) {
        bind()
        GlState.scissor(r)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GlState.noScissor()
    }

    /** 1로 채움 (마스크 "전체 선택" 등). */
    fun fillOne() {
        bind()
        GlState.noScissor()
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
    }

    /** (x, y)는 캔버스 좌표(왼쪽 위 기준)와 같습니다. 결과는 위→아래 행 순서입니다. */
    fun read(x: Int, y: Int, w: Int, h: Int): ByteBuffer {
        // R8은 RGBA로만 읽을 수 있는 기기가 있어 항상 RGBA로 읽습니다.
        val buf = GlUtil.byteBuffer(w * h * 4)
        bind()
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(x, y, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.rewind()
        return buf
    }

    fun readAll(): ByteBuffer = read(0, 0, width, height)

    /** data는 이 타깃 형식(RGBA 또는 R8)의 바이트. 원본 버퍼 위치는 건드리지 않습니다. */
    fun upload(x: Int, y: Int, w: Int, h: Int, data: ByteBuffer) {
        val b = data.duplicate()
        b.rewind()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, x, y, w, h, format, GLES20.GL_UNSIGNED_BYTE, b)
    }

    fun uploadAll(data: ByteBuffer) = upload(0, 0, width, height, data)

    fun setFilter(min: Int, mag: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, min)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, mag)
    }

    fun release() {
        GlState.forgetFbo(fbo)
        GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
    }
}

/** 정수 사각형 (캔버스 px, y는 위에서부터). */
data class IRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h
    val isEmpty: Boolean get() = w <= 0 || h <= 0

    fun union(o: IRect?): IRect {
        if (o == null || o.isEmpty) return this
        if (isEmpty) return o
        val l = minOf(x, o.x)
        val t = minOf(y, o.y)
        return IRect(l, t, maxOf(right, o.right) - l, maxOf(bottom, o.bottom) - t)
    }

    fun intersect(o: IRect): IRect? {
        val l = maxOf(x, o.x)
        val t = maxOf(y, o.y)
        val r = minOf(right, o.right)
        val b = minOf(bottom, o.bottom)
        return if (r > l && b > t) IRect(l, t, r - l, b - t) else null
    }

    fun intersects(o: IRect): Boolean = x < o.right && o.x < right && y < o.bottom && o.y < bottom

    companion object {
        fun ofBounds(l: Float, t: Float, r: Float, b: Float, w: Int, h: Int): IRect? {
            val x0 = kotlin.math.floor(l).toInt().coerceIn(0, w)
            val y0 = kotlin.math.floor(t).toInt().coerceIn(0, h)
            val x1 = kotlin.math.ceil(r).toInt().coerceIn(0, w)
            val y1 = kotlin.math.ceil(b).toInt().coerceIn(0, h)
            return if (x1 > x0 && y1 > y0) IRect(x0, y0, x1 - x0, y1 - y0) else null
        }
    }
}
