package info.remote.appletv

import androidx.compose.material3.ButtonColors
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class RemoteButtonColorsTest {
    private val normal = ButtonColors(Color.Green, Color.White, Color.DarkGray, Color.Gray)

    @Test fun transientLockUsesExactlyTheNormalContainerAndContentColors() {
        val transient = retainEnabledButtonColors(normal, true)
        assertEquals(normal.containerColor, transient.containerColor)
        assertEquals(normal.contentColor, transient.contentColor)
        assertEquals(normal.containerColor, transient.disabledContainerColor)
        assertEquals(normal.contentColor, transient.disabledContentColor)
    }

    @Test fun realUnavailableStateRetainsOriginalDisabledPalette() {
        assertSame(normal, retainEnabledButtonColors(normal, false))
        assertNotEquals(normal.containerColor, normal.disabledContainerColor)
        assertNotEquals(normal.contentColor, normal.disabledContentColor)
    }
}
