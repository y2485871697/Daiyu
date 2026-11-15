package app.daiyu.android.model.generation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GrokImageProfileRetainedTest {
    private val empty = ImageGenerationOptions()
    private fun prepare(ratio: String, tier: String) = GrokImageProfile.prepare(JSONObject(), empty,
        ImageGenerationOptions(aspectRatio = ratio, resolution = tier), 0, false)

    @Test fun hostAndModelContractDoesNotLeakToOtherGateways() {
        assertTrue(GrokImageProfile.applies("https://v1.123336.xyz/v1", "grok-imagine-image-2.0", JSONObject()))
        for (url in listOf("https://api.x.ai/v1", "https://v1.123336.xyz.attacker.test/v1", "https://v1.123336.xyz/other", "https://v1.123336.xyz:8443/v1"))
            assertFalse(url, GrokImageProfile.applies(url, "grok-imagine-image-2.0", JSONObject()))
        assertFalse(GrokImageProfile.applies("https://v1.123336.xyz/v1", "another-model", JSONObject()))
        assertFalse(GrokImageProfile.applies("https://v1.123336.xyz/v1", "grok-imagine-image-2.0", JSONObject("""{"daiyu_image_config":{"profile":"generic"}}""")))
        assertThrows(ImageGenerationParameterException::class.java) {
            GrokImageProfile.applies("https://v1.123336.xyz/v1", "grok-imagine-image-2.0", JSONObject("""{"daiyu_image_config":1}"""))
        }
    }
    @Test fun measuredSizeWireAndMeasuredOutputAreDifferentContracts() {
        val plan = prepare("9:16", "high")
        assertEquals("1152x2048", plan.body.getString("size"))
        assertEquals("1584x2816", plan.expectedSize)
        assertNull(plan.options.size)
        assertFalse(plan.body.has("resolution")); assertFalse(plan.body.has("aspect_ratio"))
        assertFalse(plan.options.dimensionReport(1584,2816,plan.expectedSize).contains("MISMATCH"))
        assertTrue(plan.options.dimensionReport(720,1280,plan.expectedSize).contains("MISMATCH"))
        assertEquals("high", plan.options.resolution)
    }
    @Test fun unsupportedTiersRatiosAndEditingFailBeforeRequest() {
        for (tier in listOf("medium", "ultra", "1.5k", "4k")) assertThrows(ImageGenerationParameterException::class.java) { prepare("9:16", tier) }
        for (ratio in listOf("21:9", "1:2", "auto")) assertThrows(ImageGenerationParameterException::class.java) { prepare(ratio, "high") }
        assertThrows(ImageGenerationParameterException::class.java) { prepare("4:3", "low") }
        assertThrows(ImageGenerationParameterException::class.java) { GrokImageProfile.prepare(JSONObject(),empty,empty,1,false) }
    }
    @Test fun explicitPixelsRemainExactAndDoNotPretendReturnedUpscaleMatches() {
        val p=GrokImageProfile.prepare(JSONObject(),empty,ImageGenerationOptions(size="1152x2048"),0,false)
        assertTrue(p.options.dimensionReport(1584,2816,p.expectedSize).contains("MISMATCH"))
        assertThrows(ImageGenerationParameterException::class.java) {
            GrokImageProfile.prepare(JSONObject(),empty,ImageGenerationOptions(size="4096x4096"),0,false)
        }
    }
    @Test fun chineseAndEnglishTiersNormalizeButLiteralWordsDoNot() {
        for ((text,value) in listOf("低" to "low","中" to "medium","高" to "high","超高" to "ultra")) {
            assertEquals(value, ImagePromptOptions.parse("生成一张${text}分辨率、9:16的猫").options.resolution)
            assertEquals(value, ImagePromptOptions.parse("画猫，分辨率设置为$text").options.resolution)
            assertEquals(value, ImagePromptOptions.parse("a cat, $value resolution").options.resolution)
        }
        assertNull(ImagePromptOptions.parse("画一个高个子和中年人").options.resolution)
        assertNull(ImagePromptOptions.parse("海报写着“高分辨率”").options.resolution)
        assertEquals("ultra", ImagePromptOptions.parse("不要高分辨率，改成超高分辨率，9:16").options.resolution)
        assertEquals("high", ImageGenerationOptions.fromJson(JSONObject("""{"resolution":"2K"}""")).resolution)
    }
}
