package app.daiyu.android.appearance

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.ComposeActivityTestCase
import app.daiyu.android.MainActivity
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.domain.appearance.IconAppearance
import app.daiyu.android.domain.appearance.IconComponentSnapshot
import app.daiyu.android.domain.appearance.IconComponentState
import app.daiyu.android.domain.appearance.IconNotificationPort
import app.daiyu.android.domain.appearance.IconThemeMode
import app.daiyu.android.domain.appearance.LauncherComponentPort
import app.daiyu.android.domain.appearance.Task17IconCatalog
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.AppearanceSettingsScreen
import app.daiyu.android.ui.screens.settings.IconAppearanceEditor
import java.util.UUID

/** Real inline editor and coordinator, isolated preferences and fake launcher writes. */
@Suppress("DEPRECATION")
class IconAppearanceScreenTest : ComposeActivityTestCase() {
    private lateinit var activity: MainActivity
    private lateinit var context: Context
    private lateinit var prefix: String
    private val page = "外观与主题"
    private val components = object : LauncherComponentPort {
        val states = mutableMapOf<String, IconComponentState>()
        var writes = 0
        var fail = false
        override fun validate(componentName: String) = Unit
        override fun snapshot(componentName: String): IconComponentSnapshot {
            val state = states[componentName] ?: IconComponentState.DEFAULT
            return IconComponentSnapshot(state, state == IconComponentState.ENABLED ||
                state == IconComponentState.DEFAULT &&
                componentName == Task17IconCatalog.defaultEntry.componentName(context.packageName))
        }
        override fun setState(componentName: String, state: IconComponentState) {
            check(!fail) { "injected launcher failure" }
            states[componentName] = state
            writes++
        }
    }

    override fun setUp() {
        super.setUp()
        prefix = "icon-screen-test-${UUID.randomUUID()}-"
        val base = instrumentation.targetContext
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        activity = startMain()
    }

    override fun tearDown() {
        try {
            mainThread { activity.finish() }
            val base = instrumentation.targetContext
            base.deleteSharedPreferences(prefix + "task17_icon_appearance")
            base.deleteSharedPreferences(prefix + "launcher_icon")
        } finally { super.tearDown() }
    }

    private fun coordinator() = IconAppearanceCoordinator(context, IconNotificationPort {}, components)

    private fun show(coordinator: IconAppearanceCoordinator, dark: Boolean = false, scale: Float = 1f, fontScale: Float = 1f) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                    interfaceScale = scale), applyInterfaceScale = true) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        key(coordinator) {
                            Box(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("icon-test-host")) {
                                AppearanceSettingsScreen(AppearanceSettings(), onChange = {}, onBack = {},
                                    iconContent = { expanded -> IconAppearanceEditor(coordinator, expanded) })
                            }
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
        node("icon-appearance-editor").assertDoesNotExist()
        reveal("settings-row:图标", page).performClick()
        node("icon-appearance-editor").assertExists()
        node("page:应用图标").assertDoesNotExist()
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun swatch(id: String, index: Int) = reveal("icon-swatch:$id:$index", page)
    private fun save() = reveal("icon-appearance-confirm", page).performClick()

    fun testOriginalPaletteAndBothPreviewsSaveOnlyAfterConfirmationAndReopenPersistentChoice() {
        val first = coordinator()
        mainThread { first.store.save(IconAppearance(themeMode = IconThemeMode.DARK)) }
        show(first)
        assertEquals(0, components.writes)
        reveal("icon-appearance-confirm", page).assertIsNotEnabled()
        for (id in listOf("light", "dark", "cat")) for (index in Task17IconCatalog.swatches.indices) {
            node("icon-swatch:$id:$index").assertExists()
        }
        swatch("light", 0).performClick()
        swatch("dark", 3).performClick()
        swatch("cat", 1).performClick()
        mainThread {
            val previews = descendants(activity.window.decorView).filterIsInstance<AppCatIconView>()
            assertEquals(2, previews.size)
            assertEquals(Task17IconCatalog.swatches[0], previews.single { it.contentDescription == "浅色图标预览" }.backgroundColorValue)
            assertEquals(Task17IconCatalog.swatches[3], previews.single { it.contentDescription == "暗色图标预览" }.backgroundColorValue)
            assertTrue(previews.all { it.catColor == Task17IconCatalog.swatches[1] })
        }
        assertEquals(IconAppearance(themeMode = IconThemeMode.DARK), first.store.load())
        assertEquals(0, components.writes)
        save()
        val saved = first.store.load()
        assertEquals(IconThemeMode.DARK, saved.themeMode)
        assertEquals(Task17IconCatalog.swatches[0], saved.iconLightColor)
        assertEquals(Task17IconCatalog.swatches[3], saved.iconDarkColor)
        assertEquals(Task17IconCatalog.swatches[1], saved.iconCatColor)
        assertEquals(saved.selectedIcon(false), first.store.current())
        assertTrue(components.writes > 0)
        node("icon-appearance-confirm").assertIsNotEnabled()
        val writes = components.writes
        val reopened = coordinator()
        show(reopened)
        swatch("light", 0).assertIsSelected(); swatch("dark", 3).assertIsSelected(); swatch("cat", 1).assertIsSelected()
        assertEquals(saved, reopened.store.load()); assertEquals(writes, components.writes)
    }

    fun testCollapseKeepsDraftWithoutSavingAndLeavingInvalidatesOldConfirmation() {
        val first = coordinator()
        show(first)
        swatch("cat", 0).performClick()
        reveal("settings-row:图标", page).performClick()
        node("icon-appearance-editor").assertDoesNotExist()
        reveal("settings-row:图标", page).performClick()
        swatch("cat", 0).assertIsSelected()
        val click = checkNotNull(reveal("icon-appearance-confirm", page).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings()) {
            top.yukonga.miuix.kmp.basic.Text("已离开外观设置")
        } } }
        ui.waitForIdle()
        mainThread { click() }
        assertEquals(0, components.writes)
        assertEquals(IconAppearance(), first.store.load())
        show(coordinator())
        swatch("cat", 5).assertIsSelected()
        reveal("icon-appearance-confirm", page).assertIsNotEnabled()
    }

    fun testPausedEditorRejectsRetainedConfirmationAndResumedEditorCanSave() {
        val coordinator = coordinator()
        show(coordinator)
        swatch("cat", 0).performClick()
        val click = checkNotNull(reveal("icon-appearance-confirm", page).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        realPauseResume(activity, atPause = { click(); Unit })
        assertEquals(0, components.writes)
        assertEquals(IconAppearance(), coordinator.store.load())
        save()
        assertEquals(Task17IconCatalog.swatches[0], coordinator.store.load().iconCatColor)
        assertTrue(components.writes > 0)
    }

    fun testMalformedPreferencesCanBeRepairedOnlyByExplicitConfirmation() {
        context.getSharedPreferences("task17_icon_appearance", Context.MODE_PRIVATE)
            .edit().putString("icon_cat_color", "not-an-int").commit()
        val coordinator = coordinator()
        show(coordinator)
        assertEquals(0, components.writes)
        node("icon-appearance-error").assertExists()
        save()
        assertEquals(IconAppearance(), coordinator.store.load())
        assertEquals(Task17IconCatalog.defaultEntry, coordinator.store.current())
        assertTrue(components.writes > 0)
        node("icon-appearance-error").assertDoesNotExist()
    }

    fun testLauncherFailureKeepsChosenColorsAndAllowsExplicitRetry() {
        val coordinator = coordinator()
        show(coordinator)
        swatch("cat", 1).performClick()
        mainThread { components.fail = true }
        save()
        node("icon-appearance-error").assertExists()
        node("icon-appearance-confirm").assertIsEnabled()
        swatch("cat", 1).assertIsSelected()
        assertEquals(Task17IconCatalog.swatches[1], coordinator.store.load().iconCatColor)
        mainThread { components.fail = false }
        save()
        node("icon-appearance-error").assertDoesNotExist()
        node("icon-appearance-confirm").assertIsNotEnabled()
        assertEquals(coordinator.store.load().selectedIcon(false), coordinator.store.current())
    }

    fun testPaletteFitsNarrowWidthInBothThemesAndScalesWithLargeText() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(coordinator(), dark = dark, scale = scale, fontScale = 1.3f)
            val host = node("icon-test-host").fetchSemanticsNode().boundsInRoot
            for (id in listOf("light", "dark", "cat")) {
                swatch(id, 0)
                val bounds = Task17IconCatalog.swatches.indices.map { node("icon-swatch:$id:$it").fetchSemanticsNode().boundsInRoot }
                bounds.forEach { assertTrue(it.width > 0 && it.left >= host.left - 1 && it.right <= host.right + 1) }
                bounds.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left + 1) }
            }
            reveal("icon-appearance-confirm", page).assertIsDisplayed().assertIsNotEnabled()
        }
        assertEquals(0, components.writes)
    }
}
