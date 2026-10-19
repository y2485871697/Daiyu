package app.daiyu.android

import android.app.Instrumentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Window
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Test-window vote only; production high-refresh selection remains unchanged. */
internal class SyntheticRefreshRateOverride(
    private val instrumentation: Instrumentation,
    private val window: Window,
    private val requestedHz: Int,
) : AutoCloseable {
    private val manager = window.context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val observations = ArrayList<JSONObject>()
    private var displayId = -1
    private var selectedModeId = 0
    private var selectedRate = 0f
    private var previousRate = 0f
    private var previousModeId = 0
    private var listening = false
    private var applied = false
    private var measuring = false
    private var invalid = false
    private var closed = false
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) { if (id == displayId && measuring) invalid = true }
        override fun onDisplayChanged(id: Int) {
            if (id == displayId && measuring) try { record() } catch (_: Exception) { invalid = true }
        }
    }

    fun apply() {
        require(requestedHz == 60 || requestedHz == 120)
        syntheticMain(instrumentation) {
            val display = checkNotNull(window.decorView.display)
            val current = display.mode
            val selected = display.supportedModes.firstOrNull {
                it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight &&
                    abs(it.refreshRate - requestedHz) <= 1f
            } ?: error("synthetic_refresh_mode_unsupported")
            displayId = display.displayId
            selectedModeId = selected.modeId; selectedRate = selected.refreshRate
            val attributes = window.attributes
            previousRate = attributes.preferredRefreshRate; previousModeId = attributes.preferredDisplayModeId
            manager.registerDisplayListener(listener, Handler(Looper.getMainLooper())); listening = true
            attributes.preferredRefreshRate = selectedRate; attributes.preferredDisplayModeId = selectedModeId
            applied = true
            window.attributes = attributes
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!syntheticMain(instrumentation) { matches(snapshot()) }) {
            check(System.nanoTime() < deadline) { "synthetic_refresh_rate_not_applied" }
            Thread.sleep(50)
        }
        syntheticMain(instrumentation) { measuring = true; record() }
    }

    private fun snapshot(): JSONObject {
        val display = checkNotNull(window.decorView.display)
        return JSONObject().put("observed_ns", System.nanoTime()).put("display_mode_id", display.mode.modeId)
            .put("refresh_rate_hz", display.refreshRate).put("display_mode_refresh_rate_hz", display.mode.refreshRate)
            .put("preferred_refresh_rate_hz", window.attributes.preferredRefreshRate)
            .put("preferred_display_mode_id", window.attributes.preferredDisplayModeId)
    }
    private fun matches(value: JSONObject): Boolean =
        value.getInt("display_mode_id") == selectedModeId && value.getInt("preferred_display_mode_id") == selectedModeId &&
            listOf("refresh_rate_hz", "display_mode_refresh_rate_hz", "preferred_refresh_rate_hz").all {
                abs(value.getDouble(it) - requestedHz) <= 1.0
            }

    private fun record() {
        val value = snapshot()
        if (!matches(value)) invalid = true
        if (observations.size < 1024) observations.add(value) else invalid = true
    }
    fun verify() = syntheticMain(instrumentation) {
        check(measuring && !closed)
        record()
        check(!invalid) { "synthetic_refresh_rate_changed" }
    }
    fun observations(): JSONArray = syntheticMain(instrumentation) { JSONArray(observations.toList()) }
    fun verified(): Boolean = syntheticMain(instrumentation) { observations.isNotEmpty() && !invalid }

    override fun close() {
        if (closed) return
        syntheticMain(instrumentation) {
            try {
                if (measuring) record()
            } catch (failure: Throwable) {
                invalid = true
                throw failure
            } finally {
                measuring = false
                try {
                    if (listening) { manager.unregisterDisplayListener(listener); listening = false }
                } finally {
                    if (applied) {
                        val attributes = window.attributes
                        if (attributes.preferredRefreshRate == selectedRate && attributes.preferredDisplayModeId == selectedModeId) {
                            attributes.preferredRefreshRate = previousRate; attributes.preferredDisplayModeId = previousModeId
                            window.attributes = attributes
                        }
                    }
                    closed = true
                }
            }
            check(!invalid) { "synthetic_refresh_rate_changed" }
        }
    }
}
