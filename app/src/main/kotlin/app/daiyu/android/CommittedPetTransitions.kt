package app.daiyu.android

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.domain.pet.PetCancel
import app.daiyu.android.pet.PetAssistantTransition
import app.daiyu.android.pet.PetAssistantTransitionSource

/** Only the runner's successful post-commit callback may publish here. No history replay. */
internal class CommittedPetTransitions : PetAssistantTransitionSource {
    private val lock = Any()
    private val listeners = linkedSetOf<(PetAssistantTransition) -> Unit>()
    override fun subscribe(listener: (PetAssistantTransition) -> Unit): PetCancel {
        synchronized(lock) { listeners.add(listener) }
        return PetCancel { synchronized(lock) { listeners.remove(listener) }; Unit }
    }
    fun afterCommit(before: Message, after: Message, receiptTitle: String) {
        if (before.status != RunStatus.RUNNING || after.status != RunStatus.COMPLETED || after.error != null ||
            before.id != after.id || before.runId != after.runId || before.conversationId != after.conversationId ||
            after.role != Message.ASSISTANT_ROLE) return
        val recipients = synchronized(lock) { listeners.toList() }
        val event = PetAssistantTransition(before, after, receiptTitle)
        recipients.forEach { runCatching { it(event) } }
    }
}
