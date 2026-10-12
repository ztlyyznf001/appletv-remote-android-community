package info.remote.appletv

import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class TouchGesturePumpTest {
    @Test fun slowNetworkCoalescesMovesButPreservesEndpoints() {
        val tasks = ArrayDeque<Runnable>()
        val sent = mutableListOf<GestureSample>()
        val pump = TouchGesturePump(Executor { tasks.add(it) }, { sent.add(it) }, { throw it })
        pump.offer(GestureSample(0, 0, GesturePhase.BEGIN))
        repeat(10000) { pump.offer(GestureSample(it, it, GesturePhase.MOVE)) }
        pump.offer(GestureSample(700, 800, GesturePhase.END, true))
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(listOf(GesturePhase.BEGIN, GesturePhase.MOVE, GesturePhase.END), sent.map { it.phase })
        assertEquals(1000, sent[1].x)
        assertTrue(sent.last().tap)
        pump.offer(GestureSample(1, 1, GesturePhase.MOVE))
        assertTrue(tasks.isEmpty())
    }
    @Test fun disconnectDropsQueuedGestures() {
        val tasks = ArrayDeque<Runnable>()
        val sent = mutableListOf<GestureSample>()
        val pump = TouchGesturePump(Executor { tasks.add(it) }, { sent.add(it) }, { throw it })
        pump.offer(GestureSample(0, 0, GesturePhase.BEGIN))
        pump.abort()
        tasks.removeFirst().run()
        assertTrue(sent.isEmpty())
    }
    @Test fun transportFailureStopsFollowingEventsAndReportsOnce() {
        val tasks = ArrayDeque<Runnable>()
        var failures = 0
        var sent = 0
        val pump = TouchGesturePump(Executor { tasks.add(it) }, { sent++; throw java.io.IOException() }, { failures++ })
        pump.offer(GestureSample(0, 0, GesturePhase.BEGIN))
        pump.offer(GestureSample(1, 1, GesturePhase.END, true))
        tasks.removeFirst().run()
        assertEquals(1, sent)
        assertEquals(1, failures)
    }
    @Test fun coalescingPreservesHoldBeforeEnd() {
        val tasks = ArrayDeque<Runnable>(); val sent = mutableListOf<GestureSample>()
        val pump = TouchGesturePump(Executor { tasks.add(it) }, { sent.add(it) }, { throw it })
        pump.offer(GestureSample(10, 20, GesturePhase.BEGIN))
        pump.offer(GestureSample(10, 20, GesturePhase.HOLD))
        repeat(50) { pump.offer(GestureSample(10, 20, GesturePhase.MOVE)) }
        pump.offer(GestureSample(10, 20, GesturePhase.END))
        tasks.removeFirst().run()
        assertEquals(listOf(GesturePhase.BEGIN, GesturePhase.HOLD, GesturePhase.MOVE, GesturePhase.END), sent.map { it.phase })
        assertFalse(sent.last().tap)
    }
}
