package app.daiyu.android.browser.openminis

import app.daiyu.android.browser.bridge.BrowserCommand
import com.openminis.app.browser.BrowserActionResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BrowserOutputTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun command(action: String, vararg args: Pair<String, Any?>) = BrowserCommand(action, 0, mapOf(*args))
    private fun result(command: BrowserCommand, text: String) = OpenMinisCommandMapper.result(command, BrowserActionResult(text))
    private fun frame(total: Int, offset: Int, page: String): String = BrowserOutputBody.PREFIX + "$total\n$offset\n${page.length}\n$page"
    private fun rejected(command: BrowserCommand, answer: BrowserActionResult) {
        try { OpenMinisCommandMapper.result(command, answer); fail("must not publish") }
        catch (failure: Exception) {
            assertTrue(failure is IllegalArgumentException || failure is IllegalStateException)
            assertFalse(failure.message.orEmpty().contains("PRIVATE_SENTINEL"))
        }
    }
    private fun jpeg(name: String = "screenshot_123.jpg"): File {
        val directory = File(temporary.root, "browser_screenshots").also { it.mkdirs() }
        return File(directory, name).also { it.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())) }
    }

    @Test fun realScreenshotReturnsVerifiedFilePathAndNeverBase64() {
        val file = jpeg()
        val output = OpenMinisCommandMapper.result(command("screenshot", "full_page" to true), BrowserActionResult(
            text = "Screenshot 1280x800\nFull page: true", base64Image = "PRIVATE_SENTINEL".repeat(10000),
            imageFilePath = file.absolutePath,
        ))
        assertEquals(file.absolutePath, output.filePath)
        assertEquals("Screenshot 1280x800\nFull page: true", output.text)
        assertFalse(output.text.contains("PRIVATE_SENTINEL"))
    }

    @Test fun screenshotWithoutActualFileOrWithInventedAttachmentIsRejected() {
        rejected(command("screenshot"), BrowserActionResult("ok", base64Image = "PRIVATE_SENTINEL"))
        for (path in listOf("PRIVATE_SENTINEL", "file:///tmp/image.jpg", "/missing/browser_screenshots/screenshot_123.jpg")) {
            rejected(command("screenshot"), BrowserActionResult("ok", imageFilePath = path))
        }
        val invalid = jpeg("screenshot_555.jpg").also { it.writeText("PRIVATE_SENTINEL") }
        rejected(command("screenshot"), BrowserActionResult("ok", imageFilePath = invalid.absolutePath))
        val wrongName = jpeg("secret.txt")
        rejected(command("screenshot"), BrowserActionResult("ok", imageFilePath = wrongName.absolutePath))
        val valid = jpeg()
        rejected(command("screenshot"), BrowserActionResult("ok", imageFilePath = valid.parent + "/../browser_screenshots/" + valid.name))
    }

    @Test fun autoSnapshotIsAlsoAFileAndSharesTheSingleTotalBudget() {
        val file = jpeg("snapshot_123.jpg")
        val output = OpenMinisCommandMapper.result(command("click", "selector" to "button"), BrowserActionResult(
            "Clicked <BUTTON>\n" + "x".repeat(64000), imageFilePath = file.absolutePath,
        ))
        assertEquals(file.absolutePath, output.filePath)
        assertTrue(output.text.endsWith("[BROWSER_OUTPUT_TRUNCATED]"))
        assertTrue(output.text.length + output.filePath!!.length <= BrowserOutput.MAX_OUTPUT_CHARS)
    }

    @Test fun errorsDownloadsCookiesAndEncodedImagesCannotBecomeConfirmedShapedValues() {
        val page = command("get_page_info")
        rejected(page, BrowserActionResult.error("PRIVATE_SENTINEL"))
        rejected(page, BrowserActionResult("Error: PRIVATE_SENTINEL", success = true))
        rejected(command("wait_for_dom_stable"), BrowserActionResult("DOM did not stabilize within 5000ms"))
        rejected(page, BrowserActionResult("PRIVATE_SENTINEL", fetchedFileData = byteArrayOf(1)))
        rejected(page, BrowserActionResult("PRIVATE_SENTINEL", fetchedFileName = "secret.txt"))
        for (action in listOf("fetch", "get_cookies", "set_cookies")) {
            rejected(command(action), BrowserActionResult("PRIVATE_SENTINEL"))
        }
        rejected(page, BrowserActionResult("PRIVATE_SENTINEL", base64Image = "PRIVATE_SENTINEL"))
        rejected(page, BrowserActionResult("data:image/jpeg;base64,PRIVATE_SENTINEL"))
    }

    @Test fun bodyOffsetAndLimitApplyToBodyNotUpstreamTitleOrWrapper() {
        val cmd = command("get_text", "offset" to 10001, "max_chars" to 4)
        val output = result(cmd, frame(20_000, 10001, "body"))
        assertEquals("body\n[body offset=10001 returned=4 total=20000 next_offset=10005; UTF-16 chars]", output.text)
        assertNull(output.filePath)
        assertFalse(output.text.contains("Title:"))
        val readable = result(command("get_readable", "offset" to 2, "max_chars" to 2), frame(4, 2, "cd"))
        assertTrue(readable.text.startsWith("cd\n"))
        assertTrue(readable.text.contains("next_offset=none"))
    }

    @Test fun bodyEmptyAndPastEndAreWellDefined() {
        assertEquals("\n[body offset=0 returned=0 total=0 next_offset=none; UTF-16 chars]",
            result(command("get_text"), frame(0, 0, "")).text)
        val cmd = command("get_readable", "offset" to 1_000_000, "max_chars" to 1)
        assertTrue(result(cmd, frame(3, 1_000_000, "")).text.contains("returned=0 total=3 next_offset=none"))
        assertTrue(result(command("get_text", "max_chars" to 1), frame(2, 0, "a")).text.startsWith("a\n"))
    }

    @Test fun bodyLargePageRemainsWithinTotalBudgetAndHasAccurateContinuation() {
        val cmd = command("get_readable", "max_chars" to 64000)
        val page = "x".repeat(BrowserOutputBody.limit(cmd))
        val output = result(cmd, frame(100_000, 0, page))
        assertTrue(output.text.length <= BrowserOutput.MAX_OUTPUT_CHARS)
        assertTrue(output.text.startsWith(page))
        assertTrue(output.text.contains("next_offset=${page.length}"))
        assertFalse(output.text.contains("BROWSER_OUTPUT_TRUNCATED"))
    }

    @Test fun malformedOrOverBudgetBodyFramesFailClosedRatherThanTruncate() {
        val cmd = command("get_text", "offset" to 1, "max_chars" to 2)
        for (text in listOf("Title: fake\nText (100 chars):\nPRIVATE_SENTINEL", frame(3, 0, "ab"),
            frame(3, 1, "abc"), frame(3, 1, "a"), frame(3, 1, "ab") + "PRIVATE_SENTINEL",
            BrowserOutputBody.PREFIX + "-1\n1\n0\n", BrowserOutputBody.PREFIX + "2147483648\n1\n0\n",
            BrowserOutputBody.PREFIX + "03\n1\n2\nab")) {
            rejected(cmd, BrowserActionResult(text))
        }
        val file = jpeg()
        rejected(cmd, BrowserActionResult(frame(3, 1, "ab"), imageFilePath = file.absolutePath))
    }

    @Test fun plainResultsAreBoundedAndDoNotSplitSurrogatePairsAtTruncation() {
        val marker = "\n[BROWSER_OUTPUT_TRUNCATED]"
        val prefixLength = BrowserOutput.MAX_OUTPUT_CHARS - marker.length - 1
        val output = result(command("execute_js", "script" to "return 'x'"),
            "x".repeat(prefixLength) + "\uD83D\uDE00" + "z".repeat(100))
        assertEquals("x".repeat(prefixLength) + marker, output.text)
        assertTrue(output.text.length <= BrowserOutput.MAX_OUTPUT_CHARS)
        assertEquals("ok", result(command("get_page_info"), "ok").text)
    }

    @Test fun unsupportedAcceptedModesCannotBypassMapViaResultSeam() {
        rejected(command("type", "selector" to "input", "text" to "x", "submit" to true), BrowserActionResult("Typed"))
        rejected(command("navigate", "url" to "https://example.com", "timeout_ms" to 100), BrowserActionResult("Navigated"))
        rejected(command("screenshot", "read_image" to true), BrowserActionResult("ok", imageFilePath = jpeg().absolutePath))
        // Dedicated host methods may publish through the SAME budget/error seam after native dispatch.
        assertEquals("Reloaded", result(command("reload"), "Reloaded").text)
        assertEquals("Viewport changed", result(command("set_viewport", "viewport_width" to 800,
            "viewport_height" to 600), "Viewport changed").text)
    }
}
