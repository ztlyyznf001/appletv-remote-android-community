package info.remote.appletv

/** A disposed editor's queued IME callbacks must not mutate the next dialog's draft. */
internal data class KeyboardDialogToken(val connection: Int, val draft: Int) {
    fun matches(currentConnection: Int, currentDraft: Int, visible: Boolean) =
        visible && connection == currentConnection && draft == currentDraft
}
