package app.daiyu.android.ui.screens.chat.conversation

import org.junit.Assert.*
import org.junit.Test

class ChatSubmissionPreviewTest {
    private val user = ChatTurnUi.User("user", "original")
    private val answer = ChatTurnUi.Assistant("answer", finalText = "old answer")
    private val history = listOf(user, answer)

    @Test fun entranceIsClaimedOnlyOnceAcrossPreviewSettlementAndRemount() {
        val entrance = UserMessageEntrance("preview-ticket")
        assertEquals("preview-ticket", entrance.messageId)
        assertTrue(entrance.claim())
        entrance.messageId = "durable-user"
        assertEquals("durable-user", entrance.messageId)
        assertFalse(entrance.claim())
    }

    @Test fun equalTextOnDifferentSendsDoesNotShareEntranceAdmission() {
        val first = UserMessageEntrance("ticket-a")
        val second = UserMessageEntrance("ticket-b")
        assertTrue(first.claim())
        assertTrue(second.claim())
        assertFalse(first.claim())
        assertFalse(second.claim())
    }

    @Test fun entranceScalesUpWithASmallReboundAndExactRest() {
        assertEquals(.82f, userMessageEntranceScale(0f), .001f)
        assertTrue(userMessageEntranceScale(.6f) > 1f)
        assertTrue(userMessageEntranceScale(.6f) < 1.03f)
        assertEquals(1f, userMessageEntranceScale(1f), 0f)
    }

    @Test fun pendingSendAppearsWithoutMutatingHistoryOrInventingReceipt() {
        val preview = ChatSubmissionPreview("ticket-a", "  exact\ntext  ")
        val displayed = submissionTurns(history, preview)
        assertEquals(history, displayed.take(2))
        val pending = displayed.last() as ChatTurnUi.User
        assertEquals(preview.key, pending.listKey)
        assertEquals(preview.text, pending.text)
        assertTrue(pending.pending)
        assertEquals("", pending.timestamp)
        assertEquals(2, history.size)
    }

    @Test fun mediaOnlySendReservesAPendingUserWithoutInventingAssetOwnership() {
        val preview = ChatSubmissionPreview("media-ticket")
        val pending = submissionTurns(history, preview).last() as ChatTurnUi.User
        assertEquals("media-ticket", pending.listKey)
        assertEquals("", pending.text)
        assertTrue(pending.pending)
        assertTrue(pending.content.isEmpty())
        assertEquals("", pending.timestamp)
        assertSame(history, submissionTurns(history, null))
    }

    @Test fun mediaOnlyPendingStaysBeforeTheAcceptedAssistantUntilItsUserArrives() {
        val preview = ChatSubmissionPreview("media-ticket", settledUserId = "media-user", settledAssistantId = "media-answer")
        val assistantOnly = history + ChatTurnUi.Assistant("media-answer")
        assertEquals(listOf("user", "answer", "media-ticket", "media-answer"),
            submissionTurns(assistantOnly, preview).map { it.id })
        val complete = history + ChatTurnUi.User("media-user", "", listKey = "encoded-media-segment") +
            ChatTurnUi.Assistant("media-answer")
        assertSame(complete, submissionTurns(complete, preview))
    }

    @Test fun cancellationOrRejectionRestoresOriginalListIdentity() {
        assertEquals(3, submissionTurns(history, ChatSubmissionPreview("ticket", "send")).size)
        assertSame(history, submissionTurns(history, null))
    }

    @Test fun equalTextInHistoryDoesNotAcknowledgeTheNewSubmission() {
        val result = submissionTurns(history, ChatSubmissionPreview("new-ticket", "original"))
        assertEquals(3, result.size)
        assertEquals("new-ticket", result.last().id)
    }

    @Test fun confirmedReceiptRetiresOnlyWhenExactAssistantIsActuallyPublished() {
        val preview = ChatSubmissionPreview("ticket", "new", settledAssistantId = "new-answer")
        assertTrue((submissionTurns(history, preview).last() as ChatTurnUi.User).pending)
        val committed = history + ChatTurnUi.User("new-user", "new") + ChatTurnUi.Assistant("new-answer")
        assertSame(committed, submissionTurns(committed, preview))
    }

    @Test fun assistantOnlyRefreshCannotRetirePendingUserBeforeItsRealRowArrives() {
        val preview = ChatSubmissionPreview("ticket", "new", settledAssistantId = "new-answer", settledUserId = "new-user")
        val assistantOnly = history + ChatTurnUi.Assistant("new-answer")
        assertEquals(listOf("user", "answer", "ticket", "new-answer"), submissionTurns(assistantOnly, preview).map { it.id })
        val complete = history + ChatTurnUi.User("new-user", "new") + ChatTurnUi.Assistant("new-answer")
        assertSame(complete, submissionTurns(complete, preview))
    }

    @Test fun realUserRowRetiresPendingCopyEvenBeforeAssistantProjectionCatchesUp() {
        val preview = ChatSubmissionPreview("ticket", "new", settledAssistantId = "new-answer", settledUserId = "new-user")
        val rows = history + ChatTurnUi.User("new-user", "new")
        assertSame(rows, submissionTurns(rows, preview))
    }

    @Test fun regenerationHidesOnlySelectedReplyAndTailUntilReceiptOrRollback() {
        val later = history + ChatTurnUi.User("later", "later") + ChatTurnUi.Assistant("later-answer")
        val preview = ChatSubmissionPreview("regeneration", replaceFromKey = answer.listKey)
        assertEquals(listOf(user), submissionTurns(later, preview))
        assertSame(later, submissionTurns(later, null))
        assertSame(later, submissionTurns(later, preview.copy(replaceFromKey = "wrong-owner")))
    }

    @Test fun regenerationHandlesExactProjectedSegmentAndDoesNotSubstituteOtherOwner() {
        val segmented = answer.copy(listKey = "answer:0", sourceListKeys = listOf("answer:0", "answer:1"))
        val rows = listOf(user, segmented)
        assertEquals(listOf(user), submissionTurns(rows,
            ChatSubmissionPreview("regeneration", replaceFromKey = "answer:1")))
        assertSame(rows, submissionTurns(rows,
            ChatSubmissionPreview("regeneration", replaceFromKey = "another:1")))
    }

    @Test fun acceptedRegenerationNeverFlashesTheOldReplyWhileWindowCatchesUp() {
        val preview = ChatSubmissionPreview("regeneration", replaceFromKey = answer.listKey,
            settledAssistantId = "new-answer")
        assertEquals(listOf(user), submissionTurns(history, preview))
        val committed = listOf(user, ChatTurnUi.Assistant("new-answer"))
        assertSame(committed, submissionTurns(committed, preview))
    }
}
