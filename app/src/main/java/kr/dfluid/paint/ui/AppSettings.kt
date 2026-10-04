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
    /** FillOptions.REF_ALL / REF_CURRENT / REF_MARKED */
    var fillRef = FillOptions.REF_ALL
    var fillOpacity = 1f
    var wandTolerance = 10
    var wandGap = 0
    var wandExpand = 0
    var wandRef = FillOptions.REF_ALL
    var gradientRadial = false
    var gradientToTransparent = false
    var gradientOpacity = 1f
    /** 새 캔버스 배경: 투명이면 배경 레이어를 만들지 않습니다. */
    var canvasTransparent = false
    var canvasBackground = Color.WHITE
    val recentColors = ArrayList<Int>()
    /** 화면 테마: THEME_SYSTEM / THEME_LIGHT / THEME_DARK */
    var themeMode = THEME_SYSTEM
    /** 오른쪽 패널 카드 펼침 */
    var cardToolOpen = true
    var cardColorOpen = true
    var cardLayerOpen = true
    /** 떠 있는 패널 위치 (남는 공간 중 비율: 0 = 왼쪽/위, 0.5 = 가운데, 1 = 오른쪽/아래) */
    var topBarFx = 0.5f
    var topBarFy = 0f
    var toolBarFx = 0f
    var toolBarFy = 0.5f
    var rightPanelFx = 1f
    /** 패널 접힘 (손잡이와 펼치기 버튼만 남김) */
    var topBarCollapsed = false
    var toolBarCollapsed = false
    var rightPanelCollapsed = false
    /** 원근 자·동심원 자 상태 (GuideRuler.encode) */
    var rulerState: String? = null
    /** 패널 위치 잠금 (손잡이 숨김) */
    var panelLock = false

    fun resetPanels() {
        topBarFx = 0.5f; topBarFy = 0f; toolBarFx = 0f; toolBarFy = 0.5f; rightPanelFx = 1f
    }

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
        fillRef = prefs.getInt("fillRef", if (prefs.getBoolean("fillReferenceAll", true)) FillOptions.REF_ALL else FillOptions.REF_CURRENT)
        fillOpacity = prefs.getFloat("fillOpacity", 1f)
        wandTolerance = prefs.getInt("wandTolerance", 10)
        wandGap = prefs.getInt("wandGap", 0)
        wandExpand = prefs.getInt("wandExpand", 0)
        wandRef = prefs.getInt("wandRef", if (prefs.getBoolean("wandReferenceAll", true)) FillOptions.REF_ALL else FillOptions.REF_CURRENT)
        gradientRadial = prefs.getBoolean("gradientRadial", false)
        gradientToTransparent = prefs.getBoolean("gradientToTransparent", false)
        gradientOpacity = prefs.getFloat("gradientOpacity", 1f)
        canvasTransparent = prefs.getBoolean("canvasTransparent", false)
        canvasBackground = prefs.getInt("canvasBackground", Color.WHITE)
        themeMode = prefs.getInt("themeMode", THEME_SYSTEM)
        cardToolOpen = prefs.getBoolean("cardToolOpen", true)
        cardColorOpen = prefs.getBoolean("cardColorOpen", true)
        cardLayerOpen = prefs.getBoolean("cardLayerOpen", true)
        topBarFx = prefs.getFloat("topBarFx", 0.5f)
        topBarFy = prefs.getFloat("topBarFy", 0f)
        toolBarFx = prefs.getFloat("toolBarFx", 0f)
        toolBarFy = prefs.getFloat("toolBarFy", 0.5f)
        rightPanelFx = prefs.getFloat("rightPanelFx", 1f)
        panelLock = prefs.getBoolean("panelLock", false)
        rulerState = prefs.getString("rulerState", null)
        topBarCollapsed = prefs.getBoolean("topBarCollapsed", false)
        toolBarCollapsed = prefs.getBoolean("toolBarCollapsed", false)
        rightPanelCollapsed = prefs.getBoolean("rightPanelCollapsed", false)
        prefs.getString("recentColors", "")?.split(",")?.mapNotNull { it.toIntOrNull() }?.let { recentColors.addAll(it.take(MAX_RECENT)) }
    }

    val fillOptions: FillOptions get() = FillOptions(fillTolerance, fillGap, fillExpand, fillRef)
    val wandOptions: FillOptions get() = FillOptions(wandTolerance, wandGap, wandExpand, wandRef)
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
            .putInt("fillRef", fillRef)
            .putFloat("fillOpacity", fillOpacity)
            .putInt("wandTolerance", wandTolerance)
            .putInt("wandGap", wandGap)
            .putInt("wandExpand", wandExpand)
            .putInt("wandRef", wandRef)
            .putBoolean("gradientRadial", gradientRadial)
            .putBoolean("gradientToTransparent", gradientToTransparent)
            .putFloat("gradientOpacity", gradientOpacity)
            .putBoolean("canvasTransparent", canvasTransparent)
            .putInt("canvasBackground", canvasBackground)
            .putString("recentColors", recentColors.joinToString(","))
            .putInt("themeMode", themeMode)
            .putBoolean("cardToolOpen", cardToolOpen)
            .putBoolean("cardColorOpen", cardColorOpen)
            .putBoolean("cardLayerOpen", cardLayerOpen)
            .putFloat("topBarFx", topBarFx)
            .putFloat("topBarFy", topBarFy)
            .putFloat("toolBarFx", toolBarFx)
            .putFloat("toolBarFy", toolBarFy)
            .putFloat("rightPanelFx", rightPanelFx)
            .putBoolean("panelLock", panelLock)
            .putString("rulerState", rulerState)
            .putBoolean("topBarCollapsed", topBarCollapsed)
            .putBoolean("toolBarCollapsed", toolBarCollapsed)
            .putBoolean("rightPanelCollapsed", rightPanelCollapsed)
            .apply()
    }

    companion object {
        const val MAX_RECENT = 12
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }
}
