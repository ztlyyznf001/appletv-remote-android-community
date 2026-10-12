package info.remote.appletv

/** Dismissal lasts for this editor session only, never for a later field or reconnect. */
internal class KeyboardFocusPolicy {
    var sessionId: String? = null
        private set
    private var dismissed: String? = null
    fun update(id: String?) {
        if (sessionId != id) dismissed = null
        sessionId = id
    }
    fun dismiss() { dismissed = sessionId }
    fun shouldOpen(): Boolean = sessionId != null && dismissed != sessionId
    fun reset() { sessionId = null; dismissed = null }
}
