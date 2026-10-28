package app.daiyu.android.workspace

import org.junit.Assert.*
import org.junit.Test

class ConversationWorkspacesTest {
    private val first = ConversationWorkspaceBinding.Private("/private/first")
    private val second = ConversationWorkspaceBinding.Private("/private/second")
    private val rootfs = ConversationWorkspaceBinding.Rootfs("debian", "generation-1", "/project")

    @Test fun addingAndSelectingAreIndependentOfOtherChoices() {
        val original = ConversationWorkspaces(listOf(first), 0)
        val added = original.add(second).add(rootfs)
        assertEquals(listOf(first, second, rootfs), added.bindings)
        assertEquals(rootfs, added.active)
        assertEquals(second, added.select(1).active)
        assertEquals(listOf(first), original.bindings)
        assertEquals(first, original.active)
    }

    @Test fun addingExistingIdentitySelectsWithoutDuplicating() {
        val choices = ConversationWorkspaces(listOf(first, second), 1).add(first)
        assertEquals(2, choices.bindings.size)
        assertEquals(0, choices.activeIndex)
    }

    @Test fun removingEarlierChoicesPreservesActiveIdentity() {
        val choices = ConversationWorkspaces(listOf(first, second, rootfs), 1).remove(setOf(0))
        assertEquals(second, choices.active)
        assertEquals(0, choices.activeIndex)
    }

    @Test fun removingCurrentSelectsFirstRemainingAndAllRemovedUsesDefault() {
        val choices = ConversationWorkspaces(listOf(first, second, rootfs), 1)
        assertEquals(first, choices.remove(setOf(1)).active)
        val empty = choices.remove(setOf(0, 1, 2))
        assertTrue(empty.bindings.isEmpty())
        assertEquals(-1, empty.activeIndex)
        assertEquals(ConversationWorkspaceBinding.Private(), empty.active)
        assertEquals(rootfs, empty.add(rootfs).active)
    }

    @Test fun rootfsGenerationIsPartOfIdentity() {
        val otherGeneration = rootfs.copy(generation = "generation-2")
        assertEquals(2, ConversationWorkspaces(listOf(rootfs), 0).add(otherGeneration).bindings.size)
    }

    @Test fun removingMissingDirectoriesOnlyChangesDescriptions() {
        val stale = ConversationWorkspaceBinding.Private("/missing/never-created")
        val choices = ConversationWorkspaces(listOf(stale, rootfs), 0)
        assertEquals(rootfs, choices.remove(setOf(0)).active)
        assertTrue(choices.remove(setOf(0, 1)).bindings.isEmpty())
    }

    @Test fun replacingInactiveChoiceKeepsActiveAndOtherPathsUnchanged() {
        val replacement = ConversationWorkspaceBinding.Private("/private/new-path")
        val choices = ConversationWorkspaces(listOf(first, second, rootfs), 1)
        val edited = choices.replace(0, replacement)
        assertEquals(listOf(replacement, second, rootfs), edited.bindings)
        assertEquals(second, edited.active)
        assertEquals(1, edited.activeIndex)
        assertEquals(first, choices.bindings[0])
    }

    @Test fun replacingCurrentChangesOnlyItsBindingAndRejectsAnotherExistingPath() {
        val choices = ConversationWorkspaces(listOf(first, second, rootfs), 1)
        val replacement = ConversationWorkspaceBinding.Private("/private/new-path")
        assertEquals(replacement, choices.replace(1, replacement).active)
        try { choices.replace(1, first); fail("duplicate directory accepted") }
        catch (failure: IllegalArgumentException) { assertEquals("workspace_duplicate", failure.message) }
        assertEquals(choices, choices.replace(1, second))
    }

    @Test fun staleIndicesDuplicatesAndInvalidActiveAreRejected() {
        val actions = listOf<() -> Any>(
            { ConversationWorkspaces(listOf(first), 1) },
            { ConversationWorkspaces(emptyList(), 0) },
            { ConversationWorkspaces(listOf(first, first), 0) },
            { ConversationWorkspaces(listOf(first), 0).select(-1) },
            { ConversationWorkspaces(listOf(first), 0).remove(setOf(1)) },
            { ConversationWorkspaces(listOf(first), 0).remove(emptySet()) },
        )
        for (action in actions) {
            try { action(); fail("invalid selection accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
