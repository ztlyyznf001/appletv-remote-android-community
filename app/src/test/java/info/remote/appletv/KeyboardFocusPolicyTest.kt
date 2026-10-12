package info.remote.appletv

import org.junit.Assert.*
import org.junit.Test

class KeyboardFocusPolicyTest {
    @Test fun repeatedTextUpdatesDoNotReopenDismissedEditor() {
        val focus = KeyboardFocusPolicy()
        focus.update("editor-a")
        assertTrue(focus.shouldOpen())
        focus.dismiss()
        repeat(20) { focus.update("editor-a"); assertFalse(focus.shouldOpen()) }
    }
    @Test fun closingSwitchingAndReconnectingResetDismissal() {
        val focus = KeyboardFocusPolicy()
        focus.update("a"); focus.dismiss()
        focus.update("b"); assertTrue(focus.shouldOpen())
        focus.dismiss(); focus.update(null); assertFalse(focus.shouldOpen())
        focus.update("b"); assertTrue(focus.shouldOpen())
        focus.dismiss(); focus.reset(); focus.update("b"); assertTrue(focus.shouldOpen())
    }
}
