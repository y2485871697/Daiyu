package app.daiyu.android

import app.daiyu.android.gui.GuiOperation
import app.daiyu.android.gui.GuiRuntime
import androidx.compose.ui.test.*
import app.daiyu.android.tasksurface.*

/** Real Main user callback -> production Controller reservation; no fake service grants.
 * Successful external-app confirmation still requires a separate device acceptance run. */
@Suppress("DEPRECATION")
class GuiMainActivityTest : WiredActivityTestCase() {
    override val engineeringUi = true
    private fun invoke(name: String, vararg args: Any?): Any? =
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)

    private fun prepareAsk(text: String): ControllerGuiEntry {
        check(fixture.taskSurfaces.save(TaskSurfacePreference.ASK))
        setDraft(text)
        node("gui-prepare-entry").performClick()
        node("prompt-input").performTextReplacement("{\"operation\":\"home\"}")
        node("dialog-confirm").performClick()
        awaitControl("original GUI question is pending before any backend") {
            fixture.controller.guiEntry()?.taskSurface?.decision() === TaskSurfaceDecision.AskPending
        }
        return checkNotNull(fixture.controller.guiEntry())
    }

    fun testPendingLocationNavigationAndOldResolvedCallbackCannotOpenOrCancelReplacement() {
        val text = "pending location preserves exact draft"
        val original = prepareAsk(text)
        lateinit var oldVisit: Any
        mainThread {
            oldVisit = checkNotNull(invoke("getTaskLocationVisit"))
            assertNull(original.session)
            assertFalse(original.submitted)
            activity.select(null)
            assertTrue(original.revoked)
            assertNull(fixture.controller.guiEntry())
        }
        val next = prepareAsk(text)
        mainThread {
            val nextVisit = checkNotNull(invoke("getTaskLocationVisit"))
            assertNotSame(oldVisit, nextVisit)
            // Exact production onResolved path retained from the old dialog, not a new default.
            invoke("taskLocationResolved", oldVisit, TaskSurface.FOREGROUND)
            assertSame(nextVisit, invoke("getTaskLocationVisit"))
            assertSame(next, fixture.controller.guiEntry())
            assertFalse(next.revoked)
            assertNull(next.session)
        }
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
        assertDraft(text)
    }

    fun testOrdinaryGuiBackgroundPreferenceRejectsWithoutServiceOrModelFallback() {
        check(fixture.taskSurfaces.save(TaskSurfacePreference.BACKGROUND))
        val text = "background GUI is not a foreground fallback"
        setDraft(text)
        node("gui-prepare-entry").performClick()
        node("prompt-input").performTextReplacement("{\"operation\":\"home\"}")
        node("dialog-confirm").performClick()
        awaitControl("unsupported GUI background request completely rejected") { fixture.controller.guiEntry() == null }
        assertNull(fixture.controller.textSendState())
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
        assertDraft(text)
    }

    fun testInvalidFullParametersNeverReserveOrSaveAndOrdinarySendRemainsOrdinary() {
        val text = "  GUI draft must remain exact  "
        setDraft(text)
        node("gui-prepare-entry").assertIsEnabled().performClick()
        node("prompt-input").performTextReplacement("{\"operation\":\"input\",\"text\":null}")
        node("dialog-confirm").performClick()
        assertDraft(text)
        assertNull(fixture.controller.guiEntry())
        assertNull(fixture.controller.textSendState())
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
        val ordinary = fixture.session(held = false)
        node("chat-send").assertIsEnabled()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ordinary.entered.count == 0L && fixture.opens.get() == 1
        }
        awaitTextSendResult(activity)
        awaitControl("ordinary model opened without GUI") { fixture.opens.get() == 1 }
        assertNull(fixture.controller.guiEntry())
    }

    fun testDisabledServiceRejectsProductionReservationWithoutChangingDraftOrStartingModel() {
        var connected = true
        mainThread { connected = GuiRuntime.isConnected() }
        // Do not revoke or otherwise operate on a user's already enabled system service.
        assertFalse("Requires a disposable device with GUI service disconnected; no user grant is modified", connected)
        val text = "  exact GUI input draft\nkeep whitespace  "
        setDraft(text)
        node("gui-prepare-entry").performClick()
        node("prompt-input").performTextReplacement("{\"operation\":\"input\",\"text\":\"complete input\"}")
        node("dialog-confirm").performClick()
        awaitControl("foreground location resolved and unavailable service rejected") {
            fixture.controller.guiEntry() == null
        }
        assertDraft(text)
        assertNull(fixture.controller.guiEntry())
        assertNull(fixture.controller.textSendState())
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
        // Failed open must release the Controller preparation, not leave only its accessor empty.
        lateinit var reserved: ControllerGuiEntry
        mainThread {
            reserved = checkNotNull(fixture.controller.prepareGuiSend(null, ChatController.TextSendOwner(null, 9, 11),
                text, GuiOperation.HOME, null))
            assertNull(reserved.session)
            assertFalse(fixture.controller.openPreparedGui(reserved))
        }
        assertEquals(TaskSurface.FOREGROUND, (reserved.taskSurface.freezeOnWorker() as TaskSurfaceDecision.Ready).surface)
        mainThread {
            assertFalse(fixture.controller.openPreparedGui(reserved))
            assertNull(fixture.controller.guiEntry())
        }
        node("chat-send").assertIsEnabled()
    }

    fun testCancelledPromptAndRetainedConfirmationCannotCreateGuiRun() {
        setDraft("draft before cancelled GUI prompt")
        node("gui-prepare-entry").performClick()
        val retained = retainedClick(activity, "dialog-confirm")
        node("dialog-cancel").performClick()
        mainThread { retained() }
        assertNull(fixture.controller.guiEntry())
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
        assertDraft("draft before cancelled GUI prompt")
    }
}
