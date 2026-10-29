package app.daiyu.android.domain.tools

enum class ToolFailureStage(val code: String) {
    APPROVAL("write_approval_failed"), ADMISSION("context_admission_failed"),
    ADAPTER("adapter_result_unconfirmed"), BATCH("receipt_batch_unconfirmed"),
    SHIZUKU_PREFLIGHT("shizuku_preflight_failed"), SHIZUKU_PREPARE("shizuku_prepare_failed"),
    SHIZUKU_DISPATCH("shizuku_dispatch_unconfirmed"), SHIZUKU_POLL("shizuku_poll_unconfirmed"),
    SHIZUKU_RESULT("shizuku_result_unconfirmed")
}

private val failureCategories = setOf("interrupted", "security", "invalid_argument", "invalid_state", "io", "exception")

fun toolFailureCode(stage: ToolFailureStage, failure: Exception): String = stage.code + "_" + when (failure) {
    is InterruptedException -> "interrupted"
    is SecurityException -> "security"
    is IllegalArgumentException -> "invalid_argument"
    is IllegalStateException -> "invalid_state"
    is java.io.IOException -> "io"
    else -> "exception"
}

fun toolFailureDiagnostic(code: String): String? = code.takeIf { value ->
    ToolFailureStage.entries.any { stage -> failureCategories.any { value == "${stage.code}_$it" } } ||
        value in setOf("receipt_persistence_failed", "adapter_result_unconfirmed", "shizuku_execution_interrupted",
            "shizuku_execution_unconfirmed", "shizuku_dispatch_identity_mismatch", "shizuku_invalid_output_page", "shizuku_output_incomplete")
}
