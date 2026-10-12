package info.remote.appletv

/** At most two automatic attempts per foreground visit, with a quiet backoff. */
internal class ReconnectPolicy(initiallyEnabled: Boolean) {
    var enabled = initiallyEnabled; private set
    private var foreground = false
    private var attempts = 0
    private var nextAttemptAt = 0L
    private var retryAllowed = true
    fun enterForeground() { foreground = true; attempts = 0; nextAttemptAt = 0; retryAllowed = true }
    fun leaveForeground() { foreground = false }
    fun connected() { enabled = true; attempts = 2 }
    fun disconnectByUser() { enabled = false; retryAllowed = false }
    fun failed(now: Long, retryable: Boolean) { nextAttemptAt = now + 3000; retryAllowed = retryable }
    fun takeAttempt(hasDevice: Boolean, idle: Boolean, now: Long = 0): Boolean {
        if (!enabled || !foreground || attempts >= 2 || !retryAllowed || !hasDevice || !idle || now < nextAttemptAt) return false
        attempts++
        // Discovery callbacks cannot make a second attempt until failure explicitly schedules it.
        nextAttemptAt = Long.MAX_VALUE
        return true
    }
}
