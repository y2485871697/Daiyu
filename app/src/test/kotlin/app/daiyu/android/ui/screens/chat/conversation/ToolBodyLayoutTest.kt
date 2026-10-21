package app.daiyu.android.ui.screens.chat.conversation

import org.junit.Assert.*
import org.junit.Test

class ToolBodyLayoutTest {
    @Test fun allCategoriesAndUnknownFallbackKeepOneReadableReceipt() {
        (ToolCardPresentations.categories + "future_category").forEach { category ->
            val details = ToolCategoryDetails.project(category, "{}", "readable receipt")
            val body = toolBodyLayout(category, details)
            val texts = body.documents.map { it.text } + listOfNotNull(body.prose?.text)
            if (category != "user_question") assertEquals(category, 1, texts.count { it == "readable receipt" })
        }
    }

    @Test fun fieldsAndInputsFollowCategoryLayoutsRatherThanOneGenericStack() {
        val inputs = """{"query":"needle","id":"sample","path":"/tmp/report","url":"https://example.com/page","prompt":"draw a tree","text":"hello"}"""
        val expected = mapOf(
            "memory" to setOf("检索条件"), "skills" to setOf("技能查询", "资源"),
            "files" to setOf("路径"), "browser" to setOf("搜索条件", "页面 · 已去除认证和查询参数"),
            "generation" to setOf("提示摘要", "文本摘要"), "context" to setOf("历史查询"),
        )
        expected.forEach { (category, titles) ->
            val body = toolBodyLayout(category, ToolCategoryDetails.project(category, inputs, "receipt"))
            assertEquals(category, titles, body.inputs.map { it.title }.toSet())
        }
        val time = ToolCategoryDetails.project("device_read", "{}",
            """{"ok":true,"data":{"local_iso":"2026-10-21T15:36:32.861853+08:00[Asia/Shanghai]","timezone":"Asia/Shanghai","epoch_ms":1}}""")
        val body = toolBodyLayout("device_read", time)
        assertTrue(body.fields.isEmpty())
        assertTrue(body.inputs.isEmpty())
        assertTrue(body.documents.isEmpty())
        assertEquals("2026-10-21 15:36:32\nAsia/Shanghai · UTC+08:00", body.prose!!.text)
    }

    @Test fun terminalOrdersCommandFirstAndDoesNotDuplicateOutputAsProse() {
        val details = ToolCategoryDetails.project("terminal", """{"command":"pwd","cwd":"/tmp"}""",
            """{"stdout":"/tmp","stderr":"/tmp","exit_code":0}""")
        val body = toolBodyLayout("terminal", details)
        assertEquals(listOf("命令", "标准输出", "标准错误"), body.documents.map { it.title })
        assertNull(body.prose)
        assertFalse(body.plainDocuments)
    }

    @Test fun privacyRestrictionStopsAllOrdinaryBodyGroups() {
        ToolCardPresentations.categories.forEach { category ->
            val details = ToolCategoryDetails.project(category, """{"query":"needle","command":"pwd"}""",
                """{"privacy_redacted":true,"status":"private_result_withheld","text":"PRIVATE"}""")
            val body = toolBodyLayout(category, details)
            assertTrue(category, body.fields.isEmpty())
            assertTrue(category, body.inputs.isEmpty())
            assertTrue(category, body.documents.isEmpty())
            assertEquals("结果未获展示授权", body.prose!!.text)
        }
    }

    @Test fun skillsUseRealSchemaIdentityAndMemoryAliasesDoNotDuplicateBody() {
        val skills = ToolCategoryDetails.project("skills", """{"id":"sample","path":"references/guide.md"}""",
            """{"content":"skill body"}""", "skills_read")
        val body = toolBodyLayout("skills", skills)
        assertTrue(body.fields.contains(ToolFieldUi("技能", "sample")))
        assertEquals("资源", body.inputs.single().title)
        assertEquals("技能说明", body.documents.single().title)
        val memory = ToolCategoryDetails.project("memory", "{}", """{"text":"same body","content":"same body"}""")
        assertEquals(1, toolBodyLayout("memory", memory).documents.count { it.text == "same body" })
    }
    @Test fun contextKeepsReportedMetricsAndNeverReplacesWaitingWithRequestedScope() {
        val waiting = toolBodyLayout("context", ToolCategoryDetails.project("context", """{"session_id":"s1"}""", null))
        assertEquals("等待执行回执", waiting.prose!!.text)
        val metrics = toolBodyLayout("context", ToolCategoryDetails.project("context", "{}",
            """{"input_tokens":12,"output_tokens":3,"cached_tokens":0,"request_count":1}"""))
        assertEquals("请求数：1\n已报告输入：12\n已报告输出：3\n已报告缓存：0", metrics.prose!!.text)
    }

    @Test fun unselectedInputDocumentsNeverMasqueradeAsReturnedBody() {
        val image = toolBodyLayout("image_read", ToolCategoryDetails.project("image_read",
            """{"path":"/tmp/a.png","question":"what is shown?"}""", """{"summary":"a cat"}"""))
        assertTrue(image.inputs.isEmpty())
        assertEquals(listOf("a cat"), image.documents.map { it.text })
        val workspace = toolBodyLayout("workspace", ToolCategoryDetails.project("workspace",
            """{"path":"/tmp/a","old_text":"old","new_text":"new"}""", """{"diff":"-old\n+new"}"""))
        assertEquals(listOf("差异"), workspace.documents.map { it.title })
    }

}
