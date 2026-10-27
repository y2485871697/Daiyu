package app.daiyu.android.gui

import org.junit.Assert.*
import org.junit.Test

/** JVM evidence only: not a claim that an OEM delivers overlay/window events correctly. */
class GuiProductionConfirmationTest {
    private val node = GuiNode(4, 1, GuiBounds(10, 20, 90, 60), "Real target", "description",
        "android.widget.Button", "external:id/send", true, false, false, false, true, false)
    private fun observation(id: String, pkg: String = "external.app", window: Int = 7,
        nodes: List<GuiNode> = listOf(node), truncated: Boolean = false) =
        GuiObservation(id, pkg, window, 5, 100, nodes, truncated)
    private fun code(result: GuiResult<*>) = (result as GuiResult.Rejected).code

    @Test fun productDefaultResolverRequiresUniqueSafeTargetAndExactRequiredParameters() {
        val observed = observation("default")
        assertEquals(GuiResult.Value(GuiAction("default", GuiOperation.TAP, node.id)),
            GuiExactTarget.resolve(observed, GuiOperation.TAP, null))
        assertEquals(GuiResult.Value(GuiAction("default", GuiOperation.HOME)),
            GuiExactTarget.resolve(observed, GuiOperation.HOME, null))
        listOf(emptyList(), listOf(node, node.copy(id = 9)), listOf(node.copy(password = true)),
            listOf(node.copy(enabled = false))).forEach { candidates ->
            assertEquals("GUI_TARGET_CLARIFICATION_REQUIRED",
                code(GuiExactTarget.resolve(observation("default", nodes = candidates), GuiOperation.TAP, null)))
        }
        assertEquals("GUI_ACTION_PARAMETERS_REQUIRED", code(GuiExactTarget.resolve(observed, GuiOperation.INPUT, null)))
        assertEquals("GUI_ACTION_PARAMETERS_REQUIRED", code(GuiExactTarget.resolve(observed, GuiOperation.TAP, "unexpected")))
        assertEquals("TARGET_TREE_TRUNCATED_RECONFIRM_NOT_EXECUTED",
            code(GuiExactTarget.resolve(observation("cut", truncated = true), GuiOperation.HOME, null)))
        val password = observation("password", nodes = listOf(node.copy(editable = true, password = true)))
        assertEquals("GUI_TARGET_CLARIFICATION_REQUIRED", code(GuiExactTarget.resolve(password, GuiOperation.INPUT, "secret")))
    }

    @Test fun oldCallbacksAreInertAfterRenderAndAfterCancel() {
        val ticket = GuiConfirmationTicket()
        val old = ticket.next()
        val current = ticket.next()
        assertFalse(ticket.current(old))
        assertTrue(ticket.current(current))
        ticket.close()
        assertFalse(ticket.current(current))
        assertFalse(ticket.current(old))
    }

    @Test fun finalCaptureMayRenumberButMustMatchAllDisplayedEvidenceUniquely() {
        val preview = observation("preview")
        val final = observation("final", nodes = listOf(node.copy(id = 8, parent = 3)))
        val match = GuiExactTarget.unique(preview, 4, final) as GuiResult.Value<GuiNode?>
        assertEquals(8, match.value?.id)
        val duplicate = observation("duplicate", nodes = listOf(node, node.copy(id = 9)))
        assertEquals("TARGET_NOT_UNIQUE_RECONFIRM_NOT_EXECUTED", code(GuiExactTarget.unique(preview, 4, duplicate)))
        listOf(node.copy(text = "Changed"), node.copy(description = "Changed"),
            node.copy(bounds = GuiBounds(11, 20, 90, 60)), node.copy(viewId = "other"),
            node.copy(enabled = false), node.copy(clickable = false), node.copy(password = true)).forEach {
            assertTrue(GuiExactTarget.unique(preview, 4, observation("changed", nodes = listOf(it))) is GuiResult.Rejected)
        }
    }

    @Test fun targetSwitchAndTruncationRequireReconfirmationIncludingGlobalActions() {
        val preview = observation("preview")
        assertEquals("TARGET_WINDOW_CHANGED_RECONFIRM_NOT_EXECUTED",
            code(GuiExactTarget.unique(preview, 4, observation("next", pkg = "other.app"))))
        assertEquals("TARGET_WINDOW_CHANGED_RECONFIRM_NOT_EXECUTED",
            code(GuiExactTarget.unique(preview, null, observation("next", window = 8))))
        assertEquals("TARGET_TREE_TRUNCATED_RECONFIRM_NOT_EXECUTED",
            code(GuiExactTarget.unique(preview, 4, observation("next", truncated = true))))
        assertTrue(GuiExactTarget.unique(preview, null, observation("final")) is GuiResult.Value)
    }

    @Test fun centralUnknownFencesActualSessionReadWriteAndScreenshotWithoutAndroidIo() {
        val session = GuiRunSession(GuiLeaseGate.Lease(GuiOwner("s", "r")))
        // Simulates dispatcher receipt_persistence_failed AFTER the leaf returned a value.
        session.onCentralUnknown()
        assertEquals("GUI_PREVIOUS_OPERATION_UNCONFIRMED", code(session.observe { false }))
        assertEquals("GUI_PREVIOUS_OPERATION_UNCONFIRMED", code(session.action(GuiAction("obs", GuiOperation.HOME)) { false }))
        assertEquals("GUI_PREVIOUS_OPERATION_UNCONFIRMED", code(session.screenshot("obs") { false }))
        session.onCentralUnknown() // idempotent; never opens a new observation/call ID
        assertFalse(session.preparationAvailable())
        assertFalse(session.releaseConfirmed) // a fence is not cleanup or drain
    }

    @Test fun unknownAndCloseAreMonotonicIndependentOfCallbackOrder() {
        val first = GuiIoFence()
        first.unknown(); first.close(); first.unknown()
        assertEquals("GUI_AUTHORIZATION_REVOKED", first.rejection()?.code)
        val second = GuiIoFence()
        second.close(); second.unknown()
        assertEquals("GUI_AUTHORIZATION_REVOKED", second.rejection()?.code)
    }

    @Test fun serviceLossAndCancelRevokeObservationAndOldCallbacksCannotAffectNextOwner() {
        val gate = GuiLeaseGate { 100L }.also { it.connect() }
        val old = (gate.acquire(GuiOwner("s", "r")) as GuiResult.Value<GuiLeaseGate.Lease>).value
        gate.publish(old, observation("obs"))
        val action = GuiAction("obs", GuiOperation.TAP, 4)
        gate.approveAction(old, action, 5)
        gate.disconnect()
        assertFalse(old.valid)
        assertTrue(gate.consumeAction(old, action, 5) is GuiResult.Rejected)
        gate.connect()
        val fresh = (gate.acquire(GuiOwner("s", "new")) as GuiResult.Value<GuiLeaseGate.Lease>).value
        gate.close(old)
        assertNull(gate.rejection(fresh))
        gate.close(fresh) // explicit cancel, not a platform action
        assertFalse(fresh.valid)
        assertTrue(gate.approvedActions(fresh).isEmpty())
    }

    @Test fun deliveryArgumentsRoundTripThroughTheExistingCatalogWithoutNullSmuggling() {
        val session = GuiRunSession(GuiLeaseGate.Lease(GuiOwner("s", "r")))
        val action = GuiAction("approved", GuiOperation.HOME)
        val delivery = GuiConfirmedDelivery(session, observation("approved"), action, setOf(action.effect(session.identity)))
        val tool = GuiScreenToolFactory.create(session).single { it.definition.name == "screen_action" }
        val prepared = tool.prepare(tool.definition.schema.parse(delivery.actionArgumentsJson()))
        assertEquals(action.effect(session.identity), prepared.writeEffect)
        assertFalse(delivery.actionArgumentsJson().contains("node_id"))
        assertFalse(delivery.actionArgumentsJson().contains("text"))
    }

    @Test fun sameObservationDeliveryContainsFullInputAndNeverRequiresAnotherObserve() {
        val session = GuiRunSession(GuiLeaseGate.Lease(GuiOwner("s", "r")))
        val observation = observation("approved")
        val input = "complete\n\"exact\"\n" + "x".repeat(4000)
        val action = GuiAction("approved", GuiOperation.INPUT, 4, input)
        val delivery = GuiConfirmedDelivery(session, observation, action, setOf(action.effect(session.identity)))
        assertSame(observation, delivery.observation)
        assertEquals(input, delivery.action.text)
        val context = delivery.modelContext()
        assertTrue(context.contains("do not observe again"))
        assertTrue(context.contains("\"observation_id\":\"approved\""))
        assertTrue(context.contains("x".repeat(4000)))
    }
}
