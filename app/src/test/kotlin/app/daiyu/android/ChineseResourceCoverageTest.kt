package app.daiyu.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** These defaults are already Chinese; the zh variants must preserve their complete payloads. */
class ChineseResourceCoverageTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { File(it, "app/src/main/AndroidManifest.xml").isFile }
    private val resources = File(root, "app/src/main/res")
    private val files = mapOf(
        "gui_strings.xml" to 2,
        "image_attachments.xml" to 24,
        "strings_shizuku.xml" to 35,
        "voice_host.xml" to 15,
    )

    private fun strings(directory: String, filename: String): Map<String, String> {
        val file = File(resources, "$directory/$filename")
        assertTrue("Missing resource file: $file", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(file).documentElement
        assertEquals(file.path, "resources", document.tagName)
        val nodes = document.getElementsByTagName("string")
        val result = linkedMapOf<String, String>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as Element
            val name = element.getAttribute("name")
            assertTrue("Unnamed string in $file", name.isNotBlank())
            assertFalse("Duplicate $name in $file", result.containsKey(name))
            assertFalse("User-facing $name must remain translatable", element.getAttribute("translatable") == "false")
            assertTrue("Empty $name in $file", element.textContent.isNotBlank())
            result[name] = element.textContent
        }
        return result
    }

    @Test fun all76ChineseStringsHaveMatchingLocalizedKeysAndPayloads() {
        files.forEach { (filename, expectedCount) ->
            val defaults = strings("values", filename)
            val localized = strings("values-zh", filename)
            assertEquals(filename, expectedCount, defaults.size)
            assertEquals("Localized keys in $filename", defaults.keys, localized.keys)
            defaults.forEach { (name, text) ->
                // Full text equality also protects numbered placeholders and literal Android escapes (e.g. \\n).
                assertEquals("Chinese payload for $name", text, localized.getValue(name))
            }
        }
    }
}
