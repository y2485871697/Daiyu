package app.daiyu.android.browser

import android.graphics.BitmapFactory
import android.os.Looper
import android.test.InstrumentationTestCase
import android.webkit.WebResourceResponse
import app.daiyu.android.browser.bridge.BrowserOutcome
import app.daiyu.android.browser.openminis.AndroidBrowserRunHost
import app.daiyu.android.browser.openminis.OpenMinisHistoryApproval
import app.daiyu.android.browser.session.BrowserAccess
import app.daiyu.android.browser.session.BrowserGrant
import app.daiyu.android.browser.session.BrowserOwner
import app.daiyu.android.browser.session.BrowserOwnerKind
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Real Android/WebView checks; host JVM typechecking is not execution of these tests. */
@Suppress("DEPRECATION")
class BrowserProductionCompositionTest : InstrumentationTestCase() {
    private val roots = mutableListOf<Path>()
    private val hosts = mutableListOf<AndroidBrowserRunHost>()
    private val screenshots = mutableListOf<Path>()
    private val historyApprovals = mutableListOf<String>()

    private fun <T> main(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    private fun host(access: BrowserAccess = BrowserAccess.FULL,
                     kind: BrowserOwnerKind = BrowserOwnerKind.MAIN): AndroidBrowserRunHost {
        val context = instrumentation.targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "browser-composition-")
        roots.add(root)
        val owner = BrowserOwner("instrumentation-${UUID.randomUUID()}", kind)
        val html = "<html><head><title>Browser fixture</title></head><body>0123456789abcdefghijklmnop</body></html>"
        return main {
            AndroidBrowserRunHost(context, BrowserGrant(owner, access), root,
                cookieEnvPath = { _, file -> file.toString() },
                cookieAuthorizeName = { _, origin, name ->
                    origin == "https://browser-fixture.invalid/" && name == "daiyu_fixture"
                },
                historyApproval = OpenMinisHistoryApproval { _, url, _ -> historyApprovals.add(url); true },
                requestOverride = { request ->
                    check(request.url.host == "browser-fixture.invalid")
                    WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                },
            )
        }.also { hosts.add(it) }
    }

    private fun confirmed(host: AndroidBrowserRunHost, vararg args: Pair<String, Any?>): BrowserOutcome.Confirmed {
        assertFalse("execute must never wait on main Looper", Looper.myLooper() == Looper.getMainLooper())
        val outcome = host.execute(mapOf(*args))
        assertTrue("Expected Confirmed, got $outcome", outcome is BrowserOutcome.Confirmed)
        return outcome as BrowserOutcome.Confirmed
    }

    fun testRealNavigationBodyPagingAndJpeg() {
        val host = host()
        confirmed(host, "action" to "new_tab")
        confirmed(host, "action" to "navigate", "url" to "https://browser-fixture.invalid/body")
        val body = confirmed(host, "action" to "get_text", "selector" to "body",
            "offset" to 1, "max_chars" to 5).result
        assertTrue(body.text.contains("12345"))
        assertFalse(body.text.contains("0123456789abcdefghijklmnop"))
        assertNull(body.filePath)
        val capture = confirmed(host, "action" to "screenshot").result
        val file = java.nio.file.Paths.get(requireNotNull(capture.filePath))
        screenshots.add(file)
        assertTrue(Files.isRegularFile(file))
        assertEquals(file.toRealPath().toString(), file.toString())
        val bitmap = BitmapFactory.decodeFile(file.toString())
        assertNotNull("capture must be a decodable image, not just an invented path", bitmap)
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        bitmap.recycle()
        assertTrue(capture.text.length + file.toString().length <= 16_000)
    }

    fun testUnsupportedModesDoNotRetireLoadedPage() {
        val host = host()
        confirmed(host, "action" to "new_tab")
        confirmed(host, "action" to "navigate", "url" to "https://browser-fixture.invalid/preflight")
        val lease = requireNotNull(host.currentTab)
        val rejected = listOf(
            mapOf("action" to "navigate", "url" to "https://browser-fixture.invalid/other", "timeout_ms" to 1000),
            mapOf("action" to "set_viewport", "viewport_width" to 800, "viewport_height" to 600),
            mapOf("action" to "type", "selector" to "body", "text" to "test", "submit" to true),
            mapOf("action" to "screenshot", "read_image" to true),
            mapOf("action" to "set_user_agent", "user_agent" to "mobile_chrome"),
        )
        for (arguments in rejected) {
            assertTrue("unsupported mode must reject before WebView entry",
                host.execute(arguments) is BrowserOutcome.NotExecuted)
            assertEquals(lease, host.currentTab)
            val body = confirmed(host, "action" to "get_text", "selector" to "body").result.text
            assertTrue("original page must remain usable", body.contains("0123456789abcdefghijklmnop"))
        }
        assertEquals("https://browser-fixture.invalid/preflight", main { host.webHost.currentUrl(lease) })
    }

    fun testCookieSummaryPrivateOffloadAndCloseCleanup() {
        val host = host()
        confirmed(host, "action" to "new_tab")
        confirmed(host, "action" to "navigate", "url" to "https://browser-fixture.invalid/cookies")
        val secret = "fixture_${UUID.randomUUID().toString().replace("-", "")}" // only test-owned data
        val written = confirmed(host, "action" to "set_cookies", "cookies" to
            "[{\"name\":\"daiyu_fixture\",\"value\":\"$secret\",\"secure\":true}]").result
        assertFalse(written.text.contains(secret))
        val summary = confirmed(host, "action" to "get_cookies", "keywords" to listOf("daiyu_fixture")).result
        assertFalse(summary.text.contains(secret))
        val file = java.nio.file.Paths.get(requireNotNull(summary.filePath))
        assertTrue(Files.readAllBytes(file).toString(Charsets.UTF_8).contains(secret))
        assertTrue(Files.getPosixFilePermissions(file) == java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        confirmed(host, "action" to "set_cookies", "cookies" to
            "[{\"name\":\"daiyu_fixture\",\"value\":\"\",\"secure\":true,\"expires\":0}]")
        host.close()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse("close must retire the actual offload", Files.exists(file))
        assertTrue(host.execute(mapOf("action" to "list_tabs")) is BrowserOutcome.NotExecuted)
    }

    fun testFrozenReadOnlyAndIndependentChildResourceOwners() {
        val parent = host(BrowserAccess.READ_ONLY)
        val child = host(BrowserAccess.READ_ONLY, BrowserOwnerKind.CHILD)
        confirmed(parent, "action" to "new_tab")
        confirmed(child, "action" to "new_tab")
        assertEquals(0, parent.currentTab!!.tabId)
        assertEquals(0, child.currentTab!!.tabId)
        assertFalse(parent.currentTab!!.owner == child.currentTab!!.owner)
        assertTrue(parent.execute(mapOf("action" to "fetch", "url" to "https://browser-fixture.invalid/file"))
            is BrowserOutcome.NotExecuted)
        assertTrue(parent.execute(mapOf("action" to "execute_js", "script" to "1")) is BrowserOutcome.NotExecuted)
        assertTrue(parent.execute(mapOf("action" to "list_tabs", "owner" to "forged")) is BrowserOutcome.NotExecuted)
        parent.close()
        confirmed(child, "action" to "list_tabs")
    }

    fun testUrlResolverUsesWholeLeaseAndRejectsWorkerThread() {
        val host = host()
        confirmed(host, "action" to "new_tab")
        val lease = requireNotNull(host.currentTab)
        try {
            host.webHost.currentUrl(lease)
            fail("worker must not read WebView URL")
        } catch (failure: IllegalStateException) {
            assertEquals("BROWSER_DISPATCH_NOT_UI", failure.message)
        }
        confirmed(host, "action" to "navigate", "url" to "https://browser-fixture.invalid/lease")
        assertEquals("https://browser-fixture.invalid/lease", main { host.webHost.currentUrl(lease) })
        confirmed(host, "action" to "close_tab")
        confirmed(host, "action" to "new_tab")
        assertEquals(lease.tabId, host.currentTab!!.tabId)
        assertFalse(lease == host.currentTab)
        assertNull(main { host.webHost.currentUrl(lease) })
        host.close()
        assertNull(main { host.webHost.currentUrl(requireNotNull(host.currentTab ?: lease)) })
    }

    fun testVisibleTakeoverUsesOriginalViewAndManualFinishNeverApprovesHistory() {
        val host = host()
        confirmed(host, "action" to "new_tab")
        confirmed(host, "action" to "navigate", "url" to "https://browser-fixture.invalid/model-original")
        val original = requireNotNull(host.currentTab)
        val before = main { historyApprovals.size }
        val token = main { host.session.takeover(original) }
        val parent = main { android.widget.FrameLayout(instrumentation.targetContext) }
        val attachment = main { host.webHost.attach(token, parent) }
        val view = main { parent.getChildAt(0) as android.webkit.WebView }
        main {
            assertEquals("https://browser-fixture.invalid/model-original", view.url)
            try { host.webHost.attach(token, android.widget.FrameLayout(instrumentation.targetContext)); fail("double attachment") }
            catch (_: IllegalStateException) { }
            try { host.session.releaseTakeover(token); fail("attached release") }
            catch (_: IllegalStateException) { }
            assertSame(view, parent.getChildAt(0))
            attachment.navigate("https://browser-fixture.invalid/manual-original")
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (main { attachment.isLoading } && System.nanoTime() < deadline) Thread.sleep(10)
        main {
            assertFalse("manual page must reach the real finish gate", attachment.isLoading)
            assertEquals(before, historyApprovals.size)
            assertEquals("https://browser-fixture.invalid/manual-original", view.url)
            attachment.close()
            assertEquals(0, parent.childCount)
            val resumed = host.session.releaseTakeover(token)
            val secondToken = host.session.takeover(resumed)
            val secondParent = android.widget.FrameLayout(instrumentation.targetContext)
            val second = host.webHost.attach(secondToken, secondParent)
            assertSame(view, secondParent.getChildAt(0))
            attachment.close()
            assertSame(view, secondParent.getChildAt(0))
            try { attachment.navigate("https://browser-fixture.invalid/stale"); fail("closed attachment") }
            catch (_: IllegalStateException) { }
            second.close()
            host.session.releaseTakeover(secondToken)
        }
        confirmed(host, "action" to "navigate", "url" to "https://browser-fixture.invalid/model-after-human")
    }

    fun testRetiredManualAttachmentCannotNavigateBeforeNativeCleanupRuns() {
        for (cancel in listOf(false, true)) {
            val host = host()
            confirmed(host, "action" to "new_tab")
            confirmed(host, "action" to "navigate", "url" to "https://browser-fixture.invalid/retirement")
            main {
                val token = host.session.takeover(requireNotNull(host.currentTab))
                val parent = android.widget.FrameLayout(instrumentation.targetContext)
                host.webHost.attach(token, parent)
                val view = parent.getChildAt(0) as android.webkit.WebView
                if (cancel) host.webHost.cancel(token.lease) else host.webHost.closeWithResult()
                // Still on the same Looper stack: queued destroy has not run yet.
                @Suppress("DEPRECATION")
                assertTrue(view.webViewClient.shouldOverrideUrlLoading(view,
                    "https://browser-fixture.invalid/rejected-after-retirement"))
            }
        }
    }

    fun testExplicitCloseAcceptsLoadingVisibleTabWithoutReturningItToModel() {
        val host = host()
        confirmed(host, "action" to "new_tab")
        main {
            val token = host.session.takeover(requireNotNull(host.currentTab))
            val parent = android.widget.FrameLayout(instrumentation.targetContext)
            val attachment = host.webHost.attach(token, parent)
            attachment.navigate("https://browser-fixture.invalid/close-during-load")
            assertTrue(attachment.isLoading)
            host.session.closeTab(token)
            attachment.close()
            assertNull(host.currentTab)
            assertEquals(0, parent.childCount)
        }
    }

    override fun tearDown() {
        try {
            hosts.forEach { it.close() }
            main { Unit } // drain previously posted WebView cleanup without blocking the main Looper
            screenshots.forEach { Files.deleteIfExists(it) }
            roots.forEach { root ->
                Files.walk(root).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        } finally { super.tearDown() }
    }
}
