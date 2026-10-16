package app.daiyu.android.model.generation

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class VideoGenerationClientTest {
    @Test fun pendingPollAndContentAreExplicitReadOnlyOperations() {
        val fixture = GenerationFixture()
        fixture.http.respond = { request -> when (request.method) {
            "POST" -> jsonResponse("{\"id\":\"video_123\",\"status\":\"queued\"}")
            else -> if (request.url.endsWith("/content")) GenerationHttpResponse(200, byteArrayOf(1, 2), "video/mp4")
                else jsonResponse("{\"id\":\"video_123\",\"status\":\"completed\"}")
        } }
        val plan = fixture.videos.prepare(generationRequest(protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART))
        val pending = fixture.videos.submit(plan) as Submission.Pending
        assertEquals(1, fixture.http.requests.size)
        val done = fixture.videos.poll(plan, pending.handle) as Submission.Completed
        assertEquals("https://api.example/v1/videos/video_123", fixture.http.requests.last().url)
        assertTrue(fixture.assets.commits.isEmpty())
        val asset = fixture.io.downloadAndCommit(plan, done.outputs.single())
        assertTrue(asset is GenerationAssetResult.Committed)
        assertEquals(listOf("POST", "GET", "GET"), fixture.http.requests.map { it.method })
        assertEquals("https://api.example/v1/videos/video_123/content", fixture.http.requests.last().url)
        assertEquals("Bearer secret-key", fixture.http.requests.last().headers["Authorization"])
        assertEquals(listOf(plan.identity), fixture.assets.commits)
        assertTrue(fixture.videos.submit(plan) is Submission.Completed)
        assertEquals(3, fixture.http.requests.size)
    }

    @Test fun completedVideoDeniedByParentNeverDownloadsCommitsOrResubmits() {
        val authorizedUrls = mutableListOf<String>()
        val fixture = GenerationFixture(allowed = { url -> authorizedUrls += url; false })
        fixture.http.respond = { request -> when (request.method) {
            "POST" -> jsonResponse("{\"id\":\"video_123\",\"status\":\"queued\"}")
            else -> jsonResponse("{\"id\":\"video_123\",\"status\":\"completed\"}")
        } }
        val plan = fixture.videos.prepare(generationRequest(protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART))
        val pending = fixture.videos.submit(plan) as Submission.Pending
        val done = fixture.videos.poll(plan, pending.handle) as Submission.Completed
        val contentUrl = plan.endpoint + "/video_123/content"
        val source = done.outputs.single().source as GenerationSource.Remote
        assertEquals(contentUrl, source.url)
        assertTrue(source.authenticated)
        assertTrue(authorizedUrls.isEmpty())

        assertEquals(GenerationAssetResult.Failed(GenerationFailure.DOWNLOAD_DENIED),
            fixture.io.downloadAndCommit(plan, done.outputs.single()))
        assertEquals(listOf(contentUrl), authorizedUrls)
        assertEquals(0, fixture.http.requests.count { it.method == "GET" && it.url == contentUrl })
        assertEquals(0, fixture.assets.inspections)
        assertTrue(fixture.assets.commits.isEmpty())
        assertEquals(plan.attempt, fixture.store.rows[plan.identity])
        assertEquals(done, fixture.store.results[plan.identity])
        assertSame(source, done.outputs.single().source)
        assertEquals(done, fixture.videos.submit(plan))
        assertEquals(1, fixture.http.requests.count { it.method == "POST" })
        assertEquals(listOf("POST", "GET"), fixture.http.requests.map { it.method })
    }

    @Test fun parentAuthorizationDoesNotReplaceAuthenticatedContentUrlChecks() {
        val authorizedUrls = mutableListOf<String>()
        val fixture = GenerationFixture(allowed = { url -> authorizedUrls += url; true })
        val plan = fixture.videos.prepare(generationRequest(protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART))
        val invalidUrls = listOf(
            "https://other.example/v1/videos/video_123/content",
            plan.endpoint + ".other.example/video_123/content",
            plan.endpoint + "/video_123/other/content",
            plan.endpoint + "/video_123/content?extra=1",
        )
        for (url in invalidUrls) {
            val output = GenerationOutput(0, GenerationSource.Remote(url, authenticated = true))
            assertEquals(GenerationAssetResult.Failed(GenerationFailure.DOWNLOAD_DENIED),
                fixture.io.downloadAndCommit(plan, output))
        }
        assertEquals(invalidUrls, authorizedUrls)
        assertTrue(fixture.http.requests.isEmpty())
        assertEquals(0, fixture.assets.inspections)
        assertTrue(fixture.assets.commits.isEmpty())
    }

    @Test fun pollNetworkAndParseFailuresKeepRecoverableHandleAndNeverResubmit() {
        val fixture = GenerationFixture()
        fixture.http.respond = { jsonResponse("{\"id\":\"job1\",\"status\":\"in_progress\"}") }
        val plan = fixture.videos.prepare(generationRequest(protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART))
        val initial = fixture.videos.submit(plan) as Submission.Pending
        fixture.http.respond = { throw IOException("connection lost") }
        val disconnected = fixture.videos.poll(plan, initial.handle) as Submission.Unknown
        assertEquals(initial.handle, disconnected.handle)
        // Reservation remembers the pending handle, not a transient read failure.
        assertEquals(initial, fixture.videos.submit(plan))
        fixture.http.respond = { jsonResponse("{broken") }
        assertEquals(initial.handle, (fixture.videos.poll(plan, initial.handle) as Submission.Unknown).handle)
        fixture.http.respond = { jsonResponse("{\"id\":\"different\",\"status\":\"completed\"}") }
        assertTrue(fixture.videos.poll(plan, initial.handle) is Submission.Unknown)
        assertEquals(1, fixture.http.requests.count { it.method == "POST" })
        assertEquals(setOf(plan.endpoint, initial.handle.pollUrl), fixture.http.requests.map { it.url }.toSet())
    }

    @Test fun invalidTaskIdOrUnknownStatusNeverTriggersAlternativeEndpoints() {
        for (json in listOf("{\"id\":\"../escape\",\"status\":\"queued\"}",
            "{\"id\":\"ok\",\"status\":\"unrecognized\"}", "{}", "")) {
            val fixture = GenerationFixture()
            fixture.http.respond = { jsonResponse(json) }
            assertTrue(fixture.videos.submit(generationRequest(protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART)) is Submission.Unknown)
            assertEquals(1, fixture.http.requests.size)
        }
    }

    @Test fun forgedOrOtherPlanHandleIsRejectedBeforeGet() {
        val fixture = GenerationFixture()
        fixture.http.respond = { jsonResponse("{\"id\":\"job1\",\"status\":\"queued\"}") }
        val plan = fixture.videos.prepare(generationRequest(protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART))
        val pending = fixture.videos.submit(plan) as Submission.Pending
        for (handle in listOf(pending.handle.copy(pollUrl = "https://evil.example/a"),
            pending.handle.copy(planFingerprint = "other"), pending.handle.copy(contentUrl = "https://evil.example/b"))) {
            assertEquals(Submission.NotExecuted(GenerationFailure.INVALID_REQUEST), fixture.videos.poll(plan, handle))
        }
        assertEquals(1, fixture.http.requests.size)
    }

    @Test fun failedVideoIsNotInterpretedAsPermissionToRegenerate() {
        val fixture = GenerationFixture()
        fixture.http.respond = { jsonResponse("{\"id\":\"job1\",\"status\":\"failed\"}") }
        val request = generationRequest(protocol = GenerationProtocol.OPENAI_VIDEO_MULTIPART)
        val failed = fixture.videos.submit(request) as Submission.Unknown
        assertEquals(GenerationFailure.REMOTE_FAILED, failed.reason)
        assertNotNull(failed.handle)
        assertEquals(failed, fixture.videos.submit(request))
        assertEquals(1, fixture.http.requests.size)
    }
}
