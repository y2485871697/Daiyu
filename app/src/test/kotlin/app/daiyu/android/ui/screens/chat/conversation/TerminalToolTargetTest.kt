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
                assertTrue("$category · $label", toolBodyLayout(category, value.details).fields.isEmpty())
            }
        }
    }

    @Test fun prootGuestRootNeverBecomesAndroidHostRoot() {
        val proot = card("""{"backend":"linux:debian","uid":0,"guest_uid":0,"host_root":false,"host_uid":10444}""")
        assertEquals("Debian • root（PRoot）", proot.targetLabel)
        assertTrue(proot.details.fields.contains(ToolFieldUi("Android 宿主权限", "user")))
        for (category in listOf("terminal", "background_job")) {
            assertTrue(category, toolBodyLayout(category, proot.details).fields.isEmpty())
        }
        val guestUser = card("""{"backend":"linux:alpine","uid":1000,"guest_uid":1000,"host_root":true,"host_uid":10444}""")
        assertEquals("Alpine • user", guestUser.targetLabel)
        assertTrue(guestUser.details.fields.contains(ToolFieldUi("Android 宿主权限", "root")))
        for (category in listOf("terminal", "background_job")) {
            assertTrue(category, toolBodyLayout(category, guestUser.details).fields.isEmpty())
        }
    }

    @Test fun prootReceiptFlagsMatchTheHostRatherThanGuestUid() {
        for (tool in listOf("terminal_exec", "terminal_job_read")) {
            val receipt = JSONObject().put("backend", "linux:debian").put("uid", 0)
                .put("root", false).put("host_root", false)
            val target = checkNotNull(TerminalToolTarget.from(tool, receipt))
            assertEquals("root", target.identity)
            assertEquals("user", target.hostIdentity)
            assertEquals("Debian • root（PRoot）", target.label)
            receipt.put("root", true)
            val conflicting = checkNotNull(TerminalToolTarget.from(tool, receipt))
            assertNull(conflicting.hostIdentity)
            assertEquals("Debian • root", conflicting.label)
        }
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
    private fun runtimeOwner(vararg blocks: MessageBlock) = Message(MessageId("runtime-message"), ConversationId("runtime-chat"),
        RunId("runtime-run"), Message.ASSISTANT_ROLE, blocks.toList(), RunStatus.RUNNING)
    private fun runtimeSteps(turns: List<ChatTurnUi>) = turns.filterIsInstance<ChatTurnUi.Assistant>().flatMap { turn ->
        turn.workProcess?.segments.orEmpty().flatMap { it.steps } + turn.content.filterIsInstance<MessageContentUi.Tool>().map { it.step }
    }
    private fun runtimeMap(messages: List<Message>, mixed: Boolean = false, cache: ToolPresentationCache? = null) =
        ChatTurnMapper.map(messages, presentations = cache, dedicatedTool = { _, _ -> if (mixed) "runtime-test" else null })

    @Test fun everyExecutingHeaderSnapshotAlreadyHasItsResolvedTarget() {
        for (mixed in listOf(false, true)) for (tool in listOf("terminal_exec", "terminal_job_start")) {
            val call = ToolInvocation(ToolCallId("first-frame"), tool, """{"environment":"android"}""")
            val live = runtimeOwner(MessageBlock.Tool(call))
            val base = ChatTurnMapper.map(listOf(live),
                dedicatedTool = { _, _ -> if (mixed) "runtime-test" else null },
                toolReceipt = { _, _ -> ToolReceiptUi(WorkStepState.PENDING, ToolActivityUi.RUNNING) })
            val preparing = ChatTurnMapper.withStreamingOwner(base, live,
                phase = app.daiyu.android.domain.runtime.ExecutionPhase.TOOL_EXECUTING)
            runtimeSteps(preparing).forEach {
                assertEquals(ToolActivityUi.RUNNING, it.activity)
                assertEquals(ToolActivityUi.PREPARING, it.displayActivity)
                assertNull(it.executionTargetLabel)
            }
            val target = app.daiyu.android.domain.runtime.TerminalExecutionTarget("linux:debian", 0, 0, true, true)
            // Production may reuse the last projected turns when only runtime metadata changes.
            val executing = ChatTurnMapper.withStreamingOwner(preparing, live,
                phase = app.daiyu.android.domain.runtime.ExecutionPhase.TOOL_EXECUTING,
                terminalTargets = mapOf(call to target))
            runtimeSteps(executing).forEach {
                assertEquals(ToolActivityUi.RUNNING, it.displayActivity)
                assertEquals("Debian • root", it.executionTargetLabel)
                assertTrue(it.name.endsWith(" · Debian • root"))
                assertFalse(it.awaitingExecutionTarget)
            }
            for (activity in listOf(ToolActivityUi.PAUSING, ToolActivityUi.PAUSED, ToolActivityUi.STOPPING,
                    ToolActivityUi.STOPPED, ToolActivityUi.INTERRUPTED, ToolActivityUi.WAITING,
                    ToolActivityUi.SUCCESS, ToolActivityUi.UNKNOWN, ToolActivityUi.FAILED, ToolActivityUi.NOT_EXECUTED)) {
                runtimeSteps(preparing).forEach { step ->
                    assertEquals(activity, step.copy(activity = activity).displayActivity)
                }
            }
            assertNull(live.blocks.filterIsInstance<MessageBlock.Tool>().single().result)
        }
    }

    @Test fun unresolvedTerminalCardsWaitForTheirOwnTargetWithoutLosingSourceSteps() {
        for (mixed in listOf(false, true)) for (tool in listOf("terminal_exec", "terminal_job_start")) {
            for (arguments in listOf("{\"command\":", "{\"environment\":\"android\"}")) {
                val call = ToolInvocation(ToolCallId("pending-environment"), tool, arguments)
                val live = runtimeOwner(MessageBlock.Tool(call))
                val pending = ChatTurnMapper.withStreamingOwner(runtimeMap(listOf(live), mixed), live)
                val original = runtimeSteps(pending).distinctBy { it.renderKey }.single()
                val turn = pending.filterIsInstance<ChatTurnUi.Assistant>().single()
                val hidden = turn.workflowContent().withResolvedTerminalCards()
                assertNull(hidden.work)
                assertTrue(hidden.steps.isEmpty())
                assertFalse(turn.hasVisibleResponse())
                assertFalse(original.isVisibleInWorkflow)
                assertNull(original.executionTargetLabel)
                val target = app.daiyu.android.domain.runtime.TerminalExecutionTarget("linux:debian", 0, 0, true, true)
                val resolved = ChatTurnMapper.withStreamingOwner(pending, live, terminalTargets = mapOf(call to target))
                val shown = resolved.filterIsInstance<ChatTurnUi.Assistant>().single().workflowContent().withResolvedTerminalCards()
                assertNull(shown.work)
                assertTrue(shown.steps.isEmpty())
                val step = runtimeSteps(resolved).distinctBy { it.renderKey }.single()
                assertEquals("Debian • root", step.executionTargetLabel)
                assertFalse(step.isVisibleInWorkflow)
                assertEquals(original.activity, step.activity)
                assertEquals(original.state, step.state)
                assertEquals(listOf(original), runtimeSteps(pending).distinctBy { it.renderKey })
                assertSame(resolved, ChatTurnMapper.withStreamingOwner(resolved, live, terminalTargets = mapOf(call to target)))
                assertNull(live.blocks.filterIsInstance<MessageBlock.Tool>().single().result)
            }
        }
    }

    @Test fun terminalDisplayFilteringKeepsPartitionsAndRemainingNodeOrder() {
        val hidden = WorkStepUi("hidden", "执行命令", "", category = "terminal")
        val child = WorkStepUi("child", "后台委派", "", category = "delegation", activity = ToolActivityUi.QUEUED)
        val narration = MessageContentUi.Text("narration", "说明")
        val work = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(hidden)),
            WorkSegmentUi(narration = "说明", steps = listOf(child))), "进行中", running = true)
        val original = AssistantWorkflowContent(work, listOf(MessageContentUi.Tool(hidden), narration, MessageContentUi.Tool(child)),
            listOf(MessageContentUi.Text("body", "正文")), pending = listOf(MessageContentUi.Text("pending", "未释放")),
            leading = listOf(MessageContentUi.Text("leading", "开头")))
        val visible = original.withResolvedTerminalCards()
        assertEquals(listOf(narration, MessageContentUi.Tool(child)), visible.steps)
        assertEquals(listOf(child), visible.work!!.segments.flatMap { it.steps })
        assertSame(original.leading, visible.leading)
        assertSame(original.body, visible.body)
        assertSame(original.pending, visible.pending)
        assertEquals(listOf(hidden, child), work.segments.flatMap { it.steps })
        val noCard = original.copy(work = work.copy(segments = work.segments.take(1)),
            steps = original.steps.take(1)).withResolvedTerminalCards()
        assertNull(noCard.work)
        assertTrue(noCard.steps.isEmpty())
        assertSame(original.body, noCard.body)
        assertSame(original.pending, noCard.pending)
    }

    @Test fun queuedCallsStayHiddenEvenAfterTerminalTargetResolution() {
        val terminal = WorkStepUi("target", "执行命令", "", category = "terminal")
        for (state in WorkStepState.entries) for (activity in ToolActivityUi.entries) {
            for (category in listOf("terminal", "background_job")) {
                val step = terminal.copy(category = category, state = state, activity = activity)
                assertFalse(step.isVisibleInWorkflow)
                assertTrue(step.copy(executionTargetLabel = "Android • user").isVisibleInWorkflow)
            }
        }
        val queued = toolReceiptPresentation(app.daiyu.android.domain.runtime.ToolDisposition.READY, true,
            app.daiyu.android.domain.runtime.ExecutionPhase.TOOL_EXECUTING)
        assertEquals(ToolActivityUi.QUEUED, queued.activity)
        for (mixed in listOf(false, true)) for (tool in listOf("run_child_models", "start_child_tasks", "delegate_task")) {
            val call = ToolInvocation(ToolCallId("queued-$tool"), tool, "{}")
            val terminal = ToolInvocation(ToolCallId("hidden-terminal"), "terminal_exec", "{}")
            val job = ToolInvocation(ToolCallId("hidden-job"), "terminal_job_start", "{}")
            val owner = runtimeOwner(MessageBlock.Tool(terminal), MessageBlock.Tool(job), MessageBlock.Tool(call))
            val turns = ChatTurnMapper.map(listOf(owner),
                dedicatedTool = { _, _ -> if (mixed) "queue-test" else null }, toolReceipt = { _, _ -> queued })
            val turn = ChatTurnMapper.withStreamingOwner(turns, owner).filterIsInstance<ChatTurnUi.Assistant>().single()
            val shown = turn.workflowContent().withResolvedTerminalCards()
            assertTrue(shown.steps.isEmpty())
            assertNull(shown.work)
            assertFalse(turn.hasVisibleResponse())
            assertEquals(3, runtimeSteps(turns).distinctBy { it.renderKey }.size)
            val target = app.daiyu.android.domain.runtime.TerminalExecutionTarget("linux:debian", 0, 0, true, true)
            val resolved = ChatTurnMapper.withStreamingOwner(turns, owner, terminalTargets = mapOf(terminal to target, job to target))
                .filterIsInstance<ChatTurnUi.Assistant>().single().workflowContent().withResolvedTerminalCards()
            assertTrue(resolved.steps.isEmpty())
            assertNull(resolved.work)
        }
    }

    @Test fun resolvedRuntimeTargetAppearsBeforeReceiptInBothLayoutsWithoutChangingStepState() {
        val call = ToolInvocation(ToolCallId("runtime-call"), "terminal_exec", """{"environment":"android"}""")
        val live = runtimeOwner(MessageBlock.Tool(call))
        val cases = listOf(
            app.daiyu.android.domain.runtime.TerminalExecutionTarget("android", uid = 0, root = true) to "Android • root",
            app.daiyu.android.domain.runtime.TerminalExecutionTarget("android", uid = 10444, root = false) to "Android • user",
            app.daiyu.android.domain.runtime.TerminalExecutionTarget("linux:debian", 0, 0, false, false) to "Debian • root（PRoot）",
            app.daiyu.android.domain.runtime.TerminalExecutionTarget("linux:alpine", 1000, 1000, false, false) to "Alpine • user",
            app.daiyu.android.domain.runtime.TerminalExecutionTarget("shizuku", uid = 2000) to "Android • Shizuku",
        )
        for (mixed in listOf(false, true)) for ((target, label) in cases) {
            val base = runtimeMap(listOf(live), mixed)
            val expected = runtimeSteps(base).map { it.copy(name = "${it.name} · $label", executionTargetLabel = label) }
            val projected = ChatTurnMapper.withStreamingOwner(base, live, terminalTargets = mapOf(call to target))
            assertEquals(expected, runtimeSteps(projected))
            assertTrue(runtimeSteps(base).all { it.executionTargetLabel == null })
            assertSame(projected, ChatTurnMapper.withStreamingOwner(projected, live, terminalTargets = mapOf(call to target)))
            assertNull(live.blocks.filterIsInstance<MessageBlock.Tool>().single().result)
        }
    }

    @Test fun runtimeTargetRequiresExactOwnerAndInvocationAndDoesNotDecorateDuplicateOccurrences() {
        val call = ToolInvocation(ToolCallId("dup"), "terminal_exec", """{"command":"new"}""")
        val target = app.daiyu.android.domain.runtime.TerminalExecutionTarget("linux:debian", 0, 0, true, true)
        val live = runtimeOwner(MessageBlock.Tool(call))
        val foreign = live.copy(runId = RunId("foreign-run"))
        val base = runtimeMap(listOf(foreign, live), mixed = true)
        val output = RunningTerminalTargets.project(base, live, mapOf(call to target))
        assertSame(base.first(), output.first())
        assertTrue(runtimeSteps(listOf(output.last())).all { it.executionTargetLabel == "Debian • root" })
        val unresolved = RunningTerminalTargets.project(base, live, mapOf(call.copy(arguments = "{}") to target))
        assertSame(base.first(), unresolved.first())
        val own = runtimeSteps(listOf(unresolved.last()))
        assertTrue(own.all { it.executionTargetLabel == null })
        assertEquals(runtimeSteps(listOf(base.last())).map { it.name }, own.map { it.name })
        val repeated = live.copy(blocks = listOf(MessageBlock.Tool(call.copy(arguments = "{}"), "{}"), MessageBlock.Tool(call)))
        val duplicates = runtimeMap(listOf(repeated), mixed = true)
        assertSame(duplicates, RunningTerminalTargets.project(duplicates, repeated, mapOf(call to target)))
        val earlier = live.copy(blocks = listOf(MessageBlock.Tool(call.copy(arguments = "{}"))))
        val merged = runtimeMap(listOf(earlier, live), mixed = true)
        assertSame(merged, RunningTerminalTargets.project(merged, live, mapOf(call to target)))
    }

    @Test fun ambiguousRunningOccurrencesWaitWithoutBorrowingAnotherTargetsLabel() {
        val call = ToolInvocation(ToolCallId("ambiguous"), "terminal_exec", "{}")
        val live = runtimeOwner(MessageBlock.Tool(call), MessageBlock.Tool(call.copy(arguments = "{\"command\":\"other\"}")))
        val target = app.daiyu.android.domain.runtime.TerminalExecutionTarget("linux:debian", 0, 0, true, true)
        val base = ChatTurnMapper.map(listOf(live), dedicatedTool = { _, _ -> "ambiguous-test" },
            toolReceipt = { _, _ -> ToolReceiptUi(WorkStepState.PENDING, ToolActivityUi.RUNNING) })
        val displayed = RunningTerminalTargets.project(base, live, mapOf(call to target))
        val steps = runtimeSteps(displayed)
        assertTrue(steps.isNotEmpty())
        steps.forEach {
            assertNull(it.executionTargetLabel)
            assertTrue(it.awaitingExecutionTarget)
            assertEquals(ToolActivityUi.PREPARING, it.displayActivity)
        }
    }

    @Test fun runtimeTargetLeavesCachedPresentationsCleanAndFinalReceiptAuthoritative() {
        val call = ToolInvocation(ToolCallId("runtime-call"), "terminal_exec", "{}")
        val live = runtimeOwner(MessageBlock.Tool(call))
        val cache = ToolPresentationCache()
        val target = app.daiyu.android.domain.runtime.TerminalExecutionTarget("linux:debian", 0, 0, true, true)
        val base = runtimeMap(listOf(live), mixed = true, cache = cache)
        assertTrue(runtimeSteps(RunningTerminalTargets.project(base, live, mapOf(call to target)))
            .all { it.executionTargetLabel == "Debian • root" })
        val fresh = runtimeMap(listOf(live), mixed = true, cache = cache)
        assertTrue(runtimeSteps(fresh).all { it.executionTargetLabel == null })
        val received = live.copy(blocks = listOf(MessageBlock.Tool(call, """{"backend":"android","uid":10444,"root":false}""")))
        val finalBase = runtimeMap(listOf(received), mixed = true, cache = cache)
        assertSame(finalBase, RunningTerminalTargets.project(finalBase, received, mapOf(call to target)))
        assertTrue(runtimeSteps(finalBase).all { it.executionTargetLabel == "Android • user" })
    }

    @Test fun runtimeTargetReachesActuallyRenderedReconnectPartsBeforeReceipt() {
        val call = ToolInvocation(ToolCallId("reconnected-call"), "terminal_exec", "{}")
        val live = runtimeOwner(MessageBlock.Tool(call))
        val anchor = app.daiyu.android.timeline.TimelineAnchor(live.conversationId.value, live.runId.value, live.id.value, 0)
        val event = app.daiyu.android.timeline.ChatTimelineEntry.Reconnect("runtime-reconnect", anchor, 1L,
            "succeeded", 10L, "network", 0, "model", app.daiyu.android.timeline.ReconnectPosition(0))
        val base = ChatTurnMapper.map(listOf(live), reconnects = listOf(event))
        val target = app.daiyu.android.domain.runtime.TerminalExecutionTarget("linux:debian", 0, 0, true, true)
        val displayed = ChatTurnMapper.withStreamingOwner(base, live, terminalTargets = mapOf(call to target))
            .filterIsInstance<ChatTurnUi.Assistant>().single()
        val sourceStep = displayed.reconnectSource!!.content.filterIsInstance<MessageContentUi.Tool>().single().step
        assertEquals("Debian • root", sourceStep.executionTargetLabel)
        val parts = reconnectParts(displayed, listOf(event))
        assertEquals(setOf(event.id), parts.placedIds)
        val steps = parts.parts.flatMap { it.workflow.steps }.filterIsInstance<MessageContentUi.Tool>().map { it.step }
        assertEquals(1, steps.size)
        assertEquals("Debian • root", steps.single().executionTargetLabel)
        assertTrue(steps.single().name.endsWith(" · Debian • root"))
        assertTrue(runtimeSteps(base).all { it.executionTargetLabel == null })
    }

}
