package app.daiyu.android

internal data class GenerationMediaKey(val source: String, val itemId: String, val outputIndex: Int) {
    companion object {
        fun parse(raw: String?): GenerationMediaKey? {
            val match = raw?.let { Regex("(live|history|static):([A-Za-z0-9_-]{1,128}):(0|[1-9][0-9]?)").matchEntire(it) }
                ?: return null
            val index = match.groupValues[3].toInt()
            if (index !in 0..31) return null
            return GenerationMediaKey(match.groupValues[1], match.groupValues[2], index)
        }
    }
}

internal class GenerationMediaOriginal(
    val metadata: ControllerGenerationOutputMetadata,
    val stream: java.io.FileInputStream,
    private val active: java.util.concurrent.atomic.AtomicBoolean,
) : AutoCloseable {
    fun requireActive() { check(active.get()) { "generation_media_visit_expired" } }
    override fun close() { stream.close() }
}
