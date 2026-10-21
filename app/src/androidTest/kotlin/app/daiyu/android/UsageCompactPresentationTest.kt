package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
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
        awaitUniqueTag("conversation-usage-body")
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

    fun testLargeFontCacheDetailsRemainReachable() {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    ConversationTokenUsageDialog(true, ConversationTokenUsageUi(100, 20, 30, 5), {})
                }
            }
        } }
        awaitUniqueTag("conversation-usage-body")
        node("chat-usage-cache-toggle").performClick()
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithText(activity.getString(R.string.stats_model_cache_hit_rate)).scrollToVisible().assertIsDisplayed()
        ui.onNodeWithText("30.0%").assertIsDisplayed()
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
        ui.mainClock.advanceTimeByFrame()
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
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithText("disabled action").assertIsNotEnabled().performTouchInput { click() }
        mainThread { assertEquals(0, calls) }
    }
}
