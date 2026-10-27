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
        assertEquals(listOf("执行命令", "终端输出"), body.documents.map { it.title })
        assertEquals("/tmp\n/tmp", body.documents.last().text)
        assertTrue(body.fields.isEmpty())
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

    @Test fun terminalShowsDecodedCommandAndOnlyOneOutputWithoutTransportMirrors() {
        val command = "ls -la /data/data/app.daiyu.android/files/workspace\nprintf '中文\\n'\nprintf \"quoted\""
        val args = org.json.JSONObject().put("command", command).toString().replace("/", "\\/")
        val stdout = "total 6\n中文\\n\n"
        val payload = org.json.JSONObject().put("ok", true).put("stdout", stdout).put("stderr", "error line")
            .put("output", "ignored mirror").put("log", "ignored log").put("stdout_base64", "ignored base64").toString()
        val body = toolBodyLayout("terminal", ToolCategoryDetails.project("terminal", args, payload, "terminal_exec"))
        assertEquals(listOf("执行命令", "终端输出"), body.documents.map { it.title })
        assertEquals(command, body.documents.first().text)
        assertEquals(stdout + "\nerror line", body.documents.last().text)
        assertTrue(body.documents.all { it.code })
        assertTrue(body.fields.isEmpty()); assertTrue(body.inputs.isEmpty()); assertNull(body.prose)
    }

    @Test fun shizukuUsesActualScriptAndOutputFields() {
        val script = "printf '中文'\nprintf 'literal\\n'"
        val args = org.json.JSONObject().put("script", script).toString()
        val body = toolBodyLayout("terminal", ToolCategoryDetails.project("terminal", args,
            """{"ok":true,"backend":"shizuku","output":"中文\n"}""", "shizuku_execute"))
        assertEquals(listOf("执行命令", "终端输出"), body.documents.map { it.title })
        assertEquals(script, body.documents.first().text)
        assertEquals("中文\n", body.documents.last().text)
    }

    @Test fun nonTerminalCategoriesNeverAcquireTerminalLabelsFromPayloadShape() {
        (ToolCardPresentations.categories - setOf("terminal", "background_job") + "future_category").forEach { category ->
            val body = toolBodyLayout(category, ToolCategoryDetails.project(category,
                """{"command":"not a terminal command","script":"not a terminal script","id":"mem-02"}""",
                """{"text":"normal result","stdout":"not terminal output","output":"ordinary field"}""", "memory_get"))
            assertFalse(category, (body.documents + body.inputs + listOfNotNull(body.prose)).any {
                it.title in setOf("执行命令", "终端输入", "终端输出")
            })
        }
    }

    @Test fun readAndFrozenPlanCardsDoNotInventCommandsFromIds() {
        for ((category, name, args) in listOf(Triple("terminal", "terminal_read", """{"job_id":"job-1"}"""),
            Triple("terminal", "terminal_pty_read", """{"read_id":"read-1"}"""),
            Triple("terminal", "terminal_pty_step", """{"step_id":"step-1"}"""),
            Triple("background_job", "terminal_job_start", "{}"), Triple("background_job", "terminal_job_read", "{}"))) {
            val body = toolBodyLayout(category, ToolCategoryDetails.project(category, args, """{"stdout":"page"}""", name))
            assertEquals(name, listOf("终端输出"), body.documents.map { it.title })
            assertEquals("page", body.documents.single().text)
        }
    }

    @Test fun explicitEmptyOutputAndUnknownReceiptRemainDifferent() {
        val args = """{"command":"true"}"""
        val empty = toolBodyLayout("terminal", ToolCategoryDetails.project("terminal", args,
            """{"ok":true,"stdout":"","stderr_merged":true}""", "terminal_exec"))
        assertEquals(listOf("执行命令", "终端输出"), empty.documents.map { it.title })
        assertEquals("", empty.documents.last().text); assertNull(empty.prose)
        val waiting = toolBodyLayout("terminal", ToolCategoryDetails.project("terminal", args, null, "terminal_exec"))
        assertEquals(listOf("执行命令"), waiting.documents.map { it.title })
        assertEquals("等待执行回执", waiting.prose!!.text)
        val notExecuted = toolBodyLayout("terminal", ToolCategoryDetails.project("terminal", args,
            """{"ok":false,"executed":false,"error":"not_authorized"}""", "terminal_exec"))
        assertEquals("本次调用未完成", notExecuted.prose!!.text)
        assertEquals(listOf("执行命令"), notExecuted.documents.map { it.title })
    }

    @Test fun terminalFailureKeepsDiagnosticsButPrivacyStillBlocksEverything() {
        val args = """{"command":"missing-file"}"""
        val failed = toolBodyLayout("terminal", ToolCategoryDetails.project("terminal", args,
            """{"ok":false,"stderr":"not found","exit_code":1}""", "terminal_exec"))
        assertEquals(listOf("执行命令", "终端输出"), failed.documents.map { it.title })
        assertEquals("not found", failed.documents.last().text)
        assertEquals("本次调用未完成", failed.prose!!.text)
        for (payload in listOf("""{"privacy_redacted":true,"stdout":"PRIVATE"}""",
            """{"data":{"status":"private_result_withheld","stdout":"PRIVATE"}}""")) {
            val private = toolBodyLayout("terminal", ToolCategoryDetails.project("terminal", args, payload, "terminal_exec"))
            assertTrue(private.documents.isEmpty()); assertTrue(private.inputs.isEmpty())
            assertEquals("结果未获展示授权", private.prose!!.text)
        }
    }

    @Test fun largeTerminalEnvelopeAndMalformedReceiptNeverExposeTransportJson() {
        val payload = org.json.JSONObject().put("stdout", "line\n".repeat(20000))
            .put("stdout_base64", "A".repeat(140000)).toString()
        assertTrue(payload.length > 131072)
        val large = toolBodyLayout("terminal", ToolCategoryDetails.project("terminal", "{}", payload, "terminal_read"))
        assertEquals("终端输出", large.documents.single().title)
        assertTrue(large.documents.single().text.startsWith("line\n"))
        assertFalse(large.documents.single().text.contains("stdout_base64"))
        for (invalid in listOf("{broken json", "{\"stdout\":\"" + "x".repeat(1048576))) {
            val body = toolBodyLayout("terminal", ToolCategoryDetails.project("terminal", "{}", invalid, "terminal_read"))
            assertTrue(body.documents.isEmpty())
            assertEquals("终端回执无法解析，未展示原始数据", body.prose!!.text)
        }
    }

    @Test fun terminalRedactionSurvivesFlatteningAndDoesNotEchoSecrets() {
        val body = toolBodyLayout("terminal", ToolCategoryDetails.project("terminal",
            """{"command":"echo PRIVATE_VALUE","api_key":"PRIVATE_VALUE"}""",
            """{"stdout":"PRIVATE_VALUE","stderr":"token=secret-value"}""", "terminal_exec"))
        val text = body.documents.joinToString { it.text }
        assertFalse(text.contains("PRIVATE_VALUE")); assertFalse(text.contains("secret-value"))
        assertTrue(text.contains("[已隐藏]"))
    }

    @Test fun specificTerminalFailuresRemainVisibleWithEmptyOrNonemptyOutput() {
        for (category in listOf("terminal", "background_job")) for (stdout in listOf("", "diagnostic")) {
            for ((status, message) in listOf("permission_denied" to "缺少本次操作所需权限",
                "unsupported" to "设备不支持本次操作", "unavailable" to "本次读取不可用")) {
                val payload = org.json.JSONObject().put("ok", false).put("status", status).put("stdout", stdout).toString()
                val body = toolBodyLayout(category, ToolCategoryDetails.project(category,
                    """{"command":"true"}""", payload, "terminal_exec"))
                assertEquals(listOf("执行命令", "终端输出"), body.documents.map { it.title })
                assertEquals(stdout, body.documents.last().text)
                assertEquals(message, body.prose!!.text)
            }
        }
    }

}
