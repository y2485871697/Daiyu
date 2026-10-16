package app.daiyu.android.model.generation

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ImageGenerationClientTest {
    @Test fun reserveIsDurableBeforePostAndDuplicateSubmissionNeverPostsAgain() {
        val fixture = GenerationFixture()
        fixture.http.respond = {
            assertEquals(1, fixture.store.rows.size)
            assertEquals("POST", it.method)
            jsonResponse("{\"data\":[{\"b64_json\":\"AQID\"}]}")
        }
        val result = fixture.images.submit(generationRequest())
        assertTrue(result is Submission.Completed)
        // Simulate a fresh client with the same parent-owned ledger after restart.
        val reopened = GenerationFixture(store = fixture.store, http = fixture.http)
        assertEquals(result, reopened.images.submit(generationRequest()))
        assertEquals(1, fixture.http.requests.size)
    }

    @Test fun disconnectEmptyMalformedAndHttpFailuresDoNotChangeEndpointOrRetry() {
        val responders: List<(GenerationHttpRequest) -> GenerationHttpResponse> = listOf(
            { throw IOException("disconnect includes secret-key") },
            { jsonResponse("") },
            { jsonResponse("not json") },
            { jsonResponse("{\"data\":[]}") },
            { jsonResponse("{}", 307) },
            { jsonResponse("{}", 401) },
            { jsonResponse("{}", 404) },
            { jsonResponse("{}", 429) },
            { jsonResponse("{}", 503) },
        )
        responders.forEach { responder ->
            val fixture = GenerationFixture()
            fixture.http.respond = responder
            val first = fixture.images.submit(generationRequest())
            assertTrue(first is Submission.Unknown)
            assertFalse(first.toString().contains("secret-key"))
            fixture.images.submit(generationRequest())
            assertEquals(1, fixture.http.requests.size)
            assertEquals("https://api.example/v1/images/generations", fixture.http.requests.single().url)
        }
    }

    @Test fun validAndInvalidOutputSiblingsAreRetainedByIndex() {
        val fixture = GenerationFixture()
        fixture.http.respond = { jsonResponse("{\"data\":[{\"b64_json\":\"AQID\"},{\"url\":\"http://unsafe.example/a\"},{\"url\":\"https://cdn.example/b?sig=s\"}]}") }
        val result = fixture.images.submit(generationRequest()) as Submission.Completed
        assertEquals(listOf(0, 1, 2), result.outputs.map { it.index })
        assertTrue(result.outputs[0].source is GenerationSource.Inline)
        assertEquals(GenerationFailure.PROTOCOL, result.outputs[1].failure)
        assertTrue(result.outputs[2].source is GenerationSource.Remote)
        assertEquals(1, fixture.http.requests.size) // no automatic download
        assertTrue(fixture.assets.commits.isEmpty())
    }

    @Test fun batchPreflightsEveryChildAndDuplicateIdentitiesBeforeAnyPost() {
        val fixture = GenerationFixture()
        val invalid = fixture.images.submitBatch(listOf(generationRequest("a"),
            generationRequest("b", parameters = mapOf("seed" to "1"))))
        assertTrue(invalid.all { it.submission is Submission.NotExecuted })
        assertTrue(fixture.http.requests.isEmpty())
        assertEquals(0, fixture.store.reserveCalls)
        val duplicate = fixture.images.submitBatch(listOf(generationRequest("a"), generationRequest("a")))
        assertTrue(duplicate.all { it.submission == Submission.NotExecuted(GenerationFailure.INVALID_BATCH) })
        assertTrue(fixture.http.requests.isEmpty())
    }

    @Test fun batchChildFailureDoesNotEraseEarlierOrLaterSuccess() {
        val fixture = GenerationFixture()
        fixture.http.respond = {
            if (fixture.http.requests.size == 2) throw IOException("lost response")
            jsonResponse("{\"data\":[{\"b64_json\":\"AQID\"}]}")
        }
        val result = fixture.images.submitBatch(listOf(generationRequest("a"), generationRequest("b"), generationRequest("c")))
        assertEquals(listOf("a", "b", "c"), result.map { it.identity.itemId })
        assertTrue(result[0].submission is Submission.Completed)
        assertTrue(result[1].submission is Submission.Unknown)
        assertTrue(result[2].submission is Submission.Completed)
        assertEquals(3, fixture.store.rows.size)
        assertEquals(3, fixture.http.requests.size)
    }

    @Test fun identityConflictStoreFailureAndUnfinishedTombstoneNeverPost() {
        val fixture = GenerationFixture()
        fixture.images.submit(generationRequest())
        assertEquals(Submission.NotExecuted(GenerationFailure.IDENTITY_CONFLICT),
            fixture.images.submit(generationRequest(prompt = "different")))
        assertEquals(1, fixture.http.requests.size)
        val blocked = GenerationFixture()
        blocked.store.failReserve = true
        assertEquals(Submission.NotExecuted(GenerationFailure.STORE_UNAVAILABLE), blocked.images.submit(generationRequest()))
        assertTrue(blocked.http.requests.isEmpty())
        blocked.store.failReserve = false
        blocked.store.reserve(blocked.images.prepare(generationRequest()).attempt)
        assertEquals(Submission.Unknown(GenerationFailure.ALREADY_ATTEMPTED), blocked.images.submit(generationRequest()))
        assertTrue(blocked.http.requests.isEmpty())
    }

    @Test fun failedOutcomePersistencePreservesKnownResultButNeverReopensReservation() {
        val fixture = GenerationFixture()
        fixture.store.failFinish = true
        assertTrue(fixture.images.submit(generationRequest()) is Submission.Completed)
        assertEquals(Submission.Unknown(GenerationFailure.ALREADY_ATTEMPTED), fixture.images.submit(generationRequest()))
        assertEquals(1, fixture.http.requests.size)
    }
}
