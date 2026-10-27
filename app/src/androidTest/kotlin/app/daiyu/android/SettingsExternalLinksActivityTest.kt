package app.daiyu.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import java.util.concurrent.CopyOnWriteArrayList

class SettingsExternalLinksActivityTest : WiredActivityTestCase() {
    private fun entry(title: String): SemanticsNodeInteraction {
        node("settings-search").assertDoesNotExist()
        if (ui.onAllNodesWithTag("page:APP 信息与更新").fetchSemanticsNodes().isEmpty()) {
            reveal("settings-row:APP 信息与更新", "设置").performClick()
            awaitUniqueTag("page:APP 信息与更新")
        }
        if (title in setOf("ST API", "摸鱼")) {
            val parent = reveal("settings-row:自用中转站", "APP 信息与更新")
            if (ui.onAllNodesWithTag("settings-row:$title").fetchSemanticsNodes().isEmpty()) parent.performClick()
        }
        return reveal("settings-row:$title", "APP 信息与更新")
    }
    private fun intercept(action: () -> Unit): List<Intent> {
        val observed = CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                observed += Intent(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try { action(); instrumentation.waitForIdleSync(); return observed.toList() }
        finally { instrumentation.removeMonitor(monitor) }
    }

    fun testRetainedCommunityAndRelayTargetsPreserveDraftAndSettings() {
        setDraft("retained links unsent")
        val before = fixture.database.exportBackup()
        val settings = fixture.settings.loadForEditing().settings
        val targets = listOf(
            "Telegram 频道" to "https://t.me/+6JhB2iRjjDExYWM1",
            "QQ 群" to "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=174109605&card_type=group&source=qrcode",
            "ST API" to "https://st.123336.xyz/join/ZKjSNbdYUh6RbF5_Kdxjrp-_AoAVcMH9",
            "摸鱼" to "https://www.u354483.nyat.app:35119/register?aff=5U8U292F5RG6",
        )
        openSettings()
        targets.forEach { (title, target) ->
            val intents = intercept { entry(title).performClick() }
            assertEquals(1, intents.size)
            assertEquals(target, intents.single().dataString)
            assertEquals(target.startsWith("https://"), intents.single().hasCategory(Intent.CATEGORY_BROWSABLE))
            assertEquals(before, fixture.database.exportBackup())
            assertEquals(settings, fixture.settings.loadForEditing().settings)
            assertEquals(0, fixture.opens.get())
        }
        backToChat(activity); assertDraft("retained links unsent")
    }

    fun testDonationRemainsUserRequestedPlaceholderWithoutOpeningPayment() {
        setDraft("donation placeholder unsent")
        val before = fixture.database.exportBackup()
        openSettings()
        assertTrue(intercept { entry("给煮波恰饭🍚").assertHasNoClickAction() }.isEmpty())
        node("page:APP 信息与更新").assertExists()
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
        backToChat(activity); assertDraft("donation placeholder unsent")
    }

    fun testReturningFromAppInfoRetiresItsExternalLinkCallback() {
        openSettings()
        val click = checkNotNull(entry("Telegram 频道").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        ui.onNodeWithContentDescription(activity.getString(R.string.action_back)).performClick()
        awaitUniqueTag("page:设置")
        assertTrue(intercept { mainThread { click() } }.isEmpty())
        node("page:设置").assertExists()
        node("page:APP 信息与更新").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }

    fun testPausedOrRetiredCommunityCallbackCannotOpenLink() {
        openSettings()
        val click = checkNotNull(entry("Telegram 频道").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        assertTrue(intercept { realPauseResume(activity, atPause = { click() }) }.isEmpty())
        backToChat(activity)
        assertTrue(intercept { mainThread { click() } }.isEmpty())
        assertEquals(0, fixture.opens.get())
    }
}
