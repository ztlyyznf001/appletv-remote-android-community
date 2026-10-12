package info.remote.appletv

import org.junit.Assert.*
import org.junit.Test

class RemoteLayoutPolicyTest {
    @Test fun ordinaryPortraitDocksActionsAndMakesTouchSurfacePrimary() {
        val layout = RemoteLayoutPolicy.calculate(393f, 800f, 1f)
        assertTrue(layout.dockActions); assertFalse(layout.sideControls)
        assertEquals(345f, layout.controlWidthDp, .01f)
    }
    @Test fun compactPortraitStillDocksActions() {
        assertTrue(RemoteLayoutPolicy.calculate(320f, 520f, 1f).dockActions)
    }
    @Test fun shortLandscapeUsesSideRail() {
        val layout = RemoteLayoutPolicy.calculate(800f, 320f, 1f)
        assertTrue(layout.sideControls); assertTrue(layout.dockActions)
        assertTrue(layout.controlWidthDp >= 400f)
    }
    @Test fun largeTextAndExtremeHeightsUseScrollableFallback() {
        assertFalse(RemoteLayoutPolicy.calculate(320f, 400f, 2f).dockActions)
        assertFalse(RemoteLayoutPolicy.calculate(800f, 320f, 2f).sideControls)
        assertFalse(RemoteLayoutPolicy.calculate(800f, 180f, 1f).dockActions)
    }
    @Test fun tabletContentIsBounded() {
        assertEquals(520f, RemoteLayoutPolicy.calculate(1600f, 1800f, 1f).contentWidthDp, .01f)
        assertEquals(1040f, RemoteLayoutPolicy.calculate(1600f, 1000f, 1f).contentWidthDp, .01f)
    }
    @Test fun extremeNarrowDirectionLayoutUsesListFallback() {
        assertTrue(RemoteLayoutPolicy.calculate(180f, 360f, 2f).surfaceHeightDp < 180f)
    }
    @Test fun matrixFitsAvailableWindowAndPreservesMinimumGridCellsOrListFallback() {
        for (w in listOf(180f, 240f, 320f, 393f, 600f, 800f, 1600f)) for (h in listOf(180f, 320f, 520f, 800f)) for (f in listOf(1f, 1.5f, 2f)) {
            val l = RemoteLayoutPolicy.calculate(w, h, f)
            assertTrue(l.contentWidthDp <= w); assertTrue(l.surfaceHeightDp <= l.controlWidthDp)
            assertTrue(l.surfaceHeightDp < 180f || (l.surfaceHeightDp - 36f) / 3f >= 48f)
        }
    }
}
