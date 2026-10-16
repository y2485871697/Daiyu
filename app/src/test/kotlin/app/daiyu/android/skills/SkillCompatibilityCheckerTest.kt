package app.daiyu.android.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** First three cases retained from the supplied saved test; see source handoff. */
class SkillCompatibilityCheckerTest {
    @Test
    fun rejectsMinisAndroidCli() {
        val result = SkillCompatibilityChecker.evaluate(
            id = "android-ui-automation",
            description = "Automate apps with Accessibility.",
            compatibility = "requires android-a11y-cli / android-open",
        )
        assertFalse(result.available)
        assertEquals("依赖 MiniS 专属 Android CLI，Eta 无法运行", result.reason)
    }

    @Test
    fun rejectsHealthKitAndIosOnly() {
        assertFalse(
            SkillCompatibilityChecker.evaluate(
                id = "health-sleep-analysis",
                description = "Analyze Apple HealthKit sleep stages.",
            ).available,
        )
        assertFalse(
            SkillCompatibilityChecker.evaluate(
                id = "shortcut-share-file",
                description = "Share iCloud Drive files using the iOS Shortcut Instant Share.",
            ).available,
        )
    }

    @Test
    fun allowsHubSkillsAndAndroidPdfPipeline() {
        assertTrue(
            SkillCompatibilityChecker.evaluate(
                id = "bilibili-hub",
                description = "Obtain cookies through browser_use get_cookies for Bilibili.",
            ).available,
        )
        assertTrue(
            SkillCompatibilityChecker.evaluate(
                id = "pdf-converter",
                description = "Convert documents to PDF.",
                compatibility = "Python 3; Alpine (iSH) / Termux (Android) / Debian",
            ).available,
        )
    }

    @Test
    fun preservesOriginalMarkersAndPriorityWithoutOrdinaryToolBlacklist() {
        for (marker in listOf("apple-tools", "HomeKit", "HealthKit", "Apple Health", "Apple Reminders", "iOS Shortcut", "iCloud Drive")) {
            assertEquals(marker, "不支持 Apple 专属运行时", SkillCompatibilityChecker.evaluate("demo", description = marker).reason)
        }
        for (marker in listOf("android-a11y-cli", "MiniS Accessibility Service", "bundled android-a11y-cli", "android-open CLIs", "/usr/local/bin/android-open")) {
            assertEquals(marker, "依赖 MiniS 专属 Android CLI，Eta 无法运行", SkillCompatibilityChecker.evaluate("demo", description = marker).reason)
        }
        for (marker in listOf("codex-on-ish", "inside the MiniS/iSH", "iSH Alpine", "generative-ui-minis", "MiniS iOS render", "App Store Connect CLI")) {
            assertEquals(marker, "依赖 MiniS / iSH 的 iOS 沙箱，Eta 无法运行", SkillCompatibilityChecker.evaluate("demo", description = marker).reason)
        }
        assertEquals("不支持 Apple 专属运行时", SkillCompatibilityChecker.evaluate("demo", description = "HomeKit android-a11y-cli").reason)
        assertEquals("该 Skill 标注为 iOS 专属", SkillCompatibilityChecker.evaluate("demo", description = "For iPhone and iPad").reason)
        assertTrue(SkillCompatibilityChecker.evaluate("demo", description = "iOS and Android instructions").available)
        assertTrue(SkillCompatibilityChecker.evaluate("demo", description = "bios and radios").available)
        assertTrue(SkillCompatibilityChecker.evaluate("demo", description = "Use browser_use, get_cookies, shell, Python, curl, ffmpeg, adb, android-open, accessibility and a CLI.").available)
    }

    @Test
    fun thinAdapterKeepsCompatibilityMetadataAndBodyInputs() {
        fun check(extra: String, body: String = "Portable instructions") = SkillCompatibilityPolicy.evaluate(
            SkillFrontmatter.parse("---\nname: demo\ndescription: Portable instructions\n${extra}---\n$body"),
            "---\nname: demo\ndescription: Portable instructions\n${extra}---\n$body",
        )
        assertFalse(check("compatibility: HomeKit\n").available)
        assertFalse(check("metadata:\n  runtime: android-a11y-cli\n").available)
        assertFalse(check("", "Requires codex-on-ish").available)
        assertTrue(check("").available)
        assertTrue(check("compatibility: >\n  Python 3; Alpine (iSH) / Termux (Android) / Debian\n").available)
        // Only values are inputs in the retained algorithm, not arbitrary frontmatter keys.
        assertTrue(check("metadata:\n  homekit: ordinary text\n").available)
        assertFalse(SkillCompatibilityChecker.evaluate(SkillHeader("apple-demo", "Portable")).available)
        assertFalse(SkillCompatibilityChecker.evaluate("demo", name = "HomeKit", description = "Portable").available)
    }
}
