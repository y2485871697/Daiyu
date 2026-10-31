package app.daiyu.android.model

enum class DaiyuPromptCacheRetention(val wireValue: String) {
    NONE("none"),
    FIVE_MINUTES("5m"),
    ONE_HOUR("1h");

    companion object {
        const val STORAGE_KEY = "Daiyu_prompt_cache"

        internal fun decode(value: Any?): DaiyuPromptCacheRetention =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("缓存保留时长无效")
    }
}
