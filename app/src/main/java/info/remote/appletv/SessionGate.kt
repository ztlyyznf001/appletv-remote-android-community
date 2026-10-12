package info.remote.appletv

/** Serializes cancellation with identity persistence: a cancelled attempt cannot save later. */
internal class SessionGate {
    private var generation = 0
    @Synchronized fun invalidate(): Int = ++generation
    @Synchronized fun current(): Int = generation
    @Synchronized fun isCurrent(candidate: Int): Boolean = generation == candidate
    @Synchronized fun commit(candidate: Int, action: () -> Unit): Boolean {
        if (generation != candidate) return false
        action()
        return true
    }
}
