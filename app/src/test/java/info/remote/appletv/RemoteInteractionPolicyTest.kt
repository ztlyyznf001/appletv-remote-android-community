package info.remote.appletv

import org.junit.Assert.*
import org.junit.Test

class RemoteInteractionPolicyTest {
    @Test fun connectedIdleAllowsAllInput() {
        val policy = RemoteInteractionPolicy.calculate(true, false, false, false)
        assertTrue(policy.controlsEnabled)
        assertTrue(policy.touchEnabled)
        assertFalse(policy.keepControlAppearance)
    }

    @Test fun touchLocksOtherControlsWithoutCancellingItsOwnGestureOrChangingPalette() {
        val policy = RemoteInteractionPolicy.calculate(true, false, true, false)
        assertFalse(policy.controlsEnabled)
        assertTrue(policy.touchEnabled)
        assertTrue(policy.keepControlAppearance)
    }

    @Test fun buttonRoundTripLocksDuplicateInputButKeepsNormalAppearance() {
        val policy = RemoteInteractionPolicy.calculate(true, true, false, true)
        assertFalse(policy.controlsEnabled)
        assertFalse(policy.touchEnabled)
        assertTrue(policy.keepControlAppearance)
    }

    @Test fun blockingOperationsKeepRealUnavailableFeedback() {
        for (touching in listOf(false, true)) {
            val policy = RemoteInteractionPolicy.calculate(true, true, touching, false)
            assertFalse(policy.controlsEnabled)
            assertFalse(policy.touchEnabled)
            assertFalse(policy.keepControlAppearance)
        }
    }

    @Test fun disconnectedNeverLooksAvailableEvenWithAStaleTransientFlag() {
        for (busy in listOf(false, true)) for (touching in listOf(false, true)) {
            for (buttonInFlight in listOf(false, true)) {
                val policy = RemoteInteractionPolicy.calculate(false, busy, touching, buttonInFlight)
                assertFalse(policy.controlsEnabled)
                assertFalse(policy.touchEnabled)
                assertFalse(policy.keepControlAppearance)
            }
        }
    }

    @Test fun completedInputReturnsToIdleWithoutLeavingAVisualLock() {
        val before = RemoteInteractionPolicy.calculate(true, false, false, false)
        for (inFlight in listOf(
            RemoteInteractionPolicy.calculate(true, false, true, false),
            RemoteInteractionPolicy.calculate(true, true, false, true),
        )) {
            assertFalse(inFlight.controlsEnabled)
            assertTrue(inFlight.keepControlAppearance)
            assertEquals(before, RemoteInteractionPolicy.calculate(true, false, false, false))
        }
    }
}
