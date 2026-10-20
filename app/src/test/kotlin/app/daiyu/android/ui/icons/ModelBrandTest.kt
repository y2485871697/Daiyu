package app.daiyu.android.ui.icons

import org.junit.Assert.*
import org.junit.Test

class ModelBrandTest {
    @Test fun allTenFamiliesAndNamespacedIdsMatch() {
        val examples = mapOf(
            ModelBrand.OPENAI to listOf("gpt-6.1-sol", "OpenAI/gpt-4.1", "o3", "o4-mini", "CHATGPT-4o"),
            ModelBrand.CLAUDE to listOf("anthropic/claude-sonnet-4", "Claude", "Anthropic"),
            ModelBrand.GEMINI to listOf("google/gemini-2.5-pro", "Gemini", "nano-banana"),
            ModelBrand.DEEPSEEK to listOf("deepseek-ai/DeepSeek-R1", "深度求索"),
            ModelBrand.QWEN to listOf("Qwen/Qwen3-32B", "qwq-32b", "通义千问"),
            ModelBrand.KIMI to listOf("kimi-k2", "moonshot-v1", "月之暗面", "k3"),
            ModelBrand.GROK to listOf("xai/grok-4", "Grok"),
            ModelBrand.ZHIPU to listOf("glm-4.5", "chatglm-6b", "智谱 GLM", "zhipu"),
            ModelBrand.DOUBAO to listOf("doubao-seed-1.6", "豆包"),
            ModelBrand.MINIMAX to listOf("MiniMax-M2", "minimax/minimax-01", "海螺"),
        )
        assertEquals(ModelBrand.entries.toSet(), examples.keys)
        examples.forEach { (brand, names) -> names.forEach { assertEquals(it, brand, modelBrand(it)) } }
    }
    @Test fun realModelIdWinsOverGatewayAndFriendlyName() {
        assertEquals(ModelBrand.DEEPSEEK, modelBrand("openai/deepseek-r1", "Claude alias"))
        assertEquals(ModelBrand.CLAUDE, modelBrand("gateway/claude-sonnet", "gpt alias"))
        assertEquals(ModelBrand.GEMINI, modelBrand("private-slot", "Gemini"))
    }
    @Test fun unknownModelsKeepGenericIconWithoutSubstringFalsePositive() {
        for (name in listOf("", "模型", "private-model", "foo13", "video1", "logo4", "gptish", "xairplane")) {
            assertNull(name, modelBrand(name))
        }
        assertTrue(ModelBrand.OPENAI.monochrome)
        assertTrue(ModelBrand.GROK.monochrome)
        assertTrue(ModelBrand.entries.filterNot { it in listOf(ModelBrand.OPENAI, ModelBrand.GROK) }.none { it.monochrome })
    }
}
