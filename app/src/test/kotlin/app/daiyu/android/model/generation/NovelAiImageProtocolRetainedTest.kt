package app.daiyu.android.model.generation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NovelAiImageProtocolRetainedTest {
    private fun prepare(params: JSONObject = JSONObject("""{"params_version":3,"steps":28,"scale":5,"sampler":"k_euler","seed":1}"""),
        options: ImageGenerationOptions = ImageGenerationOptions(size="832x1216")): ImageRequestParameters.Prepared =
        ImageRequestParameters.prepare(JSONObject().put("model","custom-nai").put("prompt","a cat").put("n",1)
            .put("daiyu_image_config",JSONObject().put("endpoint","novelai_native").put("native_parameters",params)),
            ImageGenerationOptions(),options)
    private val png = byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,13,10,26,10,0,0,0,0)
    private fun zip(vararg entries: Pair<String,ByteArray>): ByteArray = ByteArrayOutputStream().use { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (name,data) -> z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() } }
        out.toByteArray()
    }
    @Test fun createsNativeFieldsAndFreshBaseCaptionsWithoutModelGuessing() {
        val native=JSONObject("""{"params_version":4,"steps":28,"scale":5,"sampler":"configured_sampler","seed":1,"v4_prompt":{"caption":{"base_caption":"old","char_captions":[{"char_caption":"cat"}]}}}""")
        val body=NovelAiImageProtocol.request(prepare(native))
        assertEquals(setOf("model","input","action","parameters"),body.keys().asSequence().toSet())
        assertEquals("a cat",body.getString("input"))
        val p=body.getJSONObject("parameters")
        assertEquals(832,p.getInt("width"));assertEquals(1216,p.getInt("height"));assertEquals(1,p.getInt("n_samples"))
        assertEquals("a cat",p.getJSONObject("v4_prompt").getJSONObject("caption").getString("base_caption"))
        assertEquals(1,p.getJSONObject("v4_prompt").getJSONObject("caption").getJSONArray("char_captions").length())
        assertEquals("old",native.getJSONObject("v4_prompt").getJSONObject("caption").getString("base_caption"))
    }
    @Test fun invalidNativeParametersAndUnsupportedOptionsFailBeforeSending() {
        assertThrows(ImageGenerationParameterException::class.java) { NovelAiImageProtocol.request(prepare(JSONObject())) }
        for (o in listOf(ImageGenerationOptions(size="1150x2048"),ImageGenerationOptions(size="auto"),
            ImageGenerationOptions(size="832x1216",count=5),ImageGenerationOptions(size="832x1216",quality="high"),
            ImageGenerationOptions(size="832x1216",responseFormat="url"))) {
            assertThrows(ImageGenerationParameterException::class.java) { NovelAiImageProtocol.request(prepare(options=o)) }
        }
    }
    @Test fun binaryAndZipImagesAreDecodedWithoutExtractingFileNames() {
        assertArrayEquals(png,NovelAiImageProtocol.parse(png).images.single().bytes)
        val result=NovelAiImageProtocol.parse(zip("../../escape.png" to png,"notes.txt" to "metadata".toByteArray()))
        assertEquals(1,result.images.size);assertArrayEquals(png,result.images.single().bytes)
    }
    @Test fun zipEntryCountImageCountAndNonImagesAreBounded() {
        assertThrows(IllegalArgumentException::class.java) { NovelAiImageProtocol.parse(zip(*(1..33).map { "$it.txt" to byteArrayOf(1) }.toTypedArray())) }
        assertThrows(IllegalArgumentException::class.java) { NovelAiImageProtocol.parse(zip(*(1..5).map { "$it.png" to png }.toTypedArray())) }
        assertThrows(IllegalArgumentException::class.java) { NovelAiImageProtocol.parse(zip("empty.txt" to byteArrayOf(1))) }
        assertThrows(IllegalArgumentException::class.java) { NovelAiImageProtocol.parse("{}".toByteArray()) }
    }
    @Test fun oversizedIgnoredEntriesCannotBypassDecompressionBudget() {
        val bytes=ByteArray(GENERATION_IMAGE_LIMIT+1)
        assertThrows(IllegalArgumentException::class.java) { NovelAiImageProtocol.parse(zip("ignored.bin" to bytes)) }
    }
    @Test fun cancellationIsCheckedDuringZipReading() {
        assertThrows(IllegalStateException::class.java) { NovelAiImageProtocol.parse(zip("a.png" to png)) { error("cancelled") } }
    }
}
