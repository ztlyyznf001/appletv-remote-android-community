package info.remote.appletv

/** Interaction locks and their visual treatment are deliberately separate. */
internal data class RemoteInteractionPolicy(
    val controlsEnabled: Boolean,
    val touchEnabled: Boolean,
    val keepControlAppearance: Boolean,
) {
    companion object {
        fun calculate(connected: Boolean, busy: Boolean, touching: Boolean, buttonInFlight: Boolean) =
            RemoteInteractionPolicy(
                controlsEnabled = connected && !busy && !touching,
                touchEnabled = connected && !busy,
                // A brief input lock must not look like a disconnection. Genuine blocking
                // operations (pairing/text submission) still use the unavailable treatment.
                keepControlAppearance = connected && (touching || buttonInFlight) && (!busy || buttonInFlight),
            )
    }
}
