package app.daiyu.android.ui.screens.settings

import android.test.InstrumentationTestCase
import app.daiyu.android.terminal.LinuxExecutionMode

class LinuxInstallChoicePreferencesTest : InstrumentationTestCase() {
    private val name = "linux-install-choice-test-${java.util.UUID.randomUUID()}"
    private val preferences get() = instrumentation.targetContext.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)

    fun testAllPairsRoundTripAcrossNewStoreInstances() {
        assertNull(LinuxInstallChoicePreferences(preferences).read())
        for (distribution in listOf("alpine", "debian")) for (mode in LinuxExecutionMode.entries) {
            val choice = LinuxInstallChoice(distribution, mode)
            assertTrue(LinuxInstallChoicePreferences(preferences).save(choice))
            assertEquals(choice, LinuxInstallChoicePreferences(preferences).read())
        }
    }

    fun testMalformedPreferenceDoesNotBecomeRuntimeSelectionOrWriteOnRead() {
        for (raw in listOf("", "ubuntu:PROOT", "debian:ROOT", "debian:CHROOT:extra", "alpine")) {
            assertTrue(preferences.edit().putString("install_choice", raw).commit())
            assertNull(LinuxInstallChoicePreferences(preferences).read())
            assertEquals(raw, preferences.getString("install_choice", null))
        }
    }

    fun testCommitFailureAfterMemoryUpdateRestoresPreviousPairForNewStore() {
        val saved = LinuxInstallChoice("alpine", LinuxExecutionMode.PROOT)
        assertTrue(LinuxInstallChoicePreferences(preferences).save(saved))
        val failing = object : android.content.SharedPreferences by preferences {
            override fun edit(): android.content.SharedPreferences.Editor {
                val real = preferences.edit()
                return object : android.content.SharedPreferences.Editor by real {
                    override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                        real.putString(key, value); return this
                    }
                    override fun commit(): Boolean { real.commit(); return false }
                }
            }
        }
        assertFalse(LinuxInstallChoicePreferences(failing).save(LinuxInstallChoice("debian", LinuxExecutionMode.CHROOT)))
        assertEquals(saved, LinuxInstallChoicePreferences(preferences).read())
    }

    fun testUnsupportedDistributionCannotOverwriteSavedPair() {
        val saved = LinuxInstallChoice("debian", LinuxExecutionMode.CHROOT)
        val store = LinuxInstallChoicePreferences(preferences)
        assertTrue(store.save(saved))
        assertFalse(store.save(LinuxInstallChoice("ubuntu", LinuxExecutionMode.PROOT)))
        assertEquals(saved, LinuxInstallChoicePreferences(preferences).read())
    }

    override fun tearDown() {
        try { preferences.edit().clear().commit() } finally { super.tearDown() }
    }
}
