package app.daiyu.android.ui.icons

internal enum class ModelBrand(val monochrome: Boolean = false) {
    OPENAI(true), CLAUDE, GEMINI, DEEPSEEK, QWEN, KIMI, GROK(true), ZHIPU, DOUBAO, MINIMAX,
}

private val modelBrands = listOf(
    ModelBrand.CLAUDE to Regex("claude|anthropic", RegexOption.IGNORE_CASE),
    ModelBrand.GEMINI to Regex("gemini|nano[- ]banana", RegexOption.IGNORE_CASE),
    ModelBrand.DEEPSEEK to Regex("deepseek|深度求索", RegexOption.IGNORE_CASE),
    ModelBrand.QWEN to Regex("qwen|qwq|qvq|通义|千问", RegexOption.IGNORE_CASE),
    ModelBrand.KIMI to Regex("kimi|moonshot|月之暗面|(^|[^a-z0-9])k[23]([-_. ]|$)", RegexOption.IGNORE_CASE),
    ModelBrand.GROK to Regex("grok|(^|[^a-z0-9])xai([^a-z0-9]|$)", RegexOption.IGNORE_CASE),
    ModelBrand.ZHIPU to Regex("zhipu|智谱|chatglm|(^|[^a-z0-9])glm([-_. ]|$)", RegexOption.IGNORE_CASE),
    ModelBrand.DOUBAO to Regex("doubao|豆包", RegexOption.IGNORE_CASE),
    ModelBrand.MINIMAX to Regex("minimax|mini-max|海螺", RegexOption.IGNORE_CASE),
    ModelBrand.OPENAI to Regex("(^|[^a-z0-9])(gpt|chatgpt|openai)([^a-z0-9]|$)|(^|[^a-z0-9])o[134]([-_. ]|$)", RegexOption.IGNORE_CASE),
)

internal fun modelBrand(modelId: String, displayName: String = modelId): ModelBrand? {
    fun match(value: String) = modelBrands.firstOrNull { it.second.containsMatchIn(value) }?.first
    return match(modelId.substringAfterLast('/')) ?: match(modelId) ?: match(displayName)
}
