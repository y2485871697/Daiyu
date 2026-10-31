package app.daiyu.android.ui

import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import android.util.Base64
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import java.io.ByteArrayOutputStream
import org.json.JSONObject

@Suppress("DEPRECATION")
class ToolScreenshotReceiptTest : InstrumentationTestCase() {
    private fun receipt(): ToolRecord {
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray()
        }
        bitmap.recycle()
        val call = ToolInvocation(ToolCallId("capture"), "screen", """{"operation":"screenshot","observation_id":"obs"}""")
        return ToolRecord(call, ToolDisposition.SUCCEEDED, JSONObject().put("observation_id", "obs")
            .put("width", 4).put("height", 3).put("mime_type", "image/png")
            .put("data_base64", Base64.encodeToString(png, Base64.NO_WRAP)).toString())
    }
    fun testOnlyExactConfirmedPngReceiptDecodes() {
        val record = receipt()
        val image = ToolScreenshotReceipt.decode(record)
        assertNotNull(image); assertEquals(4, image!!.width); assertEquals(3, image.height); image.recycle()
        for (state in ToolDisposition.entries.filter { it != ToolDisposition.SUCCEEDED })
            assertNull(ToolScreenshotReceipt.decode(record.copy(disposition = state)))
        assertNull(ToolScreenshotReceipt.decode(record.copy(invocation = record.invocation.copy(name = "mcp_0_call"))))
        assertNull(ToolScreenshotReceipt.decode(record.copy(invocation = record.invocation.copy(arguments = """{"operation":"observe"}"""))))
    }
    fun testImplicitObservationScreenshotReadsItsExistingReceipt() {
        val original = receipt()
        val record = original.copy(invocation = original.invocation.copy(arguments = """{"operation":"screenshot"}"""))
        val image = ToolScreenshotReceipt.decode(record)
        assertNotNull(image); assertEquals(4, image!!.width); assertEquals(3, image.height); image.recycle()
        for (invalid in listOf("null", "4", "\"\"", "\"other\"")) {
            val args = "{\"operation\":\"screenshot\",\"observation_id\":$invalid}"
            assertNull(ToolScreenshotReceipt.decode(original.copy(invocation = original.invocation.copy(arguments = args))))
        }
        val missing = JSONObject(record.result!!).apply { remove("observation_id") }
        assertNull(ToolScreenshotReceipt.decode(record.copy(result = missing.toString())))
    }
    fun testVisualOnlyCaptureHasNoClickableObservationAndCannotSatisfyAnExplicitRequest() {
        val original = receipt()
        val data = JSONObject(original.result!!).apply { remove("observation_id"); put("capture_id", "visual") }
        val visual = original.copy(result = data.toString(),
            invocation = original.invocation.copy(arguments = """{"operation":"screenshot"}"""))
        val image = ToolScreenshotReceipt.decode(visual)
        assertNotNull(image); image!!.recycle()
        assertNull(ToolScreenshotReceipt.decode(original.copy(result = data.toString())))
        data.put("observation_id", "obs")
        assertNull(ToolScreenshotReceipt.decode(visual.copy(result = data.toString())))
    }
    fun testMalformedOrMismatchedMediaNeverLoadsPathOrUrl() {
        val record = receipt()
        val changes = listOf<(JSONObject) -> Unit>(
            { it.put("observation_id", "other") }, { it.put("width", 5) }, { it.put("width", "4") },
            { it.put("height", 3.5) }, { it.put("mime_type", "image/jpeg") },
            { it.put("data_base64", "https://example.test/image.png") },
            { it.put("data_base64", "/data/private/image.png") },
            { it.put("data_base64", Base64.encodeToString(ByteArray(32), Base64.NO_WRAP)) },
            { it.put("data_base64", "a".repeat(12 * 1024 * 1024)) })
        changes.forEach { mutate ->
            val data = JSONObject(record.result!!); mutate(data)
            assertNull(ToolScreenshotReceipt.decode(record.copy(result = data.toString())))
        }
    }
}
