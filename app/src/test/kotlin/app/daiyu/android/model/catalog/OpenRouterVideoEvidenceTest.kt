package app.daiyu.android.model.catalog

import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.videos.OpenRouterVideoCapability
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class OpenRouterVideoEvidenceTest {
    private val model = "fixture/exact-model"
    private val valid = """{"data":[{"id":"fixture/exact-model","architecture":{"input_modalities":["text","video"]}}]}"""
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        catch (_: RemoteModelException) { }
    }

    @Test fun strictFactsRequireExactOfficialSourceModelAndVideo() {
        val facts = parseOpenRouterVideoFacts(valid, OPENROUTER_MODELS_ENDPOINT, model)
        assertEquals(model, facts.model)
        assertEquals(setOf("text", "video"), facts.inputModalities)
        for (source in listOf("https://example.invalid/api/v1/models", "$OPENROUTER_MODELS_ENDPOINT/",
            "http://openrouter.ai/api/v1/models", "$OPENROUTER_MODELS_ENDPOINT?x=1")) {
            rejected { parseOpenRouterVideoFacts(valid, source, model) }
        }
        for (selected in listOf("", " ", " $model", "fixture/another-model")) {
            rejected { parseOpenRouterVideoFacts(valid, OPENROUTER_MODELS_ENDPOINT, selected) }
        }
        for (body in listOf(
            valid.replace("\"text\",\"video\"", "\"text\""),
            valid.replace("input_modalities", "output_modalities"),
            valid.replace("\"video\"", "\"Video\""),
            valid.replace("\"video\"", "true"),
            valid.replace("\"id\":\"$model\"", "\"id\":\" $model \""),
            """{"models":[{"id":"$model"}]}""",
            """{"data":[],"has_more":true}""",
            """{"data":[],"nextPageToken":"next"}""",
            """{"data":[{"id":"","architecture":{"input_modalities":["video"]}}]}""",
        )) rejected { parseOpenRouterVideoFacts(body, OPENROUTER_MODELS_ENDPOINT, model) }
    }

    @Test fun completeMetadataAcceptsUnfilteredCountsAndNonPaginationLinks() {
        for (metadata in listOf(
            """{"total_count":1}""",
            """{"total_count":1,"links":{"self":"$OPENROUTER_MODELS_ENDPOINT","prev":"https://openrouter.ai/api/v1/models?page=0"}}""",
            """{"total_count":1,"links":{"next":null}}""",
            """{"total_count":1,"links":{"next":""},"has_more":false,"nextPageToken":""}""",
            """{"links":{"self":"$OPENROUTER_MODELS_ENDPOINT","prev":null,"next":null}}""",
            """{"links":null}""",
        )) {
            val body = valid.dropLast(1) + "," + metadata.drop(1)
            val facts = parseOpenRouterVideoFacts(body, OPENROUTER_MODELS_ENDPOINT, model)
            assertEquals(model, facts.model)
            assertEquals(setOf("text", "video"), facts.inputModalities)
        }
        // Count all rows before selecting the exact model; unrelated models need not support video.
        val body = """{"data":[{"id":"fixture/other-model"},{"id":"$model","architecture":{"input_modalities":["video"]}}],"total_count":2,"links":{"next":null}}"""
        assertEquals(model, parseOpenRouterVideoFacts(body, OPENROUTER_MODELS_ENDPOINT, model).model)
    }

    @Test fun incompleteOrMalformedMetadataRejectsEvenWhenExactVideoModelIsPresent() {
        for (count in listOf("2", "0", "-1", "1.0", "1.5", "\"1\"", "true", "false", "null", "[]", "{}",
            "9223372036854775808")) {
            val body = valid.dropLast(1) + ",\"total_count\":$count}"
            rejected { parseOpenRouterVideoFacts(body, OPENROUTER_MODELS_ENDPOINT, model) }
        }
        for (metadata in listOf(
            """{"total_count":1,"links":{"next":"https://openrouter.ai/api/v1/models?page=2"}}""",
            """{"links":{"next":" "}}""",
            """{"links":{"next":true}}""",
            """{"links":{"next":0}}""",
            """{"links":{"next":{}}}""",
            """{"links":{"next":[]}}""",
            """{"links":[]}""",
            """{"links":""}""",
            """{"links":false}""",
            """{"total_count":1,"has_more":true}""",
            """{"total_count":1,"nextPageToken":"next"}""",
        )) {
            val body = valid.dropLast(1) + "," + metadata.drop(1)
            rejected { parseOpenRouterVideoFacts(body, OPENROUTER_MODELS_ENDPOINT, model) }
        }
    }

    @Test fun duplicateIdentitiesFailRegardlessOfOrderOrAgreement() {
        val supported = """{"id":"$model","architecture":{"input_modalities":["video"]}}"""
        val unsupported = """{"id":"$model","architecture":{"input_modalities":["text"]}}"""
        for (rows in listOf("$supported,$unsupported", "$unsupported,$supported", "$supported,$supported")) {
            rejected { parseOpenRouterVideoFacts("""{"data":[$rows]}""", OPENROUTER_MODELS_ENDPOINT, model) }
        }
    }

    private class Connection(val status: Int, body: String, url: String = OPENROUTER_MODELS_ENDPOINT) : HttpURLConnection(URL(url)) {
        val bytes = ByteArrayInputStream(body.toByteArray())
        var closed = false
        override fun getResponseCode() = status
        override fun getInputStream() = bytes
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() = Unit
    }

    @Test fun factsUseExistingBoundedCancelableNoRedirectTransportButCannotMintEvidence() {
        val connection = Connection(200, valid)
        val request = RemoteModelRequest(OPENROUTER_MODELS_ENDPOINT, "test-key", RemoteModelProtocol.OPENAI)
        val facts = RemoteModelFetcher { connection }.fetchOpenRouterVideoFacts(request, model)
        assertEquals(model, facts.model)
        assertEquals("GET", connection.requestMethod)
        assertEquals("Bearer test-key", connection.getRequestProperty("Authorization"))
        assertEquals(RemoteModelFetcher.DEFAULT_TIMEOUT_MILLIS, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.closed)
        // Facts have no conversion API to OpenRouterVideoEvidence; no production proof is faked.
        rejected { RemoteModelFetcher { Connection(302, valid) }.fetchOpenRouterVideoFacts(request, model) }
        rejected { RemoteModelFetcher { Connection(200, " ".repeat(2 * 1024 * 1024 + 1)) }
            .fetchOpenRouterVideoFacts(request, model) }
        try {
            RemoteModelFetcher { error("Must not connect") }.fetchOpenRouterVideoFacts(request, model) { true }
            fail("Expected cancellation")
        } catch (_: InterruptedException) { }
        rejected { RemoteModelFetcher { error("Must not connect") }.fetchOpenRouterVideoFacts(
            request.copy(baseUrl = "https://example.invalid/v1/models"), model) }
    }

    @Test fun savedMismatchAndRevisionMismatchRejectBeforeAnyNetwork() {
        val settings = ModelSettings(OpenRouterVideoCapability.ENDPOINT, model, "test-key", false)
        val snapshot = ProviderSnapshot.capture(settings)
        val service = ModelCatalogService({ settings }, RemoteModelFetcher { error("Test transport must not mint proof") })
        rejected { service.fetchSavedVideoEvidence(snapshot, 3, { 4 }) }
        val other = ModelCatalogService({ settings.copy(model = "other") })
        rejected { other.fetchSavedVideoEvidence(snapshot, 3, { 3 }) }
    }
}
