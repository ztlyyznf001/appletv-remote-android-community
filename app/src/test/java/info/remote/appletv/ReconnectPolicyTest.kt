package info.remote.appletv

import org.junit.Assert.*
import org.junit.Test

class ReconnectPolicyTest {
    @Test fun restoresOnceOnEachReturnAndNeverInBackground() {
        val policy = ReconnectPolicy(true)
        assertFalse(policy.takeAttempt(true, true))
        policy.enterForeground()
        assertTrue(policy.takeAttempt(true, true))
        policy.connected()
        policy.leaveForeground()
        assertFalse(policy.takeAttempt(true, true))
        policy.enterForeground()
        assertTrue(policy.takeAttempt(true, true))
        assertFalse(policy.takeAttempt(true, true))
    }
    @Test fun discoveryCallbacksDoNotCauseRetryLoopsAfterFailure() {
        val policy = ReconnectPolicy(true)
        policy.enterForeground()
        assertFalse(policy.takeAttempt(false, true))
        assertFalse(policy.takeAttempt(true, false))
        assertTrue(policy.takeAttempt(true, true))
        repeat(10) { assertFalse(policy.takeAttempt(true, true)) }
        policy.leaveForeground()
        policy.enterForeground()
        assertTrue(policy.takeAttempt(true, true))
    }
    @Test fun explicitDisconnectSurvivesForegroundAndProcessRecreation() {
        val policy = ReconnectPolicy(true)
        policy.connected()
        policy.disconnectByUser()
        policy.leaveForeground()
        policy.enterForeground()
        assertFalse(policy.takeAttempt(true, true))
        val recreated = ReconnectPolicy(policy.enabled)
        recreated.enterForeground()
        assertFalse(recreated.takeAttempt(true, true))
        recreated.connected() // A successful explicit connection opts in again.
        recreated.leaveForeground()
        recreated.enterForeground()
        assertTrue(recreated.takeAttempt(true, true))
    }
    @Test fun transientFailureAllowsOnlyOneDelayedRetry() {
        val p = ReconnectPolicy(true); p.enterForeground()
        assertTrue(p.takeAttempt(true, true, 100))
        p.failed(200, true)
        assertFalse(p.takeAttempt(true, true, 3199)); assertTrue(p.takeAttempt(true, true, 3200))
        p.failed(4000, true); assertFalse(p.takeAttempt(true, true, 10000))
    }
    @Test fun authenticationFailureAndUserDisconnectNeverRetry() {
        val p = ReconnectPolicy(true); p.enterForeground(); assertTrue(p.takeAttempt(true, true, 0))
        p.failed(10, false); assertFalse(p.takeAttempt(true, true, 10000))
        p.enterForeground(); assertTrue(p.takeAttempt(true, true, 0)); p.failed(1, true)
        p.disconnectByUser(); assertFalse(p.takeAttempt(true, true, 10000))
    }
    @Test fun missingPairedLastDeviceCannotConsumeAttempt() {
        val p = ReconnectPolicy(true); p.enterForeground()
        assertFalse(p.takeAttempt(false, true, 0)); assertTrue(p.takeAttempt(true, true, 0))
    }
}
