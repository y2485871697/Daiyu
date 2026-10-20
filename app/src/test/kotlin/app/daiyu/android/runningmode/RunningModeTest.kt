package app.daiyu.android.runningmode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RunningModeTest {
    private class Memory(var value: String? = null) : RunningModePersistence {
        var fail = false
        var writes = 0
        override fun read() = value
        override fun write(value: String) { check(!fail); writes++; this.value = value }
    }
    private class Authorization : RunningModeAuthorization {
        var root = false
        var shizuku = false
        var live = true
        var rootCalls = 0
        var shizukuCalls = 0
        var pending: CompletableDeferred<Boolean>? = null
        override suspend fun requestRoot(): Boolean { rootCalls++; return pending?.await() ?: root }
        override suspend fun requestShizuku(): Boolean { shizukuCalls++; return pending?.await() ?: shizuku }
        override suspend fun shizukuGranted() = live
    }

    @Test fun entryDoesNotPromptAndNormalNeedsNoAuthorization() = runBlocking {
        val memory = Memory()
        val store = RunningModeStore(memory)
        val auth = Authorization()
        val editor = RunningModeEditor(store, auth)
        assertEquals(RunningMode.NORMAL, store.mode.value)
        assertEquals(0, auth.rootCalls + auth.shizukuCalls)
        editor.select(RunningMode.NORMAL)
        assertEquals(0, auth.rootCalls + auth.shizukuCalls)
        assertEquals("NORMAL", memory.value)
    }

    @Test fun rootFailureKeepsPreviousModeAndSuccessPersists() = runBlocking {
        val memory = Memory("SHIZUKU")
        val store = RunningModeStore(memory)
        val auth = Authorization()
        val editor = RunningModeEditor(store, auth)
        editor.select(RunningMode.ROOT)
        assertEquals(1, auth.rootCalls)
        assertEquals("设备无root", editor.state.value.rootFailure)
        assertEquals(RunningMode.SHIZUKU, store.mode.value)
        assertEquals(0, memory.writes)
        auth.root = true
        editor.select(RunningMode.ROOT)
        assertEquals(2, auth.rootCalls)
        assertEquals(RunningMode.ROOT, RunningModeStore(memory).mode.value)
        assertNull(editor.state.value.rootFailure)
    }

    @Test fun shizukuOnlySelectsAfterCallbackAndLiveGrant() = runBlocking {
        val store = RunningModeStore(Memory())
        val auth = Authorization().apply { pending = CompletableDeferred() }
        val editor = RunningModeEditor(store, auth)
        val request = launch(start = CoroutineStart.UNDISPATCHED) { editor.select(RunningMode.SHIZUKU) }
        assertEquals(RunningMode.NORMAL, store.mode.value)
        assertEquals(RunningMode.SHIZUKU, editor.state.value.pending)
        editor.select(RunningMode.SHIZUKU)
        assertEquals(1, auth.shizukuCalls)
        auth.pending!!.complete(true)
        request.join()
        assertEquals(RunningMode.SHIZUKU, store.mode.value)
        auth.pending = null; auth.shizuku = true; auth.live = false
        editor.select(RunningMode.SHIZUKU)
        assertEquals(RunningMode.NORMAL, store.mode.value)
        assertEquals("授权失败", editor.state.value.shizukuFailure)
    }

    @Test fun denialDoesNotReplaceAnAuthorizedRootSelection() = runBlocking {
        val store = RunningModeStore(Memory("ROOT"))
        val editor = RunningModeEditor(store, Authorization())
        editor.select(RunningMode.SHIZUKU)
        assertEquals(RunningMode.ROOT, store.mode.value)
        assertEquals("授权失败", editor.state.value.shizukuFailure)
    }

    @Test fun leavingOrCancellationRejectsLateResults() = runBlocking {
        for (cancel in listOf(false, true)) {
            val memory = Memory()
            val store = RunningModeStore(memory)
            val auth = Authorization().apply { pending = CompletableDeferred() }
            val editor = RunningModeEditor(store, auth)
            val request = launch(start = CoroutineStart.UNDISPATCHED) { editor.select(RunningMode.ROOT) }
            if (cancel) request.cancel() else editor.close()
            auth.pending!!.complete(true)
            request.join()
            assertEquals(RunningMode.NORMAL, store.mode.value)
            assertEquals(0, memory.writes)
        }
    }

    @Test fun foregroundReturnIsRequiredBeforeSaving() = runBlocking {
        val store = RunningModeStore(Memory())
        val editor = RunningModeEditor(store, Authorization().apply { root = true })
        val resumed = CompletableDeferred<Unit>()
        val request = launch(start = CoroutineStart.UNDISPATCHED) { editor.select(RunningMode.ROOT) { resumed.await() } }
        assertEquals(RunningMode.NORMAL, store.mode.value)
        resumed.complete(Unit); request.join()
        assertEquals(RunningMode.ROOT, store.mode.value)
    }

    @Test fun failedPersistenceNeverShowsNewModeSelected() = runBlocking {
        val store = RunningModeStore(Memory().apply { fail = true })
        val editor = RunningModeEditor(store, Authorization().apply { root = true })
        editor.select(RunningMode.ROOT)
        assertEquals(RunningMode.NORMAL, store.mode.value)
        assertEquals("保存失败", editor.state.value.rootFailure)
    }

    @Test fun revokedShizukuIsNoLongerSelectedAndUnknownRecordsStayNormal() = runBlocking {
        val memory = Memory("SHIZUKU")
        val store = RunningModeStore(memory)
        val auth = Authorization().apply { live = false }
        val editor = RunningModeEditor(store, auth)
        editor.refreshShizuku()
        assertEquals(RunningMode.NORMAL, store.mode.value)
        assertEquals("授权失败", editor.state.value.shizukuFailure)
        assertEquals(0, auth.shizukuCalls)
        assertEquals(RunningMode.NORMAL, RunningModeStore(Memory("invalid")).mode.value)
    }
}
