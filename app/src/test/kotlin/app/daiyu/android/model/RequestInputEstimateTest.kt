package app.daiyu.android.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RequestInputEstimateTest {
    @Test fun countsInstructionsToolsAndResultsButNotOpaqueProtocolBytes() {
        val base = JSONObject("""{"input":[{"type":"reasoning","encrypted_content":"opaque","summary":[{"type":"summary_text","text":"visible"}]}],"instructions":"instruction","tools":[{"name":"lookup","parameters":{"type":"object"}}]}""")
        val measured = RequestInputEstimate.from(base, null)
        base.getJSONArray("input").getJSONObject(0).put("encrypted_content", "X".repeat(10000))
        assertEquals(measured, RequestInputEstimate.from(base, null))
        base.put("instructions", "long instructions".repeat(100))
        assertTrue(RequestInputEstimate.from(base, null) > measured)
    }
    @Test fun businessSignatureIsNotMisclassifiedAsOpaque() {
        val request = JSONObject("""{"messages":[{"role":"assistant","content":[{"type":"tool_use","id":"t","name":"f","input":{"signature":"short"}}]}]}""")
        val measured = RequestInputEstimate.from(request, null)
        request.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
            .getJSONObject("input").put("signature", "X".repeat(1000))
        assertTrue(RequestInputEstimate.from(request, null) > measured)
    }
}
