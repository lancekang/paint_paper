package kr.dfluid.paint.engine

import android.opengl.GLES20
import android.opengl.GLES30
import kr.dfluid.paint.brush.Brush
import kr.dfluid.paint.brush.StrokeBuilder
import java.nio.FloatBuffer

/** 스탬프 배열을 스트로크 버퍼에 인스턴싱으로 한 번에 그립니다. GL 스레드 전용. */
class BrushEngine {
    private val program = GlProgram(Shaders.STAMP_VS, Shaders.STAMP_FS)
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
}
