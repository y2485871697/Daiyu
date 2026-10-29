package app.daiyu.android.assistants

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class AssistantRunContextTest {
    @Test fun everyCapturedAssistantKeepsOneExecutionPolicyRegardlessOfPersonalityOrMemory() {
        val policy = AssistantRunContext.EXECUTION_POLICY
        listOf(AssistantProfile("a", "A", "custom"), AssistantProfile("b", "B", "", memoryEnabled = false)).forEach { profile ->
            val prompt = Store(profile).capture(profile.id, "memory").systemPrompt
            assertTrue(prompt.endsWith(policy)); assertEquals(1, prompt.split(policy).size - 1)
        }
        listOf("Shizuku", "uiautomator", "结果未知", "只读", "Root 通道失败", "先观察当前窗口").forEach {
            assertTrue(policy.contains(it))
        }
    }

    @Test fun explicitBindingNeverReadsGlobalSelection() {
        val first = AssistantProfile("first", "第一位", "第一人格")
        val second = AssistantProfile("second", "第二位", "第二人格")
        val store = Store(first, second)
        val context = store.capture("second", "最终合并记忆")
        assertEquals("second", context.assistantId)
        assertEquals("第二位", context.name)
        assertEquals("第二人格", context.prompt)
        assertEquals("最终合并记忆", context.resolvedMemory)
        assertEquals(AssistantPrompt.build(second.name, second.prompt) + "\n\n记忆：\n最终合并记忆\n\n" + AssistantRunContext.EXECUTION_POLICY, context.systemPrompt)
    }

    @Test fun missingBlankAndDeletedBindingsFailWithoutFallback() {
        val store = Store(AssistantProfile("first", "第一位", ""), AssistantProfile("deleted", "已删除", ""))
        listOf(null, "", "  ").forEach { id ->
            assertThrows(MissingAssistantBindingException::class.java) { store.capture(id, "") }
        }
        assertThrows(AssistantNotFoundException::class.java) { store.capture("unknown", "") }
        store.delete("deleted")
        val failure = assertThrows(AssistantNotFoundException::class.java) { store.capture("deleted", "") }
        assertEquals("deleted", failure.assistantId)
    }

    @Test fun capturedValuesSurviveEditsDeletionAndMutableSourceLists() {
        val skills = mutableListOf("skill-a", "skill-b")
        val original = AssistantProfile("bound", "旧名", "旧人格", "old.png", enabledSkillIds = skills)
        val store = Store(original)
        var memory = "  最终记忆\n"
        val context = store.capture(original.id, memory)
        val expectedPrompt = context.systemPrompt
        skills.clear()
        store.save(original.copy(name = "新名", prompt = "新人格", avatarFileName = "new.png", memoryEnabled = false))
        memory = "新记忆"
        store.delete(original.id)
        assertEquals("旧名", context.name)
        assertEquals("旧人格", context.prompt)
        assertEquals("old.png", context.avatarFileName)
        assertTrue(context.memoryEnabled)
        assertEquals("  最终记忆\n", context.resolvedMemory)
        assertNotEquals(memory, context.resolvedMemory)
        assertEquals(expectedPrompt, context.systemPrompt)
        assertEquals(listOf("skill-a", "skill-b"), context.enabledSkillIds)
        assertThrows(UnsupportedOperationException::class.java) {
            (context.enabledSkillIds as MutableList<String>).add("injected")
        }
    }

    @Test fun disabledMemoryIsNotRetainedOrInjected() {
        val profile = AssistantProfile("bound", "名称", "人格", memoryEnabled = false)
        val context = Store(profile).capture(profile.id, "不能发送的记忆")
        assertFalse(context.memoryEnabled)
        assertEquals("", context.resolvedMemory)
        assertEquals(AssistantPrompt.build(profile.name, profile.prompt) + "\n\n" + AssistantRunContext.EXECUTION_POLICY, context.systemPrompt)
    }

    @Test fun blankPersonalityUsesExistingIdentityAndEmptyMemoryAddsNothing() {
        val profile = AssistantProfile("bound", " ", " ", enabledSkillIds = emptyList())
        val context = AssistantRunContext.capture(Store(profile), profile.id, "")
        assertEquals(AssistantPrompt.EMPTY_PROMPT + "\n\n" + AssistantRunContext.EXECUTION_POLICY, context.systemPrompt)
        assertEquals(emptyList<String>(), context.enabledSkillIds)
    }

    private class Store(vararg profiles: AssistantProfile) : AssistantStore {
        override val assistants = MutableStateFlow(profiles.toList())
        // Any implicit global fallback fails even when it would select a valid profile.
        override val selectedId: MutableStateFlow<String> get() = error("capture must not read global selection")
        override fun save(profile: AssistantProfile): AssistantProfile {
            assistants.value = assistants.value.filterNot { it.id == profile.id } + profile
            return profile
        }
        override fun delete(id: String) { assistants.value = assistants.value.filterNot { it.id == id } }
        override fun select(id: String) = error("capture must not select an assistant")
    }
}
