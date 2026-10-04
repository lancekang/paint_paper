package kr.dfluid.paint.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kr.dfluid.paint.brush.Brush
import kr.dfluid.paint.brush.StrokeBuilder
import kr.dfluid.paint.document.BlendMode
import kr.dfluid.paint.document.CanvasEdit
import kr.dfluid.paint.document.CompoundCommand
import kr.dfluid.paint.document.Document
import kr.dfluid.paint.document.DocumentCommand
import kr.dfluid.paint.document.DocumentData
import kr.dfluid.paint.document.History
import kr.dfluid.paint.document.HistoryCommand
import kr.dfluid.paint.document.LayerProps
import kr.dfluid.paint.document.LayerStore
import kr.dfluid.paint.document.Node
import kr.dfluid.paint.document.NodeData
import kr.dfluid.paint.document.NodeInfo
import kr.dfluid.paint.document.NodeKind
import kr.dfluid.paint.document.ROOT_ID
import kr.dfluid.paint.document.SelectionCommand
import kr.dfluid.paint.document.ShapeEntry
import kr.dfluid.paint.document.StructureCommand
import kr.dfluid.paint.document.TextSpec
import kr.dfluid.paint.document.TilesCommand
import kr.dfluid.paint.document.VStroke
import kr.dfluid.paint.document.VectorCommand
import kr.dfluid.paint.document.TreeShape
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 채우기 옵션. */
/** [ref] = 어느 그림을 보고 영역을 찾을지: REF_ALL / REF_CURRENT / REF_MARKED */
data class FillOptions(val tolerance: Int, val gap: Int, val expand: Int, val ref: Int) {
    companion object {
        const val REF_ALL = 0
        const val REF_CURRENT = 1
        const val REF_MARKED = 2
    }
}

/** 그라데이션 옵션. */
/**
 * [preset]: 0 주색→보조색, 1 주색→투명, 2 이상은 [GRADIENT_PRESETS]의 고정 색.
 * [repeat]: 0 끝에서 멈춤, 1 반복, 2 거울 반복.
 */
data class GradientSpec(val radial: Boolean, val toTransparent: Boolean, val preset: Int = if (toTransparent) 1 else 0, val repeat: Int = 0) {
    /** 정지점 [위치, r, g, b, a(프리멀티플라이드)] × n (최대 8) */
    fun stops(fg: Int, bg: Int): FloatArray {
        fun c(pos: Float, color: Int, a: Float = 1f): List<Float> {
            val p = CanvasRenderer.premul(color, a)
            return listOf(pos, p[0], p[1], p[2], p[3])
        }
        val list = when (preset) {
            0 -> c(0f, fg) + c(1f, bg)
            1 -> c(0f, fg) + c(1f, fg, 0f)
            else -> {
                val cols = GRADIENT_PRESETS.getOrNull(preset - 2)?.second ?: intArrayOf(fg, bg)
                cols.indices.flatMap { i -> c(i / (cols.size - 1f), cols[i]) }
            }
        }
        return list.toFloatArray()
    }

    companion object {
        /** 이름과 색 (앞에서부터 고르게) */
        val GRADIENT_PRESETS: List<Pair<String, IntArray>> = listOf(
            "무지개" to intArrayOf(0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF3949AB.toInt(), 0xFF8E24AA.toInt()),
            "노을" to intArrayOf(0xFF2B1B4D.toInt(), 0xFF8E3A8C.toInt(), 0xFFF2645A.toInt(), 0xFFFFB86B.toInt()),
            "하늘" to intArrayOf(0xFF1E5AA8.toInt(), 0xFF6FB3E8.toInt(), 0xFFE8F4FB.toInt()),
            "바다" to intArrayOf(0xFF03224C.toInt(), 0xFF0B6E99.toInt(), 0xFF5ED3D1.toInt()),
            "금속" to intArrayOf(0xFF6E6E6E.toInt(), 0xFFF2F2F2.toInt(), 0xFF8A8A8A.toInt(), 0xFFDADADA.toInt()),
        )
    }
}

    /**
 * 렌더링 엔진의 중심. GL 리소스는 모두 GL 스레드에서만 만집니다.
 *
 * UI 스레드는 public 메서드를 호출하고, 이 메서드들은 명령을 큐에 넣은 뒤 프레임을 요청합니다.
 * 명령은 다음 onDrawFrame 시작 시 순서대로 실행됩니다. 결과는 Listener로 메인 스레드에 전달됩니다.
 *
 * 합성: 활성 레이어보다 아래(루트 기준)는 belowCache에 한 번 합성해 두고,
 * 매 프레임은 바뀐 영역(dirty)만 belowCache + 활성 레이어 이상을 다시 합성합니다.
 */
class CanvasRenderer(private val listener: Listener) : GLSurfaceView.Renderer, LayerStore {

    interface Listener {
        fun onGlReady(maxTextureSize: Int)
        fun onDocumentReplaced(width: Int, height: Int, refit: Boolean)
        /** liveRasterIds = 접힌 폴더 안까지 포함한 모든 래스터 id (썸네일 정리용) */
        fun onLayersChanged(nodes: List<NodeInfo>, activeId: Int, liveRasterIds: Set<Int>)
        fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean)
        fun onSelectionChanged(hasSelection: Boolean)
        fun onThumbnail(id: Int, bitmap: Bitmap)
        /** 자유 변형 시작: 떠 있는 픽셀 크기와 초기 행렬 [a,b,c,d,tx,ty] */
        fun onTransformStarted(width: Int, height: Int, matrix: FloatArray)
        fun onTransformEnded()
        fun onRendererError(message: String)
        /** 성능 측정이 켜져 있으면 0.5초마다 */
        fun onPerfStats(stats: PerfMonitor.Stats, memory: String) = Unit
        /** 합성 벤치마크 결과 (여러 줄 문자열) */
        fun onBenchmarkDone(report: String) = Unit
        /** 필터 미리보기가 끝남 (적용·취소·시작 실패 모두) */
        fun onFilterEnded() = Unit
        /** 내비게이터 축소판 (그림이 바뀌면 최대 0.5초마다) */
        fun onNavigator(bitmap: Bitmap) = Unit
        /** 애니메이션 상태: 폴더가 있는지, 현재 프레임(0부터), 프레임 수 */
        fun onAnimation(exists: Boolean, frame: Int, count: Int) = Unit
    }

    /** CanvasView가 설정합니다. */
    var requestRender: () -> Unit = {}

    /** 팁 이미지 찾기 (벡터 선을 다시 그릴 때, 아직 올리지 않은 팁). GL 스레드에서 불림. */
    var tipProvider: (String) -> kr.dfluid.paint.brush.TipImage? = { null }

    private val main = Handler(Looper.getMainLooper())
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private val worker = Executors.newSingleThreadExecutor()

    // ---- UI 스레드가 쓰고 GL 스레드가 읽는 값 ----
    @Volatile private var view = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f, 1f)
    @Volatile private var cursor: FloatArray? = null
    @Volatile private var resumeGeneration = 0
    /** 문서 내용이 바뀔 때마다 증가. 저장 여부 판단용. */
    @Volatile var version = 0L
        private set
    @Volatile var maxTextureSize = 4096
        private set
    @Volatile var hasSelection = false
        private set
    val perf = PerfMonitor()
    /** 캔버스 밖을 칠할 색 (테마) */
    @Volatile private var backdrop = floatArrayOf(0.19f, 0.195f, 0.205f)

    fun setBackdrop(color: Int) {
        backdrop = floatArrayOf(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
        requestRender()
    }

    /** UI를 다시 만든 뒤 모든 레이어·마스크 썸네일을 다시 보내 달라고 할 때. */
    fun requestThumbnails() = post {
        thumbQueue.addAll(surfaces.keys)
    }
    /** 활성 레이어의 마스크를 편집 중 (활성 레이어에 마스크가 있을 때만 의미 있음). UI는 읽기만. */
    @Volatile var maskEditing = false
        private set
    /** 마스크 적용용 임시 버퍼 (캔버스 크기, 필요할 때 만듦) */
    private var maskTmp: RenderTarget? = null
    /** 색 혼합: 작업 버퍼(캔버스 크기)와 스탬프 둘레 복사본 (필요할 때 만듦) */
    private var smudgeBuf: RenderTarget? = null
    /** 벡터 레이어의 선 목록 (레이어 id → 선). 레이어를 지워도 실행취소용으로 남겨 둡니다 */
    private val vectors = HashMap<Int, List<VStroke>>()

    // ---- 애니메이션 ----
    /** 현재 프레임 (애니메이션 폴더의 자식 번호, 0 = 맨 아래 자식) */
    @Volatile private var animFrame = 0
    /** 어니언 스킨 (앞뒤 프레임을 빨강·파랑으로 비춰 보기). 재생 중에는 끔 */
    @Volatile var onionSkin = true
        private set
    @Volatile private var animPlaying = false
    /** 경계 효과 작업 버퍼 3장 (캔버스 크기, 쓰는 레이어가 있을 때만 만듦) */
    private var borderBufs: Array<RenderTarget>? = null
    // ---- 내비게이터 ----
    /** UI가 켜 두면 그림이 바뀔 때 축소판을 보냄 */
    @Volatile var navigatorOn = false
        set(v) {
            field = v
            if (v) { navSent = -1L; requestRender() }
        }
    private var navSent = -1L
    private var navTime = 0L
    private var navTarget: RenderTarget? = null
    private var navScheduled = false

    /** 톤 효과 결과 버퍼 (캔버스 크기) */
    private var toneBuf: RenderTarget? = null
    /** 레이어 컬러 결과 버퍼 */
    private var colorBuf: RenderTarget? = null
    private var smudgePatch: RenderTarget? = null
    var defaultWidth = 2048
    var defaultHeight = 2048
    /** 앱 첫 실행 시 기본 캔버스의 배경색 (null = 투명). */
    @Volatile var defaultBackground: Int? = Color.WHITE

    // ---- GL 스레드 상태 ----
    private var glReady = false
    private lateinit var brushEngine: BrushEngine
    private lateinit var compositor: Compositor
    private val pool = TilePool()
    private val surfaces = HashMap<Int, TileSurface>()
    private var doc: Document? = null
    private val history = History()

    private var strokeBuf: RenderTarget? = null
    private var selTex: RenderTarget? = null
    private var selection: SelectionMask? = null
    private var selEncoded: ByteArray? = null
    private var selBounds: IRect? = null
    private var preview: RenderTarget? = null
    private var belowCache: RenderTarget? = null
    private var belowValid = false
    private var belowStartId = -1
    private val pairs = ArrayList<PingPong>()
    private var tileTmp: RenderTarget? = null
    private var thumbTarget: RenderTarget? = null
    private var compResult: RenderTarget? = null
    private var fullDirty = true
    private var dirtyRegion: IRect? = null
    private var mipDirty = true
    private var screenW = 1
    private var screenH = 1
    private val tips = HashMap<String, Int>()
    private val thumbQueue = LinkedHashSet<Int>()
    private var antsScheduled = false

    private val pendingPicks = ArrayList<Pair<IntArray, (Int) -> Unit>>()

    /** 활성 레이어 위에 겹쳐 보여 주는 진행 중 작업. */
    private sealed class Op {
        class Stroke(val brush: Brush, val color: FloatArray, val tipTex: Int, val colorInt: Int = 0) : Op() {
            var rect: IRect? = null
            /** 벡터 레이어에 남길 스탬프 (받은 순서대로) */
            val all = ArrayList<FloatArray>()
            var fill: FloatArray? = null
        }

        /** 벡터 지우개: 닿은 선을 통째로 지움. saved = 처음 바꾸기 전 타일 */
        class VErase(val layerId: Int, val before: List<VStroke>) : Op() {
            var current: List<VStroke> = before
            val saved = HashMap<Int, ByteBuffer?>()
        }

        /**
         * 색 혼합 획. work = 활성 레이어 복사본(캔버스 크기)을 스탬프마다 직접 고칩니다.
         * prev = 대칭 복제본(채널)마다 직전 스탬프 위치.
         */
        class Smudge(val brush: Brush, val work: RenderTarget, val lock: Boolean) : Op() {
            var rect: IRect? = null
            val prev = HashMap<Int, FloatArray>()
        }

        class Gradient(val kind: Int, val stops: FloatArray, val repeat: Int, var p: FloatArray, val opacity: Float) : Op()

        class Transform(val floating: RenderTarget, val bounds: IRect, var m: FloatArray, val whole: Boolean) : Op() {
            var lastRect: IRect = bounds
            /** 원근·자유 모서리 모드의 행렬 (행 우선 9개). null이면 [m] (아핀) */
            var h: FloatArray? = null
            /** 지금 쓰는 원근 행렬 (아핀도 이 형식으로) */
            val hm: FloatArray get() = h ?: Homography.fromAffine(m)
            /** 마스크 떠 있는 픽셀의 원근 행렬 */
            fun maskH(): FloatArray? {
                val mb = maskBounds ?: return null
                return Homography.preTranslate(hm, (mb.x - bounds.x).toFloat(), (mb.y - bounds.y).toFloat())
            }
            /** 레이어를 옮길 때 함께 옮기는 마스크 (마스크 편집 중이 아니고 마스크가 있을 때) */
            var maskFloating: RenderTarget? = null
            var maskBounds: IRect? = null

        }

        /**
         * 필터 미리보기. [bounds] 크기 버퍼 3장: orig = 원본, work·result = 중간·결과.
         * 값이 바뀌면 stale만 표시하고, 다음 합성 때 한 번만 다시 계산합니다 (슬라이더 이벤트가 몰려도).
         */
        class Filter(var spec: FilterSpec, val bounds: IRect, val target: Int, val orig: RenderTarget, val work: RenderTarget, val result: RenderTarget) : Op() {
            var stale = true

            fun release() {
                orig.release(); work.release(); result.release()
            }
        }
    }

    private var op: Op? = null

    /** 일시정지 시 잡아둔 픽셀. GL 컨텍스트를 잃었을 때 복원용. */
    private var lostSnapshot: DocumentData? = null
    private var snapshotGeneration = 0

    private class PingPong(val a: RenderTarget, val b: RenderTarget) {
        var cur = a
        var other = b
        fun swap() {
            val t = cur; cur = other; other = t
        }

        fun release() {
            a.release(); b.release()
        }
    }

    private sealed class Src {
        class Tiles(val s: TileSurface) : Src()
        class Tex(val t: RenderTarget) : Src()
    }

    private fun post(cmd: () -> Unit) {
        commands.add(cmd)
        requestRender()
    }

    // =====================================================================
    // UI 스레드 API — 보기
    // =====================================================================

    fun setView(v: FloatArray) {
        view = v
        requestRender()
    }

    /** x, y = 화면 좌표(왼쪽 위 원점), radius = 화면 px. */
    fun setCursor(x: Float, y: Float, radius: Float) {
        cursor = floatArrayOf(x, y, radius)
        requestRender()
    }

    fun hideCursor() {
        if (cursor != null) {
            cursor = null
            requestRender()
        }
    }

    fun notifyResumed() {
        resumeGeneration++
    }

    // =====================================================================
    // UI 스레드 API — 그리기
    // =====================================================================

    /** tip = 사용자 팁 이미지 (R8, size×size). 처음 쓰는 팁이면 업로드합니다. */
    fun beginStroke(brush: Brush, color: Int, tip: kr.dfluid.paint.brush.TipImage?) = post {
        val n = editableActive(allowVector = true) ?: return@post
        finishOp()
        val onVector = n.props.vector && !isMaskEdit(n)
        if (onVector && brush.isBlend) {
            reportError("벡터 레이어에는 색 혼합 도구를 쓸 수 없습니다. 레이어 ⋯ → 래스터화 하세요.")
            return@post
        }
        if (onVector && brush.isEraser) {
            op = Op.VErase(n.id, vectors[n.id] ?: emptyList())
            return@post
        }
        if (onVector && (hasSelection || n.props.alphaLock)) {
            // 선 데이터에는 선택·잠금 잘림이 남지 않아, 나중에 다시 그리면 잘린 부분이 되살아남
            reportError("벡터 레이어에는 선택 영역이나 투명 픽셀 잠금 안에서 그릴 수 없습니다. 선택을 해제하거나 래스터화하세요.")
            return@post
        }
        if (brush.isBlend) {
            beginSmudge(n, brush)
            return@post
        }
        val tipTex = if (tip != null) ensureTip(tip) else 0
        val c = premul(color, 1f)
        strokeBuf!!.clear()
        op = Op.Stroke(brush, c, tipTex, color)
        if (!n.props.visible) main.post { listener.onRendererError("숨겨진 레이어에 그리고 있습니다.") }
    }

    /** [channel] = 대칭 복제본 번호 (0 = 원본). 색 혼합이 복제본마다 직전 위치를 따로 기억합니다. */
    fun addStamps(stamps: FloatArray, channel: Int = 0) = post {
        (op as? Op.Smudge)?.let { smudgeStamps(it, stamps, channel); return@post }
        (op as? Op.VErase)?.let { vectorErase(it, stamps); return@post }
        val o = op as? Op.Stroke ?: return@post
        val d = doc ?: return@post
        o.all.add(stamps.copyOf())
        brushEngine.draw(strokeBuf!!, stamps, o.brush, o.tipTex)
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i < stamps.size) {
            val rad = stamps[i + 2] + 2f
            l = min(l, stamps[i] - rad); t = min(t, stamps[i + 1] - rad)
            r = max(r, stamps[i] + rad); b = max(b, stamps[i + 1] + rad)
            i += StrokeBuilder.FLOATS
        }
        val rect = IRect.ofBounds(l, t, r, b, d.width, d.height) ?: return@post
        o.rect = rect.union(o.rect)
        markDirty(rect)
    }

    /**
     * 직선 자 미리보기: 스트로크 버퍼를 비우고 주어진 스탬프로 다시 그립니다.
     * 매 이동마다 호출해 시작점→현재점 직선을 갱신합니다. (대칭 복제본 포함 전체 스탬프를 한 번에 넘길 것)
     */
    fun setStrokeLine(stamps: FloatArray, fill: FloatArray? = null) = post {
        val o = op as? Op.Stroke ?: return@post
        val d = doc ?: return@post
        strokeBuf!!.clear()
        val prev = o.rect
        o.rect = null
        o.all.clear()
        if (stamps.isNotEmpty()) o.all.add(stamps.copyOf())
        o.fill = fill?.takeIf { it.size >= 6 }?.copyOf()
        // 채우기 다각형 (도형 도구): 먼저 커버리지로 올리고, 선 스탬프는 그 위에 최댓값으로 겹칩니다.
        if (fill != null && fill.size >= 6) o.rect = rasterizeFill(fill, d)
        if (stamps.isNotEmpty()) {
            brushEngine.draw(strokeBuf!!, stamps, o.brush, o.tipTex)
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            var i = 0
            while (i < stamps.size) {
                val rad = stamps[i + 2] + 2f
                l = min(l, stamps[i] - rad); t = min(t, stamps[i + 1] - rad)
                r = max(r, stamps[i] + rad); b = max(b, stamps[i + 1] + rad)
                i += StrokeBuilder.FLOATS
            }
            IRect.ofBounds(l, t, r, b, d.width, d.height)?.let { o.rect = it.union(o.rect) }
        }
        // 이전 직선이 있던 영역까지 다시 합성해야 지워집니다.
        val dirty = o.rect?.union(prev) ?: prev
        dirty?.let { markDirty(it) }
    }

    fun endStroke() = post { finishOp() }

    /** 다각형 [pts](캔버스 좌표 x,y…)를 안티에일리어싱해 strokeBuf의 그 영역에 올립니다. 영역을 돌려줍니다. */
    private fun rasterizeFill(pts: FloatArray, d: Document): IRect? {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < pts.size) {
            l = min(l, pts[i]); r = max(r, pts[i]); t = min(t, pts[i + 1]); b = max(b, pts[i + 1]); i += 2
        }
        val rect = IRect.ofBounds(l - 1f, t - 1f, r + 1f, b + 1f, d.width, d.height) ?: return null
        val path = android.graphics.Path()
        path.moveTo(pts[0] - rect.x, pts[1] - rect.y)
        i = 2
        while (i + 1 < pts.size) {
            path.lineTo(pts[i] - rect.x, pts[i + 1] - rect.y); i += 2
        }
        path.close()
        val bmp = Bitmap.createBitmap(rect.w, rect.h, Bitmap.Config.ALPHA_8)
        android.graphics.Canvas(bmp).drawPath(path, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        val rb = bmp.rowBytes
        val raw = ByteBuffer.allocate(rb * rect.h)
        bmp.copyPixelsToBuffer(raw)
        bmp.recycle()
        val tight = GlUtil.byteBuffer(rect.w * rect.h)
        val arr = raw.array()
        for (y in 0 until rect.h) tight.put(arr, y * rb, rect.w)
        tight.rewind()
        strokeBuf!!.upload(rect.x, rect.y, rect.w, rect.h, tight)
        return rect
    }

    fun cancelStroke() = post {
        if (op is Op.VErase) {
            finishOp(); return@post
        }
        (op as? Op.Smudge)?.let { s ->
            op = null
            s.rect?.let { markDirty(it) }
            return@post
        }
        val o = op as? Op.Stroke ?: return@post
        op = null
        o.rect?.let { markDirty(it) }
    }

    /** 변형/그라데이션/필터 중의 실행취소는 그 작업만 취소합니다. */
    fun undo() = post {
        if (op is Op.Transform || op is Op.Gradient || op is Op.Filter) {
            cancelPreviewOps(); return@post
        }
        finishOp()
        if (history.undo(this)) afterHistory()
    }

    fun redo() = post {
        if (op is Op.Transform || op is Op.Gradient || op is Op.Filter) {
            cancelPreviewOps(); return@post
        }
        finishOp()
        if (history.redo(this)) afterHistory()
    }

    // =====================================================================
    // UI 스레드 API — 채우기 / 그라데이션
    // =====================================================================

    fun fillAt(x: Int, y: Int, opts: FillOptions, color: Int, opacity: Float) = post {
        val d = doc ?: return@post
        val n = editableActive() ?: return@post
        finishOp()
        val ref = referenceImage(d, n, opts.ref)
        val sel = if (hasSelection) selection?.toBuffer() else null
        val w = d.width
        val h = d.height
        val docRef = d
        val targetId = n.id
        worker.execute {
            val res = try {
                FloodFill.run(ref, w, h, x, y, opts.tolerance, opts.gap, opts.expand, sel)
            } catch (e: OutOfMemoryError) {
                main.post { listener.onRendererError("메모리가 부족해 채우지 못했습니다.") }
                null
            } ?: return@execute
            post { applyFill(docRef, targetId, res, color, opacity) }
        }
    }

    /** 둘러싸고 칠하기: 올가미 [pts](캔버스 좌표) 안의 닫힌 영역을 모두 채웁니다. */
    fun fillEnclosed(pts: FloatArray, opts: FillOptions, color: Int, opacity: Float) = post {
        val d = doc ?: return@post
        val n = editableActive() ?: return@post
        finishOp()
        if (pts.size < 6) return@post
        val ref = referenceImage(d, n, opts.ref)
        val sel = if (hasSelection) selection?.toBuffer() else null
        val w = d.width
        val h = d.height
        val docRef = d
        val targetId = n.id
        worker.execute {
            val res = try {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
                android.graphics.Canvas(bmp).drawPath(SelectionMask.shapePath(SelShape.LASSO, pts), android.graphics.Paint().apply { this.color = Color.BLACK })
                val rb = bmp.rowBytes
                val raw = ByteBuffer.allocate(rb * h)
                bmp.copyPixelsToBuffer(raw)
                bmp.recycle()
                val arr = raw.array()
                val lasso = ByteArray(w * h)
                for (y in 0 until h) System.arraycopy(arr, y * rb, lasso, y * w, w)
                FloodFill.enclose(ref, w, h, lasso, opts.tolerance, opts.gap, opts.expand, sel)
            } catch (e: OutOfMemoryError) {
                main.post { listener.onRendererError("메모리가 부족해 채우지 못했습니다.") }
                return@execute
            }
            if (res == null) {
                main.post { listener.onRendererError("올가미 안에 선으로 닫힌 영역이 없습니다.") }
                return@execute
            }
            post { applyFill(docRef, targetId, res, color, opacity) }
        }
    }

    /** 채우기·자동 선택이 참고할 이미지: 모든 레이어 합성 결과 또는 [n] 레이어만. */
    /** true면 합성에서 밑그림 레이어를 뺌 (내보내기·채우기 참조용으로 잠깐만) */
    private var hideDrafts = false

    /** 밑그림을 뺀 합성 결과. 밑그림이 없으면 평소 합성 그대로. 화면용 합성은 다음 프레임에 다시 만듭니다. */
    private fun cleanComposite(d: Document): ByteBuffer {
        if (d.allNodes().none { it.props.draft }) {
            ensureComposite(d)
            return compResult!!.readAll()
        }
        hideDrafts = true
        belowValid = false
        markAllDirty()
        try {
            ensureComposite(d)
            return compResult!!.readAll()
        } finally {
            hideDrafts = false
            belowValid = false
            markAllDirty()
        }
    }

    private fun referenceImage(d: Document, n: Node, ref: Int): ByteBuffer {
        if (ref == FillOptions.REF_MARKED) {
            // 참조 레이어로 지정한 보이는 레이어들만 아래 → 위로 겹쳐서
            val marked = d.allNodes().filter { it.isRaster && it.props.reference && visibleInTree(it) }
            if (marked.isNotEmpty()) {
                val pv = preview!!
                pv.clear()
                pv.bind()
                GlState.over()
                val full = IRect(0, 0, d.width, d.height)
                for (m in marked) drawSourceCopy(Src.Tiles(surfaces[m.id]!!), m.props.opacity, full)
                GlState.off()
                markAllDirty() // preview 버퍼를 빌려 썼음
                return pv.readAll()
            }
            main.post { listener.onRendererError("참조 레이어가 없어 모든 레이어를 보고 찾습니다. 레이어 ⋯ 메뉴에서 지정하세요.") }
        }
        if (ref != FillOptions.REF_CURRENT) return cleanComposite(d)
        val pv = preview!!
        pv.clear()
        pv.bind()
        drawSourceCopy(Src.Tiles(surfaces[n.id]!!), 1f, IRect(0, 0, d.width, d.height))
        markAllDirty() // preview 버퍼를 빌려 썼음
        return pv.readAll()
    }

    /**
     * 자동 선택: (x, y)와 비슷한 색으로 이어진 영역을 [selOp]로 선택 영역에 합칩니다.
     * 영역 계산은 채우기와 같은 [FloodFill]을 백그라운드에서 돌립니다.
     */
    fun selectByColor(x: Int, y: Int, opts: FillOptions, selOp: SelOp) = post {
        val d = doc ?: return@post
        if (op is Op.Transform) return@post
        val n = if (opts.ref != FillOptions.REF_CURRENT) d.activeRaster ?: d.allNodes().firstOrNull { it.isRaster } else editableActive(allowText = true, allowVector = true, allowLocked = true)
        if (n == null) return@post
        cancelPreviewOps()
        val ref = referenceImage(d, n, opts.ref)
        val w = d.width
        val h = d.height
        val docRef = d
        worker.execute {
            val res = try {
                FloodFill.run(ref, w, h, x, y, opts.tolerance, opts.gap, opts.expand, null)
            } catch (e: OutOfMemoryError) {
                main.post { listener.onRendererError("메모리가 부족해 자동 선택을 하지 못했습니다.") }
                null
            } ?: return@execute
            post { applyWand(docRef, res, selOp) }
        }
    }

    private fun applyWand(docRef: Document, res: FloodFill.Result, selOp: SelOp) {
        val d = doc
        if (d !== docRef) return
        if (op != null) {
            if (op is Op.Transform) return
            main.postDelayed({ post { applyWand(docRef, res, selOp) } }, 60)
            return
        }
        val before = selEncoded
        val m = selection ?: SelectionMask(d.width, d.height).also { selection = it }
        if (!hasSelection) m.clear()
        // 선택이 없는데 빼기/교차면 결과도 없음 → 그대로 두면 됩니다 (select()와 같은 규칙).
        m.applyMask(res.mask, selOp)
        commitSelectionChange(before)
    }

    /** 채우기 결과 반영. 다른 작업(획·변형)이 진행 중이면 끝날 때까지 미룹니다. */
    private fun applyFill(docRef: Document, targetId: Int, res: FloodFill.Result, color: Int, opacity: Float) {
        val d = doc
        if (d !== docRef) return
        if (op != null) {
            main.postDelayed({ post { applyFill(docRef, targetId, res, color, opacity) } }, 60)
            return
        }
        if (d.activeId != targetId) return
        val buf = GlUtil.byteBuffer(d.width * d.height)
        buf.put(res.mask)
        buf.rewind()
        val sb = strokeBuf!!
        sb.uploadAll(buf)
        commitCoverage(sb.tex, premul(color, 1f), opacity, res.bounds, eraser = false, useSel = false)
    }

    /** 선택 영역(없으면 전체)을 색으로 채웁니다. */
    fun fillSelection(color: Int) = post {
        val d = doc ?: return@post
        editableActive() ?: return@post
        finishOp()
        val (tex, rect) = selectionCoverage(d) ?: return@post
        commitCoverage(tex, premul(color, 1f), 1f, rect, eraser = false, useSel = false)
    }

    /** 그라데이션 미리보기. 처음 부르면 시작, 이후엔 갱신. */
    fun gradientPreview(x0: Float, y0: Float, x1: Float, y1: Float, spec: GradientSpec, fg: Int, bg: Int, opacity: Float) = post {
        val d = doc ?: return@post
        editableActive() ?: return@post
        val cur = op
        if (cur is Op.Gradient) {
            cur.p = floatArrayOf(x0, y0, x1, y1)
        } else {
            finishOp()
            op = Op.Gradient(if (spec.radial) 2 else 1, spec.stops(fg, bg), spec.repeat, floatArrayOf(x0, y0, x1, y1), opacity)
        }
        markDirty(gradientRect(d))
    }

    fun gradientCommit() = post { if (op is Op.Gradient) finishOp() }

    fun gradientCancel() = post {
        if (op is Op.Gradient) {
            op = null
            markAllDirty()
        }
    }

    // =====================================================================
    // UI 스레드 API — 필터 · 색조 보정
    // =====================================================================

    /**
     * 활성 레이어(마스크 편집 중이면 마스크)에 필터 미리보기를 시작합니다.
     * 선택 영역이 있으면 그 안만. 값은 [setFilter]로 바꾸고 [commitFilter]/[cancelFilter]로 끝냅니다.
     */
    fun beginFilter(spec: FilterSpec) = post {
        if (!beginFilterGl(spec)) main.post { listener.onFilterEnded() }
    }

    fun setFilter(spec: FilterSpec) = post {
        val o = op as? Op.Filter ?: return@post
        o.spec = spec
        o.stale = true
        markDirty(o.bounds)
    }

    fun commitFilter() = post { if (op is Op.Filter) finishOp() }

    fun cancelFilter() = post { (op as? Op.Filter)?.let { endFilter(it) } }

    // =====================================================================
    // UI 스레드 API — 복사 · 붙여넣기
    // =====================================================================

    /** 복사한 픽셀: 캔버스 (x, y)에 있던 w×h 프리멀티플라이드 RGBA (위→아래 행) */
    private class Clip(val x: Int, val y: Int, val w: Int, val h: Int, val pixels: ByteBuffer)
    private var clip: Clip? = null
    @Volatile var hasClip = false
        private set

    /** 활성 레이어의 선택 영역(없으면 레이어 전체)을 복사. [cut]이면 그 자리를 지움. */
    fun copySelection(cut: Boolean) = post {
        finishOp()
        val d = doc ?: return@post
        val n = editableActive(allowText = !cut, allowVector = !cut, allowLocked = !cut) ?: return@post
        if (cut && n.props.alphaLock && !isMaskEdit(n)) {
            reportError("투명 픽셀 잠금 레이어는 잘라낼 수 없습니다. 복사를 쓰거나 잠금을 푸세요.")
            return@post
        }
        val s = surfaces[editId(n)] ?: return@post
        val area = (if (hasSelection) selBounds else s.tileBounds()?.let { contentBounds(s, it) }) ?: run {
            reportError("복사할 픽셀이 없습니다.")
            return@post
        }
        val pv = preview!!
        pv.clear(area)
        pv.bind()
        GlState.scissor(area)
        GlState.off()
        for ((key, t) in s.tiles) {
            if (!s.tileRect(key).intersects(area)) continue
            compositor.drawTile(t.tex, s.originX(key), s.originY(key), d.width, d.height, 1f, selTexIfAny(), if (hasSelection) 1 else 0)
        }
        GlState.noScissor()
        val px = pv.read(area.x, area.y, area.w, area.h)
        markAllDirty() // preview 버퍼를 빌려 썼음
        clip = Clip(area.x, area.y, area.w, area.h, px)
        hasClip = true
        if (cut) clearLayerGl(d, n)
        main.post { listener.onRendererError(if (cut) "잘라냈습니다." else "복사했습니다.") }
    }

    /** 복사한 픽셀을 활성 레이어 위 새 레이어로 (복사했던 자리에). 실행취소 한 단계. */
    fun paste() = post {
        finishOp()
        val c = clip ?: run {
            reportError("붙여넣을 것이 없습니다. 먼저 복사하세요.")
            return@post
        }
        pasteClip(c, "붙여넣기")
    }

    /**
     * 이미지(프리멀티플라이드 RGBA, w×h)를 캔버스 가운데 새 레이어로 넣고 바로 자유 변형을 시작합니다.
     * 크기·위치를 맞춘 뒤 확정하면 됩니다.
     */
    fun importImage(w: Int, h: Int, pixels: ByteBuffer, name: String) = post {
        finishOp()
        val d = doc ?: return@post
        val c = Clip((d.width - w) / 2, (d.height - h) / 2, w, h, pixels)
        if (pasteClip(c, name)) beginTransformGl()
    }

    /** [c]를 활성 노드 위 새 레이어로. 넣었으면 true */
    private fun pasteClip(c: Clip, name: String): Boolean {
        val d = doc ?: return false
        // 캔버스 크기가 바뀌었으면 넘치는 부분은 잘림
        val tiles = HashMap<Int, ByteBuffer>()
        val cols = TileMath.cols(d.width)
        val r = IRect(c.x, c.y, c.w, c.h).intersect(IRect(0, 0, d.width, d.height)) ?: return false
        val tx0 = r.x / TILE; val ty0 = r.y / TILE
        val tx1 = (r.right - 1) / TILE; val ty1 = (r.bottom - 1) / TILE
        val row = ByteArray(TILE * 4)
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val buf = GlUtil.byteBuffer(TILE_BYTES)
            var any = false
            for (yy in 0 until TILE) {
                val cy = ty * TILE + yy
                if (cy < r.y || cy >= r.bottom) continue
                val x0 = maxOf(r.x, tx * TILE)
                val x1 = minOf(r.right, tx * TILE + TILE)
                if (x1 <= x0) continue
                val len = (x1 - x0) * 4
                val src = ((cy - c.y) * c.w + (x0 - c.x)) * 4
                c.pixels.position(src)
                c.pixels.get(row, 0, len)
                buf.position((yy * TILE + (x0 - tx * TILE)) * 4)
                buf.put(row, 0, len)
                any = true
            }
            c.pixels.rewind()
            buf.rewind()
            if (any && !TileMath.isEmpty(buf)) tiles[ty * cols + tx] = buf
        }
        if (tiles.isEmpty()) return false
        structural { doc ->
            val a = doc.active
            val parent = a?.parent ?: doc.root
            val idx = if (a != null) a.index + 1 else parent.children.size
            val n = Node(doc.newId(), NodeKind.RASTER, LayerProps(name))
            insert(parent, idx, n)
            n.id
        }
        val s = surfaces[d.activeId] ?: return false
        if (s.tileCount == 0) tiles.forEach { (k, b) -> s.write(k, b) }
        thumbQueue.add(d.activeId)
        belowValid = false
        markAllDirty()
        return true
    }

    // =====================================================================
    // UI 스레드 API — 애니메이션
    // =====================================================================

    /** 문서의 애니메이션 폴더 (첫 번째 하나만 씁니다). */
    private fun animFolder(d: Document): Node? = d.allNodes().firstOrNull { it.isFolder && it.props.animation }

    /** 애니메이션 폴더를 만듭니다 (맨 위, 빈 프레임 1장). 이미 있으면 그 첫 프레임으로. */
    fun createAnimation() = post {
        finishOp()
        val d = doc ?: return@post
        if (animFolder(d) != null) {
            setFrameGl(d, 0)
            return@post
        }
        structural { doc ->
            val f = Node(doc.newId(), NodeKind.FOLDER, LayerProps("애니메이션", blend = BlendMode.PASS_THROUGH, animation = true))
            insert(doc.root, doc.root.children.size, f)
            val cel = Node(doc.newId(), NodeKind.RASTER, LayerProps("1"))
            insert(f, 0, cel)
            cel.id
        }
        animFrame = 0
        notifyLayers()
    }

    /** [i]번째 프레임으로 (그 셀을 활성 레이어로). playing = 재생 중이면 어니언 스킨을 그리지 않음. */
    fun setFrame(i: Int, playing: Boolean = false) = post {
        val d = doc ?: return@post
        // 재생 중에 그리고 있으면 그 획을 끊지 않도록 이번 틱은 건너뜀
        if (playing && op != null) return@post
        animPlaying = playing
        setFrameGl(d, i)
    }

    private fun setFrameGl(d: Document, i: Int) {
        val f = animFolder(d) ?: return
        if (f.children.isEmpty()) return
        val idx = Math.floorMod(i, f.children.size)
        if (op != null) finishOp()
        animFrame = idx
        val cel = f.children[idx]
        if (d.activeId != cel.id) {
            d.activeId = cel.id
            maskEditing = false
        }
        belowValid = false
        markAllDirty()
        notifyLayers()
    }

    /**
     * 내보내기용: [i]번째 프레임을 어니언 스킨 없이 합성해 돌려줍니다 (GL 스레드에서 콜백).
     * 화면의 현재 프레임은 그대로 되돌립니다. 프레임 수가 0이면 null.
     */
    fun captureFrame(i: Int, callback: (Int, Int, ByteBuffer?) -> Unit) = post {
        finishOp()
        val d = doc ?: run { callback(0, 0, null); return@post }
        val f = animFolder(d)
        if (f == null || f.children.isEmpty()) {
            callback(d.width, d.height, null)
            return@post
        }
        val keepFrame = animFrame
        val keepPlaying = animPlaying
        animFrame = i.coerceIn(0, f.children.size - 1)
        animPlaying = true
        belowValid = false
        markAllDirty()
        val buf = try {
            cleanComposite(d)
        } catch (e: OutOfMemoryError) {
            null
        }
        animFrame = keepFrame
        animPlaying = keepPlaying
        belowValid = false
        markAllDirty()
        callback(d.width, d.height, buf)
    }

    fun setOnionSkin(on: Boolean) = post {
        onionSkin = on
        markAllDirty()
    }

    /** 현재 프레임 다음에 새 프레임 ([duplicate]면 현재 셀 복제). */
    fun addFrame(duplicate: Boolean) = post {
        finishOp()
        val d = doc ?: return@post
        val f = animFolder(d) ?: return@post
        val at = (animFrame + 1).coerceAtMost(f.children.size)
        structural { doc ->
            val src = f.children.getOrNull(animFrame)
            val n = if (duplicate && src != null) cloneSubtree(doc, src, "${at + 1}")
            else Node(doc.newId(), NodeKind.RASTER, LayerProps("${at + 1}"))
            insert(f, at, n)
            renameFrames(f) // 구조 변경 기록 안에서 번호를 다시 매겨야 실행취소·다시실행과 맞음
            n.id
        }
        animFrame = at
        setFrameGl(d, at)
    }

    /** 현재 프레임 삭제 (한 장은 남김). */
    fun deleteFrame() = post {
        finishOp()
        val d = doc ?: return@post
        val f = animFolder(d) ?: return@post
        if (f.children.size <= 1) {
            reportError("프레임이 한 장은 있어야 합니다.")
            return@post
        }
        val idx = animFrame.coerceIn(0, f.children.size - 1)
        structural { _ ->
            f.children.removeAt(idx)
            renameFrames(f)
            f.children[(idx - 1).coerceAtLeast(0)].id
        }
        animFrame = (idx - 1).coerceAtLeast(0)
        setFrameGl(d, animFrame)
    }

    /** 프레임 이름이 숫자뿐이면 순서대로 다시 매김 (사용자가 바꾼 이름은 그대로). */
    private fun renameFrames(f: Node) {
        f.children.forEachIndexed { i, c ->
            val want = "${i + 1}"
            if (c.props.name != want && c.props.name.all { it.isDigit() }) c.props = c.props.copy(name = want)
        }
    }

    /** 애니메이션 폴더: 현재 프레임 셀만, 어니언 스킨이면 앞(빨강)·뒤(파랑) 셀을 옅게 먼저. */
    private fun composeAnim(n: Node, t: PingPong, r: IRect, depth: Int) {
        if (n.children.isEmpty()) return
        val g = pairAt(depth + 1)
        g.cur.clear(r)
        animContent(n, g, r, depth)
        drawSource(Src.Tex(g.cur), t, normalBlend(n), n.props.opacity, false, r)
    }

    /** 애니메이션 폴더의 현재 프레임(+어니언 스킨)을 [g]에 (g = pairAt(depth + 1), 이미 비워 둔 것) */
    private fun animContent(n: Node, g: PingPong, r: IRect, depth: Int) {
        val kids = n.children
        if (kids.isEmpty()) return
        val f = animFrame.coerceIn(0, kids.size - 1)
        if (onionSkin && !animPlaying) {
            kids.getOrNull(f - 1)?.let { drawOnion(it, g, r, ONION_PREV) }
            kids.getOrNull(f + 1)?.let { drawOnion(it, g, r, ONION_NEXT) }
        }
        composeChildren(kids, f, f + 1, g, r, depth + 1)
    }

    private fun drawOnion(cel: Node, g: PingPong, r: IRect, color: Int) {
        if (!cel.isRaster || !cel.props.visible) return
        val src = maskedSrc(cel, r)
        val tinted = colorizeSrc(cel, src, r, color)
        drawSource(tinted, g, BlendMode.NORMAL, 0.35f, false, r)
    }

    // =====================================================================
    // UI 스레드 API — 텍스트
    // =====================================================================

    /** 활성 노드 위에 새 텍스트 레이어. 실행취소 한 단계. */
    fun createText(spec: TextSpec) = post {
        finishOp()
        val d = doc ?: return@post
        val tiles = spec.render(d.width, d.height)
        structural { doc ->
            val a = doc.active
            val parent = a?.parent ?: doc.root
            val idx = if (a != null) a.index + 1 else parent.children.size
            val n = Node(doc.newId(), NodeKind.RASTER, LayerProps(textName(spec), text = spec))
            insert(parent, idx, n)
            n.id
        }
        // structural이 새 surface를 만들었으면 픽셀을 채움 (실행취소하면 StructureCommand가 보관·복원)
        val id = d.activeId
        val s = surfaces[id] ?: return@post
        if (d.find(id)?.props?.text != spec) return@post
        tiles.forEach { (k, b) -> s.write(k, b) }
        thumbQueue.add(id)
        belowValid = false
        markAllDirty()
    }

    /** 텍스트 레이어 [id]의 내용을 [spec]으로 바꿉니다 (픽셀 + 속성을 한 번의 실행취소로). */
    fun updateText(id: Int, spec: TextSpec) = post {
        finishOp()
        val d = doc ?: return@post
        val n = d.find(id)?.takeIf { it.isRaster && it.props.text != null } ?: return@post
        val s = surfaces[id] ?: return@post
        val tiles = spec.render(d.width, d.height)
        val saved = HashMap<Int, ByteBuffer?>()
        for (k in s.tiles.keys.toList() + tiles.keys) if (k !in saved) saved[k] = s.read(k)
        for (k in saved.keys) s.write(k, tiles[k])
        val before = d.shape()
        val name = if (n.props.name == textName(n.props.text!!)) textName(spec) else n.props.name
        n.props = n.props.copy(text = spec, name = name)
        history.push(CompoundCommand(listOf(TilesCommand(id, saved), StructureCommand(before, d.shape(), d.activeId, d.activeId))))
        thumbQueue.add(id)
        afterEdit()
    }

    /** 텍스트 레이어를 일반 레이어로 (픽셀은 그대로, 더는 텍스트로 고칠 수 없음). */
    fun rasterizeText(id: Int) = post {
        val d = doc ?: return@post
        val n = d.find(id)?.takeIf { it.props.text != null || it.props.vector } ?: return@post
        finishOp()
        val before = d.shape()
        n.props = n.props.copy(text = null, vector = false)
        history.push(StructureCommand(before, d.shape(), d.activeId, d.activeId))
        afterEdit()
    }

    private fun textName(spec: TextSpec): String {
        val first = spec.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        return "T " + if (first.length > 12) first.take(12) + "…" else first
    }

    // =====================================================================
    // UI 스레드 API — 캔버스 편집 (크기·회전·반전)
    // =====================================================================

    /**
     * 모든 레이어·마스크에 [e]를 적용합니다. 픽셀 계산은 백그라운드, 반영은 GL 스레드.
     * 실행취소 한 단계 (문서 전체 보관). 선택 영역은 해제됩니다.
     */
    fun editCanvas(e: CanvasEdit) = post {
        finishOp()
        val d = doc ?: return@post
        val (nw, nh) = e.newSize(d.width, d.height)
        if (nw < 1 || nh < 1 || nw > maxTextureSize || nh > maxTextureSize) {
            reportError("캔버스는 1~${maxTextureSize}px이어야 합니다.")
            return@post
        }
        if (e is CanvasEdit.Resize && nw == d.width && nh == d.height) return@post
        if (e is CanvasEdit.Resample && nw == d.width && nh == d.height) return@post
        val before = try {
            captureData(d)
        } catch (t: OutOfMemoryError) {
            reportError("메모리가 부족해 캔버스를 바꾸지 못했습니다.")
            return@post
        }
        val v = version
        worker.execute {
            val after = try {
                CanvasEdit.apply(before, e)
            } catch (t: OutOfMemoryError) {
                main.post { listener.onRendererError("메모리가 부족해 캔버스를 바꾸지 못했습니다.") }
                null
            } ?: return@execute
            post { applyCanvasEdit(d, v, before, after) }
        }
    }

    private fun applyCanvasEdit(docRef: Document, v: Long, before: DocumentData, after: DocumentData) {
        if (doc !== docRef) return
        if (op != null) {
            // 진행 중인 획이 끝난 뒤에 (끝나며 그림이 바뀌면 아래 버전 검사에서 취소)
            main.postDelayed({ post { applyCanvasEdit(docRef, v, before, after) } }, 60)
            return
        }
        if (version != v) {
            reportError("계산하는 동안 그림이 바뀌어 캔버스 편집을 취소했습니다. 다시 해 주세요.")
            return
        }
        try {
            buildDocument(after, keepHistory = true, refit = true)
        } catch (t: Throwable) {
            Log.e(TAG, "canvas edit failed", t)
            reportError("메모리가 부족해 캔버스를 바꾸지 못했습니다. 기존 그림은 그대로 둡니다.")
            return
        }
        clearSelectionState()
        history.push(DocumentCommand(before))
        version++
        notifyHistory()
    }

    // =====================================================================
    // UI 스레드 API — 선택 영역
    // =====================================================================

    /** pts: 캔버스 좌표. RECT/ELLIPSE = [x0,y0,x1,y1], LASSO = 점 목록 */
    fun select(shape: SelShape, pts: FloatArray, selOp: SelOp) = post {
        val d = doc ?: return@post
        if (op is Op.Transform) return@post
        cancelPreviewOps()
        val before = selEncoded
        val m = selection ?: SelectionMask(d.width, d.height).also { selection = it }
        if (!hasSelection) m.clear()
        m.apply(SelectionMask.shapePath(shape, pts), if (!hasSelection && selOp == SelOp.SUBTRACT) SelOp.SUBTRACT else selOp)
        commitSelectionChange(before)
    }

    fun selectAll() = post {
        val d = doc ?: return@post
        if (op is Op.Transform) return@post
        cancelPreviewOps()
        val before = selEncoded
        val m = selection ?: SelectionMask(d.width, d.height).also { selection = it }
        m.selectAll()
        commitSelectionChange(before)
    }

    fun deselect() = post {
        if (!hasSelection || op is Op.Transform) return@post
        cancelPreviewOps()
        val before = selEncoded
        selection?.clear()
        commitSelectionChange(before)
    }

    /** 선택 영역 확장/축소/경계 흐리기. 계산은 백그라운드. */
    fun modifySelection(kind: SelModify, px: Int) = post {
        val d = doc ?: return@post
        if (op is Op.Transform) return@post
        if (!hasSelection) {
            reportError("선택 영역이 없습니다.")
            return@post
        }
        cancelPreviewOps()
        val m = selection ?: return@post
        val buf = m.toBuffer()
        val arr = ByteArray(buf.remaining()).also { buf.get(it) }
        val w = d.width
        val h = d.height
        worker.execute {
            val res = try {
                SelectionOps.apply(arr, w, h, kind, px)
            } catch (e: OutOfMemoryError) {
                main.post { listener.onRendererError("메모리가 부족해 선택 영역을 바꾸지 못했습니다.") }
                null
            } ?: return@execute
            post { applyWand(d, FloodFill.Result(res, IRect(0, 0, w, h)), SelOp.REPLACE) }
        }
    }

    /** 활성 레이어(마스크 편집 중이면 레이어 픽셀)의 불투명한 부분으로 선택 영역을 만듭니다. */
    fun selectFromLayer(selOp: SelOp) = post {
        val d = doc ?: return@post
        if (op is Op.Transform) return@post
        val n = editableActive(allowText = true, allowVector = true, allowLocked = true) ?: return@post
        cancelPreviewOps()
        val rgba = referenceImage(d, n, FillOptions.REF_CURRENT)
        val alpha = ByteArray(d.width * d.height)
        for (i in alpha.indices) alpha[i] = rgba.get(i * 4 + 3)
        val before = selEncoded
        val m = selection ?: SelectionMask(d.width, d.height).also { selection = it }
        if (!hasSelection) m.clear()
        m.applyMask(alpha, selOp)
        commitSelectionChange(before)
    }

    /**
     * 퀵 마스크 켜기/끄기. 켜면 선택 영역을 맨 위 반투명 "퀵 마스크" 레이어(빨강)로 옮기고 선택을 풉니다.
     * 그 레이어에 붓·지우개로 칠한 뒤 끄면 레이어의 알파가 선택 영역이 되고 레이어는 사라집니다. 각각 실행취소 한 단계.
     */
    fun toggleQuickMask() = post {
        finishOp()
        val d = doc ?: return@post
        if (op != null) return@post
        val qm = d.allNodes().firstOrNull { it.isRaster && it.props.quickMask }
        if (qm == null) enterQuickMask(d) else exitQuickMask(d, qm)
    }

    /** 퀵 마스크를 끄면 돌아갈 레이어 (켜기 전 활성 레이어) */
    private var quickMaskReturnId = 0

    private fun enterQuickMask(d: Document) {
        val before = d.shape()
        val activeBefore = d.activeId
        quickMaskReturnId = activeBefore
        val n = Node(d.newId(), NodeKind.RASTER, LayerProps("퀵 마스크", opacity = 0.5f, quickMask = true))
        insert(d.root, d.root.children.size, n)
        val struct = StructureCommand(before, d.shape(), activeBefore, n.id)
        struct.redo(this) // surface를 만들고 활성으로
        maskEditing = false
        val parts = ArrayList<HistoryCommand>()
        parts.add(struct)
        val r = selBounds
        val s = surfaces[n.id]
        if (hasSelection && r != null && s != null) {
            // 새 레이어라 TilesCommand는 필요 없음 (실행취소하면 StructureCommand가 픽셀을 보관)
            for (key in s.keysIntersecting(r)) {
                val tr = r.intersect(s.tileRect(key)) ?: continue
                val t = s.getOrCreate(key)
                s.bindCanvasSpace(key, t)
                s.scissorCanvasRect(key, tr)
                GlState.over()
                compositor.drawMergeCoverage(selTex!!.tex, QUICK_MASK, 1f, 0, d.width, d.height)
                GlState.off()
                GlState.noScissor()
                s.dropIfEmpty(key)
            }
            val selBefore = selEncoded
            selection?.clear()
            uploadSelection()
            parts.add(SelectionCommand(selBefore, selEncoded))
        }
        history.push(CompoundCommand(parts))
        thumbQueue.add(n.id)
        afterEdit()
    }

    private fun exitQuickMask(d: Document, qm: Node) {
        val rgba = referenceImage(d, qm, FillOptions.REF_CURRENT)
        val alpha = ByteArray(d.width * d.height)
        for (i in alpha.indices) alpha[i] = rgba.get(i * 4 + 3)
        val selBefore = selEncoded
        val m = selection ?: SelectionMask(d.width, d.height).also { selection = it }
        m.clear()
        m.applyMask(alpha, SelOp.REPLACE)
        uploadSelection()
        val selCmd = SelectionCommand(selBefore, selEncoded)
        val before = d.shape()
        val activeBefore = d.activeId
        val parent = qm.parent ?: d.root
        parent.children.remove(qm)
        val next = d.find(quickMaskReturnId)?.id ?: d.allNodes().lastOrNull { it.isRaster }?.id ?: 0
        val struct = StructureCommand(before, d.shape(), activeBefore, next)
        struct.redo(this)
        history.push(CompoundCommand(listOf(selCmd, struct)))
        afterEdit()
        requestRender()
    }

    fun invertSelection() = post {
        val d = doc ?: return@post
        if (op is Op.Transform) return@post
        cancelPreviewOps()
        val before = selEncoded
        val m = selection ?: SelectionMask(d.width, d.height).also { selection = it }
        if (!hasSelection) m.clear()
        m.invert()
        commitSelectionChange(before)
    }

    // =====================================================================
    // UI 스레드 API — 자유 변형 / 이동
    // =====================================================================

    fun beginTransform() = post { beginTransformGl() }

    /** m = [a,b,c,d,tx,ty] : 떠 있는 픽셀의 로컬 px → 캔버스 px. 9개면 원근 행렬 ([Homography]). */
    fun setTransform(m: FloatArray) = post {
        val o = op as? Op.Transform ?: return@post
        val d = doc ?: return@post
        if (m.size >= 9) {
            o.h = m
        } else {
            o.m = m
            o.h = null
        }
        val r = transformedRect(o, d) ?: o.bounds
        markDirty(r.union(o.lastRect))
        o.lastRect = r
    }

    fun commitTransform() = post { if (op is Op.Transform) finishOp() }

    fun cancelTransform() = post {
        val o = op as? Op.Transform ?: return@post
        endTransform(o)
    }

    // =====================================================================
    // UI 스레드 API — 레이어
    // =====================================================================

    /** [mask] = true면 그 레이어의 마스크를 편집 대상으로 (마스크가 있을 때만). */
    fun selectNode(id: Int, mask: Boolean = false) = post {
        val d = doc ?: return@post
        val n = d.find(id) ?: return@post
        val wantMask = mask && n.isRaster && n.props.mask
        if (id != d.activeId || wantMask != maskEditing) {
            finishOp()
            if (id != d.activeId) {
                d.activeId = id
                // 애니메이션 셀을 고르면 그 프레임을 보여 줌 (안 보이는 셀에 그리지 않게)
                if (n.parent?.props?.animation == true) animFrame = n.index
                belowValid = false
                markAllDirty()
            }
            maskEditing = wantMask
            notifyLayers()
        }
    }

    // ---- 레이어 마스크 ----

    /** 활성 레이어에 빈 마스크(전부 보임)를 만들고 마스크 편집으로 들어갑니다. */
    fun addMask() = post {
        finishOp()
        val d = doc ?: return@post
        val n = editableActive(allowVector = true) ?: return@post
        if (n.props.mask) {
            maskEditing = true
            notifyLayers()
            return@post
        }
        val before = d.shape()
        n.props = n.props.copy(mask = true, maskEnabled = true)
        val cmd = StructureCommand(before, d.shape(), n.id, n.id)
        cmd.redo(this) // applyShape가 surfaces[-id]를 만듭니다
        history.push(cmd)
        maskEditing = true
        afterEdit()
    }

    /** 마스크 삭제 (실행취소하면 마스크 픽셀도 돌아옵니다). */
    fun deleteMask() = post {
        finishOp()
        val d = doc ?: return@post
        val n = d.activeRaster?.takeIf { it.props.mask } ?: return@post
        val before = d.shape()
        n.props = n.props.copy(mask = false)
        val cmd = StructureCommand(before, d.shape(), n.id, n.id)
        cmd.redo(this) // 마스크 픽셀은 cmd 안에 보관
        history.push(cmd)
        maskEditing = false
        afterEdit()
    }

    /** 마스크를 레이어 픽셀에 합치고(가린 부분을 실제로 지움) 마스크를 없앱니다. */
    fun applyMask() = post {
        finishOp()
        val d = doc ?: return@post
        val n = d.activeRaster?.takeIf { it.props.mask } ?: return@post
        val s = surfaces[n.id] ?: return@post
        val m = surfaces[-n.id]
        val saved = HashMap<Int, ByteBuffer?>()
        if (m != null && n.props.maskEnabled) {
            for ((key, mt) in m.tiles) {
                val lt = s.tiles[key] ?: continue
                saved[key] = s.read(key)
                s.bindLocal(lt)
                GlState.erase()
                compositor.drawCopy(mt.tex, 1f)
                GlState.off()
                s.dropIfEmpty(key)
            }
        }
        val before = d.shape()
        n.props = n.props.copy(mask = false)
        val struct = StructureCommand(before, d.shape(), n.id, n.id)
        struct.redo(this)
        history.push(if (saved.isEmpty()) struct else CompoundCommand(listOf(TilesCommand(n.id, saved), struct)))
        thumbQueue.add(n.id)
        maskEditing = false
        afterEdit()
    }

    /** 마스크 편집 중이면 마스크 surface id(-id), 아니면 레이어 id. */
    private fun editId(n: Node): Int = if (maskEditing && n.props.mask) -n.id else n.id

    private fun isMaskEdit(n: Node): Boolean = maskEditing && n.props.mask

    /** delta = +1 위 레이어, -1 아래 레이어 (화면 목록 기준) */
    fun selectAdjacent(delta: Int) = post {
        val d = doc ?: return@post
        val list = d.infos()
        val i = list.indexOfFirst { it.id == d.activeId }
        val j = i - delta
        if (i >= 0 && j in list.indices) {
            finishOp()
            d.activeId = list[j].id
            maskEditing = false
            belowValid = false
            markAllDirty()
            notifyLayers()
        }
    }

    fun addLayer(vector: Boolean = false) = post {
        finishOp()
        structural { d ->
            val a = d.active
            val parent = a?.parent ?: d.root
            val idx = if (a != null) a.index + 1 else parent.children.size
            val name = if (vector) "벡터 " + d.nextLayerName() else d.nextLayerName()
            val n = Node(d.newId(), NodeKind.RASTER, LayerProps(name, vector = vector))
            insert(parent, idx, n)
            n.id
        }
    }

    fun addFolder() = post {
        finishOp()
        structural { d ->
            val a = d.active
            val parent = a?.parent ?: d.root
            val idx = if (a != null) a.index + 1 else parent.children.size
            val n = Node(d.newId(), NodeKind.FOLDER, LayerProps(d.nextFolderName(), blend = BlendMode.PASS_THROUGH))
            insert(parent, idx, n)
            n.id
        }
    }

    /** 활성 노드를 새 폴더로 감쌉니다 (Ctrl+G). */
    fun groupActive() = post {
        finishOp()
        structural { d ->
            val a = d.active ?: return@structural null
            val parent = a.parent ?: return@structural null
            val idx = a.index
            val f = Node(d.newId(), NodeKind.FOLDER, LayerProps(d.nextFolderName(), blend = BlendMode.PASS_THROUGH))
            parent.children.removeAt(idx)
            insert(parent, idx, f)
            insert(f, 0, a)
            a.props = a.props.copy(clip = false)
            a.id
        }
    }

    fun duplicate() = post {
        finishOp()
        structural { d ->
            val a = d.active ?: return@structural null
            val parent = a.parent ?: return@structural null
            val copy = cloneSubtree(d, a, a.props.name + " 복사")
            insert(parent, a.index + 1, copy)
            copy.id
        }
    }

    fun deleteNode() = post {
        finishOp()
        val d = doc ?: return@post
        val a = d.active ?: return@post
        val rastersLeft = d.allNodes().count { it.isRaster && it !== a && !a.isAncestorOf(it) }
        if (rastersLeft == 0) {
            main.post { listener.onRendererError("레이어가 하나는 있어야 합니다.") }
            return@post
        }
        structural { doc ->
            val node = doc.active ?: return@structural null
            val parent = node.parent ?: return@structural null
            val idx = node.index
            parent.children.removeAt(idx)
            val next = parent.children.getOrNull(idx - 1) ?: parent.children.getOrNull(idx)
                ?: parent.takeIf { it.id != ROOT_ID }
            next?.id ?: doc.allNodes().firstOrNull { it.isRaster }?.id
        }
    }

    /** +1 = 위로, -1 = 아래로. 폴더 경계에서는 폴더 밖/안으로 드나듭니다. */
    fun moveNode(delta: Int) = moveNodeSteps(delta)

    /** 화면 목록에서 [steps]칸 (양수 = 위로) 옮깁니다. 여러 칸이어도 실행취소 한 단계. */
    fun moveNodeSteps(steps: Int) = post {
        finishOp()
        if (steps == 0) return@post
        structural { d ->
            val a = d.active ?: return@structural null
            var moved = false
            repeat(kotlin.math.abs(steps)) { if (stepMove(a, if (steps > 0) 1 else -1)) moved = true }
            if (moved) a.id else null
        }
    }

    /** 한 칸 옮기기. 더 갈 곳이 없으면 false */
    private fun stepMove(a: Node, delta: Int): Boolean {
        val parent = a.parent ?: return false
        val idx = a.index
        if (delta > 0) {
            when {
                idx == parent.children.lastIndex && parent.id != ROOT_ID -> {
                    val gp = parent.parent!!
                    parent.children.removeAt(idx)
                    insert(gp, parent.index + 1, a)
                }
                idx < parent.children.lastIndex -> {
                    val above = parent.children[idx + 1]
                    parent.children.removeAt(idx)
                    if (above.isFolder && above.props.expanded && !a.isAncestorOf(above)) insert(above, 0, a)
                    else insert(parent, idx + 1, a)
                }
                else -> return false
            }
        } else {
            when {
                idx == 0 && parent.id != ROOT_ID -> {
                    val gp = parent.parent!!
                    parent.children.removeAt(idx)
                    insert(gp, parent.index, a)
                }
                idx > 0 -> {
                    val below = parent.children[idx - 1]
                    parent.children.removeAt(idx)
                    if (below.isFolder && below.props.expanded) insert(below, below.children.size, a)
                    else insert(parent, idx - 1, a)
                }
                else -> return false
            }
        }
        return true
    }

    fun mergeDown() = post {
        finishOp()
        val d = doc ?: return@post
        val a = d.active
        val parent = a?.parent
        val below = if (a != null && parent != null) parent.children.getOrNull(a.index - 1) else null
        if (a == null || !a.isRaster || below == null || !below.isRaster) {
            main.post { listener.onRendererError("바로 아래가 일반 레이어일 때만 병합할 수 있습니다.") }
            return@post
        }
        if (below.props.clip) {
            main.post { listener.onRendererError("아래 레이어가 클리핑 레이어이면 병합할 수 없습니다.") }
            return@post
        }
        if (!a.props.visible) {
            main.post { listener.onRendererError("숨긴 레이어는 병합할 수 없습니다.") }
            return@post
        }
        if (a.props.mask && a.props.maskEnabled) {
            main.post { listener.onRendererError("마스크가 있는 레이어는 먼저 마스크를 적용하거나 삭제한 뒤 병합하세요.") }
            return@post
        }
        val up = surfaces[a.id]!!
        val low = surfaces[below.id]!!
        val preserve = a.props.clip
        val saved = HashMap<Int, ByteBuffer?>()
        val mode = a.props.blend.shaderId
        val tmp = tileTmp!!
        for (key in up.tiles.keys.toList()) {
            val ta = up.tiles[key] ?: continue
            if (preserve && low.tiles[key] == null) continue
            saved[key] = low.read(key)
            val tb = low.getOrCreate(key)
            if (mode == 0) {
                low.bindLocal(tb)
                if (preserve) GlState.atop() else GlState.over()
                compositor.drawCopy(ta.tex, a.props.opacity)
                GlState.off()
            } else {
                tmp.bind()
                GlState.off()
                compositor.drawBlend(ta.tex, tb.tex, a.props.opacity, mode, preserve)
                low.bindLocal(tb)
                compositor.drawCopy(tmp.tex, 1f)
            }
        }
        val pixels = TilesCommand(below.id, saved)
        val before = d.shape()
        val activeBefore = d.activeId
        if (below.props.text != null || below.props.vector) below.props = below.props.copy(text = null, vector = false)
        parent!!.children.removeAt(a.index)
        val after = d.shape()
        val struct = StructureCommand(before, after, activeBefore, below.id)
        struct.redo(this)
        history.push(CompoundCommand(listOf(pixels, struct)))
        thumbQueue.add(below.id)
        afterEdit()
    }

    /** 선택 영역이 있으면 그 안만, 없으면 레이어 전체를 지웁니다. */
    fun clearLayer() = post {
        finishOp()
        val d = doc ?: return@post
        val n = editableActive(allowVector = true) ?: return@post
        clearLayerGl(d, n)
    }

    private fun clearLayerGl(d: Document, n: Node) {
        val onVector = n.props.vector && !isMaskEdit(n)
        if (onVector && hasSelection) {
            reportError("벡터 레이어는 선택 영역 안만 지울 수 없습니다. 지우개로 선을 지우거나 선택을 해제하세요.")
            return
        }
        if (hasSelection || isMaskEdit(n)) {
            // 마스크에서 "지우기" = 가리기 (선택이 없으면 전체)
            val (tex, rect) = selectionCoverage(d) ?: return
            commitCoverage(tex, floatArrayOf(1f, 1f, 1f, 1f), 1f, rect, eraser = true, useSel = false)
        } else {
            val s = surfaces[n.id]!!
            if (s.tileCount == 0) return
            val saved = HashMap<Int, ByteBuffer?>()
            for (k in s.tiles.keys.toList()) saved[k] = s.read(k)
            s.clear()
            val old = vectors[n.id]
            if (onVector && !old.isNullOrEmpty()) {
                vectors[n.id] = emptyList()
                history.push(CompoundCommand(listOf(TilesCommand(n.id, saved), VectorCommand(n.id, old, emptyList()))))
            } else history.push(TilesCommand(n.id, saved))
            thumbQueue.add(n.id)
            afterEdit()
        }
    }

    /**
     * 속성 변경. 슬라이더 드래그 중에는 record=false로 미리보기만 하고,
     * 손을 뗄 때 record=true + 시작 값(before)으로 실행취소 한 단계를 남깁니다.
     */
    fun setProps(id: Int, props: LayerProps, record: Boolean, before: LayerProps? = null) = post {
        val d = doc ?: return@post
        val n = d.find(id) ?: return@post
        val old = before ?: n.props
        if (record && old != props) {
            n.props = old
            val shapeBefore = d.shape()
            n.props = props
            history.push(StructureCommand(shapeBefore, d.shape(), d.activeId, d.activeId))
            notifyHistory()
        } else {
            n.props = props
        }
        val onlyUi = old.copy(expanded = props.expanded) == props
        if (!onlyUi) {
            version++
            // 아래 캐시보다 위(활성 레이어 쪽)에 있는 노드면 캐시는 그대로 씁니다.
            // 불투명도 슬라이더처럼 활성 레이어를 드래그로 바꿀 때 50장 전체를 다시 합성하지 않게.
            // (클리핑 변경으로 시작 위치가 바뀌면 belowStartId 비교에서 알아서 다시 만듭니다.)
            if (rootIndexOf(d, n) < startIndex(d)) belowValid = false
            markAllDirty()
        }
        notifyLayers()
    }

    // =====================================================================
    // UI 스레드 API — 기타
    // =====================================================================

    /** 합성 결과에서 색을 집습니다. 투명한 곳이면 콜백을 부르지 않습니다. */
    fun pickColor(x: Int, y: Int, callback: (Int) -> Unit) = post {
        pendingPicks.add(intArrayOf(x, y) to callback)
    }

    /** [background]가 있으면 맨 아래에 그 색으로 채운 "배경" 레이어를 만들고, 그 위의 "레이어 1"을 선택합니다. */
    fun newDocument(width: Int, height: Int, background: Int? = null, onLoaded: ((Long) -> Unit)? = null) = post {
        replaceDocument(blankData(width, height, background), onLoaded)
    }

    fun loadDocument(data: DocumentData, onLoaded: ((Long) -> Unit)? = null) = post {
        replaceDocument(data, onLoaded)
    }

    /** 저장용 스냅샷. 콜백은 GL 스레드에서 불리므로 무거운 작업은 다른 스레드로 넘기세요. */
    fun captureDocument(callback: (DocumentData, ByteBuffer, Long) -> Unit) = post {
        finishOp()
        val d = doc ?: return@post
        try {
            val data = captureData(d)
            ensureComposite(d)
            callback(data, compResult!!.readAll(), version)
        } catch (e: OutOfMemoryError) {
            main.post { listener.onRendererError("메모리가 부족해 저장할 수 없습니다.") }
        }
    }

    /** PNG 내보내기용 합성 결과. */
    fun captureFlattened(callback: (Int, Int, ByteBuffer) -> Unit) = post {
        finishOp()
        val d = doc ?: return@post
        try {
            callback(d.width, d.height, cleanComposite(d))
        } catch (e: OutOfMemoryError) {
            main.post { listener.onRendererError("메모리가 부족해 내보낼 수 없습니다.") }
        }
    }

    /**
     * GLSurfaceView.queueEvent 안에서 호출(= GL 스레드). 일시정지 직전에 부릅니다.
     * 반환값: (스냅샷, 합성 결과, 버전). 문서가 없으면 null.
     */
    fun capturePauseSnapshot(): Triple<DocumentData, ByteBuffer, Long>? {
        if (!glReady) return null
        drainCommands()
        finishOp()
        val d = doc ?: return null
        return try {
            val data = captureData(d)
            ensureComposite(d)
            lostSnapshot = data
            snapshotGeneration = resumeGeneration
            Triple(data, compResult!!.readAll(), version)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "pause snapshot failed", e)
            null
        }
    }

    // =====================================================================
    // GLSurfaceView.Renderer
    // =====================================================================

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val contextLost = glReady
        GlState.resetFbo()
        brushEngine = BrushEngine()
        compositor = Compositor()
        val arr = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, arr, 0)
        val dims = IntArray(2)
        GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, dims, 0)
        maxTextureSize = minOf(arr[0], dims[0].takeIf { it > 0 } ?: arr[0], dims[1].takeIf { it > 0 } ?: arr[0])
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_DITHER)
        glReady = true

        // 컨텍스트를 잃었다면 이전 GL 객체는 이미 무효. delete 없이 버리고 다시 만듭니다.
        val old = doc
        if (contextLost) forgetGlObjects()
        val snapshot = lostSnapshot
        when {
            contextLost && snapshot != null -> try {
                buildDocument(snapshot, keepHistory = true, refit = false)
                selEncoded?.let { setSelectionMask(it) }
            } catch (t: Throwable) {
                Log.e(TAG, "context restore failed", t)
                buildBlank(snapshot.width, snapshot.height)
                history.clear()
                clearSelectionState()
                main.post { listener.onRendererError("메모리가 부족해 그림을 복원하지 못했습니다. 자동 저장본을 확인하세요.") }
            }
            contextLost && old != null -> {
                buildBlank(old.width, old.height)
                history.clear()
                clearSelectionState()
                main.post { listener.onRendererError("그래픽 컨텍스트를 잃어 그림을 복원하지 못했습니다.") }
            }
            else -> buildBlank(defaultWidth.coerceAtMost(maxTextureSize), defaultHeight.coerceAtMost(maxTextureSize), defaultBackground)
        }
        lostSnapshot = null
        val max = maxTextureSize
        main.post { listener.onGlReady(max) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenW = width
        screenH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        perf.frameStart()
        drainCommands()
        val d = doc ?: return
        if (lostSnapshot != null && resumeGeneration > snapshotGeneration) lostSnapshot = null
        val benchStart = if (benchPhase != 0) benchPrepare(d) else 0L

        if (fullDirty || dirtyRegion != null) {
            try {
                ensureComposite(d)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "composite OOM", e)
                fullDirty = false
                dirtyRegion = null
                reportError("GPU 메모리가 부족합니다. 폴더 중첩이나 캔버스 크기를 줄여 주세요.")
            }
        }
        processPicks()
        drawScreen(d)
        processThumbnail()
        processNavigator(d)
        if (benchPhase != 0) benchFinish(d, benchStart)
        perf.frameEnd()?.let { s ->
            val mem = memoryLine()
            main.post { listener.onPerfStats(s, mem) }
        }
    }

    /** 성능 패널용 메모리 요약: GPU(레이어 타일 + 캔버스 크기 작업 버퍼) · 실행취소 기록 (MB). */
    private fun memoryLine(): String {
        val mb = 1024.0 * 1024.0
        val tiles = pool.allocated.toLong() * TILE_BYTES
        var buffers = 0L
        fun add(t: RenderTarget?) {
            if (t != null) buffers += t.width.toLong() * t.height * t.bytesPerPixel
        }
        add(strokeBuf); add(selTex); add(preview); add(belowCache); add(maskTmp); add(tileTmp); add(thumbTarget)
        add(smudgeBuf); add(smudgePatch)
        borderBufs?.forEach { add(it) }
        add(toneBuf); add(colorBuf)
        pairs.forEach { add(it.a); add(it.b) }
        (op as? Op.Filter)?.let { add(it.orig); add(it.work); add(it.result) }
        return String.format(
            "GPU %.0fMB (레이어 타일 %.0fMB · 작업 버퍼 %.0fMB, 합성 깊이 %d) · 실행취소 %.0fMB",
            (tiles + buffers) / mb, tiles / mb, buffers / mb, pairs.size, history.totalBytes / mb
        )
    }

    // =====================================================================
    // 합성 벤치마크 (로드맵 관문 2)
    // =====================================================================

    private var benchPhase = 0
    /** 벤치마크 5단계: framebuffer fetch를 끄고 전체 다시 합성 (같은 조건에서 비교용) */
    private var benchNoFetch = false
    private var benchLeft = 0
    private val benchTimes = PerfMonitor.Window(120)
    private val benchReport = StringBuilder()

    /**
     * 세 단계를 차례로 잽니다. 매 프레임 glFinish로 GPU 완료까지 기다리므로 실제 그리기보다 약간 느리게 나옵니다.
     * 1) 전체 다시 합성: 모든 레이어를 처음부터 (레이어 표시/순서를 바꿀 때)
     * 2) 활성 레이어 속성 변경: 아래 캐시는 두고 활성 레이어부터 전체 영역 (불투명도 슬라이더 드래그)
     * 3) 브러시 영역 512px: 활성 레이어 위쪽만 부분 합성 (그리는 중 매 프레임)
     * 4) 화면 표시만: 확대·회전 중
     */
    fun runBenchmark() = post {
        finishOp()
        val d = doc ?: return@post
        benchReport.clear()
        benchReport.append("캔버스 ${d.width}×${d.height} · 레이어 ${d.allNodes().count { it.isRaster }}장 · 타일 ${surfaces.values.sumOf { it.tileCount }}개\n")
        startBenchPhase(1)
    }

    private fun startBenchPhase(phase: Int) {
        if (phase == 1) segMs.fill(0.0)
        benchPhase = phase
        benchLeft = if (phase == 1 || phase == 5) 20 else 60
        benchNoFetch = phase == 5
        benchTimes.clear()
        requestRender()
    }

    private fun benchPrepare(d: Document): Long {
        GLES20.glFinish()
        when (benchPhase) {
            1, 5 -> { belowValid = false; markAllDirty() }
            2 -> markAllDirty()
            3 -> {
                val s = 512
                markDirty(IRect(max(0, d.width / 2 - s / 2), max(0, d.height / 2 - s / 2), min(s, d.width), min(s, d.height)))
            }
        }
        return System.nanoTime()
    }

    private fun benchFinish(d: Document, start: Long) {
        GLES20.glFinish()
        benchTimes.add((System.nanoTime() - start) / 1e6f)
        if (--benchLeft > 0) {
            requestRender()
            return
        }
        val label = when (benchPhase) {
            1 -> if (compositor.hasFetch) "전체 다시 합성 (fetch)" else "전체 다시 합성"
            5 -> "전체 다시 합성 (fetch 끔)"
            2 -> "활성 레이어 속성 변경"
            3 -> "브러시 영역(512px) 합성"
            else -> "화면 표시만"
        }
        val avg = benchTimes.mean()
        benchReport.append(String.format("%s: 평균 %.1fms · 95%% %.1fms · 최소 %.1fms → 약 %.0f fps\n",
            label, avg, benchTimes.percentile(0.95f), benchTimes.min(), if (avg > 0f) 1000f / avg else 0f))
        if (benchPhase == 1) benchReport.append(String.format("  └ 아래 합성 %.1f · 캐시 저장 %.1f · 캐시 복사 %.1f · 위 합성 %.1f (ms, 평균)\n", segMs[0] / 20, segMs[1] / 20, segMs[2] / 20, segMs[3] / 20))
        if (benchPhase == 4 && !compositor.hasFetch) benchPhase = 5
        if (benchPhase < 5) startBenchPhase(benchPhase + 1)
        else {
            benchPhase = 0
            benchNoFetch = false
            val report = benchReport.toString().trimEnd()
            main.post { listener.onBenchmarkDone(report) }
        }
    }

    /**
     * 부하 테스트 문서: [width]×[height]에 레이어 [layers]장. 레이어마다 타일 2줄 높이의 반투명 띠를
     * 서로 다른 위치·색으로 채우고, 몇 장은 곱하기/스크린 합성으로 둡니다.
     */
    fun loadStressDocument(width: Int, height: Int, layers: Int, onLoaded: ((Long) -> Unit)? = null) = post {
        val w = min(width, maxTextureSize)
        val h = min(height, maxTextureSize)
        val cols = TileMath.cols(w)
        val rows = TileMath.rows(h)
        val nodes = ArrayList<NodeData>(layers)
        for (i in 0 until layers) {
            val hsv = floatArrayOf((i * 37f) % 360f, 0.6f, 0.9f)
            val c = Color.HSVToColor(hsv)
            val a = 0.5f
            val tile = GlUtil.byteBuffer(TILE * TILE * 4)
            val r = (Color.red(c) * a).toInt().toByte()
            val g = (Color.green(c) * a).toInt().toByte()
            val b = (Color.blue(c) * a).toInt().toByte()
            val ab = (255 * a).toInt().toByte()
            for (k in 0 until TILE * TILE) tile.put(r).put(g).put(b).put(ab)
            tile.rewind()
            val tiles = HashMap<Int, ByteBuffer>()
            val row0 = (i * 3) % max(1, rows - 1)
            for (ty in row0 until min(rows, row0 + 2)) for (tx in 0 until cols) tiles[ty * cols + tx] = tile
            val blend = when (i % 10) {
                3 -> BlendMode.MULTIPLY
                7 -> BlendMode.SCREEN
                else -> BlendMode.NORMAL
            }
            nodes.add(NodeData(i + 1, NodeKind.RASTER, LayerProps("부하 ${i + 1}", blend = blend), ROOT_ID, tiles))
        }
        replaceDocument(DocumentData(w, h, layers, nodes), onLoaded)
    }

    private fun drainCommands() {
        while (true) {
            val c = commands.poll() ?: break
            try {
                c()
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "command OOM", e)
                main.post { listener.onRendererError("메모리가 부족합니다. 캔버스 크기나 레이어 수를 줄여 주세요.") }
            } catch (e: RuntimeException) {
                Log.e(TAG, "command failed", e)
                main.post { listener.onRendererError(e.message ?: "렌더링 오류") }
            }
        }
    }

    // =====================================================================
    // 문서 생성
    // =====================================================================

    private fun replaceDocument(data: DocumentData, onLoaded: ((Long) -> Unit)?) {
        if (data.width > maxTextureSize || data.height > maxTextureSize) {
            main.post { listener.onRendererError("이 기기는 ${maxTextureSize}px보다 큰 캔버스를 지원하지 않습니다.") }
            return
        }
        finishOp()
        try {
            buildDocument(data, keepHistory = false, refit = true)
        } catch (t: Throwable) {
            Log.e(TAG, "document build failed", t)
            if (doc == null) buildBlank(min(defaultWidth, maxTextureSize), min(defaultHeight, maxTextureSize))
            main.post { listener.onRendererError("메모리가 부족해 문서를 열지 못했습니다. 기존 그림은 그대로 둡니다.") }
            return
        }
        clearSelectionState()
        version++
        val v = version
        if (onLoaded != null) main.post { onLoaded(v) }
    }

    private fun buildBlank(w: Int, h: Int, background: Int? = null) {
        buildDocument(blankData(w, h, background), keepHistory = false, refit = true)
    }

    private fun blankData(w: Int, h: Int, background: Int?): DocumentData {
        if (background == null) {
            return DocumentData(w, h, 1, listOf(NodeData(1, NodeKind.RASTER, LayerProps("레이어 1"), ROOT_ID, emptyMap())))
        }
        // 불투명 색 한 장을 모든 타일이 함께 씁니다 (write가 duplicate 후 업로드하므로 공유해도 안전).
        val tile = GlUtil.byteBuffer(TILE * TILE * 4)
        val r = Color.red(background).toByte()
        val g = Color.green(background).toByte()
        val b = Color.blue(background).toByte()
        for (i in 0 until TILE * TILE) {
            tile.put(r).put(g).put(b).put(0xFF.toByte())
        }
        tile.rewind()
        val tiles = HashMap<Int, ByteBuffer>()
        val cols = TileMath.cols(w)
        for (ty in 0 until TileMath.rows(h)) for (tx in 0 until cols) tiles[ty * cols + tx] = tile
        return DocumentData(
            w, h, 2,
            listOf(
                NodeData(1, NodeKind.RASTER, LayerProps("배경"), ROOT_ID, tiles),
                NodeData(2, NodeKind.RASTER, LayerProps("레이어 1"), ROOT_ID, emptyMap()),
            )
        )
    }

    /** 새 GL 객체를 모두 만든 뒤에만 기존 문서를 버립니다. 실패하면 기존 문서는 그대로 남습니다. */
    private fun buildDocument(data: DocumentData, keepHistory: Boolean, refit: Boolean) {
        val w = data.width
        val h = data.height
        val createdTargets = ArrayList<RenderTarget>()
        val newSurfaces = HashMap<Int, TileSurface>()
        fun rt(mask: Boolean = false) = RenderTarget(w, h, mask).also { createdTargets.add(it) }
        val d = Document(w, h)
        val sb: RenderTarget; val sel: RenderTarget; val pv: RenderTarget; val below: RenderTarget
        val ca: RenderTarget; val cb: RenderTarget; val tt: RenderTarget; val th: RenderTarget
        try {
            for (n in data.nodes) {
                if (n.kind == NodeKind.RASTER) {
                    val s = TileSurface(w, h, pool)
                    newSurfaces[n.id] = s
                    n.tiles?.let { s.loadCpu(it) }
                    if (n.props.mask) {
                        val m = TileSurface(w, h, pool)
                        newSurfaces[-n.id] = m
                        n.maskTiles?.let { m.loadCpu(it) }
                    }
                }
            }
            sb = rt(true); sel = rt(true); pv = rt(); below = rt(); ca = rt(); cb = rt()
            tt = RenderTarget(TILE, TILE).also { createdTargets.add(it) }
            val (tw, thh) = thumbSize(w, h)
            th = RenderTarget(tw, thh).also { createdTargets.add(it) }
        } catch (t: Throwable) {
            createdTargets.forEach { it.release() }
            newSurfaces.values.forEach { it.clear() }
            pool.clearFree()
            throw t
        }
        releaseAll()
        surfaces.putAll(newSurfaces)
        // 실행취소 기록을 유지하면 지운 레이어(parked)의 선도 남겨 둠
        if (!keepHistory) vectors.clear()
        for (n in data.nodes) n.vector?.let { vectors[n.id] = it }
        d.rebuild(data.nodes.map { ShapeEntry(it.id, it.kind, it.props, it.parentId) })
        d.activeId = if (d.find(data.activeId) != null) data.activeId else d.allNodes().firstOrNull { it.isRaster }?.id ?: 0
        // 활성 레이어가 애니메이션 셀이면 그 프레임을 보여 줌
        animFrame = d.active?.takeIf { it.parent?.props?.animation == true }?.index ?: 0
        animPlaying = false
        strokeBuf = sb; selTex = sel; preview = pv; belowCache = below
        pairs.add(PingPong(ca, cb))
        tileTmp = tt; thumbTarget = th
        doc = d
        if (!keepHistory) history.clear()
        op = null
        belowValid = false
        markAllDirty()
        main.post { listener.onDocumentReplaced(w, h, refit) }
        notifyLayers()
        notifyHistory()
        thumbQueue.clear()
        thumbQueue.addAll(surfaces.keys)
    }

    private fun thumbSize(w: Int, h: Int): kotlin.Pair<Int, Int> {
        val s = THUMB.toFloat() / max(w, h)
        return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
    }

    private fun releaseAll() {
        surfaces.values.forEach { it.clear() }
        surfaces.clear()
        pool.clearFree()
        strokeBuf?.release(); selTex?.release(); preview?.release(); belowCache?.release()
        tileTmp?.release(); thumbTarget?.release()
        maskTmp?.release(); maskTmp = null
        smudgeBuf?.release(); smudgeBuf = null
        borderBufs?.forEach { it.release() }; borderBufs = null
        toneBuf?.release(); toneBuf = null
        navTarget?.release(); navTarget = null
        navSent = -1L
        colorBuf?.release(); colorBuf = null
        smudgePatch?.release(); smudgePatch = null
        maskEditing = false
        pairs.forEach { it.release() }
        pairs.clear()
        (op as? Op.Transform)?.floating?.release()
        (op as? Op.Filter)?.let {
            it.release()
            main.post { listener.onFilterEnded() }
        }
        op = null
        doc = null
        strokeBuf = null; selTex = null; preview = null; belowCache = null; compResult = null
        tileTmp = null; thumbTarget = null
    }

    /** 컨텍스트 손실 후: 모든 GL 핸들을 지우지 않고 잊습니다. */
    private fun forgetGlObjects() {
        surfaces.values.forEach { it.tiles.clear() }
        surfaces.clear()
        pool.forget()
        pairs.clear()
        tips.clear()
        strokeBuf = null; selTex = null; preview = null; belowCache = null; compResult = null
        tileTmp = null; thumbTarget = null
        maskTmp = null
        smudgeBuf = null
        smudgePatch = null
        borderBufs = null
        toneBuf = null
        navTarget = null
        navSent = -1L
        colorBuf = null
        if (op is Op.Filter) main.post { listener.onFilterEnded() }
        op = null
        doc = null
    }

    private fun captureData(d: Document): DocumentData = DocumentData(
        d.width, d.height, d.activeId,
        d.shape().map { e ->
            NodeData(
                e.id, e.kind, e.props, e.parentId, surfaces[e.id]?.toCpu(), if (e.props.mask) surfaces[-e.id]?.toCpu() else null,
                if (e.props.vector) vectors[e.id] ?: emptyList() else null,
            )
        }
    )

    private fun clearSelectionState() {
        selection?.bitmap?.recycle()
        selection = null
        selEncoded = null
        selBounds = null
        setHasSelection(false)
    }

    // =====================================================================
    // 진행 중 작업 (미리보기 → 확정)
    // =====================================================================

    /** [allowText] = false면 텍스트 레이어도 거부합니다 (붓질·채우기·필터 등 픽셀을 직접 고치는 작업). */
    /** 잠긴 폴더 안에 있는지 */
    private fun lockedByFolder(n: Node): Boolean {
        var p = n.parent
        while (p != null && p.id != ROOT_ID) {
            if (p.props.locked) return true
            p = p.parent
        }
        return false
    }

    /** [allowLocked] = 읽기만 하는 작업(복사·선택)이면 잠긴 레이어도 허용 */
    private fun editableActive(allowText: Boolean = false, allowVector: Boolean = false, allowLocked: Boolean = false): Node? {
        val d = doc ?: return null
        val n = d.active
        if (n == null || !n.isRaster) {
            reportError("폴더가 아닌 레이어를 선택하세요.")
            return null
        }
        if (!allowLocked && (n.props.locked || lockedByFolder(n))) {
            reportError("잠긴 레이어입니다. 레이어 ⋯ 메뉴에서 잠금을 푸세요.")
            return null
        }
        if (!allowText && n.props.text != null) {
            reportError("텍스트 레이어입니다. 텍스트 도구로 고치거나, 레이어 ⋯ 메뉴에서 래스터화한 뒤 그리세요.")
            return null
        }
        if (!allowVector && n.props.vector && !isMaskEdit(n)) {
            reportError("벡터 레이어입니다. 펜·지우개·도형으로 그리거나, 레이어 ⋯ 메뉴에서 래스터화하세요.")
            return null
        }
        return n
    }

    private var lastError: String? = null
    private var lastErrorTime = 0L

    /** 같은 메시지를 짧은 시간에 반복해서 띄우지 않습니다 (드래그 중 매 이벤트 등). */
    private fun reportError(msg: String) {
        val now = SystemClock.uptimeMillis()
        if (msg == lastError && now - lastErrorTime < 2000) return
        lastError = msg
        lastErrorTime = now
        main.post { listener.onRendererError(msg) }
    }

    /** 진행 중인 획/그라데이션/변형을 확정합니다. */
    private fun finishOp() {
        val d = doc ?: return
        when (val o = op) {
            is Op.Stroke -> {
                op = null
                val rect = o.rect ?: return
                // 벡터 레이어: 선 데이터도 남김 (픽셀과 한 번의 실행취소로)
                val n = d.active
                var extra: HistoryCommand? = null
                var revert: (() -> Unit)? = null
                if (n != null && n.props.vector && !isMaskEdit(n) && !o.brush.isEraser) {
                    val total = o.all.sumOf { it.size }
                    val st = FloatArray(total)
                    var off = 0
                    for (a in o.all) { System.arraycopy(a, 0, st, off, a.size); off += a.size }
                    val old = vectors[n.id] ?: emptyList()
                    val now = old + VStroke(st, o.brush, o.colorInt, o.fill)
                    vectors[n.id] = now
                    extra = VectorCommand(n.id, old, now)
                    revert = { vectors[n.id] = old }
                }
                if (!commitCoverage(strokeBuf!!.tex, o.color, o.brush.opacity, rect, o.brush.isEraser, useSel = hasSelection, extra = extra)) revert?.invoke()
            }
            is Op.VErase -> {
                op = null
                if (o.current !== o.before) {
                    history.push(CompoundCommand(listOf(TilesCommand(o.layerId, o.saved), VectorCommand(o.layerId, o.before, o.current))))
                    version++
                    thumbQueue.add(o.layerId)
                    notifyHistory()
                }
            }
            is Op.Gradient -> {
                op = null
                val rect = gradientRect(d)
                val p = o.p
                commitToActive(rect, eraser = false) {
                    compositor.drawMergeGradient(o.kind, o.stops, o.repeat, p[0], p[1], p[2], p[3], o.opacity, selTexIfAny(), d.width, d.height)
                }
            }
            is Op.Transform -> commitTransformGl(o, d)
            is Op.Filter -> commitFilterGl(o, d)
            is Op.Smudge -> {
                op = null
                commitSmudge(o, d)
            }
            null -> Unit
        }
    }

    /** 미리보기만 있는 작업(그라데이션/변형/필터)은 취소, 획은 확정. 실행취소 전에 부릅니다. */
    private fun cancelPreviewOps() {
        when (val o = op) {
            is Op.Gradient -> {
                op = null; markAllDirty()
            }
            is Op.Transform -> endTransform(o)
            is Op.Filter -> endFilter(o)
            is Op.Stroke, is Op.Smudge, is Op.VErase -> finishOp()
            null -> Unit
        }
    }

    private fun selTexIfAny(): Int = if (hasSelection) selTex!!.tex else 0

    private fun gradientRect(d: Document): IRect =
        (if (hasSelection) selBounds else null) ?: IRect(0, 0, d.width, d.height)

    /** 선택 영역 커버리지 텍스처와 경계. 선택이 없으면 strokeBuf를 1로 채워 전체. */
    private fun selectionCoverage(d: Document): kotlin.Pair<Int, IRect>? {
        return if (hasSelection) {
            val r = selBounds ?: return null
            selTex!!.tex to r
        } else {
            strokeBuf!!.fillOne()
            strokeBuf!!.tex to IRect(0, 0, d.width, d.height)
        }
    }

    /** 커버리지 텍스처 × 색을 활성 레이어에 확정. */
    /** 반환: 실제로 바뀐 픽셀이 있어 실행취소 단계를 남겼는지 */
    private fun commitCoverage(coverageTex: Int, color: FloatArray, opacity: Float, rect: IRect, eraser: Boolean, useSel: Boolean, extra: HistoryCommand? = null): Boolean {
        val d = doc ?: return false
        val sel = if (useSel) selTexIfAny() else 0
        return commitToActive(rect, eraser, extra) {
            compositor.drawMergeCoverage(coverageTex, color, opacity, sel, d.width, d.height)
        }
    }

    /**
     * 활성 레이어의 rect 범위 타일에 draw()를 실행하고 실행취소 단계를 남깁니다.
     * draw()는 캔버스 좌표계 셰이더를 쓰면 됩니다 (타일 오프셋 뷰포트가 설정됨).
     */
    private fun commitToActive(rect: IRect, eraser: Boolean, extra: HistoryCommand? = null, draw: () -> Unit): Boolean {
        val d = doc ?: return false
        val n = d.active?.takeIf { it.isRaster } ?: return false
        val target = editId(n)
        val s = surfaces[target] ?: return false
        // 마스크(알파 = 가림)에서는 그리기 = 드러내기(지움), 지우개 = 가리기(칠함). 투명 잠금은 무시.
        val onMask = target < 0
        val eraser = if (onMask) !eraser else eraser
        val lock = !onMask && n.props.alphaLock
        markDirty(rect)
        if (eraser && lock) return false
        val saved = HashMap<Int, ByteBuffer?>()
        for (key in s.keysIntersecting(rect)) {
            val existing = s.tiles[key]
            if (existing == null && (eraser || lock)) continue
            val r = rect.intersect(s.tileRect(key)) ?: continue
            saved[key] = s.read(key)
            val t = s.getOrCreate(key)
            s.bindCanvasSpace(key, t)
            s.scissorCanvasRect(key, r)
            when {
                eraser -> GlState.erase()
                lock -> GlState.atop()
                else -> GlState.over()
            }
            draw()
            GlState.off()
            GlState.noScissor()
            if (eraser || existing == null) s.dropIfEmpty(key)
        }
        // 실제로 바뀐 게 없는 타일(새로 만들었다 바로 비운 것)은 기록에서 뺍니다.
        saved.keys.toList().forEach { k -> if (saved[k] == null && s.tiles[k] == null) saved.remove(k) }
        if (saved.isEmpty()) return false
        history.push(if (extra != null) CompoundCommand(listOf(TilesCommand(target, saved), extra)) else TilesCommand(target, saved))
        version++
        thumbQueue.add(target)
        notifyHistory()
        return true
    }

    // ---- 자유 변형 ----

    private fun beginTransformGl() {
        val d = doc ?: return
        val n = editableActive(allowText = true, allowVector = true) ?: return
        if (op is Op.Transform) return
        finishOp()
        val s = surfaces[editId(n)]!!
        val bounds = if (hasSelection) {
            selBounds?.let { contentBounds(s, it) }
        } else {
            s.tileBounds()?.let { contentBounds(s, it) }
        }
        if (bounds == null) {
            main.post { listener.onRendererError("변형할 픽셀이 없습니다.") }
            return
        }
        val fl = RenderTarget(bounds.w, bounds.h)
        // 떠 있는 픽셀 = 레이어 × 선택 마스크. 뷰포트를 밀어 캔버스 좌표계로 그립니다.
        GlState.bindFbo(fl.fbo)
        GLES20.glViewport(-bounds.x, -bounds.y, d.width, d.height)
        GlState.off()
        for ((key, t) in s.tiles) {
            if (!s.tileRect(key).intersects(bounds)) continue
            compositor.drawTile(t.tex, s.originX(key), s.originY(key), d.width, d.height, 1f, selTexIfAny(), if (hasSelection) 1 else 0)
        }
        fl.setFilter(GLES20.GL_LINEAR_MIPMAP_LINEAR, GLES20.GL_LINEAR)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        val m = floatArrayOf(1f, 0f, 0f, 1f, bounds.x.toFloat(), bounds.y.toFloat())
        val tr = Op.Transform(fl, bounds, m, whole = !hasSelection)
        // 레이어를 옮기면 마스크도 같이 (같은 선택 범위 안의 마스크 픽셀)
        val ms = if (!isMaskEdit(n) && n.props.mask) surfaces[-n.id] else null
        if (ms != null && ms.tileCount > 0) {
            val mb = if (hasSelection) selBounds?.let { contentBounds(ms, it) } else ms.tileBounds()?.let { contentBounds(ms, it) }
            if (mb != null) {
                val mf = RenderTarget(mb.w, mb.h)
                GlState.bindFbo(mf.fbo)
                GLES20.glViewport(-mb.x, -mb.y, d.width, d.height)
                GlState.off()
                for ((key, t) in ms.tiles) {
                    if (!ms.tileRect(key).intersects(mb)) continue
                    compositor.drawTile(t.tex, ms.originX(key), ms.originY(key), d.width, d.height, 1f, selTexIfAny(), if (hasSelection) 1 else 0)
                }
                mf.setFilter(GLES20.GL_LINEAR, GLES20.GL_LINEAR)
                tr.maskFloating = mf
                tr.maskBounds = mb
                markDirty(mb)
            }
        }
        op = tr
        markDirty(bounds)
        val bw = bounds.w
        val bh = bounds.h
        main.post { listener.onTransformStarted(bw, bh, m.copyOf()) }
    }

    /** 대략 경계(타일/선택) 안에서 실제 픽셀이 있는 경계를 계산합니다. */
    private fun contentBounds(s: TileSurface, hint: IRect): IRect? {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
        for (key in s.keysIntersecting(hint)) {
            val buf = s.read(key) ?: continue
            val ox = s.originX(key)
            val oy = s.originY(key)
            for (y in 0 until TILE) {
                val cy = oy + y
                if (cy < hint.y || cy >= hint.bottom) continue
                for (x in 0 until TILE) {
                    val cx = ox + x
                    if (cx < hint.x || cx >= hint.right) continue
                    if (buf.get((y * TILE + x) * 4 + 3).toInt() != 0) {
                        if (cx < minX) minX = cx
                        if (cx > maxX) maxX = cx
                        if (cy < minY) minY = cy
                        if (cy > maxY) maxY = cy
                    }
                }
            }
        }
        if (maxX < 0) return null
        return IRect(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun transformedRect(o: Op.Transform, d: Document): IRect? = transformedRect(o.bounds, o.hm, d)

    /** hm = 원근 행렬 (행 우선 9개) */
    private fun transformedRect(bounds: IRect, hm: FloatArray, d: Document): IRect? {
        val w = bounds.w.toFloat()
        val h = bounds.h.toFloat()
        val p = FloatArray(2)
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for ((u, v) in listOf(0f to 0f, w to 0f, w to h, 0f to h)) {
            Homography.map(hm, u, v, p)
            l = min(l, p[0]); r = max(r, p[0]); t = min(t, p[1]); b = max(b, p[1])
        }
        return IRect.ofBounds(l - 2, t - 2, r + 2, b + 2, d.width, d.height)
    }

    private fun commitTransformGl(o: Op.Transform, d: Document) {
        val n = d.active?.takeIf { it.isRaster }
        val s = n?.let { surfaces[editId(it)] }
        if (n == null || s == null) {
            endTransform(o); return
        }
        val saved = placeFloating(s, o.floating, o.bounds, o.hm, o.whole, d)
        val parts = ArrayList<HistoryCommand>()
        if (saved.isNotEmpty()) parts.add(TilesCommand(editId(n), saved))
        // 함께 옮긴 마스크
        val mf = o.maskFloating
        val mb = o.maskBounds
        val ms = surfaces[-n.id]
        if (mf != null && mb != null && ms != null) {
            val ms2 = placeFloating(ms, mf, mb, o.maskH()!!, o.whole, d)
            if (ms2.isNotEmpty()) parts.add(TilesCommand(-n.id, ms2))
            thumbQueue.add(-n.id)
        }
        // 벡터 레이어: 선 데이터도 같은 행렬로 옮기고 선명하게 다시 그림 (선택 영역만 옮기면 일반 레이어로 굳힘)
        if (n.props.vector && !isMaskEdit(n)) {
            val old = vectors[n.id] ?: emptyList()
            if (hasSelection || o.h != null) {
                val before = d.shape()
                n.props = n.props.copy(vector = false)
                parts.add(StructureCommand(before, d.shape(), n.id, n.id))
                reportError(if (o.h != null) "원근 변형이라 벡터 레이어가 일반 레이어로 바뀌었습니다." else "선택 영역만 변형해 벡터 레이어가 일반 레이어로 바뀌었습니다.")
                notifyLayers()
            } else if (old.isNotEmpty()) {
                val m = o.m
                val bx = o.bounds.x.toFloat()
                val by = o.bounds.y.toFloat()
                val mm = floatArrayOf(m[0], m[1], m[2], m[3], m[4] - (m[0] * bx + m[2] * by), m[5] - (m[1] * bx + m[3] * by))
                val now = old.map { it.transformed(mm) }
                vectors[n.id] = now
                parts.add(VectorCommand(n.id, old, now))
                val area = (transformedRect(o, d) ?: o.bounds).union(o.bounds)
                // placeFloating이 보관한 원본(saved)은 그대로 두고, 새로 건드리는 타일만 더해 보관
                val hadTiles = saved.isNotEmpty()
                rasterStrokes(d, s, area, now, saved)
                if (!hadTiles && saved.isNotEmpty()) parts.add(0, TilesCommand(n.id, saved))
            }
        }
        // 텍스트 레이어: 평행이동만이면 위치를 옮기고, 회전·확대 등이면 픽셀로 굳힘
        n.props.text?.takeIf { !isMaskEdit(n) }?.let { t ->
            val before = d.shape()
            val m = o.m
            val pure = o.h == null && !hasSelection && !isMaskEdit(n) && m[0] == 1f && m[1] == 0f && m[2] == 0f && m[3] == 1f
            n.props = n.props.copy(text = if (pure) t.copy(x = t.x + m[4] - o.bounds.x, y = t.y + m[5] - o.bounds.y) else null)
            parts.add(StructureCommand(before, d.shape(), n.id, n.id))
            notifyLayers()
        }
        if (hasSelection) {
            val before = selEncoded
            val am = Matrix()
            am.setValues(o.hm.copyOf())
            am.preTranslate(-o.bounds.x.toFloat(), -o.bounds.y.toFloat())
            selection!!.transform(am)
            uploadSelection()
            parts.add(SelectionCommand(before, selEncoded))
        }
        if (parts.isNotEmpty()) history.push(if (parts.size == 1) parts[0] else CompoundCommand(parts))
        version++
        thumbQueue.add(n.id)
        endTransform(o)
        notifyHistory()
    }

    /**
     * 떠 있는 픽셀을 [s]에 내려놓습니다: 들어 올린 자리를 지우고(whole이면 전부, 아니면 선택 영역만) 변형해 그립니다.
     * 바뀐 타일의 이전 내용을 돌려줍니다 (실행취소용).
     */
    private fun placeFloating(s: TileSurface, floating: RenderTarget, bounds: IRect, hm: FloatArray, whole: Boolean, d: Document): HashMap<Int, ByteBuffer?> {
        val target = transformedRect(bounds, hm, d)
        val rect = bounds.union(target)
        val saved = HashMap<Int, ByteBuffer?>()
        val sel = selTexIfAny()
        for (key in s.keysIntersecting(rect)) {
            val existing = s.tiles[key]
            val inTarget = target?.intersects(s.tileRect(key)) == true
            if (existing == null && !inTarget) continue
            saved[key] = s.read(key)
            val t = s.getOrCreate(key)
            s.bindCanvasSpace(key, t)
            // 1) 들어 올린 자리 지우기
            bounds.intersect(s.tileRect(key))?.let { lift ->
                s.scissorCanvasRect(key, lift)
                if (whole) {
                    GLES20.glClearColor(0f, 0f, 0f, 0f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                } else {
                    GlState.erase()
                    compositor.drawMergeCoverage(sel, WHITE, 1f, 0, d.width, d.height)
                    GlState.off()
                }
            }
            // 2) 변형된 픽셀 올리기
            if (inTarget) {
                val r = target.intersect(s.tileRect(key))
                if (r != null) {
                    s.scissorCanvasRect(key, r)
                    // 변형은 투명 픽셀 잠금과 상관없이 옮깁니다 (잠금이면 들어 올린 자리가 비어 아무것도 안 그려짐).
                    GlState.over()
                    compositor.drawProjective(floating.tex, bounds.w, bounds.h, hm, d.width, d.height, 1f)
                    GlState.off()
                }
            }
            GlState.noScissor()
            s.dropIfEmpty(key)
        }
        saved.keys.toList().forEach { k -> if (saved[k] == null && s.tiles[k] == null) saved.remove(k) }
        return saved
    }

    private fun endTransform(o: Op.Transform) {
        o.floating.release()
        o.maskFloating?.release()
        op = null
        markAllDirty()
        main.post { listener.onTransformEnded() }
    }

    // ---- 색 혼합 ----

    private fun beginSmudge(n: Node, brush: Brush) {
        val d = doc ?: return
        if (isMaskEdit(n)) {
            reportError("마스크에는 색 혼합 도구를 쓸 수 없습니다.")
            return
        }
        val s = surfaces[n.id] ?: return
        val work = smudgeBuf ?: RenderTarget(d.width, d.height).also { smudgeBuf = it }
        work.clear()
        work.bind()
        GlState.off()
        drawSourceCopy(Src.Tiles(s), 1f, IRect(0, 0, d.width, d.height))
        op = Op.Smudge(brush, work, n.props.alphaLock)
        if (!n.props.visible) main.post { listener.onRendererError("숨겨진 레이어에 그리고 있습니다.") }
    }

    /** 스탬프마다: 둘레를 patch로 복사 → 작업 버퍼에 섞어 그림. */
    private fun smudgeStamps(o: Op.Smudge, stamps: FloatArray, channel: Int) {
        val d = doc ?: return
        val count = stamps.size / StrokeBuilder.FLOATS
        val sel = selTexIfAny()
        for (i in 0 until count) {
            val b = i * StrokeBuilder.FLOATS
            val x = stamps[b]
            val y = stamps[b + 1]
            val rad = stamps[b + 2]
            val prev = o.prev[channel]
            o.prev[channel] = floatArrayOf(x, y)
            val mode = o.brush.mixMode
            val drags = mode == Brush.MIX_SMUDGE || mode == Brush.MIX_PUSH
            // 손끝·밀기는 직전 위치가 있어야 끌고 올 색이 있습니다.
            if (drags && prev == null) continue
            val px = prev?.get(0) ?: x
            val py = prev?.get(1) ?: y
            // 스탬프 사각형(회전 포함) 반경 + 가져올 범위
            val reach = (rad + 1f) * 1.42f + 2f
            val extra = when (mode) {
                Brush.MIX_BLUR -> rad * 0.5f + 1f
                Brush.MIX_BLOAT, Brush.MIX_PINCH -> rad * 0.5f + 2f
                else -> 0f
            }
            val stampRect = IRect.ofBounds(x - reach, y - reach, x + reach, y + reach, d.width, d.height) ?: continue
            var src = IRect.ofBounds(x - reach - extra, y - reach - extra, x + reach + extra, y + reach + extra, d.width, d.height) ?: continue
            if (drags) {
                IRect.ofBounds(px - reach, py - reach, px + reach, py + reach, d.width, d.height)?.let { src = src.union(it) }
            }
            val patch = ensurePatch(src.w, src.h)
            // patch ← 작업 버퍼의 src 영역 (뷰포트를 밀어 캔버스 좌표계로)
            GlState.bindFbo(patch.fbo)
            GLES20.glViewport(-src.x, -src.y, d.width, d.height)
            GlState.noScissor()
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GlState.off()
            GlState.scissor(0, 0, src.w, src.h)
            compositor.drawCopy(o.work.tex, 1f)
            GlState.noScissor()
            // 작업 버퍼에 섞기
            o.work.bind()
            GlState.scissor(stampRect)
            GlState.off()
            brushEngine.drawSmudge(o.work, stamps, i, o.brush, patch, src.x, src.y, px - x, py - y, sel, o.lock)
            GlState.noScissor()
            o.rect = stampRect.union(o.rect)
            markDirty(stampRect)
        }
    }

    /** 최소 w×h인 patch 버퍼 (작으면 2배씩 키워 다시 만듦). 선형 보간으로 읽습니다. */
    private fun ensurePatch(w: Int, h: Int): RenderTarget {
        val cur = smudgePatch
        if (cur != null && cur.width >= w && cur.height >= h) return cur
        cur?.release()
        var nw = max(cur?.width ?: 128, 128)
        var nh = max(cur?.height ?: 128, 128)
        while (nw < w) nw *= 2
        while (nh < h) nh *= 2
        val p = RenderTarget(min(nw, maxTextureSize), min(nh, maxTextureSize))
        p.setFilter(GLES20.GL_LINEAR, GLES20.GL_LINEAR)
        smudgePatch = p
        return p
    }

    private fun commitSmudge(o: Op.Smudge, d: Document) {
        val rect = o.rect ?: return
        val n = d.active?.takeIf { it.isRaster } ?: return
        val s = surfaces[n.id] ?: return
        val saved = HashMap<Int, ByteBuffer?>()
        for (key in s.keysIntersecting(rect)) {
            val r = rect.intersect(s.tileRect(key)) ?: continue
            saved[key] = s.read(key)
            val t = s.getOrCreate(key)
            s.bindCanvasSpace(key, t)
            s.scissorCanvasRect(key, r)
            GlState.off()
            compositor.drawCopy(o.work.tex, 1f)
            GlState.noScissor()
            s.dropIfEmpty(key)
        }
        saved.keys.toList().forEach { k -> if (saved[k] == null && s.tiles[k] == null) saved.remove(k) }
        markDirty(rect)
        if (saved.isEmpty()) return
        history.push(TilesCommand(n.id, saved))
        version++
        thumbQueue.add(n.id)
        notifyHistory()
    }

    // ---- 벡터 ----

    /** 벡터 레이어 [id]의 모든 선 굵기를 [factor]배로 (실행취소 한 단계). */
    fun scaleVectorWidth(id: Int, factor: Float) = post {
        finishOp()
        val d = doc ?: return@post
        val n = d.find(id)?.takeIf { it.props.vector } ?: return@post
        val s = surfaces[n.id] ?: return@post
        val old = vectors[n.id] ?: return@post
        if (old.isEmpty()) return@post
        val now = old.map { it.widthScaled(factor) }
        vectors[n.id] = now
        val saved = HashMap<Int, ByteBuffer?>()
        var area: IRect? = null
        for (v in old + now) {
            val b = v.bounds
            IRect.ofBounds(b[0], b[1], b[2], b[3], d.width, d.height)?.let { area = it.union(area) }
        }
        area?.let { rasterStrokes(d, s, it, now, saved) }
        history.push(CompoundCommand(listOf(TilesCommand(n.id, saved), VectorCommand(n.id, old, now))))
        thumbQueue.add(n.id)
        afterEdit()
    }

    /** 지우개 스탬프에 닿은 선을 지우고, 그 선들이 있던 영역을 남은 선으로 다시 그립니다. */
    private fun vectorErase(o: Op.VErase, stamps: FloatArray) {
        val d = doc ?: return
        val s = surfaces[o.layerId] ?: return
        val cur = o.current
        if (cur.isEmpty()) return
        val keep = ArrayList<VStroke>(cur.size)
        var area: IRect? = null
        for (v in cur) {
            var hit = false
            var i = 0
            while (i + 2 < stamps.size) {
                if (v.hits(stamps[i], stamps[i + 1], stamps[i + 2])) { hit = true; break }
                i += StrokeBuilder.FLOATS
            }
            if (hit) {
                val b = v.bounds
                IRect.ofBounds(b[0], b[1], b[2], b[3], d.width, d.height)?.let { area = it.union(area) }
            } else keep.add(v)
        }
        val a = area ?: return
        o.current = keep
        vectors[o.layerId] = keep
        rasterStrokes(d, s, a, keep, o.saved)
    }

    /**
     * [area] 안을 비우고 [strokes] 중 겹치는 선을 그때와 같은 브러시로 다시 그립니다.
     * 바꾸기 전 타일은 [saved]에 (이미 있으면 그대로) 보관합니다.
     */
    private fun rasterStrokes(d: Document, s: TileSurface, area: IRect, strokes: List<VStroke>, saved: HashMap<Int, ByteBuffer?>) {
        val keys = s.keysIntersecting(area)
        for (key in keys) {
            if (!saved.containsKey(key)) saved[key] = s.read(key)
            val t = s.getOrCreate(key)
            s.bindCanvasSpace(key, t)
            s.scissorCanvasRect(key, area.intersect(s.tileRect(key)) ?: continue)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GlState.noScissor()
        }
        val sb = strokeBuf!!
        for (v in strokes) {
            val b = v.bounds
            val vr = IRect.ofBounds(b[0], b[1], b[2], b[3], d.width, d.height) ?: continue
            val r = vr.intersect(area) ?: continue
            sb.clear()
            v.fill?.let { rasterizeFill(it, d) }
            val tip = v.brush.tipId?.let { id -> tips[id] ?: tipProvider(id)?.let { ensureTip(it) } } ?: 0
            brushEngine.draw(sb, v.stamps, v.brush, tip)
            val c = premul(v.color, 1f)
            for (key in s.keysIntersecting(r)) {
                val tr = r.intersect(s.tileRect(key)) ?: continue
                val t = s.getOrCreate(key)
                s.bindCanvasSpace(key, t)
                s.scissorCanvasRect(key, tr)
                GlState.over()
                compositor.drawMergeCoverage(sb.tex, c, v.brush.opacity, 0, d.width, d.height)
                GlState.off()
                GlState.noScissor()
            }
        }
        for (key in keys) s.dropIfEmpty(key)
        saved.keys.toList().forEach { k -> if (saved[k] == null && s.tiles[k] == null) saved.remove(k) }
        markDirty(area)
    }

    // ---- 필터 ----

    /** 미리보기 버퍼를 만들고 원본을 담습니다. 시작하지 못하면 false (안내는 여기서). */
    private fun beginFilterGl(spec: FilterSpec): Boolean {
        val d = doc ?: return false
        val n = editableActive() ?: return false
        finishOp()
        if (isMaskEdit(n) && !spec.kind.forMask) {
            reportError("마스크에는 흐리기만 쓸 수 있습니다.")
            return false
        }
        val target = editId(n)
        val s = surfaces[target] ?: return false
        val pad = spec.kind.pad
        fun grow(r: IRect) = IRect.ofBounds(
            (r.x - pad).toFloat(), (r.y - pad).toFloat(), (r.right + pad).toFloat(), (r.bottom + pad).toFloat(), d.width, d.height
        )
        val content = s.tileBounds()?.let { grow(it) }
        val b = if (content != null && hasSelection) selBounds?.let { grow(it) }?.let { content.intersect(it) } else content
        if (b == null) {
            reportError(if (hasSelection) "선택 영역 안에 필터를 적용할 픽셀이 없습니다." else "필터를 적용할 픽셀이 없습니다.")
            return false
        }
        val made = ArrayList<RenderTarget>(3)
        try {
            repeat(3) { made.add(RenderTarget(b.w, b.h)) }
        } catch (e: OutOfMemoryError) {
            made.forEach { it.release() }
            reportError("메모리가 부족해 필터를 쓸 수 없습니다. 선택 영역을 좁혀 보세요.")
            return false
        }
        val orig = made[0]
        // 원본 = 레이어 픽셀 (뷰포트를 밀어 캔버스 좌표계로 그림)
        GlState.bindFbo(orig.fbo)
        GLES20.glViewport(-b.x, -b.y, d.width, d.height)
        GlState.off()
        GlState.noScissor()
        for ((key, t) in s.tiles) {
            if (!s.tileRect(key).intersects(b)) continue
            compositor.drawTile(t.tex, s.originX(key), s.originY(key), d.width, d.height, 1f)
        }
        // 흐리기는 두 탭을 선형 보간으로 한 번에 읽습니다. 픽셀 중심만 읽는 곳에서는 결과가 같습니다.
        made.forEach { it.setFilter(GLES20.GL_LINEAR, GLES20.GL_LINEAR) }
        op = Op.Filter(spec, b, target, orig, made[1], made[2])
        markDirty(b)
        return true
    }

    /** 현재 값으로 result를 다시 계산합니다. */
    private fun runFilter(d: Document, o: Op.Filter) {
        val n = d.find(if (o.target < 0) -o.target else o.target)
        val lock = o.target > 0 && n?.props?.alphaLock == true
        val spec = o.spec
        val k = spec.kind
        val w = o.bounds.w
        val h = o.bounds.h
        val p = spec.values.toFloatArray()
        GlState.off()
        GlState.noScissor()
        if (k.blurs) {
            val radius = spec[0]
            val taps = FilterKind.taps(radius)
            o.result.bind()
            compositor.drawFilter(4, o.orig.tex, floatArrayOf(radius), 1f / w, 0f, taps)
            o.work.bind()
            compositor.drawFilter(4, o.result.tex, floatArrayOf(radius), 0f, 1f / h, taps)
        } else {
            o.work.bind()
            compositor.drawFilter(k.shaderId, o.orig.tex, p)
        }
        o.result.bind()
        val sharpen = k == FilterKind.SHARPEN
        compositor.drawFilterCombine(o.orig.tex, o.work.tex, selTexIfAny(), o.bounds, d.width, d.height, lock, sharpen, if (sharpen) spec[1] else 0f)
        o.stale = false
    }

    private fun commitFilterGl(o: Op.Filter, d: Document) {
        val s = surfaces[o.target]
        if (s == null) {
            endFilter(o); return
        }
        if (o.stale) runFilter(d, o)
        val b = o.bounds
        val m = floatArrayOf(1f, 0f, 0f, 1f, b.x.toFloat(), b.y.toFloat())
        val saved = HashMap<Int, ByteBuffer?>()
        for (key in s.keysIntersecting(b)) {
            val r = b.intersect(s.tileRect(key)) ?: continue
            saved[key] = s.read(key)
            val t = s.getOrCreate(key)
            s.bindCanvasSpace(key, t)
            s.scissorCanvasRect(key, r)
            GlState.off()
            compositor.drawAffine(o.result.tex, b.w, b.h, m, d.width, d.height, 1f)
            GlState.noScissor()
            s.dropIfEmpty(key)
        }
        saved.keys.toList().forEach { k -> if (saved[k] == null && s.tiles[k] == null) saved.remove(k) }
        if (saved.isNotEmpty()) {
            history.push(TilesCommand(o.target, saved))
            version++
            thumbQueue.add(o.target)
            notifyHistory()
        }
        endFilter(o)
    }

    private fun endFilter(o: Op.Filter) {
        o.release()
        if (op === o) op = null
        markDirty(o.bounds)
        main.post { listener.onFilterEnded() }
    }

    // ---- 선택 영역 ----

    private fun commitSelectionChange(before: ByteArray?) {
        uploadSelection()
        if (before != null || selEncoded != null) {
            history.push(SelectionCommand(before, selEncoded))
            notifyHistory()
        }
        requestRender()
    }

    /** CPU 마스크 → GPU + 압축본 갱신. */
    private fun uploadSelection() {
        val d = doc ?: return
        val m = selection
        if (m == null) {
            selEncoded = null; selBounds = null; setHasSelection(false); return
        }
        val buf = m.toBuffer()
        val b = SelectionMask.boundsOf(buf, d.width, d.height)
        selBounds = b
        if (b == null) {
            selEncoded = null
            setHasSelection(false)
            selTex?.clear()
        } else {
            selTex!!.uploadAll(buf)
            selEncoded = SelectionMask.encode(buf, d.width, d.height)
            setHasSelection(true)
        }
    }

    private fun setHasSelection(v: Boolean) {
        if (hasSelection != v) {
            hasSelection = v
            main.post { listener.onSelectionChanged(v) }
        }
    }

    // ---- 팁 ----

    private fun ensureTip(tip: kr.dfluid.paint.brush.TipImage): Int {
        tips[tip.id]?.let { return it }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        val b = tip.pixels.duplicate()
        b.rewind()
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, android.opengl.GLES30.GL_R8, tip.size, tip.size, 0,
            android.opengl.GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, b
        )
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        tips[tip.id] = ids[0]
        return ids[0]
    }

    // =====================================================================
    // 합성
    // =====================================================================

    private fun markDirty(r: IRect) {
        dirtyRegion = r.union(dirtyRegion)
    }

    private fun markAllDirty() {
        fullDirty = true
    }

    private fun pairAt(depth: Int): PingPong {
        val d = doc!!
        while (pairs.size <= depth) pairs.add(PingPong(RenderTarget(d.width, d.height), RenderTarget(d.width, d.height)))
        return pairs[depth]
    }

    /** 증분 합성을 시작할 루트 자식의 인덱스 (활성 노드의 루트 조상, 클리핑이면 그 기준 레이어). */
    /** 자신과 모든 조상 폴더가 보이는지 */
    private fun visibleInTree(n: Node): Boolean {
        var p: Node? = n
        while (p != null && p.id != ROOT_ID) {
            if (!p.props.visible) return false
            p = p.parent
        }
        return true
    }

    /** 노드의 루트 조상이 루트 자식 중 몇 번째인지. */
    private fun rootIndexOf(d: Document, node: Node): Int {
        var n = node
        while (n.parent != null && n.parent!!.id != ROOT_ID) n = n.parent!!
        return d.root.children.indexOf(n)
    }

    private fun startIndex(d: Document): Int {
        var n = d.active ?: return 0
        while (n.parent != null && n.parent!!.id != ROOT_ID) n = n.parent!!
        var i = d.root.children.indexOf(n)
        if (i < 0) return 0
        while (i > 0 && d.root.children[i].isRaster && d.root.children[i].props.clip) i--
        return i
    }

    private fun ensureComposite(d: Document) {
        val full = IRect(0, 0, d.width, d.height)
        var region = if (fullDirty) full else dirtyRegion?.intersect(full)
        fullDirty = false
        dirtyRegion = null
        if (region == null) {
            if (compResult != null) return
            region = full
        }
        // 경계 효과가 있으면 바뀐 곳 둘레의 테두리도 다시 그려야 합니다.
        val reach = borderReach(d)
        if (reach > 0 && region != full) {
            region = IRect.ofBounds(
                (region.x - reach).toFloat(), (region.y - reach).toFloat(),
                (region.right + reach).toFloat(), (region.bottom + reach).toFloat(), d.width, d.height,
            ) ?: region
        }
        val root = pairAt(0)
        val kids = d.root.children
        val start = startIndex(d)
        val startId = kids.getOrNull(start)?.id ?: -1
        val below = belowCache!!

        segMark(-1)
        if (!belowValid || belowStartId != startId) {
            root.cur.clear()
            composeChildren(kids, 0, start, root, full, 0)
            segMark(0)
            below.bind()
            GlState.off()
            GlState.noScissor()
            compositor.drawCopy(root.cur.tex, 1f)
            segMark(1)
            belowValid = true
            belowStartId = startId
            region = full
        }

        val active = d.active
        if (op != null && active != null && active.isRaster) {
            // 경계 효과는 영역 둘레까지 읽으므로 미리보기도 그만큼 넓게
            val pr = if (reach > 0) IRect.ofBounds(
                (region.x - reach).toFloat(), (region.y - reach).toFloat(),
                (region.right + reach).toFloat(), (region.bottom + reach).toFloat(), d.width, d.height,
            ) ?: region else region
            updatePreview(d, active, pr)
        }

        // 결과는 항상 같은 버퍼에 남깁니다. 블렌드 모드 핑퐁이 홀수 번 일어나면
        // 영역 밖 픽셀이 오래된 쪽 버퍼가 결과가 되기 때문입니다.
        val startBuf = root.cur
        startBuf.bind()
        GlState.scissor(region)
        GlState.off()
        compositor.drawCopy(below.tex, 1f)
        GlState.noScissor()
        segMark(2)
        composeChildren(kids, start, kids.size, root, region, 0)
        if (root.cur !== startBuf) {
            startBuf.bind()
            GlState.scissor(region)
            GlState.off()
            compositor.drawCopy(root.cur.tex, 1f)
            GlState.noScissor()
            root.swap()
        }
        compResult = root.cur
        mipDirty = true
        segMark(3)
    }

    // ---- 벤치마크 1단계 구간 시간 (어디가 느린지 보고서에 함께 표시) ----
    private val segMs = DoubleArray(4)
    private var segT = 0L
    private fun segMark(i: Int) {
        if (benchPhase != 1) return
        GLES20.glFinish()
        val now = System.nanoTime()
        if (i >= 0) segMs[i] += (now - segT) / 1e6
        segT = now
    }

    /** nodes[from until to]를 T에 합성. 클리핑 묶음은 기준 레이어와 함께 처리합니다. */
    private fun composeChildren(nodes: List<Node>, from: Int, to: Int, t: PingPong, r: IRect, depth: Int) {
        var i = from
        while (i < to) {
            val n = nodes[i]
            var j = i + 1
            while (j < to && nodes[j].isRaster && nodes[j].props.clip) j++
            val p = n.props
            if (p.visible && p.opacity > 0f && !(hideDrafts && p.draft)) {
                val clipped = nodes.subList(i + 1, j).filter { it.props.visible && it.props.opacity > 0f && !(hideDrafts && it.props.draft) }
                if (clipped.isEmpty()) {
                    composeNode(n, t, r, depth)
                } else {
                    val g = pairAt(depth + 1)
                    g.cur.clear(r)
                    if (n.isRaster) {
                        drawSource(layerSrc(n, r), g, BlendMode.NORMAL, 1f, false, r)
                    } else if (n.props.animation) {
                        animContent(n, g, r, depth)
                    } else {
                        composeChildren(n.children, 0, n.children.size, g, r, depth + 1)
                    }
                    for (c in clipped) drawSource(layerSrc(c, r), g, c.props.blend, c.props.opacity, true, r)
                    drawSource(Src.Tex(g.cur), t, normalBlend(n), p.opacity, false, r)
                }
            }
            i = j
        }
    }

    private fun composeNode(n: Node, t: PingPong, r: IRect, depth: Int) {
        if (n.isFolder && n.props.animation) {
            composeAnim(n, t, r, depth)
            return
        }
        if (n.isRaster) {
            drawSource(layerSrc(n, r), t, n.props.blend, n.props.opacity, false, r)
            return
        }
        if (n.props.blend == BlendMode.PASS_THROUGH && n.props.opacity >= 0.999f) {
            composeChildren(n.children, 0, n.children.size, t, r, depth)
            return
        }
        val g = pairAt(depth + 1)
        g.cur.clear(r)
        composeChildren(n.children, 0, n.children.size, g, r, depth + 1)
        drawSource(Src.Tex(g.cur), t, normalBlend(n), n.props.opacity, false, r)
    }

    private fun normalBlend(n: Node) = if (n.props.blend == BlendMode.PASS_THROUGH) BlendMode.NORMAL else n.props.blend

    private fun sourceOf(n: Node): Src {
        val d = doc!!
        val live = op != null && n.id == d.activeId
        return if (live && !isMaskEdit(n)) Src.Tex(preview!!) else Src.Tiles(surfaces[n.id]!!)
    }

    /**
     * 합성에 쓸 레이어 소스. 마스크가 켜져 있으면 임시 버퍼에 "레이어 × (1 − 마스크 알파)"를 만들어 돌려줍니다.
     * 임시 버퍼는 하나뿐이므로 돌려받은 즉시 그려야 합니다 (호출부는 모두 바로 drawSource).
     */
    /** 보이는 레이어 중 가장 굵은 경계 효과가 닿는 거리 (px, 없으면 0) */
    private fun borderReach(d: Document): Int {
        var m = 0f
        for (n in d.allNodes()) if (n.isRaster && n.props.borderWidth > 0f && n.props.visible) m = max(m, n.props.borderWidth)
        return if (m > 0f) kotlin.math.ceil(m).toInt() + 2 else 0
    }

    /** 레이어 컬러 적용 소스 ([r] 영역). 결과는 toneBuf가 아닌 별도 버퍼(borderBufs[0] 재사용 불가 → colorBuf). */
    private fun colorizeSrc(n: Node, src: Src, r: IRect, color: Int = n.props.layerColor): Src {
        val d = doc!!
        val b = borderBufs ?: Array(3) { RenderTarget(d.width, d.height) }.also { borderBufs = it }
        val srcTex = when (src) {
            is Src.Tex -> src.t
            is Src.Tiles -> {
                b[0].clear(r)
                b[0].bind()
                GlState.scissor(r)
                GlState.off()
                drawSourceCopy(src, 1f, r)
                GlState.noScissor()
                b[0]
            }
        }
        val out = colorBuf ?: RenderTarget(d.width, d.height).also { colorBuf = it }
        out.bind()
        GlState.scissor(r)
        GlState.off()
        compositor.drawColorize(srcTex.tex, color)
        GlState.noScissor()
        return Src.Tex(out)
    }

    /** 톤 효과: 농도를 망점으로 바꾼 소스 ([r] 영역). */
    private fun toneSrc(n: Node, src: Src, r: IRect): Src {
        val d = doc!!
        val srcTex = when (src) {
            is Src.Tex -> src.t
            is Src.Tiles -> {
                val b = borderBufs ?: Array(3) { RenderTarget(d.width, d.height) }.also { borderBufs = it }
                b[0].clear(r)
                b[0].bind()
                GlState.scissor(r)
                GlState.off()
                drawSourceCopy(src, 1f, r)
                GlState.noScissor()
                b[0]
            }
        }
        val out = toneBuf ?: RenderTarget(d.width, d.height).also { toneBuf = it }
        out.bind()
        GlState.scissor(r)
        GlState.off()
        compositor.drawTone(srcTex.tex, d.width, d.height, n.props.toneCell, Math.toRadians(n.props.toneAngle.toDouble()).toFloat(), premul(n.props.toneColor, 1f))
        GlState.noScissor()
        return Src.Tex(out)
    }

    private fun layerSrc(n: Node, r: IRect): Src {
        val bw = n.props.borderWidth
        val toned = n.props.toneCell > 0f
        val colored = n.props.layerColorOn
        if (bw <= 0f) {
            var m = maskedSrc(n, r)
            if (colored) m = colorizeSrc(n, m, r)
            return if (toned) toneSrc(n, m, r) else m
        }
        val d = doc!!
        val reach = kotlin.math.ceil(bw).toInt() + 1
        val er = IRect.ofBounds((r.x - reach).toFloat(), (r.y - reach).toFloat(), (r.right + reach).toFloat(), (r.bottom + reach).toFloat(), d.width, d.height) ?: r
        val src = maskedSrc(n, er).let { if (colored) colorizeSrc(n, it, er) else it }.let { if (toned) toneSrc(n, it, er) else it }
        val bufs = borderBufs ?: Array(3) { RenderTarget(d.width, d.height) }.also { borderBufs = it }
        // 이웃을 읽으려면 텍스처여야 합니다 (타일이면 한 장에 모음).
        val srcTex = when (src) {
            is Src.Tex -> src.t
            is Src.Tiles -> {
                bufs[0].clear(er)
                bufs[0].bind()
                GlState.scissor(er)
                GlState.off()
                drawSourceCopy(src, 1f, er)
                GlState.noScissor()
                bufs[0]
            }
        }
        // 1단계: 가로 거리 (er 전체)
        bufs[1].bind()
        GlState.scissor(er)
        GlState.off()
        compositor.drawBorderH(srcTex.tex, d.width, d.height, reach)
        // 2단계: 테두리 색, 그 위에 원본 (r만)
        val out = bufs[2]
        out.bind()
        GlState.scissor(r)
        GlState.off()
        compositor.drawBorderV(bufs[1].tex, d.width, d.height, reach, bw, premul(n.props.borderColor, 1f))
        GlState.over()
        compositor.drawCopy(srcTex.tex, 1f)
        GlState.off()
        GlState.noScissor()
        return Src.Tex(out)
    }

    /** 마스크까지 적용한 레이어 소스. */
    private fun maskedSrc(n: Node, r: IRect): Src {
        val base = sourceOf(n)
        if (!n.props.mask || !n.props.maskEnabled) return base
        val d = doc!!
        val tr = op as? Op.Transform
        val moving = tr?.maskFloating != null && n.id == d.activeId && !isMaskEdit(n)
        val maskSrc = if (op != null && n.id == d.activeId && isMaskEdit(n)) Src.Tex(preview!!)
        else Src.Tiles(surfaces[-n.id] ?: return base)
        val tmp = maskTmp ?: RenderTarget(d.width, d.height).also { maskTmp = it }
        tmp.clear(r)
        tmp.bind()
        GlState.scissor(r)
        GlState.off()
        drawSourceCopy(base, 1f, r)
        GlState.erase()
        if (moving && tr != null) {
            // 들어 올린 자리를 뺀 마스크 + 옮겨진 마스크
            if (!tr.whole) {
                val ms = (maskSrc as Src.Tiles).s
                for ((key, tile) in ms.tiles) {
                    if (!ms.tileRect(key).intersects(r)) continue
                    compositor.drawTile(tile.tex, ms.originX(key), ms.originY(key), d.width, d.height, 1f, selTex!!.tex, 2)
                }
            }
            compositor.drawProjective(tr.maskFloating!!.tex, tr.maskBounds!!.w, tr.maskBounds!!.h, tr.maskH()!!, d.width, d.height, 1f)
        } else {
            drawSourceCopy(maskSrc, 1f, r)
        }
        GlState.off()
        GlState.noScissor()
        return Src.Tex(tmp)
    }

    private fun drawSource(src: Src, t: PingPong, mode: BlendMode, opacity: Float, preserve: Boolean, r: IRect) {
        if (mode.shaderId == 0) {
            t.cur.bind()
            GlState.scissor(r)
            if (preserve) GlState.atop() else GlState.over()
            drawSourceCopy(src, opacity, r)
            GlState.off()
            GlState.noScissor()
            return
        }
        if (compositor.hasFetch && !benchNoFetch) {
            // framebuffer fetch: 대상 FBO에서 바로 읽고 써서 핑퐁·FBO 전환이 없습니다.
            val d = doc!!
            t.cur.bind()
            GlState.off()
            when (src) {
                is Src.Tex -> {
                    GlState.scissor(r)
                    compositor.drawBlendFetch(src.t.tex, opacity, mode.shaderId, preserve)
                }
                is Src.Tiles -> {
                    val b = src.s.tileBounds()?.intersect(r)
                    if (b != null) {
                        GlState.scissor(b)
                        for ((key, tile) in src.s.tiles) {
                            if (!src.s.tileRect(key).intersects(b)) continue
                            compositor.drawBlendTileFetch(tile.tex, src.s.originX(key), src.s.originY(key), d.width, d.height, opacity, mode.shaderId, preserve)
                        }
                    }
                }
            }
            GlState.noScissor()
            return
        }
        if (src is Src.Tiles) {
            // 타일 레이어: 내용이 있는 영역 밖은 합성해도 그대로이므로 그 영역만 처리하고,
            // 결과를 cur로 되돌려 씁니다 (swap하지 않으므로 영역 밖 픽셀이 오래된 버퍼로 바뀌지 않음).
            // 캔버스 전체를 두 번 복사하던 것을 → 내용 영역만 세 번 (A4 300dpi에서 큰 차이).
            val b = src.s.tileBounds()?.intersect(r) ?: return
            val d = doc!!
            t.other.bind()
            GlState.scissor(b)
            GlState.off()
            compositor.drawCopy(t.cur.tex, 1f)
            for ((key, tile) in src.s.tiles) {
                if (!src.s.tileRect(key).intersects(b)) continue
                compositor.drawBlendTile(tile.tex, src.s.originX(key), src.s.originY(key), d.width, d.height, t.cur.tex, opacity, mode.shaderId, preserve)
            }
            t.cur.bind()
            GlState.scissor(b)
            GlState.off()
            compositor.drawCopy(t.other.tex, 1f)
            GlState.noScissor()
            return
        }
        // 핑퐁: other ← cur (영역 복사), other ← blend(src, cur), swap
        t.other.bind()
        GlState.scissor(r)
        GlState.off()
        compositor.drawCopy(t.cur.tex, 1f)
        val d = doc!!
        when (src) {
            is Src.Tex -> compositor.drawBlend(src.t.tex, t.cur.tex, opacity, mode.shaderId, preserve)
            is Src.Tiles -> for ((key, tile) in src.s.tiles) {
                if (!src.s.tileRect(key).intersects(r)) continue
                compositor.drawBlendTile(tile.tex, src.s.originX(key), src.s.originY(key), d.width, d.height, t.cur.tex, opacity, mode.shaderId, preserve)
            }
        }
        GlState.noScissor()
        t.swap()
    }

    /** 바인딩/블렌딩/시저는 호출자가. */
    private fun drawSourceCopy(src: Src, opacity: Float, r: IRect) {
        val d = doc!!
        when (src) {
            is Src.Tex -> compositor.drawCopy(src.t.tex, opacity)
            is Src.Tiles -> for ((key, tile) in src.s.tiles) {
                if (!src.s.tileRect(key).intersects(r)) continue
                compositor.drawTile(tile.tex, src.s.originX(key), src.s.originY(key), d.width, d.height, opacity)
            }
        }
    }

    /** 활성 레이어 + 진행 중 작업을 preview 버퍼의 r 영역에 그립니다. */
    private fun updatePreview(d: Document, n: Node, r: IRect) {
        val pv = preview!!
        val onMask = isMaskEdit(n)
        val s = surfaces[editId(n)] ?: return
        (op as? Op.Filter)?.let { if (it.stale) runFilter(d, it) }
        pv.clear(r)
        pv.bind()
        GlState.scissor(r)
        GlState.off()
        val lock = !onMask && n.props.alphaLock
        when (val o = op) {
            is Op.Stroke -> {
                drawSourceCopy(Src.Tiles(s), 1f, r)
                val erase = if (onMask) !o.brush.isEraser else o.brush.isEraser
                if (!(erase && lock)) {
                    when {
                        erase -> GlState.erase()
                        lock -> GlState.atop()
                        else -> GlState.over()
                    }
                    compositor.drawMergeCoverage(strokeBuf!!.tex, o.color, o.brush.opacity, selTexIfAny(), d.width, d.height)
                }
            }
            is Op.Gradient -> {
                drawSourceCopy(Src.Tiles(s), 1f, r)
                when {
                    onMask -> GlState.erase()
                    lock -> GlState.atop()
                    else -> GlState.over()
                }
                val p = o.p
                compositor.drawMergeGradient(o.kind, o.stops, o.repeat, p[0], p[1], p[2], p[3], o.opacity, selTexIfAny(), d.width, d.height)
            }
            is Op.Transform -> {
                if (!o.whole) {
                    for ((key, tile) in s.tiles) {
                        if (!s.tileRect(key).intersects(r)) continue
                        compositor.drawTile(tile.tex, s.originX(key), s.originY(key), d.width, d.height, 1f, selTex!!.tex, 2)
                    }
                }
                GlState.over()
                compositor.drawProjective(o.floating.tex, o.bounds.w, o.bounds.h, o.hm, d.width, d.height, 1f)
            }
            is Op.Smudge -> compositor.drawCopy(o.work.tex, 1f)
            is Op.VErase -> drawSourceCopy(Src.Tiles(s), 1f, r)
            is Op.Filter -> {
                drawSourceCopy(Src.Tiles(s), 1f, r)
                o.bounds.intersect(r)?.let { fr ->
                    // 필터 영역은 결과로 바꿔 끼움 (블렌딩 끔)
                    GlState.scissor(fr)
                    GlState.off()
                    val b = o.bounds
                    compositor.drawAffine(o.result.tex, b.w, b.h, floatArrayOf(1f, 0f, 0f, 1f, b.x.toFloat(), b.y.toFloat()), d.width, d.height, 1f)
                }
            }
            null -> drawSourceCopy(Src.Tiles(s), 1f, r)
        }
        GlState.off()
        GlState.noScissor()
    }

    private fun processPicks() {
        if (pendingPicks.isEmpty()) return
        val d = doc
        val comp = compResult
        if (d == null || comp == null) {
            pendingPicks.clear()
            return
        }
        for ((pos, cb) in pendingPicks) {
            val x = pos[0]
            val y = pos[1]
            if (x !in 0 until d.width || y !in 0 until d.height) continue
            val px = comp.read(x, y, 1, 1)
            val r = px.get(0).toInt() and 0xFF
            val g = px.get(1).toInt() and 0xFF
            val b = px.get(2).toInt() and 0xFF
            val a = px.get(3).toInt() and 0xFF
            if (a == 0) continue
            val color = Color.rgb(
                (r * 255 / a).coerceAtMost(255),
                (g * 255 / a).coerceAtMost(255),
                (b * 255 / a).coerceAtMost(255)
            )
            main.post { cb(color) }
        }
        pendingPicks.clear()
    }

    private fun drawScreen(d: Document) {
        val comp = compResult ?: return
        val v = view
        GlState.bindFbo(0)
        GLES20.glViewport(0, 0, screenW, screenH)
        GlState.noScissor()
        val bd = backdrop
        GLES20.glClearColor(bd[0], bd[1], bd[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GlState.off()

        val scale = v[6]
        when {
            scale < 0.75f -> {
                if (mipDirty) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, comp.tex)
                    GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
                    mipDirty = false
                }
                comp.setFilter(GLES20.GL_LINEAR_MIPMAP_LINEAR, GLES20.GL_LINEAR)
            }
            scale >= 2f -> comp.setFilter(GLES20.GL_NEAREST, GLES20.GL_NEAREST)
            else -> comp.setFilter(GLES20.GL_LINEAR, GLES20.GL_LINEAR)
        }
        val showSel = hasSelection && op !is Op.Transform
        selTex?.setFilter(GLES20.GL_NEAREST, GLES20.GL_NEAREST)
        val time = (SystemClock.uptimeMillis() % 100_000L) / 1000f
        compositor.drawDisplay(comp.tex, v, d.width, d.height, screenW, screenH, if (showSel) selTex!!.tex else 0, time)
        // 다음 합성에서 레벨 0만 쓰도록 되돌림
        comp.setFilter(GLES20.GL_NEAREST, GLES20.GL_NEAREST)

        val c = cursor
        if (c != null) {
            GlState.over()
            compositor.drawCursor(c[0], screenH - c[1], max(c[2], 2f))
            GlState.off()
        }

        // 선택 영역 점선 애니메이션
        if (showSel && !antsScheduled) {
            antsScheduled = true
            main.postDelayed({
                antsScheduled = false
                if (hasSelection) requestRender()
            }, 120)
        }
    }

    /** 그림이 바뀌었으면 합성 결과를 축소판으로 (그리는 중이나 0.5초 안에는 미룸) */
    private fun processNavigator(d: Document) {
        if (!navigatorOn || navSent == version) return
        val comp = compResult ?: return
        val now = SystemClock.uptimeMillis()
        if (op != null || now - navTime < 500) {
            if (!navScheduled) {
                navScheduled = true
                main.postDelayed({ navScheduled = false; requestRender() }, 520)
            }
            return
        }
        val s = NAV.toFloat() / max(d.width, d.height)
        val tw = max(1, (d.width * s).roundToInt())
        val th = max(1, (d.height * s).roundToInt())
        var t = navTarget
        if (t == null || t.width != tw || t.height != th) {
            t?.release()
            t = RenderTarget(tw, th)
            navTarget = t
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, comp.tex)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        comp.setFilter(GLES20.GL_LINEAR_MIPMAP_LINEAR, GLES20.GL_LINEAR)
        t.bind()
        GlState.noScissor()
        GlState.off()
        compositor.drawCopy(comp.tex, 1f)
        comp.setFilter(GLES20.GL_NEAREST, GLES20.GL_NEAREST)
        val buf = t.readAll()
        val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        navSent = version
        navTime = now
        main.post { listener.onNavigator(bmp) }
    }

    private fun processThumbnail() {
        if (op != null || thumbQueue.isEmpty()) return
        val d = doc ?: return
        val id = thumbQueue.first()
        thumbQueue.remove(id)
        val s = surfaces[id]
        val th = thumbTarget
        if (s != null && th != null) {
            th.clear()
            th.bind()
            GlState.off()
            for ((key, tile) in s.tiles) {
                compositor.drawTile(tile.tex, s.originX(key), s.originY(key), d.width, d.height, 1f)
            }
            val buf = th.readAll()
            if (id < 0) {
                for (i in 0 until th.width * th.height) {
                    val v = (255 - (buf.get(i * 4 + 3).toInt() and 0xFF)).toByte()
                    buf.put(i * 4, v).put(i * 4 + 1, v).put(i * 4 + 2, v).put(i * 4 + 3, 0xFF.toByte())
                }
                buf.rewind()
            }
            val bmp = Bitmap.createBitmap(th.width, th.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            main.post { listener.onThumbnail(id, bmp) }
        }
        if (thumbQueue.isNotEmpty()) requestRender()
    }

    // =====================================================================
    // 구조 편집
    // =====================================================================

    private fun insert(parent: Node, index: Int, n: Node) {
        n.parent = parent
        parent.children.add(index.coerceIn(0, parent.children.size), n)
    }

    /** edit()이 트리를 고치고 새 활성 id를 돌려주면, 전/후 모양으로 실행취소 단계를 만듭니다. */
    private fun structural(edit: (Document) -> Int?) {
        val d = doc ?: return
        val before = d.shape()
        val activeBefore = d.activeId
        val newActive = edit(d) ?: return
        val after = d.shape()
        if (before == after && newActive == activeBefore) return
        val cmd = StructureCommand(before, after, activeBefore, newActive)
        cmd.redo(this)
        history.push(cmd)
        afterEdit()
    }

    private fun cloneSubtree(d: Document, n: Node, name: String? = null): Node {
        val c = Node(d.newId(), n.kind, if (name != null) n.props.copy(name = name) else n.props)
        if (n.isRaster) {
            val s = TileSurface(d.width, d.height, pool)
            surfaces[n.id]?.let { s.copyFrom(it) }
            surfaces[c.id] = s
            thumbQueue.add(c.id)
            vectors[n.id]?.let { vectors[c.id] = it }
            if (n.props.mask) {
                val m = TileSurface(d.width, d.height, pool)
                surfaces[-n.id]?.let { m.copyFrom(it) }
                surfaces[-c.id] = m
                thumbQueue.add(-c.id)
            }
        }
        for (ch in n.children) insert(c, c.children.size, cloneSubtree(d, ch))
        return c
    }

    // =====================================================================
    // LayerStore (히스토리 커맨드가 호출, GL 스레드)
    // =====================================================================

    override fun readTile(layerId: Int, key: Int): ByteBuffer? = surfaces[layerId]?.read(key)

    override fun writeTile(layerId: Int, key: Int, data: ByteBuffer?) {
        surfaces[layerId]?.write(key, data)
        thumbQueue.add(layerId)
    }

    override fun applyShape(shape: TreeShape, activeId: Int, parked: MutableMap<Int, Map<Int, ByteBuffer>>) {
        val d = doc ?: return
        val want = shape.filter { it.kind == NodeKind.RASTER }.flatMap { if (it.props.mask) listOf(it.id, -it.id) else listOf(it.id) }.toSet()
        for (id in surfaces.keys.toList()) {
            if (id !in want) {
                val s = surfaces.remove(id)!!
                parked[id] = s.toCpu()
                s.clear()
            }
        }
        for (id in want) {
            if (id !in surfaces) {
                val s = TileSurface(d.width, d.height, pool)
                parked.remove(id)?.let { s.loadCpu(it) }
                surfaces[id] = s
                thumbQueue.add(id)
            }
        }
        d.rebuild(shape)
        d.activeId = if (d.find(activeId) != null) activeId else d.allNodes().firstOrNull { it.isRaster }?.id ?: 0
        // 활성 레이어가 애니메이션 셀이면 그 프레임을 보여 줌 (새 셀·실행취소 뒤에도 보이는 셀에 그리도록)
        d.active?.takeIf { it.parent?.props?.animation == true }?.let { animFrame = it.index }
        belowValid = false
        markAllDirty()
    }

    override fun setSelectionMask(encoded: ByteArray?) {
        val d = doc ?: return
        if (encoded == null) {
            selection?.clear()
            selEncoded = null
            selBounds = null
            selTex?.clear()
            setHasSelection(false)
            return
        }
        val (_, buf) = SelectionMask.decode(encoded)
        val m = selection ?: SelectionMask(d.width, d.height).also { selection = it }
        m.fromBuffer(buf)
        selBounds = SelectionMask.boundsOf(buf, d.width, d.height)
        selTex!!.uploadAll(buf)
        selEncoded = encoded
        setHasSelection(true)
    }

    override fun captureAll(): DocumentData = captureData(doc!!)

    override fun setVector(layerId: Int, strokes: List<VStroke>) {
        vectors[layerId] = strokes
    }

    override fun replaceAll(data: DocumentData) {
        buildDocument(data, keepHistory = true, refit = true)
        clearSelectionState()
    }

    private fun afterEdit() {
        version++
        belowValid = false
        markAllDirty()
        notifyLayers()
        notifyHistory()
    }

    private fun afterHistory() {
        version++
        belowValid = false
        markAllDirty()
        notifyLayers()
        notifyHistory()
    }

    private fun notifyLayers() {
        val d = doc ?: return
        // 활성 노드가 접힌 폴더 안에 있으면 펼쳐서 목록에 보이게 합니다.
        var p = d.active?.parent
        while (p != null && p.id != ROOT_ID) {
            if (!p.props.expanded) p.props = p.props.copy(expanded = true)
            p = p.parent
        }
        val list = d.infos()
        val active = d.activeId
        val ids = d.rasterIds()
        main.post { listener.onLayersChanged(list, active, ids) }
        val af = animFolder(d)
        val count = af?.children?.size ?: 0
        if (af != null && count > 0) animFrame = animFrame.coerceIn(0, count - 1)
        val frame = animFrame
        main.post { listener.onAnimation(af != null, frame, count) }
    }

    private fun notifyHistory() {
        val u = history.canUndo
        val r = history.canRedo
        main.post { listener.onHistoryChanged(u, r) }
    }

    companion object {
        private const val TAG = "DFPaint"
        private const val THUMB = 96
        /** 내비게이터 축소판 긴 변 (px) */
        private const val NAV = 320
        private val WHITE = floatArrayOf(1f, 1f, 1f, 1f)
        /** 퀵 마스크 색 (프리멀티플라이드 빨강) */
        private val QUICK_MASK = floatArrayOf(0.95f, 0.15f, 0.2f, 1f)
        /** 어니언 스킨 색: 앞 프레임 빨강, 뒤 프레임 파랑 */
        private const val ONION_PREV = 0xFFE53935.toInt()
        private const val ONION_NEXT = 0xFF1E88E5.toInt()

        /** sRGB 색 + 알파 → 프리멀티플라이드 float4 */
        fun premul(color: Int, alpha: Float): FloatArray = floatArrayOf(
            Color.red(color) / 255f * alpha, Color.green(color) / 255f * alpha, Color.blue(color) / 255f * alpha, alpha
        )
    }
}
