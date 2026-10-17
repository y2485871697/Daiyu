package app.daiyu.android.balance

/** Preserved from frozen f001fb82 BalanceOption; credentials live only in encrypted P05 storage. */
data class BalanceOption(
    val enabled: Boolean = false,
    val preset: String = PRESET_CUSTOM,
    val apiPath: String = "",
    val resultPath: String = "",
    val userId: String = "",
    val accessToken: String = "",
) {
    fun resolved(): BalanceOption = when (preset) {
        PRESET_NEW_API -> copy(apiPath = NEW_API_PATH, resultPath = NEW_API_RESULT_PATH)
        // Filling templates remain editable; only blank fields fall back to the template.
        PRESET_SUB2API -> copy(apiPath = apiPath.ifBlank { SUB2API_PATH }, resultPath = resultPath.ifBlank { SUB2API_RESULT_PATH })
        PRESET_DEEPSEEK -> copy(apiPath = apiPath.ifBlank { DEEPSEEK_PATH }, resultPath = resultPath.ifBlank { DEEPSEEK_RESULT_PATH })
        else -> this
    }

    override fun toString() = "BalanceOption(<redacted>)"

    companion object {
        const val PRESET_CUSTOM = "custom"
        const val PRESET_NEW_API = "new_api"
        const val PRESET_SUB2API = "sub2api"
        const val PRESET_DEEPSEEK = "deepseek"
        const val NEW_API_PATH = "api/user/self"
        const val NEW_API_RESULT_PATH = "data.quota / 500000"
        const val SUB2API_PATH = "/usage"
        const val SUB2API_RESULT_PATH = "remaining"
        const val DEEPSEEK_PATH = "/user/balance"
        const val DEEPSEEK_RESULT_PATH = "balance_infos[0].total_balance"

        fun applyPreset(preset: String, current: BalanceOption): BalanceOption = when (preset) {
            PRESET_NEW_API -> current.copy(preset = PRESET_NEW_API)
            PRESET_SUB2API -> current.copy(preset = PRESET_SUB2API, apiPath = SUB2API_PATH, resultPath = SUB2API_RESULT_PATH)
            PRESET_DEEPSEEK -> current.copy(preset = PRESET_DEEPSEEK, apiPath = DEEPSEEK_PATH, resultPath = DEEPSEEK_RESULT_PATH)
            else -> current.copy(
                preset = PRESET_CUSTOM,
                apiPath = if (current.preset == PRESET_NEW_API && current.apiPath == NEW_API_PATH) "" else current.apiPath,
                resultPath = if (current.preset == PRESET_NEW_API && current.resultPath == NEW_API_RESULT_PATH) "" else current.resultPath,
            )
        }
    }
}
