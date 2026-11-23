package app.daiyu.android.integration.tools

import android.app.Application
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.BrowserSettings
import app.daiyu.android.browser.session.BrowserAccess
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, manifest = Config.NONE)
class RunOrdinaryBrowserPreparationTest {
    @get:org.junit.Rule val liveOwners = app.daiyu.android.integration.tools.LiveRunnerOwners()
    private fun owner(): RunExecutionOwner =
        liveOwners.issue(Message(MessageId("reply"), ConversationId("conversation"),
            RunId("run"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))

    @Test fun firstNavigationAndNewTabUrlPrepareThroughTheActualOrdinaryLeafWithoutOpeningHost() {
        var opened = 0
        val leaf = RunOrdinaryBrowserTools(RuntimeEnvironment.getApplication(), owner(),
            TaskAuthorization("run", "run"), AssistantProfile("profile", "Profile", ""), MessageId("reply"),
            BrowserSettings(BrowserAccess.FULL), { opened++ })
        try {
            val write = leaf.tools.single { it.definition.name == RunBrowserTools.WRITE_TOOL_NAME }
            for (raw in listOf("""{"action":"navigate","url":"https://example.invalid/first"}""",
                """{"action":"new_tab","url":"https://example.invalid/new"}""")) {
                val prepared = write.prepare(write.definition.schema.parse(raw))
                assertNotNull(prepared.writeEffect)
            }
            assertEquals(0, opened)
        } finally { leaf.close() }
    }
}
