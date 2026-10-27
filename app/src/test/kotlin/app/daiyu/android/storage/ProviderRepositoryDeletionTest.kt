package app.daiyu.android.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProviderRepositoryDeletionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val file get() = File(temporary.root, "registry/state.bin")
    private fun repository() = ProviderRepository(ProviderRegistry(file)) { error("Deletion must not resolve credentials or model stores") }
    private inline fun <reified T : Throwable> rejects(block: () -> Unit) {
        try { block() } catch (error: Throwable) {
            if (error is T) return
            throw error
        }
        fail("Expected ${T::class.java.simpleName}")
    }
    private fun populated(): ProviderRegistrySnapshot {
        val repo = repository()
        val first = repo.register(repo.snapshot().revision, "First")
        return repo.register(first.revision, "Second")
    }
    @Test fun deletesSeveralInOneRevisionAndPreservesUnselectedOrder() {
        val before = populated()
        val selected = before.providers.drop(1).map { it.slotId }.toSet()
        var admitted = 0
        val after = repository().deleteSelected(before.revision, selected) { admitted++ }
        assertEquals(before.revision + 1, after.revision)
        assertEquals(1, admitted)
        assertEquals(before.providers.take(1), after.providers)
        assertEquals(before.activeSlotId, after.activeSlotId)
        assertEquals(after, repository().snapshot())
    }
    @Test fun deletingCurrentClearsSelectionWithoutSwitching() {
        val before = populated()
        val selected = before.providers.take(2).map { it.slotId }.toSet()
        val after = repository().deleteSelected(before.revision, selected)
        assertNull(after.activeSlotId)
        assertEquals(before.providers.drop(2), after.providers)
    }
    @Test fun deletingEverythingStaysEmptyAfterReopen() {
        val before = populated()
        val after = repository().deleteSelected(before.revision, before.providers.map { it.slotId }.toSet())
        assertTrue(after.providers.isEmpty())
        assertNull(after.activeSlotId)
        assertEquals(before.revision + 1, after.revision)
        assertEquals(after, repository().snapshot())
    }
    @Test fun staleRevisionNeverCallsAdmissionOrDeletesAnything() {
        val before = populated()
        var admitted = false
        rejects<StaleProviderRegistryException> {
            repository().deleteSelected(before.revision - 1, setOf("primary")) { admitted = true }
        }
        assertFalse(admitted)
        assertEquals(before, repository().snapshot())
    }
    @Test fun missingTargetRejectsTheEntireBatch() {
        val before = populated()
        rejects<IllegalStateException> {
            repository().deleteSelected(before.revision, setOf(before.providers[1].slotId, "missing"))
        }
        assertEquals(before, repository().snapshot())
    }
    @Test fun revokedAdmissionNeverPublishesPartialDeletion() {
        val before = populated()
        rejects<IllegalStateException> {
            repository().deleteSelected(before.revision, before.providers.map { it.slotId }.toSet()) { error("revoked") }
        }
        assertEquals(before, repository().snapshot())
    }
    @Test fun emptySelectionDoesNotPublishAnEmptyChange() {
        val before = populated()
        var admitted = false
        rejects<IllegalArgumentException> {
            repository().deleteSelected(before.revision, emptySet()) { admitted = true }
        }
        assertFalse(admitted)
        assertEquals(before, repository().snapshot())
    }
    @Test fun existingSingleDeleteRetainsItsContract() {
        val before = populated()
        val after = repository().delete(before.revision, "primary")
        assertEquals(before.providers.drop(1), after.providers)
        assertNull(after.activeSlotId)
        assertEquals(before.revision + 1, after.revision)
    }
}
