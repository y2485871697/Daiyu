package app.daiyu.android.platform

import android.content.pm.ApplicationInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class AppLabelCacheTest {
    @Test fun labelReuseExpiresAndTracksPackageResourcesAndConfiguration() {
        var now = 0L
        var calls = 0
        val cache = AppLabelCache { now }
        val info = ApplicationInfo().apply { packageName = "package"; sourceDir = "/one"; labelRes = 1 }
        fun label(configuration: String = "en") = cache.label(info, configuration) { "label-${++calls}" }
        assertEquals("label-1", label())
        assertEquals("label-1", label())
        now = 60_000
        assertEquals("label-2", label())
        info.sourceDir = "/two"
        assertEquals("label-3", label())
        assertEquals("label-4", label("zh"))
        info.labelRes = 2
        assertEquals("label-5", label("zh"))
        info.nonLocalizedLabel = "new"
        assertEquals("label-6", label("zh"))
        assertEquals(6, calls)
    }

    @Test fun failedLoadsNeverBecomeLabelsAndEntriesAreBounded() {
        val cache = AppLabelCache { 0L }
        val info = ApplicationInfo().apply { packageName = "first" }
        assertThrows(IllegalStateException::class.java) { cache.label(info, "en") { error("fixture") } }
        assertEquals("first", cache.label(info, "en") { "first" })
        repeat(512) { n ->
            cache.label(ApplicationInfo().apply { packageName = "other-$n" }, "en") { "other" }
        }
        assertEquals("reloaded", cache.label(info, "en") { "reloaded" })
    }
    @Test fun oversizedLabelsStillReturnCompleteValuesWithoutBeingRetained() {
        val cache = AppLabelCache { 0L }
        val info = ApplicationInfo().apply { packageName = "large" }
        val value = "x".repeat(4097)
        var calls = 0
        repeat(2) { assertEquals(value, cache.label(info, "en") { calls++; value }) }
        assertEquals(2, calls)
    }

}
