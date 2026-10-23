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
                     kind: BrowserOwnerKind = BrowserOwnerKind.MAIN,
                     mainFrameRequests: java.util.concurrent.atomic.AtomicInteger? = null): AndroidBrowserRunHost {
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
                    val marker = if (request.isForMainFrame) mainFrameRequests?.incrementAndGet() else null
                    val response = if (marker == null) html else html.replace("<title>Browser fixture</title>",
                        "<title>Browser fixture $marker</title>")
                    WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(response.toByteArray()))
                },
            )
        }.also { hosts.add(it) }
    }

    private fun confirmed(host: AndroidBrowserRunHost, vararg args: Pair<String, Any?>): BrowserOutcome.Confirmed {
        assertFalse("execute must never wait on main Looper", Looper.myLooper() == Looper.getMainLooper())
        val outcome = host.execute(mapOf(*args))
        assertTrue("Expected Confirmed action=${mapOf(*args)["action"]}, got $outcome", outcome is BrowserOutcome.Confirmed)
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
        val original = Files.readAllBytes(file)
        val second = java.nio.file.Paths.get(requireNotNull(confirmed(host, "action" to "screenshot").result.filePath))
        screenshots.add(second)
        assertFalse("each capture must own a distinct cache file", file == second)
        assertTrue(original.contentEquals(Files.readAllBytes(file)))
        assertTrue(capture.text.length + file.toString().length <= 16_000)
    }

    fun testUnsupportedModesDoNotRetireLoadedPage() {
        val host = host()
        confirmed(host, "action" to "new_tab")
        confirmed(host, "action" to "navigate", "url" to "https://browser-fixture.invalid/preflight")
        val lease = requireNotNull(host.currentTab)
        val rejected = listOf(
            mapOf("action" to "navigate", "url" to "https://browser-fixture.invalid/other", "timeout_ms" to 1000),
            mapOf("action" to "set_viewport", "viewport_width" to 800),
            mapOf("action" to "type", "selector" to "body", "text" to "test", "submit" to true),
            mapOf("action" to "screenshot", "read_image" to true),
            mapOf("action" to "set_user_agent", "user_agent" to "forged"),
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

    fun testNativeHistoryAndConfigurationUseTheLoadedResource() {
        val host = host()
        confirmed(host, "action" to "new_tab")
        val first = "https://browser-fixture.invalid/native-first"
        val second = "https://browser-fixture.invalid/native-second"
        confirmed(host, "action" to "navigate", "url" to first)
        confirmed(host, "action" to "navigate", "url" to second)
        val original = requireNotNull(host.currentTab)
        confirmed(host, "action" to "go_back")
        assertEquals(first, main { host.webHost.currentUrl(original) })
        assertTrue(confirmed(host, "action" to "get_page_info").result.text.contains(first))
        val backScreenshot = confirmed(host, "action" to "screenshot").result
        screenshots.add(java.nio.file.Paths.get(requireNotNull(backScreenshot.filePath)))
        assertTrue(backScreenshot.text.lineSequence().any { it.trim() == "URL: $first" })
        confirmed(host, "action" to "go_forward")
        assertEquals(second, main { host.webHost.currentUrl(original) })
        confirmed(host, "action" to "reload")
        confirmed(host, "action" to "set_user_agent", "user_agent" to "desktop_chrome")
        confirmed(host, "action" to "set_viewport", "viewport_width" to 800, "viewport_height" to 600)
        val token = main { host.session.takeover(original) }
        main {
            val parent = android.widget.FrameLayout(instrumentation.targetContext)
            val attachment = host.webHost.attach(token, parent)
            val view = parent.getChildAt(0) as android.webkit.WebView
            assertEquals(com.openminis.app.browser.UserAgentProfile.DESKTOP_CHROME.userAgentString,
                view.settings.userAgentString)
            assertEquals(second, attachment.page().url)
            attachment.close()
            host.session.releaseTakeover(token)
        }
        assertTrue(confirmed(host, "action" to "get_text", "selector" to "body")
            .result.text.contains("0123456789abcdefghijklmnop"))
    }

    fun testBlankVisibleTabMountsOnlyAfterNavigationAndSurvivesLateDetachedCallback() {
        val host = host()
        confirmed(host, "action" to "new_tab")
        val token = main { host.session.takeover(requireNotNull(host.currentTab)) }
        val parent = main { android.widget.FrameLayout(instrumentation.targetContext) }
        val attachment = main { host.webHost.attach(token, parent) }
        main {
            assertEquals(0, parent.childCount)
            attachment.navigate("https://browser-fixture.invalid/blank-first-load")
            assertEquals(1, parent.childCount)
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (main { attachment.isLoading } && System.nanoTime() < deadline) Thread.sleep(10)
        main {
            assertFalse(attachment.isLoading)
            val view = parent.getChildAt(0) as android.webkit.WebView
            attachment.close()
            view.webViewClient.onPageFinished(view, view.url)
            assertEquals(0, parent.childCount)
            val remounted = host.webHost.attach(token, parent)
            assertSame(view, parent.getChildAt(0))
            remounted.control(app.daiyu.android.browser.openminis.BrowserVisibleAction.STOP)
            remounted.close()
            host.session.releaseTakeover(token)
        }
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
        val manualUrl = "https://browser-fixture.invalid/manual-original"
        assertTrue(confirmed(host, "action" to "get_page_info").result.text.contains(manualUrl))
        val screenshot = confirmed(host, "action" to "screenshot").result
        screenshots.add(java.nio.file.Paths.get(requireNotNull(screenshot.filePath)))
        assertTrue(screenshot.text.lineSequence().any { it.trim() == "URL: $manualUrl" })
        confirmed(host, "action" to "navigate", "url" to "https://browser-fixture.invalid/model-after-human")
    }


    fun testHumanBlockedResourcesAndExternalNavigationDoNotRetireOriginalView() {
        val host = host()
        confirmed(host, "action" to "new_tab")
        val parent = main { android.widget.FrameLayout(instrumentation.targetContext) }
        val attachment = main { host.webHost.attach(host.session.takeover(requireNotNull(host.currentTab)), parent) }
        val first = "https://browser-fixture.invalid/human-before-blocked"
        main { attachment.navigate(first) }
        val view = main { parent.getChildAt(0) as android.webkit.WebView }
        fun awaitPage(url: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (main { attachment.isLoading || view.progress != 100 || view.title != "Browser fixture" || view.url != url } &&
                System.nanoTime() < deadline) Thread.sleep(10)
            main {
                assertFalse(attachment.isLoading)
                assertEquals(100, view.progress)
                assertEquals(url, attachment.page().url)
                assertEquals("Browser fixture", view.title)
                assertSame(view, parent.getChildAt(0))
            }
        }
        awaitPage(first)
        val blocked = listOf("file:///private", "content://private/item", "about:blank",
            "intent://external/#Intent;end", "https://accounts.google.com/signin")
        // Get the client on UI; invoke its request callback off the Looper like WebView does.
        val client = main { view.webViewClient }
        for (url in blocked) {
            val request = object : android.webkit.WebResourceRequest {
                override fun getUrl() = android.net.Uri.parse(url)
                override fun isForMainFrame() = false
                override fun isRedirect() = false
                override fun hasGesture() = false
                override fun getMethod() = "GET"
                override fun getRequestHeaders() = mutableMapOf<String, String>()
            }
            val response = client.shouldInterceptRequest(view, request)
            assertEquals(url, 403, requireNotNull(response).statusCode)
        }
        main {
            for (url in blocked) {
                @Suppress("DEPRECATION")
                assertTrue(view.webViewClient.shouldOverrideUrlLoading(view, url))
                assertEquals(first, attachment.page().url)
                assertSame(view, parent.getChildAt(0))
            }
            attachment.navigate("https://browser-fixture.invalid/human-after-blocked")
        }
        awaitPage("https://browser-fixture.invalid/human-after-blocked")
        main {
            attachment.control(app.daiyu.android.browser.openminis.BrowserVisibleAction.BACK)
        }
        awaitPage(first)
        assertTrue("human navigation must not approve model history", historyApprovals.isEmpty())
        main { attachment.close() }
    }

    fun testStoppedHumanLateStartDoesNotMakePageBusyOrRetireView() {
        val host = host()
        confirmed(host, "action" to "new_tab")
        val parent = main { android.widget.FrameLayout(instrumentation.targetContext) }
        main {
            val attachment = host.webHost.attach(host.session.takeover(requireNotNull(host.currentTab)), parent)
            val old = "https://browser-fixture.invalid/human-cancelled"
            attachment.navigate(old)
            val view = parent.getChildAt(0) as android.webkit.WebView
            attachment.control(app.daiyu.android.browser.openminis.BrowserVisibleAction.STOP)
            view.webViewClient.onPageStarted(view, old, null)
            assertFalse(attachment.isLoading)
            attachment.page()
            assertSame(view, parent.getChildAt(0))
            attachment.close()
        }
        assertTrue(historyApprovals.isEmpty())
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

    fun testUnmountedHumanRestoredTabsSurviveSaveAndReopen() {
        val context = instrumentation.targetContext
        val conversation = app.daiyu.android.domain.ConversationId("human-restore-test-${UUID.randomUUID()}")
        val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
        val key = "tabs:human:${conversation.value}"
        val expected = listOf("https://browser-fixture.invalid/restored-one",
            "https://browser-fixture.invalid/restored-two", "https://browser-fixture.invalid/restored-three")
        fun persistedUrls(): List<String> {
            val array = org.json.JSONArray(prefs.getString(key, "[]"))
            return (0 until array.length()).map { array.getString(it) }
        }
        try {
            main {
                val array = org.json.JSONArray()
                expected.forEach { array.put(it) }
                assertTrue(prefs.edit().putString(key, array.toString()).commit())
            }
            repeat(2) {
                val local = main { app.daiyu.android.LocalBrowserVisual(context, conversation) }
                try {
                    main {
                        val visit = checkNotNull(local.openVisit())
                        val state = visit.snapshot()
                        assertFalse(state.unknown)
                        assertEquals(3, state.tabs.size)
                        assertFalse(state.mounted)
                        visit.close()
                        assertEquals(expected, persistedUrls())
                    }
                } finally { main { local.close() } }
                main { assertEquals(expected, persistedUrls()) }
            }
        } finally { main { assertTrue(prefs.edit().remove(key).commit()) } }
    }

    fun testModelConfigurationInvalidatesVisibleConfigurationCache() {
        fun assertViewport(view: android.webkit.WebView, width: Int, height: Int) {
            val density = view.resources.displayMetrics.density
            assertEquals((width * density).toInt().coerceAtLeast(1), view.width)
            assertEquals((height * density).toInt().coerceAtLeast(1), view.height)
        }
        fun awaitLoaded(attachment: app.daiyu.android.browser.openminis.BrowserVisibleAttachment,
                        view: android.webkit.WebView, requests: java.util.concurrent.atomic.AtomicInteger,
                        before: Int, url: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            fun loaded() = main {
                !attachment.isLoading && requests.get() > before && view.progress == 100 &&
                    view.title == "Browser fixture ${requests.get()}" && view.url == url
            }
            while (!loaded() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("fresh intercepted response must finish, not just cancel loading", loaded())
        }
        for (action in listOf("set_user_agent", "set_viewport", "reset_viewport")) {
            val requests = java.util.concurrent.atomic.AtomicInteger()
            val host = host(mainFrameRequests = requests)
            val url = "https://browser-fixture.invalid/config-cache-$action"
            confirmed(host, "action" to "new_tab")
            confirmed(host, "action" to "navigate", "url" to url)
            val configuration = app.daiyu.android.browser.openminis.BrowserVisibleConfiguration(
                profile = com.openminis.app.browser.UserAgentProfile.MOBILE_CHROME, width = 640, height = 480)
            val parent = main { android.widget.FrameLayout(instrumentation.targetContext) }
            val firstToken = main { host.session.takeover(requireNotNull(host.currentTab)) }
            val first = main { host.webHost.attach(firstToken, parent) }
            val originalView = main { parent.getChildAt(0) as android.webkit.WebView }
            try {
                val before = requests.get()
                main { first.configure(configuration); assertViewport(originalView, 640, 480) }
                awaitLoaded(first, originalView, requests, before, url)
            } finally { main { first.close(); host.session.releaseTakeover(firstToken) } }
            when (action) {
                "set_user_agent" -> confirmed(host, "action" to action, "user_agent" to "desktop_chrome")
                "set_viewport" -> confirmed(host, "action" to action, "viewport_width" to 800, "viewport_height" to 600)
                "reset_viewport" -> confirmed(host, "action" to "set_viewport", "reset" to true)
            }
            main {
                val modelViewport = when (action) {
                    "set_user_agent" -> 640 to 480
                    "set_viewport" -> 800 to 600
                    else -> configuration.profile.viewportSize
                }
                assertViewport(originalView, modelViewport.first, modelViewport.second)
            }
            val secondToken = main { host.session.takeover(requireNotNull(host.currentTab)) }
            val second = main { host.webHost.attach(secondToken, parent) }
            var appliedRequests = 0
            try {
                val before = requests.get()
                main {
                    assertSame(originalView, parent.getChildAt(0))
                    assertFalse(second.isLoading)
                    second.configure(configuration)
                    assertTrue("$action must invalidate visible configuration deduplication", second.isLoading)
                    assertEquals(configuration.profile.userAgentString, originalView.settings.userAgentString)
                    assertViewport(originalView, 640, 480)
                }
                awaitLoaded(second, originalView, requests, before, url)
                appliedRequests = requests.get()
                main {
                    second.configure(configuration)
                    assertFalse(second.isLoading)
                    assertViewport(originalView, 640, 480)
                    assertEquals(appliedRequests, requests.get())
                }
            } finally { main { second.close(); host.session.releaseTakeover(secondToken) } }
            assertTrue(confirmed(host, "action" to "get_text", "selector" to "body").result.text.contains("0123456789"))
            assertEquals(appliedRequests, requests.get())
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
