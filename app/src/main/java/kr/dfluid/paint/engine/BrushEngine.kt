package kr.dfluid.paint.engine

import android.opengl.GLES20
import android.opengl.GLES30
import kr.dfluid.paint.brush.Brush
import kr.dfluid.paint.brush.StrokeBuilder
import java.nio.FloatBuffer

/** 스탬프 배열을 스트로크 버퍼에 인스턴싱으로 한 번에 그립니다. GL 스레드 전용. */
class BrushEngine {
    private val program = GlProgram(Shaders.STAMP_VS, Shaders.STAMP_FS)
    private val smudge = GlProgram(Shaders.STAMP_VS, Shaders.SMUDGE_FS)
    private val vao: Int
    private val cornerVbo: Int
    private val instanceVbo: Int
    private var capacity = 0
    private var buffer: FloatBuffer = GlUtil.floatBuffer(StrokeBuilder.FLOATS)

    init {
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES20.glGenBuffers(2, ids, 0)
        cornerVbo = ids[0]
        instanceVbo = ids[1]

        GLES30.glBindVertexArray(vao)

        val corners = GlUtil.floatBuffer(8).apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, cornerVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 8 * 4, corners, GLES20.GL_STATIC_DRAW)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, 0)

        ensureCapacity(1024)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, instanceVbo)
        val stride = StrokeBuilder.FLOATS * 4
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, stride, 0)
        GLES30.glVertexAttribDivisor(1, 1)
        GLES20.glEnableVertexAttribArray(2)
        GLES20.glVertexAttribPointer(2, 4, GLES20.GL_FLOAT, false, stride, 8)
        GLES30.glVertexAttribDivisor(2, 1)

        GLES30.glBindVertexArray(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun ensureCapacity(stamps: Int) {
        if (stamps <= capacity) return
        capacity = maxOf(stamps, capacity * 2)
        buffer = GlUtil.floatBuffer(capacity * StrokeBuilder.FLOATS)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, instanceVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, capacity * StrokeBuilder.FLOATS * 4, null, GLES20.GL_DYNAMIC_DRAW)
    }

    /** stamps: StrokeBuilder 형식. target은 캔버스 크기 스트로크 버퍼. */
    /** tipTex = 0 이면 원형 팁. */
    fun draw(target: RenderTarget, stamps: FloatArray, brush: Brush, tipTex: Int = 0) {
        val count = stamps.size / StrokeBuilder.FLOATS
        if (count == 0) return
        ensureCapacity(count)
        buffer.clear()
        buffer.put(stamps, 0, count * StrokeBuilder.FLOATS)
        buffer.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, instanceVbo)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, count * StrokeBuilder.FLOATS * 4, buffer)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        target.bind()
        GlState.noScissor()
        if (brush.buildUp) GlState.over() else GlState.max()

        program.use()
        GLES20.glUniform2f(program.u("u_canvas"), target.width.toFloat(), target.height.toFloat())
        GLES20.glUniform1f(program.u("u_hardness"), brush.hardness)
        GLES20.glUniform1f(program.u("u_grain"), brush.grain)
        GLES20.glUniform1f(program.u("u_grainScale"), brush.grainScale)
        GLES20.glUniform1i(program.u("u_useTip"), if (tipTex != 0) 1 else 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tipTex)
        GLES20.glUniform1i(program.u("u_tip"), 0)

        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArraysInstanced(GLES20.GL_TRIANGLE_STRIP, 0, 4, count)
        GLES30.glBindVertexArray(0)

        GlState.resetEquation()
        GlState.off()
    }

    /**
     * 색 혼합 스탬프 하나([stamps]의 [index]번째)를 [target]에 그립니다. 블렌딩 끄고, 호출자가 target을 바인딩.
     * [patch]는 target의 (ox, oy)부터를 복사해 둔 것. shift = 직전 스탬프 − 지금 스탬프 (캔버스 px).
     */
    fun drawSmudge(
        target: RenderTarget, stamps: FloatArray, index: Int, brush: Brush,
        patch: RenderTarget, ox: Int, oy: Int, shiftX: Float, shiftY: Float, selTex: Int, lock: Boolean,
    ) {
        ensureCapacity(1)
        buffer.clear()
        buffer.put(stamps, index * StrokeBuilder.FLOATS, StrokeBuilder.FLOATS)
        buffer.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, instanceVbo)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, StrokeBuilder.FLOATS * 4, buffer)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        val p = smudge
        p.use()
        GLES20.glUniform2f(p.u("u_canvas"), target.width.toFloat(), target.height.toFloat())
        GLES20.glUniform1f(p.u("u_hardness"), brush.hardness)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, patch.tex)
        GLES20.glUniform1i(p.u("u_patch"), 0)
        GLES20.glUniform2f(p.u("u_origin"), ox.toFloat(), oy.toFloat())
        GLES20.glUniform2f(p.u("u_psize"), patch.width.toFloat(), patch.height.toFloat())
        GLES20.glUniform2f(p.u("u_shift"), shiftX, shiftY)
        GLES20.glUniform2f(p.u("u_center"), stamps[index * StrokeBuilder.FLOATS], stamps[index * StrokeBuilder.FLOATS + 1])
        GLES20.glUniform1i(p.u("u_mode"), brush.mixMode)
        GLES20.glUniform1f(p.u("u_strength"), brush.opacity)
        GLES20.glUniform1f(p.u("u_blurR"), stamps[index * StrokeBuilder.FLOATS + 2] * 0.5f)
        GLES20.glUniform1i(p.u("u_useSel"), if (selTex != 0) 1 else 0)
        GLES20.glUniform1i(p.u("u_lock"), if (lock) 1 else 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, selTex)
        GLES20.glUniform1i(p.u("u_sel"), 1)

        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArraysInstanced(GLES20.GL_TRIANGLE_STRIP, 0, 4, 1)
        GLES30.glBindVertexArray(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }
}
