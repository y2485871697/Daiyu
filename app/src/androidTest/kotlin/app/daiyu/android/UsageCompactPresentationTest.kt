package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.app.ConversationTokenUsageDialog
import app.daiyu.android.ui.components.UsageAction
import app.daiyu.android.ui.components.UsageGlassDialog
import app.daiyu.android.ui.model.ConversationTokenUsageUi

/** Real presentation assertions; fixture counts are never installed in production. */
class UsageCompactPresentationTest : WiredActivityTestCase() {
    fun testCacheRowsAreInitiallyCollapsedAndRevealActualCounts() {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ConversationTokenUsageDialog(true,
                        ConversationTokenUsageUi(inputTokens = 100, outputTokens = 20,
                            cachedTokens = 30, cacheCreationTokens = 5), onDismiss = {})
                }
            }
        }
        ui.onNodeWithText("120").assertExists()
        ui.onNodeWithText("65").assertExists()
        val cachedLabel = activity.getString(R.string.stats_page_cached_tokens)
        ui.onNodeWithText(cachedLabel).assertDoesNotExist()
        ui.onNodeWithText("▸ 缓存明细").performClick()
        ui.onNodeWithText(cachedLabel).assertExists()
        ui.onNodeWithText("30").assertExists()
        ui.onNodeWithText("▾ 缓存明细").performClick()
        ui.onNodeWithText(cachedLabel).assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }

    fun testTouchingDialogTitleDoesNotDismissTheCard() {
        var dismissals = 0
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    UsageGlassDialog("inside card", onDismiss = { dismissals++ }, compact = true) {}
                }
            }
        }
        ui.onNodeWithText("inside card").performTouchInput { click() }
        mainThread { assertEquals(0, dismissals) }
    }

    fun testDisabledLocalActionDoesNotDispatchCallback() {
        var calls = 0
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    UsageAction("disabled action", onClick = { calls++ }, enabled = false)
                }
            }
        }
        ui.onNodeWithText("disabled action").assertIsNotEnabled().performTouchInput { click() }
        mainThread { assertEquals(0, calls) }
    }
}
