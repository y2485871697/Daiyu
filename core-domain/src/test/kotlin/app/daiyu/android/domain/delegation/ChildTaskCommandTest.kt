package app.daiyu.android.domain.delegation

import org.junit.Assert.*
import org.junit.Test

class ChildTaskCommandTest {
    private val source = ChildTaskRecord(ChildRunOwner("conversation", "parent", "group", 3, "child", "assistant"),
        "parent-message", "parent-owner", 0, "review", "original", ChildTaskStatus.COMPLETED, "original result", revision = 4)
    private fun command(kind: ChildTaskCommandKind = ChildTaskCommandKind.APPEND) =
        ChildTaskCommand("call", source, kind, "review", "additional")

    @Test fun everyOwnerComponentAndExpectedRevisionAreExact() {
        val o = source.owner
        listOf(o.copy(conversationId = "other"), o.copy(parentRunId = "other"), o.copy(taskGroupId = "other"),
            o.copy(generation = 5), o.copy(childRunId = "other"), o.copy(assistantMessageId = "other")).forEach {
            assertEquals("child_source_stale", command().validateSource(source.copy(owner = it)))
        }
        assertEquals("child_source_stale", command().validateSource(source.copy(parentMessageId = "other")))
        assertEquals("child_source_stale", command().validateSource(source.copy(parentOwnerId = "other")))
        assertEquals("child_source_stale", command().validateSource(source.copy(revision = 5)))
        assertNull(command().validateSource(source))
    }

    @Test fun statusAloneNeverAuthorizesAnUnknownReplayOrRunningReplacement() {
        val unknown = source.copy(status = ChildTaskStatus.UNKNOWN, text = "", detail = "unconfirmed")
        val retry = command(ChildTaskCommandKind.REDISPATCH).copy(source = unknown)
        assertEquals("child_execution_confirmation_required", retry.validateSource(unknown))
        assertNull(retry.copy(acknowledgePossibleExecution = true).validateSource(unknown))
        val notExecuted = unknown.copy(status = ChildTaskStatus.NOT_EXECUTED)
        assertNull(retry.copy(source = notExecuted).validateSource(notExecuted))
        assertEquals("child_append_requires_result", command().copy(source = unknown).validateSource(unknown))
        val running = unknown.copy(status = ChildTaskStatus.RUNNING)
        assertEquals("child_execution_confirmation_required", command(ChildTaskCommandKind.REPLACE).copy(source = running).validateSource(running))
        assertEquals("child_not_failed", command(ChildTaskCommandKind.REDISPATCH).validateSource(source))
    }

    @Test fun fingerprintBindsExactArgumentsButNotMutableExecutionObservation() {
        val command = command()
        val old = command.fingerprint()
        assertEquals(old, command.copy(source = source.copy(status = ChildTaskStatus.RUNNING, text = "")).fingerprint())
        listOf(command.copy(instruction = "changed"), command.copy(profileId = "other"),
            command.copy(kind = ChildTaskCommandKind.REPLACE), command.copy(source = source.copy(revision = 5)),
            command.copy(acknowledgePossibleExecution = true), command.copy(source = source.copy(parentOwnerId = "other")))
            .forEach { assertNotEquals(old, it.fingerprint()) }
    }
}
