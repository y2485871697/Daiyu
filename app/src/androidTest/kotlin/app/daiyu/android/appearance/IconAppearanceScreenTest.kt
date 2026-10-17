package app.daiyu.android.appearance

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import app.daiyu.android.ComposeActivityTestCase
import app.daiyu.android.MainActivity
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.appearance.IconAppearance
import app.daiyu.android.domain.appearance.IconComponentSnapshot
import app.daiyu.android.domain.appearance.IconComponentState
import app.daiyu.android.domain.appearance.IconNotificationPort
import app.daiyu.android.domain.appearance.IconThemeMode
import app.daiyu.android.domain.appearance.LauncherComponentPort
import app.daiyu.android.domain.appearance.Task17IconCatalog
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.IconAppearanceScreen
import java.util.UUID

/** Exercises the Compose-to-original-View bridge with isolated preferences and fake PM writes. */
@Suppress("DEPRECATION")
class IconAppearanceScreenTest : ComposeActivityTestCase() {
    private lateinit var activity: MainActivity
    private lateinit var context: Context
    private lateinit var prefix: String
    private val components = object : LauncherComponentPort {
        val states = mutableMapOf<String, IconComponentState>()
        var writes = 0
        override fun validate(componentName: String) = Unit
        override fun snapshot(componentName: String): IconComponentSnapshot {
            val state = states[componentName] ?: IconComponentState.DEFAULT
            return IconComponentSnapshot(state, state == IconComponentState.ENABLED ||
                state == IconComponentState.DEFAULT &&
                componentName == Task17IconCatalog.defaultEntry.componentName(context.packageName))
        }
        override fun setState(componentName: String, state: IconComponentState) {
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

    private fun show(coordinator: IconAppearanceCoordinator) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    IconAppearanceScreen(coordinator = coordinator, onBack = {})
                }
            }
        }
        node("icon-appearance-editor").assertExists()
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun editor(): IconAppearanceSettingsView = descendants(activity.window.decorView)
        .filterIsInstance<IconAppearanceSettingsView>().single()

    private fun save() = mainThread {
        descendants(editor()).filterIsInstance<Button>().single { it.text == "保存并应用图标" }.performClick()
    }

    fun testOriginalSelectorsSaveThroughCoordinatorAndReopenPersistentChoice() {
        val first = coordinator()
        show(first)
        assertEquals(0, components.writes) // Merely rendering the settings page never mutates PM.
        mainThread {
            val selectors = descendants(editor()).filterIsInstance<Spinner>()
            assertEquals(4, selectors.size)
            selectors[0].setSelection(2) // DARK
            selectors[2].setSelection(3) // Green dark background, original palette order.
            selectors[3].setSelection(1) // Purple cat.
        }
        save()
        val saved = first.store.load()
        assertEquals(IconThemeMode.DARK, saved.themeMode)
        assertEquals(Task17IconCatalog.swatches[3], saved.iconDarkColor)
        assertEquals(Task17IconCatalog.swatches[1], saved.iconCatColor)
        assertEquals(saved.selectedIcon(false), first.store.current())
        assertTrue(components.writes > 0)
        val reopened = coordinator()
        show(reopened)
        mainThread {
            val selectors = descendants(editor()).filterIsInstance<Spinner>()
            assertEquals(2, selectors[0].selectedItemPosition)
            assertEquals(3, selectors[2].selectedItemPosition)
            assertEquals(1, selectors[3].selectedItemPosition)
        }
        assertEquals(saved, reopened.store.load())
    }

    fun testMalformedPreferencesDoNotCrashScreenOrChangeLauncherUntilExplicitSave() {
        context.getSharedPreferences("task17_icon_appearance", Context.MODE_PRIVATE)
            .edit().putString("icon_cat_color", "not-an-int").commit()
        val coordinator = coordinator()
        show(coordinator)
        assertEquals(0, components.writes)
        save()
        assertEquals(IconAppearance(), coordinator.store.load())
        assertEquals(Task17IconCatalog.defaultEntry, coordinator.store.current())
        assertTrue(components.writes > 0)
    }
}
