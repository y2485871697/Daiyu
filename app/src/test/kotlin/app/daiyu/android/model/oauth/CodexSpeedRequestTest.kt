package app.daiyu.android.model.oauth

import app.daiyu.android.model.GptServiceTier
import app.daiyu.android.model.GptSpeedMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexSpeedRequestTest {
    private fun request() = JSONObject().put("model", "gpt-fixture")
        .put("reasoning", JSONObject().put("effort", "high"))

    @Test fun oauthNormalOmitsTierAfterGenericRequestOptions() {
        val body = request()
        GptServiceTier.apply(body, GptSpeedMode.NORMAL)
        assertEquals("default", body.getString("service_tier"))
        CodexRequestPolicy.apply(body, GptSpeedMode.NORMAL)
        assertFalse(body.has("service_tier"))
        assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
    }

    @Test fun explicitCodexNormalClearsMergedManualTier() {
        for (tier in listOf("default", "fast", "priority", "ultrafast", "auto")) {
            val body = request().put("service_tier", tier)
            GptServiceTier.apply(body, GptSpeedMode.NORMAL, codex = true)
            assertFalse(tier, body.has("service_tier"))
        }
    }

    @Test fun oauthFastUsesPriorityAfterGenericFastAndCustomBody() {
        val body = request().put("service_tier", "auto")
        GptServiceTier.apply(body, GptSpeedMode.FAST)
        assertEquals("fast", body.getString("service_tier"))
        CodexRequestPolicy.apply(body, GptSpeedMode.FAST)
        assertEquals("priority", body.getString("service_tier"))
        assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
    }

    @Test fun oauthNormalRemovesFastTierOnEveryRequestRound() {
        repeat(2) {
            val body = request().put("service_tier", "fast")
            CodexRequestPolicy.apply(body, GptSpeedMode.NORMAL)
            assertFalse(body.has("service_tier"))
        }
    }

    @Test fun gatewayModesKeepTheirOriginalWireValues() {
        for ((mode, expected) in listOf(GptSpeedMode.NORMAL to "default",
            GptSpeedMode.FAST to "fast", GptSpeedMode.ULTRA_FAST to "ultrafast")) {
            val body = request()
            GptServiceTier.apply(body, mode)
            assertEquals(expected, body.getString("service_tier"))
            assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
        }
    }

    @Test fun unsetModeKeepsManualTierForBothAuthenticationPaths() {
        for (codex in listOf(false, true)) {
            val body = request().put("service_tier", "priority")
            GptServiceTier.apply(body, null, codex)
            if (codex) CodexRequestPolicy.apply(body, null)
            assertEquals("priority", body.getString("service_tier"))
        }
        val body = request()
        CodexRequestPolicy.apply(body, null)
        assertFalse(body.has("service_tier"))
    }

    @Test fun codexUltraFastUsesItsOfficialWireValueWithoutDowngrading() {
        val body = request().put("service_tier", "priority")
        CodexRequestPolicy.apply(body, GptSpeedMode.ULTRA_FAST)
        assertEquals("ultrafast", body.getString("service_tier"))
        assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
        assertEquals("model=gpt-fixture;tier=ultrafast", CodexRequestPolicy.routingHint(body))
    }

    @Test fun manualCodexUltraFastIsPreservedWithoutSpeedSelection() {
        val body = request().put("service_tier", "ultrafast")
        CodexRequestPolicy.apply(body, null)
        assertEquals("ultrafast", body.getString("service_tier"))
    }

    @Test fun routingHintUsesFinalModelAndTierWithoutAllowingHeaderInjection() {
        assertEquals("model=gpt-fixture", CodexRequestPolicy.routingHint(request()))
        assertEquals("model=relay/GPT-final;tier=priority", CodexRequestPolicy.routingHint(
            request().put("model", "relay/GPT-final").put("service_tier", "priority")))
        assertNull(CodexRequestPolicy.routingHint(request().put("model", "gpt-bad\r\nInjected: yes")))
        assertNull(CodexRequestPolicy.routingHint(request().put("service_tier", "bad\nvalue")))
    }

    @Test fun codexMatchingUsesFinalModelAndLeavesOtherModelsUntouched() {
        val namespaced = request().put("model", "relay/team/GPT-fixture").put("service_tier", "fast")
        GptServiceTier.apply(namespaced, GptSpeedMode.NORMAL, codex = true)
        assertFalse(namespaced.has("service_tier"))
        val other = request().put("model", "o3").put("service_tier", "auto")
        GptServiceTier.apply(other, GptSpeedMode.NORMAL, codex = true)
        assertEquals("auto", other.getString("service_tier"))
    }
}
