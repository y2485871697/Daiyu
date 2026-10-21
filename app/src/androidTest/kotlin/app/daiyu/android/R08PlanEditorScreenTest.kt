package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import app.daiyu.android.integration.tools.R08VirtualDisplayAction
import app.daiyu.android.vdapproval.*

/** Isolated controller host for the editor; never launches a runtime, accepts text or requests root. */
@Suppress("DEPRECATION")
class R08PlanEditorScreenTest : WiredActivityTestCase() {
    private val visible = mutableStateOf<R08PlanEditorHost?>(null)
    private val frozen = mutableListOf<Pair<R08PlanEditorHost, R08FrozenPlan>>()
    private val cancelled = mutableListOf<R08PlanEditorHost>()

    override fun setUp() {
        super.setUp()
        mainThread {
            activity.setContent {
                MaterialTheme {
                    visible.value?.let { host ->
                        R08PlanEditor(host, onFrozen = { frozen.add(host to it) }, onCancel = { cancelled.add(host) })
                    }
                }
            }
        }
    }

    private fun reveal(tag: String) {
        dismissIme()
        node("r08-plan-rows").scrollToDescendant(hasTestTag(tag))
        node(tag).scrollToVisible().assertIsDisplayed()
    }
    private fun add(action: String) { reveal("r08-add-$action"); node("r08-add-$action").performClick() }
    private fun edit(index: Int, field: String, value: String) {
        val tag = "r08-plan-$index-$field"
        reveal(tag)
        node(tag).performSemanticsAction(SemanticsActions.SetText) { setText ->
            assertTrue(setText(AnnotatedString(value)))
        }.assertEditableText(value)
    }

    fun testActualFieldsFreezeLaunchTapSwipeTextKeyAndReadParameters() {
        val host = R08PlanEditorHost()
        mainThread { visible.value = host }
        assertTrue(frozen.isEmpty())
        assertTrue(cancelled.isEmpty())
        add("LAUNCH"); edit(2, "package", "org.example.notes")
        edit(2, "component", "org.example.notes/.MainActivity")
        add("TAP"); edit(3, "x", "123"); edit(3, "y", "456")
        add("SWIPE"); edit(4, "durationMs", "789")
        add("TEXT"); edit(5, "text", "exact editor text")
        add("KEY"); edit(6, "keyCode", "66")
        add("HANDOFF"); add("RELEASE"); add("STATUS"); add("SNAPSHOT")
        edit(10, "include", "false"); edit(10, "maxBytes", "4096")
        node("r08-plan-freeze").performClick()
        assertSame(host, frozen.single().first)
        val plan = frozen.single().second
        assertEquals(R08VirtualDisplayAction.Launch("org.example.notes", "org.example.notes/.MainActivity"),
            plan.writes[2].action)
        assertEquals(R08VirtualDisplayAction.Handoff, plan.writes[7].action)
        assertEquals(R08VirtualDisplayAction.Release, plan.writes[8].action)
        assertEquals(R08VirtualDisplayAction.Tap(123, 456), plan.writes[3].action)
        assertEquals(789, (plan.writes[4].action as R08VirtualDisplayAction.Swipe).durationMs)
        assertEquals(R08VirtualDisplayAction.Text("exact editor text"), plan.writes[5].action)
        assertEquals(R08VirtualDisplayAction.Key(66), plan.writes[6].action)
        assertFalse(plan.reads.last().include)
        assertEquals(4096, plan.reads.last().maxBytes)
        node("r08-plan-freeze").assertIsNotEnabled()
        mainThread { visible.value = null }
        assertTrue(cancelled.isEmpty())
    }

    fun testInvalidParametersCannotFreezeAndReplacementDisposesOnlyOriginalHost() {
        val old = R08PlanEditorHost()
        mainThread { visible.value = old }
        add("TAP"); edit(2, "x", "-1")
        node("r08-plan-freeze").performClick()
        node("r08-plan-error").assertExists()
        assertTrue(frozen.isEmpty())
        val current = R08PlanEditorHost()
        mainThread { visible.value = current }
        ui.waitForIdle()
        assertEquals(listOf(old), cancelled)
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
        assertEquals(listOf(old, current), cancelled)
        assertTrue(frozen.isEmpty())
    }
}
