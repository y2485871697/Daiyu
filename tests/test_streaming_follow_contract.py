"""检查按帧驱动的聊天跟随源码连接。"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatMessageList.kt"
DEVICE = ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatStreamingFollowTest.kt"


class StreamingFollowContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = CHAT.read_text()
        cls.guard = cls.source.split("fun canFollowBottom(): Boolean =", 1)[1].split("\n\n", 1)[0]
        cls.follow = cls.source.split("// Keep a single motion", 1)[1].split(
            "    app.daiyu.android.diagnostics.ObserveChatRecovery(", 1)[0]

    def test_height_updates_do_not_restart_the_motion_collector(self):
        self.assertIn("LaunchedEffect(listState, resumeFollowEpoch)", self.follow)
        self.assertIn("snapshotFlow {", self.follow)
        self.assertIn("Triple(canFollowBottom(),", self.follow)
        self.assertIn("val motion = BottomFollowMotion()", self.follow)
        self.assertNotIn("while (true)", self.follow)
        self.assertIn("withFrameNanos", self.follow)
        self.assertNotIn("alignViewport", self.follow)
        self.assertIn("observeFollowViewport(layout)", self.follow)
        alignment = self.follow.split("if (alignment) {", 1)[1].split("}", 1)[0]
        self.assertIn("motion.reset()", alignment)
        self.assertNotIn("LaunchedEffect(turns", self.follow)

    def test_disabled_follow_does_not_observe_layout_until_ownership_returns(self):
        guard = "if (!canFollowBottom()) return@snapshotFlow null"
        observation = self.follow.split("snapshotFlow {", 1)[1].split("}.collect { target ->", 1)[0]
        self.assertLess(observation.index(guard), observation.index("val layout = listState.layoutInfo"))
        self.assertLess(observation.index(guard), observation.index("val tail = currentTurns.lastOrNull()"))
        self.assertIn("if (target == null) {", self.follow)
        disabled = self.follow.split("if (target == null) {", 1)[1].split("return@collect", 1)[0]
        self.assertIn("motion.reset()", disabled)
        self.assertIn("clearanceLayout.viewportLoss.reset()", disabled)
        self.assertIn("LaunchedEffect(listState, resumeFollowEpoch)", self.follow)
        self.assertIn("while (canFollowBottom())", self.follow)
        for dependency in ("!initialPositionPending", "pageActive", "scrollResumed", "followBottom",
                           "!currentDragging", "currentTailIndex >= 0", "consumedScrollSerial",
                           "!currentNestedTail.migrating"):
            self.assertIn(dependency, self.guard)

    def test_measured_overflow_is_integrated_not_paid_as_a_single_scroll(self):
        self.assertIn("val followDistance = maxOf(overflow.toFloat(), obscuredTail.toFloat())", self.follow)
        self.assertIn("if (followDistance <= 0f)", self.follow)
        self.assertIn("val alignment = resumeFollowPending && !currentNestedTail.textClearance.pacedFollowing", self.follow)
        self.assertIn("var delta = if (alignment) followDistance else 0f", self.follow)
        self.assertIn("val frameFollowDistance = maxOf(frameOverflow.toFloat(), frameObscuredTail.toFloat())", self.follow)
        self.assertIn("delta = motion.step(frameFollowDistance, frameTimeNanos, currentDensity,", self.follow)
        self.assertNotIn("delta = maxOf(", self.follow)
        self.assertNotIn("System.nanoTime()", self.follow)
        self.assertIn("listState.scrollBy(delta)", self.follow)
        self.assertIn("if (followDistance <= 0f)", self.follow)
        self.assertIn("while (canFollowBottom())", self.follow)
        self.assertNotIn("alignViewport || resumeFollowPending) overflow.toFloat() else {", self.follow)
        self.assertNotIn("animateScrollToItem", self.follow)
        self.assertIn("maxOf(frameOverflow.toFloat(), frameObscuredTail.toFloat())", self.follow)

    def test_settled_follow_observes_real_extent_and_reconciles_only_stale_lazy_geometry(self):
        self.assertIn("Triple(resumeReady, currentNestedTail.contentHeight, Triple(currentTailKey, currentNestedTail, currentNestedTail.textClearance.typingPending))", " ".join(self.follow.split()))
        self.assertIn("currentNestedTail.bounded && currentTailKey == assistantOwnerKey", self.follow)
        self.assertIn("currentNestedTail.contentHeight > 0 && last.size != currentNestedTail.contentHeight", self.follow)
        stale = self.follow.split("last.size != currentNestedTail.contentHeight", 1)[1].split("observeFollowViewport(layout)", 1)[0]
        self.assertIn("clearanceLayout.remeasurement?.forceRemeasure()", stale)
        self.assertIn("layout = listState.layoutInfo", stale)
        self.assertIn("it.index == index && it.key == currentTailKey", stale)
        self.assertNotIn("scrollBy", stale)
        self.assertIn("delta = motion.step(frameFollowDistance, frameTimeNanos, currentDensity,", self.follow)

    def test_pending_text_preserves_only_the_current_owner_motion_phase(self):
        self.assertIn("var motionOwner: String? = null", self.follow)
        self.assertIn("if (motionOwner != currentTailKey)", self.follow)
        self.assertIn("motionOwner = currentTailKey", self.follow)
        self.assertIn("continuous = currentNestedTail.textClearance.typingPending", self.follow)
        idle = self.follow.split("if (followDistance <= 0f)", 1)[1].split("val alignment =", 1)[0]
        self.assertIn("if (currentNestedTail.textClearance.typingPending)", idle)
        parked = idle.split("if (currentNestedTail.textClearance.typingPending)", 1)[1].split("}", 1)[0]
        self.assertIn("motion.pauseFrameClock()", parked)
        self.assertIn("return@collect", parked)
        for forbidden in ("withFrameNanos", "motion.step(", "motion.reset("):
            self.assertNotIn(forbidden, parked)
        motion = (ROOT / "core-domain/src/main/kotlin/app/daiyu/android/domain/BottomFollowMotion.kt").read_text()
        parked_clock = motion.split("fun pauseFrameClock() {", 1)[1].split("}", 1)[0]
        self.assertEqual("lastFrameNanos = null", parked_clock.strip())
        self.assertIn("motion.reset()", idle)
        typing = (CHAT.parent / "MarkdownTyping.kt").read_text()
        self.assertIn("if (progress.target === target) progress.visible else target.start", typing)
        self.assertIn("minOf(target.total, advanceLimit)", typing)
        self.assertIn("onDispose { clearance?.setTypingPending(clearanceOwner, false) }", typing)
        self.assertIn('shown = when { !markdownTypingCanAdvance(currentCanAdvance, currentPlayback) -> { motion.pause(current) motion.visible(current) } textClearance?.held == true -> { motion.hold(current, frameNanos) motion.visible(current) } currentAwaitPaintAdmission && !publication.ready -> motion.visible(current) else -> motion.advance(current, frameNanos, end) { currentAdvance() } }', " ".join(typing.split()))

    def test_explicit_scroll_and_touch_keep_ownership(self):
        self.assertIn("if (target == null) {", self.follow)
        disabled = self.follow.split("if (target == null) {", 1)[1].split("return@collect", 1)[0]
        self.assertIn("motion.reset()", disabled)
        self.assertIn("clearanceLayout.viewportLoss.reset()", disabled)
        self.assertIn("scrollResumed && followBottom && !currentDragging", self.guard)
        self.assertIn("followBottom = false; wasDragged = false", self.source)
        self.assertIn("consumedScrollSerial = currentScrollSerial", self.source)
        self.assertIn("listState.animateScrollToItem(index)", self.source)
        self.assertIn("readyTurns[it.listKey] == it", self.source)
        resume = self.follow.split("val resumeReady =", 1)[1].split("Triple(canFollowBottom()", 1)[0]
        self.assertIn("readyTurns[tail.listKey] == tail", resume)
        self.assertNotIn("revealedTurns", resume)

    def test_idle_wait_rechecks_current_layout_without_ending_the_worker(self):
        self.assertIn("if (followDistance <= 0f)", self.follow)
        idle = self.follow.split("if (followDistance <= 0f)", 1)[1].split("val alignment =", 1)[0]
        self.assertIn("motion.reset()", idle)
        self.assertIn("return@collect", idle)
        self.assertNotIn("break", self.follow)
        self.assertIn("withFrameNanos { frameTimeNanos ->", self.follow)
        self.assertIn("if (delta > 0f && canFollowBottom())", self.follow)

    def test_domain_motion_algorithm_remains_single_source(self):
        self.assertIn("import app.daiyu.android.domain.BottomFollowMotion", self.source)
        self.assertIn("val currentDensity by rememberUpdatedState(density.density)", self.source)
        self.assertNotIn("2400f", self.source)
        self.assertNotIn("720f", self.source)

    def test_device_regressions_cover_frames_moving_targets_touch_and_navigation(self):
        device = DEVICE.read_text()
        for token in ["testLineGrowthUsesSeveralForwardFramesAndRetainsMotionWhenTargetMoves",
                      "testTouchCancelsAnActiveMotionUntilAnExplicitBottomRequest",
                      "testExactMessageNavigationWinsOverAnActiveFollow",
                      "testIdleFollowWakesForEachLaterBurstWithoutANewScrollRequest", "height += 26.dp",
                      "movement <= 14f * density", "height += 78.dp", "frame()",
                      "Continuous growth cannot starve follow", "ui.mainClock.autoAdvance = false",
                      "ui.mainClock.advanceTimeByFrame()", "positionInRoot().y", "composerBottomInset = 64.dp",
                      "Touch cancels momentum as well as later height updates",
                      'messageId = "u1", followBottom = false', "fixture.opens.get()"]:
            self.assertIn(token, device)


    def test_short_live_body_defers_layout_scroll_to_the_existing_frame_follower(self):
        correction = self.source.split("fun correctFollowClearance()", 1)[1].split(
            "// A non-following viewport", 1)[0]
        live = correction.split("// Ordinary live text", 1)[1].split(
            "if (!listState.canScrollForward)", 1)[0]
        for token in ("shortBoundedTail", "val liveTextTail = activeTail?.let {",
                      "it.status == RunStatus.RUNNING &&",
                      "(it.isStreaming && it.hasBodyTextActions() && it.workflowContent().work == null ||",
                      "it.hasPlainTextBody(allowEmpty = true) && !hasTurnExtras(it) &&",
                      "reconnectPartsByKey.getValue(it.listKey).placedIds.isEmpty() &&",
                      "fallbackAfter[it.listKey].isNullOrEmpty() && compressionAfter[it.listKey].isNullOrEmpty())",
                      "} == true", "!resumeFollowPending", "!nestedTail.followCatchup",
                      "clearanceLayout.followViewport == Triple(layout.viewportSize,",
                      "layout.beforeContentPadding, currentOcclusionPx + currentFollowClearancePx", ")) return"):
            self.assertIn(token, live)
        self.assertNotIn("layout.afterContentPadding", live)
        self.assertLess(correction.index("remeasurement.forceRemeasure()"),
                        correction.index("// Ordinary live text"))
        self.assertLess(correction.index("// Ordinary live text"),
                        correction.index("listState.dispatchRawDelta"))
        device = DEVICE.read_text()
        for token in ("testShortStreamingBodyLinesUseSeveralDrawnFramesAfterEachIdleBurst",
                      "initialHeight = 0.dp", "viewportHeight = 400.dp", "ViewTreeObserver.OnDrawListener",
                      "observer.addOnDrawListener(listener)", "observer.removeOnDrawListener(listener)",
                      "host.invalidate()", "draws.isNotEmpty()",
                      "draws.zipWithNext()", "movingDraws > 1", "tail.size.height",
                      "History and the assistant move as one outer list",
                      "Viewport resize retains the original immediate clearance alignment"):
            self.assertIn(token, device)

        self.assertNotIn("draws += origin to", device)
        self.assertNotIn("sampledTail", device)

    def test_manual_clock_mutations_do_not_implicitly_advance_an_extra_frame(self):
        device = DEVICE.read_text()
        controlled = False
        mutations = 0
        for line in device.splitlines():
            if "ui.mainClock.autoAdvance = false" in line:
                controlled = True
            elif "ui.mainClock.autoAdvance = true" in line:
                controlled = False
            if controlled:
                self.assertNotIn("mainThread {", line)
                if "ui.runOnUiThread {" in line:
                    mutations += 1
        self.assertEqual(9, mutations)
        frame = device.split("private fun frame(): Float {", 1)[1].split("\n    }", 1)[0]
        self.assertEqual(1, frame.count("advanceTimeByFrame()"))
        self.assertIn("movement <= 14f * density", device)



    def test_viewport_geometry_restores_only_lost_safe_height_without_paying_text_backlog(self):
        correction = self.source.split("fun correctFollowClearance()", 1)[1].split("// A non-following viewport", 1)[0]
        for token in ("observeFollowViewport(layout)",
                      "val viewportCorrection = clearanceLayout.viewportLoss.pendingPx > 0f",
                      "nestedTail.textClearance.pacedFollowing && !viewportCorrection",
                      "shortBoundedTail || boundedTerminalReveal || viewportCorrection",
                      "clearanceLayout.viewportLoss.correction(obscured.toFloat())",
                      "clearanceLayout.viewportLoss.consume(consumed)"):
            self.assertIn(token, correction)
        observation = self.source.split("fun observeFollowViewport(", 1)[1].split("fun correctFollowClearance", 1)[0]
        self.assertIn("clearanceLayout.viewportLoss.observe(currentTailKey,", observation)
        self.assertIn("viewport.first.height - viewport.second - viewport.third", observation)
        self.assertIn("following = canFollowBottom()", observation)
        self.assertIn("clearanceLayout.followViewport = viewport", observation)
        self.assertNotIn("alignViewport", self.follow)
        self.assertIn("clearanceLayout.viewportLoss.consume(consumed)", self.follow)
        zero_inset = correction.split("if (currentOcclusionPx <= 0) {", 1)[1].split("return", 1)[0]
        self.assertIn("clearanceLayout.viewportLoss.correction(0f)", zero_inset)
        positioned = self.source.split('.onPlaced { clearanceLayout.coordinates = it }', 1)[1].split('.drawWithContent', 1)[0]
        self.assertIn('.onGloballyPositioned', positioned)
        self.assertIn('if (coordinates.isAttached) correctFollowClearance()', positioned)

if __name__ == "__main__":
    unittest.main()
