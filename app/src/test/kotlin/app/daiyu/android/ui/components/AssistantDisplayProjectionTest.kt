package app.daiyu.android.ui.components

import app.daiyu.android.assistants.AssistantProfile
import org.junit.Assert.*
import org.junit.Test

class AssistantDisplayProjectionTest {
    @Test fun projectsExactStoreIdentityNameAndAvatarReferenceWithoutInventedDefaults() {
        val profile = AssistantProfile("real-id", "Actual name", "private prompt", "avatar.png")
        var requested: String? = null
        val display = profile.toAssistantDisplay { file -> requested = file; null }
        assertEquals(profile.id, display.id)
        assertEquals(profile.name, display.name)
        assertEquals(profile.avatarFileName, requested)
        assertNull(display.avatar)
    }

    @Test fun preservesProfileOrderAndLeavesSelectionToStore() {
        val profiles = listOf(AssistantProfile("second", "Two", ""), AssistantProfile("first", "One", ""))
        val displays = profiles.toAssistantDisplays(null)
        assertEquals(profiles.map { it.id }, displays.map { it.id })
        assertEquals(profiles.map { it.name }, displays.map { it.name })
        assertTrue(emptyList<AssistantProfile>().toAssistantDisplays(null).isEmpty())
    }
}
