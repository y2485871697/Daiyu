package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.delegation.ConversationSubAgentPreferences
import app.daiyu.android.ui.SubAgentSettingsScreen
import app.daiyu.android.ui.app.AgentAppTheme
import java.util.UUID

class SubAgentSettingsButtonTest : WiredActivityTestCase() {
    private val preferenceNames = mutableListOf<String>()
    private var density = 1f
    private var expectedContainer = Color.Unspecified

    override fun cleanupFixture() {
        try { super.cleanupFixture() }
        finally {
            preferenceNames.forEach { instrumentation.targetContext.deleteSharedPreferences(it) }
            preferenceNames.clear()
        }
    }

    private fun repository(): ConversationSubAgentPreferences {
        val name = "subagent-add-button-${UUID.randomUUID()}"
        preferenceNames += name
        return ConversationSubAgentPreferences(instrumentation.targetContext.getSharedPreferences(name, 0))
    }

    private fun addGroupButton() = ui.onNode(hasText("添加子代理组") and hasClickAction())

    private fun show(repository: ConversationSubAgentPreferences, dark: Boolean, scale: Float) {
        mainThread { activity.setContent {
            key(repository, dark, scale) {
                AgentAppTheme(AppearanceSettings(
                    themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                    interfaceScale = scale), applyInterfaceScale = true) {
                    density = LocalDensity.current.density
                    expectedContainer = MaterialTheme.colorScheme.primaryContainer
                    Box(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("subagent-settings-test-host")) {
                        SubAgentSettingsScreen(onBack = {}, repositoryFactory = { repository })
                    }
                }
            }
        } }
        ui.waitUntil(5_000) {
            addGroupButton().fetchSemanticsNode().config.getOrNull(
                androidx.compose.ui.semantics.SemanticsProperties.Disabled) == null
        }
        addGroupButton().assertIsDisplayed().assertIsEnabled()
    }

    private fun assertFloatingContainer(button: SemanticsNodeInteraction): Color {
        button.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        val bounds = button.fetchSemanticsNode().boundsInRoot
        val host = node("subagent-settings-test-host").fetchSemanticsNode().boundsInRoot
        assertTrue("Button retains minimum width", bounds.width >= 168f * density - 1f)
        assertTrue("Button retains minimum touch height", bounds.height >= 48f * density - 1f)
        assertEquals("Button stays centered", host.center.x, bounds.center.x, 1.5f)
        val pixels = captureScreenNode(button).toPixelMap()
        // The capsule's left-center is background, outside the text and rounded edge.
        val actual = pixels[(12f * density).toInt(), pixels.height / 2]
        assertEquals("Filled container red", expectedContainer.red, actual.red, .01f)
        assertEquals("Filled container green", expectedContainer.green, actual.green, .01f)
        assertEquals("Filled container blue", expectedContainer.blue, actual.blue, .01f)
        return actual
    }

    fun testAddGroupKeepsFilledFloatingSurfaceInBothThemesAndScalesForEmptyAndPopulatedLists() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            val repository = repository()
            show(repository, dark, scale)
            ui.onNodeWithText("还没有子代理组，可在设置中添加。").assertIsDisplayed()
            val emptyColor = assertFloatingContainer(addGroupButton())
            val group = repository.addPreset("按钮样式")
            ui.waitUntil(5_000) {
                ui.onAllNodesWithContentDescription("编辑子代理组${group.name}").fetchSemanticsNodes().size == 1
            }
            ui.onNodeWithContentDescription("编辑子代理组${group.name}").assertIsDisplayed()
            val listColor = assertFloatingContainer(addGroupButton())
            assertEquals(emptyColor, listColor)
            ui.onNodeWithContentDescription("编辑子代理组${group.name}").performClick()
            val detail = ui.onNode(hasText("添加子代理") and hasClickAction())
            ui.waitUntil(5_000) {
                detail.fetchSemanticsNode().config.getOrNull(
                    androidx.compose.ui.semantics.SemanticsProperties.Disabled) == null
            }
            assertEquals("List and detail use the same filled color", listColor, assertFloatingContainer(detail))
            assertTrue(repository.snapshot(app.daiyu.android.delegation.SubAgentConfigKey.Preset(group.id)).profiles.isEmpty())
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testAddGroupButtonStillOpensFormAndSavesOnlyOnConfirmation() {
        val repository = repository()
        show(repository, dark = false, scale = 1f)
        addGroupButton().performClick()
        val name = ui.onNode(hasSetTextAction())
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1
        }
        awaitNodeWindowFocus(name)
        ui.onNodeWithText("保存").assertIsNotEnabled()
        name.performTextReplacement("取消的组")
        assertTrue(repository.presets().isEmpty())
        ui.onNodeWithText("取消").performClick()
        name.assertDoesNotExist()
        assertTrue(repository.presets().isEmpty())

        addGroupButton().performClick()
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1
        }
        awaitNodeWindowFocus(name)
        name.performTextReplacement("新子代理组")
        assertTrue(repository.presets().isEmpty())
        ui.onNodeWithText("保存").assertIsEnabled().performClick()
        name.assertDoesNotExist()
        assertEquals(listOf("新子代理组"), repository.presets().map { it.name })
        ui.onNodeWithContentDescription("编辑子代理组新子代理组").assertIsDisplayed()
        assertFloatingContainer(addGroupButton())
        assertEquals(0, fixture.opens.get())
    }
}
