package app.daiyu.android.model.generation

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationParserRetainedTest {
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4,
    )
    private val b64 = Base64.getEncoder().encodeToString(png)

    @Test
    fun parsesOpenAiB64Json() {
        val parsed = ImageGenerationParser.parse(
            """{"data":[{"b64_json":"$b64","revised_prompt":"a cat"}]}""",
        )
        assertEquals(1, parsed.images.size)
        assertEquals(png.toList(), parsed.images.single().bytes!!.toList())
        assertEquals("a cat", parsed.text)
    }

    @Test
    fun parsesUrlAndMarkdown() {
        val parsed = ImageGenerationParser.parse(
            """
            {
              "images":[{"url":"https://cdn.example/a.png"}],
              "choices":[{"message":{"content":"here you go\n![img](https://cdn.example/b.jpg)"}}]
            }
            """.trimIndent(),
        )
        val urls = parsed.images.mapNotNull { it.url }
        assertTrue(urls.contains("https://cdn.example/a.png"))
        assertTrue(urls.contains("https://cdn.example/b.jpg"))
        assertTrue(parsed.text.contains("here you go"))
    }

    @Test
    fun parsesChatContentArrayAndDataUrl() {
        val parsed = ImageGenerationParser.parse(
            """
            {
              "choices":[{
                "message":{
                  "content":[
                    {"type":"text","text":"done"},
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,$b64"}}
                  ]
                }
              }]
            }
            """.trimIndent(),
        )
        assertEquals("done", parsed.text)
        assertEquals(1, parsed.images.size)
        assertEquals(png.toList(), parsed.images.single().bytes!!.toList())
        assertEquals("image/png", parsed.images.single().mimeType)
    }

    @Test
    fun markdownJoinsTextAndLocalPaths() {
        assertEquals(
            "caption\n\n![generated](/tmp/a.png)\n\n![generated](/tmp/b.png)",
            ImageGenerationParser.markdown(listOf("/tmp/a.png", "/tmp/b.png"), "caption"),
        )
        assertEquals("png", ImageGenerationParser.extensionForMime("image/png"))
        assertEquals("jpg", ImageGenerationParser.extensionForMime("image/jpeg"))
    }

    @Test
    fun readsNestedErrorMessage() {
        val message = ImageGenerationParser.errorMessage(
            """{"error":{"message":"model not found","code":"404"}}""",
            404,
        )
        assertEquals("model not found", message)
    }
}
