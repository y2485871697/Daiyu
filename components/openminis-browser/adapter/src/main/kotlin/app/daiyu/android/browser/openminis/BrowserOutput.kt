package app.daiyu.android.browser.openminis

import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import com.openminis.app.browser.BrowserActionResult
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** One budget for ALL returned string fields, including diagnostics and a real screenshot path. */
internal object BrowserOutput {
    const val MAX_OUTPUT_CHARS = 16_000
    private const val TRUNCATED = "\n[BROWSER_OUTPUT_TRUNCATED]"
    private val imageName = Regex("(?:screenshot|snapshot)_[0-9]+\\.jpg")
    private val errorResult = Regex("^\\s*(?:Error:|DOM did not stabilize within)", RegexOption.IGNORE_CASE)

    fun convert(command: BrowserCommand, answer: BrowserActionResult): BrowserExecutionResult {
        // Never include an upstream failure message: it can contain URLs, script arguments or secrets.
        check(answer.success && !errorResult.containsMatchIn(answer.text)) { "BROWSER_OPENMINIS_ACTION_FAILED" }
        check(answer.fetchedFileData == null && answer.fetchedFileName == null) {
            "BROWSER_OPENMINIS_UNEXPECTED_DOWNLOAD"
        }
        check(command.action !in setOf("fetch", "get_cookies", "set_cookies")) {
            "BROWSER_OPENMINIS_DEDICATED_CAPABILITY_REQUIRED"
        }
        // base64Image is intentionally NOT transferred. Defend against it being echoed in text too.
        val encodedImage = answer.base64Image
        check(encodedImage.isNullOrEmpty() || !answer.text.contains(encodedImage)) {
            "BROWSER_OPENMINIS_ENCODED_IMAGE_IN_TEXT"
        }
        check(!answer.text.contains("data:image/", ignoreCase = true)) { "BROWSER_OPENMINIS_ENCODED_IMAGE_IN_TEXT" }
        val file = answer.imageFilePath?.let(::imagePath)
        check(command.action != "screenshot" || file != null) { "BROWSER_OPENMINIS_SCREENSHOT_FILE_REQUIRED" }
        val budget = MAX_OUTPUT_CHARS - (file?.length ?: 0)
        val text = if (command.action in setOf("get_text", "get_readable")) {
            // Body results have no snapshot. Do not truncate a validated page or its continuation marker.
            check(file == null) { "BROWSER_OPENMINIS_BODY_RESULT_INVALID" }
            BrowserOutputBody.convert(command, answer.text).also {
                check(it.length <= budget) { "BROWSER_OPENMINIS_OUTPUT_BUDGET" }
            }
        } else bounded(answer.text, budget)
        return BrowserExecutionResult(text, file)
    }

    private fun bounded(value: String, budget: Int): String {
        check(budget >= TRUNCATED.length) { "BROWSER_OPENMINIS_OUTPUT_BUDGET" }
        if (value.length <= budget) return value
        var end = budget - TRUNCATED.length
        if (end > 0 && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
        return value.substring(0, end) + TRUNCATED
    }

    private fun imagePath(path: String): String {
        check(path.length in 1..4096 && !path.any { it.code < 32 }) { "BROWSER_OPENMINIS_IMAGE_PATH_INVALID" }
        val file = File(path)
        // Exact pinned upstream directory/name, not a file URI, base64 blob or invented attachment.
        check(file.isAbsolute && file.parentFile?.name == "browser_screenshots" && imageName.matches(file.name)) {
            "BROWSER_OPENMINIS_IMAGE_PATH_INVALID"
        }
        check(file.canonicalPath == file.absolutePath && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
            && file.canRead() && file.length() >= 4) { "BROWSER_OPENMINIS_IMAGE_FILE_MISSING" }
        // A path alone is not evidence that capture wrote a JPEG. Never return arbitrary local text.
        file.inputStream().use { stream ->
            check(stream.read() == 0xff && stream.read() == 0xd8 && stream.read() == 0xff) {
                "BROWSER_OPENMINIS_IMAGE_FILE_INVALID"
            }
        }
        return file.absolutePath
    }
}
