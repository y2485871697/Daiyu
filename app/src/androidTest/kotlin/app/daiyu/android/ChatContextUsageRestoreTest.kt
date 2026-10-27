package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.ui.screens.chat.conversation.ComposerPhase
import app.daiyu.android.ui.screens.chat.conversation.rememberChatContextUsage
import java.util.concurrent.TimeUnit

class ChatContextUsageRestoreTest : WiredActivityTestCase() {
    fun testReadyTransitionAndRemountReadSavedActualWithoutANewRequest() {
        val session = fixture.session()
        val conversation = checkNotNull(fixture.controller.send(null, "context fixture"))
        try {
            assertTrue(session.entered.await(5, TimeUnit.SECONDS))
            checkNotNull(session.sink.get())(ModelEvent.Usage(8_162, 10))
        } finally { session.release.countDown() }
        awaitControl("context fixture completed") { fixture.controller.active == null && !fixture.controller.dataBusy }
        val expected = fixture.controller.cloudContextUsage(conversation.id)
        assertEquals(8_162L, expected.inputTokens)
        val before = fixture.database.exportBackup()
        val opens = fixture.opens.get()
        val ready = mutableStateOf(false)
        fun mount() = mainThread { activity.setContent {
            val usage = rememberChatContextUsage(fixture.controller, conversation.id, 0, 0, 0,
                ComposerPhase.IDLE, ready = ready.value)
            BasicText(usage.tokensLabel())
        } }
        mount()
        ui.onNodeWithText(expected.tokensLabel()).assertDoesNotExist()
        mainThread { ready.value = true }
        ui.waitUntil(5_000) { ui.onAllNodesWithText(expected.tokensLabel()).fetchSemanticsNodes().size == 1 }
        ui.onNodeWithText(expected.tokensLabel()).assertIsDisplayed()
        mainThread { activity.setContent { BasicText("unmounted") } }
        mount()
        ui.waitUntil(5_000) { ui.onAllNodesWithText(expected.tokensLabel()).fetchSemanticsNodes().size == 1 }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(opens, fixture.opens.get())
    }
}
