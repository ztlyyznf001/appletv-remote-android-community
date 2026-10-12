package info.remote.appletv

import java.util.concurrent.Executor

internal enum class GesturePhase { BEGIN, MOVE, HOLD, END }
internal data class GestureSample(val x: Int, val y: Int, val phase: GesturePhase, val tap: Boolean = false, val cancelled: Boolean = false)

/** Keep endpoints, coalesce unsent moves, and serialize all I/O on the session worker. */
internal class TouchGesturePump(
    private val executor: Executor,
    private val send: (GestureSample) -> Unit,
    private val failed: (Exception) -> Unit
) {
    private val queue = ArrayDeque<GestureSample>()
    private var running = false
    private var started = false
    private var finished = false

    @Synchronized fun offer(sample: GestureSample) {
        if (finished) return
        if (!started) {
            if (sample.phase != GesturePhase.BEGIN) return
            started = true
        } else if (sample.phase == GesturePhase.BEGIN) return
        if (sample.phase == GesturePhase.MOVE && queue.lastOrNull()?.phase == GesturePhase.MOVE) queue.removeLast()
        queue.addLast(sample.copy(x = sample.x.coerceIn(0, 1000), y = sample.y.coerceIn(0, 1000)))
        if (sample.phase == GesturePhase.END) finished = true
        if (!running) {
            running = true
            try { executor.execute { drain() } }
            catch (e: Exception) { abort(); failed(e) }
        }
    }
    @Synchronized fun abort() { finished = true; queue.clear() }
    private fun drain() {
        try {
            while (true) {
                val next = synchronized(this) {
                    if (queue.isEmpty()) { running = false; null } else queue.removeFirst()
                } ?: return
                send(next)
            }
        } catch (e: Exception) { abort(); failed(e) }
    }
}
