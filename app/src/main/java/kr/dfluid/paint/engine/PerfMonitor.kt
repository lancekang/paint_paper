package kr.dfluid.paint.engine

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * 성능 측정 (로드맵 관문 1·2). GL 스레드에서 프레임마다 [frameStart]/[frameEnd]를 부르고,
 * UI 스레드는 펜 이벤트 시각을 [markInput]으로 알립니다.
 *
 * - 프레임 시간: onDrawFrame 안에서 쓴 CPU 시간 (GPU 대기는 포함 안 됨. 벤치마크는 glFinish로 포함)
 * - 펜 지연: MotionEvent.eventTime → 그 입력을 반영한 프레임의 onDrawFrame 끝.
 *   실제 화면 표시까지는 보통 vsync 1번이 더 걸립니다.
 */
class PerfMonitor {
    @Volatile var enabled = false

    /** 아직 그리지 않은 가장 이른 입력 시각(ms, uptime). 0 = 없음 */
    private val pendingInput = AtomicLong(0)

    private val frameMs = Window(240)
    private val intervalMs = Window(240)
    private val latencyMs = Window(240)
    private var startNs = 0L
    private var lastEndNs = 0L
    private var lastReport = 0L

    fun markInput(eventTimeMs: Long) {
        if (!enabled) return
        pendingInput.compareAndSet(0, eventTimeMs)
    }

    fun frameStart() {
        if (enabled) startNs = System.nanoTime()
    }

    /** 보고할 때가 되면 통계를 돌려줍니다 (0.5초마다). */
    fun frameEnd(): Stats? {
        if (!enabled) return null
        val now = System.nanoTime()
        frameMs.add((now - startNs) / 1e6f)
        if (lastEndNs != 0L) {
            val gap = (now - lastEndNs) / 1e6f
            // 0.25초 넘게 쉬었다면 유휴 구간이므로 FPS 계산에서 뺍니다.
            if (gap < 250f) intervalMs.add(gap)
        }
        lastEndNs = now
        val input = pendingInput.getAndSet(0)
        if (input != 0L) latencyMs.add((SystemClock.uptimeMillis() - input).toFloat())
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - lastReport < 500) return null
        lastReport = nowMs
        return snapshot()
    }

    fun reset() {
        frameMs.clear(); intervalMs.clear(); latencyMs.clear()
        lastEndNs = 0L
        pendingInput.set(0)
    }

    fun snapshot() = Stats(
        fps = intervalMs.mean().let { if (it > 0f) 1000f / it else 0f },
        frameAvg = frameMs.mean(), frameP95 = frameMs.percentile(0.95f),
        latencyAvg = latencyMs.mean(), latencyP95 = latencyMs.percentile(0.95f),
        latencySamples = latencyMs.size,
    )

    class Stats(
        val fps: Float,
        val frameAvg: Float,
        val frameP95: Float,
        val latencyAvg: Float,
        val latencyP95: Float,
        val latencySamples: Int,
    )

    /** 최근 N개 값 (링 버퍼). */
    class Window(private val cap: Int) {
        private val buf = FloatArray(cap)
        private var head = 0
        var size = 0
            private set

        fun add(v: Float) {
            buf[head] = v
            head = (head + 1) % cap
            if (size < cap) size++
        }

        fun clear() {
            head = 0; size = 0
        }

        fun mean(): Float {
            if (size == 0) return 0f
            var s = 0f
            for (i in 0 until size) s += buf[i]
            return s / size
        }

        fun percentile(q: Float): Float {
            if (size == 0) return 0f
            val sorted = buf.copyOf(size).also { it.sort() }
            return sorted[((size - 1) * q).toInt()]
        }

        fun min(): Float = if (size == 0) 0f else buf.copyOf(size).min()
    }
}
