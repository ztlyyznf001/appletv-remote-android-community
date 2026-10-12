package info.remote.appletv

/** One in-memory draft, one serialized send; no composing text or stale editor can be replayed. */
internal class KeyboardSyncPolicy {
    data class Send(val epoch: Int, val revision: Int, val editor: String, val fullText: String, val payload: String, val replace: Boolean)
    private var epoch = 0
    private var revision = 0
    private var editor: String? = null
    private var composing = false
    private var sent = ""
    private var pending: Send? = null
    private var stopped = false
    var draft = ""; private set
    @Synchronized fun bind(id: String?) { epoch++; revision = 0; editor = id; draft = ""; sent = ""; pending = null; composing = false; stopped = false }
    @Synchronized fun edit(text: String, isComposing: Boolean) { draft = text; composing = isComposing; revision++ }
    @Synchronized fun needsReplacement() = !draft.startsWith(sent)
    @Synchronized fun take(replace: Boolean = false, manual: Boolean = false): Send? {
        val id = editor ?: return null
        if (pending != null || composing || (!manual && stopped) || (!replace && needsReplacement())) return null
        if (!replace && draft == sent) return null
        return Send(epoch, revision, id, draft, if (replace) draft else draft.removePrefix(sent), replace).also { pending = it }
    }
    @Synchronized fun isReady(send: Send) = send.epoch == epoch && pending == send && send.revision == revision && !composing
    /** Once I/O has started, newer edits must compare against exactly what was sent, not the new draft. */
    @Synchronized fun complete(send: Send, delivered: Boolean, attempted: Boolean = true): Boolean {
        if (send.epoch != epoch || pending != send) return false
        pending = null
        if (delivered) { sent = send.fullText; stopped = false } else if (attempted) stopped = true
        return true
    }
    @Synchronized fun pause() { stopped = true; revision++ }
}
