package app.daiyu.android

import app.daiyu.android.model.GptSpeedMode
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.storage.EditingSettings
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ProviderEditorSlot
import org.junit.Assert.*
import org.junit.Test

class ChatGptSpeedSelectionTest {
    private fun selection(mode: GptSpeedMode? = null, model: String = "gpt-5", body: String = "", codex: Boolean = false,
        protocol: ProviderProtocol = ProviderProtocol.RESPONSES) = ChatGptSpeedSelection(ProviderEditorSlot("provider", 1),
        EditingSettings(ModelSettings("https://example.invalid", model, "fixture", false, protocol = protocol,
            requestOptions = ProviderRequestOptions(speedMode = mode, customBody = body, codex = codex)), false))

    @Test fun cyclesThreeModesAndLeavesNullUnchangedUntilAnAction() {
        val original = selection()
        assertEquals(GptSpeedMode.NORMAL, original.mode)
        assertNull(original.editing.settings.requestOptions.speedMode)
        var current = original
        listOf(GptSpeedMode.FAST, GptSpeedMode.ULTRA_FAST, GptSpeedMode.NORMAL).forEach { expected ->
            val next = current.next()
            assertEquals(expected, next)
            current = current.copy(editing = current.editing.copy(settings = current.editing.settings.copy(
                requestOptions = current.editing.settings.requestOptions.copy(speedMode = next))))
        }
    }

    @Test fun modelMatchUsesTheFinalMergedModelAndExistingProtocolBoundary() {
        assertEquals(GptSpeedMode.NORMAL, selection(model = "vendor/nested/GPT-5").mode)
        assertNull(selection(model = "foo-gpt-5").mode)
        assertNull(selection(body = "{\"model\":\"claude-sonnet\"}").mode)
        assertEquals(GptSpeedMode.NORMAL, selection(model = "other", body = "{\"model\":\"gpt-5\"}").mode)
        assertNull(selection(body = "{\"model\":null}").mode)
        assertNull(selection(body = "not json").mode)
        assertNull(selection(protocol = ProviderProtocol.CHAT_COMPLETIONS).mode)
    }

    @Test fun unavailableConfigurationNeverAdvertisesSpeed() {
        val original = selection()
        listOf(original.editing.copy(credentialUnavailable = true), original.editing.copy(requestOptionsUnavailable = true),
            original.editing.copy(headersUnavailable = true)).forEach { assertNull(original.copy(editing = it).mode) }
    }

    @Test fun codexKeepsExistingUltraFastRejection() {
        assertEquals(GptSpeedMode.FAST, selection(codex = true).next())
        try { selection(mode = GptSpeedMode.FAST, codex = true).next(); fail("Codex ultra-fast accepted") }
        catch (_: IllegalStateException) { }
    }

    @Test fun speedDoesNotAlterReasoningOrOtherRequestOptions() {
        val original = selection(body = "{\"reasoning\":{\"effort\":\"high\"},\"service_tier\":\"custom\"}")
        val changed = original.editing.settings.requestOptions.copy(speedMode = original.next())
        assertEquals(original.editing.settings.requestOptions.customBody, changed.customBody)
        assertEquals(original.editing.settings.requestOptions.copy(speedMode = GptSpeedMode.FAST), changed)
    }

    @Test fun successMessagesMatchTheRequestedTextExactly() {
        assertEquals("已切换标准速度", chatGptSpeedToast(GptSpeedMode.NORMAL))
        assertEquals("已切换快速速度", chatGptSpeedToast(GptSpeedMode.FAST))
        assertEquals("已切换极速速度", chatGptSpeedToast(GptSpeedMode.ULTRA_FAST))
    }
}
