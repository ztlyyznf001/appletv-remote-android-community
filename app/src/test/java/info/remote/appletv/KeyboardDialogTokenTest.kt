package info.remote.appletv

import org.junit.Assert.*
import org.junit.Test

class KeyboardDialogTokenTest {
    @Test fun staleImeEventCannotEnterNewEditorOnSameConnection() {
        assertFalse(KeyboardDialogToken(4, 8).matches(4, 9, true))
    }
    @Test fun dismissedAndReconnectedEditorsRejectCallbacks() {
        val old = KeyboardDialogToken(4, 8)
        assertFalse(old.matches(4, 8, false)); assertFalse(old.matches(5, 8, true))
    }
    @Test fun currentVisibleEditorAcceptsInput() {
        assertTrue(KeyboardDialogToken(4, 8).matches(4, 8, true))
    }
}
