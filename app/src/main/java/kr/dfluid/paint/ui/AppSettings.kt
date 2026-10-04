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
    /** 후보정 (화면 px 기준 σ, 0 = 끔) */
    var postSmoothing = 0f
    var drawWithFinger = false
    var primaryColor = Color.BLACK
    var secondaryColor = Color.WHITE
    var lastUri: String? = null

    var selectShape = SelShape.RECT
    var selectMode = SelOp.REPLACE
    var fillTolerance = 10
    var fillGap = 0
    var fillExpand = 1
    /** 채우기: false = 누른 곳, true = 둘러싸고 칠하기 */
    var fillEnclose = false
    /** FillOptions.REF_ALL / REF_CURRENT / REF_MARKED */
    var fillRef = FillOptions.REF_ALL
    var fillOpacity = 1f
    var wandTolerance = 10
    var wandGap = 0
    var wandExpand = 0
    /** 선택 영역 확장/축소/경계 흐리기 범위 (px) */
    var selModifyPx = 4
    /** 도형 종류: 0 직선, 1 사각형, 2 타원, 3 올가미 채우기 */
    var shapeKind = 1
    /** 사각형·타원: 0 선, 1 채우기, 2 선 + 채우기 */
    var shapeFill = 0
    /** 새 텍스트의 기본값 */
    var textSize = 48f
    var textFont = 0
    var textVertical = false
    /** 애니메이션 재생 속도 */
    var animFps = 12
    /** 서브 뷰 (참고 이미지 창) */
    var subOpen = false
    /** 퀵 액세스: 담아 둔 동작 이름 (Action.name) */
    val quickActions = arrayListOf("UNDO", "REDO", "TOOL_PEN", "TOOL_ERASER", "SELECT_NONE", "TRANSFORM")
    var quickOpen = false
    var quickFx = 0f
    var quickFy = 1f
    /** 내비게이터 창 */
    var navOpen = false
    var navFx = 1f
    var navFy = 1f
    /** 선택 범위 런처를 보일지 */
    var selLauncher = true
    var subUri: String? = null
    var subFx = 0.72f
    var subFy = 0.55f
    var subW = 320
    var subH = 260
    var wandRef = FillOptions.REF_ALL
    var gradientRadial = false
    var gradientToTransparent = false
    var gradientPreset = 0
    var gradientRepeat = 0
    var gradientOpacity = 1f
    /** 새 캔버스 배경: 투명이면 배경 레이어를 만들지 않습니다. */
    var canvasTransparent = false
    var canvasBackground = Color.WHITE
    val recentColors = ArrayList<Int>()
    /** 색 카드 탭: 0 서클, 1 사각형, 2 중간색, 3 컬러 세트 */
    var colorTab = 0
    /** 중간색 네 모서리 (왼쪽 위, 오른쪽 위, 왼쪽 아래, 오른쪽 아래) */
    val mixCorners = intArrayOf(0xFFFFFFFF.toInt(), 0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF000000.toInt())
    /** 컬러 세트 */
    val palette = arrayListOf(
        0xFF000000.toInt(), 0xFF404040.toInt(), 0xFF808080.toInt(), 0xFFC0C0C0.toInt(), 0xFFFFFFFF.toInt(),
        0xFFFFE0D0.toInt(), 0xFFF5C6A5.toInt(), 0xFFD9967A.toInt(), 0xFF8D5524.toInt(),
        0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
        0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(), 0xFF3949AB.toInt(), 0xFF8E24AA.toInt(), 0xFFD81B60.toInt(),
    )
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
        postSmoothing = prefs.getFloat("postSmoothing", 0f)
        drawWithFinger = prefs.getBoolean("drawWithFinger", false)
        primaryColor = prefs.getInt("primaryColor", Color.BLACK)
        secondaryColor = prefs.getInt("secondaryColor", Color.WHITE)
        lastUri = prefs.getString("lastUri", null)
        selectShape = SelShape.entries.getOrNull(prefs.getInt("selectShape", 0)) ?: SelShape.RECT
        selectMode = SelOp.entries.getOrNull(prefs.getInt("selectMode", 0)) ?: SelOp.REPLACE
        fillTolerance = prefs.getInt("fillTolerance", 10)
        fillGap = prefs.getInt("fillGap", 0)
        fillExpand = prefs.getInt("fillExpand", 1)
        fillEnclose = prefs.getBoolean("fillEnclose", false)
        fillRef = prefs.getInt("fillRef", if (prefs.getBoolean("fillReferenceAll", true)) FillOptions.REF_ALL else FillOptions.REF_CURRENT)
        fillOpacity = prefs.getFloat("fillOpacity", 1f)
        wandTolerance = prefs.getInt("wandTolerance", 10)
        wandGap = prefs.getInt("wandGap", 0)
        wandExpand = prefs.getInt("wandExpand", 0)
        selModifyPx = prefs.getInt("selModifyPx", 4)
        shapeKind = prefs.getInt("shapeKind", 1).coerceIn(0, 4)
        shapeFill = prefs.getInt("shapeFill", 0).coerceIn(0, 2)
        textSize = prefs.getFloat("textSize", 48f).coerceIn(4f, 1000f)
        textFont = prefs.getInt("textFont", 0).coerceIn(0, 2)
        textVertical = prefs.getBoolean("textVertical", false)
        animFps = prefs.getInt("animFps", 12)
        subOpen = prefs.getBoolean("subOpen", false)
        selLauncher = prefs.getBoolean("selLauncher", true)
        navOpen = prefs.getBoolean("navOpen", false)
        quickOpen = prefs.getBoolean("quickOpen", false)
        quickFx = prefs.getFloat("quickFx", 0f)
        quickFy = prefs.getFloat("quickFy", 1f)
        prefs.getString("quickActions", null)?.let { q ->
            quickActions.clear()
            quickActions.addAll(q.split(",").filter { it.isNotBlank() })
        }
        navFx = prefs.getFloat("navFx", 1f)
        navFy = prefs.getFloat("navFy", 1f)
        subUri = prefs.getString("subUri", null)
        subFx = prefs.getFloat("subFx", 0.72f)
        subFy = prefs.getFloat("subFy", 0.55f)
        subW = prefs.getInt("subW", 320).coerceIn(160, 900)
        subH = prefs.getInt("subH", 260).coerceIn(120, 900)
        wandRef = prefs.getInt("wandRef", if (prefs.getBoolean("wandReferenceAll", true)) FillOptions.REF_ALL else FillOptions.REF_CURRENT)
        gradientRadial = prefs.getBoolean("gradientRadial", false)
        gradientToTransparent = prefs.getBoolean("gradientToTransparent", false)
        gradientPreset = prefs.getInt("gradientPreset", if (gradientToTransparent) 1 else 0)
        gradientRepeat = prefs.getInt("gradientRepeat", 0).coerceIn(0, 2)
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
        colorTab = prefs.getInt("colorTab", 0).coerceIn(0, 4)
        prefs.getString("mixCorners", null)?.split(",")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 }?.forEachIndexed { i, c -> mixCorners[i] = c }
        prefs.getString("palette", null)?.let { p ->
            palette.clear()
            p.split(",").mapNotNull { it.toIntOrNull() }.let { palette.addAll(it) }
        }
    }

    val fillOptions: FillOptions get() = FillOptions(fillTolerance, fillGap, fillExpand, fillRef)
    val wandOptions: FillOptions get() = FillOptions(wandTolerance, wandGap, wandExpand, wandRef)
    val gradientSpec: GradientSpec get() = GradientSpec(gradientRadial, gradientPreset == 1, gradientPreset, gradientRepeat)
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
            .putFloat("postSmoothing", postSmoothing)
            .putBoolean("drawWithFinger", drawWithFinger)
            .putInt("primaryColor", primaryColor)
            .putInt("secondaryColor", secondaryColor)
            .putString("lastUri", lastUri)
            .putInt("selectShape", selectShape.ordinal)
            .putInt("selectMode", selectMode.ordinal)
            .putInt("fillTolerance", fillTolerance)
            .putInt("fillGap", fillGap)
            .putInt("fillExpand", fillExpand)
            .putBoolean("fillEnclose", fillEnclose)
            .putInt("fillRef", fillRef)
            .putFloat("fillOpacity", fillOpacity)
            .putInt("wandTolerance", wandTolerance)
            .putInt("wandGap", wandGap)
            .putInt("wandExpand", wandExpand)
            .putInt("selModifyPx", selModifyPx)
            .putInt("shapeKind", shapeKind)
            .putInt("shapeFill", shapeFill)
            .putFloat("textSize", textSize)
            .putInt("textFont", textFont)
            .putBoolean("textVertical", textVertical)
            .putInt("animFps", animFps)
            .putBoolean("subOpen", subOpen)
            .putBoolean("selLauncher", selLauncher)
            .putBoolean("navOpen", navOpen)
            .putBoolean("quickOpen", quickOpen)
            .putFloat("quickFx", quickFx)
            .putFloat("quickFy", quickFy)
            .putString("quickActions", quickActions.joinToString(","))
            .putFloat("navFx", navFx)
            .putFloat("navFy", navFy)
            .putString("subUri", subUri)
            .putFloat("subFx", subFx)
            .putFloat("subFy", subFy)
            .putInt("subW", subW)
            .putInt("subH", subH)
            .putInt("wandRef", wandRef)
            .putBoolean("gradientRadial", gradientRadial)
            .putBoolean("gradientToTransparent", gradientToTransparent)
            .putInt("gradientPreset", gradientPreset)
            .putInt("gradientRepeat", gradientRepeat)
            .putFloat("gradientOpacity", gradientOpacity)
            .putBoolean("canvasTransparent", canvasTransparent)
            .putInt("canvasBackground", canvasBackground)
            .putString("recentColors", recentColors.joinToString(","))
            .putInt("colorTab", colorTab)
            .putString("mixCorners", mixCorners.joinToString(","))
            .putString("palette", palette.joinToString(","))
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
