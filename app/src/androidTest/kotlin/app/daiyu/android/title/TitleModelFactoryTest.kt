package app.daiyu.android.title

import android.test.InstrumentationTestCase
import app.daiyu.android.model.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray

/** Validates native prompt/budget wire composition with existing adapters; never next()/network. */
@Suppress("DEPRECATION")
class TitleModelFactoryTest : InstrumentationTestCase() {
    fun testResponsesBindingBudgetAndNativeInstructionsUseExistingFactory() {
        val frozen = ModelSettings("https://title.invalid", "fixed-title", "synthetic-key", false).requestSnapshot()
        val request = TitleModelCoordinator.requestSnapshot(frozen)
        assertEquals(frozen.model, request.model)
        assertEquals(frozen.apiKey, request.apiKey)
        val body = ResponsesRequest.withAssistantInstructions(request.requestOptions.apply(
            ResponsesRequest.body(request.model, JSONArray(), emptyList()), request.protocol), ConversationTitleModel.INSTRUCTIONS)
        assertEquals("fixed-title", body.getString("model"))
        assertEquals(512, body.getInt("max_output_tokens"))
        assertEquals(ConversationTitleModel.INSTRUCTIONS, body.getString("instructions"))
        assertFalse(body.has("tools"))
        assertTrue(ModelFactory.create(request, ConversationTitleModel.INSTRUCTIONS) is ResponsesModel)
    }
    fun testMessagesBindingBudgetAndNativeSystemUseExistingFactory() {
        val frozen = ModelSettings("https://title.invalid", "fixed-title", "synthetic-key", false,
            protocol = ProviderProtocol.MESSAGES).requestSnapshot()
        val request = TitleModelCoordinator.requestSnapshot(frozen)
        val body = MessagesRequest.withAssistantInstructions(request.requestOptions.apply(
            MessagesRequest.body(request.model, JSONArray(), emptyList(), 4096), request.protocol), ConversationTitleModel.INSTRUCTIONS)
        assertEquals("fixed-title", body.getString("model"))
        assertEquals(512, body.getInt("max_tokens"))
        assertEquals(ConversationTitleModel.INSTRUCTIONS, body.getString("system"))
        assertFalse(body.has("tools"))
        assertTrue(ModelFactory.create(request, ConversationTitleModel.INSTRUCTIONS) is MessagesModel)
    }
}
