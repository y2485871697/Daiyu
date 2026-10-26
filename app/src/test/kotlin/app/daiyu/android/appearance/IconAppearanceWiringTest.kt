package app.daiyu.android.appearance

import app.daiyu.android.domain.appearance.Task17IconCatalog
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

/** Production-file contracts, not a second manifest/catalog. No PM writes or build inputs needed. */
class IconAppearanceWiringTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { File(it, "app/src/main/AndroidManifest.xml").isFile }
    private val android = "http://schemas.android.com/apk/res/android"
    private fun Element.attr(name: String): String = getAttributeNS(android, name)
    private fun Element.elements(tag: String): List<Element> = getElementsByTagName(tag).let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }
    }
    private fun application(): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return factory.newDocumentBuilder().parse(File(root, "app/src/main/AndroidManifest.xml"))
            .documentElement.elements("application").single()
    }

    @Test fun productionManifestHasExactlyCatalogAliasesAndOneDefaultEntrance() {
        val app = application()
        val aliases = app.elements("activity-alias")
        val pkg = "app.daiyu.android"
        val entries = Task17IconCatalog.entries.associateBy { it.componentName(pkg) }
        assertEquals(64, aliases.size)
        assertEquals(entries.keys, aliases.map { it.attr("name") }.toSet())
        assertEquals(listOf(Task17IconCatalog.defaultEntry.componentName(pkg)),
            aliases.filter { it.attr("enabled") == "true" }.map { it.attr("name") })
        aliases.forEach { alias ->
            val entry = entries.getValue(alias.attr("name"))
            assertEquals("$pkg.MainActivity", alias.attr("targetActivity"))
            assertEquals("true", alias.attr("exported"))
            assertEquals((entry == Task17IconCatalog.defaultEntry).toString(), alias.attr("enabled"))
            assertEquals("@drawable/${entry.resourceName}", alias.attr("icon"))
            assertEquals(alias.attr("icon"), alias.attr("roundIcon"))
            val filters = alias.elements("intent-filter")
            assertEquals(1, filters.size)
            assertEquals(listOf("android.intent.action.MAIN"), filters.single().elements("action").map { it.attr("name") })
            assertEquals(listOf("android.intent.category.LAUNCHER"), filters.single().elements("category").map { it.attr("name") })
        }
        assertEquals("@drawable/${Task17IconCatalog.defaultEntry.resourceName}", app.attr("icon"))
        assertEquals(app.attr("icon"), app.attr("roundIcon"))
    }

    @Test fun targetRemainsEnabledAndDocumentGrantIntentHasOnlyJsonAndZipWithoutDuplicateLauncher() {
        val app = application()
        val target = app.elements("activity").single { it.attr("name") in listOf(".MainActivity", "app.daiyu.android.MainActivity") }
        assertNotEquals("false", target.attr("enabled"))
        assertEquals("true", target.attr("exported"))
        assertEquals("singleTop", target.attr("launchMode"))
        assertTrue(app.elements("activity").none { activity ->
            activity.elements("category").any { it.attr("name") == "android.intent.category.LAUNCHER" }
        })
        val filter = target.elements("intent-filter").single()
        assertEquals(listOf("android.intent.action.VIEW"), filter.elements("action").map { it.attr("name") })
        assertEquals(listOf("android.intent.category.DEFAULT"), filter.elements("category").map { it.attr("name") })
        val data = filter.elements("data")
        assertEquals(2, data.size)
        assertEquals(
            setOf("content" to "application/json", "content" to "application/zip"),
            data.map { it.attr("scheme") to it.attr("mimeType") }.toSet(),
        )
        assertEquals("false", app.attr("allowBackup"))
        assertEquals("false", app.attr("usesCleartextTraffic"))
    }

    @Test fun all66ProductionAssetsArePngAndOriginalCatMarksRetainKnownDigests() {
        val resources = File(root, "app/src/main/res")
        val files = Task17IconCatalog.entries.map { File(resources, "drawable-nodpi/${it.resourceName}.png") } +
            listOf(File(resources, "drawable-nodpi/task17_ic_cat_mark.png"), File(resources, "drawable/task17_ic_stat_cat.png"))
        assertEquals(66, files.size)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        files.forEach { file ->
            assertTrue(file.path, file.isFile)
            val bytes = file.readBytes()
            assertTrue(file.path, bytes.size > 24)
            assertArrayEquals(file.path, png, bytes.copyOfRange(0, png.size))
        }
        fun digest(path: String): String = MessageDigest.getInstance("SHA-256")
            .digest(File(resources, path).readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        // Digests recorded by the retained 5ac9b270 source's integration notes; no redrawn marks.
        assertEquals("b8099ee06dcbdc52a78bc9c746d4d358a0bb324c796ae2e3b96d5252932f08b5",
            digest("drawable-nodpi/task17_ic_cat_mark.png"))
        assertEquals("7d435ba5e900bc6bbb41906145e22e1ea89f76d69101173c5731f076c1914970",
            digest("drawable/task17_ic_stat_cat.png"))
    }
}
