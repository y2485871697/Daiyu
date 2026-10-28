package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownBlock

internal fun markdownCodeProducing(block: MarkdownBlock.Code, visibleCharacters: Int, generationComplete: Boolean): Boolean =
    visibleCharacters < block.text.length || (!block.closed && !generationComplete)

internal fun markdownCodeDisplayText(block: MarkdownBlock.Code, visibleText: String): String =
    if (visibleText.length == block.text.length) visibleText.removeSuffix("\n") else visibleText

internal fun markdownCodeFileName(language: String): String {
    val extension = when (language.lowercase(java.util.Locale.ROOT)) {
        "sh", "bash", "shell", "zsh" -> "sh"
        "python", "py" -> "py"
        "javascript", "js" -> "js"
        "typescript", "ts" -> "ts"
        "kotlin", "kt" -> "kt"
        "java", "json", "xml", "html", "css", "sql", "c", "cpp", "go", "rs", "swift", "rb", "php" -> language.lowercase(java.util.Locale.ROOT)
        "yaml", "yml" -> "yaml"
        "powershell", "ps1" -> "ps1"
        else -> "txt"
    }
    return "code.$extension"
}
