package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.domain.tools.WriteEffect
import org.junit.Assert.*
import org.junit.Test

class ChildRunScopeTest {
    private val owner = ChildRunOwner("conversation", "parent", "group", 2, "child", "assistant")
    private val grant = WriteEffect("file", "replace", "known", "exact")
    private fun rejected(work: () -> Unit) { try { work(); fail("accepted") } catch (_: IllegalArgumentException) { } }
    @Test fun capabilitiesAreIntersectionNotUnion() {
        val child = ChildRunScope.capture(owner, SessionCapabilities("session", setOf("read")),
            TaskAuthorization("session", "parent"), setOf("read", "write"))
        assertEquals(setOf("read"), child.session.enabled)
        assertEquals("child", child.authorization.taskId)
        assertEquals("session", child.authorization.sessionId)
    }
    @Test fun writesDefaultToEmpty() {
        val child = ChildRunScope.capture(owner, SessionCapabilities("session", emptySet()),
            TaskAuthorization("session", "parent", setOf(grant)), emptySet())
        assertTrue(child.authorization.allowedWrites.isEmpty())
    }
    @Test fun writesNeedExactParentGrantAndExplicitRequest() {
        val child = ChildRunScope.capture(owner, SessionCapabilities("session", emptySet()),
            TaskAuthorization("session", "parent", setOf(grant)), emptySet(), setOf(grant, grant.copy(change = "other")))
        assertEquals(setOf(grant), child.authorization.allowedWrites)
    }
    @Test fun wrongParentSessionOrRunRejected() {
        rejected { ChildRunScope.capture(owner, SessionCapabilities("session", emptySet()), TaskAuthorization("other", "parent"), emptySet()) }
        rejected { ChildRunScope.capture(owner, SessionCapabilities("session", emptySet()), TaskAuthorization("session", "wrong"), emptySet()) }
    }
    @Test fun childIdentityMustBeValidAndDistinctFromParentRun() {
        rejected { owner.copy(childRunId = "parent") }
        rejected { owner.copy(assistantMessageId = "") }
        rejected { owner.copy(assistantMessageId = " ".repeat(3)) }
        rejected { owner.copy(assistantMessageId = "a".repeat(201)) }
    }
    @Test fun inputMutationCannotBroadenCapture() {
        val caps = mutableSetOf("read"); val writes = mutableSetOf(grant)
        val child = ChildRunScope.capture(owner, SessionCapabilities("session", caps), TaskAuthorization("session", "parent", writes), caps, writes)
        caps.add("write"); writes.clear(); writes.add(grant.copy(change = "unauthorized"))
        assertEquals(setOf("read"), child.session.enabled)
        assertEquals(setOf(grant), child.authorization.allowedWrites)
        try { (child.authorization.allowedWrites as MutableSet<WriteEffect>).clear(); fail() } catch (_: UnsupportedOperationException) { }
        try { (child.session.enabled as MutableSet<String>).add("write"); fail() } catch (_: UnsupportedOperationException) { }
    }
    @Test fun grandchildCannotRegainRemovedPermission() {
        val child = ChildRunScope.capture(owner, SessionCapabilities("session", setOf("read", "write")),
            TaskAuthorization("session", "parent", setOf(grant)), setOf("read"))
        val descendant = ChildRunScope.capture(owner.copy(parentRunId = "child", childRunId = "grandchild"),
            child.session, child.authorization, setOf("read", "write"), setOf(grant))
        assertEquals(setOf("read"), descendant.session.enabled)
        assertTrue(descendant.authorization.allowedWrites.isEmpty())
    }
    @Test fun messageMustBelongToExactChildConversationRunAssistantIdAndRole() {
        val scope = ChildRunScope.capture(owner, SessionCapabilities("session", emptySet()),
            TaskAuthorization("session", "parent"), emptySet())
        val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("child"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        scope.requireOwner(message)
        rejected { scope.requireOwner(message.copy(id = MessageId("other-assistant"))) }
        rejected { scope.requireOwner(message.copy(conversationId = ConversationId("other"))) }
        rejected { scope.requireOwner(message.copy(runId = RunId("parent"))) }
        rejected { scope.requireOwner(message.copy(role = Message.USER_ROLE)) }
    }

}
