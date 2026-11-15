package app.daiyu.android.model.generation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImageGenerationOptionsRetainedTest {
    @Test fun allModelNamesUseSameForwardingPolicy() {
        listOf("agnes-image-2.5-flash", "grok-imagine-image-2.0", "gpt-image-1", "custom-alias").forEach { model ->
            val body = JSONObject("""{"size":"1024x1024","vendor_extra":true}""")
            ImageGenerationOptions(aspectRatio = "9:16", resolution = "high", quality = "ultra").applyTo(body, model)
            assertEquals("9:16", body.getString("aspect_ratio"))
            assertEquals("high", body.getString("resolution"))
            assertFalse(body.has("size"))
            assertTrue(body.getBoolean("vendor_extra"))
        }
    }
    @Test fun customPositiveRatioAndTierAreNotAClosedModelList() {
        val options = ImageGenerationOptions.fromJson(JSONObject("""{"aspect_ratio":"7:5","resolution":"4K"}"""))
        assertEquals("7:5", options.aspectRatio)
        assertEquals("ultra", options.resolution)
    }
    @Test fun invalidFormatsStillFailBeforeNetwork() {
        listOf("""{"n":1.5}""", """{"n":11}""", """{"aspect_ratio":"0:0"}""",
            """{"size":"0x0"}""", """{"resolution":null}""", """{"prompt":"override"}""").forEach { json ->
            assertThrows(ImageGenerationParameterException::class.java) { ImageGenerationOptions.fromJson(JSONObject(json)) }
        }
    }
    @Test fun explicitConflictsFail() {
        assertThrows(ImageGenerationParameterException::class.java) {
            ImageGenerationOptions(aspectRatio = "9:16", size = "1024x1024").applyTo(JSONObject())
        }
        assertThrows(ImageGenerationParameterException::class.java) {
            ImageGenerationOptions(aspectRatio = "9:16", size = "auto").applyTo(JSONObject())
        }
    }
    @Test fun actualDimensionsExposeIgnoredAspectAndResolution() {
        val options = ImageGenerationOptions(aspectRatio = "9:16", resolution = "high")
        assertTrue(options.dimensionReport(1024, 1024).contains("IMAGE_DIMENSIONS_MISMATCH"))
        assertFalse(options.dimensionReport(1152, 2048).contains("MISMATCH"))
        assertTrue(options.dimensionReport(-1, -1).contains("IMAGE_DIMENSIONS_UNVERIFIED"))
    }
}
