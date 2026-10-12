package info.remote.appletv

import org.junit.Assert.*
import org.junit.Test

class KeyboardSyncPolicyTest {
    @Test fun composingChineseCandidatesNeverSend() {
        val sync = KeyboardSyncPolicy(); sync.bind("A")
        sync.edit("zhong", true); assertNull(sync.take())
        sync.edit("中文 😀", false); val send = sync.take()!!
        assertEquals("中文 😀", send.payload); assertTrue(sync.isReady(send))
    }
    @Test fun appendSendsOnlyUnsentSuffixWithoutDuplicatingOlderText() {
        val sync = KeyboardSyncPolicy(); sync.bind("A")
        sync.edit("中", false); val first = sync.take()!!; sync.complete(first, true)
        sync.edit("中文", false); assertEquals("文", sync.take()!!.payload)
    }
    @Test fun debounceCoalescesRapidEditsAndSameTextHasNoSecondSend() {
        val sync = KeyboardSyncPolicy(); sync.bind("A")
        listOf("a", "ab", "abc").forEach { sync.edit(it, false) }
        val send = sync.take()!!; assertEquals("abc", send.payload)
        sync.complete(send, true); assertNull(sync.take())
    }
    @Test fun staleQueuedRevisionIsSkippedBeforeIo() {
        val sync = KeyboardSyncPolicy(); sync.bind("A"); sync.edit("a", false)
        val queued = sync.take()!!; sync.edit("ab", false)
        assertFalse(sync.isReady(queued)); sync.complete(queued, false, attempted = false)
        assertEquals("ab", sync.take()!!.payload)
    }
    @Test fun inFlightInputCompletesAgainstItsOwnSnapshotThenSendsNewSuffix() {
        val sync = KeyboardSyncPolicy(); sync.bind("A"); sync.edit("a", false)
        val first = sync.take()!!; sync.edit("ab", false); assertNull(sync.take())
        sync.complete(first, true); assertEquals("b", sync.take()!!.payload)
    }
    @Test fun deletingAlreadySentTextRequiresExplicitReplacement() {
        val sync = KeyboardSyncPolicy(); sync.bind("A"); sync.edit("abc", false)
        sync.complete(sync.take()!!, true); sync.edit("a", false)
        assertTrue(sync.needsReplacement()); assertNull(sync.take()); assertNull(sync.take(manual = true))
        val replace = sync.take(replace = true, manual = true)!!
        assertTrue(replace.replace); assertEquals("a", replace.payload)
        sync.complete(replace, true); assertFalse(sync.needsReplacement())
    }
    @Test fun emptyReplacementIsExplicitNotAnAutomaticClear() {
        val sync = KeyboardSyncPolicy(); sync.bind("A")
        assertNull(sync.take()); assertEquals("", sync.take(replace = true, manual = true)!!.payload)
    }
    @Test fun sessionSwitchAndDismissInvalidateQueuedWorkAndCallbacks() {
        val sync = KeyboardSyncPolicy(); sync.bind("A"); sync.edit("secret", false)
        val queued = sync.take()!!; sync.bind("B")
        assertFalse(sync.isReady(queued)); assertFalse(sync.complete(queued, true)); assertEquals("", sync.draft)
        sync.edit("new", false); val fresh = sync.take()!!; assertEquals("B", fresh.editor)
        sync.bind(null); assertFalse(sync.isReady(fresh)); assertNull(sync.take())
    }
    @Test fun unsuccessfulSendDoesNotRetryAutomatically() {
        val sync = KeyboardSyncPolicy(); sync.bind("A"); sync.edit("abc", false)
        sync.complete(sync.take()!!, false); assertNull(sync.take())
        assertNotNull(sync.take(manual = true))
    }
    @Test fun newCompositionCancelsQueuedCommittedText() {
        val sync = KeyboardSyncPolicy(); sync.bind("A"); sync.edit("a", false)
        val queued = sync.take()!!; sync.edit("azhong", true)
        assertFalse(sync.isReady(queued)); sync.complete(queued, false, attempted = false); assertNull(sync.take())
    }
}
