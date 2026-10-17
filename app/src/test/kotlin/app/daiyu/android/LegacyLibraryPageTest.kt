package app.daiyu.android

import android.net.Uri
import app.daiyu.android.backup.legacy.LegacyConversionReport
import app.daiyu.android.backup.legacy.LegacyHistorySummary
import app.daiyu.android.backup.legacy.LegacyMedia
import app.daiyu.android.backup.legacy.LegacyReadOnlyHistory
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises actual Page/picker closures without running a DB worker or constructing a helper. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LegacyLibraryPageTest {
    private class Queue : LegacyLibraryHost.Gateway {
        data class Job(val work: () -> Unit, val completed: (Boolean) -> Unit)
        val jobs = mutableListOf<Job>()
        var accepting = true
        override fun submit(work: () -> Unit, completed: (Boolean) -> Unit): Boolean {
            if (!accepting) return false
            jobs += Job(work, completed)
            return true
        }
    }

    private fun host(queue: Queue) = LegacyLibraryHost(
        resolver = RuntimeEnvironment.getApplication().contentResolver,
        serviceFactory = { error("No test may construct a service for revoked/unqueued work") },
        gateway = queue,
    )

    @Test fun sourcePickerResultIsSingleUse() {
        val queue = Queue()
        val page = host(queue).openPage { true }
        val picker = requireNotNull(page.requestSource())
        picker.result(Uri.parse("content://test/source.json"))
        picker.result(Uri.parse("content://test/replacement.zip"))
        assertEquals(1, queue.jobs.size)
        assertTrue(page.state.busy)
    }

    @Test fun historySwitchInvalidatesPickerBeforeWorkerRuns() {
        val queue = Queue()
        val page = host(queue).openPage { true }
        val picker = requireNotNull(page.requestSource())
        page.histories()
        assertEquals(1, queue.jobs.size)
        picker.result(Uri.parse("content://test/source.json"))
        assertEquals(1, queue.jobs.size)
        assertFalse(page.state.canConfirm)
    }

    @Test fun cancelAndNewPickerDoNotRebindOldCallback() {
        val queue = Queue()
        val page = host(queue).openPage { true }
        val old = requireNotNull(page.requestSource())
        page.cancelPending()
        val replacement = requireNotNull(page.requestSource())
        old.result(Uri.parse("content://test/old.json"))
        assertTrue(queue.jobs.isEmpty())
        assertTrue(page.state.pending)
        replacement.result(Uri.parse("content://test/new.json"))
        assertEquals(1, queue.jobs.size)
    }

    @Test fun revokedQueuedReadDoesNotReachServiceOrPublishCompletion() {
        val queue = Queue()
        val page = host(queue).openPage { true }
        page.histories()
        val job = queue.jobs.single()
        page.revoke()
        assertTrue(runCatching { job.work() }.isFailure)
        val revoked = page.state
        job.completed(false)
        job.completed(true)
        assertEquals(revoked, page.state)
        assertFalse(page.state.active)
    }

    @Test fun replacementVisitDoesNotReceiveOldPickerOrCompletion() {
        val queue = Queue()
        val host = host(queue)
        val old = host.openPage { true }
        val picker = requireNotNull(old.requestSource())
        old.histories()
        val job = queue.jobs.single()
        old.revoke()
        val replacement = host.openPage { true }
        val replacementState = replacement.state
        picker.result(Uri.parse("content://test/stale.zip"))
        job.completed(true)
        assertEquals(1, queue.jobs.size)
        assertEquals(replacementState, replacement.state)
    }

    @Test fun rejectedQueueDoesNotKeepGrantOrRetryPicker() {
        val queue = Queue().apply { accepting = false }
        val page = host(queue).openPage { true }
        val picker = requireNotNull(page.requestSource())
        picker.result(Uri.parse("content://test/source.json"))
        assertFalse(page.state.busy)
        assertFalse(page.state.pending)
        queue.accepting = true
        picker.result(Uri.parse("content://test/source.json"))
        assertTrue(queue.jobs.isEmpty())
    }

    private fun history(media: List<LegacyMedia> = emptyList()) = LegacyReadOnlyHistory(
        summary = LegacyHistorySummary("legacy-conversation", "source", "title", null, null, "legacy-import"),
        messages = emptyList(), modelHistoryJson = "[]", checkpointHistoryJson = null,
        media = media, compatibilityJson = "{}", report = LegacyConversionReport(emptyList()),
    )

    @Test fun saveRejectsKnownSourceIncludingDocumentTreeAlias() {
        val queue = Queue()
        val host = host(queue)
        val page = host.openPage { true }
        requireNotNull(page.requestSource()).result(Uri.parse("content://test/document/original"))
        assertTrue(runCatching {
            host.rejectSourceTarget(Uri.parse("content://test/tree/root/document/original"), history())
        }.isFailure)
        // A reference is compared only as URI text; no provider or file read is needed.
        val media = LegacyMedia("content://test/document/referenced", null, null, null, "raw", false)
        assertTrue(runCatching {
            host.rejectSourceTarget(Uri.parse("content://test/document/referenced"), history(listOf(media)))
        }.isFailure)
        host.rejectSourceTarget(Uri.parse("content://test/document/new-target"), history())
    }

    @Test fun inventoryIncludesUnreferencedOriginalAndRejectsConflictingEntry() {
        val host = host(Queue())
        val hash = "0".repeat(64)
        val unreferenced = LegacyMedia("", "blobs/original", hash, 3L, "unreferenced", true)
        val rows = host.inventory(listOf(unreferenced, unreferenced))
        assertEquals(listOf(LegacyOriginalRow("blobs/original", hash, 3L)), rows)
        assertTrue(runCatching {
            host.inventory(listOf(unreferenced, unreferenced.copy(bytes = 4L)))
        }.isFailure)
        assertTrue(runCatching { host.inventory(listOf(unreferenced.copy(sha256 = null))) }.isFailure)
    }

    @Test fun originalIsIndependentlyVerifiedAgainstBothHashAndByteCount() {
        val host = host(Queue())
        val bytes = "original".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val expected = LegacyOriginalRow("blobs/original", hash, bytes.size.toLong())
        host.verifyOriginal(bytes, expected)
        assertTrue(runCatching { host.verifyOriginal(bytes, expected.copy(bytes = expected.bytes + 1)) }.isFailure)
        assertTrue(runCatching { host.verifyOriginal("modified".toByteArray(), expected) }.isFailure)
    }

    @Test fun nullOrNonContentSourceDoesNotQueueOrConstructService() {
        val queue = Queue()
        val page = host(queue).openPage { true }
        requireNotNull(page.requestSource()).result(null)
        requireNotNull(page.requestSource()).result(Uri.parse("file:///private/legacy.json"))
        assertFalse(page.state.pending)
        assertTrue(queue.jobs.isEmpty())
    }
}
