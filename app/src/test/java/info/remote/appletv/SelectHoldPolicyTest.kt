package info.remote.appletv

import org.junit.Assert.*
import org.junit.Test

class SelectHoldPolicyTest {
    @Test fun longPressHasRealWireHoldEvenIfFingerReleasesAtThreshold() {
        val hold = SelectHoldPolicy(); hold.started(500)
        assertEquals(980L, hold.releaseDelay(520, false))
        assertEquals(0L, hold.releaseDelay(1600, false))
    }
    @Test fun cancellationNeverWaitsForMinimumHold() {
        val hold = SelectHoldPolicy(); hold.started(500)
        assertEquals(0L, hold.releaseDelay(520, true))
    }
    @Test fun ordinaryTapDoesNotWaitAndDuplicateDownDoesNotExtendHold() {
        val hold = SelectHoldPolicy(); assertEquals(0L, hold.releaseDelay(200, false))
        hold.started(500); hold.started(900); assertEquals(500L, hold.releaseDelay(1000, false))
    }
}
