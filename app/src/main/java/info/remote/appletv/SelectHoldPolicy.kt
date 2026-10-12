package info.remote.appletv

/** The TV needs a real down-to-up hold, not a delayed ordinary click. Cancellation releases immediately. */
internal class SelectHoldPolicy {
    private var downAt: Long? = null
    fun started(now: Long) { if (downAt == null) downAt = now }
    fun releaseDelay(now: Long, cancelled: Boolean): Long =
        if (cancelled) 0 else downAt?.let { (1000L - (now - it)).coerceIn(0L, 1000L) } ?: 0L
}
