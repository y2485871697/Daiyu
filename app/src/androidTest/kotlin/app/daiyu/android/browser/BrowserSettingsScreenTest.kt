package app.daiyu.android.browser

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.browser.session.BrowserAccess
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.BrowserSettingsScreen
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Settings only; the test never creates a browser host, WebView, tab or website request. */
class BrowserSettingsScreenTest : WiredActivityTestCase() {
    private fun show(
        store: BrowserSettingsStore,
        owner: BrowserSettingsSaveOwner? = null,
        current: () -> Boolean = { true },
    ) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    BrowserSettingsScreen(store, onBack = {}, ownerCurrent = current, saveOwner = owner)
                }
            }
        }
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("browser-save").fetchSemanticsNodes().singleOrNull()
                ?.config?.getOrNull(SemanticsActions.OnClick) != null
        }
    }

    fun testDefaultSaveAndReadbackAcrossNewStoreAndComposition() {
        val preferences = instrumentation.targetContext.getSharedPreferences("browser-screen-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            val store = BrowserSettingsStore(preferences)
            show(store)
            assertEquals(BrowserAccess.DISABLED, store.snapshot().access)
            reveal("browser-access:FULL", "浏览器").performClick()
            // Selecting the radio does not save, issue task authorization, or execute anything.
            assertEquals(BrowserAccess.DISABLED, store.snapshot().access)
            reveal("browser-save", "浏览器").performClick()
            ui.waitUntil(5_000) { store.snapshot().access == BrowserAccess.FULL }
            reveal("browser-saved", "浏览器").assertExists()
            ui.waitUntil(5_000) {
                ui.onAllNodes(hasText("完整能力上限（FULL）") and
                    hasAnyAncestor(hasTestTag("browser-saved")), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            val reopened = BrowserSettingsStore(preferences)
            show(reopened)
            assertEquals(BrowserAccess.FULL, reopened.snapshot().access)
            reveal("settings-row:任务历史记录：DENY", "浏览器").assertExists()
            reveal("settings-row:Cookie 名称：默认全部拒绝", "浏览器").assertExists()
            assertEquals("DENY", reopened.snapshot().historyConsent)
            assertTrue(reopened.snapshot().allowedCookieNames.isEmpty())
        } finally { preferences.edit().clear().commit() }
    }

    fun testQueuedSaveIsRejectedAfterDefaultOwnerIsDisposed() {
        queuedSaveThenLeave(owner = null) { _ -> activity.setContent {} }
    }

    fun testQueuedSaveIsRejectedWhenHostRevokesBeforeDisposal() {
        val owner = BrowserSettingsSaveOwner()
        queuedSaveThenLeave(owner) { invalidate ->
            owner.revoke() // Host contract: synchronous, before changing page identity.
            invalidate()
        }
    }

    fun testBackRevokesQueuedSaveEvenBeforeParentDisposesPage() {
        val owner = BrowserSettingsSaveOwner()
        queuedSaveThenLeave(owner) { _ ->
            activity.onBackPressedDispatcher.onBackPressed()
            assertFalse(owner.isActive) // onBack is deliberately a no-op in this fixture.
        }
    }

    private fun queuedSaveThenLeave(owner: BrowserSettingsSaveOwner?, leave: (() -> Unit) -> Unit) {
        val preferences = instrumentation.targetContext.getSharedPreferences("browser-screen-queue-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val blocked = BlockingPreferences(preferences)
        val store = BrowserSettingsStore(blocked.preferences)
        var current = true // Only main reads/writes this; never evaluated by the IO worker.
        var holder: FutureTask<Unit>? = null
        try {
            show(store, owner) {
                check(Looper.myLooper() == Looper.getMainLooper())
                current
            }
            reveal("browser-access:FULL", "浏览器").performClick()
            blocked.blockNextRead.set(true)
            val heldRead = FutureTask<Unit> { store.snapshot(); Unit }
            holder = heldRead
            Thread(heldRead, "browser-settings-ui-lock-holder").start()
            assertTrue(blocked.entered.await(5, TimeUnit.SECONDS))
            reveal("browser-save", "浏览器").performClick()
            // Prove the clicked save reached the lock before invalidating the page. No sleeps,
            // and no assumption that cancelling a coroutine can stop synchronous preference IO.
            var queuedThread: Thread? = null
            ui.waitUntil(5_000) {
                queuedThread = Thread.getAllStackTraces().entries.firstOrNull { (thread, frames) ->
                    thread.state == Thread.State.BLOCKED && frames.any(::isStoreSaveFrame)
                }?.key
                queuedThread != null
            }
            mainThread { leave { current = false } }
            ui.waitForIdle() // setContent schedules disposal; do not release IO before it ran.
            assertEquals(0, blocked.edits.get())
            assertEquals(0, blocked.commits.get())
            blocked.release.countDown()
            heldRead.get(5, TimeUnit.SECONDS)
            ui.waitUntil(5_000) { queuedThread!!.stackTrace.none(::isStoreSaveFrame) }
            ui.waitForIdle()
            assertEquals(0, blocked.edits.get())
            assertEquals(0, blocked.commits.get())
            assertEquals(BrowserAccess.DISABLED, store.snapshot().access)
        } finally {
            blocked.release.countDown()
            holder?.get(5, TimeUnit.SECONDS)
            mainThread { activity.setContent {} }
            preferences.edit().clear().commit()
        }
    }

    private fun isStoreSaveFrame(frame: StackTraceElement): Boolean =
        frame.className == BrowserSettingsStore::class.java.name && frame.methodName.startsWith("save")

    /** Blocks snapshot inside the real store monitor; wraps only this test's preferences. */
    private class BlockingPreferences(private val delegate: SharedPreferences) {
        val blockNextRead = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val edits = AtomicInteger()
        val commits = AtomicInteger()
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "getString" -> {
                    if (blockNextRead.compareAndSet(true, false)) {
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS)) { "UI test lock was not released" }
                    }
                    delegate.getString(args!![0] as String, args[1] as String?)
                }
                "edit" -> {
                    edits.incrementAndGet()
                    val editor = delegate.edit()
                    lateinit var wrapped: SharedPreferences.Editor
                    wrapped = Proxy.newProxyInstance(
                        SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
                    ) { _, editorMethod, editorArgs ->
                        when (editorMethod.name) {
                            "putString" -> { editor.putString(editorArgs!![0] as String, editorArgs[1] as String?); wrapped }
                            "commit" -> { commits.incrementAndGet(); editor.commit() }
                            else -> error("unexpected editor call ${editorMethod.name}")
                        }
                    } as SharedPreferences.Editor
                    wrapped
                }
                else -> error("unexpected preferences call ${method.name}")
            }
        } as SharedPreferences
    }

    fun testOutgoingPageCannotSave() {
        val preferences = instrumentation.targetContext.getSharedPreferences("browser-screen-owner-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        var current = true
        try {
            val store = BrowserSettingsStore(preferences)
            show(store) { current }
            reveal("browser-access:FULL", "浏览器").performClick()
            mainThread { current = false }
            reveal("browser-save", "浏览器").performClick()
            ui.waitForIdle()
            assertEquals(BrowserAccess.DISABLED, store.snapshot().access)
        } finally { preferences.edit().clear().commit() }
    }
}
