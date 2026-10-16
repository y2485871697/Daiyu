package app.daiyu.android.domain.haptics

import org.junit.Assert.*
import org.junit.Test

class HapticEngineTest {
    private class Clock(var time: Long = 1000L) : HapticClock { override fun nowMillis() = time }
    private class Port : HapticPort {
        val requests = mutableListOf<HapticRequest>()
        var cancels = 0
        var result = HapticDelivery.PLAYED
        var throws = false
        override fun play(request: HapticRequest): HapticDelivery {
            requests += request
            if (throws) throw IllegalStateException("device failed")
            return result
        }
        override fun cancel() { cancels++ }
    }
    private class Fixture {
        val clock = Clock()
        val port = Port()
        val engine = HapticEngine(clock)
        val owner = engine.attach("conversation", port, resumed = true)
        fun advance(ms: Long = 32L) { clock.time += ms; engine.drain() }
    }

    @Test fun localDefaultsAndWireParsingAreExact() {
        assertEquals(HapticSettings(true, true, true, true, HapticIntensity.DEFAULT), HapticSettings())
        assertEquals(HapticIntensity.LOW, HapticIntensity.fromWire(" LOW "))
        assertEquals(HapticIntensity.DEFAULT, HapticIntensity.fromWire("unknown"))
        assertEquals(HapticIntensity.DEFAULT, HapticIntensity.fromWire(null))
        assertEquals(0.12f, HapticIntensity.LOW.primitiveScale(0.1f), 0f)
        assertEquals(0.35f, HapticIntensity.MEDIUM.primitiveScale(0.1f), 0f)
        assertEquals(1f, HapticIntensity.HIGH.primitiveScale(0.1f), 0f)
        val f = Fixture()
        HapticAgentAction.entries.forEach { action ->
            assertEquals(HapticDelivery.PLAYED, f.engine.agentAction(f.owner, action))
            assertEquals(action, f.port.requests.last().agentAction)
        }
        f.engine.updateSettings(HapticSettings(touch = false))
        assertNull(f.engine.agentAction(f.owner, HapticAgentAction.TAP))
    }

    @Test fun allIntensityClickAndTickAmplitudesAndPrimitiveScales() {
        val amplitudes = listOf(48 to 32, 140 to 96, 255 to 200)
        HapticIntensity.entries.drop(1).forEachIndexed { index, intensity ->
            val click = HapticRequest(HapticKind.CLICK, intensity)
            val tick = HapticRequest(HapticKind.TICK, intensity)
            assertEquals(HapticMotorEffect.OneShot(16L, amplitudes[index].first), motorEffect(click, 26, false, true))
            assertEquals(HapticMotorEffect.OneShot(10L, amplitudes[index].second), motorEffect(tick, 26, false, true))
            assertEquals(HapticMotorEffect.Primitive(false, intensity.clickScale), motorEffect(click, 30, true, true))
            assertEquals(HapticMotorEffect.Primitive(true, intensity.tickScale), motorEffect(tick, 30, true, true))
            assertEquals(HapticMotorEffect.OneShot(10L, null), motorEffect(tick, 26, false, false))
        }
        val default = HapticRequest(HapticKind.TICK, HapticIntensity.DEFAULT)
        assertEquals(HapticMotorEffect.SystemTick, motorEffect(default, 29, false, true))
        assertEquals(HapticMotorEffect.OneShot(10L, null), motorEffect(default, 26, false, true))
    }

    @Test fun generationUsesThirtyTwoMillisAndDoesNotReplayDroppedText() {
        val f = Fixture()
        assertEquals(HapticDelivery.PLAYED, f.engine.visibleAdvance(f.owner))
        repeat(31) { f.clock.time++; assertNull(f.engine.visibleAdvance(f.owner)) }
        f.clock.time++
        assertEquals(HapticDelivery.PLAYED, f.engine.visibleAdvance(f.owner))
        assertEquals(2, f.port.requests.size)
        assertEquals(0, f.engine.pendingCount())
    }

    @Test fun toolAndCompletionDedupSurviveRebindAndQueueBehindText() {
        val f = Fixture()
        f.engine.visibleAdvance(f.owner)
        repeat(4) { f.engine.toolAppeared(f.owner, "tool") }
        f.engine.reasoningCompleted(f.owner, "reason")
        f.engine.reasoningCompleted(f.owner, "reason")
        f.engine.toolAppeared(f.owner, " ")
        assertEquals(2, f.engine.pendingCount())
        f.advance(31)
        assertEquals(1, f.port.requests.size)
        f.advance(1)
        f.advance()
        assertEquals(3, f.port.requests.size)
        val rebound = f.engine.attach("conversation", f.port, resumed = true)
        f.engine.toolAppeared(rebound, "tool")
        assertEquals(0, f.engine.pendingCount())
    }

    @Test fun toolDedupeHasBoundedTwoHundredFiftySixIds() {
        val f = Fixture()
        repeat(257) { f.engine.toolAppeared(f.owner, "tool-$it") }
        assertEquals(257, f.engine.pendingCount())
        f.engine.toolAppeared(f.owner, "tool-256")
        assertEquals(257, f.engine.pendingCount())
        f.engine.toolAppeared(f.owner, "tool-0")
        assertEquals(258, f.engine.pendingCount())
    }

    @Test fun backgroundIsCappedAndLateCallbacksDoNotReplayInForeground() {
        val f = Fixture()
        f.engine.lifecycle(f.owner, resumed = false, visible = false)
        f.engine.setForeground(false)
        f.engine.backgroundOutput(f.owner, Int.MAX_VALUE)
        assertEquals(36, f.engine.pendingCount())
        f.engine.backgroundOutput(f.owner, Int.MAX_VALUE)
        assertEquals(36, f.engine.pendingCount())
        f.advance(31)
        assertTrue(f.port.requests.isEmpty())
        f.advance(1)
        assertEquals(1, f.port.requests.size)
        f.engine.setForeground(true)
        f.engine.lifecycle(f.owner, true, true)
        f.advance(1000)
        assertEquals(1, f.port.requests.size)
        assertEquals(0, f.engine.pendingCount())
        assertNull(f.engine.nextDueMillis())
    }

    @Test fun backgroundReasoningOffStillAllowsTextToolsAndCompletion() {
        val f = Fixture()
        f.engine.updateSettings(HapticSettings(backgroundReasoning = false))
        f.engine.lifecycle(f.owner, false, false)
        f.engine.setForeground(false)
        f.engine.backgroundOutput(f.owner, 3, reasoning = true)
        assertEquals(0, f.engine.pendingCount())
        f.engine.backgroundOutput(f.owner, 2)
        f.engine.toolAppeared(f.owner, "tool")
        f.engine.reasoningCompleted(f.owner, "reason")
        assertEquals(4, f.engine.pendingCount())
        repeat(4) { f.advance() }
        assertEquals(4, f.port.requests.size)
    }

    @Test fun backgroundSwitchDoesNotBlockHiddenForegroundAndDoesBlockStoppedApp() {
        val f = Fixture()
        f.engine.updateSettings(HapticSettings(background = false))
        f.engine.lifecycle(f.owner, false, false)
        f.engine.backgroundOutput(f.owner, 1)
        assertEquals(1, f.engine.pendingCount())
        f.engine.setForeground(false)
        assertEquals(0, f.engine.pendingCount())
        f.engine.toolAppeared(f.owner, "tool")
        f.advance()
        assertTrue(f.port.requests.isEmpty())
    }

    @Test fun switchDependenciesCancelAndPreviewOnlyBypassesAppSwitch() {
        val f = Fixture()
        f.engine.touch(f.owner, HapticKind.CLICK)
        f.engine.toolAppeared(f.owner, "tool")
        f.engine.updateSettings(HapticSettings(touch = false))
        assertEquals(1, f.port.cancels)
        assertEquals(0, f.engine.pendingCount())
        assertNull(f.engine.touch(f.owner, HapticKind.CLICK))
        assertNull(f.engine.visibleAdvance(f.owner))
        assertEquals(HapticDelivery.PLAYED, f.engine.preview(f.owner, HapticIntensity.HIGH))
        assertTrue(f.port.requests.last().preview)
        assertEquals(HapticIntensity.HIGH, f.port.requests.last().intensity)
        f.engine.setForeground(false)
        assertEquals(2, f.port.cancels)
        assertNull(f.engine.preview(f.owner, HapticIntensity.LOW))
        f.engine.updateSettings(HapticSettings(generation = false))
        assertFalse(f.engine.settings.backgroundOn)
        assertFalse(f.engine.settings.backgroundReasoningOn)
    }

    @Test fun closingOwnerCannotCancelOrRemoveAnotherOwnersPendingWork() {
        val f = Fixture()
        val secondPort = Port()
        val second = f.engine.attach("conversation", secondPort, resumed = true)
        f.engine.toolAppeared(second, "second")
        f.engine.detach(f.owner)
        f.engine.drain()
        assertEquals(1, secondPort.requests.size)
        f.engine.detach(f.owner)
        assertEquals(0, secondPort.cancels)
        f.engine.detach(second)
        assertEquals(1, secondPort.cancels)
        f.engine.toolAppeared(second, "late")
        assertEquals(0, f.engine.pendingCount())
    }

    @Test fun changingConversationCancelsOldAndRejectsLateEvents() {
        val f = Fixture()
        f.engine.visibleAdvance(f.owner)
        f.engine.toolAppeared(f.owner, "old")
        val next = f.engine.attach("next", Port(), resumed = true)
        assertEquals(1, f.port.cancels)
        assertEquals(0, f.engine.pendingCount())
        f.engine.toolAppeared(f.owner, "late")
        f.engine.backgroundOutput(f.owner, 10)
        f.engine.toolAppeared(next, "old")
        assertEquals(1, f.engine.pendingCount())
        f.advance()
        assertEquals(1, f.port.requests.size)
    }

    @Test fun disablingPlayingOwnerCancelsAndDropsWorkWithoutReplayOnEnable() {
        val f = Fixture()
        f.engine.visibleAdvance(f.owner)
        f.engine.toolAppeared(f.owner, "queued")
        f.engine.lifecycle(f.owner, resumed = true, visible = true, enabled = false)
        assertEquals(1, f.port.cancels)
        assertEquals(0, f.engine.pendingCount())
        assertNull(f.engine.nextDueMillis())
        assertNull(f.engine.touch(f.owner, HapticKind.CLICK))
        assertNull(f.engine.preview(f.owner, HapticIntensity.HIGH))
        assertNull(f.engine.agentAction(f.owner, HapticAgentAction.TAP))
        assertNull(f.engine.visibleAdvance(f.owner))
        f.engine.backgroundOutput(f.owner, 3)
        f.engine.toolAppeared(f.owner, "disabled")
        f.engine.updateSettings(HapticSettings(touch = false))
        f.engine.lifecycle(f.owner, false, false, enabled = false)
        assertEquals(1, f.port.cancels)
        f.engine.updateSettings(HapticSettings())
        f.engine.lifecycle(f.owner, true, true)
        f.advance(1000)
        assertEquals(1, f.port.requests.size)
        assertEquals(HapticDelivery.PLAYED, f.engine.touch(f.owner, HapticKind.CLICK))
        f.engine.lifecycle(f.owner, true, true, enabled = false)
        assertEquals(2, f.port.cancels)
    }

    @Test fun disabledResumedAttachAndLifecycleCannotStealConversation() {
        val f = Fixture()
        f.engine.visibleAdvance(f.owner)
        f.engine.toolAppeared(f.owner, "queued")
        val otherPort = Port()
        val other = f.engine.attach("other", otherPort, resumed = true, enabled = false)
        assertEquals(0, f.port.cancels)
        assertEquals(1, f.engine.pendingCount())
        f.engine.lifecycle(other, resumed = true, visible = true, enabled = false)
        f.engine.lifecycle(f.owner, resumed = false, visible = false)
        f.engine.updateSettings(HapticSettings(intensity = HapticIntensity.LOW))
        f.advance()
        assertEquals(2, f.port.requests.size)
        assertTrue(otherPort.requests.isEmpty())
        f.engine.lifecycle(f.owner, resumed = true, visible = true)
        assertEquals(HapticDelivery.PLAYED, f.engine.touch(f.owner, HapticKind.CLICK))
        f.engine.lifecycle(other, resumed = true, visible = true, enabled = true)
        assertEquals(1, f.port.cancels)
        assertNull(f.engine.touch(f.owner, HapticKind.CLICK))
        assertEquals(HapticDelivery.PLAYED, f.engine.touch(other, HapticKind.CLICK))
        // A late disabled resume from the old screen must not switch back or cancel the new one.
        f.engine.lifecycle(f.owner, resumed = true, visible = true, enabled = false)
        f.engine.detach(f.owner)
        assertEquals(0, otherPort.cancels)
        assertEquals(HapticDelivery.PLAYED, f.engine.touch(other, HapticKind.CLICK))
    }

    @Test fun disabledInitialHostCannotClaimSessionUntilEnabledResume() {
        val engine = HapticEngine(Clock())
        val port = Port()
        val owner = engine.attach("disabled", port, resumed = true, enabled = false)
        engine.lifecycle(owner, resumed = false, visible = false, enabled = true)
        engine.backgroundOutput(owner, 3)
        engine.toolAppeared(owner, "unclaimed")
        assertEquals(0, engine.pendingCount())
        engine.lifecycle(owner, resumed = true, visible = true, enabled = true)
        assertEquals(HapticDelivery.PLAYED, engine.touch(owner, HapticKind.CLICK))
    }

    @Test fun sharedPortStaleDisableAndDetachCannotCancelNewPlayingHost() {
        val f = Fixture()
        f.engine.touch(f.owner, HapticKind.CLICK)
        val replacement = f.engine.attach("conversation", f.port, resumed = true)
        f.engine.touch(replacement, HapticKind.CLICK)
        f.engine.lifecycle(f.owner, resumed = false, visible = false, enabled = false)
        f.engine.detach(f.owner)
        assertEquals(0, f.port.cancels)
        f.engine.toolAppeared(replacement, "replacement")
        f.engine.drain()
        assertEquals(3, f.port.requests.size)
        f.engine.lifecycle(replacement, resumed = true, visible = true, enabled = false)
        assertEquals(1, f.port.cancels)
        f.engine.detach(replacement)
        f.engine.close()
        assertEquals(1, f.port.cancels)
    }

    @Test fun sharedPortUnplayedHostDetachDoesNotCancelPlayingHost() {
        val f = Fixture()
        f.engine.touch(f.owner, HapticKind.CLICK)
        val unplayed = f.engine.attach("conversation", f.port, resumed = true)
        f.engine.detach(unplayed)
        assertEquals(0, f.port.cancels)
        f.engine.detach(f.owner)
        assertEquals(1, f.port.cancels)
    }

    @Test fun failedReplacementPlayDoesNotAcquireCancellationOwnership() {
        val f = Fixture()
        f.engine.touch(f.owner, HapticKind.CLICK)
        val replacement = f.engine.attach("conversation", f.port, resumed = true)
        f.port.result = HapticDelivery.UNAVAILABLE
        assertEquals(HapticDelivery.UNAVAILABLE, f.engine.touch(replacement, HapticKind.CLICK))
        f.engine.lifecycle(replacement, true, true, enabled = false)
        f.engine.detach(replacement)
        assertEquals(0, f.port.cancels)
        f.engine.lifecycle(f.owner, true, true, enabled = false)
        assertEquals(1, f.port.cancels)
    }

    @Test fun settingsOffOnPauseAndReplacementKeepCancellationWithLatestPlayer() {
        val f = Fixture()
        f.engine.touch(f.owner, HapticKind.CLICK)
        f.engine.updateSettings(HapticSettings(touch = false))
        assertEquals(1, f.port.cancels)
        val replacement = f.engine.attach("conversation", f.port, resumed = true)
        f.engine.lifecycle(f.owner, resumed = false, visible = false)
        assertNull(f.engine.touch(replacement, HapticKind.CLICK))
        f.engine.updateSettings(HapticSettings())
        f.engine.touch(replacement, HapticKind.CLICK)
        f.engine.lifecycle(f.owner, false, false, enabled = false)
        f.engine.detach(f.owner)
        assertEquals(1, f.port.cancels)
        f.engine.lifecycle(replacement, false, false)
        f.engine.setForeground(false)
        assertEquals(2, f.port.cancels)
        f.engine.backgroundOutput(replacement, 1)
        f.advance()
        assertEquals(3, f.port.requests.size)
        f.engine.close()
        assertEquals(3, f.port.cancels)
        f.engine.detach(replacement)
        f.engine.lifecycle(replacement, true, true)
        assertNull(f.engine.touch(replacement, HapticKind.CLICK))
        // A fresh session after close must not inherit cancellation ownership.
        val reopened = f.engine.attach("conversation", f.port, resumed = true)
        f.engine.setForeground(true)
        assertEquals(HapticDelivery.PLAYED, f.engine.touch(reopened, HapticKind.CLICK))
        f.engine.detach(replacement)
        assertEquals(3, f.port.cancels)
        f.engine.detach(reopened)
        assertEquals(4, f.port.cancels)
    }

    @Test fun failuresAreExplicitAndNeverRetriedOrThrownToUi() {
        HapticDelivery.entries.filter { it != HapticDelivery.PLAYED }.forEach { failure ->
            val f = Fixture()
            f.port.result = failure
            assertEquals(failure, f.engine.touch(f.owner, HapticKind.CLICK))
            f.engine.updateSettings(HapticSettings(touch = false))
            assertEquals(0, f.port.cancels)
            assertEquals(1, f.port.requests.size)
        }
        val f = Fixture()
        f.port.throws = true
        assertEquals(HapticDelivery.FAILED, f.engine.touch(f.owner, HapticKind.CLICK))
        assertEquals(HapticDelivery.FAILED, f.engine.lastDelivery)
    }
}
