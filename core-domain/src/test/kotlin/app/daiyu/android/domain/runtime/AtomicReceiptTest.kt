package app.daiyu.android.domain.runtime

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class AtomicReceiptTest {
    private val initial=Message(MessageId("a"),ConversationId("c"),RunId("r"),"assistant",emptyList(),RunStatus.RUNNING)
    private class Store(val failOnText:Boolean=false,val failBefore:Boolean=false,val terminal:RunStatus?=null):AtomicRuntimeStore {
        var latest:RunCheckpoint?=null;var attempts=0;var reads=0;var failedAt=0
        override fun commitCheckpoint(checkpoint:RunCheckpoint) {
            attempts++
            val fail=if(failOnText) checkpoint.message.blocks.isNotEmpty() else checkpoint.phase==ExecutionPhase.TERMINAL
            if(fail) {check(failedAt==0) {"second write after failure"};failedAt=attempts}
            if(fail && failBefore) throw IllegalStateException("not committed")
            latest=if(fail && terminal!=null) checkpoint.copy(message=checkpoint.message.copy(status=terminal)) else checkpoint
            if(fail) throw IllegalStateException("receipt lost")
        }
        override fun readCommittedCheckpoint(checkpoint:RunCheckpoint):RunCheckpoint? {reads++;return latest}
        override fun reconcileCheckpoint(checkpoint:RunCheckpoint):RunCheckpoint=error("must not recover live owner")
    }
    private fun run(store:AtomicRuntimeStore,afterText:()->Unit={}):Message = ChatRunner(ModelPort {
        object:ModelSession {
            override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {
                sink(ModelEvent.TextFinal("t","committed text"));afterText()
            }
            override fun cancel() {}
        }
    },object:ConversationStore {override fun save(message:Message)=error("atomic writer only")},BatteryTool {error("must not execute")},
        RunnerOptions(runtimeStore=store)){}.run(initial,emptyList())
    @Test fun runningCommitLostReceiptDoesNotWriteFailedOrContinueModel() {
        val store=Store(failOnText=true);var continued=false
        val result=run(store){continued=true}
        assertEquals(RunStatus.FAILED,result.status);assertEquals(RunStatus.RUNNING,store.latest!!.message.status)
        assertEquals(store.latest!!.message.blocks,result.blocks);assertFalse(continued);assertEquals(1,store.reads);assertEquals(store.failedAt,store.attempts)
        assertTrue(result.error!!.startsWith("保存失败"))
    }
    @Test fun beforeCommitFailureLeavesOldRunningAndDoesNotTryAnotherWrite() {
        val store=Store(failOnText=true,failBefore=true);val result=run(store)
        assertEquals(RunStatus.RUNNING,store.latest!!.message.status);assertTrue(store.latest!!.message.blocks.isEmpty())
        assertTrue(result.blocks.isEmpty());assertEquals(1,store.reads);assertEquals(store.failedAt,store.attempts)
    }
    @Test fun anyActualTerminalWinsWithoutRecoveryOrSecondTerminalWrite() {
        for(status in listOf(RunStatus.COMPLETED,RunStatus.FAILED,RunStatus.STOPPED,RunStatus.INTERRUPTED)) {
            val store=Store(terminal=status);val result=run(store)
            assertEquals(status,result.status);assertEquals(result,store.latest!!.message);assertEquals(1,store.reads);assertEquals(store.failedAt,store.attempts)
        }
    }
    @Test fun nullCorruptOrFailedReadNeverReportsSuccess() {
        for(kind in 0..3) {
            val store=object:AtomicRuntimeStore {
                var latest:RunCheckpoint?=null;var failed=false
                override fun commitCheckpoint(checkpoint:RunCheckpoint) {
                    check(!failed) {"second write after failure"}
                    if(checkpoint.phase==ExecutionPhase.TERMINAL) {failed=true;throw IllegalStateException()}
                    latest=checkpoint
                }
                override fun readCommittedCheckpoint(checkpoint:RunCheckpoint):RunCheckpoint? = when(kind) {
                    0->null;1->error("read failure")
                    2->checkpoint.copy(message=checkpoint.message.copy(id=MessageId("wrong")))
                    else->checkpoint.copy(owner="foreign")
                }
                override fun reconcileCheckpoint(checkpoint:RunCheckpoint):RunCheckpoint=error("must not recover")
            }
            val result=run(store);assertEquals(RunStatus.FAILED,result.status);assertTrue(result.error!!.startsWith("保存失败"))
        }
    }
    @Test fun normalNetworkFailureCommitsFailedOnceWithoutReadOrRecovery() {
        var terminals=0;var latest:RunCheckpoint?=null
        val store=object:AtomicRuntimeStore {
            override fun commitCheckpoint(checkpoint:RunCheckpoint) {latest=checkpoint;if(checkpoint.phase==ExecutionPhase.TERMINAL) terminals++}
            override fun readCommittedCheckpoint(checkpoint:RunCheckpoint):RunCheckpoint?=error("normal failure must commit")
            override fun reconcileCheckpoint(checkpoint:RunCheckpoint):RunCheckpoint=error("must not recover")
        }
        val result=ChatRunner(ModelPort {throw ModelRequestException(ModelFailure.NETWORK)},
            object:ConversationStore {override fun save(message:Message)=error("legacy")},BatteryTool {error("no tools")},
            RunnerOptions(runtimeStore=store)){}.run(initial,emptyList())
        assertEquals(RunStatus.FAILED,result.status);assertEquals(1,terminals);assertEquals(latest!!.message,result)
    }

}
