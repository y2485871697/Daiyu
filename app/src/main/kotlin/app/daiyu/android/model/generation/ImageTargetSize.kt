package app.daiyu.android.model.generation

import java.math.BigDecimal

/** Client-side size policy, not a claim about any provider's native resolution tiers. */
internal object ImageTargetSize {
    private val edges = mapOf("1k" to 1024, "1.5k" to 1536, "2k" to 2048, "3k" to 3072, "4k" to 4096)

    fun resolve(ratio: String?, resolution: String?): String {
        if (ratio == null || ratio == "auto" || resolution == null)
            ImageGenerationOptions.invalid("像素尺寸协议需要明确比例和分辨率档位，或直接指定 size。")
        val edge = edges[ImageResolutionTier.legacy(resolution)] ?: ImageGenerationOptions.invalid("未配置该分辨率档位的像素映射，请指定 size 或 sizes。")
        val parts = ratio.split(':').map(::BigDecimal)
        val scale = maxOf(parts[0].scale(), parts[1].scale(), 0)
        val w = parts[0].movePointRight(scale).toBigIntegerExact()
        val h = parts[1].movePointRight(scale).toBigIntegerExact()
        val gcd = w.gcd(h)
        val unitWidth = (w / gcd).intValueExact(); val unitHeight = (h / gcd).intValueExact()
        // A K tier is a long-edge budget, not a promise of exactly that many pixels.
        // 21:9 + 2k => 2044x876, keeping 21:9 exactly instead of snapping the ratio.
        val multiplier = edge / maxOf(unitWidth, unitHeight)
        if (multiplier < 1) ImageGenerationOptions.invalid("该比例在当前档位下无法得到整数像素尺寸，请指定 size 或 sizes。")
        val width = unitWidth * multiplier
        val height = unitHeight * multiplier
        if (minOf(width, height) < 64) ImageGenerationOptions.invalid("计算后的短边不足 64 像素，请指定明确 size。")
        return "${width}x$height"
    }
}
