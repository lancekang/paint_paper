package kr.dfluid.paint.engine

import android.opengl.GLES20

/**
 * 풀스크린/타일 패스 모음. GL 스레드 전용.
 * 블렌딩 상태와 타깃 바인딩은 호출자가 정합니다.
 */
class Compositor {
    private val quad = UnitQuad()
    private val copy = GlProgram(Shaders.QUAD_VS, Shaders.COPY_FS)
    private val copyTile = GlProgram(Shaders.TILE_VS, Shaders.COPY_FS)
    private val copyAffine = GlProgram(Shaders.AFFINE_VS, Shaders.COPY_FS)
    private val copyProj = GlProgram(Shaders.PROJ_VS, Shaders.COPY_FS)
    private val projCols = FloatArray(9)
    private val merge = GlProgram(Shaders.QUAD_VS, Shaders.MERGE_FS)
    private val blend = GlProgram(Shaders.QUAD_VS, Shaders.BLEND_FS)
    private val blendTile = GlProgram(Shaders.TILE_VS, Shaders.BLEND_FS)

    /** framebuffer fetch 합성 프로그램 (지원하지 않거나 컴파일에 실패하면 null → 핑퐁 사용) */
    private val fetchPrograms: kotlin.Pair<GlProgram, GlProgram>? = run {
        val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        if (!ext.contains("GL_EXT_shader_framebuffer_fetch")) return@run null
        try {
            GlProgram(Shaders.QUAD_VS, Shaders.BLEND_FETCH_FS) to GlProgram(Shaders.TILE_VS, Shaders.BLEND_FETCH_FS)
        } catch (e: RuntimeException) {
            android.util.Log.w("DFPaint", "framebuffer fetch 셰이더 실패, 핑퐁으로 합성합니다", e)
            null
        }
    }

    /** 블렌드 모드를 대상 FBO에서 바로 합성할 수 있음 (핑퐁 불필요) */
    val hasFetch: Boolean get() = fetchPrograms != null
    private val display = GlProgram(Shaders.DISPLAY_VS, Shaders.DISPLAY_FS)
    private val cursor = GlProgram(Shaders.QUAD_VS, Shaders.CURSOR_FS)
    private val filter = GlProgram(Shaders.QUAD_VS, Shaders.FILTER_FS)
    private val borderH = GlProgram(Shaders.QUAD_VS, Shaders.BORDER_H_FS)
    private val tone = GlProgram(Shaders.QUAD_VS, Shaders.TONE_FS)
    private val colorize = GlProgram(Shaders.QUAD_VS, Shaders.COLORIZE_FS)
    private val borderV = GlProgram(Shaders.QUAD_VS, Shaders.BORDER_V_FS)
    private val filterCombine = GlProgram(Shaders.QUAD_VS, Shaders.FILTER_COMBINE_FS)
    private val viewMatrix = FloatArray(9)
    private val affine = FloatArray(9)

    private fun bindTex(unit: Int, tex: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
    }

    private fun unbind1() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun setupCopy(p: GlProgram, tex: Int, opacity: Float, maskTex: Int, maskMode: Int) {
        p.use()
        bindTex(0, tex)
        GLES20.glUniform1i(p.u("u_tex"), 0)
        GLES20.glUniform1f(p.u("u_opacity"), opacity)
        GLES20.glUniform1i(p.u("u_maskMode"), maskMode)
        if (maskMode != 0) {
            bindTex(1, maskTex)
            GLES20.glUniform1i(p.u("u_mask"), 1)
        }
    }

    /** 캔버스 크기 텍스처 → 현재 타깃(캔버스 좌표계). */
    fun drawCopy(tex: Int, opacity: Float, maskTex: Int = 0, maskMode: Int = 0) {
        setupCopy(copy, tex, opacity, maskTex, maskMode)
        quad.draw()
        if (maskMode != 0) unbind1()
    }

    /** 타일 한 장 → 캔버스 좌표계 타깃의 제자리. */
    fun drawTile(tex: Int, ox: Int, oy: Int, canvasW: Int, canvasH: Int, opacity: Float, maskTex: Int = 0, maskMode: Int = 0) {
        setupCopy(copyTile, tex, opacity, maskTex, maskMode)
        GLES20.glUniform2f(copyTile.u("u_origin"), ox.toFloat(), oy.toFloat())
        GLES20.glUniform2f(copyTile.u("u_canvas"), canvasW.toFloat(), canvasH.toFloat())
        quad.draw()
        if (maskMode != 0) unbind1()
    }

    /** m = [a, b, c, d, tx, ty] : 캔버스 = (a·x + c·y + tx, b·x + d·y + ty) */
    fun drawAffine(tex: Int, w: Int, h: Int, m: FloatArray, canvasW: Int, canvasH: Int, opacity: Float) {
        affine[0] = m[0]; affine[1] = m[1]; affine[2] = 0f
        affine[3] = m[2]; affine[4] = m[3]; affine[5] = 0f
        affine[6] = m[4]; affine[7] = m[5]; affine[8] = 1f
        setupCopy(copyAffine, tex, opacity, 0, 0)
        GLES20.glUniformMatrix3fv(copyAffine.u("u_m"), 1, false, affine, 0)
        GLES20.glUniform2f(copyAffine.u("u_size"), w.toFloat(), h.toFloat())
        GLES20.glUniform2f(copyAffine.u("u_canvas"), canvasW.toFloat(), canvasH.toFloat())
        quad.draw()
    }

    /** h = 원근 행렬 (행 우선 9개, [Homography]): 로컬 px → 캔버스 px */
    fun drawProjective(tex: Int, w: Int, h: Int, hm: FloatArray, canvasW: Int, canvasH: Int, opacity: Float) {
        // GLSL mat3은 열 우선
        projCols[0] = hm[0]; projCols[1] = hm[3]; projCols[2] = hm[6]
        projCols[3] = hm[1]; projCols[4] = hm[4]; projCols[5] = hm[7]
        projCols[6] = hm[2]; projCols[7] = hm[5]; projCols[8] = hm[8]
        setupCopy(copyProj, tex, opacity, 0, 0)
        GLES20.glUniformMatrix3fv(copyProj.u("u_h"), 1, false, projCols, 0)
        GLES20.glUniform2f(copyProj.u("u_size"), w.toFloat(), h.toFloat())
        GLES20.glUniform2f(copyProj.u("u_canvas"), canvasW.toFloat(), canvasH.toFloat())
        quad.draw()
    }

    /** 커버리지 텍스처 × 색. color0 = 프리멀티플라이드 RGBA. */
    fun drawMergeCoverage(coverageTex: Int, color0: FloatArray, opacity: Float, selTex: Int, canvasW: Int, canvasH: Int) {
        setupMerge(0, color0, color0, 0f, 0f, 0f, 0f, opacity, selTex, canvasW, canvasH)
        bindTex(0, coverageTex)
        GLES20.glUniform1i(merge.u("u_stroke"), 0)
        quad.draw()
        unbind1()
    }

    /** kind 1 = 선형, 2 = 원형. */
    fun drawMergeGradient(
        kind: Int, c0: FloatArray, c1: FloatArray, x0: Float, y0: Float, x1: Float, y1: Float,
        opacity: Float, selTex: Int, canvasW: Int, canvasH: Int,
    ) {
        setupMerge(kind, c0, c1, x0, y0, x1, y1, opacity, selTex, canvasW, canvasH)
        quad.draw()
        unbind1()
    }

    private fun setupMerge(
        kind: Int, c0: FloatArray, c1: FloatArray, x0: Float, y0: Float, x1: Float, y1: Float,
        opacity: Float, selTex: Int, canvasW: Int, canvasH: Int,
    ) {
        merge.use()
        GLES20.glUniform1i(merge.u("u_kind"), kind)
        GLES20.glUniform4f(merge.u("u_c0"), c0[0], c0[1], c0[2], c0[3])
        GLES20.glUniform4f(merge.u("u_c1"), c1[0], c1[1], c1[2], c1[3])
        GLES20.glUniform2f(merge.u("u_p0"), x0, y0)
        GLES20.glUniform2f(merge.u("u_p1"), x1, y1)
        GLES20.glUniform2f(merge.u("u_canvas"), canvasW.toFloat(), canvasH.toFloat())
        GLES20.glUniform1f(merge.u("u_opacity"), opacity)
        GLES20.glUniform1i(merge.u("u_useSel"), if (selTex != 0) 1 else 0)
        bindTex(1, selTex)
        GLES20.glUniform1i(merge.u("u_sel"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /** 블렌딩 끄고 호출. 결과 = blend(src × opacity, dst). 소스가 캔버스 크기 텍스처일 때. */
    fun drawBlend(srcTex: Int, dstTex: Int, opacity: Float, mode: Int, preserve: Boolean) {
        setupBlend(blend, srcTex, dstTex, opacity, mode, preserve)
        quad.draw()
        unbind1()
    }

    /** 소스가 타일일 때. dst는 캔버스 크기 텍스처. */
    fun drawBlendTile(tileTex: Int, ox: Int, oy: Int, canvasW: Int, canvasH: Int, dstTex: Int, opacity: Float, mode: Int, preserve: Boolean) {
        setupBlend(blendTile, tileTex, dstTex, opacity, mode, preserve)
        GLES20.glUniform2f(blendTile.u("u_origin"), ox.toFloat(), oy.toFloat())
        GLES20.glUniform2f(blendTile.u("u_canvas"), canvasW.toFloat(), canvasH.toFloat())
        quad.draw()
        unbind1()
    }

    /** [hasFetch]일 때만. 현재 FBO = 대상. 블렌딩 끄고 호출. 소스가 캔버스 크기 텍스처. */
    fun drawBlendFetch(srcTex: Int, opacity: Float, mode: Int, preserve: Boolean) {
        val p = fetchPrograms!!.first
        setupFetch(p, srcTex, opacity, mode, preserve)
        quad.draw()
    }

    /** [hasFetch]일 때만. 소스가 타일. */
    fun drawBlendTileFetch(tileTex: Int, ox: Int, oy: Int, canvasW: Int, canvasH: Int, opacity: Float, mode: Int, preserve: Boolean) {
        val p = fetchPrograms!!.second
        setupFetch(p, tileTex, opacity, mode, preserve)
        GLES20.glUniform2f(p.u("u_origin"), ox.toFloat(), oy.toFloat())
        GLES20.glUniform2f(p.u("u_canvas"), canvasW.toFloat(), canvasH.toFloat())
        quad.draw()
    }

    private fun setupFetch(p: GlProgram, srcTex: Int, opacity: Float, mode: Int, preserve: Boolean) {
        p.use()
        bindTex(0, srcTex)
        GLES20.glUniform1i(p.u("u_src"), 0)
        GLES20.glUniform1f(p.u("u_opacity"), opacity)
        GLES20.glUniform1i(p.u("u_mode"), mode)
        GLES20.glUniform1i(p.u("u_preserve"), if (preserve) 1 else 0)
    }

    private fun setupBlend(p: GlProgram, srcTex: Int, dstTex: Int, opacity: Float, mode: Int, preserve: Boolean) {
        p.use()
        bindTex(0, srcTex)
        bindTex(1, dstTex)
        GLES20.glUniform1i(p.u("u_src"), 0)
        GLES20.glUniform1i(p.u("u_dst"), 1)
        GLES20.glUniform1f(p.u("u_opacity"), opacity)
        GLES20.glUniform1i(p.u("u_mode"), mode)
        GLES20.glUniform1i(p.u("u_preserve"), if (preserve) 1 else 0)
    }

    /** 필터 한 단계: srcTex → 현재 타깃(같은 크기). 블렌딩 끄고 호출. [dirX]/[dirY] = 흐리기 방향(텍셀). */
    fun drawFilter(kind: Int, srcTex: Int, p: FloatArray, dirX: Float = 0f, dirY: Float = 0f, taps: Int = 0) {
        filter.use()
        bindTex(0, srcTex)
        GLES20.glUniform1i(filter.u("u_src"), 0)
        GLES20.glUniform1i(filter.u("u_kind"), kind)
        GLES20.glUniform4f(filter.u("u_p"), p.getOrElse(0) { 0f }, p.getOrElse(1) { 0f }, p.getOrElse(2) { 0f }, p.getOrElse(3) { 0f })
        GLES20.glUniform4f(filter.u("u_q"), p.getOrElse(4) { 0f }, p.getOrElse(5) { 0f }, p.getOrElse(6) { 0f }, p.getOrElse(7) { 0f })
        GLES20.glUniform2f(filter.u("u_dir"), dirX, dirY)
        GLES20.glUniform1i(filter.u("u_taps"), taps)
        quad.draw()
    }

    /** 필터 결과 + 원본 → 현재 타깃. rect = 필터 영역(캔버스 px). selTex = 0이면 선택 없음. */
    fun drawFilterCombine(origTex: Int, filtTex: Int, selTex: Int, rect: IRect, canvasW: Int, canvasH: Int, lock: Boolean, sharpen: Boolean, amount: Float) {
        val p = filterCombine
        p.use()
        bindTex(0, origTex)
        bindTex(1, filtTex)
        bindTex(2, selTex)
        GLES20.glUniform1i(p.u("u_orig"), 0)
        GLES20.glUniform1i(p.u("u_filt"), 1)
        GLES20.glUniform1i(p.u("u_sel"), 2)
        GLES20.glUniform1i(p.u("u_useSel"), if (selTex != 0) 1 else 0)
        GLES20.glUniform1i(p.u("u_lock"), if (lock) 1 else 0)
        GLES20.glUniform1i(p.u("u_sharpen"), if (sharpen) 1 else 0)
        GLES20.glUniform1f(p.u("u_amount"), amount)
        GLES20.glUniform4f(p.u("u_rect"), rect.x.toFloat(), rect.y.toFloat(), rect.w.toFloat(), rect.h.toFloat())
        GLES20.glUniform2f(p.u("u_canvas"), canvasW.toFloat(), canvasH.toFloat())
        quad.draw()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        unbind1()
    }

    /** 레이어 컬러: srcTex(캔버스 크기) → 현재 타깃. 블렌딩 끄고 호출. */
    fun drawColorize(srcTex: Int, rgb: Int) {
        colorize.use()
        bindTex(0, srcTex)
        GLES20.glUniform1i(colorize.u("u_src"), 0)
        GLES20.glUniform3f(colorize.u("u_color"), android.graphics.Color.red(rgb) / 255f, android.graphics.Color.green(rgb) / 255f, android.graphics.Color.blue(rgb) / 255f)
        quad.draw()
    }

    /** 톤 효과: srcTex(캔버스 크기) → 현재 타깃. 블렌딩 끄고 호출. color = 프리멀티플라이드. */
    fun drawTone(srcTex: Int, canvasW: Int, canvasH: Int, cell: Float, angleRad: Float, color: FloatArray) {
        tone.use()
        bindTex(0, srcTex)
        GLES20.glUniform1i(tone.u("u_src"), 0)
        GLES20.glUniform2f(tone.u("u_canvas"), canvasW.toFloat(), canvasH.toFloat())
        GLES20.glUniform1f(tone.u("u_cell"), cell)
        GLES20.glUniform1f(tone.u("u_angle"), angleRad)
        GLES20.glUniform4f(tone.u("u_color"), color[0], color[1], color[2], color[3])
        quad.draw()
    }

    /** 경계 효과 1단계: srcTex(캔버스 크기 RGBA) → 현재 타깃. 블렌딩 끄고 호출. */
    fun drawBorderH(srcTex: Int, canvasW: Int, canvasH: Int, r: Int) {
        borderH.use()
        bindTex(0, srcTex)
        GLES20.glUniform1i(borderH.u("u_src"), 0)
        GLES20.glUniform2f(borderH.u("u_texel"), 1f / canvasW, 1f / canvasH)
        GLES20.glUniform1i(borderH.u("u_r"), r)
        quad.draw()
    }

    /** 경계 효과 2단계: 1단계 결과 → 테두리 색 × 커버리지. color = 프리멀티플라이드. */
    fun drawBorderV(hTex: Int, canvasW: Int, canvasH: Int, r: Int, width: Float, color: FloatArray) {
        borderV.use()
        bindTex(0, hTex)
        GLES20.glUniform1i(borderV.u("u_h"), 0)
        GLES20.glUniform2f(borderV.u("u_texel"), 1f / canvasW, 1f / canvasH)
        GLES20.glUniform1i(borderV.u("u_r"), r)
        GLES20.glUniform1f(borderV.u("u_width"), width)
        GLES20.glUniform4f(borderV.u("u_color"), color[0], color[1], color[2], color[3])
        quad.draw()
    }

    /** view = [m00, m10, m01, m11, tx, ty, scale] (Viewport.toGl) */
    fun drawDisplay(tex: Int, view: FloatArray, canvasW: Int, canvasH: Int, screenW: Int, screenH: Int, selTex: Int, time: Float) {
        viewMatrix[0] = view[0]; viewMatrix[1] = view[1]; viewMatrix[2] = 0f
        viewMatrix[3] = view[2]; viewMatrix[4] = view[3]; viewMatrix[5] = 0f
        viewMatrix[6] = view[4]; viewMatrix[7] = view[5]; viewMatrix[8] = 1f
        display.use()
        bindTex(0, tex)
        GLES20.glUniform1i(display.u("u_tex"), 0)
        bindTex(1, selTex)
        GLES20.glUniform1i(display.u("u_sel"), 1)
        GLES20.glUniform1i(display.u("u_hasSel"), if (selTex != 0) 1 else 0)
        GLES20.glUniform1f(display.u("u_time"), time)
        GLES20.glUniformMatrix3fv(display.u("u_view"), 1, false, viewMatrix, 0)
        GLES20.glUniform2f(display.u("u_canvas"), canvasW.toFloat(), canvasH.toFloat())
        GLES20.glUniform2f(display.u("u_screen"), screenW.toFloat(), screenH.toFloat())
        quad.draw()
        unbind1()
    }

    /** x, y = GL 창 좌표(왼쪽 아래 원점). */
    fun drawCursor(x: Float, y: Float, radius: Float) {
        cursor.use()
        GLES20.glUniform2f(cursor.u("u_center"), x, y)
        GLES20.glUniform1f(cursor.u("u_radius"), radius)
        quad.draw()
    }
}
