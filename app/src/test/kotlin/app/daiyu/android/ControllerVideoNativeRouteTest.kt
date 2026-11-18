package app.daiyu.android

import app.daiyu.android.domain.*
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.model.ReasoningRequestPolicy
import app.daiyu.android.model.videos.OpenRouterVideoCapability
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test

class ControllerVideoNativeRouteTest {
    private val settings = ModelSettings(OpenRouterVideoCapability.ENDPOINT, "fixture/exact", "fixture", false)
    private fun video(mime: String = "video/mp4", bytes: Long = 1024) =
        MessageVideoReference(MessageVideoOwner("c", "m"), "video", mime, bytes, "a".repeat(64), 4031, 1080, 2344)

    @Test fun nativeProbeRequiresImplementedEndpointAndProtocolNotAModelName() {
        assertTrue(canProbeNativeVideo(ProviderSnapshot.capture(settings), listOf(video())))
        assertFalse(canProbeNativeVideo(ProviderSnapshot.capture(settings.copy(endpoint = "https://example.invalid/v1/responses")), listOf(video())))
        assertFalse(canProbeNativeVideo(ProviderSnapshot.capture(settings.copy(protocol = ProviderProtocol.CHAT_COMPLETIONS,
            endpoint = "https://openrouter.ai/api/v1/chat/completions")), listOf(video())))
    }

    @Test fun unsupportedMimeEmptyAndExpandedHistoryBudgetsChooseFileRouteBeforeAnyUpload() {
        val provider = ProviderSnapshot.capture(settings)
        assertFalse(canProbeNativeVideo(provider, emptyList()))
        assertFalse(canProbeNativeVideo(provider, listOf(video("video/webm"))))
        assertFalse(canProbeNativeVideo(provider, List(5) { video() }))
        assertFalse(canProbeNativeVideo(provider, listOf(video(bytes = 16L * 1024 * 1024 + 1))))
        assertFalse(canProbeNativeVideo(provider, List(2) { video(bytes = 9L * 1024 * 1024) }))
        assertTrue(canProbeNativeVideo(provider, listOf(video(bytes = 16L * 1024 * 1024))))
    }

    @Test fun highReasoningIsPreservedButRecipientOverridesStillCannotUseNativeVideo() {
        val provider = ReasoningRequestPolicy.freeze(ProviderSnapshot.capture(settings), ReasoningEffort.HIGH)
        assertTrue(canProbeNativeVideo(provider, listOf(video())))
        assertTrue(provider.requestOptions.customBody.contains("high"))
        assertFalse(canProbeNativeVideo(ProviderSnapshot.capture(settings.copy(requestOptions =
            ProviderRequestOptions("""{"provider":{"allow_fallbacks":true}}"""))), listOf(video())))
    }
    private fun unsupported(status: Int = 400, message: String = "Unsupported input_video content type") =
        ModelRequestException(ModelFailure.REQUEST, ModelRequestDiagnostic(httpStatus = status,
            providerDetail = ModelProviderDetail.sanitized(message = message)))

    @Test fun explicitFirstVideoRejectionUsesFileRouteOnceWithoutRepeatingTheUserMessage() {
        var nativeCalls = 0
        var cancelled = 0
        var fileOpens = 0
        var fileCalls = 0
        val rows = listOf(Message(MessageId("u"), ConversationId("c"), RunId("r"), "user", emptyList(), RunStatus.COMPLETED))
        val native = ModelPort { source ->
            assertEquals(rows, source)
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    nativeCalls++; sink(ModelEvent.InputEstimate(10)); throw unsupported()
                }
                override fun cancel() { cancelled++ }
            }
        }
        val files = ModelPort { source ->
            fileOpens++; assertEquals(rows, source)
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    assertTrue(outputs.isEmpty()); fileCalls++; sink(ModelEvent.TextFinal("answer", "from Linux frames"))
                }
                override fun cancel() { }
            }
        }
        val received = mutableListOf<ModelEvent>()
        val session = nativeVideoWithFileFallback(native) { files }.open(rows)
        session.next(emptyList(), received::add)
        assertEquals(1, nativeCalls); assertEquals(1, cancelled)
        assertEquals(1, fileOpens); assertEquals(1, fileCalls)
        assertEquals("from Linux frames", received.filterIsInstance<ModelEvent.TextFinal>().single().text)
        session.cancel()
    }

    @Test fun networkAuthenticationPartialOutputAndNonVideoRejectionsNeverChangeRoutes() {
        val errors = listOf(unsupported(422), unsupported(401), unsupported(400, "Invalid request"),
            unsupported(400, "Unsupported parameter 'temperature' for input_video requests"),
            ModelRequestException(ModelFailure.NETWORK), java.io.IOException("unknown transport outcome"))
        errors.forEachIndexed { index, failure ->
            var fallbacks = 0
            val native = ModelPort {
                object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        if (index == 0) sink(ModelEvent.TextDelta("partial", "already accepted"))
                        throw failure
                    }
                    override fun cancel() { }
                }
            }
            val session = nativeVideoWithFileFallback(native) { fallbacks++; error("must not open") }.open(emptyList())
            assertSame(failure, assertThrows(Exception::class.java) { session.next(emptyList()) {} })
            assertEquals(0, fallbacks)
            session.cancel()
        }
    }

    @Test fun submittedToolResultsCannotTriggerOriginalVideoFallback() {
        var fallbacks = 0
        val native = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { throw unsupported() }
            override fun cancel() { }
        } }
        val session = nativeVideoWithFileFallback(native) { fallbacks++; error("must not open") }.open(emptyList())
        assertThrows(ModelRequestException::class.java) {
            session.next(listOf(ToolOutput(ToolCallId("tool"), "already executed"))) {}
        }
        assertEquals(0, fallbacks)
        session.cancel()
    }

}
