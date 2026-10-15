package app.daiyu.android.model

import android.test.InstrumentationTestCase
import org.json.JSONObject

@Suppress("DEPRECATION")
class GptServiceTierTest : InstrumentationTestCase() {
    fun testNullModeLeavesExistingTierUntouched() {
        val request = JSONObject()
            .put("model", "gpt-5")
            .put("service_tier", "auto")

        GptServiceTier.apply(request, null)

        assertEquals("auto", request.getString("service_tier"))

        val withoutTier = JSONObject().put("model", " \tRelay/team/GPT-5\n")
        val before = withoutTier.toString()
        GptServiceTier.apply(withoutTier, null)
        assertEquals(before, withoutTier.toString())
        assertFalse(withoutTier.has("service_tier"))
    }

    fun testExplicitModesNormalizeToWireServiceTiers() {
        val expected = mapOf(
            GptSpeedMode.NORMAL to "default",
            GptSpeedMode.FAST to "fast",
            GptSpeedMode.ULTRA_FAST to "ultrafast",
        )

        expected.forEach { (mode, tier) ->
            val request = JSONObject().put("model", "gpt-5")
            GptServiceTier.apply(request, mode)
            assertEquals(tier, request.getString("service_tier"))
        }
    }

    fun testOnlyFinalGptModelReceivesTier() {
        val request = JSONObject().put("model", "gpt-5")
            .put("service_tier", "auto")
            .put("reasoning", JSONObject().put("effort", "high"))
            .put("reasoning_effort", "high")

        GptServiceTier.apply(request, GptSpeedMode.FAST)

        assertEquals("fast", request.getString("service_tier"))
        assertEquals("gpt-5", request.getString("model"))
        assertEquals("high", request.getJSONObject("reasoning").getString("effort"))
        assertEquals("high", request.getString("reasoning_effort"))
        assertEquals(4, request.length())
    }

    fun testNonGptModelsAndMalformedModelValuesAreUntouched() {
        listOf("o3", "codex", "foo-gpt-5", "claude-sonnet", "deepseek-chat").forEach { model ->
            val request = JSONObject().put("model", model)
            GptServiceTier.apply(request, GptSpeedMode.FAST)
            assertFalse(model, request.has("service_tier"))
        }

        listOf(
            JSONObject(),
            JSONObject().put("model", JSONObject.NULL),
            JSONObject().put("model", 5),
            JSONObject().put("model", ""),
        ).forEach { request ->
            GptServiceTier.apply(request, GptSpeedMode.ULTRA_FAST)
            assertFalse(request.has("service_tier"))
        }
    }

    fun testNamespacesWhitespaceAndCaseMatchWithoutChangingOtherFields() {
        val models = listOf(
            "gpt-5",
            "GpT-5",
            "OpenAI/GPT-99-relay",
            "relay/gpt-5",
            "vendor/team/deployment/GpT-5",
            " \tvendor/team/GPT-5\r\n",
            " \tgpt-5 \n",
            "/gpt-5",
            "vendor//GPT-5",
            "gpt-",
        )

        models.forEach { model ->
            assertTrue(model, GptServiceTier.isGptSpeedModel(model))
            GptSpeedMode.values().forEach { mode ->
                val reasoning = JSONObject().put("effort", "high")
                val request = JSONObject().put("model", model)
                    .put("service_tier", "auto")
                    .put("reasoning", reasoning)
                    .put("reasoning_effort", "high")

                GptServiceTier.apply(request, mode)

                val expectedTier = when (mode) {
                    GptSpeedMode.NORMAL -> "default"
                    GptSpeedMode.FAST -> "fast"
                    GptSpeedMode.ULTRA_FAST -> "ultrafast"
                }
                assertEquals(model, expectedTier, request.getString("service_tier"))
                assertEquals(model, model, request.getString("model"))
                assertSame(reasoning, request.getJSONObject("reasoning"))
                assertEquals("high", request.getString("reasoning_effort"))
                assertEquals(4, request.length())
            }
        }
    }

    fun testMatcherUsesOnlyLastSegmentAndTrimsOnlyFullIdentifier() {
        val models = listOf(
            "foo-gpt-5",
            "relay/foo-gpt-99",
            "vendor/team/FOO-GPT-5",
            "gpt-5/o3",
            "openai/gpt-5/claude-sonnet",
            "gpt-5/",
            "vendor/team/",
            "vendor/ gpt-5",
            "vendor/\tgpt-5",
            "gpt",
            "gpt_5",
            "xgpt-5",
            "",
            " \t\r\n",
            "/",
        )

        models.forEach { model ->
            assertFalse(model, GptServiceTier.isGptSpeedModel(model))
            GptSpeedMode.values().forEach { mode ->
                listOf(false, true).forEach { hasTier ->
                    val request = JSONObject().put("model", model)
                        .put("reasoning", JSONObject().put("effort", "high"))
                    if (hasTier) request.put("service_tier", "auto")
                    val before = request.toString()

                    GptServiceTier.apply(request, mode)

                    assertEquals(model, before, request.toString())
                }
            }
        }
    }

    fun testOnlyFinalMergedJsonModelControlsTier() {
        val overriddenNonGpt = JSONObject().put("model", "gpt-5")
            .put("service_tier", "auto")
        overriddenNonGpt.put("model", "vendor/team/o3")
        val before = overriddenNonGpt.toString()

        GptServiceTier.apply(overriddenNonGpt, GptSpeedMode.FAST)

        assertEquals(before, overriddenNonGpt.toString())

        val overriddenGpt = JSONObject().put("model", "o3")
        overriddenGpt.put("model", " \tvendor/team/GPT-5\n")

        GptServiceTier.apply(overriddenGpt, GptSpeedMode.ULTRA_FAST)

        assertEquals("ultrafast", overriddenGpt.getString("service_tier"))
        assertEquals(" \tvendor/team/GPT-5\n", overriddenGpt.getString("model"))
    }
}
