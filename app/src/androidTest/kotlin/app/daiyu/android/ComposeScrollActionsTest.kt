package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

class ComposeScrollActionsTest : WiredActivityTestCase() {
    private fun show(content: @Composable () -> Unit) {
        mainThread { activity.setContent(content = content) }
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
    }

    fun testColumnScrollDrivesQueuedAnimationBeforeRealClick() {
        val state = ScrollState(0)
        var clicks = 0
        show {
            Column(Modifier.height(180.dp).verticalScroll(state)) {
                repeat(20) { index ->
                    BasicText("entry $index", Modifier.height(40.dp).testTag("entry:$index")
                        .clickable { clicks++ })
                }
            }
        }
        ui.onNodeWithTag("entry:18").scrollToVisible().assertIsDisplayed().performClick()
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
        assertTrue(state.value > 0)
        assertEquals(1, clicks)
    }

    fun testLazySearchComposesFarTargetAndPreservesContainerReturnValue() {
        val state = LazyListState()
        show {
            LazyColumn(Modifier.height(180.dp).testTag("scroll"), state = state) {
                items(100) { index -> BasicText("entry $index", Modifier.height(40.dp).testTag("entry:$index")) }
            }
        }
        val container = ui.onNodeWithTag("scroll")
        assertSame(container, container.scrollToDescendant(hasTestTag("entry:99")))
        ui.onNodeWithTag("entry:99").assertIsDisplayed()
        assertTrue(state.firstVisibleItemIndex > 0)
    }

    fun testHorizontalRtlReverseSearchFindsOffscreenTarget() {
        val state = LazyListState()
        show {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                LazyRow(Modifier.width(180.dp).testTag("scroll"), state = state, reverseLayout = true) {
                    items(40) { index -> BasicText("entry $index", Modifier.width(60.dp).testTag("entry:$index")) }
                }
            }
        }
        ui.onNodeWithTag("scroll").scrollToDescendant(hasTestTag("entry:30"))
        ui.onNodeWithTag("entry:30").assertIsDisplayed()
        assertTrue(state.firstVisibleItemIndex > 0)
    }

    fun testMissingTargetFailsAtContainerEnd() {
        show {
            LazyColumn(Modifier.height(120.dp).testTag("scroll")) {
                items(5) { BasicText("entry $it", Modifier.height(40.dp)) }
            }
        }
        var failure: AssertionError? = null
        try { ui.onNodeWithTag("scroll").scrollToDescendant(hasTestTag("missing")) }
        catch (caught: AssertionError) { failure = caught }
        assertNotNull(failure)
        assertTrue(checkNotNull(failure).message.orEmpty().contains("Target not found before end"))
    }

    fun testNestedMatchingTargetScrollsNearestAncestorAndAcceptsFirstMatch() {
        val outer = ScrollState(0)
        val inner = ScrollState(0)
        show {
            Column(Modifier.height(120.dp).testTag("outer").verticalScroll(outer)) {
                Row(Modifier.width(120.dp).horizontalScroll(inner)) {
                    BasicText("spacer", Modifier.width(300.dp))
                    BasicText("target", Modifier.width(60.dp).testTag("same"))
                    BasicText("target", Modifier.width(60.dp).testTag("same"))
                }
            }
        }
        ui.onNodeWithTag("outer").scrollToDescendant(hasTestTag("same"))
        assertTrue(inner.value > 0)
        assertEquals(0, outer.value)
        ui.onAllNodesWithTag("same")[0].assertIsDisplayed()
    }

    fun testMissingNonLazyTargetFailsWithoutChangingPosition() {
        val state = ScrollState(0)
        show {
            Column(Modifier.height(120.dp).testTag("scroll").verticalScroll(state)) {
                repeat(5) { BasicText("entry $it", Modifier.height(40.dp)) }
            }
        }
        var failure: AssertionError? = null
        try { ui.onNodeWithTag("scroll").scrollToDescendant(hasTestTag("missing")) }
        catch (caught: AssertionError) { failure = caught }
        assertNotNull(failure)
        assertTrue(checkNotNull(failure).message.orEmpty().contains("non-lazy scroll container"))
        assertEquals(0, state.value)
    }

    fun testEmptyLazyTargetFailsAtEndWithoutInvalidIndexReset() {
        show { LazyColumn(Modifier.height(120.dp).testTag("scroll")) {} }
        var failure: AssertionError? = null
        try { ui.onNodeWithTag("scroll").scrollToDescendant(hasTestTag("missing")) }
        catch (caught: AssertionError) { failure = caught }
        assertNotNull(failure)
        assertTrue(checkNotNull(failure).message.orEmpty().contains("Target not found before end"))
    }

    fun testUnmergedTargetKeepsHiddenScrollAncestorIdentity() {
        val state = ScrollState(0)
        show {
            Box(Modifier.semantics(mergeDescendants = true) {}) {
                Column(Modifier.height(120.dp).verticalScroll(state)) {
                    repeat(10) { BasicText("entry $it", Modifier.height(40.dp).testTag("entry:$it")) }
                }
            }
        }
        ui.onNodeWithTag("entry:9", useUnmergedTree = true).scrollToVisible().assertIsDisplayed()
        assertTrue(state.value > 0)
    }

    fun testInnerScrollUsesOwnViewportWhileOuterContainerIsOffscreen() {
        val outer = ScrollState(0)
        val inner = ScrollState(0)
        show {
            Column(Modifier.height(120.dp).verticalScroll(outer)) {
                Box(Modifier.height(300.dp))
                Column(Modifier.height(120.dp).verticalScroll(inner)) {
                    repeat(10) { BasicText("entry $it", Modifier.height(40.dp).testTag("entry:$it")) }
                }
            }
        }
        ui.onNodeWithTag("entry:9").scrollToVisible()
        assertTrue(inner.value > 0)
        assertEquals(0, outer.value)
        ui.onNodeWithTag("entry:9").assertIsNotDisplayed()
    }

    fun testAcceptedButUnconsumedScrollFailsWithProgressDiagnostic() {
        show {
            Box(Modifier.size(100.dp).clipToBounds().semantics {
                verticalScrollAxisRange = ScrollAxisRange({ 0f }, { 500f })
                scrollBy { _, _ -> true }
            }) {
                BasicText("offscreen", Modifier.offset(y = 300.dp).testTag("offscreen"))
            }
        }
        var failure: AssertionError? = null
        try { ui.onNodeWithTag("offscreen").scrollToVisible() }
        catch (caught: AssertionError) { failure = caught }
        assertNotNull(failure)
        assertTrue(checkNotNull(failure).message.orEmpty().contains("Scroll made no progress"))
    }
    fun testPageClickScrollAvoidsOverlayingTopBar() {
        val state = ScrollState(0)
        var clicks = 0
        show {
            Box {
                Column(Modifier.width(180.dp).height(180.dp).testTag("page:overlay").verticalScroll(state)) {
                    Box(Modifier.height(200.dp))
                    BasicText("target", Modifier.height(40.dp).testTag("target").clickable { clicks++ })
                    Box(Modifier.height(400.dp))
                }
                Box(Modifier.height(90.dp).width(180.dp).testTag("page-topbar:overlay").clickable {})
            }
        }
        val target = ui.onNodeWithTag("target")
        val top = target.fetchSemanticsNode().positionInRoot.y
        ui.onNodeWithTag("page:overlay").performSemanticsAction(SemanticsActions.ScrollBy) {
            assertTrue(it(0f, top))
        }
        ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
        assertTrue(target.fetchSemanticsNode().boundsInRoot.center.y <
            ui.onNodeWithTag("page-topbar:overlay").fetchSemanticsNode().boundsInRoot.bottom)
        target.scrollToVisible().performClick()
        ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
        assertEquals(1, clicks)
    }
}
