package app.daiyu.android.platform

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.daiyu.android.domain.ReadOnlyTool
import app.daiyu.android.integration.tools.AndroidToolRegistry
import app.daiyu.android.storage.PlatformToolSettings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/** Synthetic preferences/URI fixtures only: never prompts, enumerates providers or reads user data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, manifest = Config.NONE)
class PlatformProductionSettingsTest {
    private lateinit var preferences: android.content.SharedPreferences
    @Before fun freshPreferences() {
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences("platform-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    }

    @Test fun defaultsAndLegacyBatteryNeverEnablePlatformOrPrivateDestinations() {
        preferences.edit().putBoolean("battery-enabled", true).putBoolean("device-status-enabled", true).commit()
        val frozen = PlatformToolSettings(preferences).freeze()
        assertTrue(frozen.platformAllowed.isEmpty())
        assertEquals(setOf(AndroidToolRegistry.BATTERY_CAPABILITY),
            AndroidToolRegistry.capabilitiesFor(setOf(ReadOnlyTool.BATTERY), frozen.platformAllowed))
        for (tool in PlatformReadTool.entries) {
            assertFalse(frozen.modelConsent(tool)); assertFalse(frozen.historyConsent(tool))
            if (tool.toolName in DeviceResultPolicy.PRIVATE_TOOLS) assertFalse(frozen.resultPolicy.allowsShared(tool.toolName))
        }
    }

    @Test fun settingsPersistIndividuallyAndRunFreezeKeepsSelectionAndBothConsentBits() {
        val store = PlatformToolSettings(preferences)
        val tool = PlatformReadTool.CONTACTS
        store.select(tool, true)
        val selected = store.freeze()
        assertEquals(setOf(tool), selected.platformAllowed)
        assertFalse(selected.resultPolicy.allowsShared(tool.toolName))
        store.consentModel(tool, true)
        val modelOnly = PlatformToolSettings(preferences).freeze()
        assertTrue(modelOnly.modelConsent(tool)); assertFalse(modelOnly.historyConsent(tool))
        assertFalse(modelOnly.resultPolicy.allowsShared(tool.toolName))
        store.consentHistory(tool, true)
        val both = store.freeze()
        assertTrue(both.resultPolicy.allowsShared(tool.toolName))
        store.select(tool, false); store.consentModel(tool, false); store.consentHistory(tool, false)
        assertEquals(setOf(tool), both.platformAllowed); assertTrue(both.resultPolicy.allowsShared(tool.toolName))
        assertFalse(selected.modelConsent(tool)); assertFalse(modelOnly.resultPolicy.allowsShared(tool.toolName))
        assertTrue(store.freeze().platformAllowed.isEmpty()); assertFalse(store.freeze().resultPolicy.allowsShared(tool.toolName))
        try { (both.platformAllowed as MutableSet<PlatformReadTool>).clear(); fail("mutable run selection") } catch (_: UnsupportedOperationException) { }
    }

    @Test fun allThirteenSelectionsRoundTripWithoutGrantingAnySiblingOrDestination() {
        val store = PlatformToolSettings(preferences)
        assertEquals(13, PlatformReadTool.entries.size)
        for (tool in PlatformReadTool.entries) {
            store.select(tool, true)
            val frozen = PlatformToolSettings(preferences).freeze()
            assertEquals(setOf(tool), frozen.platformAllowed)
            assertTrue(PlatformReadTool.entries.none { frozen.modelConsent(it) || frozen.historyConsent(it) })
            assertEquals(setOf(tool.capability), AndroidToolRegistry.capabilitiesFor(emptySet(), frozen.platformAllowed))
            store.select(tool, false)
            assertTrue(store.freeze().platformAllowed.isEmpty())
        }
    }

    @Test fun historyOnlyAndUnknownStoredNamesAreFailClosed() {
        preferences.edit().putStringSet("selected-v1", setOf("unknown", PlatformReadTool.CONTACTS.toolName))
            .putStringSet("private-model-v1", setOf("unknown", PlatformReadTool.TIME.toolName))
            .putStringSet("private-history-v1", setOf(PlatformReadTool.CONTACTS.toolName)).commit()
        val frozen = PlatformToolSettings(preferences).freeze()
        assertEquals(setOf(PlatformReadTool.CONTACTS), frozen.platformAllowed)
        assertFalse(frozen.modelConsent(PlatformReadTool.TIME))
        assertTrue(frozen.historyConsent(PlatformReadTool.CONTACTS))
        assertFalse(frozen.resultPolicy.allowsShared(PlatformReadTool.CONTACTS.toolName))
        preferences.edit().putString("selected-v1", "corrupt-type").commit()
        assertTrue(PlatformToolSettings(preferences).freeze().platformAllowed.isEmpty())
    }

    @Test fun runtimeRequestsAreExplicitVersionGatedAndNeverRequestBroadPermissions() {
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), PlatformSettingsAccess.runtimePermissions(PlatformReadTool.MEDIA, 26))
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), PlatformSettingsAccess.runtimePermissions(PlatformReadTool.MEDIA, 32))
        val api33 = PlatformSettingsAccess.runtimePermissions(PlatformReadTool.MEDIA, 33)
        assertEquals(listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO), api33)
        assertFalse(api33.contains(Manifest.permission.READ_EXTERNAL_STORAGE))
        assertTrue(PlatformSettingsAccess.runtimePermissions(PlatformReadTool.MEDIA, 34).contains(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            PlatformSettingsAccess.permissionRequest(PlatformReadTool.MEDIA, Manifest.permission.READ_MEDIA_IMAGES, 34))
        assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_AUDIO),
            PlatformSettingsAccess.permissionRequest(PlatformReadTool.MEDIA, Manifest.permission.READ_MEDIA_AUDIO, 34))
        assertTrue(PlatformSettingsAccess.runtimePermissions(PlatformReadTool.NETWORK, 30).isEmpty())
        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT), PlatformSettingsAccess.runtimePermissions(PlatformReadTool.NETWORK, 31))
        assertTrue(PlatformSettingsAccess.runtimePermissions(PlatformReadTool.NOTIFICATIONS, 37).isEmpty())
        assertTrue(PlatformSettingsAccess.runtimePermissions(PlatformReadTool.FILES, 37).isEmpty())
        try { PlatformSettingsAccess.permissionRequest(PlatformReadTool.MEDIA, Manifest.permission.READ_EXTERNAL_STORAGE, 33); fail() }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun safPickerAndResultRequireExactContentPersistentReadWithoutTakingWrite() {
        for (tree in listOf(true, false)) {
            val picker = PlatformSettingsAccess.pickerIntent(tree)
            assertEquals(if (tree) Intent.ACTION_OPEN_DOCUMENT_TREE else Intent.ACTION_OPEN_DOCUMENT, picker.action)
            assertEquals(0, picker.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val uri = Uri.parse(if (tree) "content://fixture/tree/selected" else "content://fixture/document/selected")
            val result = result(uri)
            assertEquals(uri, PlatformSettingsAccess.acceptedUri(result, tree))
            assertNull(PlatformSettingsAccess.acceptedUri(result, !tree))
            result.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            assertNull(PlatformSettingsAccess.acceptedUri(result, tree))
        }
        for (text in listOf("file:///sdcard/private", "https://fixture/document/x", "content://user@fixture/document/x", "content://fixture/document/x#fragment"))
            assertNull(PlatformSettingsAccess.acceptedUri(result(Uri.parse(text)), false))
    }

    @Test fun safOnlyPersistsExactReadGrantAndRevocationAndSystemLossStayNarrow() {
        val tree = Uri.parse("content://fixture/tree/selected")
        val child = Uri.parse("content://fixture/tree/selected/document/child")
        assertNull(PlatformSettingsAccess.acceptedUri(result(child), true))
        val unrelated = Uri.parse("content://fixture/document/unrelated")
        val persisted = mutableListOf(unrelated)
        val taken = mutableListOf<Uri>(); val released = mutableListOf<Uri>()
        fun store() = PlatformSafGrants(preferences, { persisted.toList() },
            { taken.add(it); persisted.add(it) }, { released.add(it); persisted.remove(it) })
        assertTrue(store().grants().isEmpty())
        assertTrue(store().accept(result(tree), true))
        assertEquals(listOf(tree), taken); assertEquals(listOf(tree), store().grants())
        assertFalse(store().grants().contains(child)); assertFalse(store().grants().contains(unrelated))
        store().revoke(tree)
        assertEquals(listOf(tree), released); assertTrue(store().grants().isEmpty()); assertEquals(listOf(unrelated), persisted)
        assertTrue(store().accept(result(tree), true)); persisted.remove(tree)
        assertTrue(store().grants().isEmpty())
        val refused = PlatformSafGrants(preferences, { emptyList() }, { throw SecurityException("synthetic") }, { fail() })
        assertFalse(refused.accept(result(tree), true)); assertTrue(refused.grants().isEmpty())
    }

    private fun result(uri: Uri) = Intent().setData(uri).addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}
