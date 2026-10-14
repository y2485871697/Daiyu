package app.daiyu.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompressionSendPolicyTest {
    @Test fun activeJobOnlyBlocksItsOwnConversation() {
        assertTrue(blocked("a", jobOwner = "a", jobActive = true))
        assertFalse(blocked("b", jobOwner = "a", jobActive = true))
    }

    @Test fun activeJobDoesNotBlockNewDraft() {
        assertFalse(blocked(null, jobOwner = "a", jobActive = true))
    }

    @Test fun draftJobDoesNotBlockSavedConversations() {
        assertTrue(blocked(null, jobOwner = null, jobActive = true))
        assertFalse(blocked("a", jobOwner = null, jobActive = true))
    }

    @Test fun completedJobOwnerDoesNotKeepSendBlocked() {
        assertFalse(blocked("a", jobOwner = "a", jobActive = false))
        assertFalse(blocked(null, jobOwner = null, jobActive = false))
    }

    @Test fun currentConversationFlagBlocksWithoutSharedJob() {
        assertTrue(blocked("a", compressing = true))
        assertTrue(blocked(null, compressing = true))
    }

    @Test fun currentFlagStillBlocksWhenQueueTailBelongsToAnotherConversation() {
        assertTrue(blocked("a", compressing = true, jobOwner = "b", jobActive = true))
    }

    @Test fun pendingRequestOnlyBlocksItsOwnConversation() {
        assertTrue(blocked("a", pendingOwner = "a", hasPending = true))
        assertFalse(blocked("b", pendingOwner = "a", hasPending = true))
        assertFalse(blocked(null, pendingOwner = "a", hasPending = true))
    }

    @Test fun pendingDraftIsNotAWildcard() {
        assertTrue(blocked(null, pendingOwner = null, hasPending = true))
        assertFalse(blocked("b", pendingOwner = null, hasPending = true))
    }

    @Test fun absentPendingDoesNotBlockEvenIfOwnerMatches() {
        assertFalse(blocked("a", pendingOwner = "a", hasPending = false))
        assertFalse(blocked(null, pendingOwner = null, hasPending = false))
    }

    @Test fun activeAndPendingOwnersDoNotBlockThirdConversation() {
        for (owner in listOf("a", "b", "c", null)) {
            assertEquals(owner == "a" || owner == "b",
                blocked(owner, jobOwner = "a", jobActive = true,
                    pendingOwner = "b", hasPending = true))
        }
    }

    @Test fun switchingAwayAndBackPreservesOwnRestriction() {
        val selections = listOf("a", "b", null, "a")
        assertEquals(listOf(true, false, false, true), selections.map {
            blocked(it, jobOwner = "a", jobActive = true)
        })
    }

    @Test fun finishingCompressionOnlyReleasesItsOwnRestriction() {
        assertTrue(blocked("a", compressing = true, jobOwner = "a", jobActive = true))
        assertFalse(blocked("a", jobOwner = "a", jobActive = false))
        assertFalse(blocked("b", jobOwner = "a", jobActive = false))
        assertTrue(blocked("b", compressing = true, jobOwner = "a", jobActive = false))
    }

    private fun blocked(
        selected: String?,
        compressing: Boolean = false,
        jobOwner: String? = null,
        jobActive: Boolean = false,
        pendingOwner: String? = null,
        hasPending: Boolean = false,
    ): Boolean = conversationCompressionBlocksSend(
        conversationId = selected,
        isCompressingContext = compressing,
        jobActive = jobActive,
        jobConversationId = jobOwner,
        hasPending = hasPending,
        pendingConversationId = pendingOwner,
    )
}
