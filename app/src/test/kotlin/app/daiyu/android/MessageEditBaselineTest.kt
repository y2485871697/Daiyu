package app.daiyu.android

import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.storage.MessageActionSnapshot
import app.daiyu.android.storage.MessageActionTarget
import app.daiyu.android.storage.MessageEditBaseline
import org.junit.Assert.*
import org.junit.Test

class MessageEditBaselineTest {
    private val conversation = ConversationId("conversation")
    private val run = RunId("run")
    private val user = Message(MessageId("user"), conversation, run, Message.USER_ROLE,
        listOf(MessageBlock.Text("question", "original user")), RunStatus.COMPLETED)
    private val answer = user.copy(id = MessageId("answer"), role = Message.ASSISTANT_ROLE,
        blocks = listOf(MessageBlock.Tool(ToolInvocation(ToolCallId("lookup"), "lookup", "{}"), "old result")),
        status = RunStatus.STOPPED, error = "stopped")
    private val target = MessageActionTarget(conversation, user.id, user.id, run, Message.USER_ROLE, "", "assistant")
    private val history = listOf(user, answer)
    private fun snapshot(rows: List<Message>, binding: String? = "assistant"): MessageActionSnapshot {
        val revision = ContextRevision.digest(rows)
        val current = target.copy(revision = revision, assistantId = binding)
        val rowRevisions = rows.associate { it.id.value to ContextRevision.digest(listOf(it)) }
        return MessageActionSnapshot(conversation, revision, mapOf("user" to current),
            rowRevisions = rowRevisions, renderedTargets = mapOf("key" to current),
            editBaseline = MessageEditBaseline.capture(rows, rowRevisions))
    }
    private fun refresh(before: List<Message>, after: List<Message>): MessageActionTarget? {
        val original = snapshot(before)
        return snapshot(after).refreshedEditTarget("key", original.renderedTargets.getValue("key"), original)
    }

    @Test fun equalReloadRetainsTheSameEditTarget() {
        val original = snapshot(history)
        val target = original.renderedTargets.getValue("key")
        assertEquals(target, snapshot(history.map { it.copy() }).refreshedEditTarget("key", target, original))
    }
    @Test fun stoppedCompanionResultAndProtocolCanSettleWithoutChangingTheUser() {
        val updated = listOf(user, answer.copy(blocks = listOf(MessageBlock.Text("answer", "settled answer")),
            protocolItems = listOf("settled protocol"), status = RunStatus.COMPLETED, error = null))
        val refreshed = refresh(history, updated)
        assertNotNull(refreshed)
        assertEquals(ContextRevision.digest(updated), refreshed!!.revision)
        assertNotEquals(ContextRevision.digest(history), refreshed.revision)
    }
    @Test fun repeatedSettlingUsesEachAcknowledgedSnapshotWithoutReplayingAnAction() {
        val original = snapshot(history)
        val second = snapshot(listOf(user, answer.copy(error = "settled error")))
        val refreshed = checkNotNull(second.refreshedEditTarget("key", original.targets.getValue("user"), original))
        val third = snapshot(listOf(user, answer.copy(error = "final error")))
        assertNotNull(third.refreshedEditTarget("key", refreshed, second))
    }
    @Test fun originalTextAndImageMetadataChangesStillConflict() {
        val image = MessageBlock.Image(MessageImageReference(MessageImageOwner(conversation.value, user.id.value),
            "00000000-0000-0000-0000-000000000001", "image/png", 64, "a".repeat(64), 2, 2))
        val original = user.copy(blocks = user.blocks + image)
        assertNull(refresh(listOf(original, answer), listOf(original.copy(blocks = listOf(MessageBlock.Text("question", "changed"), image)), answer)))
        assertNull(refresh(listOf(original, answer), listOf(original.copy(blocks = user.blocks + image.copy(reference = image.reference.copy(sha256 = "b".repeat(64)))), answer)))
    }
    @Test fun newUserRoundOrAdditionalPhysicalRowCannotBeSilentlyDiscarded() {
        assertNull(refresh(history, history + user.copy(id = MessageId("new-user"), runId = RunId("next-run"))))
        assertNull(refresh(history, history + answer.copy(id = MessageId("new-answer"))))
    }
    @Test fun sameSizeNewUserAndReorderingAreNotAssistantSettling() {
        assertNull(refresh(history, listOf(user, answer.copy(role = Message.USER_ROLE))))
        assertNull(refresh(history, history.reversed()))
    }
    @Test fun earlierHistoryAndDifferentRunAssistantChangesAreRejected() {
        val earlier = answer.copy(id = MessageId("earlier"), runId = RunId("earlier-run"))
        assertNull(refresh(listOf(earlier) + history, listOf(earlier.copy(error = "changed")) + history))
        val later = answer.copy(id = MessageId("later"), runId = RunId("later-run"))
        assertNull(refresh(history + later, history + later.copy(error = "changed")))
    }
    @Test fun embeddedSupplementContentAndSegmentTopologyRemainProtected() {
        val assistantSegment = MessageHistorySegment(answer.id, Message.ASSISTANT_ROLE, answer.blocks)
        val supplement = MessageHistorySegment(MessageId("supplement"), Message.USER_ROLE,
            listOf(MessageBlock.Text("supplement", "original supplement")))
        val segmented = answer.copy(historySegments = listOf(assistantSegment, supplement))
        val changedSupplement = supplement.copy(blocks = listOf(MessageBlock.Text("supplement", "changed")))
        val changed = segmented.copy(historySegments = listOf(assistantSegment, changedSupplement))
        val settledSegment = assistantSegment.copy(blocks = listOf(MessageBlock.Text("answer", "settled")))
        val settled = segmented.copy(historySegments = listOf(settledSegment, supplement))
        assertNull(refresh(listOf(user, segmented), listOf(user, changed)))
        assertNull(refresh(history, listOf(user, segmented)))
        assertNotNull(refresh(listOf(user, segmented), listOf(user, settled)))
        val reordered = segmented.copy(historySegments = listOf(supplement, assistantSegment))
        val renamed = segmented.copy(historySegments = listOf(
            assistantSegment.copy(messageId = MessageId("different-segment")), supplement))
        val changedProtocol = segmented.copy(historySegments = listOf(
            assistantSegment, supplement.copy(protocolItems = listOf("changed protocol"))))
        assertNull(refresh(listOf(user, segmented), listOf(user, reordered)))
        assertNull(refresh(listOf(user, segmented), listOf(user, renamed)))
        assertNull(refresh(listOf(user, segmented), listOf(user, changedProtocol)))
    }
    @Test fun aRunningCompanionNeverReceivesANewEditCapability() {
        assertNull(refresh(history, listOf(user, answer.copy(status = RunStatus.RUNNING, error = null))))
    }
    @Test fun unchangedRunningAndPreviouslyRunningSnapshotsNeverReauthorize() {
        val running = listOf(user, answer.copy(status = RunStatus.RUNNING, error = null))
        assertNull(refresh(running, running.map { it.copy() }))
        assertNull(refresh(running, history))
    }
    @Test fun changedBindingAndForgedIdentityCannotRebase() {
        val original = snapshot(history)
        val target = original.targets.getValue("user")
        val updated = snapshot(listOf(user, answer.copy(error = "settled")))
        assertNull(snapshot(history, "different-assistant").refreshedEditTarget("key", target, original))
        assertNull(updated.refreshedEditTarget("key", target.copy(ownerId = answer.id), original))
        assertNull(updated.refreshedEditTarget("key", target.copy(segmentIndex = 1), original))
        assertNull(updated.refreshedEditTarget("missing", target, original))
        assertNull(updated.refreshedEditTarget("key", target.copy(branchPrefixSize = 2), original))
    }
    @Test fun missingBaselineAndRevisionInconsistentSnapshotFailClosed() {
        val original = snapshot(history)
        val target = original.targets.getValue("user")
        val updated = snapshot(listOf(user, answer.copy(error = "settled")))
        assertNull(updated.copy(editBaseline = null).refreshedEditTarget("key", target, original))
        assertNull(original.copy(editBaseline = null).refreshedEditTarget("key", target, original))
        assertNull(original.refreshedEditTarget("key", target, original.copy(editBaseline = null)))
        assertNull(updated.copy(revision = "wrong").refreshedEditTarget("key", target, original))
    }
}
