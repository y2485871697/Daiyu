package app.daiyu.android

import app.daiyu.android.assistants.create
import app.daiyu.android.delegation.*
import app.daiyu.android.domain.Conversation
import app.daiyu.android.storage.ConversationModelBinding

class ConversationSelectionInheritanceActivityTest : WiredActivityTestCase() {
    private fun selected() = MainActivity::class.java.getDeclaredField("selected").apply { isAccessible = true }.get(activity) as Conversation?
    private fun generation() = MainActivity::class.java.getDeclaredField("editorGeneration").apply { isAccessible = true }.getLong(activity)
    private fun pickAssistant(id: String, source: Conversation? = selected(), visit: Long = generation()) {
        val method = MainActivity::class.java.declaredMethods.single { it.name.startsWith("selectCurrentConversationAssistant") }
        method.isAccessible = true
        method.invoke(activity, id, source?.id?.value, visit)
    }

    fun testSidebarBindingDoesNotChangeAnotherConversationOrGlobalAndNewChatInheritsIt() {
        val store = fixture.controller.assistantStore
        val global = store.selectedId.value
        val firstAssistant = store.create("first")
        val secondAssistant = store.create("second")
        lateinit var first: Conversation
        lateinit var second: Conversation
        mainThread {
            first = fixture.database.create(firstAssistant.id)
            second = fixture.database.create(secondAssistant.id)
            activity.select(first)
            val visit = generation()
            activity.select(second)
            pickAssistant(firstAssistant.id, first, visit)
            assertEquals(secondAssistant.id, fixture.database.assistantId(second.id))
            activity.select(first)
            pickAssistant(secondAssistant.id)
            assertEquals(secondAssistant.id, selected()?.assistantId)
            assertEquals(secondAssistant.id, fixture.database.assistantId(first.id))
            assertEquals(secondAssistant.id, fixture.database.assistantId(second.id))
            assertEquals(global, store.selectedId.value)
            val inherited = fixture.database.modelBinding(first.id)
            activity.createNewConversation()
            val child = checkNotNull(selected())
            assertEquals(secondAssistant.id, child.assistantId)
            assertEquals(inherited, fixture.database.modelBinding(child.id))
            pickAssistant(firstAssistant.id)
            assertEquals(secondAssistant.id, fixture.database.assistantId(first.id))
        }
        assertEquals(0, fixture.opens.get())
    }
    fun testModelBindingIsReadFromSourceAndSurvivesIndependentChildSelectionAndRecreation() {
        lateinit var source: Conversation
        lateinit var child: Conversation
        lateinit var binding: ConversationModelBinding
        mainThread {
            source = fixture.database.create(fixture.database.selectedAssistantId())
            binding = checkNotNull(fixture.database.modelBinding(source.id))
            activity.select(source)
            activity.createNewConversation()
            child = checkNotNull(selected())
            assertEquals(binding, fixture.database.modelBinding(child.id))
            val other = binding.copy(localModelIdentity = java.util.UUID.randomUUID().toString())
            assertTrue(fixture.database.bindModel(child.id, binding, other))
            assertEquals(binding, fixture.database.modelBinding(source.id))
            activity.select(source)
        }
        activity = recreateActivity(activity)
        awaitControl("source owner restored") { selected()?.id == source.id }
        assertEquals(binding, fixture.database.modelBinding(source.id))
        assertEquals(0, fixture.opens.get())
    }
    private fun childRepository(): ConversationSubAgentPreferences =
        (MainActivity::class.java.getDeclaredField("subAgentRepository\$delegate").apply { isAccessible = true }
            .get(activity) as Lazy<*>).value as ConversationSubAgentPreferences

    fun testNewChatCopiesActualChildConfigurationAndPersistsItWithoutLinkingSourceOrPreset() {
        lateinit var child: Conversation
        lateinit var expected: ConversationSubAgentConfig
        mainThread {
            val repository = childRepository()
            val preset = repository.addPreset("继承测试组")
            val presetOwner = SubAgentConfigKey.Preset(preset.id)
            for (enabled in listOf(false, true)) {
                val source = fixture.database.create(fixture.database.selectedAssistantId())
                val binding = checkNotNull(fixture.database.modelBinding(source.id))
                val original = SubAgentProfile("execution", "组内执行", providerId = binding.providerSlotId,
                    modelId = binding.localModelIdentity, tier = SubAgentTaskTier.COMPLEX, parallelLimit = 0)
                repository.update(presetOwner) { it.copy(profiles = listOf(original)) }
                val sourceOwner = SubAgentConfigKey.Conversation(source.id.value)
                assertTrue(repository.applyPreset(sourceOwner, preset.id) is ConversationSubAgentPreferences.WriteResult.Saved)
                repository.update(sourceOwner) { it.copy(enabled = enabled,
                    profiles = listOf(original.copy(name = "原会话局部修改"), original.copy(id = "review", name = "审查",
                        role = "review", tier = null, parallelLimit = 3))) }
                val inherited = repository.snapshot(sourceOwner)
                activity.select(source)
                activity.createNewConversation()
                child = checkNotNull(selected())
                val childOwner = SubAgentConfigKey.Conversation(child.id.value)
                assertEquals(inherited, repository.snapshot(childOwner))
                assertNotNull(repository.snapshot(childOwner).appliedPresetId)
                assertEquals(enabled, repository.snapshot(childOwner).enabled)
                assertFalse(repository.snapshot(sourceOwner).poolKey(sourceOwner, "execution") ==
                    repository.snapshot(childOwner).poolKey(childOwner, "execution"))
                repository.update(childOwner) { it.copy(profiles = it.profiles.map { card -> card.copy(name = "只改新会话") }) }
                assertEquals(inherited, repository.snapshot(sourceOwner))
                assertEquals("组内执行", repository.snapshot(presetOwner).profiles.single().name)
                expected = repository.snapshot(childOwner)
                activity.select(source)
                assertEquals(inherited, repository.snapshot(sourceOwner))
                activity.select(child)
                assertEquals(expected, repository.snapshot(childOwner))
            }
        }
        activity = recreateActivity(activity)
        awaitControl("inherited child owner restored") { selected()?.id == child.id }
        mainThread { assertEquals(expected, childRepository().snapshot(SubAgentConfigKey.Conversation(child.id.value))) }
        assertEquals(0, fixture.opens.get())
    }

}
