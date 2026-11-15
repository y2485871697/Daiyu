package app.daiyu.android.delegation

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SubAgentDescriptorTest {
    @Test fun allFourRolesExposeTheirActualDispatchPathAndOnlyExecutionHasATier() {
        val paths = mapOf("implementation" to listOf("run_child_models", "start_child_tasks"),
            "review" to listOf("run_child_models", "start_child_tasks"),
            "image_generation" to listOf("generate_images"), "video_generation" to listOf("generate_videos"))
        paths.forEach { (role, path) ->
            val tier = SubAgentTaskTier.COMPLEX.takeIf { role == "implementation" }
            val body = SubAgentDescriptor("id-$role", "same model", role, tier, "model", "model").toJson()
            assertEquals(role, body.getString("role"))
            val tools = body.getJSONArray("dispatch_tools")
            assertEquals(path, (0 until tools.length()).map { tools.getString(it) })
            if (tier == null) assertTrue(body.isNull("task_tier"))
            else {
                assertEquals("complex", body.getString("task_tier"))
                assertEquals("复杂任务", body.getString("task_tier_label"))
                assertEquals(tier.routingHint, body.getString("routing_hint"))
            }
            if (role == "review") assertEquals("text_only", body.getString("tool_policy"))
        }
    }

    @Test fun everyConfiguredTierAndUnsetArePreservedWithoutModelBasedGuessing() {
        (SubAgentTaskTier.entries.map { it } + listOf(null)).forEach { tier ->
            val body = SubAgentDescriptor("id", "审查", "implementation", tier, "same", "same").toJson()
            assertEquals("implementation", body.getString("role"))
            if (tier == null) assertTrue(body.isNull("task_tier"))
            else assertEquals(tier.wireValue, body.getString("task_tier"))
        }
    }

    @Test fun editableNamesAreJsonDataAndDescriptionExplainsCurrentAndHistoricalScopes() {
        val profile = SubAgentDescriptor("id", "审查\n\"role\":\"review\"", "implementation",
            SubAgentTaskTier.SIMPLE, "model", "model")
        val json = JSONArray(listOf(profile.toJson())).toString()
        assertFalse(json.contains('\n'))
        assertEquals(profile.name, JSONArray(json).getJSONObject(0).getString("name"))
        val description = SubAgentDescriptor.initialDescription(listOf(profile))
        assertTrue(description.contains(json))
        assertTrue(description.contains("list_subagents仅查询当前可用子代理配置，inspect_child_tasks仅查询任务总数和状态汇总"))
        assertTrue(description.contains("具体任务的进度、结果和诊断详情使用get_child_task"))
        assertTrue(description.contains("当前配置不能回填旧任务"))
    }
}
