package app.daiyu.android.model.videos

import app.daiyu.android.VideoDraftRequest
import app.daiyu.android.domain.*
import app.daiyu.android.media.video.*
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test

/** Pure state-machine fixtures. None of these tests mint runtime evidence or production grants. */
class VideoUploadApprovalTest {
    private val settings = ModelSettings(OpenRouterVideoCapability.ENDPOINT, "fixture/exact-model", "fixture-key", false)
    private val provider = ProviderSnapshot.capture(settings)
    private val owner = MessageVideoOwner("conversation", "old-user")
    private val reference = MessageVideoReference(owner, "11111111-1111-1111-1111-111111111111",
        "video/mp4", 4096, "ab".repeat(32), 1000, 64, 64)
    private val row = Message(MessageId(owner.messageId), ConversationId(owner.conversationId), RunId("old-run"),
        "user", listOf(MessageBlock.Text("body", "Original history text")), RunStatus.COMPLETED)

    private fun text(
        run: String = "run-1", actor: String = "account-1", revision: Long = 3, body: String = "Continue exactly",
        config: ProviderSnapshot = provider, configRevision: Long = 7,
        rows: List<Message> = listOf(row), refs: List<MessageVideoReference> = listOf(reference),
    ) = FrozenVideoApprovalRequest.forText("conversation", run, actor, revision, body,
        config, configRevision, rows, listOf(MessageVideoInput(owner, refs)))

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun exactPromptOnlyAndOneConsumptionNotFutureRun() {
        val gate = VideoApprovalGate()
        val request = text()
        val prompt = gate.offer(request)
        assertEquals(1, prompt.videoCount)
        assertEquals(4096L, prompt.totalBytes)
        assertTrue(prompt.disclosure.contains("工具续轮"))
        assertTrue(prompt.disclosure.contains("不授权"))
        rejected { gate.confirm(VideoApprovalGate.Prompt(request), request) }
        val ticket = gate.confirm(prompt, text())
        rejected { gate.confirm(prompt, request) }
        rejected { gate.consume(VideoApprovalGate.Ticket(), request) }
        rejected { gate.consume(ticket, text(run = "run-2")) }
        val consumed = gate.consume(ticket, request)
        assertTrue(gate.authorizes(consumed))
        assertFalse(gate.authorizes(VideoApprovalGate.Consumption(request)))
        rejected { gate.consume(ticket, request) }
        gate.revoke()
        assertFalse(gate.authorizes(consumed))
    }

    @Test fun editsOwnerConfigAndFullHistoryAreBound() {
        val original = text()
        val changed = listOf(
            text(body = "Continue exactly "), text(revision = 4), text(actor = "account-2"),
            text(configRevision = 8), text(config = ProviderSnapshot.capture(settings)),
            text(config = ProviderSnapshot.capture(settings.copy(apiKey = "changed-key"))),
            text(rows = listOf(row.copy(blocks = listOf(MessageBlock.Text("body", "Changed history"))))),
            text(refs = listOf(reference.copy(sha256 = "cd".repeat(32)))),
            text(refs = listOf(reference.copy(bytes = 8192))),
        )
        changed.forEach { current ->
            val gate = VideoApprovalGate()
            val prompt = gate.offer(original)
            rejected { gate.confirm(prompt, current) }
            val ticket = gate.confirm(prompt, original)
            rejected { gate.consume(ticket, current) }
        }
    }

    @Test fun revokedOrSupersededPromptsAndTicketsFail() {
        val gate = VideoApprovalGate()
        val request = text()
        val oldPrompt = gate.offer(request)
        val oldTicket = gate.confirm(oldPrompt, request)
        gate.revoke()
        rejected { gate.consume(oldTicket, request) }
        val nextPrompt = gate.offer(request)
        rejected { gate.confirm(oldPrompt, request) }
        val nextTicket = gate.confirm(nextPrompt, request)
        gate.offer(text(run = "another-run"))
        rejected { gate.consume(nextTicket, request) }
    }

    @Test fun draftOriginalAndEveryHistoryVideoAreDisclosedTogether() {
        val draftOwner = VideoAttachmentOwner("conversation", "draft-1")
        val original = VideoAttachment(draftOwner, "22222222-2222-2222-2222-222222222222",
            "video/mp4", 2048, "cd".repeat(32), 2000, 64, 64)
        val draft = VideoDraftRequest("conversation", draftOwner, 8, "Untrimmed draft  ", listOf(original))
        val frozen = FrozenVideoApprovalRequest.forDraft(draft, "run-1", "account-1", provider, 7,
            listOf(row), listOf(MessageVideoInput(owner, listOf(reference))))
        val gate = VideoApprovalGate()
        val prompt = gate.offer(frozen)
        assertEquals(2, prompt.videoCount)
        assertEquals(6144L, prompt.totalBytes)
        assertEquals(listOf(reference, original), prompt.inventory)
        val recreatedDraft = VideoDraftRequest("conversation", draftOwner, 8, draft.text, listOf(original))
        val notTheHandoff = FrozenVideoApprovalRequest.forDraft(recreatedDraft, "run-1", "account-1", provider, 7,
            listOf(row), listOf(MessageVideoInput(owner, listOf(reference))))
        rejected { gate.confirm(prompt, notTheHandoff) }
        rejected { (prompt.inventory as MutableList<VideoReference>).clear() }
    }

    @Test fun noEmptyHistoryVideoApprovalOrUnsupportedOriginal() {
        rejected { FrozenVideoApprovalRequest.forText("conversation", "run", "actor", 0, "hello",
            provider, 7, emptyList(), emptyList()) }
        rejected { text(refs = listOf(reference.copy(mime = "video/webm"))) }
        rejected { text(refs = listOf(reference.copy(bytes = Long.MAX_VALUE))) }
    }
}
