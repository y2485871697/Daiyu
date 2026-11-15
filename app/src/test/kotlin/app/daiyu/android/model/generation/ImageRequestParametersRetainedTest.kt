package app.daiyu.android.model.generation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImageRequestParametersRetainedTest {
    private val empty = ImageGenerationOptions()
    @Test fun directDirectiveIsSharedAndNotSentAsPrompt() {
        val p = ImagePromptOptions.parse("画猫\nimage_options: {\"aspect_ratio\":\"9:16\",\"resolution\":\"2k\"}")
        assertEquals("画猫", p.prompt)
        val r = ImageRequestParameters.prepare(JSONObject(), p.options, empty)
        assertEquals("1152x2048", r.body.getString("size"))
        assertFalse(r.body.has("aspect_ratio")); assertFalse(r.body.has("resolution"))
    }
    @Test fun proseTimesExamplesAndNegationsAreNotGuessed() {
        for (text in listOf("9:16 开会", "时钟显示9:16", "文字写着1024x1024")) {
            assertTrue(ImagePromptOptions.parse(text).options.isEmpty)
        }
    }
    @Test fun unambiguousShapeClausesAreSharedWithDirectChat() {
        for (prompt in listOf("画猫，9:16，2k", "画猫；比例9:16；分辨率2k", "画猫\n9:16\n2K")) {
            val result = ImagePromptOptions.parse(prompt)
            assertEquals("9:16", result.options.aspectRatio)
            assertEquals("high", result.options.resolution)
        }
    }
    @Test fun negatedOrExampleGeometryDoesNotSilentlyRevertToDefaults() {
        assertEquals("9:16", ImagePromptOptions.parse("不要方图，9:16，2k").options.aspectRatio)
        assertThrows(ImageGenerationParameterException::class.java) { ImagePromptOptions.parse("不要2k方图") }
        assertThrows(ImageGenerationParameterException::class.java) { ImagePromptOptions.parse("例如，9:16，2k") }
    }
    @Test fun conflictingShapeClausesFailRatherThanChoosingOne() {
        assertThrows(ImageGenerationParameterException::class.java) { ImagePromptOptions.parse("画猫，9:16，1:1") }
        assertThrows(ImageGenerationParameterException::class.java) { ImagePromptOptions.parse("画猫，9:16，尺寸1024x1024") }
    }
    @Test fun duplicateOrFencedDirectiveFails() {
        for (text in listOf("image_options: {}\nimage_options: {}", "```\nimage_options: {}\n```", "image_options: nope")) {
            assertThrows(ImageGenerationParameterException::class.java) { ImagePromptOptions.parse(text) }
        }
    }
    @Test fun explicitWinsOverInlineAndDefaultsWithoutModifyingOtherFields() {
        val r = ImageRequestParameters.prepare(JSONObject("""{"size":"1024x1024","seed":42}"""),
            ImageGenerationOptions(aspectRatio = "1:1", resolution = "1k"),
            ImageGenerationOptions(aspectRatio = "9:16", resolution = "2k"))
        assertEquals("9:16", r.options.aspectRatio)
        assertEquals("high", r.options.resolution)
        assertEquals("1152x2048", r.body.getString("size"))
        assertEquals(42, r.body.getInt("seed"))
    }
    @Test fun nestedFieldsAndMappedDefaultsWorkWithoutLeakingPrivateConfig() {
        val body = JSONObject("""{"prompt":"secret prompt","generationConfig":{"imageConfig":{"aspectRatio":"1:1","other":true}},"daiyu_image_config":{"fields":{"aspect_ratio":"generationConfig.imageConfig.aspectRatio","resolution":"generationConfig.imageConfig.imageSize"},"values":{"resolution":{"high":"2k"}}}}""")
        val r = ImageRequestParameters.prepare(body, empty, ImageGenerationOptions(aspectRatio = "9:16", resolution = "2k"))
        val image = r.body.getJSONObject("generationConfig").getJSONObject("imageConfig")
        assertEquals("9:16", image.getString("aspectRatio"))
        assertEquals("2k", image.getString("imageSize"))
        assertTrue(image.getBoolean("other"))
        assertFalse(r.body.has("daiyu_image_config")); assertFalse(r.body.has("aspect_ratio"))
        assertFalse(r.summary.contains("secret prompt"))
    }
    @Test fun sizeConversionRequiresExplicitExactMappingIncludingTier() {
        val body = JSONObject("""{"daiyu_image_config":{"protocol":"size","sizes":{"9:16@high":"1152x2048"}}}""")
        val r = ImageRequestParameters.prepare(body, empty, ImageGenerationOptions(aspectRatio = "9:16", resolution = "2k"))
        assertEquals("1152x2048", r.body.getString("size"))
        assertFalse(r.body.has("aspect_ratio")); assertFalse(r.body.has("resolution"))
        assertTrue(r.options.dimensionReport(1024, 1024).contains("MISMATCH"))
    }
    @Test fun approximateOrMissingMappingFails() {
        for (sizes in listOf("{}", """{"9:16":"1024x1536"}""")) {
            assertThrows(ImageGenerationParameterException::class.java) {
                ImageRequestParameters.prepare(JSONObject("""{"daiyu_image_config":{"protocol":"size","sizes":$sizes}}"""),
                    empty, ImageGenerationOptions(aspectRatio = "9:16"))
            }
        }
    }
    @Test fun protectedAndCollidingPathsFail() {
        for (fields in listOf("""{"aspect_ratio":"prompt"}""", """{"aspect_ratio":"foo","size":"foo.bar"}""")) {
            assertThrows(ImageGenerationParameterException::class.java) {
                ImageRequestParameters.prepare(JSONObject("""{"daiyu_image_config":{"fields":$fields}}"""), empty, empty)
            }
        }
    }

    @Test fun defaultUsesSizeWithoutChangingSavedBody() {
        val body = JSONObject("""{"model":"anything","prompt":"cat","n":1}""")
        val r = ImageRequestParameters.prepare(body, empty, ImageGenerationOptions(aspectRatio="1:2", resolution="high"))
        assertEquals("1024x2048", r.body.getString("size"))
        assertEquals(setOf("model","prompt","n","size"), r.body.keys().asSequence().toSet())
        assertFalse(body.has("size"))
    }
    @Test fun explicitNativePassthroughKeepsRatioAndResolutionOnly() {
        val r = ImageRequestParameters.prepare(JSONObject("""{"daiyu_image_config":{"protocol":"passthrough","values":{"resolution":{"high":"2K"}}}}"""),
            empty, ImageGenerationOptions(aspectRatio="9:16", resolution="high"))
        assertEquals("9:16", r.body.getString("aspect_ratio"))
        assertEquals("2K", r.body.getString("resolution")); assertFalse(r.body.has("size"))
    }
    @Test fun ratioAndTierMappingHandlesSizeAliasAndCapitalization() {
        val body=JSONObject("""{"size":"1K","ratio":"1:1","daiyu_image_config":{"fields":{"aspect_ratio":"ratio","resolution":"size"},"values":{"resolution":{"low":"1K","high":"2K"}}}}""")
        val r=ImageRequestParameters.prepare(body, empty, ImageGenerationOptions(aspectRatio="9:16",resolution="high"))
        assertEquals(setOf("size","ratio"), r.body.keys().asSequence().toSet())
        assertEquals("2K",r.body.getString("size")); assertEquals("9:16",r.body.getString("ratio"))
        assertEquals("1K",body.getString("size"))
    }
    @Test fun mappingNeverDropsAnUnconfiguredValueOrOverwritesAnotherActiveField() {
        for (config in listOf(
            """{"protocol":"passthrough","values":{"resolution":{"low":"1K"}}}""",
            """{"protocol":"passthrough","fields":{"aspect_ratio":"same","resolution":"same"}}""")) {
            assertThrows(ImageGenerationParameterException::class.java) {
                ImageRequestParameters.prepare(JSONObject().put("daiyu_image_config",JSONObject(config)),empty,
                    ImageGenerationOptions(aspectRatio="9:16",resolution="high"))
            }
        }
    }
    @Test fun malformedProtocolAndNativeOptionsFailLocally() {
        for (config in listOf("""{"protocol":1}""", """{"endpoint":"chat"}""", """{"endpoint":"anthropic"}""",
            """{"endpoint":"novelai_native","protocol":"passthrough"}""", """{"native_parameters":{"seed":1}}""")) {
            assertThrows(ImageGenerationParameterException::class.java) {
                ImageRequestParameters.prepare(JSONObject().put("daiyu_image_config",JSONObject(config)),empty,empty)
            }
        }
    }

    @Test fun unconfiguredAliasCannotBeBlindlySentAlongsideSize() {
        assertThrows(ImageGenerationParameterException::class.java) {
            ImageRequestParameters.prepare(JSONObject("""{"ratio":"1:1"}"""),empty,
                ImageGenerationOptions(aspectRatio="9:16",resolution="high"))
        }
    }
    @Test fun localConcurrencyCannotBeReintroducedByFieldMapping() {
        for(path in listOf("concurrency","params.concurrency")) {
            val body=JSONObject().put("daiyu_image_config",JSONObject().put("fields",JSONObject().put("n",path)))
            assertThrows(ImageGenerationParameterException::class.java) {
                ImageRequestParameters.prepare(body,ImageGenerationOptions(count=3,concurrency=2),empty)
            }
        }
    }

}
