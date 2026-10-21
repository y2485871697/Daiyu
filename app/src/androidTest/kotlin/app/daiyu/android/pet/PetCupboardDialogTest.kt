package app.daiyu.android.pet

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import app.daiyu.android.R
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.domain.pet.*
import app.daiyu.android.ui.app.AgentAppTheme

class PetCupboardDialogTest : WiredActivityTestCase() {
    private var controller: PetController? = null
    private var layer: FrameLayout? = null
    private var store: InMemoryPetStore? = null
    private var requests = 0
    private fun mount(satiety: Int = 0) {
        mainThread {
            store = InMemoryPetStore(PetState(settings = PetSettings(enabled = true, scale = .65f),
                satiety = satiety, memories = listOf(PetMemory(PetMood.HAPPY, "原有发言", 0, 0, PetEvent.FEED))))
            controller = PetController(checkNotNull(store), PetModelPort { _, _ -> requests++; PetCancel {} },
                AndroidPetScheduler(), PetClock { 0 })
            val root = FrameLayout(activity)
            root.addView(PetViewFactory.createPet(activity, checkNotNull(controller), WhaleMaidResources(activity)))
            layer = root
            activity.setContent { AndroidView(factory = { root }) }
        }
    }
    private fun open() {
        onView(withId(R.id.pet_sprite)).perform(click())
        awaitUniqueTag("pet-cupboard")
    }
    private fun reveal(tag: String) {
        node("pet-cupboard").scrollToDescendant(hasTestTag(tag))
    }
    override fun cleanupFixture() {
        mainThread { layer?.removeAllViews(); layer = null; controller?.close(); controller = null }
        super.cleanupFixture()
    }

    fun testSpriteTapOpensOriginalCupboardAndCommitsExistingActions() {
        mount(); open()
        ui.onNodeWithText("女仆储物橱柜").assertIsDisplayed()
        node("pet-cupboard-satiety").assertTextEquals("0 / 30000 (0%)")
        ui.onNodeWithText("快要饿死了").assertIsDisplayed()
        reveal("pet-cupboard-reset")
        node("pet-cupboard-reset").assertTextEquals("65%  重置").performClick()
        node("pet-cupboard-reset").assertTextEquals("100%  重置")
        node("pet-cupboard-scale").performSemanticsAction(SemanticsActions.SetProgress) { it(65f) }
        node("pet-cupboard-reset").assertTextEquals("65%  重置")
        reveal("pet-cupboard-clear"); node("pet-cupboard-clear").performClick()
        mainThread {
            assertTrue(checkNotNull(controller).snapshot().state.memories.isEmpty())
            assertEquals(.65f, checkNotNull(store).load().settings.scale)
        }
        ui.onNodeWithText("原有发言", substring = true).assertDoesNotExist()
        val expected = listOf(1, 1001, 4001, 14001)
        PetFood.entries.forEachIndexed { index, food ->
            reveal("pet-cupboard-food:${food.name}")
            node("pet-cupboard-food:${food.name}").performClick()
            awaitVisualIdle()
            node("pet-cupboard").assertDoesNotExist()
            mainThread { assertEquals(expected[index], checkNotNull(store).load().satiety) }
            open()
        }
        assertEquals(0, requests)
    }

    fun testHiddenAndDisabledSpriteClosesWindowAndFencesRetainedCallbacks() {
        mount(); open()
        reveal("pet-cupboard-clear")
        val oldClear = node("pet-cupboard-clear").fetchSemanticsNode().config[PetCupboardClearAction]
        mainThread { checkNotNull(layer).visibility = View.GONE }
        node("pet-cupboard").assertDoesNotExist()
        mainThread {
            oldClear()
            assertEquals(1, checkNotNull(controller).snapshot().state.memories.size)
            checkNotNull(layer).visibility = View.VISIBLE
        }
        open()
        mainThread {
            oldClear()
            assertEquals(1, checkNotNull(controller).snapshot().state.memories.size)
        }
        node("pet-cupboard").assertExists()
        mainThread { checkNotNull(controller).setEnabled(false) }
        node("pet-cupboard").assertDoesNotExist()
        assertEquals(0, requests)
    }

    fun testDragDoesNotOpenCupboardButFollowingTapDoes() {
        mount()
        mainThread {
            val sprite = checkNotNull(layer).findViewById<View>(R.id.pet_sprite)
            val now = android.os.SystemClock.uptimeMillis()
            fun event(action: Int, x: Float) = MotionEvent.obtain(now, now + 30, action, x, 20f, 0)
                .also { sprite.dispatchTouchEvent(it); it.recycle() }
            event(MotionEvent.ACTION_DOWN, 20f)
            event(MotionEvent.ACTION_MOVE, 100f * activity.resources.displayMetrics.density)
            event(MotionEvent.ACTION_UP, 100f * activity.resources.displayMetrics.density)
        }
        node("pet-cupboard").assertDoesNotExist()
        open()
        ui.onNodeWithText("女仆储物橱柜").assertIsDisplayed()
    }

    fun testLargeFontCupboardKeepsAllOriginalControlsReachableInBothThemes() {
        val assets = WhaleMaidResources(instrumentation.targetContext)
        for (dark in listOf(false, true)) {
            mainThread {
                activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                        AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), false) {
                            PetCupboardContent(PetSnapshot(PetState(settings = PetSettings(enabled = true),
                                memories = List(20) { PetMemory(PetMood.HAPPY, "发言$it", 0, 0, PetEvent.FEED) })),
                                assets, onFeed = {}, onScale = {}, onClear = {})
                        }
                    }
                }
            }
            awaitUniqueTag("pet-cupboard")
            reveal("pet-cupboard-food:POT"); node("pet-cupboard-food:POT").assertIsDisplayed()
            reveal("pet-cupboard-scale"); node("pet-cupboard-scale").assertIsDisplayed()
            reveal("pet-cupboard-clear"); node("pet-cupboard-clear").assertIsDisplayed()
            node("pet-cupboard").scrollToDescendant(hasText("[happy] 发言19"))
            ui.onNodeWithText("[happy] 发言19").assertIsDisplayed()
        }
    }
}
