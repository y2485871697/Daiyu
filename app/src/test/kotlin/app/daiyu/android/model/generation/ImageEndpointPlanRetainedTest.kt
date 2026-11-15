package app.daiyu.android.model.generation
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class ImageEndpointPlanRetainedTest {
    private fun prepared(edit:String="multipart") = ImageRequestParameters.prepare(JSONObject().put("model","test").put("prompt","cat")
        .put("daiyu_image_config",JSONObject().put("edit_protocol",edit)),ImageGenerationOptions(),ImageGenerationOptions(size="1024x2048"))
    @Test fun routingIsByProtocolAndDoesNotDuplicateImagesSuffixes() {
        assertEquals(ImageEndpointPlan.Kind.GENERATIONS,ImageEndpointPlan.create(prepared(),0,false).kind)
        val multi=ImageEndpointPlan.create(prepared(),2,false)
        assertEquals("https://example.invalid/v1/images/edits",multi.url("https://example.invalid/v1/images/generations/"))
        val json=ImageEndpointPlan.create(prepared("generations_image"),1,true)
        assertEquals("https://example.invalid/v1/images/generations",json.url("https://example.invalid/v1/images/edits"))
        assertEquals(ImageEndpointPlan.Kind.GENERATIONS_IMAGE,json.kind)
    }
    @Test fun cannotDropMasksOrReferencesToFitProtocol() {
        assertThrows(ImageGenerationParameterException::class.java) { ImageEndpointPlan.create(prepared(),0,true) }
        assertThrows(ImageGenerationParameterException::class.java) { ImageEndpointPlan.create(prepared("generations_image"),2,false) }
        assertThrows(ImageGenerationParameterException::class.java) { ImageEndpointPlan.create(prepared("json_image_url"),1,true) }
    }
    @Test fun textEndpointsAndAmbiguousUrlsAreNotImageEndpoints() {
        val plan=ImageEndpointPlan.create(prepared(),0,false)
        for(url in listOf("https://example.invalid/v1/responses","https://example.invalid/v1/chat/completions","https://example.invalid?key=a","https://user:pass@example.invalid")) {
            assertThrows(IllegalArgumentException::class.java) { plan.url(url) }
        }
    }
}
