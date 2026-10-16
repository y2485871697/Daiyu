package app.daiyu.android.media

import app.daiyu.android.media.video.VideoAttachmentLimits
import app.daiyu.android.media.video.VideoInspector
import app.daiyu.android.media.video.VideoMetadata
import app.daiyu.android.model.generation.*
import app.daiyu.android.storage.GenerationAttemptBinding
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Offline storage tests. Injected inspectors accept NON-media bytes; these do not prove codecs. */
class MessageGenerationAssetsTest {
    private lateinit var root: File
    private lateinit var owner: GenerationAssetOwner
    private lateinit var fence: GenerationAssetFence
    private val image = byteArrayOf(1, 2, 3, 4)
    private val video = byteArrayOf(2, 3, 4, 5)
    private val info = GenerationMediaInfo("image/png", 2, 2)
    private val imageInspector = ImageInspector {
        require(it.first() == 1.toByte())
        ImageDimensions("image/png", 2, 2)
    }
    private val videoInspector = VideoInspector { file, _, _ ->
        require(file.readBytes().first() == 2.toByte())
        VideoMetadata("video/mp4", 1000, 2, 2)
    }
    private val identity get() = owner.binding.identity("item")

    @Before fun setUp() {
        root = Files.createTempDirectory("generation-assets-test").toFile()
        owner = owner("account", "assistant")
        fence = GenerationAssetFence(owner) { true }
    }
    @After fun tearDown() { root.deleteRecursively() }

    private fun owner(account: String, assistant: String) = GenerationAssetOwner(account,
        GenerationAttemptBinding("conversation", assistant, "run", "call", "image_generation"))
    private fun store(admission: GenerationAssetFence = fence, inspect: ImageInspector = imageInspector) =
        MessageGenerationAssets(root, admission, inspect, videoInspector, ImageAttachmentLimits(), VideoAttachmentLimits())
    private fun expect(reason: GenerationAssetFailure, action: () -> Unit) {
        try { action(); fail("Expected $reason") }
        catch (failure: GenerationAssetException) { assertEquals(reason, failure.failure) }
    }
    private fun originals() = root.walkTopDown().filter { it.name == "original" }.toList()
    private fun await(latch: CountDownLatch) { check(latch.await(5, TimeUnit.SECONDS)) }
    private fun join(thread: Thread) { thread.join(5000); assertFalse("worker did not exit", thread.isAlive) }
    private fun awaitBlockedOnFence(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.BLOCKED && thread.isAlive && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(Thread.State.BLOCKED, thread.state)
    }

    @Test fun incrementalIndicesAndReopenPreserveExactOriginalsAndReceipt() {
        val first = store().commit(identity, 3, GenerationKind.IMAGE, image, info)
        val second = store().commit(identity, 0, GenerationKind.VIDEO, video,
            GenerationMediaInfo("video/mp4", 2, 2))
        assertNotEquals(first, second)
        val reopened = store(GenerationAssetFence(owner("account", "assistant")) { true })
        assertEquals(first, reopened.pending(identity, 3)!!.receipt)
        assertEquals(second, reopened.pending(identity, 0)!!.receipt)
        assertEquals(first, reopened.commit(identity, 3, GenerationKind.IMAGE, image, info))
        assertTrue(image.contentEquals(reopened.openOriginal(first).use { it.readBytes() }))
        assertTrue(video.contentEquals(reopened.openOriginal(second).use { it.readBytes() }))
        assertEquals(1000L, reopened.pending(second).durationMs)
        assertEquals("video/mp4", reopened.pending(second).mimeType)
        assertEquals(2, originals().size)
        assertNull(reopened.pending(identity, 1))
    }

    @Test fun digestConflictAndInvalidSiblingNeverEraseEarlierResults() {
        val target = store()
        val first = target.commit(identity, 0, GenerationKind.IMAGE, image, info)
        val changed = image.copyOf().apply { this[3] = 9 }
        expect(GenerationAssetFailure.IDENTITY_CONFLICT) {
            target.commit(identity, 0, GenerationKind.IMAGE, changed, info)
        }
        expect(GenerationAssetFailure.INVALID_MEDIA) {
            target.commit(identity, 1, GenerationKind.IMAGE, video, info)
        }
        val third = target.commit(identity, 2, GenerationKind.IMAGE, changed, info)
        assertEquals(first, store().pending(identity, 0)!!.receipt)
        assertEquals(third, store().pending(identity, 2)!!.receipt)
        assertEquals(2, originals().size)
    }

    @Test fun sameBytesInAnotherAccountOwnerItemOrIndexAreNotTheSameAsset() {
        val first = store().commit(identity, 0, GenerationKind.IMAGE, image, info)
        val nextIndex = store().commit(identity, 1, GenerationKind.IMAGE, image, info)
        val nextItem = store().commit(owner.binding.identity("other"), 0, GenerationKind.IMAGE, image, info)
        val otherAccount = GenerationAssetFence(owner("other-account", "assistant")) { true }
        val other = store(otherAccount).commit(identity, 0, GenerationKind.IMAGE, image, info)
        val otherOwner = GenerationAssetFence(owner("account", "other-assistant")) { true }
        val different = store(otherOwner).commit(otherOwner.owner.binding.identity("item"), 0, GenerationKind.IMAGE, image, info)
        assertEquals(5, setOf(first, nextIndex, nextItem, other, different).size)
        expect(GenerationAssetFailure.CORRUPT) { store(otherAccount).pending(first) }
        expect(GenerationAssetFailure.OWNER_REJECTED) {
            store().commit(otherOwner.owner.binding.identity("item"), 2, GenerationKind.IMAGE, image, info)
        }
    }

    @Test fun renamingTheSameToolCallCannotAllocateAnotherSlot() {
        val first = store().commit(identity, 0, GenerationKind.IMAGE, image, info)
        val renamed = GenerationAssetOwner("account", GenerationAttemptBinding(
            "conversation", "assistant", "run", "call", "renamed_tool"))
        assertEquals(identity.operationId, renamed.binding.operationId)
        expect(GenerationAssetFailure.CORRUPT) {
            store(GenerationAssetFence(renamed) { true }).commit(identity, 0, GenerationKind.IMAGE, image, info)
        }
        assertEquals(first, store().pending(identity, 0)!!.receipt)
        assertEquals(1, originals().size)
    }

    @Test fun bypassingInspectCannotPublishFalseMimeOrDimensions() {
        expect(GenerationAssetFailure.INVALID_MEDIA) { store().inspect(GenerationKind.IMAGE, image, "image/jpeg") }
        assertEquals(info, store().inspect(GenerationKind.IMAGE, image, "application/octet-stream"))
        expect(GenerationAssetFailure.INVALID_MEDIA) {
            store().commit(identity, 0, GenerationKind.IMAGE, image, info.copy(width = 90))
        }
        expect(GenerationAssetFailure.INVALID_MEDIA) { store().commit(identity, 0, GenerationKind.IMAGE, byteArrayOf(), info) }
        assertTrue(originals().isEmpty())
    }

    @Test fun revokedAndRejectedOwnersCannotPublishOrResolve() {
        val receipt = store().commit(identity, 0, GenerationKind.IMAGE, image, info)
        fence.revoke()
        expect(GenerationAssetFailure.CANCELLED) { store().pending(receipt) }
        expect(GenerationAssetFailure.CANCELLED) { store().commit(identity, 1, GenerationKind.IMAGE, image, info) }
        val denied = store(GenerationAssetFence(owner) { false })
        expect(GenerationAssetFailure.OWNER_REJECTED) { denied.pending(receipt) }
        assertEquals(receipt, store(GenerationAssetFence(owner) { true }).pending(identity, 0)!!.receipt)
    }

    @Test fun registrationAdmissionRejectsRevocationAndForeignOwnerWithoutRunningSql() {
        val assets = store()
        var sqlCalls = 0
        val sameOwner = owner("account", "assistant")
        assertEquals("committed", assets.admitRegistration(sameOwner) { sqlCalls++; "committed" })
        expect(GenerationAssetFailure.OWNER_REJECTED) {
            assets.admitRegistration(owner("other-account", "assistant")) { sqlCalls++ }
        }
        fence.revoke()
        expect(GenerationAssetFailure.CANCELLED) { assets.admitRegistration(owner) { sqlCalls++ } }
        assertEquals(1, sqlCalls)
        assertTrue(originals().isEmpty()) // Admission itself performs no file verification/publication.
    }

    @Test fun registrationAdmissionKeepsSameGateUntilCallbackCompletes() {
        val assets = store()
        val inSql = CountDownLatch(1); val releaseSql = CountDownLatch(1)
        val revoking = CountDownLatch(1); val revoked = CountDownLatch(1)
        val result = AtomicReference<String>()
        val failure = AtomicReference<Throwable>()
        val register = Thread {
            try {
                result.set(assets.admitRegistration(owner) {
                    inSql.countDown(); check(releaseSql.await(15, TimeUnit.SECONDS)); "committed"
                })
            } catch (error: Throwable) { failure.set(error) }
        }
        val revoke = Thread { revoking.countDown(); fence.revoke(); revoked.countDown() }
        register.start()
        try {
            await(inSql); revoke.start(); await(revoking)
            awaitBlockedOnFence(revoke)
            assertEquals(1L, revoked.count)
        } finally {
            releaseSql.countDown(); join(register); join(revoke)
        }
        failure.get()?.let { throw it }
        assertEquals("committed", result.get())
        assertEquals(0L, revoked.count)
        expect(GenerationAssetFailure.CANCELLED) { assets.admitRegistration(owner) { fail("revoked") } }
    }

    @Test fun cancellationDuringInspectionPreventsPublicationButPreservesSiblings() {
        val prior = store().commit(identity, 0, GenerationKind.IMAGE, image, info)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<Throwable?>()
        val blocking = ImageInspector { bytes -> entered.countDown(); await(release); imageInspector.inspect(bytes) }
        val worker = Thread {
            try { store(inspect = blocking).commit(identity, 1, GenerationKind.IMAGE, image, info) }
            catch (failure: Throwable) { result.set(failure) }
        }
        worker.start()
        try { await(entered); fence.revoke() } finally { release.countDown() }
        join(worker)
        assertEquals(GenerationAssetFailure.CANCELLED, (result.get() as GenerationAssetException).failure)
        val reopened = store(GenerationAssetFence(owner) { true })
        assertEquals(prior, reopened.pending(identity, 0)!!.receipt)
        assertNull(reopened.pending(identity, 1))
        assertFalse(root.walkTopDown().any { it.isDirectory && it.name.endsWith(".pending") })
    }

    @Test fun publicationAdmittedFirstWinsAgainstRevoke() {
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val revoking = CountDownLatch(1)
        val result = AtomicReference<GenerationAssetReceipt?>()
        val failure = AtomicReference<Throwable?>()
        var admissions = 0
        val linearFence = GenerationAssetFence(owner) {
            admissions++
            if (admissions == 2) { admitted.countDown(); await(release) }
            true
        }
        val writer = Thread {
            try { result.set(store(linearFence).commit(identity, 0, GenerationKind.IMAGE, image, info)) }
            catch (error: Throwable) { failure.set(error) }
        }
        val canceller = Thread { revoking.countDown(); linearFence.revoke() }
        writer.start()
        try { await(admitted); canceller.start(); await(revoking) } finally { release.countDown() }
        join(writer); join(canceller)
        assertNull(failure.get())
        assertNotNull(result.get())
        assertEquals(result.get(), store(GenerationAssetFence(owner) { true }).pending(identity, 0)!!.receipt)
    }

    @Test fun twoIndependentHelpersConvergeOnOnePublishedDirectory() {
        val start = CountDownLatch(1)
        val results = List(2) { AtomicReference<GenerationAssetReceipt?>() }
        val errors = List(2) { AtomicReference<Throwable?>() }
        val workers = (0..1).map { i -> Thread {
            try { await(start); results[i].set(store(GenerationAssetFence(owner) { true })
                .commit(identity, 0, GenerationKind.IMAGE, image, info)) }
            catch (error: Throwable) { errors[i].set(error) }
        } }
        workers.forEach { it.start() }; start.countDown(); workers.forEach(::join)
        errors.forEach { assertNull(it.get()) }
        assertNotNull(results[0].get()); assertEquals(results[0].get(), results[1].get())
        assertEquals(1, originals().size)
    }

    @Test fun tamperedSizeDigestRecordAndSymlinkFailWithoutDeletingFiles() {
        val target = store()
        val receipt = target.commit(identity, 0, GenerationKind.IMAGE, image, info)
        val original = originals().single()
        original.writeBytes(image + 0)
        expect(GenerationAssetFailure.CORRUPT) { store().pending(receipt) }
        original.writeBytes(image.copyOf().apply { this[3] = 8 })
        expect(GenerationAssetFailure.CORRUPT) { store().pending(identity, 0) }
        original.writeBytes(image)
        val record = File(original.parentFile, "record-v1")
        val recordBytes = record.readBytes()
        record.writeBytes(recordBytes + 0)
        expect(GenerationAssetFailure.CORRUPT) { store().pending(receipt) }
        record.writeBytes(recordBytes)
        val outside = File(root, "outside").apply { writeBytes(image) }
        assertTrue(original.delete())
        Files.createSymbolicLink(original.toPath(), outside.toPath())
        expect(GenerationAssetFailure.STORAGE) { store().pending(receipt) }
        assertTrue(Files.isSymbolicLink(original.toPath()))
        assertTrue(outside.exists())
    }

    @Test fun unknownStagingAndUnknownDbOutcomeNeverTriggerPublishedCleanup() {
        val receipt = store().commit(identity, 0, GenerationKind.IMAGE, image, info)
        val published = originals().single().parentFile
        val staging = File(published.parentFile, published.name + ".pending").apply { mkdir() }
        val unknown = File(staging, "unknown").apply { writeText("retained") }
        // Simulates an unknown DB outcome: no registration is acknowledged, only recover by key.
        assertEquals(receipt, store().pending(identity, 0)!!.receipt)
        assertEquals(receipt, store().commit(identity, 0, GenerationKind.IMAGE, image, info))
        assertTrue(unknown.exists()); assertTrue(published.exists())
    }

    @Test fun incompletePriorPublicationIsNotSilentlyDeletedOrOverwritten() {
        val first = store().commit(identity, 0, GenerationKind.IMAGE, image, info)
        val second = store().commit(identity, 1, GenerationKind.IMAGE, image, info)
        val secondDirectory = originals().single { file ->
            File(file.parentFile, "record-v1").readBytes().let { record ->
                // Receipt encodes the stable slot; there is no random output identifier.
                second.assetId.contains(file.parentFile.name) && record.isNotEmpty()
            }
        }.parentFile
        val pending = File(secondDirectory.parentFile, secondDirectory.name + ".pending")
        assertTrue(secondDirectory.renameTo(pending))
        expect(GenerationAssetFailure.STORAGE) { store().commit(identity, 1, GenerationKind.IMAGE, image, info) }
        assertTrue(File(pending, "original").exists())
        assertEquals(first, store().pending(identity, 0)!!.receipt)
        assertNull(store().pending(identity, 1))
    }

    @Test fun existingDownloadAuthorizationIsStillMandatoryAndDenialDoesNotInspect() {
        var inspected = 0
        val assets = store(inspect = ImageInspector { inspected++; imageInspector.inspect(it) })
        val plan = GenerationRequestPlan.prepare(GenerationRequest(identity, GenerationProtocol.OPENAI_IMAGES_JSON,
            "https://example.invalid/v1/images/generations", "", "test", "test"), assets)
        val attempts = object : GenerationAttemptStore {
            override fun reserve(attempt: GenerationAttempt): GenerationReservation = error("no submit")
            override fun finish(attempt: GenerationAttempt, submission: Submission) = error("no submit")
        }
        val io = GenerationIo(assets, attempts, { false }, GenerationHttp { error("network forbidden") })
        assertEquals(GenerationAssetResult.Failed(GenerationFailure.DOWNLOAD_DENIED),
            io.downloadAndCommit(plan, GenerationOutput(0, GenerationSource.Remote("https://example.invalid/result"))))
        assertEquals(0, inspected); assertTrue(originals().isEmpty())
    }
}
