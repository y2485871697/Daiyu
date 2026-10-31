package app.daiyu.android.voice.duplex

import android.media.AudioDeviceInfo
import org.junit.Assert.*
import org.junit.Test

/** Synthetic route fixtures; not a claim of physical Android routing verification. */
class DuplexRouteLeaseTest {
    private class Routes : DuplexRoutePort {
        var modeFailure: RuntimeException? = null
        override var mode = 0
            set(value) {
                if (value == 0) modeFailure?.let { throw it }
                field = value
            }
        override val communicationMode = 3
        override var current: Int? = 8
        var privateAvailable = true
        var accept = true
        val unavailable = mutableSetOf<Int>()
        var systemDefault: Int? = 4
        var clearCalls = 0
        var selectFailure: RuntimeException? = null
        var clearFailure: RuntimeException? = null
        var beforeSelect: ((Int) -> Unit)? = null
        var afterSelect: (() -> Unit)? = null
        var afterClear: (() -> Unit)? = null
        var onAvailability: (() -> Unit)? = null
        val selected = mutableListOf<Int>()
        override fun target(speaker: Boolean): Int? = if (speaker) 1 else if (privateAvailable) 2 else null
        override fun isSpeaker(route: Int?) = route == 1
        override fun isAvailable(route: Int): Boolean {
            onAvailability?.invoke()
            return route !in unavailable
        }
        override fun select(route: Int): Boolean {
            selected += route
            selectFailure?.let { throw it }
            beforeSelect?.invoke(route)
            if (!accept || route in unavailable) return false
            current = route
            afterSelect?.invoke()
            return true
        }
        override fun clearSelection(): Int? {
            clearCalls++
            clearFailure?.let { throw it }
            current = systemDefault
            val observed = current
            afterClear?.invoke()
            return observed
        }
    }

    private fun closeFailure(lease: DuplexRouteLease): RuntimeException {
        try {
            lease.close()
        } catch (failure: RuntimeException) {
            return failure
        }
        throw AssertionError("close must report the platform failure")
    }

    @Test fun speakerAndPrivateRouteAreRealAndCloseRestoresOwnedModeAndRoute() {
        val routes = Routes()
        val lease = DuplexRouteLease(routes)
        assertTrue(lease.setSpeaker(true))
        assertEquals(3, routes.mode)
        assertEquals(1, routes.current)
        assertTrue(lease.speaker)
        assertTrue(lease.setSpeaker(false))
        assertFalse(lease.speaker)
        assertEquals(2, routes.current)
        lease.close(); lease.close()
        assertEquals(8, routes.current)
        assertEquals(0, routes.mode)
        assertEquals(listOf(1, 2, 8), routes.selected)
        assertFalse(lease.setSpeaker(true))
    }

    @Test fun unavailablePrivateRouteAndRejectedSelectionNeverFakeSuccess() {
        val routes = Routes()
        val lease = DuplexRouteLease(routes)
        assertTrue(lease.setSpeaker(true))
        routes.privateAvailable = false
        assertFalse(lease.setSpeaker(false))
        assertTrue(lease.speaker)
        routes.privateAvailable = true; routes.accept = false
        assertFalse(lease.setSpeaker(false))
        assertTrue(lease.speaker)
        routes.accept = true; lease.close()
        assertEquals(8, routes.current)
    }

    @Test fun externalRouteOrModeChangeIsNotOverwrittenByControlsOrClose() {
        for (changeMode in listOf(false, true)) {
            val routes = Routes()
            val lease = DuplexRouteLease(routes)
            assertTrue(lease.setSpeaker(true))
            if (changeMode) routes.mode = 9 else routes.current = 99
            val selectedBefore = routes.selected.toList()
            assertFalse(lease.setSpeaker(false))
            lease.close()
            assertEquals(selectedBefore, routes.selected)
            if (changeMode) assertEquals(9, routes.mode) else assertEquals(99, routes.current)
        }
    }

    @Test fun legacyScoEnumerationCannotConfirmPrivateRoute() {
        val speakerAndSco = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        assertNull(legacyPrivateRouteTarget(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))
        assertNull(legacyPrivateRouteTarget(speakerAndSco))
        assertNull(legacyPrivateRouteTarget(speakerAndSco + AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertNull(legacyPrivateRouteTarget(speakerAndSco + AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertNull(legacyPrivateRouteTarget(listOf(AudioDeviceInfo.TYPE_BLE_HEADSET)))
        val routes = Routes().apply {
            // Even an already false speaker flag is not evidence that SCO was selected.
            current = 0
            privateAvailable = legacyPrivateRouteTarget(speakerAndSco) != null
        }
        val lease = DuplexRouteLease(routes)
        assertFalse(lease.speaker)
        assertFalse(lease.setSpeaker(false))
        lease.close()
        assertTrue(routes.selected.isEmpty())
        assertEquals(0, routes.mode)
        for (type in listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)) {
            assertEquals(0, legacyPrivateRouteTarget(listOf(type)))
        }
    }

    @Test fun missingPreviousDeviceClearsPreferenceAndRestoresModeWithAnySystemDefault() {
        for (default in listOf<Int?>(null, 4, 1)) {
            val routes = Routes().apply { systemDefault = default }
            val lease = DuplexRouteLease(routes)
            assertTrue(lease.setSpeaker(true))
            routes.unavailable += 8
            lease.close(); lease.close()
            assertEquals(listOf(1), routes.selected)
            assertEquals(1, routes.clearCalls)
            assertEquals(default, routes.current)
            assertEquals(0, routes.mode)
        }
    }

    @Test fun previousSystemDefaultAlsoReleasesPreferenceWithoutRequiringNullActiveDevice() {
        val routes = Routes().apply { current = null }
        val lease = DuplexRouteLease(routes)
        assertTrue(lease.setSpeaker(true))
        lease.close()
        assertEquals(1, routes.clearCalls)
        assertEquals(4, routes.current)
        assertEquals(0, routes.mode)
    }

    @Test fun deviceDisappearingDuringRestoreAlsoFallsBackToSystemDefault() {
        val routes = Routes()
        val lease = DuplexRouteLease(routes)
        assertTrue(lease.setSpeaker(true))
        routes.beforeSelect = { routes.unavailable += it }
        lease.close()
        assertEquals(listOf(1, 8), routes.selected)
        assertEquals(1, routes.clearCalls)
        assertEquals(4, routes.current)
        assertEquals(0, routes.mode)
    }

    @Test fun disappearanceDuringRestoreDoesNotClearAnExternalRouteOrMode() {
        for (changeMode in listOf(false, true)) {
            val routes = Routes()
            val lease = DuplexRouteLease(routes)
            assertTrue(lease.setSpeaker(true))
            routes.beforeSelect = {
                routes.unavailable += it
                if (changeMode) routes.mode = 9 else routes.current = 99
            }
            lease.close()
            assertEquals(listOf(1, 8), routes.selected)
            assertEquals(0, routes.clearCalls)
            assertEquals(if (changeMode) 9 else 3, routes.mode)
            assertEquals(if (changeMode) 1 else 99, routes.current)
        }
    }

    @Test fun rejectedAvailableDeviceRestoreIsNotHiddenByClearingOrRestoringMode() {
        val routes = Routes()
        val lease = DuplexRouteLease(routes)
        assertTrue(lease.setSpeaker(true))
        routes.accept = false
        assertEquals("communication_route_restore_failed", closeFailure(lease).message)
        assertEquals(0, routes.clearCalls)
        assertEquals(1, routes.current)
        assertEquals(3, routes.mode)
    }

    @Test fun platformRestoreAndClearExceptionsArePropagatedWithoutRestoringMode() {
        for (missingDevice in listOf(false, true)) {
            val routes = Routes()
            val lease = DuplexRouteLease(routes)
            assertTrue(lease.setSpeaker(true))
            val failure = SecurityException("platform release denied")
            if (missingDevice) {
                routes.unavailable += 8
                routes.clearFailure = failure
            } else routes.selectFailure = failure
            assertSame(failure, closeFailure(lease))
            assertEquals(3, routes.mode)
            assertEquals(1, routes.current)
            assertEquals(if (missingDevice) 1 else 0, routes.clearCalls)
        }
    }

    @Test fun modeRestoreFailureAfterClearIsStillReported() {
        val routes = Routes()
        val lease = DuplexRouteLease(routes)
        assertTrue(lease.setSpeaker(true))
        routes.unavailable += 8
        val failure = IllegalStateException("mode restore failed")
        routes.modeFailure = failure
        assertSame(failure, closeFailure(lease))
        assertEquals(1, routes.clearCalls)
        assertEquals(4, routes.current)
        assertEquals(3, routes.mode)
    }

    @Test fun missingPreviousDeviceDoesNotPermitOverwritingExternalRouteOrMode() {
        for (changeMode in listOf(false, true)) {
            val routes = Routes()
            val lease = DuplexRouteLease(routes)
            assertTrue(lease.setSpeaker(true))
            routes.unavailable += 8
            if (changeMode) routes.mode = 9 else routes.current = 99
            lease.close()
            assertEquals(listOf(1), routes.selected)
            assertEquals(0, routes.clearCalls)
            assertEquals(if (changeMode) 9 else 3, routes.mode)
            assertEquals(if (changeMode) 1 else 99, routes.current)
        }
    }

    @Test fun externalChangeDuringAvailabilityCheckPreventsClearing() {
        for (changeMode in listOf(false, true)) {
            val routes = Routes()
            val lease = DuplexRouteLease(routes)
            assertTrue(lease.setSpeaker(true))
            routes.unavailable += 8
            routes.onAvailability = { if (changeMode) routes.mode = 9 else routes.current = 99 }
            lease.close()
            assertEquals(0, routes.clearCalls)
            assertEquals(if (changeMode) 9 else 3, routes.mode)
            assertEquals(if (changeMode) 1 else 99, routes.current)
        }
    }

    @Test fun externalChangeDuringRestoreOrAfterClearObservationPreventsModeRestore() {
        for (missingDevice in listOf(false, true)) {
            for (changeMode in listOf(false, true)) {
                val routes = Routes()
                val lease = DuplexRouteLease(routes)
                assertTrue(lease.setSpeaker(true))
                val externalChange: () -> Unit = {
                    if (changeMode) routes.mode = 9 else routes.current = 99
                }
                if (missingDevice) {
                    routes.unavailable += 8
                    routes.afterClear = externalChange
                } else routes.afterSelect = externalChange
                lease.close()
                assertEquals(if (missingDevice) 1 else 0, routes.clearCalls)
                assertEquals(if (changeMode) 9 else 3, routes.mode)
                assertEquals(if (changeMode) (if (missingDevice) 4 else 8) else 99, routes.current)
            }
        }
    }

    @Test fun initialRouteFailureStillRestoresOnlyOwnedMode() {
        val routes = Routes(); routes.accept = false
        val lease = DuplexRouteLease(routes)
        assertFalse(lease.setSpeaker(true))
        assertEquals(8, routes.current)
        routes.accept = true; lease.close()
        assertEquals(0, routes.mode)
        assertEquals(8, routes.current)
    }
}
