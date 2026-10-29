package app.daiyu.android.gui

import app.daiyu.android.domain.tools.ToolEffect
import org.junit.Assert.*
import org.junit.Test

class GuiScreenToolFactoryTest {
    private val session = GuiRunSession(GuiLeaseGate.Lease(GuiOwner("s", "r")))
    private val tools = GuiScreenToolFactory.create(session).associateBy { it.definition.name }

    @Test fun onlyRecoverableWindowRejectionsOfferExplicitTargetClarification() {
        listOf("NO_ACTIVE_EXTERNAL_WINDOW", "SELF_APP_TARGET_REQUIRED_NOT_EXECUTED", "TARGET_PACKAGE_MISMATCH_NOT_EXECUTED",
            "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED").forEach { assertTrue(session.canClarifyWindow(it)) }
        listOf("SELF_APP_TARGET_LOST", "FOREGROUND_BUSY", "GUI_AUTHORIZATION_REVOKED", "SCREEN_LOCKED_NOT_EXECUTED",
            "APPLICATION_WINDOW_AMBIGUOUS_NOT_EXECUTED").forEach { assertFalse(session.canClarifyWindow(it)) }
    }

    @Test fun onlyOrdinaryScreenshotDefinitionAllowsOneImplicitObservation() {
        val ordinary = GuiScreenToolFactory.readDefinition(allowImplicitObservation = true)
        assertNotNull(ordinary.schema.parse("{\"operation\":\"screenshot\"}"))
        assertTrue(ordinary.description.contains("observation"))
        assertFalse(GuiScreenToolFactory.readDefinition().description.contains("omit"))
        assertTrue(tools.getValue("screen_action").definition.description.contains("bypass"))
    }

    @Test fun readAndWriteHaveSeparateFixedEffectsInExistingCatalogContract() {
        assertEquals(ToolEffect.READ_ONLY, tools.getValue("screen").definition.effect)
        assertEquals(ToolEffect.WRITE, tools.getValue("screen_action").definition.effect)
        assertTrue(tools.values.all { it.definition.capability == GuiScreenToolFactory.CAPABILITY })
    }
    @Test fun observeCannotSmuggleScreenshotAndScreenshotRequiresObservationId() {
        val tool = tools.getValue("screen")
        assertNull(tool.prepare(tool.definition.schema.parse("{\"operation\":\"observe\"}")).writeEffect)
        invalid { tool.prepare(tool.definition.schema.parse("{\"operation\":\"observe\",\"observation_id\":\"x\"}")) }
        invalid { tool.prepare(tool.definition.schema.parse("{\"operation\":\"screenshot\"}")) }
        invalid { tool.definition.schema.parse("{\"operation\":\"observe\",\"screenshot\":true}") }
    }
    @Test fun prepareIsPureAndReturnsOnlyExactNormalizedEffect() {
        val tool = tools.getValue("screen_action")
        val plan = tool.prepare(tool.definition.schema.parse(
            "{\"operation\":\"input\",\"observation_id\":\"obs\",\"node_id\":0,\"text\":\"exact\"}"))
        assertEquals(GuiAction("obs", GuiOperation.INPUT, 0, "exact").effect(session.identity), plan.writeEffect)
        invalid { tool.prepare(tool.definition.schema.parse("{\"operation\":\"tap\",\"observation_id\":\"obs\"}")) }
        invalid { tool.prepare(tool.definition.schema.parse("{\"operation\":\"home\",\"observation_id\":\"obs\",\"node_id\":0}")) }
        invalid { tool.prepare(tool.definition.schema.parse("{\"operation\":\"tap\",\"observation_id\":\"obs\",\"node_id\":0,\"text\":\"hidden\"}")) }
        invalid { tool.definition.schema.parse("{\"operation\":\"tap\",\"observation_id\":\"obs\",\"node_id\":128}") }
    }
    @Test fun serializedTreeReportsBoundedEvidenceAndNeverCreatesScreenshot() {
        val json = GuiScreenToolFactory.observationJson(GuiObservation("obs", "example.app", 1, 3, 100,
            emptyList(), true))
        assertTrue(json.contains("\"observation_id\":\"obs\""))
        assertTrue(json.contains("\"truncated\":true"))
        assertFalse(json.contains("data_base64"))
        assertEquals(120_000L, org.json.JSONObject(json).getLong("expires_after_ms"))
    }
    private fun invalid(block: () -> Unit) {
        try { block(); fail("must reject malformed/expanded request") } catch (_: IllegalArgumentException) { }
    }
}
