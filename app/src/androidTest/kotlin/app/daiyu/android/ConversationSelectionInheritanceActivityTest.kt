package app.daiyu.android

import app.daiyu.android.assistants.create
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
}
