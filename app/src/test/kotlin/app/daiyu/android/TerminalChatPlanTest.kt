package app.daiyu.android

import app.daiyu.android.domain.ConversationId
import app.daiyu.android.integration.tools.TerminalPtyLaunch
import app.daiyu.android.integration.tools.TerminalPtyStep
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TerminalChatPlanTest {
    private val launch = TerminalPtyLaunch.Android(listOf("/system/bin/sh"), emptyMap(), File("/tmp"))

    @Test fun independentCommandBytesAreNotTrimmedOrInferredOrGivenAnImplicitEnter() {
        val exact = "  printf '你好'\r\n# final spaces  "
        val plan = TerminalChatPlan.create(ConversationId("owner"), exact, launch, File("/tmp/journal"), "run")
        assertEquals(listOf("open", "input"), plan.steps.map { it.id })
        assertTrue(plan.steps[0] is TerminalPtyStep.Open)
        assertArrayEquals(exact.toByteArray(Charsets.UTF_8), (plan.steps[1] as TerminalPtyStep.Input).bytes())
        assertEquals("run", plan.sessionId)
        assertEquals("run", plan.runId)
        assertEquals(2, plan.reads.size)
        assertEquals(listOf(0, 16384), plan.reads.map { it.offset })
        assertTrue(plan.reads.all { it.limit == 16384 })
        assertEquals(listOf("/system/bin/sh"), (plan.launch as TerminalPtyLaunch.Android).argv)
    }

    @Test fun emptyOversizedOrMalformedCommandsAreRejectedNotReplacedWithDraftText() {
        listOf("", "x".repeat(65537), "\uD800", "x\u0000y").forEach { command ->
            try {
                TerminalChatPlan.create(ConversationId("owner"), command, launch, File("/tmp/journal"))
                fail("invalid exact input accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
