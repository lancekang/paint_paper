package kr.dfluid.paint.document

import kr.dfluid.paint.engine.CpuTiles

enum class BlendMode(val label: String, val shaderId: Int) {
    NORMAL("표준", 0),
    MULTIPLY("곱하기", 1),
    SCREEN("스크린", 2),
    OVERLAY("오버레이", 3),
    ADD("더하기(발광)", 4),
    DARKEN("비교(어둡게)", 5),
    LIGHTEN("비교(밝게)", 6),
    DIFFERENCE("차이", 7),

    /** 폴더 전용: 자식을 폴더 밖 레이어와 바로 합성 */
    PASS_THROUGH("통과", 0);

    companion object {
        fun parse(name: String?): BlendMode = entries.firstOrNull { it.name == name } ?: NORMAL
        val forLayers: List<BlendMode> get() = entries.filter { it != PASS_THROUGH }
    }
}

enum class NodeKind { RASTER, FOLDER }

/** 레이어/폴더 속성 (픽셀 제외). 불변 값이라 스레드 간에 넘겨도 안전합니다. */
data class LayerProps(
    val name: String,
    val opacity: Float = 1f,
    val blend: BlendMode = BlendMode.NORMAL,
    val visible: Boolean = true,
    /** 투명 픽셀 잠금 */
    val alphaLock: Boolean = false,
    /** 아래 레이어에서 클리핑 */
    val clip: Boolean = false,
    /** 폴더 펼침 (UI 전용) */
    val expanded: Boolean = true,
)

/** UI에 보여주는 한 줄. 목록은 위 → 아래(화면 순서), depth = 폴더 깊이. */
data class NodeInfo(
    val id: Int,
    val kind: NodeKind,
    val props: LayerProps,
    val depth: Int,
    val parentId: Int,
    /** 클리핑이 켜져 있지만 아래에 기준 레이어가 없어 일반 레이어로 동작 */
    val orphanClip: Boolean,
)

/** 트리 모양 스냅샷의 한 항목. 부모가 자식보다 먼저, 형제는 아래 → 위 순서로 나옵니다. */
data class ShapeEntry(val id: Int, val kind: NodeKind, val props: LayerProps, val parentId: Int)

typealias TreeShape = List<ShapeEntry>

const val ROOT_ID = 0

/** GL 스레드 전용 트리 노드. children[0] = 맨 아래. */
class Node(val id: Int, val kind: NodeKind, var props: LayerProps) {
    var parent: Node? = null
    val children = ArrayList<Node>()
    val isFolder: Boolean get() = kind == NodeKind.FOLDER
    val isRaster: Boolean get() = kind == NodeKind.RASTER
    val index: Int get() = parent?.children?.indexOf(this) ?: -1

    fun isAncestorOf(n: Node): Boolean {
        var p = n.parent
        while (p != null) {
            if (p === this) return true
            p = p.parent
        }
        return false
    }

    val depth: Int
        get() {
            var d = 0
            var p = parent
            while (p != null && p.id != ROOT_ID) {
                d++; p = p.parent
            }
            return d
        }
}

/** GL 스레드 전용 문서 트리. 픽셀은 CanvasRenderer의 surfaces[id]에 있습니다. */
class Document(val width: Int, val height: Int) {
    val root = Node(ROOT_ID, NodeKind.FOLDER, LayerProps("root"))
    var activeId = 0
    private var nextId = 1

    fun newId(): Int = nextId++

    fun reserveId(id: Int) {
        if (id >= nextId) nextId = id + 1
    }

    fun find(id: Int): Node? = find(root, id)

    private fun find(n: Node, id: Int): Node? {
        if (n.id == id) return n
        for (c in n.children) find(c, id)?.let { return it }
        return null
    }

    val active: Node? get() = find(activeId)

    /** 활성 노드가 래스터면 그것, 아니면 null. */
    val activeRaster: Node? get() = active?.takeIf { it.isRaster }

    fun shape(): TreeShape {
        val out = ArrayList<ShapeEntry>()
        fun walk(n: Node) {
            for (c in n.children) {
                out.add(ShapeEntry(c.id, c.kind, c.props, n.id))
                walk(c)
            }
        }
        walk(root)
        return out
    }

    fun rebuild(shape: TreeShape) {
        root.children.clear()
        val map = HashMap<Int, Node>()
        map[ROOT_ID] = root
        for (e in shape) {
            val n = Node(e.id, e.kind, e.props)
            reserveId(e.id)
            val parent = map[e.parentId] ?: root
            n.parent = parent
            parent.children.add(n)
            map[e.id] = n
        }
    }

    fun allNodes(): List<Node> {
        val out = ArrayList<Node>()
        fun walk(n: Node) {
            for (c in n.children) {
                out.add(c); walk(c)
            }
        }
        walk(root)
        return out
    }

    fun rasterIds(): Set<Int> = allNodes().filter { it.isRaster }.map { it.id }.toSet()

    /** UI 목록: 위 → 아래, 접힌 폴더의 자식은 생략. */
    fun infos(): List<NodeInfo> {
        val out = ArrayList<NodeInfo>()
        fun walk(n: Node, depth: Int) {
            for (i in n.children.indices.reversed()) {
                val c = n.children[i]
                // 렌더러와 같은 규칙: 맨 아래의 클리핑 레이어와 클리핑 폴더는 일반 레이어처럼 동작
                val orphan = c.props.clip && (i == 0 || c.isFolder)
                out.add(NodeInfo(c.id, c.kind, c.props, depth, n.id, orphan))
                if (c.isFolder && c.props.expanded) walk(c, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    fun nextLayerName(prefix: String = "레이어"): String {
        val names = allNodes().map { it.props.name }.toSet()
        var n = allNodes().count { it.isRaster } + 1
        while ("$prefix $n" in names) n++
        return "$prefix $n"
    }

    fun nextFolderName(): String {
        val names = allNodes().map { it.props.name }.toSet()
        var n = allNodes().count { it.isFolder } + 1
        while ("폴더 $n" in names) n++
        return "폴더 $n"
    }
}

/** 파일 저장/불러오기, 일시정지 스냅샷에 쓰는 CPU 쪽 문서. 순서 = ShapeEntry와 같은 규칙. */
class NodeData(
    val id: Int,
    val kind: NodeKind,
    val props: LayerProps,
    val parentId: Int,
    /** 래스터만. 타일 키 → 256×256 RGBA */
    val tiles: CpuTiles?,
)

class DocumentData(
    val width: Int,
    val height: Int,
    val activeId: Int,
    val nodes: List<NodeData>,
)
