package app.daiyu.android

import android.net.Uri
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import org.robolectric.Robolectric
import app.daiyu.android.media.video.*
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Host state/race contracts only. Fake metadata below is NOT evidence of Android video decoding. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VideoChatHostTest {
    private class Journal : VideoDraftPersistence {
        var rows = emptyList<VideoHostDraftStore.Draft>()
        var fail = false
        override fun read() = rows
        override fun write(drafts: List<VideoHostDraftStore.Draft>): Boolean {
            if (fail) return false
            VideoHostDraftStore.validate(drafts)
            rows = drafts.map { it.copy(videos = it.videos.toList()) }
            return true
        }
    }
    private class Media : VideoDraftMedia {
        var imports = 0
        var previewCalls = 0
        var fail = false
        var duringImport: (CancellationSignal) -> Unit = {}
        var duringPreview: (CancellationSignal) -> Unit = {}
        val deleted = mutableListOf<VideoAttachment>()
        val originals = mutableListOf<VideoAttachment>()
        var deleteFails = false
        var listFails = false
        override fun list(owner: VideoAttachmentOwner): List<VideoAttachment> {
            if (listFails) throw IllegalStateException("inventory unavailable")
            return originals.filter { it.owner == owner }
        }
        override fun import(owner: VideoAttachmentOwner, uri: Uri, cancellation: CancellationSignal): VideoAttachment {
            imports++
            duringImport(cancellation)
            if (fail) throw VideoAttachmentException(VideoAttachmentFailure.INVALID_VIDEO)
            // Deliberately return even if cancelled, to exercise the host's late-publication guard.
            if (originals.count { it.owner == owner } >= 4) throw VideoAttachmentException(VideoAttachmentFailure.TOO_MANY)
            return VideoAttachment(owner, UUID.randomUUID().toString(), "video/mp4", 20, "a".repeat(64), 1000, 32, 24)
                .also { originals.add(it) }
        }
        override fun preview(video: VideoAttachment, maxEdge: Int, cancellation: CancellationSignal): VideoPreview {
            previewCalls++
            duringPreview(cancellation)
            return VideoPreview(video, 2, 2, byteArrayOf(1, 2))
        }
        override fun remove(video: VideoAttachment) {
            if (deleteFails) throw IllegalStateException("delete failed")
            originals.remove(video)
            deleted.add(video)
        }
    }
    class PickerActivity : ComponentActivity()

    @Test fun actualActivityStopStartPreservesOnlyLaunchedPickerTicket() = runTest {
        val media = Media(); val d = StandardTestDispatcher(testScheduler)
        val host = VideoChatHost(Journal(), media, d, d)
        val activity = Robolectric.buildActivity(PickerActivity::class.java).setup()
        val binding = host.bindLifecycle(activity.get())
        val ticket = checkNotNull(host.requestSelection())
        var launched = 0
        assertTrue(host.launchSelection(ticket) { launched++ })
        assertEquals(1, launched)
        activity.pause().stop()
        assertFalse(host.state.visible)
        activity.start().resume()
        assertTrue(host.state.visible)
        assertTrue(host.picked(ticket, listOf(uri)))
        testScheduler.advanceUntilIdle()
        assertEquals(1, media.imports)

        // A requested but never launched ticket has NO lifecycle exemption.
        val unlaunched = checkNotNull(host.requestSelection())
        activity.pause().stop().start().resume()
        assertFalse(host.picked(unlaunched, listOf(uri)))
        assertEquals(1, media.imports)

        // The excursion is single-use; another ordinary stop after return invalidates it.
        val twice = checkNotNull(host.requestSelection())
        assertTrue(host.launchSelection(twice) {})
        activity.pause().stop().start().resume()
        activity.pause().stop().start().resume()
        assertFalse(host.picked(twice, listOf(uri)))
        val departed = checkNotNull(host.requestSelection())
        assertTrue(host.launchSelection(departed) {})
        activity.pause().stop()
        binding.close() // Real route departure while the Activity is still alive.
        activity.start().resume()
        host.attach()
        assertFalse(host.picked(departed, listOf(uri)))
        activity.pause().stop().destroy()
    }

    @Test fun actualLifecycleRouteDepartureDestroyWrongTicketAndLaunchFailureStayFenced() = runTest {
        val journal = Journal(); val media = Media(); val d = StandardTestDispatcher(testScheduler)
        val host = VideoChatHost(journal, media, d, d)
        val activity = Robolectric.buildActivity(PickerActivity::class.java).setup()
        val binding = host.bindLifecycle(activity.get())
        val old = checkNotNull(host.requestSelection())
        assertTrue(host.launchSelection(old) {})
        activity.pause().stop()
        host.enter("B"); host.enter(null)
        activity.start().resume()
        assertFalse(host.picked(old, listOf(uri)))
        val fresh = checkNotNull(host.requestSelection())
        assertFalse(host.picked(old, listOf(uri))) // wrong identity must not consume fresh
        assertFalse(host.launchSelection(fresh) { throw IllegalStateException("cannot launch") })
        assertFalse(host.picked(fresh, listOf(uri)))
        val destroyed = checkNotNull(host.requestSelection())
        assertTrue(host.launchSelection(destroyed) {})
        activity.pause().stop().destroy()
        host.attach()
        assertFalse(host.picked(destroyed, listOf(uri)))
        val restarted = VideoChatHost(journal, media, d, d)
        restarted.attach()
        assertFalse(restarted.picked(destroyed, listOf(uri)))
        testScheduler.advanceUntilIdle()
        assertEquals(0, media.imports)
        binding.close()
    }

    private val uri = Uri.parse("content://video-test/private-input")
    private fun VideoChatHost.pick(count: Int = 1): Boolean = picked(checkNotNull(requestSelection()), List(count) { uri })
    private fun receipt(request: VideoDraftRequest, outcome: VideoDraftOutcome, slot: String? = request.conversation,
        revision: Long = request.revision, videos: List<VideoAttachment> = request.videos,
        destination: String? = if (outcome == VideoDraftOutcome.ACCEPTED) request.owner.conversationId else null) =
        VideoDraftReceipt(slot, request.owner, revision, videos, outcome, destination)

    @Test fun nullSlotOwnerAndUnknownRevisionSurviveRestartWithoutRetry() = runTest {
        val journal = Journal(); val media = Media(); val dispatcher = StandardTestDispatcher(testScheduler)
        val host = VideoChatHost(journal, media, dispatcher, dispatcher)
        host.attach()
        val owner = host.state.owner
        host.enter("A"); host.enter(null)
        assertEquals(owner, host.state.owner)
        assertTrue(host.pick()); testScheduler.advanceUntilIdle()
        var callback = 0
        val request = checkNotNull(host.submit("unchanged") { callback++ })
        assertEquals(1, callback)
        assertTrue(host.settle(receipt(request, VideoDraftOutcome.UNKNOWN)))
        assertEquals(request.revision, host.state.revision)
        host.detach()
        val restored = VideoChatHost(journal, media, dispatcher, dispatcher)
        restored.attach()
        assertEquals(owner, restored.state.owner)
        assertEquals(request.revision, restored.state.revision)
        assertTrue(restored.state.fenced)
        assertNull(restored.requestSelection())
        assertNull(restored.submit("same") { fail("UNKNOWN must not retry") })
        assertFalse(restored.remove(restored.state.items.single().key))
        assertTrue(media.deleted.isEmpty())
        // Only an exact, authoritative recovery outcome may unseal the same saved revision.
        assertTrue(restored.settle(receipt(request, VideoDraftOutcome.REJECTED)))
        assertFalse(restored.state.fenced)
        assertEquals(request.revision + 1, restored.state.revision)
    }

    @Test fun aToBToAOldPickerNeverAccessesProviderOrBorrowNewTicket() = runTest {
        val media = Media(); val d = StandardTestDispatcher(testScheduler)
        val host = VideoChatHost(Journal(), media, d, d)
        host.attach(); host.enter("A")
        val old = checkNotNull(host.requestSelection())
        host.enter("B"); host.enter("A")
        assertNull(host.requestSelection()) // Outstanding launcher tombstone.
        assertFalse(host.picked(old, listOf(uri)))
        testScheduler.advanceUntilIdle()
        assertEquals(0, media.imports)
        val fresh = checkNotNull(host.requestSelection())
        assertFalse(host.picked(old, listOf(uri))) // Cannot consume the fresh ticket.
        assertTrue(host.picked(fresh, listOf(uri)))
        testScheduler.advanceUntilIdle()
        assertEquals(1, media.imports)
    }

    @Test fun departureCancelsSynchronouslyAndLateImportDeletesOnlyItsOwnOriginal() = runTest {
        val media = Media(); val journal = Journal(); val d = StandardTestDispatcher(testScheduler)
        val host = VideoChatHost(journal, media, d, d)
        host.attach(); host.enter("A")
        media.duringImport = { signal ->
            host.enter("B")
            assertTrue(signal.isCanceled)
            host.enter("A")
        }
        assertTrue(host.pick()); testScheduler.advanceUntilIdle()
        assertEquals(1, media.deleted.size)
        assertTrue(host.state.items.single() is VideoChatHost.Item.Failed)
        assertEquals(3, host.state.remaining) // Cancellation is not an implicit quota reset.
        assertFalse(host.state.canSubmit)
        assertTrue(journal.rows.single { it.conversation == "A" }.videos.isEmpty())
        host.enter("B")
        assertTrue(host.state.items.isEmpty())
    }

    @Test fun fourFailuresOccupyFourSlotsAndOversizedPickHasNoPartialImport() = runTest {
        val media = Media(); val d = StandardTestDispatcher(testScheduler)
        val host = VideoChatHost(Journal(), media, d, d)
        host.attach()
        assertFalse(host.pick(5)); testScheduler.advanceUntilIdle()
        assertEquals(0, media.imports)
        media.fail = true
        assertTrue(host.pick(4)); testScheduler.advanceUntilIdle()
        assertEquals(4, media.imports)
        assertEquals(0, host.state.remaining)
        assertTrue(host.state.items.all { it is VideoChatHost.Item.Failed })
        assertNull(host.requestSelection())
        assertNull(host.submit("cannot omit failed video") { fail("must not hand off") })
        assertTrue(host.remove(host.state.items.first().key))
        assertEquals(1, checkNotNull(host.requestSelection()).remaining)
    }

    @Test fun acceptedMigratesOnlyExactNullSlotAndCleanupUsesDraftReferences() = runTest {
        val media = Media(); val journal = Journal(); val d = StandardTestDispatcher(testScheduler)
        val host = VideoChatHost(journal, media, d, d)
        host.attach(); host.pick(); testScheduler.advanceUntilIdle()
        val request = checkNotNull(host.submit("text") {})
        host.enter("B")
        val otherOwner = host.state.owner
        assertFalse(host.settle(receipt(request, VideoDraftOutcome.ACCEPTED, slot = "")))
        assertFalse(host.settle(receipt(request, VideoDraftOutcome.ACCEPTED, revision = request.revision + 1)))
        assertFalse(host.settle(receipt(request, VideoDraftOutcome.ACCEPTED, videos = emptyList())))
        assertFalse(host.settle(receipt(request, VideoDraftOutcome.ACCEPTED, destination = "wrong")))
        assertTrue(media.deleted.isEmpty())
        assertTrue(host.settle(receipt(request, VideoDraftOutcome.ACCEPTED)))
        testScheduler.advanceUntilIdle()
        assertEquals(request.videos, media.deleted)
        assertEquals("B", host.state.conversation)
        assertEquals(otherOwner, host.state.owner)
        assertFalse(journal.rows.any { it.conversation == null })
        host.enter(request.owner.conversationId)
        assertEquals(request.owner, host.state.owner)
        assertTrue(host.state.items.isEmpty())
        assertEquals(request.revision + 1, host.state.revision)
        host.enter(null)
        assertNotEquals(request.owner, host.state.owner)
    }

    @Test fun failedDurableAcceptanceNeverDeletesAndThrownHandoffStaysFenced() = runTest {
        val journal = Journal(); val media = Media(); val d = StandardTestDispatcher(testScheduler)
        val host = VideoChatHost(journal, media, d, d)
        host.attach(); host.pick(); testScheduler.advanceUntilIdle()
        val request = checkNotNull(host.submit("text") { throw IllegalStateException("after possible commit") })
        assertTrue(host.state.fenced)
        journal.fail = true
        assertFalse(host.settle(receipt(request, VideoDraftOutcome.ACCEPTED)))
        testScheduler.advanceUntilIdle()
        assertTrue(media.deleted.isEmpty())
        assertTrue(host.state.storageFailed)
        assertEquals(request.videos, journal.rows.single().videos)
        assertTrue(journal.rows.single().fenced)
    }

    @Test fun previewResultCannotEscapeItsOriginalVisit() = runTest {
        val media = Media(); val d = StandardTestDispatcher(testScheduler)
        val host = VideoChatHost(Journal(), media, d, d)
        host.attach(); host.pick(); testScheduler.advanceUntilIdle()
        val video = (host.state.items.single() as VideoChatHost.Item.Ready).video
        val preview = async { host.preview(video) }
        testScheduler.advanceUntilIdle()
        assertEquals(video, checkNotNull(preview.await()).original)
        media.duringPreview = { signal ->
            host.detach(); host.attach()
            assertTrue(signal.isCanceled)
        }
        val stale = async { host.preview(video) }
        testScheduler.advanceUntilIdle()
        assertNull(stale.await())
        assertEquals(2, media.previewCalls)
        assertTrue(media.deleted.isEmpty())
    }

    @Test fun failedReservationsSurviveRestartAndSubmissionIsDurableBeforeHandoff() = runTest {
        val media = Media(); val journal = Journal(); val d = StandardTestDispatcher(testScheduler)
        val host = VideoChatHost(journal, media, d, d)
        host.attach(); media.fail = true
        host.pick(4); testScheduler.advanceUntilIdle(); host.detach()
        val restored = VideoChatHost(journal, media, d, d)
        restored.attach()
        testScheduler.advanceUntilIdle()
        assertEquals(4, restored.state.items.size)
        assertEquals(0, restored.state.remaining)
        assertNull(restored.requestSelection())
        restored.state.items.toList().forEach { assertTrue(restored.remove(it.key)) }
        media.fail = false
        restored.pick(); testScheduler.advanceUntilIdle()
        val request = checkNotNull(restored.submit("body") { handed ->
            val saved = journal.rows.single()
            assertTrue(saved.fenced)
            assertEquals(handed.revision, saved.revision)
            assertEquals(handed.videos, saved.videos)
        })
        assertTrue(restored.settle(receipt(request, VideoDraftOutcome.REJECTED)))
        assertTrue(media.deleted.isEmpty())
        assertFalse(restored.settle(receipt(request, VideoDraftOutcome.ACCEPTED)))
    }

    @Test fun importOnDiskBeforeJournalCrashRecoversOnlyExactUnreferencedOwnerAndReimportsFour() = runTest {
        val journal = Journal(); val media = Media(); val d = StandardTestDispatcher(testScheduler)
        val host = VideoChatHost(journal, media, d, d)
        host.attach()
        val owner = host.state.owner
        // A provider completion writes the original, then the Ready journal commit fails.
        media.duringImport = { journal.fail = true }
        assertTrue(host.pick(4))
        testScheduler.advanceUntilIdle()
        assertTrue(host.state.storageFailed)
        assertEquals(4, media.originals.count { it.owner == owner })
        assertTrue(journal.rows.single().videos.isEmpty())
        assertEquals(4, journal.rows.single().blockedSlots)
        val otherOwner = VideoAttachmentOwner("elsewhere", "other-draft")
        val other = media.import(otherOwner, uri, CancellationSignal())
        media.duringImport = {}
        journal.fail = false
        val restored = VideoChatHost(journal, media, d, d)
        restored.attach()
        assertTrue(restored.state.recovering)
        assertNull(restored.requestSelection())
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(other), media.originals)
        assertEquals(4, media.deleted.size)
        assertTrue(journal.rows.single().pendingDeletes.isEmpty())
        // Failed cards remain explicit, but their files no longer permanently consume quota.
        restored.state.items.toList().forEach { assertTrue(restored.remove(it.key)) }
        assertTrue(restored.pick(4))
        testScheduler.advanceUntilIdle()
        assertEquals(4, restored.state.items.filterIsInstance<VideoChatHost.Item.Ready>().size)
        assertEquals(4, media.originals.count { it.owner == owner })
    }

    @Test fun acceptedEmptyJournalBeforeDeleteCrashRestartsFromDurableQueueAndCanImportAgain() = runTest {
        val journal = Journal(); val media = Media()
        // The old process executor is deliberately never run after the ACCEPTED commit.
        val deadScheduler = TestCoroutineScheduler(); val dead = StandardTestDispatcher(deadScheduler)
        val host = VideoChatHost(journal, media, dead, dead)
        host.attach(); assertTrue(host.pick(4)); deadScheduler.advanceUntilIdle()
        val request = checkNotNull(host.submit("body") {})
        assertTrue(host.settle(receipt(request, VideoDraftOutcome.ACCEPTED)))
        val saved = journal.rows.single()
        assertTrue(saved.videos.isEmpty())
        assertFalse(saved.fenced)
        assertEquals(request.videos, saved.pendingDeletes)
        assertTrue(media.deleted.isEmpty())
        val d = StandardTestDispatcher(testScheduler)
        val restored = VideoChatHost(journal, media, d, d)
        restored.attach(); testScheduler.advanceUntilIdle()
        assertTrue(media.originals.isEmpty())
        assertEquals(request.videos.toSet(), media.deleted.toSet())
        assertTrue(journal.rows.single { it.owner == request.owner }.pendingDeletes.isEmpty())
        restored.enter(request.owner.conversationId)
        assertTrue(restored.pick(4)); testScheduler.advanceUntilIdle()
        assertEquals(4, restored.state.items.filterIsInstance<VideoChatHost.Item.Ready>().size)
        assertTrue(restored.pendingRecovery().isEmpty()) // no resend was invented
    }

    @Test fun deletionFailureRetainsExactQueueAndRestartRetriesWithoutTouchingReadyOrUnknown() = runTest {
        val journal = Journal(); val media = Media(); val d = StandardTestDispatcher(testScheduler)
        val host = VideoChatHost(journal, media, d, d)
        host.attach(); host.enter("ready"); host.pick(); testScheduler.advanceUntilIdle()
        val ready = (host.state.items.single() as VideoChatHost.Item.Ready).video
        host.enter("unknown"); host.pick(); testScheduler.advanceUntilIdle()
        val unknown = checkNotNull(host.submit("never resend") {})
        host.enter("delete"); host.pick(4); testScheduler.advanceUntilIdle()
        val accepted = checkNotNull(host.submit("body") {})
        media.deleteFails = true
        assertTrue(host.settle(receipt(accepted, VideoDraftOutcome.ACCEPTED)))
        testScheduler.advanceUntilIdle()
        assertEquals(accepted.videos, journal.rows.single { it.conversation == "delete" }.pendingDeletes)
        assertTrue(media.deleted.isEmpty())
        // Failed explicit retry is bounded (no retry storm).
        host.retryCleanup(); testScheduler.advanceUntilIdle()
        assertEquals(accepted.videos, journal.rows.single { it.conversation == "delete" }.pendingDeletes)
        val restored = VideoChatHost(journal, media, d, d)
        restored.attach(); testScheduler.advanceUntilIdle()
        assertEquals(accepted.videos, journal.rows.single { it.conversation == "delete" }.pendingDeletes)
        media.deleteFails = false
        restored.retryCleanup(); testScheduler.advanceUntilIdle()
        assertEquals(setOf(ready) + unknown.videos, media.originals.toSet())
        assertEquals(accepted.videos.toSet(), media.deleted.toSet())
        assertEquals(unknown.videos, restored.pendingRecovery().single().videos)
        restored.enter("delete"); assertTrue(restored.pick(4)); testScheduler.advanceUntilIdle()
        assertEquals(4, restored.state.items.filterIsInstance<VideoChatHost.Item.Ready>().size)
    }

    @Test fun inventoryFailureNeverDeletesOrGrantsNewPickerCapacityUntilRetry() = runTest {
        val journal = Journal(); val media = Media(); val d = StandardTestDispatcher(testScheduler)
        val host = VideoChatHost(journal, media, d, d)
        host.attach(); host.pick(); testScheduler.advanceUntilIdle()
        val original = (host.state.items.single() as VideoChatHost.Item.Ready).video
        media.listFails = true
        val restored = VideoChatHost(journal, media, d, d)
        restored.attach(); testScheduler.advanceUntilIdle()
        assertTrue(restored.state.recovering)
        assertNull(restored.requestSelection())
        assertTrue(media.deleted.isEmpty())
        media.listFails = false
        restored.retryCleanup(); testScheduler.advanceUntilIdle()
        assertFalse(restored.state.recovering)
        assertEquals(listOf(original), media.originals)
        assertNotNull(restored.requestSelection())
    }

    @Test fun corruptJournalIsNeverOverwrittenByFreshSendableIdentity() = runTest {
        val media = Media(); val d = StandardTestDispatcher(testScheduler)
        var writes = 0
        val persistence = object : VideoDraftPersistence {
            override fun read(): List<VideoHostDraftStore.Draft> = throw IllegalStateException("corrupt")
            override fun write(drafts: List<VideoHostDraftStore.Draft>): Boolean { writes++; return true }
        }
        val host = VideoChatHost(persistence, media, d, d)
        host.attach(); host.enter("A"); host.enter(null)
        assertTrue(host.state.storageFailed)
        assertNull(host.requestSelection())
        assertNull(host.submit("body") { fail("unreadable journal cannot send") })
        assertEquals(0, writes)
    }

    @Test fun staleUiRevisionAndDetachedPickerCannotGrantPermission() = runTest {
        val media = Media(); val d = StandardTestDispatcher(testScheduler)
        val host = VideoChatHost(Journal(), media, d, d)
        host.attach()
        val before = host.state
        host.pick(); testScheduler.advanceUntilIdle()
        assertNull(host.requestSelection(before))
        assertFalse(host.remove(host.state.items.single().key, before))
        val picker = checkNotNull(host.requestSelection())
        host.detach()
        assertFalse(host.picked(picker, listOf(uri)))
        assertEquals(1, media.imports)
    }
}
