package kr.dfluid.paint.document

import java.nio.ByteBuffer

/** 커맨드가 문서를 바꿀 때 쓰는 인터페이스. CanvasRenderer가 GL 스레드에서 구현합니다. */
interface LayerStore {
    /** 타일 하나 읽기. 없는 타일 = null */
    fun readTile(layerId: Int, key: Int): ByteBuffer?
    /** 타일 하나 쓰기. null = 타일 제거 */
    fun writeTile(layerId: Int, key: Int, data: ByteBuffer?)

    /**
     * 트리를 shape 대로 다시 만듭니다.
     * shape에 없는 살아 있는 레이어 → 픽셀을 parked로 옮기고 GPU에서 해제,
     * shape에 있는데 살아 있지 않은 레이어 → parked에서 꺼내 복원 (없으면 빈 레이어).
     */
    fun applyShape(shape: TreeShape, activeId: Int, parked: MutableMap<Int, Map<Int, ByteBuffer>>)

    /** RLE로 압축된 선택 마스크 (SelectionMask.encode). null = 선택 없음 */
    fun setSelectionMask(encoded: ByteArray?)

    /** 문서 전체 스냅샷 (캔버스 크기·회전처럼 모든 픽셀이 바뀌는 편집의 실행취소용) */
    fun captureAll(): DocumentData

    /** 문서 전체를 [data]로 바꿉니다. 실행취소 기록은 그대로 두고 선택 영역은 해제합니다. */
    fun replaceAll(data: DocumentData)

    /** 벡터 레이어의 선 목록 바꾸기 (픽셀은 함께 묶인 TilesCommand가) */
    fun setVector(layerId: Int, strokes: List<VStroke>) {}
}

interface HistoryCommand {
    val bytes: Long
    fun undo(s: LayerStore)
    fun redo(s: LayerStore)
}

/**
 * 실행취소 스택. GL 스레드에서만 사용합니다.
 * 스냅샷은 CPU 메모리에 있으므로 GL 컨텍스트를 잃어도 남습니다.
 */
class History(
    private val maxSteps: Int = 100,
    private val maxBytes: Long = 512L * 1024 * 1024,
) {
    private val undoStack = ArrayDeque<HistoryCommand>()
    private val redoStack = ArrayDeque<HistoryCommand>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoCount: Int get() = undoStack.size

    /** 다음 [push]에 붙일 이름 (작업 내역 창용). 없으면 커맨드 종류로 짐작 */
    var nextLabel: String? = null
    /** 커맨드 → 이름 (커맨드는 equals를 쓰지 않으므로 객체 자체로 구분) */
    private val labels = HashMap<HistoryCommand, String>()

    /** 작업 내역: 실행취소할 수 있는 단계(오래된 것부터) + 다시실행할 수 있는 단계(다음 것부터) */
    fun undoLabels(): List<String> = undoStack.map { labels[it] ?: describe(it) }
    fun redoLabels(): List<String> = redoStack.reversed().map { labels[it] ?: describe(it) }

    /** 실행취소·다시실행 기록이 쓰는 CPU 메모리 (바이트) */
    val totalBytes: Long get() = undoStack.sumOf { it.bytes } + redoStack.sumOf { it.bytes }

    fun push(cmd: HistoryCommand) {
        redoStack.forEach { labels.remove(it) }
        redoStack.clear()
        undoStack.addLast(cmd)
        labels[cmd] = nextLabel ?: describe(cmd)
        nextLabel = null
        trim()
    }

    private fun trim() {
        var total = undoStack.sumOf { it.bytes }
        while (undoStack.size > 1 && (undoStack.size > maxSteps || total > maxBytes)) {
            val c = undoStack.removeFirst()
            labels.remove(c)
            total -= c.bytes
        }
    }

    fun undo(s: LayerStore): Boolean {
        val c = undoStack.removeLastOrNull() ?: return false
        try {
            c.undo(s)
        } catch (t: Throwable) {
            // 실패하면 기록을 그대로 둡니다 (빠지면 이후 단계들이 다른 문서 상태에 적용됨)
            undoStack.addLast(c)
            throw t
        }
        redoStack.addLast(c)
        return true
    }

    fun redo(s: LayerStore): Boolean {
        val c = redoStack.removeLastOrNull() ?: return false
        try {
            c.redo(s)
        } catch (t: Throwable) {
            redoStack.addLast(c)
            throw t
        }
        undoStack.addLast(c)
        return true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        labels.clear()
    }

    private fun describe(c: HistoryCommand): String = when (c) {
        is TilesCommand -> "그리기"
        is StructureCommand -> "레이어"
        is SelectionCommand -> "선택 영역"
        is DocumentCommand -> "캔버스 편집"
        is CompoundCommand -> c.parts.firstOrNull { it !is SelectionCommand }?.let { describe(it) } ?: "편집"
        else -> "벡터 선"
    }
}

/**
 * 타일 단위 픽셀 변경. 실행취소/다시실행 모두 "현재 ↔ 보관본" 교환입니다.
 * saved[key] = null 은 "그 타일이 없었다"는 뜻입니다.
 */
class TilesCommand(private val layerId: Int, private val saved: HashMap<Int, ByteBuffer?>) : HistoryCommand {
    override val bytes: Long get() = saved.values.sumOf { (it?.capacity() ?: 0).toLong() }
    override fun undo(s: LayerStore) = swap(s)
    override fun redo(s: LayerStore) = swap(s)

    private fun swap(s: LayerStore) {
        for (k in saved.keys.toList()) {
            val cur = s.readTile(layerId, k)
            s.writeTile(layerId, k, saved[k])
            saved[k] = cur
        }
    }
}

/** 트리 구조/속성 변경. 삭제된 레이어의 픽셀은 parked에 보관됩니다. */
class StructureCommand(
    private val before: TreeShape,
    private val after: TreeShape,
    private val activeBefore: Int,
    private val activeAfter: Int,
) : HistoryCommand {
    private val parked = HashMap<Int, Map<Int, ByteBuffer>>()
    override val bytes: Long
        get() = parked.values.sumOf { m -> m.values.sumOf { it.capacity().toLong() } }

    override fun undo(s: LayerStore) = s.applyShape(before, activeBefore, parked)
    override fun redo(s: LayerStore) = s.applyShape(after, activeAfter, parked)
}

/** 선택 영역 변경. 마스크는 RLE로 압축해 보관합니다 (보통 수 KB). */
class SelectionCommand(private val before: ByteArray?, private val after: ByteArray?) : HistoryCommand {
    override val bytes: Long get() = ((before?.size ?: 0) + (after?.size ?: 0)).toLong()
    override fun undo(s: LayerStore) = s.setSelectionMask(before)
    override fun redo(s: LayerStore) = s.setSelectionMask(after)
}

/** 여러 커맨드를 한 단계로. 실행취소는 역순. */
class CompoundCommand(val parts: List<HistoryCommand>) : HistoryCommand {
    override val bytes: Long get() = parts.sumOf { it.bytes }
    override fun undo(s: LayerStore) {
        for (i in parts.indices.reversed()) parts[i].undo(s)
    }

    override fun redo(s: LayerStore) {
        for (p in parts) p.redo(s)
    }
}

/**
 * 문서 전체 교체 (캔버스 크기 변경·회전·반전). 보관본은 한 벌만:
 * 실행취소/다시실행 때마다 지금 문서를 잡아 두고 보관본으로 바꿉니다.
 */
class DocumentCommand(private var other: DocumentData) : HistoryCommand {
    override val bytes: Long
        get() = other.nodes.sumOf { n ->
            (n.tiles?.values?.sumOf { it.capacity().toLong() } ?: 0L) + (n.maskTiles?.values?.sumOf { it.capacity().toLong() } ?: 0L)
        }

    override fun undo(s: LayerStore) = swap(s)
    override fun redo(s: LayerStore) = swap(s)

    private fun swap(s: LayerStore) {
        val cur = s.captureAll()
        s.replaceAll(other)
        other = cur
    }
}
