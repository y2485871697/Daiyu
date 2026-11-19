"""检查聊天跟随对输入区遮挡的源码处理。"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatMessageList.kt"
DEVICE = ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatStreamingOcclusionTest.kt"


class StreamingOcclusionContract(unittest.TestCase):
    def test_plain_text_reserves_height_without_lowering_the_original_safety_edge(self):
        source = CHAT.read_text()
        viewport = source.split("internal fun StreamingTailContainer(", 1)[1].split("internal fun ChatMessageList(", 1)[0]
        drawing, placement = viewport.split(".drawWithContent {", 1)[1].split(".scrollable", 1)
        self.assertIn("if (protect) drawClearance() else 0", drawing)
        self.assertIn("val safeBottom = currentListHeight - clearance()", placement)
        self.assertNotIn("drawClearance()", placement)
        production = source.split("val tailViewportHeight =", 1)[1].split("else turnContent()", 1)[0]
        self.assertNotIn("drawClearance =", production)
        self.assertNotIn("currentVisualTopInsetPx", source)
        self.assertNotIn("composerVisualTopInset", source)
        for token in ("clearance = { currentOcclusionPx + currentFollowClearancePx }",
                      "preserveTextClearance = turn.listKey == currentTailKey",
                      "val typingPending: Boolean get() = typingOwners.isNotEmpty()",
                      "typingOwners[owner] = Unit", "pacedFollowing = true", "} else typingOwners.remove(owner)"):
            self.assertIn(token, source)
        admission = viewport.split(".onPlaced { viewport ->", 1)[1].split(".drawWithContent {", 1)[0]
        self.assertIn("state.textClearance.update(listCoordinates(), viewport, state.scrollOffset.roundToInt(), drawClearance()", admission)
        self.assertIn("preserveTextClearance && protectClearance() && followIntent()", admission)
        self.assertNotIn("state.textClearance.update(", drawing)
        self.assertIn("body.placeRelative(0, -state.scrollOffset.roundToInt())", placement)
        self.assertIn("val safeBottom = current.listHeight - current.clearance.toFloat()", source)
        self.assertIn("bottom - list.localPositionOf(leaf, Offset.Zero).y", source)
        self.assertIn(".onGloballyPositioned { viewport ->", admission)
        self.assertIn("val listHeight: Int, val viewportTop: Float, val viewportHeight: Int", source)
        self.assertIn("if (attached) list!!.localPositionOf(viewport!!, Offset.Zero).y", source)
        typing = (CHAT.parent / "MarkdownTyping.kt").read_text()
        self.assertIn("markdownTypingCanAdvance(currentCanAdvance, currentPlayback) && textClearance?.held != true", typing)
        self.assertIn("else { motion.pause(current); motion.visible(current) }", typing)
        markdown = (CHAT.parent / "MessageMarkdown.kt").read_text()
        for token in ("remember(clearance) { mutableMapOf<Int, TextLayoutResult>() }",
                      "clearance.fits(coordinates, candidate.size.height)", "painted[paintOffset] = actual",
                      "actual.size.height <= available + .01f", "frame.committed",
                      'BasicText(displayed?.layoutInput?.text ?: AnnotatedString("")',
                      "paintOffset + offset", "paintOffset + offsets[column]",
                      "previous?.canPaintPrefixOf(candidate) == true", "current.text.text.startsWith(previous.text.text)",
                      "previous.constraints.maxWidth != current.constraints.maxWidth",
                      "previous.text == prefix", "textClearance?.held != true",
                      "frame.rendered", "publication?.setPending(owner, !published())",
                      "actual.layoutInput.text == text", "modifier.layout { measurable, constraints ->"):
            self.assertIn(token, markdown)
        workflow = (CHAT.parent / "AssistantWorkflowContent.kt").read_text()
        classifier = workflow.split("internal fun ChatTurnUi.Assistant.hasPlainTextBody", 1)[1].split("internal fun MessageContentUi.workflowKey", 1)[0]
        self.assertNotIn("RunStatus", classifier)
        self.assertNotIn("isStreaming", classifier)
        for token in ("!error.isNullOrBlank()", "workProcess != null", "reportedReasoning.isNotEmpty()",
                      "leadingContent.isNotEmpty()", "reconnectSource != null", "val source = workflowContent()",
                      "source.pending.isEmpty()", "source.body.all { it is MessageContentUi.Text }"):
            self.assertIn(token, classifier)
        self.assertNotIn("withResolvedTerminalCards", classifier)
        self.assertIn("hasTurnExtras = hasTurnExtras", (CHAT.parent / "ChatScreen.kt").read_text())
        self.assertIn("turn.segmentIndex == 0 && questionRows[turn.id]?.retryCleanup == true",
                      (ROOT / "app/src/main/kotlin/app/daiyu/android/MainActivity.kt").read_text())

    def test_leaf_pause_uses_drawn_prefix_and_layout_does_not_report_paint(self):
        markdown = (CHAT.parent / "MessageMarkdown.kt").read_text()
        frame = markdown.split("private class MarkdownTextFrame", 1)[1].split("@Composable", 1)[0]
        self.assertIn("var coordinates: LayoutCoordinates? = null", frame)
        self.assertIn("var availableHeight by mutableStateOf(0f)", frame)
        self.assertNotIn("neverEqualPolicy", frame)
        leaf = markdown.split("internal fun ClearanceText", 1)[1].split("internal fun TextLayoutResult.canPaintPrefixOf", 1)[0]
        self.assertIn("val displayed = if (playback?.paused == true) frame.drawn else frame.committed", leaf)
        self.assertIn("playback?.paused == true -> frame.drawn", leaf)
        self.assertIn('val candidateText = if (playback?.paused == true) displayed?.layoutInput?.text ?: AnnotatedString("") else text', leaf)
        self.assertIn("measurer.measure(candidateText, style", leaf)
        self.assertIn("}, style, softWrap = softWrap, onTextLayout", leaf)
        publication = leaf.split("fun published(): Boolean =", 1)[1].split("fun placed(", 1)[0]
        self.assertEqual(" ".join(publication.split()),
                         "text.isEmpty() || frame.layout?.let { candidate -> "
                         "candidate.layoutInput.text == text && clearance.whollyAboveViewport(frame.coordinates, "
                         "maxOf(candidate.size.height, frame.coordinates?.size?.height ?: 0)) "
                         "} == true || frame.drawn?.let { actual -> "
                         "actual.layoutInput.text == text && frame.layout?.let { actual.canPaintPrefixOf(it) } == true && "
                         "clearance.fits(frame.coordinates, actual.size.height) } == true")
        self.assertNotIn("frame.drawn =", publication)
        self.assertNotIn("painted[", publication)
        self.assertIn("!frozen || actual.layoutInput.text == frame.drawn?.layoutInput?.text", leaf)
        placed, drawn = leaf.split(".drawWithContent {", 1)
        self.assertNotIn("painted[paintOffset] =", placed)
        self.assertNotIn("frame.drawn =", placed)
        self.assertLess(drawn.index("drawContent()"), drawn.index("frame.drawn = actual"))
        self.assertLess(drawn.index("frame.drawn = actual"), drawn.index("publication?.setPending"))
        layout = drawn.split("onTextLayout = {", 1)[1]
        self.assertIn("frame.rendered = it", layout)
        self.assertNotIn("publication?.setPending", layout)
        self.assertNotIn("frame.drawn =", layout)
        self.assertNotIn("painted[paintOffset] =", layout)

    def test_wholly_offscreen_receipt_does_not_relax_visible_paint_or_other_owners(self):
        source = CHAT.read_text()
        geometry = source.split("fun whollyAboveViewport(", 1)[1].split("fun fits(", 1)[0]
        for token in ("!positioned || height <= 0", "viewportGeometry ?: return false",
                      "current.list?.takeIf { it.isAttached }", "current.viewport?.takeIf { it.isAttached }",
                      "coordinates?.takeIf { it.isAttached }",
                      "list.localPositionOf(leaf, Offset.Zero).y + height <= maxOf(0f, current.viewportTop)"):
            self.assertIn(token, geometry)
        self.assertIn("viewportGeometry = placed.takeIf { attached }", source)
        self.assertIn("geometry = if (protect) placed else null", source)
        markdown = (CHAT.parent / "MessageMarkdown.kt").read_text()
        self.assertIn("snapshotFlow { publicationCheck() }.collect { publication?.setPending(owner, !it) }", markdown)
        self.assertIn("onDispose { publication?.setPending(owner, false) }", markdown)
        device = DEVICE.read_text().split("fun testUnpaintedLeafAboveViewportReleasesOnlyItsPendingReceipt()", 1)[1]
        device = device.split("fun testProtectedCodeViewport", 1)[0]
        for token in ("publication.setPending(otherOwner, true)", "publication.setPending(otherOwner, false)",
                      "assertFalse", "origin = (-120).dp", "origin = 240.dp", "origin = 0.dp"):
            self.assertIn(token, device)

    def test_code_and_workflow_placement_publish_only_changed_height_and_keep_full_border_geometry(self):
        code = (CHAT.parent / "MarkdownCodeReveal.kt").read_text()
        frame = code.split("private class MarkdownCodeViewportFrame", 1)[1].split("@Composable", 1)[0]
        self.assertIn("var coordinates: LayoutCoordinates? = null", frame)
        self.assertIn("var availableHeight by mutableStateOf(0f)", frame)
        self.assertNotIn("neverEqualPolicy", frame)
        workflow = (CHAT.parent / "WorkflowTimelineLayout.kt").read_text()
        surface = workflow.split("internal fun Modifier.streamingWorkflowSurface", 1)[1].split("internal fun Modifier.workflowInkClearance", 1)[0]
        self.assertIn("clipRect(bottom = height)", surface)
        self.assertIn("size.width, size.height, CornerRadius(corner)", surface)
        self.assertIn("Size(size.width - stroke, size.height - stroke)", surface)
        self.assertNotIn("Size(size.width - stroke, height - stroke)", surface)

    def test_glyph_regression_rejects_the_visual_edge_and_verifies_committed_complete_lines(self):
        device = DEVICE.read_text()
        for token in ("testShortTextDrawClearancePreservesGlyphButStopsAtSurface",
                      "testRunningPlainMarkdownKeepsFullGlyphAboveOriginalSafetyLine",
                      "testLongPlainMarkdownKeepsFullGlyphDuringFollowAndCompletion",
                      "drawText(p.layout, topLeft = p.at)", "O-10 demonstrably paints into the original O+8 safety gap",
                      "A painted trailing line must retain its complete reference glyph",
                      "The complete original safety gap, including its sides, must remain empty",
                      "assertNoSurfaceInk(host, shot)", "unsafeCandidates > 0",
                      "Detached plain text must not pull the reader back to the bottom",
                      "referenceBelow > 2", "Old O+8 draw boundary must demonstrably cut ink",
                      "assertEquals(candidate.layout.layoutInput, after.layout.layoutInput)"):
            self.assertIn(token, device)

    def test_terminal_append_retains_only_its_measured_action_slot(self):
        source = CHAT.read_text()
        assistant = source.split('internal fun AssistantTurn(', 1)[1].split('private fun AssistantTextContent', 1)[0]
        self.assertIn('remember(actionTurn.actionKey, actionTurn.status == RunStatus.RUNNING, actionSlotOwner)', assistant)
        self.assertIn('mutableStateOf(0)', assistant)
        self.assertIn('actionSlotOwner && actionTurn.status != RunStatus.RUNNING && actionSlotHeightPx > 0', assistant)
        self.assertIn('(!generationComplete || !revealSettled || !showActions)', assistant)
        self.assertIn('Modifier.onSizeChanged { actionSlotHeightPx = it.height }', assistant)
        self.assertIn('else if (holdActionSlot)', assistant)
        self.assertIn('actionSlotHeightPx.toDp()', assistant)
        self.assertIn('generationComplete && revealSettled', assistant)
        reconnect = (CHAT.parent / 'ReconnectedAssistantTurn.kt').read_text()
        self.assertIn('actionSlotOwner = index == parts.parts.lastIndex', reconnect)

    def test_idle_viewport_cleanup_does_not_subscribe_to_each_scroll_layout(self):
        source = CHAT.read_text()
        cleanup = source.split('LaunchedEffect(listState, turnKeys, assistantOwnerKey)', 1)[1].split('val boundaryRequests', 1)[0]
        cache = cleanup.index('val cleanupCandidates = derivedStateOf')
        scan = cleanup.index('nestedStates.filter', cache)
        flow = cleanup.index('snapshotFlow {', scan)
        candidates = cleanup.index('val candidates = cleanupCandidates.value', flow)
        idle = cleanup.index('if (candidates.isEmpty()) return@snapshotFlow emptyList()', candidates)
        layout = cleanup.index('val layout = listState.layoutInfo', idle)
        self.assertLess(cache, scan)
        self.assertLess(scan, flow)
        self.assertLess(flow, candidates)
        self.assertLess(candidates, idle)
        self.assertLess(idle, layout)
        self.assertNotIn('layoutInfo', cleanup[:idle])
        self.assertNotIn('nestedStates.filter', cleanup[flow:layout])
        for token in ('!(key == assistantOwnerKey && followBottom)', 'viewport.bounded', '!viewport.migrating'):
            self.assertIn(token, cleanup[scan:flow])
        self.assertIn('candidates.mapNotNull', cleanup[layout:])
        collect = cleanup.split('.collect { offscreen ->', 1)[1]
        self.assertIn('(key != assistantOwnerKey || !followBottom) && viewport.bounded && !viewport.migrating', collect)
        for token in ('viewport.bounded = false', 'viewport.scrollOffset = 0f',
                      'viewport.viewportOffset = 0', 'viewport.boundaryDelta = 0f'):
            self.assertIn(token, collect)

    def test_waiting_phase_keeps_running_status_and_draws_real_body_coordinates(self):
        device = DEVICE.read_text()
        self.assertIn('status = if (running) RunStatus.RUNNING else RunStatus.COMPLETED,', device)
        self.assertIn('isStreaming = running && !waiting', device)
        self.assertNotIn('RunStatus.WAITING_USER', CHAT.read_text())
        for token in ('testWaitingUserRetainsLiveViewportAndEveryDrawnReadingAnchor',
                      'testWaitingUserStartsBoundedAndExplicitBottomKeepsItsViewport',
                      'body.positionInRoot().y', 'anchor.positionInRoot().y',
                      'WAITING_USER must not take terminal unwrap', 'Completion still unwraps'):
            self.assertIn(token, device)
        measure = DEVICE.with_name('ChatMessageListMeasurePhaseTest.kt').read_text()
        self.assertIn('The actual body must use the same transform in every draw', measure)
        self.assertIn('A follow gate cannot expose half a placed transform', measure)

    def test_unbounded_placement_does_not_observe_unused_item_origin(self):
        source = CHAT.read_text()
        viewport = source.split('internal fun StreamingTailContainer(', 1)[1].split('internal fun ChatMessageList(', 1)[0]
        placement = viewport.split('layout(viewport.width, contentHeight)', 1)[1]
        self.assertIn('val origin = if (state.bounded) state.itemOrigin else null', placement)
        self.assertIn('state.viewportOffset = q', placement)
        self.assertIn('viewport.placeRelative(0, q)', placement)
        self.assertIn('list.localPositionOf(coordinates, Offset.Zero).y', viewport)

    def test_occlusion_clearance_uses_the_same_bounded_frame_motion(self):
        source = CHAT.read_text()
        follow = source.split("// Keep a single motion", 1)[1].split("// Read confirmation", 1)[0]
        self.assertIn("val visibleBottom = layout.viewportEndOffset - currentOcclusionPx - currentFollowClearancePx", follow)
        self.assertIn("val frameVisibleBottom = frameLayout.viewportEndOffset - currentOcclusionPx - currentFollowClearancePx", follow)
        self.assertIn("val currentFollowClearancePx by rememberUpdatedState(with(density) { ChatTailClearance.roundToPx() })", source)
        self.assertIn("bottom = composerBottomInset + ChatTailClearance", source)
        self.assertIn("val obscuredTail = (last.offset + last.size - visibleBottom).coerceAtLeast(0)", follow)
        self.assertIn("(last.offset + last.size - visibleBottom).coerceAtLeast(0)", follow)
        self.assertIn("(frameLast.offset + frameLast.size - frameVisibleBottom).coerceAtLeast(0)", follow)
        self.assertIn("val followDistance = maxOf(overflow.toFloat(), obscuredTail.toFloat())", follow)
        self.assertIn("val frameFollowDistance = maxOf(frameOverflow.toFloat(), frameObscuredTail.toFloat())", follow)
        self.assertIn("delta = motion.step(frameFollowDistance, frameTimeNanos, currentDensity, continuous = currentNestedTail.textClearance.typingPending)", " ".join(follow.split()))
        self.assertNotIn("delta = maxOf(", follow)
        self.assertIn("while (canFollowBottom())", follow)
        self.assertIn("withFrameNanos { frameTimeNanos ->", follow)
        self.assertIn("listState.scrollBy(delta)", follow)

    def test_bounded_viewport_preserves_extent_and_aligns_real_content_same_measure(self):
        source = CHAT.read_text()
        viewport = source.split("internal fun StreamingTailContainer(", 1)[1].split("internal fun ChatMessageList(", 1)[0]
        for token in ("maxHeight = Constraints.Infinity", "state.contentHeight = body.height",
                      "val contentHeight = state.contentHeight",
                      "minOf(body.height, currentViewportHeight)", "state.contentHeight - visibleHeight",
                      "state.bounded && following && !state.followCatchup", "state.maxOffset.toFloat()",
                      "body.placeRelative(0, -state.scrollOffset.roundToInt())",
                      "layout(viewport.width, contentHeight)", "Modifier.onPlaced",
                      "list.localPositionOf(coordinates, Offset.Zero).y",
                      "viewport.placeRelative(0, q)", "state.scrollOffset = (old - delta).coerceIn",
                      "ScrollAxisRange(", "state.viewportOffset.coerceIn(0, state.maxOffset)"):
            self.assertIn(token, viewport)
        self.assertNotIn("BottomFollowMotion()", viewport)
        self.assertNotIn("listState.layoutInfo", viewport)
        self.assertIn("ChatTailClearance.roundToPx()", source)
        migration = source.split("// Unwrap only after terminal", 1)[1].split("val requestedIndex", 1)[0]
        for token in ("revealedTurns[tail.listKey] != tail", "while (followBottom && (disclosureViewport.active != null ||",
                      "stopScroll(MutatePriority.PreventUserInput)",
                      "nestedTail.scrollOffset.roundToInt() - nestedTail.viewportOffset",
                      "listState.scrollToItem(index, offset + delta)"):
            self.assertIn(token, migration)
        exact = source.split("val index = request.targetIndex(turns)", 1)[1].split("} else if (followBottom", 1)[0]
        self.assertIn("nestedTail.viewportOffset = 0", exact)
        self.assertIn("nestedTail.scrollOffset = 0f", exact)
        self.assertIn("listState.animateScrollToItem(index)", exact)

    def test_short_growth_does_not_use_partial_height_as_an_internal_follow_target(self):
        source = CHAT.read_text()
        viewport = source.split("internal fun StreamingTailContainer(", 1)[1].split("internal fun ChatMessageList(", 1)[0]
        self.assertIn("layout(body.width, height) {", viewport)
        measure = viewport.split("onViewportMeasure?.invoke()", 1)[1].split("layout(body.width, height) {", 1)[0]
        self.assertEqual(1, measure.count("viewportHeight()"))
        self.assertIn("val currentViewportHeight = viewportHeight().coerceAtLeast(1)", measure)
        self.assertIn("minOf(body.height, currentViewportHeight)", measure)
        self.assertNotIn("state.itemOrigin", measure)
        self.assertNotIn("val followOffset = (body.height - viewportHeight()", viewport)
        placement = viewport.split("layout(viewport.width, contentHeight)", 1)[1]
        self.assertNotIn("state.scrollOffset =", measure)
        self.assertEqual(1, placement.count("val following = follows()"))
        self.assertIn("val followOffset = if (state.contentHeight > viewport.height) maximum else 0", placement)
        self.assertIn("val maximum = if (state.bounded) (state.contentHeight - visibleHeight).coerceAtLeast(0) else 0", placement)
        self.assertLess(placement.index("val origin ="), placement.index("val maximum ="))
        self.assertNotIn("if (follows()", placement)
        self.assertIn("state.scrollOffset = if (state.bounded && following && !state.followCatchup) followOffset.toFloat()", placement)
        self.assertLess(placement.index("state.scrollOffset = if"), placement.index("state.viewportOffset = q"))
        self.assertLess(placement.index("state.viewportOffset = q"), placement.index("viewport.placeRelative(0, q)"))
        self.assertNotIn("if (state.bounded && follows()) maximum.toFloat()", viewport)
        self.assertIn("clipRect(bottom = clipBottom)", viewport)
        self.assertIn("state.viewportOffset.coerceIn(0, state.maxOffset)", viewport)
        device = DEVICE.read_text()
        for token in ("testAppendingShortWorkflowKeepsIdentityAndHeaderUnclippedInEveryFrame",
                      "shortWorkflow(count)", "reply-identity:", "work-process-header",
                      "range.value()", "node.boundsInRoot.height", "node.size.height.toFloat()",
                      "node.positionInRoot.y", "assertVerticallyUnclipped(node, tag)",
                      "testSemanticVisibilityProbeDistinguishesPaddingFromTopClipping",
                      "complete.size.height > complete.layoutInfo.coordinates.size.height",
                      "clipped.boundsInRoot.height < clipped.size.height - 1f",
                      "failure is junit.framework.AssertionFailedError", "neighbor.boundsInRoot.bottom",
                      "The outer follower must actually reveal each new tool"):
            self.assertIn(token, device)

    def test_following_catchup_retains_new_text_and_card_pixel_regression(self):
        device = DEVICE.with_name("ChatMessageListMeasurePhaseTest.kt").read_text()
        case = device.split("fun testFollowingCatchupKeepsGrowingCardAndLastLineInsidePhysicalSafeEdge()", 1)[1].split("fun testCurrentPlacementOrigin", 1)[0]
        for token in ("state.followCatchup && following && protect", "state.scrollOffset", "state.viewportOffset",
                      "text.bottom > viewport.bottom", "card.bottom < safeBottom", "repeat(3)",
                      "WorkProcessCard(", "missingText", "missingBorder", "clearanceInk", "failures.isEmpty()"):
            self.assertIn(token, case)
        source = CHAT.read_text()
        drawing = source.split(".drawWithContent {", 1)[1].split(".scrollable", 1)[0]
        self.assertIn("state.bounded && state.followCatchup && followIntent()", drawing)
        self.assertIn("followIntent: () -> Boolean = follows", source)
        self.assertIn("followIntent = { turn.listKey == assistantOwnerKey && followBottom }", source)
        self.assertIn("protect && !followingCatchup", drawing)
        self.assertIn("minOf(size.height, bottom) else bottom", drawing)

    def test_real_disclosure_gate_samples_safe_tail_in_the_captured_frame(self):
        device = DEVICE.with_name("ChatMessageListMeasurePhaseTest.kt").read_text()
        case = device.split("fun testRealDisclosureGateCannotClipSafeTailWithNonzeroFollowTransform()", 1)[1].split("fun testCurrentPlacementOrigin", 1)[0]
        for token in ("ChatConversationLayers(", "ChatMessageList(", "ChatTurnMapper.map(",
                      "LocalWorkflowDisclosureContext.current?.viewport", "work-process-header",
                      "performSemanticsAction", "follow=true canFollow=false", "catchup=true",
                      'number(diagnostic, "a") <= 1f', 'number(diagnostic, "q") <= 1f',
                      'physical("chat-stream-viewport:${turn().listKey}")',
                      "end.bottom > safeBottom + 1f", "sampleY <= viewport.bottom + 1f",
                      "ChatTailClearance.value * pixelsPerDp", "eligibleFrames > 0",
                      "color.red > .9f && color.blue > .9f && color.green < .1f", "failures.isEmpty()"):
            self.assertIn(token, case)
        self.assertNotIn('chat-stream-viewport:gate-tail', case)
        capture = case.index('val image = captureScreenNode(')
        diagnostic = case.index('val diagnostic = geometry()', capture)
        gate = case.index('if (!diagnostic.contains(', diagnostic)
        geometry = case.index('val end = physical(', gate)
        eligible = case.index('eligibleFrames++', geometry)
        sample = case.index('val color = pixels[', eligible)
        self.assertLess(capture, diagnostic)
        self.assertLess(diagnostic, gate)
        self.assertLess(gate, geometry)
        self.assertLess(geometry, eligible)
        self.assertLess(eligible, sample)

    def test_protected_drawing_caps_current_frame_without_clipping_manual_backdrop(self):
        source = CHAT.read_text()
        viewport = source.split("internal fun StreamingTailContainer(", 1)[1].split("internal fun ChatMessageList(", 1)[0]
        self.assertIn("val followingCatchup = state.bounded && state.followCatchup && followIntent()", viewport)
        self.assertIn("val clipBottom = if (protect && !followingCatchup) minOf(size.height, bottom) else bottom", viewport)
        self.assertIn("clipRect(bottom = clipBottom)", viewport)
        self.assertIn("internal val ChatTailClearance = 8.dp", source)
        device = DEVICE.read_text()
        for token in ("testProtectedTailClipsItsOwnBottomButManualReadingKeepsBackdrop",
                      "sampleBelowViewport()", "protectBottom = false", "protectBottom = true",
                      "inside.red < .1f", "clipped.red > .9f", "manual.red < .1f",
                      "protectedAgain.red > .9f"):
            self.assertIn(token, device)

    def test_short_to_long_growth_exercises_partial_viewport_before_outer_catchup(self):
        source = DEVICE.read_text()
        case = source.split("fun testShortReplyGrowingPastViewportKeepsTailPinnedDuringOuterCatchup()", 1)[1].split("fun testContinuousEveryFrameGrowth", 1)[0]
        for token in ("height = 120.dp", "height = 900.dp", "tailTop() > 40f * density",
                      "ui.mainClock.autoAdvance = false", "repeat(24)", "frame()",
                      'ui.onNodeWithTag("occlusion-tail").assertIsDisplayed()', "assertTailClearance()",
                      "ChatTailClearance.value, (composer.top - bottom) / density", "observedPartialViewport",
                      "finally", "ui.mainClock.autoAdvance = true"):
            self.assertIn(token, case)

    def test_short_growth_aligns_outer_geometry_before_drawing_without_inner_crop(self):
        source = CHAT.read_text()
        correction = source.split("fun correctFollowClearance() {", 1)[1].split("val requestedIndex", 1)[0]
        for token in ("val shortBoundedTail = nestedTail.bounded && currentTailKey == assistantOwnerKey",
                      "nestedTail.contentHeight <= layout.viewportSize.height", "11.dp.roundToPx() + ChatTailClearance.roundToPx()",
                      "val protectMeasuredTail = shortBoundedTail || boundedTerminalReveal || viewportCorrection",
                      "if (!protectMeasuredTail && (activeTail?.status == RunStatus.RUNNING || nestedTail.bounded)) return",
                      "val tailClearance = currentFollowClearancePx",
                      "dispatchRawDelta", "remeasurement.forceRemeasure()", "!canFollowBottom()",
                      "protectMeasuredTail && last.size != nestedTail.contentHeight", "layout = listState.layoutInfo"):
            self.assertIn(token, correction)
        self.assertNotIn("nestedTail.migrating || nestedTail.followCatchup", correction)
        self.assertIn("if (nestedTail.followCatchup && !protectMeasuredTail) return", correction)
        self.assertIn("if (!protectMeasuredTail && resumeFollowPending", correction)
        self.assertIn("readyTurns[activeTail.listKey] == activeTail", correction)
        self.assertIn("revealedTurns[activeTail.listKey] != activeTail", correction)
        self.assertIn("activeTail.status != RunStatus.RUNNING", correction)
        self.assertLess(correction.index("if (protectMeasuredTail && last.size"), correction.index("if (!listState.canScrollForward) {"))
        device = DEVICE.read_text()
        case = device.split("fun testShortReplyGrowingPastViewportKeepsTailPinnedDuringOuterCatchup()", 1)[1].split("fun testManualReadingDrawsThrough", 1)[0]
        for token in ("for (shortHeight in listOf(160, 200))", "height = shortHeight.dp",
                      "shortViewport.config[SemanticsProperties.VerticalScrollAxisRange].value()",
                      "ChatTailClearance.value, (composer.top - shortBottom) / density"):
            self.assertIn(token, case)

    def test_drawing_clip_protects_follow_clearance_but_retains_detached_backdrop(self):
        source = CHAT.read_text()
        viewport = source.split("internal fun StreamingTailContainer(", 1)[1].split("internal fun ChatMessageList(", 1)[0]
        drawing = viewport.split(".drawWithContent {", 1)[1].split(".scrollable", 1)[0]
        self.assertIn("import androidx.compose.runtime.neverEqualPolicy", source)
        self.assertIn("var viewportCoordinates by mutableStateOf<LayoutCoordinates?>(null, neverEqualPolicy())", source)
        self.assertIn(".onPlaced { viewport ->\n                state.viewportCoordinates = viewport", viewport)
        self.assertIn("val viewport = state.viewportCoordinates?.takeIf { it.isAttached }", drawing)
        geometry = drawing.split("val actualTop = ", 1)[1].split("} else size.height", 1)[0]
        self.assertEqual(
            "list.localPositionOf(viewport, Offset.Zero).y "
            "(list.size.height - actualTop - if (protect) drawClearance() else 0).coerceAtLeast(0f)",
            " ".join(geometry.split()),
        )
        self.assertIn("clipRect(bottom = clipBottom)", drawing)
        self.assertNotIn(".clipToBounds()", viewport)
        device = DEVICE.read_text()
        case = device.split("fun testManualReadingDrawsThroughClearanceInsteadOfExposingPageBackground()", 1)[1].split("fun testContinuousEveryFrameGrowth", 1)[0]
        for token in ("manualNonzeroPosition()", "painted = true", "sampleY > viewport.boundsInRoot.bottom",
                      "composer.top - ChatTailClearance.value * .5f * density", "captureToImage().toPixelMap()",
                      "color.red < .1f", "color.green > .9f", "color.blue > .9f"):
            self.assertIn(token, case)

    def test_full_extent_invalidates_outer_measure_and_exact_ownership_survives_consumption(self):
        source = CHAT.read_text()
        self.assertIn("var contentHeight by androidx.compose.runtime.mutableIntStateOf(0)", source)
        self.assertIn("state.contentHeight = body.height", source)
        self.assertIn("val contentHeight = state.contentHeight", source)
        self.assertNotIn("StreamingTailContentHeight", source)
        self.assertIn("layout(viewport.width, contentHeight)", source)
        migration = source.split("// Unwrap only after terminal", 1)[1].split("val requestedIndex", 1)[0]
        self.assertIn("&& !exactNavigationOwnsScroll", migration)
        self.assertIn("if (exactNavigationOwnsScroll) return@LaunchedEffect", migration)
        self.assertIn("val requestTouchEpoch = remember(scrollRequest?.serial) { touchEpoch }", source)
        self.assertIn("touchEpoch == requestTouchEpoch", source)
        self.assertIn("currentScrollSerial == consumedScrollSerial", migration)

    def test_assistant_identity_retains_viewports_when_physical_tail_changes(self):
        source = CHAT.read_text()
        for token in ("val nestedStates = remember { mutableMapOf<String, StreamingTailViewport>() }",
                      "nestedStates.getOrPut(key)", "nestedStates[tailAssistant?.listKey]",
                      "LaunchedEffect(nestedTail, currentTailKey, turnKeys, tailAssistant",
                      "currentNestedTail === nestedTail", "nestedStates[tail.listKey] === nestedTail",
                      "if (!ownsMigration()) return@LaunchedEffect",
                      "if (unwrapped && !committed) nestedTail.bounded = true",
                      "val viewportState = nestedStates[turn.listKey]",
                      "turn.listKey == assistantOwnerKey && followBottom",
                      "nestedStates[targetTurn.listKey]?.let"):
            self.assertIn(token, source)
        self.assertNotIn("val nestedTail = remember(currentTailKey)", source)
        device = DEVICE.read_text()
        for token in ("testCompressionAndNewAssistantRetainOldManualAnchor",
                      "showCompression = true", "appendAssistant = true",
                      "Compression must not unwrap the old viewport",
                      "New assistant must not unwrap the old viewport",
                      "Retired migration cannot steal a new exact request"):
            self.assertIn(token, device)

    def test_new_device_cases_exercise_scroll_ownership_and_completed_anchors(self):
        source = DEVICE.read_text()
        for token in ("else Box(Modifier.height(height))",
                      'Text("occlusion tail marker", Modifier.testTag("occlusion-tail"))',
                      "SemanticsProperties.VerticalScrollAxisRange", "after < before - 20f * density",
                      "after > 20f * density", "durationMillis = 1000",
                      "testContinuousEveryFrameGrowthKeepsRealTailClearAndOuterBounded",
                      "movement <= 14f * density", "testManualInnerScrollOwnsBothLayersDuringLaterGrowth",
                      "offset, innerOffset(), 1f", "testCompletionMigratesRealManualAnchorWithoutJumpOrDrift",
                      "anchor, anchorTop(), 2f", "repeat(90)",
                      "testExactTailShowsContentAndExactHistoryPreemptsCompletionMigration",
                      'ui.onNodeWithTag("inner-start").assertIsDisplayed()', "repeat(180)", "repeat(30)"):
            self.assertIn(token, source)
        original = source.split("fun testStreamingGrowthKeepsTailAboveComposer()", 1)[1].split("fun testComposerAndViewport", 1)[0]
        self.assertIn("listOf(26, 52, 300, 26, 78)", original)
        self.assertIn("repeat(2) { frame() }", original)
        self.assertIn("assertTailClearance()", original)

    def test_real_streaming_text_is_checked_before_settling(self):
        source = (DEVICE.parent / "ChatMessageSpacingTest.kt").read_text()
        live = source.split("fun testRealStreamingTextKeepsClearanceDuringIntermediateFrames()", 1)[1].split("fun testShortReply", 1)[0]
        for token in ["ui.mainClock.autoAdvance = false", "ui.runOnUiThread", "SemanticsProperties.Text",
                      "hasAnyAncestor(hasTestTag(\"chat-turn:tail\"))", "useUnmergedTree = true",
                      "coordinates.positionInRoot().y + coordinates.size.height", "gap >= ChatTailClearance.value",
                      "ui.mainClock.advanceTimeByFrame()", "sawNewestLine = assertLiveClearance(marker) || sawNewestLine",
                      "Newest streaming line must actually be displayed", "originalComposer.top, composer.top, 2f",
                      "originalComposer.bottom, composer.bottom, 2f", "finally", "ui.mainClock.autoAdvance = true"]:
            self.assertIn(token, live)
        self.assertNotIn("ui.waitUntil", live)
        self.assertNotIn("awaitVisualIdle", live)
        self.assertIn("phase = if (running) ComposerPhase.RUNNING else ComposerPhase.IDLE", source)

    def test_post_layout_clearance_uses_the_same_follow_ownership_and_exact_tail(self):
        source = CHAT.read_text()
        correction = source.split("fun correctFollowClearance() {", 1)[1].split("val requestedIndex", 1)[0]
        for token in ("activeTail?.status == RunStatus.RUNNING", "!canFollowBottom()", "clearanceLayout.correcting", "clearanceLayout.active",
                      "it.index == currentTailIndex && it.key == currentTailKey",
                      "last.offset + last.size - visibleBottom", "dispatchRawDelta", "forceRemeasure()",
                      "finally", "clearanceLayout.correcting = false"):
            self.assertIn(token, correction)
        self.assertIn("coordinates.isAttached && turn.listKey == currentTailKey", source)
        self.assertIn("status = if (running) RunStatus.RUNNING else RunStatus.COMPLETED, isStreaming = running", (DEVICE.parent / "ChatMessageSpacingTest.kt").read_text())
        device = ' '.join(DEVICE.read_text().split())
        self.assertIn("status = if (running) RunStatus.RUNNING else RunStatus.COMPLETED, isStreaming = running && !waiting", device)

    def test_device_sources_use_real_composer_measure_and_unclipped_tail_geometry(self):
        source = DEVICE.read_text()
        for token in ["ChatConversationLayers(", "composerBottomInset = bottomInset",
                      "positionInRoot().y", "coordinates.size.height", "boundsInRoot.top",
                      "testStreamingGrowthKeepsTailAboveComposer", "testComposerAndViewportChangesKeepFollowVisible",
                      "testTouchAndExactNavigationRetainScrollOwnership", "assertEquals(0, fixture.opens.get())",
                      "val gap = (composerTop - tailBottom) / density", "gap >= ChatTailClearance.value - 1f / density",
                      "repeat(2) { frame() }", "assertTailClearance()"]:
            self.assertIn(token, source)


    def test_nonfollowing_running_owner_unwraps_after_leaving_the_viewport(self):
        source = CHAT.read_text()
        cleanup = source.split('LaunchedEffect(listState, turnKeys, assistantOwnerKey)', 1)[1].split('val boundaryRequests', 1)[0]
        self.assertIn('key == assistantOwnerKey && followBottom', cleanup)
        self.assertIn('(key != assistantOwnerKey || !followBottom)', cleanup)
        self.assertIn('bottom <= layout.viewportStartOffset || top >= layout.viewportEndOffset', cleanup)
        self.assertIn('viewport.scrollOffset = 0f', cleanup)
        owner = DEVICE.read_text().split('fun testRunningOwnerViewportHasRealContentWhenReaderScrollsBackIntoIt()', 1)[1].split('fun testContinuousEveryFrameGrowth', 1)[0]
        for token in ('height = 2400.dp', 'manualNonzeroPosition()', 'messageId = "u0"', 'sawRealStart && observedUnwrapped', 'assertTrue(running)', 'assertFalse(appendAssistant)'):
            self.assertIn(token, owner)

    def test_measure_phase_fixture_derives_the_same_fixed_clearance(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatMessageListMeasurePhaseTest.kt').read_text()
        case = source.split('fun testRealStreamingListViewportResizeDoesNotRecomposeTurnContent()', 1)[1]
        self.assertIn('200.dp - 40.dp - 11.dp - ChatTailClearance', case)
        self.assertIn('height - 40.dp - 11.dp - ChatTailClearance', case)
        self.assertIn('initial, compositions.get()', case)
        self.assertNotIn('125.dp', case)
        self.assertNotIn('- 24.dp', case)

    def test_catchup_draw_and_terminal_transfer_share_observable_geometry(self):
        source = CHAT.read_text()
        self.assertIn('var viewportOffset by mutableIntStateOf(0)', source)
        settle = source.split('if (followDistance <= 0f)', 1)[1].split('val alignment =', 1)[0]
        self.assertIn('clearanceLayout.remeasurement?.forceRemeasure()', settle)
        self.assertIn('settledTail.size == currentNestedTail.contentHeight', settle)
        self.assertIn('currentNestedTail.followCatchup = false', settle)

    def test_terminal_reveal_keeps_strict_pixels_and_only_accounts_for_native_entry(self):
        source = DEVICE.read_text()
        for token in ("minimumGap = ChatTailClearance.value - 2f / density",
                      "maximumGap = ChatTailClearance.value + if (settled) 64f else 26f",
                      "ink > 2", "index < 90", "growthFrames > 120", "paintedGrowth >= 5",
                      "Lifecycle.State.CREATED", "Lifecycle.State.RESUMED",
                      "if (entering.isEmpty()) return bottom", "it.config.getOrNull(WorkflowEntryEntering)?.invoke() == true",
                      "it.positionInRoot.y + it.size.height"):
            self.assertIn(token, source)
        observation = source.split("fun paintedBottom(", 1)[1].split("fun testNewToolHeaderHasNoPartialInkAtTheOriginalSafetyEdge(", 1)[0]
        self.assertNotIn("boundsInRoot", observation)
        self.assertNotIn("ChatTailClearance", observation)
        self.assertIn("testNativeEntryMeasurementStillDetectsDrawOnlyClipping", source)
        self.assertIn("The strict latest-line ink probe must detect the deliberately clipped line", source)


if __name__ == "__main__":
    unittest.main()
