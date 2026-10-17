package app.daiyu.android

import app.daiyu.android.gui.GuiOperation
import app.daiyu.android.gui.GuiRuntime
import androidx.compose.ui.test.*

/** Real Main user callback -> production Controller reservation; no fake service grants.
 * Successful external-app confirmation still requires a separate device acceptance run. */
@Suppress("DEPRECATION")
class GuiMainActivityTest : WiredActivityTestCase() {
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
        fixture.session(held = false)
        node("chat-send").performClick()
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
        assertDraft(text)
        assertNull(fixture.controller.guiEntry())
        assertNull(fixture.controller.textSendState())
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
        // Failed open must release the Controller preparation, not leave only its accessor empty.
        mainThread {
            assertNull(fixture.controller.prepareGuiSend(null, ChatController.TextSendOwner(null, 9, 11),
                text, GuiOperation.HOME, null))
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
