package app.daiyu.android.browser.openminis

import org.junit.Assert.*
import org.junit.Test

/** Exercises the same gate GuardedClient uses, including the actual upstream forwarding boundary. */
class NavigationCallbacksTest {
    private val a = "https://fixture.daiyu.invalid/a"
    private val b = "https://fixture.daiyu.invalid/b"
    private val c = "https://fixture.daiyu.invalid/c"

    private class Harness {
        var live = true
        var approval: (String, String) -> Boolean = { _, _ -> true }
        val approvals = mutableListOf<Pair<String, String>>()
        val rejections = mutableListOf<String>()
        val upstreamFinishes = mutableListOf<String>()
        val recordedHistory = mutableListOf<String>()
        private var suppressHistory = false
        val gate = NavigationCallbacks({ live }, { url, title ->
            approvals += url to title
            approval(url, title)
        }, { rejections += it }, withoutHistory = { action ->
            suppressHistory = true
            try { action() } finally { suppressHistory = false }
        })

        fun load(url: String) {
            assertTrue(gate.begin(url))
            assertTrue(gate.directLoad(url))
            assertTrue(gate.onStarted(url))
        }

        fun finish(url: String, visible: String = url): Boolean =
            gate.onFinished(url, "title", visible) {
                upstreamFinishes += url
                if (!suppressHistory) recordedHistory += url
            }
    }

    @Test fun `request during handoff is denied without poisoning resumed navigation`() {
        val h = Harness()
        h.live = false
        assertTrue(pageRequestBlocked(h.live, a) { h.gate.failed(it) })
        assertEquals(NavigationCallbacks.Phase.IDLE, h.gate.phase)
        assertTrue(h.rejections.isEmpty())
        h.live = true
        assertFalse(pageRequestBlocked(h.live, a) { h.gate.failed(it) })
        h.load(a)
        assertTrue(h.finish(a))
        assertTrue(h.gate.returned(true))
        assertTrue(pageRequestBlocked(h.live, "file:///private") { h.gate.failed(it) })
        assertEquals(NavigationCallbacks.Phase.QUARANTINED, h.gate.phase)
        assertEquals(listOf("BROWSER_OPENMINIS_PAGE_URL_BLOCKED"), h.rejections)
        h.live = false
        assertTrue(pageRequestBlocked(h.live, b) { fail("retired request must stay denied") })
    }

    @Test fun `fresh distinct navigations and reads can continue on same resource`() {
        val h = Harness()
        for (url in listOf(a, b, c)) {
            h.load(url)
            assertTrue(h.finish(url))
            assertEquals(NavigationCallbacks.Phase.FINISHED, h.gate.phase)
            assertTrue(h.gate.returned(true))
            // Idle is reusable for reads/lease migration, not a permanently-disabled resource.
            assertEquals(NavigationCallbacks.Phase.IDLE, h.gate.phase)
        }
        assertEquals(listOf(a, b, c), h.upstreamFinishes)
        assertTrue(h.rejections.isEmpty())
    }

    @Test fun `finish without start cannot complete upstream or write history`() {
        val h = Harness()
        assertTrue(h.gate.begin(a))
        assertTrue(h.gate.directLoad(a))
        assertFalse(h.finish(a))
        assertTrue(h.approvals.isEmpty())
        assertTrue(h.upstreamFinishes.isEmpty())
        assertFalse(h.gate.returned(true)) // Fixed manager timeout can return success.
        assertEquals(NavigationCallbacks.Phase.QUARANTINED, h.gate.phase)
    }

    @Test fun `old legal finish cannot complete later epoch even after new start`() {
        val h = Harness()
        h.load(a)
        assertTrue(h.finish(a))
        assertTrue(h.gate.returned(true))
        assertTrue(h.gate.begin(b))
        assertTrue(h.gate.directLoad(b))
        assertFalse(h.finish(a))
        assertTrue(h.gate.onStarted(b))
        assertFalse(h.finish(a, b))
        assertEquals(listOf(a), h.upstreamFinishes)
        assertTrue(h.finish(b))
        assertTrue(h.gate.returned(true))
        assertEquals(listOf(a, b), h.upstreamFinishes)
        assertEquals(2, h.approvals.size) // No hook at either old finish.
    }

    @Test fun `unmatched observed finish URL cannot become a future epoch identity`() {
        val h = Harness()
        assertFalse(h.finish(a))
        assertFalse(h.gate.begin(a))
        assertTrue(h.approvals.isEmpty())
        assertTrue(h.upstreamFinishes.isEmpty())
    }

    @Test fun `same URL or equivalent fragment URL is quarantined before manager reuse`() {
        for (second in listOf(a, "$a#fragment", "https://FIXTURE.daiyu.invalid:443/a")) {
            val h = Harness()
            h.load(a)
            assertTrue(h.finish(a))
            assertTrue(h.gate.returned(true))
            assertFalse(h.gate.begin(second))
            assertFalse(h.finish(a))
            assertEquals(listOf(a), h.upstreamFinishes)
            assertEquals("BROWSER_OPENMINIS_NAVIGATION_URL_REUSE", h.rejections.single())
        }
    }

    @Test fun `redirect approval and start required before final finish`() {
        val h = Harness()
        h.load(a)
        assertTrue(h.gate.allowOverride(b, true))
        assertFalse(h.finish(a)) // Superseded even before B start.
        assertFalse(h.finish(b)) // Finish cannot manufacture a start.
        assertTrue(h.gate.onStarted(b))
        assertFalse(h.finish(a, b))
        assertTrue(h.finish(b))
        assertTrue(h.gate.returned(true))
        assertEquals(listOf(b), h.upstreamFinishes)
        assertEquals(listOf(b to "title"), h.approvals)
        assertFalse(h.gate.begin(a)) // Redirect source also remains ambiguous forever.
    }

    @Test fun `redirect start without override still requires approval`() {
        val h = Harness()
        h.load(a)
        assertTrue(h.gate.onStarted(b))
        assertFalse(h.finish(a, b))
        assertTrue(h.finish(b))
        assertTrue(h.gate.returned(true))
        assertEquals(listOf(b to "title"), h.approvals)
    }

    @Test fun `history denial or failed consent lookup does not forbid navigation`() {
        for (throws in listOf(false, true)) {
            val h = Harness()
            h.approval = { _, _ -> if (throws) error("approval failed") else false }
            h.load(a)
            assertTrue(h.finish(a))
            assertTrue(h.gate.returned(true))
            assertEquals(listOf(a), h.upstreamFinishes)
            assertTrue(h.recordedHistory.isEmpty())
            assertTrue(h.rejections.isEmpty())
        }
    }

    @Test fun `redirect without history consent still reaches its actual final page`() {
        for (throws in listOf(false, true)) for (useOverride in listOf(false, true)) {
            val h = Harness()
            h.approval = { _, _ -> if (throws) error("approval failed") else false }
            h.load(a)
            if (useOverride) assertTrue(h.gate.allowOverride(b, true))
            assertTrue(h.gate.onStarted(b))
            assertFalse(h.finish(a, b))
            assertTrue(h.finish(b))
            assertTrue(h.gate.returned(true))
            assertEquals(listOf(b), h.upstreamFinishes)
            assertTrue(h.recordedHistory.isEmpty())
        }
    }

    @Test fun `errors timeout cancellation and retirement never become confirmed`() {
        for (mode in listOf("error", "timeout", "cancel", "retire", "failure")) {
            val h = Harness()
            h.load(a)
            when (mode) {
                "error" -> h.gate.failed()
                "timeout" -> assertFalse(h.gate.returned(true))
                "cancel" -> h.gate.retire()
                "retire" -> h.live = false
                "failure" -> assertFalse(h.gate.returned(false))
            }
            assertFalse(h.finish(a))
            assertFalse(h.gate.returned(true))
            assertFalse(h.gate.begin(b))
            assertTrue(h.upstreamFinishes.isEmpty())
        }
    }

    @Test fun `late finish after upstream timeout cannot retroactively confirm`() {
        var now = 0L
        var forwards = 0
        val reasons = mutableListOf<String>()
        val gate = NavigationCallbacks({ true }, { _, _ -> true }, { reasons += it }, nowNanos = { now })
        assertTrue(gate.begin(a, 60_000)) // Host can be longer than pinned upstream's 30 seconds.
        assertTrue(gate.directLoad(a))
        assertTrue(gate.onStarted(a))
        now = 30_000_000_000L
        assertFalse(gate.onFinished(a, "title", a) { forwards++ })
        assertFalse(gate.returned(true))
        assertEquals(0, forwards)
        assertEquals(listOf("BROWSER_OPENMINIS_NAVIGATION_TIMEOUT"), reasons)
    }

    @Test fun `approval that exhausts deadline cannot forward history write`() {
        var now = 0L
        var forwards = 0
        val gate = NavigationCallbacks({ true }, { _, title ->
            if (title.isNotEmpty()) now = 8_000_000_000L
            true
        }, {}, nowNanos = { now })
        assertTrue(gate.begin(a))
        assertTrue(gate.directLoad(a))
        assertTrue(gate.onStarted(a))
        assertFalse(gate.onFinished(a, "title", a) { forwards++ })
        assertEquals(0, forwards)
    }

    @Test fun `forbidden initial redirect and subframe URL cannot reach upstream`() {
        val forbidden = listOf("file:///etc/passwd", "content://provider/private", "minis://local/a",
            "intent://external", "javascript:alert(1)", "data:text/html,hi", "https://accounts.google.com/login")
        for (url in forbidden) {
            val initial = Harness()
            assertFalse(initial.gate.begin(url))
            assertTrue(initial.approvals.isEmpty())
            val redirect = Harness()
            redirect.load(a)
            assertFalse(redirect.gate.allowOverride(url, true))
            assertFalse(redirect.finish(url))
            assertTrue(redirect.upstreamFinishes.isEmpty())
            val subframe = Harness()
            subframe.load(a)
            assertFalse(subframe.gate.allowOverride(url, false))
        }
    }

    @Test fun `allowed subframes neither change main frame identity nor gain history consent`() {
        val h = Harness()
        h.load(a)
        assertTrue(h.gate.allowOverride(b, false))
        assertFalse(h.finish(b, a))
        assertTrue(h.finish(a))
        assertTrue(h.gate.returned(true))
        assertEquals(listOf(a to "title"), h.approvals)
    }

    @Test fun `visible URL mismatch quarantines and no duplicate finish history write`() {
        val h = Harness()
        h.load(a)
        assertFalse(h.finish(a, b))
        assertFalse(h.finish(a))
        assertTrue(h.upstreamFinishes.isEmpty())
        val duplicate = Harness()
        duplicate.load(a)
        assertTrue(duplicate.finish(a))
        assertFalse(duplicate.finish(a))
        assertTrue(duplicate.gate.returned(true))
        assertFalse(duplicate.finish(a))
        assertEquals(listOf(a), duplicate.upstreamFinishes)
    }

    @Test fun `history approval invalidation and throwing upstream are terminal`() {
        val invalidated = Harness()
        invalidated.load(a)
        invalidated.approval = { _, _ -> invalidated.live = false; true }
        assertFalse(invalidated.finish(a))
        assertTrue(invalidated.upstreamFinishes.isEmpty())
        val throwing = Harness()
        throwing.load(a)
        assertFalse(throwing.gate.onFinished(a, "title", a) { error("history store failed") })
        assertFalse(throwing.gate.returned(true))
        assertFalse(throwing.finish(a))
    }

    @Test fun `upstream deferred completion does not confirm until forward fully returns`() {
        val h = Harness()
        h.load(a)
        assertFalse(h.gate.onFinished(a, "title", a) {
            assertEquals(NavigationCallbacks.Phase.FORWARDING, h.gate.phase)
            assertFalse(h.gate.returned(true)) // Reentrant return must never publish success.
        })
        assertEquals(NavigationCallbacks.Phase.QUARANTINED, h.gate.phase)
    }

    @Test fun `unsolicited start direct load and previously used redirect quarantine`() {
        val unsolicited = Harness()
        assertFalse(unsolicited.gate.onStarted(a))
        val direct = Harness()
        assertFalse(direct.gate.directLoad(a))
        val redirect = Harness()
        redirect.load(a)
        assertTrue(redirect.finish(a))
        assertTrue(redirect.gate.returned(true))
        redirect.load(b)
        assertFalse(redirect.gate.allowOverride(a, true))
        assertFalse(redirect.finish(b))
    }
    @Test fun manualNavigationNeverWritesUpstreamHistoryAndTaintsModelReuse() {
        val h = Harness()
        h.gate.enterManual()
        assertFalse(h.gate.begin(b))
        assertTrue(h.gate.manualOverride(a, true))
        assertTrue(h.gate.onStarted(a))
        assertTrue(h.finish(a))
        assertEquals(NavigationCallbacks.Phase.IDLE, h.gate.phase)
        assertTrue(h.approvals.isEmpty())
        assertTrue(h.upstreamFinishes.isEmpty())
        h.gate.leaveManual()
        assertFalse(h.gate.begin(a))
        assertEquals(listOf("BROWSER_OPENMINIS_NAVIGATION_URL_REUSE"), h.rejections)
    }

    @Test fun humanLinkAndRedirectStayManualWithoutApprovingModelHistory() {
        val h = Harness()
        h.gate.enterManual()
        assertTrue(h.gate.manualOverride(a, true))
        assertTrue(h.gate.onStarted(a))
        assertTrue(h.gate.manualOverride(b, true))
        assertTrue(h.gate.onStarted(b))
        assertFalse(h.finish(a, b))
        assertTrue(h.finish(b))
        assertTrue(h.approvals.isEmpty())
        assertTrue(h.upstreamFinishes.isEmpty())
        h.gate.leaveManual()
        h.load(c)
        assertTrue(h.finish(c))
        assertTrue(h.gate.returned(true))
        assertEquals(listOf(c), h.upstreamFinishes)
    }

    @Test fun busyManualPageCannotBeReleasedAndRevokedPageCannotRearm() {
        val h = Harness()
        h.gate.enterManual()
        assertTrue(h.gate.manualOverride(a, true))
        try { h.gate.leaveManual(); fail("busy page released") } catch (_: IllegalStateException) { }
        h.live = false
        assertFalse(h.gate.onStarted(a))
        h.live = true
        assertFalse(h.finish(a))
        assertFalse(h.gate.manualOverride(b, true))
        assertTrue(h.upstreamFinishes.isEmpty())
    }

    @Test fun manualReloadAndBackForwardAllowRepeatedUrlsWithoutAcknowledgingTools() {
        val h = Harness()
        h.gate.enterManual()
        for (url in listOf(a, b, a, b, b)) {
            assertTrue(h.gate.manualOverride(url, true))
            assertTrue(h.gate.onStarted(url))
            assertTrue(h.finish(url))
            assertEquals(NavigationCallbacks.Phase.IDLE, h.gate.phase)
        }
        assertEquals(5L, h.gate.manualSequence)
        assertTrue(h.recordedHistory.isEmpty())
        assertTrue(h.upstreamFinishes.isEmpty())
        assertTrue(h.rejections.isEmpty())
        h.gate.leaveManual()
        assertFalse(h.gate.begin(a))
    }

    @Test fun explicitManualStopAllowsDetachWithoutRearmingAnyToolEpoch() {
        val h = Harness()
        h.gate.enterManual()
        assertTrue(h.gate.manualOverride(a, true))
        assertTrue(h.gate.onStarted(a))
        h.gate.cancelManualLoad()
        assertEquals(NavigationCallbacks.Phase.IDLE, h.gate.phase)
        h.gate.leaveManual()
        assertFalse(h.finish(a))
        assertTrue(h.upstreamFinishes.isEmpty())
        assertFalse(h.gate.begin(a))
    }

    @Test fun cancelledHumanStartCannotRestartLoadingOrConsumeNextNavigation() {
        val h = Harness()
        h.gate.enterManual()
        assertTrue(h.gate.manualOverride(a, true))
        assertTrue(h.gate.onStarted(a))
        h.gate.cancelManualLoad()
        assertFalse(h.gate.onStarted(a))
        assertEquals(NavigationCallbacks.Phase.IDLE, h.gate.phase)
        assertTrue(h.gate.manualOverride(b, true))
        assertFalse(h.gate.onStarted(a))
        assertEquals(NavigationCallbacks.Phase.AWAITING_START, h.gate.phase)
        assertTrue(h.gate.onStarted(b))
        assertFalse(h.gate.onStarted(a))
        assertTrue(h.finish(b))
        assertTrue(h.rejections.isEmpty())
        assertTrue(h.approvals.isEmpty())
        assertTrue(h.upstreamFinishes.isEmpty())
    }

    @Test fun mismatchedHumanFinishDoesNotRetireTabOrApproveModelHistory() {
        val h = Harness()
        h.gate.enterManual()
        assertTrue(h.gate.manualOverride(a, true))
        assertTrue(h.gate.onStarted(a))
        assertFalse(h.finish(a, b))
        assertEquals(NavigationCallbacks.Phase.LOADING, h.gate.phase)
        assertTrue(h.gate.manualOverride(b, true))
        assertTrue(h.gate.onStarted(b))
        assertTrue(h.finish(b))
        assertTrue(h.rejections.isEmpty())
        assertTrue(h.recordedHistory.isEmpty())
        assertTrue(h.upstreamFinishes.isEmpty())
    }

    @Test fun humanRedirectStartWithoutOverrideCanFinishWithoutModelApproval() {
        val h = Harness()
        h.gate.enterManual()
        assertTrue(h.gate.manualOverride(a, true))
        assertTrue(h.gate.onStarted(b))
        assertTrue(h.finish(b))
        assertEquals(NavigationCallbacks.Phase.IDLE, h.gate.phase)
        assertTrue(h.rejections.isEmpty())
        assertTrue(h.approvals.isEmpty())
        assertTrue(h.upstreamFinishes.isEmpty())
    }

    @Test fun freshHumanFormStartWithoutOverrideCanStillLoad() {
        val h = Harness()
        h.gate.enterManual()
        assertTrue(h.gate.onStarted(a))
        assertTrue(h.finish(a))
        assertTrue(h.gate.onStarted(b))
        assertTrue(h.finish(b))
        assertEquals(NavigationCallbacks.Phase.IDLE, h.gate.phase)
        assertTrue(h.rejections.isEmpty())
        assertTrue(h.approvals.isEmpty())
        assertTrue(h.upstreamFinishes.isEmpty())
    }

}
