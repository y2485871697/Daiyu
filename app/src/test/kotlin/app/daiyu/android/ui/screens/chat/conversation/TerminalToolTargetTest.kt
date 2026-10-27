package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TerminalToolTargetTest {
    private fun card(result: String?, args: String = "{}", name: String = "terminal_exec") =
        ToolCardPresentations.present(name, args, result)

    @Test fun actualReceiptSeparatesDistributionAndExecutionIdentity() {
        val cases = mapOf(
            """{"backend":"linux:debian","uid":0,"guest_uid":0,"host_root":true}""" to "Debian • root",
            """{"backend":"linux:alpine","uid":1000,"guest_uid":1000,"host_root":false}""" to "Alpine • user",
            """{"backend":"android","uid":0,"root":true,"host_root":true}""" to "Android • root",
            """{"backend":"android","uid":10444,"root":false,"host_root":false}""" to "Android • user",
            """{"backend":"shizuku","uid":2000}""" to "Android • Shizuku",
            """{"backend":"shizuku","uid":0}""" to "Android • Shizuku",
        )
        cases.forEach { (receipt, label) ->
            val value = card(receipt, """{"environment":"other","identity":"root","backend":"fake"}""")
            assertEquals("terminal", value.category)
            assertEquals(label, value.targetLabel)
            assertEquals("执行命令 · $label", value.title)
            assertTrue(value.details.fields.contains(ToolFieldUi("执行环境", label)))
            for (category in listOf("terminal", "background_job")) {
                assertFalse(toolBodyLayout(category, value.details).fields.any { it.label == "执行环境" })
            }
        }
    }

    @Test fun prootGuestRootNeverBecomesAndroidHostRoot() {
        val proot = card("""{"backend":"linux:debian","uid":0,"guest_uid":0,"host_root":false,"host_uid":10444}""")
        assertEquals("Debian • root（PRoot）", proot.targetLabel)
        assertTrue(proot.details.fields.contains(ToolFieldUi("Android 宿主权限", "user")))
        val body = toolBodyLayout(proot.category, proot.details)
        assertEquals(listOf(ToolFieldUi("Android 宿主权限", "user")), body.fields)
        val guestUser = card("""{"backend":"linux:alpine","uid":1000,"guest_uid":1000,"host_root":true,"host_uid":10444}""")
        assertEquals("Alpine • user", guestUser.targetLabel)
        assertTrue(guestUser.details.fields.contains(ToolFieldUi("Android 宿主权限", "root")))
    }

    @Test fun missingReceiptOrUnknownBackendNeverUsesRequestsOrCurrentSettings() {
        val args = """{"backend":"android","environment":"debian","identity":"root","uid":0}"""
        for (receipt in listOf(null, "not-json", "{}", """{"stdout":"backend=android uid=0"}""",
            """{"backend":"unregistered","uid":0}""")) {
            assertNull(card(receipt, args).targetLabel)
            assertEquals("执行命令", card(receipt, args).title)
        }
        assertEquals("Android • 身份未确认", card("""{"backend":"android"}""").targetLabel)
        assertEquals("Debian • 身份未确认", card("""{"backend":"linux:debian"}""").targetLabel)
    }

    @Test fun malformedAndConflictingEvidenceDoesNotDefaultToRoot() {
        for (value in listOf("null", "-1", "0.5", "\"0\"", "true", "2147483648")) {
            assertEquals("Android • 身份未确认", card("""{"backend":"android","uid":$value}""").targetLabel)
        }
        for (payload in listOf("""{"backend":"android","uid":0,"host_root":false}""",
            """{"backend":"android","uid":10444,"root":true}""",
            """{"backend":"android","uid":0,"host_root":true,"root":false}""")) {
            assertEquals("Android • 身份未确认", card(payload).targetLabel)
        }
        assertEquals("Debian • 身份未确认", card("""{"backend":"linux:debian","uid":0,"guest_uid":"0","host_root":true}""").targetLabel)
        assertNull(card("""{"backend":"linux:private-secret-label","uid":0}""").targetLabel)
        assertFalse(card("""{"backend":"linux:private-secret-label","uid":0}""").title.contains("private-secret-label"))
        assertEquals("Debian • 身份未确认", card("""{"backend":"linux:debian","uid":1000,"guest_uid":0,"host_root":true}""").targetLabel)
        assertEquals("Alpine • 身份未确认", card("""{"backend":"linux:alpine","host_root":true}""").targetLabel)
        val forged = card("""{"backend":"android","uid":0}""", name = "unknown_terminal")
        assertEquals("generic", forged.category)
        assertNull(forged.targetLabel)
    }

    @Test fun historicalShizukuUidStillIdentifiesItsDedicatedTransport() {
        assertEquals("Android • Shizuku", card("""{"uid":2000,"output":"old receipt"}""", name = "shizuku_execute").targetLabel)
        assertEquals("Android • Shizuku", card("""{"uid":0}""", name = "shizuku_execute").targetLabel)
        assertNull(card("""{"uid":0}""").targetLabel)
        assertNull(card("""{"uid":10444}""", name = "shizuku_execute").targetLabel)
        for (backend in listOf("null", "3", "{}", "[]", "true")) {
            assertNull(card("""{"backend":$backend,"uid":0}""", name = "shizuku_execute").targetLabel)
        }
    }

    @Test fun processTitlesRequireEveryStepToShareTheSameKnownTarget() {
        fun step(target: String?, category: String = "terminal") = WorkStepUi("call", "执行", "", category = category,
            executionTargetLabel = target)
        assertEquals("Debian • root", ToolCardPresentations.processTitleForSteps(listOf(step("Debian • root"), step("Debian • root"))))
        assertEquals("多环境终端", ToolCardPresentations.processTitleForSteps(listOf(step("Debian • root"), step("Android • user"))))
        assertEquals("终端命令", ToolCardPresentations.processTitleForSteps(listOf(step("Debian • root"), step(null))))
        assertEquals("终端命令", ToolCardPresentations.processTitleForSteps(listOf(step(null), step(null))))
        assertEquals("工具链", ToolCardPresentations.processTitleForSteps(listOf(step("Debian • root"), step(null, "device_read"))))
        assertEquals("设备查询", ToolCardPresentations.processTitleForSteps(listOf(step(null, "device_read"))))
        assertEquals("Android • user", ToolCardPresentations.processTitleForSteps(listOf(step("Android • user", "background_job"))))
        assertEquals("工具链", ToolCardPresentations.processTitleForSteps(emptyList()))
    }

    @Test fun legalEscapedLogPagesKeepTargetLabelsWithoutChangingArgumentLimits() {
        val result = JSONObject().put("backend", "android").put("uid", 10444).put("root", false)
            .put("stdout", "\u0001".repeat(65_536)).put("stdout_base64", "A".repeat(87_384)).toString()
        assertTrue(result.length > 131_072)
        assertTrue(result.length < 524_288)
        val value = card(result, name = "terminal_read")
        assertEquals("Android • user", value.targetLabel)
        assertTrue(value.details.fields.contains(ToolFieldUi("执行环境", "Android • user")))
        val shizuku = JSONObject().put("backend", "shizuku").put("uid", 2000)
            .put("output", "\u0001".repeat(262_144)).put("output_base64", "A".repeat(349_528)).toString()
        assertTrue(shizuku.length > 524_288)
        assertTrue(shizuku.length < 2_097_152)
        val shizukuCard = card(shizuku, name = "shizuku_execute")
        assertEquals("Android • Shizuku", shizukuCard.targetLabel)
        assertTrue(shizukuCard.details.fields.contains(ToolFieldUi("执行环境", "Android • Shizuku")))
        assertTrue(toolBodyLayout(shizukuCard.category, shizukuCard.details).fields.isEmpty())
        assertNull(TerminalToolTarget.fromResult("terminal_read", " ".repeat(2_097_153)))
        val oversizedBrowserArgs = JSONObject().put("action", "navigate").put("url", "x".repeat(65_537)).toString()
        assertEquals("generic", ToolCardPresentations.present("browser_write", oversizedBrowserArgs, "{}").category)
    }

    @Test fun mapperKeepsOriginalOwnerStateAndToolIdentityWithTargetLabels() {
        for (state in WorkStepState.entries) for (mixed in listOf(false, true)) {
            val owner = MessageImageOwner("chat", "message")
            val invocation = ToolInvocation(ToolCallId("original-call"), "terminal_exec", """{"command":"pwd","environment":"android"}""")
            val blocks = buildList {
                if (mixed) add(MessageBlock.Image(MessageImageReference(owner, "00000000-0000-0000-0000-000000000001",
                    "image/png", 10, "b".repeat(64), 2, 2)))
                add(MessageBlock.Tool(invocation, """{"ok":true,"backend":"android","uid":10444,"exit_code":0,"stdout":"/workspace"}"""))
            }
            val message = Message(MessageId("message"), ConversationId("chat"), RunId("run"), Message.ASSISTANT_ROLE, blocks, RunStatus.COMPLETED)
            val turn = ChatTurnMapper.map(listOf(message), toolState = { _, _ -> state }).single() as ChatTurnUi.Assistant
            val step = if (mixed) turn.content.filterIsInstance<MessageContentUi.Tool>().single().step
                else turn.workProcess!!.segments.flatMap { it.steps }.single()
            assertEquals("执行命令 · Android • user", step.name)
            assertEquals("Android • user", step.executionTargetLabel)
            assertEquals("terminal", step.category)
            assertEquals("terminal", step.operationGlyph)
            assertEquals(state, step.state)
            assertEquals(WorkStepOwnerUi("chat", "run", "message"), step.owner)
            assertEquals(invocation.id.value, step.toolCallId)
            assertEquals(invocation.name, step.toolName)
            assertEquals(invocation, message.blocks.filterIsInstance<MessageBlock.Tool>().single().invocation)
        }
    }
}
