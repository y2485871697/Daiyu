package app.daiyu.android.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProviderRegistryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun stateFile() = File(temporary.root, "registry/state.bin")
    private fun provider(name: String = "额外提供商", enabled: Boolean = false) =
        RegisteredProvider(UUID.randomUUID().toString(), name, enabled)
    private inline fun <reified T : Throwable> rejects(block: () -> Unit) {
        try { block() } catch (error: Throwable) {
            if (error is T) return
            throw error
        }
        fail("Expected ${T::class.java.simpleName}")
    }

    @Test fun initializesPrimaryOnceAndReopensSameSnapshot() {
        val file = stateFile()
        val first = ProviderRegistry(file).snapshot()
        assertEquals(0L, first.revision)
        assertEquals(listOf(RegisteredProvider("primary", "主提供商", true)), first.providers)
        assertEquals("primary", first.activeSlotId)
        assertEquals(first, ProviderRegistry(file).snapshot())
    }

    @Test fun changesPersistAndReturnedListsCannotMutateRegistry() {
        val file = stateFile()
        val registry = ProviderRegistry(file)
        val addition = provider()
        val next = registry.change(0) { it.copy(providers = it.providers + addition) }
        assertEquals(1L, next.revision)
        assertEquals(next, ProviderRegistry(file).snapshot())
        rejects<UnsupportedOperationException> { (next.providers as MutableList).clear() }
        assertEquals(2, registry.snapshot().providers.size)
    }

    @Test fun deletingEverythingDoesNotResurrectPrimaryOnReopen() {
        val file = stateFile()
        val empty = ProviderRegistry(file).change(0) { it.copy(providers = emptyList(), activeSlotId = null) }
        assertEquals(empty, ProviderRegistry(file).snapshot())
        assertTrue(empty.providers.isEmpty())
        assertNull(empty.activeSlotId)
    }

    @Test fun staleWriteNeverInvokesUpdateAndPreservesPublishedState() {
        val file = stateFile()
        val first = ProviderRegistry(file)
        val second = ProviderRegistry(file)
        val next = first.change(0) { it.copy(providers = it.providers + provider()) }
        var invoked = false
        rejects<StaleProviderRegistryException> { second.change(0) { invoked = true; it } }
        assertFalse(invoked)
        assertEquals(next, second.snapshot())
    }

    @Test fun failedUpdateAndInvalidSnapshotDoNotPublish() {
        val registry = ProviderRegistry(stateFile())
        val initial = registry.snapshot()
        rejects<IllegalStateException> { registry.change(0) { error("cancelled") } }
        rejects<IllegalArgumentException> { registry.change(0) { it.copy(activeSlotId = UUID.randomUUID().toString()) } }
        rejects<IllegalArgumentException> { registry.change(0) { it.copy(providers = it.providers + it.providers.single()) } }
        assertEquals(initial, registry.snapshot())
    }

    @Test fun disabledProviderCannotRemainActive() {
        val registry = ProviderRegistry(stateFile())
        rejects<IllegalArgumentException> {
            registry.change(0) { it.copy(providers = it.providers.map { row -> row.copy(enabled = false) }) }
        }
        assertEquals("primary", registry.snapshot().activeSlotId)
    }

    @Test fun boundsNamesIdsAndProviderCountBeforePublishing() {
        val registry = ProviderRegistry(stateFile())
        listOf("", " ", " leading", "trailing ", "a\nb", "a\u007fb", "x".repeat(201)).forEach { name ->
            rejects<IllegalArgumentException> {
                registry.change(0) { it.copy(providers = it.providers + provider(name)) }
            }
        }
        rejects<IllegalArgumentException> {
            registry.change(0) { it.copy(providers = it.providers + RegisteredProvider("../foreign", "合法名称", false)) }
        }
        rejects<IllegalArgumentException> {
            registry.change(0) { it.copy(providers = it.providers + List(64) { provider() }) }
        }
        val legal = registry.change(0) { it.copy(providers = it.providers + List(63) { provider("界".repeat(200)) }) }
        assertEquals(64, legal.providers.size)
    }

    @Test fun corruptTruncatedAndTrailingDataAreNotReinitialized() {
        val file = stateFile()
        ProviderRegistry(file).snapshot()
        val original = file.readBytes()
        listOf(byteArrayOf(), original.copyOf(original.size - 1), original + byteArrayOf(7),
            original.copyOf().also { it[0] = 0 }).forEach { invalid ->
            file.writeBytes(invalid)
            rejects<Exception> { ProviderRegistry(file).snapshot() }
            assertArrayEquals(invalid, file.readBytes())
        }
    }

    @Test fun oversizedFileIsRejectedWithoutReplacement() {
        val file = stateFile()
        file.parentFile.mkdirs()
        val original = ByteArray(64 * 1024 + 1) { 7 }
        file.writeBytes(original)
        rejects<IllegalArgumentException> { ProviderRegistry(file).snapshot() }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun leftoverPendingDoesNotOverridePublishedState() {
        val file = stateFile()
        val registry = ProviderRegistry(file)
        val initial = registry.snapshot()
        File(file.path + ".pending").writeText("interrupted write")
        assertEquals(initial, ProviderRegistry(file).snapshot())
        val next = registry.change(0) { it.copy(providers = emptyList(), activeSlotId = null) }
        assertEquals(next, ProviderRegistry(file).snapshot())
        assertFalse(File(file.path + ".pending").exists())
    }

    @Test fun canonicalAliasesShareReentrantLock() {
        val file = stateFile()
        val registry = ProviderRegistry(file)
        registry.snapshot()
        val alias = ProviderRegistry(File(file.parentFile, "../registry/state.bin"))
        val actual = registry.access { outer -> alias.access { inner -> assertEquals(outer, inner); inner } }
        assertEquals(actual, registry.snapshot())
    }

    @Test fun concurrentSameRevisionHasOneWinner() {
        val file = stateFile()
        ProviderRegistry(file).snapshot()
        val workers = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val results = List(2) {
                workers.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    try {
                        ProviderRegistry(file).change(0) { old -> old.copy(providers = old.providers + provider()) }
                        true
                    } catch (_: StaleProviderRegistryException) { false }
                }
            }
            start.countDown()
            assertEquals(1, results.count { it.get(10, TimeUnit.SECONDS) })
            val actual = ProviderRegistry(file).snapshot()
            assertEquals(1L, actual.revision)
            assertEquals(2, actual.providers.size)
        } finally { workers.shutdownNow() }
    }
}
