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
import kr.dfluid.paint.document.TilesCommand
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
data class GradientSpec(val radial: Boolean, val toTransparent: Boolean)

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
    }

    /** CanvasView가 설정합니다. */
    var requestRender: () -> Unit = {}

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
        class Stroke(val brush: Brush, val color: FloatArray, val tipTex: Int) : Op() {
            var rect: IRect? = null
        }

        class Gradient(val kind: Int, val c0: FloatArray, val c1: FloatArray, var p: FloatArray, val opacity: Float) : Op()

        class Transform(val floating: RenderTarget, val bounds: IRect, var m: FloatArray, val whole: Boolean) : Op() {
            var lastRect: IRect = bounds
            /** 레이어를 옮길 때 함께 옮기는 마스크 (마스크 편집 중이 아니고 마스크가 있을 때) */
            var maskFloating: RenderTarget? = null
            var maskBounds: IRect? = null

            /** 마스크 떠 있는 픽셀의 행렬: 레이어 행렬에 두 경계의 차이만큼 평행이동을 더함 */
            fun maskMatrix(): FloatArray? {
                val mb = maskBounds ?: return null
                val dx = (mb.x - bounds.x).toFloat()
                val dy = (mb.y - bounds.y).toFloat()
                return floatArrayOf(m[0], m[1], m[2], m[3], m[4] + m[0] * dx + m[2] * dy, m[5] + m[1] * dx + m[3] * dy)
            }
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
        val n = editableActive() ?: return@post
        finishOp()
        val tipTex = if (tip != null) ensureTip(tip) else 0
        val c = premul(color, 1f)
        strokeBuf!!.clear()
        op = Op.Stroke(brush, c, tipTex)
        if (!n.props.visible) main.post { listener.onRendererError("숨겨진 레이어에 그리고 있습니다.") }
    }

    fun addStamps(stamps: FloatArray) = post {
        val o = op as? Op.Stroke ?: return@post
        val d = doc ?: return@post
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
    fun setStrokeLine(stamps: FloatArray) = post {
        val o = op as? Op.Stroke ?: return@post
        val d = doc ?: return@post
        strokeBuf!!.clear()
        val prev = o.rect
        o.rect = null
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
            o.rect = IRect.ofBounds(l, t, r, b, d.width, d.height)
        }
        // 이전 직선이 있던 영역까지 다시 합성해야 지워집니다.
        val dirty = o.rect?.union(prev) ?: prev
        dirty?.let { markDirty(it) }
    }

    fun endStroke() = post { finishOp() }

    fun cancelStroke() = post {
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

    /** 채우기·자동 선택이 참고할 이미지: 모든 레이어 합성 결과 또는 [n] 레이어만. */
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
        if (ref != FillOptions.REF_CURRENT) {
            ensureComposite(d)
            return compResult!!.readAll()
        }
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
        val n = if (opts.ref != FillOptions.REF_CURRENT) d.activeRaster ?: d.allNodes().firstOrNull { it.isRaster } else editableActive()
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
            val c1 = if (spec.toTransparent) premul(fg, 0f) else premul(bg, 1f)
            op = Op.Gradient(if (spec.radial) 2 else 1, premul(fg, 1f), c1, floatArrayOf(x0, y0, x1, y1), opacity)
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
        val n = editableActive() ?: return@post
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

    /** m = [a,b,c,d,tx,ty] : 떠 있는 픽셀의 로컬 px → 캔버스 px */
    fun setTransform(m: FloatArray) = post {
        val o = op as? Op.Transform ?: return@post
        val d = doc ?: return@post
        o.m = m
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
        val n = editableActive() ?: return@post
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

    fun addLayer() = post {
        finishOp()
        structural { d ->
            val a = d.active
            val parent = a?.parent ?: d.root
            val idx = if (a != null) a.index + 1 else parent.children.size
            val n = Node(d.newId(), NodeKind.RASTER, LayerProps(d.nextLayerName()))
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
    fun moveNode(delta: Int) = post {
        finishOp()
        structural { d ->
            val a = d.active ?: return@structural null
            val parent = a.parent ?: return@structural null
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
                        if (above.isFolder && above.props.expanded) insert(above, 0, a)
                        else insert(parent, idx + 1, a)
                    }
                    else -> return@structural null
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
                    else -> return@structural null
                }
            }
            a.id
        }
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
        val n = editableActive() ?: return@post
        if (hasSelection || isMaskEdit(n)) {
            // 마스크에서 "지우기" = 가리기 (선택이 없으면 전체)
            val (tex, rect) = selectionCoverage(d) ?: return@post
            commitCoverage(tex, floatArrayOf(1f, 1f, 1f, 1f), 1f, rect, eraser = true, useSel = false)
        } else {
            val s = surfaces[n.id]!!
            if (s.tileCount == 0) return@post
            val saved = HashMap<Int, ByteBuffer?>()
            for (k in s.tiles.keys.toList()) saved[k] = s.read(k)
            s.clear()
            history.push(TilesCommand(n.id, saved))
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
            ensureComposite(d)
            callback(d.width, d.height, compResult!!.readAll())
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
        d.rebuild(data.nodes.map { ShapeEntry(it.id, it.kind, it.props, it.parentId) })
        d.activeId = if (d.find(data.activeId) != null) data.activeId else d.allNodes().firstOrNull { it.isRaster }?.id ?: 0
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
        if (op is Op.Filter) main.post { listener.onFilterEnded() }
        op = null
        doc = null
    }

    private fun captureData(d: Document): DocumentData = DocumentData(
        d.width, d.height, d.activeId,
        d.shape().map { e ->
            NodeData(e.id, e.kind, e.props, e.parentId, surfaces[e.id]?.toCpu(), if (e.props.mask) surfaces[-e.id]?.toCpu() else null)
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

    private fun editableActive(): Node? {
        val d = doc ?: return null
        val n = d.active
        if (n == null || !n.isRaster) {
            reportError("폴더가 아닌 레이어를 선택하세요.")
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
                commitCoverage(strokeBuf!!.tex, o.color, o.brush.opacity, rect, o.brush.isEraser, useSel = hasSelection)
            }
            is Op.Gradient -> {
                op = null
                val rect = gradientRect(d)
                val p = o.p
                commitToActive(rect, eraser = false) {
                    compositor.drawMergeGradient(o.kind, o.c0, o.c1, p[0], p[1], p[2], p[3], o.opacity, selTexIfAny(), d.width, d.height)
                }
            }
            is Op.Transform -> commitTransformGl(o, d)
            is Op.Filter -> commitFilterGl(o, d)
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
            is Op.Stroke -> finishOp()
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
    private fun commitCoverage(coverageTex: Int, color: FloatArray, opacity: Float, rect: IRect, eraser: Boolean, useSel: Boolean) {
        val d = doc ?: return
        val sel = if (useSel) selTexIfAny() else 0
        commitToActive(rect, eraser) {
            compositor.drawMergeCoverage(coverageTex, color, opacity, sel, d.width, d.height)
        }
    }

    /**
     * 활성 레이어의 rect 범위 타일에 draw()를 실행하고 실행취소 단계를 남깁니다.
     * draw()는 캔버스 좌표계 셰이더를 쓰면 됩니다 (타일 오프셋 뷰포트가 설정됨).
     */
    private fun commitToActive(rect: IRect, eraser: Boolean, draw: () -> Unit) {
        val d = doc ?: return
        val n = d.active?.takeIf { it.isRaster } ?: return
        val target = editId(n)
        val s = surfaces[target] ?: return
        // 마스크(알파 = 가림)에서는 그리기 = 드러내기(지움), 지우개 = 가리기(칠함). 투명 잠금은 무시.
        val onMask = target < 0
        val eraser = if (onMask) !eraser else eraser
        val lock = !onMask && n.props.alphaLock
        markDirty(rect)
        if (eraser && lock) return
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
        if (saved.isEmpty()) return
        history.push(TilesCommand(target, saved))
        version++
        thumbQueue.add(target)
        notifyHistory()
    }

    // ---- 자유 변형 ----

    private fun beginTransformGl() {
        val d = doc ?: return
        val n = editableActive() ?: return
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

    private fun transformedRect(o: Op.Transform, d: Document): IRect? = transformedRect(o.bounds, o.m, d)

    private fun transformedRect(bounds: IRect, m: FloatArray, d: Document): IRect? {
        val w = bounds.w.toFloat()
        val h = bounds.h.toFloat()
        val xs = floatArrayOf(m[4], m[0] * w + m[4], m[2] * h + m[4], m[0] * w + m[2] * h + m[4])
        val ys = floatArrayOf(m[5], m[1] * w + m[5], m[3] * h + m[5], m[1] * w + m[3] * h + m[5])
        return IRect.ofBounds(xs.min() - 2, ys.min() - 2, xs.max() + 2, ys.max() + 2, d.width, d.height)
    }

    private fun commitTransformGl(o: Op.Transform, d: Document) {
        val n = d.active?.takeIf { it.isRaster }
        val s = n?.let { surfaces[editId(it)] }
        if (n == null || s == null) {
            endTransform(o); return
        }
        val saved = placeFloating(s, o.floating, o.bounds, o.m, o.whole, d)
        val parts = ArrayList<HistoryCommand>()
        if (saved.isNotEmpty()) parts.add(TilesCommand(editId(n), saved))
        // 함께 옮긴 마스크
        val mf = o.maskFloating
        val mb = o.maskBounds
        val ms = surfaces[-n.id]
        if (mf != null && mb != null && ms != null) {
            val ms2 = placeFloating(ms, mf, mb, o.maskMatrix()!!, o.whole, d)
            if (ms2.isNotEmpty()) parts.add(TilesCommand(-n.id, ms2))
            thumbQueue.add(-n.id)
        }
        if (hasSelection) {
            val before = selEncoded
            val am = Matrix()
            am.setValues(floatArrayOf(o.m[0], o.m[2], o.m[4], o.m[1], o.m[3], o.m[5], 0f, 0f, 1f))
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
    private fun placeFloating(s: TileSurface, floating: RenderTarget, bounds: IRect, m: FloatArray, whole: Boolean, d: Document): HashMap<Int, ByteBuffer?> {
        val target = transformedRect(bounds, m, d)
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
                    compositor.drawAffine(floating.tex, bounds.w, bounds.h, m, d.width, d.height, 1f)
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
        if (op != null && active != null && active.isRaster) updatePreview(d, active, region)

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
            if (p.visible && p.opacity > 0f) {
                val clipped = nodes.subList(i + 1, j).filter { it.props.visible && it.props.opacity > 0f }
                if (clipped.isEmpty()) {
                    composeNode(n, t, r, depth)
                } else {
                    val g = pairAt(depth + 1)
                    g.cur.clear(r)
                    if (n.isRaster) {
                        drawSource(layerSrc(n, r), g, BlendMode.NORMAL, 1f, false, r)
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
    private fun layerSrc(n: Node, r: IRect): Src {
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
            compositor.drawAffine(tr.maskFloating!!.tex, tr.maskBounds!!.w, tr.maskBounds!!.h, tr.maskMatrix()!!, d.width, d.height, 1f)
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
                compositor.drawMergeGradient(o.kind, o.c0, o.c1, p[0], p[1], p[2], p[3], o.opacity, selTexIfAny(), d.width, d.height)
            }
            is Op.Transform -> {
                if (!o.whole) {
                    for ((key, tile) in s.tiles) {
                        if (!s.tileRect(key).intersects(r)) continue
                        compositor.drawTile(tile.tex, s.originX(key), s.originY(key), d.width, d.height, 1f, selTex!!.tex, 2)
                    }
                }
                GlState.over()
                compositor.drawAffine(o.floating.tex, o.bounds.w, o.bounds.h, o.m, d.width, d.height, 1f)
            }
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
    }

    private fun notifyHistory() {
        val u = history.canUndo
        val r = history.canRedo
        main.post { listener.onHistoryChanged(u, r) }
    }

    companion object {
        private const val TAG = "DFPaint"
        private const val THUMB = 96
        private val WHITE = floatArrayOf(1f, 1f, 1f, 1f)

        /** sRGB 색 + 알파 → 프리멀티플라이드 float4 */
        fun premul(color: Int, alpha: Float): FloatArray = floatArrayOf(
            Color.red(color) / 255f * alpha, Color.green(color) / 255f * alpha, Color.blue(color) / 255f * alpha, alpha
        )
    }
}
