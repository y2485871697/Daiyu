package app.daiyu.android.vdapproval

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.integration.tools.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class R08PlanEditorHostTest {
    private fun row(action: R08PlanAction, vararg values: String) = R08PlanEditorRow(action,
        if (values.isEmpty()) action.defaults else values.toList())
    private fun prefix() = listOf(row(R08PlanAction.START), row(R08PlanAction.INITIALIZE))

    @Test fun editedParametersBecomeExactFrozenPlanAndFinalApprovalRows() {
        val values = mutableListOf("11", "22")
        val rows = (prefix() + listOf(row(R08PlanAction.LAUNCH, "org.example.notes", "org.example.notes/.MainActivity"),
            R08PlanEditorRow(R08PlanAction.TAP, values), row(R08PlanAction.SWIPE, "1", "2", "30", "40", "456"),
            row(R08PlanAction.TEXT, "输入 exact text"), row(R08PlanAction.KEY, "66"), row(R08PlanAction.HANDOFF),
            row(R08PlanAction.RELEASE),
            row(R08PlanAction.STATUS), row(R08PlanAction.SNAPSHOT, "false", "1024"))).toMutableList()
        val host = R08PlanEditorHost()
        val frozen = host.freeze(rows)
        values[0] = "999"
        rows.clear()
        val owner = runnerOwner(Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
        val caps = SessionCapabilities("session", setOf(R08VirtualDisplayCatalogLeaf.CAPABILITY))
        val plan = frozen.prepare(owner, caps, "durable_generation")
        val review = VirtualDisplayApprovalRequest(plan)
        assertSame(owner, plan.owner)
        assertSame(caps, plan.capabilities)
        assertEquals(listOf("start", "initialize", "launch", "input", "input", "input", "input", "handoff", "release"),
            review.rows.map { it.operation })
        assertEquals("org.example.notes", plan.writes[2].parameters().getString("package"))
        assertEquals("org.example.notes/.MainActivity", plan.writes[2].parameters().getString("component"))
        assertEquals("{\"taskIds\":[]}", plan.writes[7].canonicalParameters)
        assertEquals("{}", plan.writes[8].canonicalParameters)
        assertEquals(plan.writes.map { it.canonicalParameters }, review.rows.map { it.effectiveParameters })
        assertEquals(11, plan.writes[3].parameters().getInt("x"))
        assertEquals(456, plan.writes[4].parameters().getInt("durationMs"))
        assertEquals("输入 exact text", plan.writes[5].parameters().getString("text"))
        assertEquals(66, plan.writes[6].parameters().getInt("keyCode"))
        assertEquals(listOf("status", "snapshot"), plan.reads.map { it.operation })
        assertEquals(false, plan.reads.last().parameters().getBoolean("include"))
        assertEquals(1024, plan.reads.last().parameters().getInt("maxBytes"))
        assertTrue(review.rows.all { it.exactEffect.target == "${plan.bindingKey}/durable_generation" })
        val defaultGrant = review.authorizeWithProductDefaults()
        assertSame(plan, defaultGrant.plan)
        assertEquals(plan.effects, defaultGrant.requiredEffects)
        assertTrue(defaultGrant.isActive)
        assertThrows(IllegalStateException::class.java) { review.authorizeWithProductDefaults() }
        assertThrows(IllegalStateException::class.java) { review.answerFromUi(true) { fail("default already consumed") } }
        defaultGrant.revoke()
        assertFalse(defaultGrant.isActive)
        assertThrows(IllegalStateException::class.java) { review.authorizeWithProductDefaults() }
        assertThrows(IllegalStateException::class.java) { host.freeze(prefix()) }
        assertFalse(host.cancel())
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (frozen.writes as MutableList<R08VirtualDisplayWrite>).clear()
        }
    }

    @Test fun finiteValidationRejectsUnsafeAndAmbiguousInputsWithoutConsumingEditor() {
        val bad = listOf(row(R08PlanAction.LAUNCH, "org.example.notes"),
            row(R08PlanAction.LAUNCH, "org.example.notes", ""),
            row(R08PlanAction.LAUNCH, "org.example.notes", "other.package/.Main"),
            row(R08PlanAction.LAUNCH, "org.example.notes", "org.example.notes/.Main;kill"),
            row(R08PlanAction.LAUNCH, "org.example;kill", "org.example;kill/.Main"),
            row(R08PlanAction.TAP, "-1", "0"),
            row(R08PlanAction.TAP, "2147483648", "0"), row(R08PlanAction.TAP, "01", "0"),
            row(R08PlanAction.SWIPE, "0", "0", "2", "3", "60001"), row(R08PlanAction.KEY, "1001"),
            row(R08PlanAction.TEXT, "secret\ncommand"), row(R08PlanAction.TEXT, ""),
            row(R08PlanAction.TEXT, "a".repeat(4097)), row(R08PlanAction.SNAPSHOT, "1", "100"),
            row(R08PlanAction.SNAPSHOT, "true", "6291457"))
        bad.forEach { invalid ->
            val host = R08PlanEditorHost()
            assertThrows(RuntimeException::class.java) { host.freeze(prefix() + invalid) }
            assertEquals(2, host.freeze(prefix()).writes.size)
        }
        listOf(prefix() + row(R08PlanAction.START), prefix() + row(R08PlanAction.INITIALIZE),
            prefix() + row(R08PlanAction.RELEASE) + row(R08PlanAction.TAP),
            prefix() + row(R08PlanAction.RELEASE) + row(R08PlanAction.RELEASE),
            prefix() + List(63) { row(R08PlanAction.STATUS) }).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { R08FrozenPlan.freeze(invalid) }
        }
    }

    @Test fun cancellationCannotFreezeOrAffectAnotherHost() {
        val old = R08PlanEditorHost()
        val current = R08PlanEditorHost()
        assertTrue(old.cancel())
        assertFalse(old.cancel())
        assertThrows(IllegalStateException::class.java) { old.freeze(prefix()) }
        assertEquals(2, current.freeze(prefix()).writes.size)
    }
}
