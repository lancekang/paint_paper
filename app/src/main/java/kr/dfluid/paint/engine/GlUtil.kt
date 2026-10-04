package kr.dfluid.paint.engine

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GlUtil {
    private const val TAG = "DFPaint-GL"

    fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("셰이더 컴파일 실패: $log")
        }
        return shader
    }

    fun link(vsSrc: String, fsSrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("프로그램 링크 실패: $log")
        }
        return program
    }

    fun floatBuffer(count: Int): FloatBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun byteBuffer(bytes: Int): ByteBuffer =
        ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    /** GL 에러를 로그로 남기고, 메모리 부족이면 예외를 던집니다. */
    fun check(op: String) {
        var err = GLES20.glGetError()
        while (err != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "$op: glError 0x${Integer.toHexString(err)}")
            if (err == GLES20.GL_OUT_OF_MEMORY) throw OutOfMemoryError("GPU 메모리 부족 ($op)")
            err = GLES20.glGetError()
        }
    }
}

/** 컴파일된 셰이더 프로그램 + 유니폼 위치 캐시. */
class GlProgram(vs: String, fs: String) {
    val id: Int = GlUtil.link(vs, fs)
    private val uniforms = HashMap<String, Int>()

    fun use() = GLES20.glUseProgram(id)

    fun u(name: String): Int = uniforms.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }
}

/** (0,0)~(1,1) 단위 사각형. 풀스크린/캔버스 패스에 공용으로 씁니다. */
class UnitQuad {
    private val vao: Int
    private val vbo: Int

    init {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        val data = GlUtil.floatBuffer(8).apply {
            put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
            position(0)
        }
        GLES30.glBindVertexArray(vao)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 8 * 4, data, GLES20.GL_STATIC_DRAW)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, 0)
        GLES30.glBindVertexArray(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }
}

/** 블렌딩 상태 전환 헬퍼. 모든 텍스처는 프리멀티플라이드 알파입니다. */
object GlState {
    /**
     * 지금 묶인 FBO (-1 = 모름). 같은 FBO를 다시 묶지 않습니다.
     * 타일 기반 GPU(Adreno 등)는 FBO를 다시 묶을 때마다 렌더 패스를 새로 열어
     * 캔버스 전체를 타일 메모리로 다시 읽을 수 있어서, 레이어마다 묶으면 매우 느려집니다.
     * 모든 FBO 바인딩은 이 함수로만 하고, FBO를 지울 때는 forgetFbo를 부릅니다.
     */
    private var boundFbo = -1

    fun bindFbo(fbo: Int) {
        if (fbo != boundFbo) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            boundFbo = fbo
        }
    }

    fun forgetFbo(fbo: Int) {
        if (boundFbo == fbo) boundFbo = -1
    }

    /** 새 GL 컨텍스트 (onSurfaceCreated) */
    fun resetFbo() {
        boundFbo = -1
    }

    fun off() {
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** 일반 합성: dst = src + dst * (1 - src.a) */
    fun over() {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    /** 지우개: dst = dst * (1 - src.a) */
    fun erase() {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
        GLES20.glBlendFunc(GLES20.GL_ZERO, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    /**
     * source-atop: dst 알파를 유지한 채 색만 덮습니다.
     * 투명 픽셀 잠금과 클리핑 레이어(표준 모드)에 씁니다.
     */
    fun atop() {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
        GLES20.glBlendFuncSeparate(
            GLES20.GL_DST_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA,
            GLES20.GL_ZERO, GLES20.GL_ONE
        )
    }

    /** 스트로크 커버리지를 겹쳐도 짙어지지 않게: dst = max(src, dst) */
    fun max() {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendEquation(GLES30.GL_MAX)
    }

    fun resetEquation() {
        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
    }

    fun scissor(x: Int, y: Int, w: Int, h: Int) {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(x, y, w, h)
    }

    fun scissor(r: IRect) = scissor(r.x, r.y, r.w, r.h)

    fun noScissor() {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }
}
