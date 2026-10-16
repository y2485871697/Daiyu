package app.daiyu.android.domain.appearance

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class Task17IconCatalogTest {
    @Test fun exactSourcePaletteAnd64UniqueMappings() {
        assertEquals(listOf(0xFFF27A1A.toInt(), 0xFF7B61FF.toInt(), 0xFFF26D9A.toInt(), 0xFF3DDC84.toInt(),
            0xFFF5C542.toInt(), 0xFF2491FF.toInt(), 0xFFF6F7F9.toInt(), 0xFF1C1C1E.toInt()), Task17IconCatalog.swatches)
        val entries = Task17IconCatalog.entries
        assertEquals(64, entries.size)
        assertEquals(64, entries.map { it.resourceName }.toSet().size)
        assertEquals(64, entries.map { it.componentName("app.daiyu.android") }.toSet().size)
        entries.forEach { entry ->
            assertEquals(entry, Task17IconCatalog.entry(entry.background, entry.cat))
            assertEquals(entry, Task17IconCatalog.fromResourceName(entry.resourceName))
            assertEquals(entry, Task17IconCatalog.fromResourceName(entry.sourceResourceName))
        }
        assertNull(Task17IconCatalog.fromResourceName("../../other"))
        assertNull(Task17IconCatalog.fromResourceName(null))
    }

    @Test fun defaultsAndNightSelectionExactlyMatchSource() {
        val defaults = IconAppearance()
        assertEquals(IconThemeMode.LIGHT, defaults.themeMode)
        assertEquals(0xFFFFFFFF.toInt(), defaults.iconLightColor)
        assertEquals(0xFF1C1C1E.toInt(), defaults.iconDarkColor)
        assertEquals(0xFF2491FF.toInt(), defaults.iconCatColor)
        assertEquals("launcher_f6f7f9_2491ff", defaults.selectedIcon(false).sourceResourceName)
        assertEquals(defaults.selectedIcon(false), defaults.selectedIcon(true))
        val system = defaults.copy(themeMode = IconThemeMode.SYSTEM)
        assertEquals("launcher_1c1c1e_2491ff", system.selectedIcon(true).sourceResourceName)
        assertEquals(defaults.selectedIcon(false), system.selectedIcon(false))
        assertEquals(system.selectedIcon(true), defaults.copy(themeMode = IconThemeMode.DARK).selectedIcon(false))
        assertEquals(IconThemeMode.LIGHT, IconThemeMode.fromPersistedValue("unknown"))
        IconThemeMode.entries.forEach { assertEquals(it, IconThemeMode.fromPersistedValue(it.persistedValue)) }
        assertEquals(0xFF001122.toInt(), defaults.copy(iconCatColor = 0x00001122).normalized().iconCatColor)
    }

    @Test fun nearestIgnoresAlphaAndHasSourceOrderTieBreak() {
        assertEquals(0xFFF6F7F9.toInt(), Task17IconCatalog.nearest(0x00FFFFFF))
        // Check midpoint candidates against the source RGB distance and palette-order rule.
        val palette = Task17IconCatalog.swatches
        for (a in palette) for (b in palette) {
            val color = ((a and 0xFEFEFE) ushr 1) + ((b and 0xFEFEFE) ushr 1)
            val expected = palette.minBy { swatch ->
                val dr = ((color ushr 16) and 255) - ((swatch ushr 16) and 255)
                val dg = ((color ushr 8) and 255) - ((swatch ushr 8) and 255)
                val db = (color and 255) - (swatch and 255)
                dr * dr + dg * dg + db * db
            }
            assertEquals(expected, Task17IconCatalog.nearest(color))
        }
    }

    @Test fun manifestIsDerived64AliasesOneDefaultAndLocaleIndependent() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val fragment = Task17IconManifest.fragment("app.daiyu.android")
            assertEquals(64, Regex("<activity-alias ").findAll(fragment).count())
            assertEquals(1, Regex("android:enabled=\"true\"").findAll(fragment).count())
            assertEquals(64, Regex("android:exported=\"true\"").findAll(fragment).count())
            Task17IconCatalog.entries.forEach {
                assertTrue(fragment.contains("android:name=\"${it.componentName("app.daiyu.android")}\""))
                assertTrue(fragment.contains("@drawable/${it.resourceName}"))
            }
            assertTrue(fragment.contains("app.daiyu.android.icon.L_F6F7F9_2491FF"))
        } finally { Locale.setDefault(original) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun manifestRejectsXmlInjection() { Task17IconManifest.fragment("app.\"evil") }
}
