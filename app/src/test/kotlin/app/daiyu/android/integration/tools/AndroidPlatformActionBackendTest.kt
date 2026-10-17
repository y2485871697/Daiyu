package app.daiyu.android.integration.tools

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.AudioManager
import org.robolectric.RuntimeEnvironment
import app.daiyu.android.domain.tools.ExecutionReceipt
import app.daiyu.android.platform.actions.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Host shadows only: no device permissions, shell, real audio, activity launch or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class AndroidPlatformActionBackendTest {
    private class ContextWithNormalPermissions(base: Context, private val granted: Boolean) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun checkSelfPermission(permission: String): Int =
            if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }
    private fun backend(granted: Boolean = true): AndroidPlatformActionBackend = AndroidPlatformActionBackend(
        ContextWithNormalPermissions(RuntimeEnvironment.getApplication(), granted))
    @Test fun volumeRequiresReadbackAndMissingPermissionDoesNotChangeState() {
        val app = RuntimeEnvironment.getApplication()
        val audio = checkNotNull(app.getSystemService(AudioManager::class.java))
        val target = minOf(3, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        val action = PlatformAction.parse(PlatformActionKind.VOLUME, """{"stream":"music","index":$target}""")
        assertEquals(ExecutionReceipt.NotExecuted("modify_audio_settings_missing"), backend(false).execute(action) { false })
        assertEquals(ExecutionReceipt.NotExecuted("platform_action_cancelled"), backend().execute(action) { true })
        val receipt = backend().execute(action) { false }
        assertTrue(receipt is ExecutionReceipt.Confirmed)
        assertEquals(target, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue((receipt as ExecutionReceipt.Confirmed).payload.contains("\"read_back\":true"))
    }
    @Test fun outOfRealRangeVolumeIsNotExecuted() {
        val action = PlatformAction.parse(PlatformActionKind.VOLUME, """{"stream":"music","index":100}""")
        assertEquals(ExecutionReceipt.NotExecuted("volume_index_unavailable_or_fixed"), backend().execute(action) { false })
    }
    @Test fun mediaSubmissionNeverPretendsPlaybackWasConfirmed() {
        val action = PlatformAction.parse(PlatformActionKind.MEDIA, """{"key":"pause"}""")
        assertTrue(backend().execute(action) { false } is ExecutionReceipt.Unknown)
    }
    @Test fun modernWifiIsExplicitlyUnavailableAndNeverAttemptsAWrite() {
        val action = PlatformAction.parse(PlatformActionKind.WIFI, """{"enabled":true}""")
        assertNotNull(backend().unavailable(action.kind))
        assertEquals(ExecutionReceipt.NotExecuted("wifi_toggle_requires_privileged_identity_on_android_10_plus"),
            backend().execute(action) { false })
    }
    @Test @Config(sdk = [28]) fun legacyWifiUsesActualRadioStateReadback() {
        val action = PlatformAction.parse(PlatformActionKind.WIFI, """{"enabled":false}""")
        assertNull(backend().unavailable(action.kind))
        val receipt = backend().execute(action) { false }
        assertTrue(receipt is ExecutionReceipt.Confirmed)
        assertTrue((receipt as ExecutionReceipt.Confirmed).payload.contains("\"enabled\":false"))
        assertTrue(receipt.payload.contains("\"read_back\":true"))
    }
}
