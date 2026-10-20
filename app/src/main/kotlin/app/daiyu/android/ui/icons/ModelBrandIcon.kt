package app.daiyu.android.ui.icons

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

internal val ModelBrandIdentity = SemanticsPropertyKey<String>("ModelBrandIdentity")

@Composable
internal fun ModelBrandIcon(modelId: String, modifier: Modifier = Modifier,
    displayName: String = modelId, tint: Color) {
    val brand = remember(modelId, displayName) { modelBrand(modelId, displayName) }
    val vector = when (brand) {
        ModelBrand.OPENAI -> ModelBrandVectors.OPENAI
        ModelBrand.CLAUDE -> ModelBrandVectors.CLAUDE
        ModelBrand.GEMINI -> ModelBrandVectors.GEMINI
        ModelBrand.DEEPSEEK -> ModelBrandVectors.DEEPSEEK
        ModelBrand.QWEN -> ModelBrandVectors.QWEN
        ModelBrand.KIMI -> ModelBrandVectors.KIMI
        ModelBrand.GROK -> ModelBrandVectors.GROK
        ModelBrand.ZHIPU -> ModelBrandVectors.ZHIPU
        ModelBrand.DOUBAO -> ModelBrandVectors.DOUBAO
        ModelBrand.MINIMAX -> ModelBrandVectors.MINIMAX
        null -> DaiyuIcons.Model
    }
    Icon(vector, null, modifier.semantics { this[ModelBrandIdentity] = brand?.name ?: "GENERIC" },
        tint = if (brand == null || brand.monochrome) tint else Color.Unspecified)
}
