package app.daiyu.android.ui

import android.graphics.Bitmap
import android.util.Base64
import app.daiyu.android.domain.ToolCallId
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.runtime.ToolDisposition
import app.daiyu.android.domain.runtime.ToolRecord
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ToolScreenshotReceiptUnitTest {
    private fun visual(): ToolRecord {
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.toByteArray()
        }
        bitmap.recycle()
        return ToolRecord(ToolInvocation(ToolCallId("capture"), "screen", """{"operation":"screenshot"}"""),
            ToolDisposition.SUCCEEDED, JSONObject().put("capture_id", "visual")
                .put("width", 4).put("height", 3).put("mime_type", "image/png")
                .put("data_base64", Base64.encodeToString(bytes, Base64.NO_WRAP)).toString())
    }
    @Test fun currentVisualImageDecodesWithoutANodeObservation() {
        val record = visual()
        val image = ToolScreenshotReceipt.decode(record)
        assertNotNull(image); assertEquals(4, image!!.width); assertEquals(3, image.height); image.recycle()
    }
    @Test fun historicalAndExplicitObservationReceiptsRemainCompatible() {
        val record = visual()
        val data = JSONObject(record.result!!).apply { remove("capture_id"); put("observation_id", "obs") }
        val historical = record.copy(result = data.toString())
        val image = ToolScreenshotReceipt.decode(historical)
        assertNotNull(image); image!!.recycle()
        val explicit = historical.copy(invocation = record.invocation.copy(
            arguments = """{"operation":"screenshot","observation_id":"obs"}"""))
        val exact = ToolScreenshotReceipt.decode(explicit)
        assertNotNull(exact); exact!!.recycle()
        assertNull(ToolScreenshotReceipt.decode(explicit.copy(result = data.put("observation_id", "other").toString())))
    }
    @Test fun visualCaptureCannotSatisfyAnExplicitNodeSnapshotOrAmbiguousIds() {
        val record = visual()
        assertNull(ToolScreenshotReceipt.decode(record.copy(invocation = record.invocation.copy(
            arguments = """{"operation":"screenshot","observation_id":"visual"}"""))))
        for (invalid in listOf<Any>("visual", JSONObject.NULL, 123, true, JSONObject(), org.json.JSONArray())) {
            val data = JSONObject(record.result!!).put("observation_id", invalid)
            assertNull(ToolScreenshotReceipt.decode(record.copy(result = data.toString())))
            data.remove("capture_id"); data.put("observation_id", "obs"); data.put("capture_id", invalid)
            assertNull(ToolScreenshotReceipt.decode(record.copy(result = data.toString())))
        }
        val data = JSONObject(record.result!!).put("capture_id", "")
        assertNull(ToolScreenshotReceipt.decode(record.copy(result = data.toString())))
    }
    @Test fun automaticObserveFallbackDisplaysItsRealImageWithoutPretendingItHasNodes() {
        val original = visual()
        val record = original.copy(invocation = original.invocation.copy(arguments = """{"operation":"observe"}"""),
            result = JSONObject(original.result!!).put("screen_read_mode", "visual_fallback")
                .put("node_tree_available", false).put("visual_actions_available", true)
                .put("node_failure_code", "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED").toString())
        val image = ToolScreenshotReceipt.decode(record)
        assertNotNull(image); assertEquals(4, image!!.width); assertEquals(3, image.height); image.recycle()
        for (key in listOf("screen_read_mode", "node_tree_available", "node_failure_code", "visual_actions_available")) {
            assertNull(ToolScreenshotReceipt.decode(record.copy(result = JSONObject(record.result!!).apply { remove(key) }.toString())))
        }
        assertNull(ToolScreenshotReceipt.decode(record.copy(disposition = ToolDisposition.NOT_EXECUTED)))
    }

    @Test fun truncatedPngWithAValidHeaderNeverDisplays() {
        val record = visual()
        val data = JSONObject(record.result!!)
        val bytes = Base64.decode(data.getString("data_base64"), Base64.NO_WRAP).copyOf(33)
        data.put("data_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        assertNull(ToolScreenshotReceipt.decode(record.copy(result = data.toString())))
    }

}
