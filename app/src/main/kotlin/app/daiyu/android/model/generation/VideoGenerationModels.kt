package app.daiyu.android.model.generation

/**
 * 识别「专门生视频」模型：走独立视频接口，不进 Agent 工具循环。
 *
 * 视频理解对话模型（input 含 video 的 kimi / step 等）不含这些标记，仍走普通聊天。
 */
internal object VideoGenerationModels {
    private val ID_MARKERS = listOf(
        "veo-",
        "veo3",
        "sora-",
        "sora2",
        "seedance",
        "kling-video",
        "runway",
        "hailuo",
        "cogvideox",
        "hunyuan-video",
        "hunyuanvideo",
        "luma-video",
        "dream-machine",
        "grok-video",
        "grok-imagine-video",
        "imagine-video",
        "minimax-video",
        "ltx-video",
        "vidu-",
        "pika-",
        "gen-3-alpha",
        "gen3a",
        "-t2v",
        "_t2v",
        "-i2v",
        "_i2v",
        "t2v-",
        "i2v-",
    )


    fun matches(modelId: String, outputModalities: List<String> = emptyList()): Boolean {
        val id = modelId.lowercase()
        if (id.isBlank()) return false
        val outputs = outputModalities.map { it.lowercase() }
        if (outputs.any { it == "video" } && outputs.none { it == "text" }) {
            return true
        }
        val base = id.substringAfterLast('/')
        if (
            base == "sora" ||
            base.startsWith("sora-") ||
            base.startsWith("sora2") ||
            base.startsWith("kling") ||
            base.startsWith("vidu")
        ) {
            return true
        }
        return ID_MARKERS.any { it in id }
    }
}
