package com.openminis.app.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** JVM tests: only the Context type is linked; no Android Context is constructed or invoked. */
class BrowserHistoryStoreTest {
    private val now = 1_750_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private class GatedBackend(private val initial: String? = null) : BrowserHistoryStore.Backend {
        val reading = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val writes = Collections.synchronizedList(mutableListOf<String>())
        val threads = Collections.synchronizedList(mutableListOf<String>())
        override fun read(): String? {
            threads.add(Thread.currentThread().name)
            reading.countDown()
            check(releaseRead.await(5, TimeUnit.SECONDS))
            return initial
        }
        override fun write(json: String) {
            threads.add(Thread.currentThread().name)
            writes.add(json)
        }
    }

    private fun seed(vararg entries: BrowserHistoryStore.Entry): String {
        val array = JSONArray()
        for (entry in entries) array.put(JSONObject().apply {
            put("id", entry.id); put("url", entry.url); put("title", entry.title)
            put("timestamp", entry.timestamp); put("domain", entry.domain)
        })
        return array.toString()
    }

    private fun entry(id: String, age: Long = 0) = BrowserHistoryStore.Entry(
        id, "https://$id.example/", "Title $id", now - age,
    )

    private fun drain(store: BrowserHistoryStore) {
        store.close()
        assertTrue("worker did not drain", store.awaitTermination(5, TimeUnit.SECONDS))
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("must reject before admission") }
        catch (_: RejectedExecutionException) { }
    }

    @Test(timeout = 10000) fun initialLoadAndEveryIoStayOffCallerAndMergeAcceptedRecords() {
        val backend = GatedBackend(seed(entry("old")))
        val store = BrowserHistoryStore(backend, { now })
        assertTrue(backend.reading.await(2, TimeUnit.SECONDS))
        try {
            assertEquals(BrowserHistoryStore.LoadState.LOADING, store.loadState)
            assertTrue(store.getEntries().isEmpty()) // Explicitly partial, not synchronously loaded.
            store.record("https://new.example/", "New")
            store.record("https://new.example/", "duplicate")
            assertEquals(listOf("New"), store.getEntries().map { it.title })
            assertEquals(0, backend.writes.size)
        } finally {
            backend.releaseRead.countDown()
            drain(store)
        }
        assertEquals(BrowserHistoryStore.LoadState.READY, store.loadState)
        assertEquals(setOf("Title old", "New"), store.getEntries().map { it.title }.toSet())
        assertTrue(backend.threads.all { it == "browser-history-io" })
        assertEquals(1, backend.writes.size)
        assertEquals(2, JSONArray(backend.writes.last()).length())
    }

    @Test(timeout = 10000) fun startupClearCannotBeOverwrittenByLateLoad() {
        val backend = GatedBackend(seed(entry("old"), entry("keep")))
        val store = BrowserHistoryStore(backend, { now })
        assertTrue(backend.reading.await(2, TimeUnit.SECONDS))
        try {
            store.record("https://before.example/", "Before")
            store.clear()
            store.record("https://after.example/", "After")
        } finally {
            backend.releaseRead.countDown()
            drain(store)
        }
        assertEquals(setOf("https://after.example/"), store.getEntries().map { it.url }.toSet())
        val disk = JSONArray(backend.writes.last()).toString()
        assertFalse(disk.contains("old.example"))
        assertEquals(1, JSONArray(disk).length())
    }

    @Test(timeout = 10000) fun fullQueueRejectsWithoutChangingStateOrReplayingRejectedEvents() {
        val backend = GatedBackend()
        val store = BrowserHistoryStore(backend, { now }, queueCapacity = 2)
        assertTrue(backend.reading.await(2, TimeUnit.SECONDS))
        try {
            store.record("https://one.example/", "one")
            store.record("https://two.example/", "two")
            val before = store.getEntries()
            rejected { store.record("https://rejected.example/", "rejected") }
            rejected { store.clear() }
            assertEquals(before, store.getEntries())
            // Duplicate is a no-op, not an event that needs queue admission.
            store.record("https://two.example/", "ignored")
        } finally {
            store.close() // Must return while initial read is still blocked.
            rejected { store.record("https://closed.example/", "closed") }
            backend.releaseRead.countDown()
            assertTrue(store.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertEquals(2, backend.writes.size)
        assertFalse(backend.writes.last().contains("rejected"))
        assertFalse(backend.writes.last().contains("closed"))
    }

    @Test(timeout = 10000) fun concurrentAdmissionIsBoundedAndNoAcceptedRecordIsLostDuringLoad() {
        val backend = GatedBackend(seed(entry("seed")))
        val store = BrowserHistoryStore(backend, { now }, maxEntries = 16)
        assertTrue(backend.reading.await(2, TimeUnit.SECONDS))
        try {
            val threads = (0 until 8).map { owner ->
                thread {
                    repeat(4) { n -> store.record("https://o$owner.example/$n", "$owner-$n") }
                }
            }
            threads.forEach { it.join() }
            assertEquals(16, store.getEntries().size)
            assertEquals(16, store.getEntries().map { it.id }.distinct().size)
        } finally {
            backend.releaseRead.countDown()
            drain(store)
        }
        assertEquals(16, store.getEntries().size)
        assertEquals(16, JSONArray(backend.writes.last()).length())
        assertFalse(store.getEntries().any { it.id == "seed" })
    }

    @Test(timeout = 10000) fun realAtomicFileSurvivesRestartAndContainsOnlyHistorySchema() {
        val dir = Files.createTempDirectory("browser-history-test")
        try {
            val directoryThreads = Collections.synchronizedList(mutableListOf<String>())
            val backend = BrowserHistoryStore.FileBackend {
                directoryThreads.add(Thread.currentThread().name)
                dir
            }
            val store = BrowserHistoryStore(backend, { now })
            store.record("https://example.com/a", "A \"title\"\nwith unicode 星")
            drain(store)
            assertFalse(store.persistenceFailed)
            assertTrue(directoryThreads.all { it == "browser-history-io" })
            val path = dir.resolve(BrowserHistoryStore.FILENAME)
            assertTrue(Files.isRegularFile(path))
            val row = JSONArray(Files.readString(path)).getJSONObject(0)
            val keys = mutableSetOf<String>()
            val iterator = row.keys()
            while (iterator.hasNext()) keys.add(iterator.next())
            assertEquals(setOf("id", "url", "title", "timestamp", "domain"), keys)
            assertEquals("example.com", row.getString("domain"))
            Files.list(dir).use { assertEquals(1L, it.count()) } // No orphan temp files.

            val reopened = BrowserHistoryStore(BrowserHistoryStore.FileBackend { dir }, { now })
            drain(reopened)
            assertEquals(BrowserHistoryStore.LoadState.READY, reopened.loadState)
            assertEquals(store.getEntries(), reopened.getEntries())

            val cleared = BrowserHistoryStore(BrowserHistoryStore.FileBackend { dir }, { now })
            cleared.clear() // May race startup read; must still persist empty history.
            drain(cleared)
            assertEquals(0, JSONArray(Files.readString(path)).length())
        } finally { deleteDirectory(dir) }
    }

    @Test(timeout = 10000) fun retentionSnapshotSearchGroupingAndDomainApiRemainCompatible() {
        val clock = AtomicLong(now)
        val backend = GatedBackend(seed(entry("expired", 7 * day + 1),
            entry("boundary", 7 * day), entry("yesterday", day)))
        val store = BrowserHistoryStore(backend, clock::get)
        try {
            store.record("https://today.example/", "Needle Today")
            store.record("https://today.example/", "dedup")
        } finally {
            backend.releaseRead.countDown()
            drain(store)
        }
        assertEquals(3, store.getEntries().size)
        assertEquals(listOf("Needle Today"), store.search("NEEDLE").map { it.title })
        assertEquals(store.getEntries(), store.search("  "))
        assertEquals(listOf("boundary.example", "today.example", "yesterday.example"), store.uniqueDomains())
        assertTrue(store.groupedByDay().containsKey("Today"))
        assertTrue(store.groupedByDay().containsKey("Yesterday"))
        val snapshot = store.getEntries()
        clock.addAndGet(8 * day)
        assertTrue(store.getEntries().isEmpty())
        assertEquals(3, snapshot.size) // Detached snapshot, not live mutable state.
    }

    @Test(timeout = 10000) fun corruptOrOversizedLoadIsExplicitlyFailedAndDoesNotDropNewEvents() {
        val dir = Files.createTempDirectory("browser-history-corrupt")
        try {
            val path = dir.resolve(BrowserHistoryStore.FILENAME)
            for (oversized in listOf(false, true)) {
                if (oversized) RandomAccessFile(path.toFile(), "rw").use {
                    it.setLength(BrowserHistoryStore.MAX_FILE_BYTES.toLong() + 1)
                } else Files.writeString(path, "not JSON")
                val store = BrowserHistoryStore(BrowserHistoryStore.FileBackend { dir }, { now })
                store.record("https://accepted.example/", "accepted")
                drain(store)
                assertEquals(BrowserHistoryStore.LoadState.FAILED, store.loadState)
                assertEquals(listOf("accepted"), store.getEntries().map { it.title })
                assertEquals(1, JSONArray(Files.readString(path)).length())
            }
        } finally { deleteDirectory(dir) }
    }

    @Test(timeout = 10000) fun blockedOrFailedWriteNeverBlocksRecordOrCloseAndIsNotRetried() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicLong()
        val backend = object : BrowserHistoryStore.Backend {
            override fun read(): String? = null
            override fun write(json: String) {
                attempts.incrementAndGet()
                writing.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw IOException("must not be logged with history")
            }
        }
        val store = BrowserHistoryStore(backend, { now })
        try {
            store.record("https://first.example/", "first")
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            store.record("https://second.example/", "second")
            store.close() // Write still blocked; accepted events may persist after this returns.
            assertEquals(2, store.getEntries().size)
        } finally {
            release.countDown()
            drain(store)
        }
        assertTrue(store.persistenceFailed)
        assertEquals(2L, attempts.get()) // One attempt per admitted mutation, no retry loop.
    }

    private fun deleteDirectory(dir: Path) {
        Files.walk(dir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
