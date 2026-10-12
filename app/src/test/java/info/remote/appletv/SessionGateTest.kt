package info.remote.appletv

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class SessionGateTest {
    @Test fun cancelledPairingCannotPersistOrPublish() {
        val gate = SessionGate()
        val attempt = gate.invalidate()
        gate.invalidate()
        var stored = false
        assertFalse(gate.commit(attempt) { stored = true })
        assertFalse(stored)
        assertFalse(gate.isCurrent(attempt))
    }
    @Test fun delayedBackgroundPairingCannotRestoreForgottenIdentity() {
        val gate = SessionGate()
        val attempt = gate.invalidate()
        val allowed = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            var identity: String? = null
            val result = worker.submit<Boolean> { check(allowed.await(2, TimeUnit.SECONDS)); gate.commit(attempt) { identity = "paired" } }
            gate.invalidate()
            identity = null
            allowed.countDown()
            assertFalse(result.get(2, TimeUnit.SECONDS))
            assertNull(identity)
            val replacement = gate.invalidate()
            assertTrue(gate.commit(replacement) { identity = "new pairing" })
            assertEquals("new pairing", identity)
        } finally { worker.shutdownNow() }
    }
}
