package app.daiyu.android.model.generation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GenerationRequestPlanTest {
    @Test fun jsonFreezesExactlyOneEndpointAndWhitelistedFields() {
        val plan = GenerationRequestPlan.prepare(generationRequest(), TestGenerationAssets())
        assertEquals("https://api.example/v1/images/generations", plan.endpoint)
        val json = JSONObject(plan.body.toString(Charsets.UTF_8))
        assertEquals(setOf("model", "prompt", "n"), json.keys().asSequence().toSet())
        assertEquals(1, json.getInt("n"))
        assertEquals("private-prompt", json.getString("prompt"))
        assertEquals(plan.fingerprint, GenerationRequestPlan.prepare(generationRequest(), TestGenerationAssets()).fingerprint)
    }

    @Test fun missingHelperOptionsNeverDisappearSilently() {
        for (parameter in listOf("seed", "size", "quality", "aspect_ratio", "duration", "n", "count", "concurrency", "extra_body", "internal_image_config")) {
            assertFailure(GenerationFailure.UNSUPPORTED_PARAMETER, generationRequest(parameters = mapOf(parameter to "1")))
        }
    }

    @Test fun rejectsAmbiguousEndpointsInsteadOfChangingTransport() {
        for (endpoint in listOf("http://api.example/v1/images/generations", "https://u:p@api.example/v1/images/generations",
            "https://api.example/v1/images/generations?token=x", "https://api.example/v1/images/generations#x",
            "https://api.example/v1/chat/completions", "https://api.example/v1/images/generations/",
            "https://api.example/a/../images/generations", "https://api.example/v1/%69mages/generations")) {
            assertFailure(GenerationFailure.INVALID_REQUEST, generationRequest(endpoint = endpoint))
        }
    }

    @Test fun editsSendOneValidatedReferenceAndMaskAsSeparateParts() {
        val input = GenerationInput(byteArrayOf(1, 2, 3), "image/png")
        val request = generationRequest(protocol = GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART, refs = listOf(input), mask = input)
        val assets = TestGenerationAssets()
        val plan = GenerationRequestPlan.prepare(request, assets)
        assertEquals(2, assets.inspections)
        val body = plan.body.toString(Charsets.UTF_8)
        assertTrue(body.contains("name=\"image\"; filename=\"reference.png\""))
        assertTrue(body.contains("name=\"mask\"; filename=\"mask.png\""))
        assertTrue(body.contains("name=\"n\"\r\n\r\n1\r\n"))
        assertEquals(plan.fingerprint, GenerationRequestPlan.prepare(request, assets).fingerprint)
    }

    @Test fun referenceAndMaskErrorsArePreflightFailures() {
        val png = GenerationInput(byteArrayOf(1), "image/png")
        val jpeg = GenerationInput(byteArrayOf(1), "image/jpeg")
        assertFailure(GenerationFailure.UNSUPPORTED_REFERENCE, generationRequest(refs = listOf(png)))
        assertFailure(GenerationFailure.UNSUPPORTED_REFERENCE, generationRequest(mask = png))
        assertFailure(GenerationFailure.UNSUPPORTED_REFERENCE, generationRequest(protocol = GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART))
        assertFailure(GenerationFailure.UNSUPPORTED_REFERENCE, generationRequest(protocol = GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART, refs = listOf(png, png)))
        assertFailure(GenerationFailure.INVALID_MEDIA, generationRequest(protocol = GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART, refs = listOf(jpeg)))
        assertFailure(GenerationFailure.INVALID_MEDIA, generationRequest(protocol = GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART,
            refs = listOf(png), mask = GenerationInput(byteArrayOf(99), "image/png")))
        assertFailure(GenerationFailure.INVALID_MEDIA, generationRequest(protocol = GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART,
            refs = listOf(GenerationInput(byteArrayOf(), "image/png"))))
        assertFailure(GenerationFailure.INVALID_MEDIA, generationRequest(protocol = GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART,
            refs = listOf(GenerationInput(ByteArray(GenerationRequestPlan.MAX_INPUT_BYTES + 1), "image/png"))))
    }

    @Test fun videosDoNotDiscardAdditionalReferencesOrMasks() {
        val png = GenerationInput(byteArrayOf(1), "image/png")
        assertFailure(GenerationFailure.UNSUPPORTED_REFERENCE, generationRequest(protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART, refs = listOf(png, png)))
        assertFailure(GenerationFailure.UNSUPPORTED_REFERENCE, generationRequest(protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART, mask = png))
        val plan = GenerationRequestPlan.prepare(generationRequest(protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART, refs = listOf(png)), TestGenerationAssets())
        val body = plan.body.toString(Charsets.UTF_8)
        assertTrue(body.contains("name=\"input_reference\""))
        assertFalse(body.contains("name=\"n\""))
        assertFalse(body.contains("name=\"image_url\""))
    }

    @Test fun parentValidationFailureStopsBeforeAnyNetworkOrAccounting() {
        val fixture = GenerationFixture()
        fixture.assets.reject = true
        val result = fixture.images.submit(generationRequest(protocol = GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART,
            refs = listOf(GenerationInput(byteArrayOf(1), "image/png"))))
        assertEquals(Submission.NotExecuted(GenerationFailure.INVALID_MEDIA), result)
        assertTrue(fixture.http.requests.isEmpty())
        assertEquals(0, fixture.store.reserveCalls)
    }

    private fun assertFailure(expected: GenerationFailure, request: GenerationRequest) {
        try {
            GenerationRequestPlan.prepare(request, TestGenerationAssets())
            fail("Expected preflight failure")
        } catch (failure: GenerationPreflightException) { assertEquals(expected, failure.failure) }
    }
}
