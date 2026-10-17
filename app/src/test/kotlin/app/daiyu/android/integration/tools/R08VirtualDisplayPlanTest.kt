package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.vdapproval.VirtualDisplayApprovalRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class R08VirtualDisplayPlanTest {
    private val owner = runnerOwner(Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
    private val caps = SessionCapabilities("session", setOf(R08VirtualDisplayCatalogLeaf.CAPABILITY))
    private fun plan(vararg actions: R08VirtualDisplayAction) = R08VirtualDisplayPlan.prepare(owner, caps,
        "durable_generation", actions.mapIndexed { i, action -> R08VirtualDisplayWrite("step_$i", action) })

    @Test fun explicitShortAndFullComponentsAreFrozenWithoutResolutionOrExecutionRewrite() {
        listOf("org.example.notes/.MainActivity", "org.example.notes/org.example.notes.MainActivity",
            "org.example.notes/." + "A".repeat(236)).forEach { component ->
            val prepared = plan(R08VirtualDisplayAction.Launch("org.example.notes", component))
            val operation = prepared.writes.single()
            val canonical = "{\"categories\":[],\"component\":\"$component\",\"flags\":0,\"package\":\"org.example.notes\"}"
            assertSame(owner, prepared.owner)
            assertSame(caps, prepared.capabilities)
            assertEquals(canonical, operation.canonicalParameters)
            assertEquals(canonical, VirtualDisplayApprovalRequest(prepared).rows.single().effectiveParameters)
            assertEquals(canonical, prepared.intent("actual-call", operation).canonicalParameters)
            operation.parameters().put("component", "org.example.notes/.Changed")
            assertEquals(canonical, operation.canonicalParameters)
            assertEquals(component, operation.parameters().getString("component"))
        }
    }

    @Test fun componentChangeChangesExactApprovedEffectAndIntentButNeverInvocationIdentity() {
        val first = plan(R08VirtualDisplayAction.Launch("org.example.notes", "org.example.notes/.First"))
        val second = plan(R08VirtualDisplayAction.Launch("org.example.notes", "org.example.notes/.Second"))
        assertNotEquals(first.effects, second.effects)
        val a = first.intent("same-real-call", first.writes.single())
        val b = second.intent("same-real-call", second.writes.single())
        assertEquals(a.operationId, b.operationId) // journal rejects the changed full intent, not a fresh ID
        assertNotEquals(a.canonicalParameters, b.canonicalParameters)
    }

    @Test fun rejectsMissingMalformedMismatchedAndInjectedComponentsBeforeAnyApproval() {
        listOf("", "org.example.notes", "org.example.notes/", "/.Main", "other.package/.Main",
            "org.example.notes/.Main/Extra", "org.example.notes/Main", "org.example.notes/..Main",
            "org.example.notes/.Main;am", "org.example.notes/.Main Activity", "org.example.notes/.Main\n",
            "org.example.notes/.Main\u0000", "org.example.notes/.Main\$Nested",
            "org.example.notes/." + "A".repeat(237)).forEach { component ->
            assertThrows(IllegalArgumentException::class.java) {
                plan(R08VirtualDisplayAction.Launch("org.example.notes", component))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            R08VirtualDisplayAction.Launch("org.example;am", "org.example;am/.Main")
        }
    }

    @Test fun cleanupOnlyHandoffAndReleaseAreSeparateExactEffectsInOneFrozenPlan() {
        val prepared = plan(R08VirtualDisplayAction.Launch("org.example.notes", "org.example.notes/.Main"),
            R08VirtualDisplayAction.Handoff, R08VirtualDisplayAction.Release)
        assertEquals(listOf("launch", "handoff", "release"), prepared.writes.map { it.operation })
        assertEquals(listOf("{\"taskIds\":[]}", "{}"), prepared.writes.drop(1).map { it.canonicalParameters })
        assertEquals(3, prepared.effects.size)
        assertEquals(listOf("launch", "handoff", "release"),
            VirtualDisplayApprovalRequest(prepared).rows.map { it.exactEffect.operation })
        assertEquals("handoff", prepared.intent("handoff-call", prepared.writes[1]).operation)
        assertEquals("{\"taskIds\":[]}", prepared.intent("handoff-call", prepared.writes[1]).canonicalParameters)
        assertEquals(setOf("${prepared.bindingKey}/durable_generation"), prepared.effects.map { it.target }.toSet())
    }

    @Test fun rejectsDuplicateOrNonterminalHandoffAndLaunchReleaseWithoutHandoff() {
        listOf(listOf(R08VirtualDisplayAction.Handoff, R08VirtualDisplayAction.Handoff),
            listOf(R08VirtualDisplayAction.Handoff, R08VirtualDisplayAction.Tap(1, 2)),
            listOf(R08VirtualDisplayAction.Release, R08VirtualDisplayAction.Handoff),
            listOf(R08VirtualDisplayAction.Launch("org.example.notes", "org.example.notes/.Main"),
                R08VirtualDisplayAction.Release)).forEach { actions ->
            assertThrows(IllegalArgumentException::class.java) { plan(*actions.toTypedArray()) }
        }
        // Separate explicit Release-only maintenance does not acquire an unapproved Handoff.
        assertEquals(listOf("release"), plan(R08VirtualDisplayAction.Release).writes.map { it.operation })
    }
}
