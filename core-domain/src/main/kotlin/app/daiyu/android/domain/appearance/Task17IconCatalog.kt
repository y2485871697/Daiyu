package app.daiyu.android.domain.appearance

import java.util.Locale

/** Palette/order and naming ported from user source 3196270 LauncherIconSync.kt. */
object Task17IconCatalog {
    val swatches: List<Int> = listOf(
        0xFFF27A1A.toInt(), 0xFF7B61FF.toInt(), 0xFFF26D9A.toInt(), 0xFF3DDC84.toInt(),
        0xFFF5C542.toInt(), 0xFF2491FF.toInt(), 0xFFF6F7F9.toInt(), 0xFF1C1C1E.toInt(),
    )

    data class Entry(val background: Int, val cat: Int) {
        private val pair = String.format(Locale.ROOT, "%06x_%06x", background and 0xFFFFFF, cat and 0xFFFFFF)
        val sourceResourceName: String = "launcher_$pair"
        val resourceName: String = "task17_$sourceResourceName"
        val componentSuffix: String = ".icon.L_${pair.uppercase(Locale.ROOT)}"
        fun componentName(packageName: String): String = packageName + componentSuffix
    }

    val entries: List<Entry> = swatches.flatMap { background -> swatches.map { Entry(background, it) } }
    // Same default enabled alias as source, not a guessed appearance preference default.
    val defaultEntry: Entry = entry(0xFFF6F7F9.toInt(), 0xFF2491FF.toInt())

    fun nearest(color: Int): Int {
        val rgb = color and 0xFFFFFF
        return swatches.minBy { swatch ->
            val other = swatch and 0xFFFFFF
            val dr = ((rgb shr 16) and 0xFF) - ((other shr 16) and 0xFF)
            val dg = ((rgb shr 8) and 0xFF) - ((other shr 8) and 0xFF)
            val db = (rgb and 0xFF) - (other and 0xFF)
            dr * dr + dg * dg + db * db
        }
    }

    fun entry(background: Int, cat: Int): Entry {
        val bg = nearest(background)
        val ink = nearest(cat)
        return entries.first { it.background == bg && it.cat == ink }
    }

    fun fromResourceName(name: String?): Entry? = entries.firstOrNull {
        it.resourceName == name || it.sourceResourceName == name
    }
}

/** One source of truth for component declarations and resource generation; no hand-maintained mapping. */
object Task17IconManifest {
    fun fragment(packageName: String, targetActivity: String = "$packageName.MainActivity"): String {
        require(packageName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")))
        require(targetActivity.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")))
        return Task17IconCatalog.entries.joinToString("\n") { entry ->
            """<activity-alias android:name="${entry.componentName(packageName)}" android:targetActivity="$targetActivity" android:enabled="${entry == Task17IconCatalog.defaultEntry}" android:exported="true" android:icon="@drawable/${entry.resourceName}" android:roundIcon="@drawable/${entry.resourceName}">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity-alias>"""
        }
    }
}
