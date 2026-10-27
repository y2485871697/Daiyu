package app.daiyu.android.skills

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Only synthetic capability settings. Does not fetch, seed, install or select any skill. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, manifest = Config.NONE)
class SkillCapabilityDefaultMigrationTest {
    private lateinit var context: Context
    private val marker = "product-capability-default-v1"
    private val preferences get() = context.getSharedPreferences("runtime-skills-v1-settings", Context.MODE_PRIVATE)

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        check(preferences.edit().clear().commit())
    }

    @Test fun freshAndLegacyGlobalFalseMigrateOnlyOnceWithoutChangingSelectionOrMetadataKeys() {
        for (legacy in listOf<Boolean?>(null, false, true)) {
            preferences.edit().clear().putStringSet("enabledSkillIds", setOf("user:synthetic"))
                .putBoolean("skill-synthetic-enabled", false).commit()
            if (legacy != null) preferences.edit().putBoolean("enabled", legacy).commit()
            assertTrue(SkillToolRegistry(context).enabled)
            assertTrue(preferences.getBoolean(marker, false))
            assertEquals(setOf("user:synthetic"), preferences.getStringSet("enabledSkillIds", emptySet()))
            assertFalse(preferences.getBoolean("skill-synthetic-enabled", true))
            assertTrue(SkillToolRegistry(context).enabled)
        }
    }

    @Test fun newExplicitFalseSurvivesMultipleInstancesAndReopening() {
        val first = SkillToolRegistry(context)
        assertTrue(first.enabled)
        first.enabled = false
        assertFalse(first.enabled)
        assertFalse(SkillToolRegistry(context).enabled)
        assertTrue(preferences.getBoolean(marker, false))
    }

    @Test fun settingFalseBeforeFirstReadIsAlreadyAnExplicitNewPolicyChoice() {
        SkillToolRegistry(context).enabled = false
        assertFalse(SkillToolRegistry(context).enabled)
    }

    @Test fun malformedToolCallDoesNotTriggerLazySettingsMigrationOrStoreIo() {
        preferences.edit().putBoolean("enabled", false).commit()
        val result = SkillToolRegistry(context).execute("skills_read", JSONObject().put("unknown", "synthetic"))
        assertFalse(result.getBoolean("ok"))
        assertFalse(preferences.contains(marker))
        assertFalse(preferences.getBoolean("enabled", true))
    }
}
