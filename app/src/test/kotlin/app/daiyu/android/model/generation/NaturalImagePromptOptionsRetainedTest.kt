package app.daiyu.android.model.generation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NaturalImagePromptOptionsRetainedTest {
    private fun parse(text: String)=ImagePromptOptions.parse(text).options
    @Test fun screenshotsAndEmbeddedChineseDimensionsWork() {
        for(prompt in listOf("生成一张2k，9:16的动漫美少女","生成一张2k、9:16的动漫美少女","生成一张2K 9:16的动漫美少女")) {
            val o=parse(prompt);assertEquals("high",o.resolution);assertEquals("9:16",o.aspectRatio);assertEquals(1,o.count)
            val body=ImageRequestParameters.prepare(JSONObject().put("n",1),o,ImageGenerationOptions()).body
            assertEquals("1152x2048",body.getString("size"))
        }
        for(prompt in listOf("生成一张动漫美少女1312×736","画猫，尺寸为1312 x 736像素","生成一张插画，宽1312高736","生成一张插画宽度为1312，高度为736")) {
            assertEquals("1312x736",parse(prompt).size)
        }
    }
    @Test fun allScreenshotRatioAndTierCombinationsKeepRequestedRatio() {
        val ratios=listOf("1:1","3:4","9:16","4:3","16:9","21:9")
        val tiers=listOf("1k","1.5k","2k","4k")
        for(ratio in ratios) for(tier in tiers) {
            val options=parse("生成一张${tier}画质、${ratio}画幅的风景")
            assertEquals(ImageResolutionTier.normalize(tier),options.resolution);assertEquals(ratio,options.aspectRatio)
            val plan=ImageRequestParameters.prepare(JSONObject(),options,ImageGenerationOptions())
            val (w,h)=ImageGenerationOptions.dimensions(plan.body.getString("size"))
            val (a,b)=ratio.split(':').map(String::toInt)
            assertEquals("$ratio@${ImageResolutionTier.normalize(tier)}",w*b,h*a)
            assertFalse(plan.body.has("resolution"));assertFalse(plan.body.has("aspect_ratio"))
        }
    }
    @Test fun spokenRatiosAndChineseQuantitiesAreRecognized() {
        val a=parse("画三张4K、16比9的风景")
        assertEquals(3,a.count);assertEquals("16:9",a.aspectRatio);assertEquals("ultra",a.resolution)
        assertEquals("9:16",parse("画一张九比十六的插画，2K").aspectRatio)
        assertEquals("21:9",parse("画一张二十一比九的壁纸，2K").aspectRatio)
        assertEquals("medium",parse("1.5K画质，3:4").resolution)
    }
    @Test fun fullWidthDigitsAndWhitespaceNormalizeWithoutChangingPrompt() {
        val prompt="生成一张２Ｋ、９：１６的插画"
        val result=ImagePromptOptions.parse(prompt)
        assertEquals(prompt,result.prompt);assertEquals("high",result.options.resolution);assertEquals("9:16",result.options.aspectRatio)
    }
    @Test fun countDoesNotMeanNumberOfPeopleOrFurniture() {
        for(text in listOf("画三个人和两只猫","画三张床","画两张桌子","一张脸的特写","画猫，猫的数量为3","画两张书桌","画两张漂亮的书桌","画一个正在画三张画的女孩","画一个画三张画的人","画猫，扑克牌的张数为3")) assertNull(text,parse(text).count)
        assertEquals(1,parse("画一张图，里面有三个人两只猫").count)
        assertEquals(2,parse("generate two images of three cats").count)
        assertEquals(3,parse("张数为三，画一只猫").count)
        assertEquals(3,parse("请帮我画三张风景").count)
    }
    @Test fun concurrencyIsLocalAndNeverLeaksIntoProviderFields() {
        val options=parse("生成三张2K、9:16插画，并发数设为2")
        assertEquals(3,options.count);assertEquals(2,options.concurrency)
        val result=ImageRequestParameters.prepare(JSONObject(),options,ImageGenerationOptions())
        assertEquals(2,result.options.concurrency);assertEquals(3,result.body.getInt("n"))
        assertFalse(result.body.has("concurrency"));assertFalse(result.summary.contains("concurrency"))
    }
    @Test fun timesScoresLiteralTextCodeUrlsAndReferenceGeometryAreNotOutputSettings() {
        for(text in listOf("9:16 开会","画时钟显示9:16","比赛比分3:4","文字写着1024x1024","画写着“4K 16:9”的海报",
            "`size=1312x736;n=3`","```\n4k 16:9\n```","https://example.com/2k/9:16","参考图原尺寸1312×736、原比例16:9",
            "NBA2K的海报","桌子2×3米","预算2k的房间","人物头身比例1:8")) {
            val o=parse(text);assertNull(text,o.size);assertNull(text,o.aspectRatio);assertNull(text,o.resolution);assertNull(text,o.count)
        }
        val o=parse("参考图尺寸1024x1024，输出尺寸1312x736")
        assertEquals("1312x736",o.size)
    }
    @Test fun unrelatedNegationDoesNotSwallowOutputSettings() {
        val options=parse("画一张风景，不要水印，2k，9:16")
        assertEquals("high",options.resolution);assertEquals("9:16",options.aspectRatio)
        assertEquals("high",parse("不要水印的2k图片，9:16").resolution)
        assertEquals("high",parse("不要文字的2k图片，9:16").resolution)
        assertEquals("ultra",parse("不要2k，改成4k，9:16").resolution)
        assertEquals("16:9",parse("不是9:16而是16:9，2k").aspectRatio)
    }
    @Test fun rejectedOrConflictingParametersCannotSilentlyProduceDefaultImages() {
        for(text in listOf("不要生成3张","生成一张不要2k的图片","例如2k，9:16","画一张2k和4k的图","生成一张图，9:16、1:1","生成两张图，张数3",
            "生成一张图并发2，并发3","画一张0张图，张数0","生成11张插画","并发9，生成三张插画","画一张1312x736的图，比例9:16")) {
            assertThrows(text,ImageGenerationParameterException::class.java) { parse(text) }
        }
    }
    @Test fun fractionalAndNegativeCountsAreNotTruncatedOrIgnored() {
        for(text in listOf("生成1.5张插画","生成三张2k插画，并发2.5","画三张图，并发-2")) {
            assertThrows(text,ImageGenerationParameterException::class.java) { parse(text) }
        }
    }
    @Test fun structuredWorkerOptionsOverrideProseAndJsonDirectiveStillWorks() {
        val explicit=ImageGenerationOptions(aspectRatio="1:1",resolution="ultra",count=2,concurrency=2)
        val prose=ImagePromptOptions.parse("生成一张2k、9:16的插画",explicit)
        val body=ImageRequestParameters.prepare(JSONObject().put("n",1),prose.options,explicit)
        assertEquals("4096x4096",body.body.getString("size"));assertEquals(2,body.body.getInt("n"));assertFalse(body.body.has("concurrency"))
        val withLabel=ImagePromptOptions.parse("输出尺寸1312x736，画猫",ImageGenerationOptions(size="1024x1024"))
        assertNull(withLabel.options.size)
        assertNull(ImagePromptOptions.parse("画猫，并发2.5",ImageGenerationOptions(concurrency=2)).options.concurrency)
        assertNull(ImagePromptOptions.parse("生成1.5张插画",ImageGenerationOptions(count=2)).options.count)
        assertNull(ImagePromptOptions.parse("生成999999999999999999999999张插画",ImageGenerationOptions(count=2)).options.count)
        val json=ImagePromptOptions.parse("猫\nimage_options: {\"size\":\"1312x736\",\"n\":3,\"concurrency\":2}")
        assertEquals("猫",json.prompt);assertEquals("1312x736",json.options.size);assertEquals(2,json.options.concurrency)
    }
    @Test fun clearSizeOnlyIsNotTreatedAsDefaultSquare() {
        val o=parse("画一张1312×736的风景")
        assertEquals("1312x736",ImageRequestParameters.prepare(JSONObject(),o,ImageGenerationOptions()).body.getString("size"))
        for(text in listOf("画一张2k风景","画一张9:16风景")) {
            assertFalse(parse(text).isEmpty)
            assertThrows(ImageGenerationParameterException::class.java) { ImageRequestParameters.prepare(JSONObject(),parse(text),ImageGenerationOptions()) }
        }
    }
}
