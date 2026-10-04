package kr.dfluid.paint.ui

import android.content.Context
import android.graphics.Color
import kr.dfluid.paint.engine.FillOptions
import kr.dfluid.paint.engine.GradientSpec
import kr.dfluid.paint.engine.SelOp
import kr.dfluid.paint.engine.SelShape

/** 앱 설정 + 도구 옵션. UI 스레드 전용. 브러시 프리셋은 BrushLibrary가 따로 저장합니다. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 필압 감마. 1 = 선형, < 1 = 가볍게, > 1 = 무겁게 */
    var pressureGamma = 1f
    var smoothing = 0.3f
    var drawWithFinger = false
    var primaryColor = Color.BLACK
    var secondaryColor = Color.WHITE
    var lastUri: String? = null

    var selectShape = SelShape.RECT
    var selectMode = SelOp.REPLACE
    var fillTolerance = 10
    var fillGap = 0
    var fillExpand = 1
    var fillReferenceAll = true
    var fillOpacity = 1f
    var gradientRadial = false
    var gradientToTransparent = false
    var gradientOpacity = 1f
    /** 새 캔버스 배경: 투명이면 배경 레이어를 만들지 않습니다. */
    var canvasTransparent = false
    var canvasBackground = Color.WHITE
    val recentColors = ArrayList<Int>()

    init {
        pressureGamma = prefs.getFloat("pressureGamma", 1f)
        smoothing = prefs.getFloat("smoothing", 0.3f)
        drawWithFinger = prefs.getBoolean("drawWithFinger", false)
        primaryColor = prefs.getInt("primaryColor", Color.BLACK)
        secondaryColor = prefs.getInt("secondaryColor", Color.WHITE)
        lastUri = prefs.getString("lastUri", null)
        selectShape = SelShape.entries.getOrNull(prefs.getInt("selectShape", 0)) ?: SelShape.RECT
        selectMode = SelOp.entries.getOrNull(prefs.getInt("selectMode", 0)) ?: SelOp.REPLACE
        fillTolerance = prefs.getInt("fillTolerance", 10)
        fillGap = prefs.getInt("fillGap", 0)
        fillExpand = prefs.getInt("fillExpand", 1)
        fillReferenceAll = prefs.getBoolean("fillReferenceAll", true)
        fillOpacity = prefs.getFloat("fillOpacity", 1f)
        gradientRadial = prefs.getBoolean("gradientRadial", false)
        gradientToTransparent = prefs.getBoolean("gradientToTransparent", false)
        gradientOpacity = prefs.getFloat("gradientOpacity", 1f)
        canvasTransparent = prefs.getBoolean("canvasTransparent", false)
        canvasBackground = prefs.getInt("canvasBackground", Color.WHITE)
        prefs.getString("recentColors", "")?.split(",")?.mapNotNull { it.toIntOrNull() }?.let { recentColors.addAll(it.take(MAX_RECENT)) }
    }

    val fillOptions: FillOptions get() = FillOptions(fillTolerance, fillGap, fillExpand, fillReferenceAll)
    val gradientSpec: GradientSpec get() = GradientSpec(gradientRadial, gradientToTransparent)
    /** 새 캔버스 배경색. null = 투명 */
    val newCanvasBackground: Int? get() = if (canvasTransparent) null else canvasBackground

    fun pushRecent(color: Int) {
        recentColors.remove(color)
        recentColors.add(0, color)
        while (recentColors.size > MAX_RECENT) recentColors.removeAt(recentColors.lastIndex)
    }

    fun save() {
        prefs.edit()
            .putFloat("pressureGamma", pressureGamma)
            .putFloat("smoothing", smoothing)
            .putBoolean("drawWithFinger", drawWithFinger)
            .putInt("primaryColor", primaryColor)
            .putInt("secondaryColor", secondaryColor)
            .putString("lastUri", lastUri)
            .putInt("selectShape", selectShape.ordinal)
            .putInt("selectMode", selectMode.ordinal)
            .putInt("fillTolerance", fillTolerance)
            .putInt("fillGap", fillGap)
            .putInt("fillExpand", fillExpand)
            .putBoolean("fillReferenceAll", fillReferenceAll)
            .putFloat("fillOpacity", fillOpacity)
            .putBoolean("gradientRadial", gradientRadial)
            .putBoolean("gradientToTransparent", gradientToTransparent)
            .putFloat("gradientOpacity", gradientOpacity)
            .putBoolean("canvasTransparent", canvasTransparent)
            .putInt("canvasBackground", canvasBackground)
            .putString("recentColors", recentColors.joinToString(","))
            .apply()
    }

    companion object {
        const val MAX_RECENT = 12
    }
}
