package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import app.daiyu.android.context.CatalogSummaryBindings
import app.daiyu.android.context.CompressionSettingsEditor
import app.daiyu.android.context.ProductionCompressionSettings
import app.daiyu.android.context.ProductionCompressionSettingsStore
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.pet.PetApplicationHost
import app.daiyu.android.pet.PetModelResolver
import app.daiyu.android.storage.IndependentModelSettings
import app.daiyu.android.storage.ProviderCatalogRunTarget
import app.daiyu.android.title.*
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.*

class ModelSelectionSwitchStateActivityTest : WiredActivityTestCase() {
    private lateinit var source: SettingsModelSource
    private lateinit var choice: SettingsModelChoice

    private fun prepare(feature: String) {
        fixture.application.getSharedPreferences("settings-model-selection", 0).edit().clear().commit()
        source = SettingsModelSource(fixture.application, fixture.controller.providers)
        choice = source.choices(SettingsModelProtocols).first()
        assertNull(source.key(feature))
    }

    private fun show(content: @Composable () -> Unit) {
        mainThread {
            activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) { content() } }
        }
        ui.waitForIdle()
    }

    private fun ready(tag: String) {
        ui.waitUntil(5000) {
            ui.onAllNodes(hasTestTag(tag) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun choose(feature: String) {
        awaitUniqueTag("settings-model-picker")
        node("chat-model-search").performTextReplacement(choice.selection.record.id)
        val option = hasTestTag("chat-model-option:${choice.selection.record.id}") or
            hasTestTag("chat-model-option:${choice.provider.slotId}:${choice.selection.record.id}")
        ui.waitUntil(5000) { ui.onAllNodes(option).fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(option).performClick()
        awaitControl("selection persisted") { source.key(feature) == choice.key }
        ui.waitForIdle()
    }

    private fun closePicker(page: String) {
        if (ui.onAllNodesWithTag("settings-model-picker").fetchSemanticsNodes().isNotEmpty())
            backToPage(activity, "page:$page")
    }

    fun testCompressionModelRowPreservesBothSwitchesAndManualEnableStillWorks() {
        val context = fixture.application
        val store = ProductionCompressionSettingsStore(context)
        for (automatic in listOf(false, true)) for (enabled in listOf(false, true)) {
            prepare("compression")
            val binding = CatalogSummaryBindings.encode(ProviderCatalogRunTarget(choice.provider,
                choice.selection.providerIdentity, choice.selection.expectedRevision,
                choice.selection.record.settings.localIdentity, choice.selection.record.id, choice.protocol))
            store.save(ProductionCompressionSettings(automatic = automatic, summaryModel = binding.takeIf { enabled }))
            val owner = CompressionSettingsEditor(context)
            show { CompressionSettingsScreen(owner, {}, { true }, source = source) }
            ready("compression-summary-config")
            reveal("compression-summary-config", "上下文压缩").performClick()
            choose("compression")
            closePicker("上下文压缩")
            assertEquals(automatic, store.snapshot().automatic)
            assertEquals(binding.takeIf { enabled }, store.snapshot().summaryModel)
            ready("settings-switch:使用独立摘要配置")
            val toggle = node("settings-switch:使用独立摘要配置")
            if (enabled) toggle.assertIsOn() else {
                toggle.assertIsOff().performClick()
                awaitControl("manual compression enable") { store.snapshot().summaryModel == binding }
                node("settings-model-picker").assertDoesNotExist()
            }
            assertEquals(automatic, store.snapshot().automatic)
            assertEquals(0, fixture.opens.get())
        }
    }

    fun testTitleModelRowPreservesSwitchAndExplicitEnableStillWorks() {
        val store = TitleModelSettingsStore(fixture.application)
        for (enabled in listOf(false, true)) {
            prepare("title")
            source.withModel(choice) { store.save(TitleModelProfile(TitleModelBinding("fixture", "initial"), it), enabled) }
            val owner = TitleModelSettingsEditor { store }
            show { TitleModelSettingsScreen(owner, { true }, {}, source) }
            ready("title-model-configure")
            reveal("title-model-configure", "标题模型").performClick()
            choose("title")
            assertEquals(enabled, store.snapshot().enabled)
            assertNotNull(store.snapshot().binding)
            if (!enabled) {
                ready("title-model-enabled")
                node("title-model-enabled").assertIsOff().performClick()
                choose("title")
                awaitControl("manual title enable") { store.snapshot().enabled }
            }
            node("title-model-enabled").assertIsOn()
            assertEquals(0, fixture.opens.get())
        }
    }

    fun testVisionModelRowPreservesSwitchAndCancelDoesNotEnable() {
        val store = IndependentModelSettings(fixture.application)
        val slot = IndependentModelSettings.Slot.VISION
        lateinit var pets: PetApplicationHost
        mainThread { pets = PetApplicationHost(fixture.application, PetModelResolver { null }, onOpenSettings = {}) }
        try {
            for (enabled in listOf(false, true)) {
                prepare("independent-vision")
                source.withModel(choice) { store.save(slot, it, null, enabled, true) }
                show { IndependentModelScreen(store, slot, emptyList(), pets, {}, source) }
                ready("independent-model-configure")
                reveal("independent-model-configure", "辅助视觉模型").performClick()
                choose("independent-vision")
                closePicker("辅助视觉模型")
                assertEquals(enabled, store.record(slot).enabled)
                assertEquals(choice.selection.record.id, store.editing(slot).settings.model)
                if (!enabled) {
                    val before = store.record(slot)
                    node("independent-model-enabled").assertIsOff().performClick()
                    closePicker("辅助视觉模型")
                    assertEquals(before, store.record(slot))
                    node("independent-model-enabled").performClick()
                    choose("independent-vision")
                    awaitControl("manual vision enable") { store.record(slot).enabled }
                    closePicker("辅助视觉模型")
                }
                node("independent-model-enabled").assertIsOn()
                assertEquals(0, fixture.opens.get())
            }
        } finally { mainThread { pets.close() } }
    }
}
