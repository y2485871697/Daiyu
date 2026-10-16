package app.daiyu.android.browser

import android.content.SharedPreferences
import app.daiyu.android.browser.session.BrowserAccess
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class BrowserSettingsStoreTest {
    @Test fun missingUnknownCorruptAndLegacyDenyAreDisabledWithoutMigration() {
        for (raw in listOf(null, "DENY", "v1:DENY", "v1:DISABLED", "FULL", "v2:FULL", "v1:full", 7)) {
            val fake = PreferencesFake(raw)
            val settings = BrowserSettingsStore(fake.preferences).snapshot()
            assertEquals(BrowserAccess.DISABLED, settings.access)
            assertEquals("DENY", settings.historyConsent)
            assertTrue(settings.allowedCookieNames.isEmpty())
            assertEquals(0, fake.edits)
        }
    }

    @Test fun eachCeilingIsCommittedAndReadBackByAnotherInstance() {
        val fake = PreferencesFake()
        val first = BrowserSettingsStore(fake.preferences)
        val second = BrowserSettingsStore(fake.preferences)
        for (access in BrowserAccess.entries) {
            assertEquals(BrowserSettings(access), first.save(BrowserSettings(access)))
            assertEquals(BrowserSettings(access), second.snapshot())
            assertEquals("v1:${access.name}", fake.raw)
            assertEquals("DENY", second.snapshot().historyConsent)
            assertTrue(second.snapshot().allowedCookieNames.isEmpty())
        }
        assertEquals(3, fake.commits)
    }

    @Test fun failedCommitCannotExposeInMemoryFullAndExplicitSaveRecovers() {
        val fake = PreferencesFake()
        val first = BrowserSettingsStore(fake.preferences)
        val second = BrowserSettingsStore(fake.preferences)
        fake.commitResult = false
        rejected { first.save(BrowserSettings(BrowserAccess.FULL)) }
        assertEquals("v1:FULL", fake.raw) // Model SharedPreferences' in-memory mutation on failure.
        rejected { first.snapshot() }
        rejected { second.snapshot() }
        fake.commitResult = true
        second.save(BrowserSettings())
        assertEquals(BrowserSettings(), first.snapshot())
    }

    @Test fun commitSuccessWithMismatchedReadbackRemainsFenced() {
        val fake = PreferencesFake()
        val store = BrowserSettingsStore(fake.preferences)
        fake.replaceAfterCommit = "v1:READ_ONLY"
        rejected { store.save(BrowserSettings(BrowserAccess.FULL)) }
        rejected { store.snapshot() }
        fake.replaceAfterCommit = null
        assertEquals(BrowserSettings(), store.save(BrowserSettings()))
    }

    @Test fun queuedSaveRevokedBeforeAdmissionDoesNotEditOrBlockRevoker() {
        val fake = PreferencesFake()
        val store = BrowserSettingsStore(fake.preferences)
        val otherStore = BrowserSettingsStore(fake.preferences)
        val owner = BrowserSettingsSaveOwner()
        val gate = Gate()
        fake.beforeRead = { gate.hold() }
        val holder = Worker { store.snapshot() }
        var queued: Worker? = null
        var revoker: Worker? = null
        try {
            gate.awaitEntered() // snapshot holds the shared store lock, not merely an IO queue.
            queued = Worker { rejected { otherStore.save(BrowserSettings(BrowserAccess.FULL), owner) } }
            queued.awaitBlocked()
            revoker = Worker { owner.revoke() } // Models UI invalidation while IO owns the lock.
            revoker.awaitDone() // Must finish BEFORE releasing the store lock.
            assertFalse(owner.isActive)
            assertEquals(0, fake.edits)
            assertEquals(0, fake.commits)
        } finally {
            gate.release.countDown()
            holder.awaitDone()
            revoker?.awaitDone()
            queued?.awaitDone()
        }
        assertEquals(0, fake.edits)
        assertEquals(0, fake.commits)
        assertEquals(BrowserSettings(), otherStore.snapshot())
    }

    @Test fun revocationAfterAdmissionAllowsCommitAndReadbackToFinish() {
        val fake = PreferencesFake()
        val store = BrowserSettingsStore(fake.preferences)
        val owner = BrowserSettingsSaveOwner()
        val gate = Gate()
        fake.beforeCommit = { gate.hold() }
        val writer = Worker {
            assertEquals(BrowserSettings(BrowserAccess.FULL),
                store.save(BrowserSettings(BrowserAccess.FULL), owner))
        }
        var revoker: Worker? = null
        try {
            gate.awaitEntered() // Admission has happened; commit has not mutated preferences yet.
            revoker = Worker { owner.revoke() }
            revoker.awaitDone()
            assertFalse(owner.isActive)
            assertEquals(0, fake.commits)
        } finally {
            gate.release.countDown()
            writer.awaitDone()
            revoker?.awaitDone()
        }
        assertEquals(1, fake.commits)
        assertEquals(BrowserAccess.FULL, store.snapshot().access)
        rejected { store.save(BrowserSettings(), owner) }
        assertEquals(1, fake.commits)
    }

    @Test fun rejectedOwnerCannotClearOrChangeAnExistingFailureFence() {
        val fake = PreferencesFake()
        val first = BrowserSettingsStore(fake.preferences)
        val second = BrowserSettingsStore(fake.preferences)
        fake.commitResult = false
        rejected { first.save(BrowserSettings(BrowserAccess.FULL)) }
        val owner = BrowserSettingsSaveOwner().also { it.revoke() }
        fake.commitResult = true
        rejected { second.save(BrowserSettings(), owner) }
        assertEquals(1, fake.edits)
        assertEquals(1, fake.commits)
        assertEquals("v1:FULL", fake.raw)
        rejected { first.snapshot() }
        rejected { second.snapshot() }
        second.save(BrowserSettings(), BrowserSettingsSaveOwner())
        assertEquals(BrowserSettings(), first.snapshot())
    }

    @Test fun oldOwnerCannotBeReactivatedOrInvalidateANewPage() {
        val fake = PreferencesFake()
        val store = BrowserSettingsStore(fake.preferences)
        val old = BrowserSettingsSaveOwner()
        old.revoke()
        assertFalse(old.tryAdmit())
        val fresh = BrowserSettingsSaveOwner()
        store.save(BrowserSettings(BrowserAccess.READ_ONLY), fresh)
        old.revoke() // A late disposal from the old page cannot affect the new token.
        rejected { store.save(BrowserSettings(BrowserAccess.FULL), old) }
        assertTrue(fresh.isActive)
        store.save(BrowserSettings(BrowserAccess.FULL), fresh) // Admission does not consume a token.
        assertEquals(2, fake.commits)
        assertEquals(BrowserAccess.FULL, store.snapshot().access)
    }

    private class Gate {
        private val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun hold() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "test gate was not released" }
        }
        fun awaitEntered() { assertTrue("worker did not reach gate", entered.await(3, TimeUnit.SECONDS)) }
    }

    private class Worker(block: () -> Unit) {
        private val task = FutureTask<Unit> { block() }
        private val thread = Thread(task, "browser-settings-test").also { it.start() }
        fun awaitDone() { task.get(3, TimeUnit.SECONDS) }
        fun awaitBlocked() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (thread.state != Thread.State.BLOCKED && !task.isDone && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals("save did not queue on the shared lock", Thread.State.BLOCKED, thread.state)
        }
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected fail-closed fence") } catch (_: IllegalStateException) { }
    }

    private class PreferencesFake(var raw: Any? = null) {
        var edits = 0
        var commits = 0
        var commitResult = true
        var replaceAfterCommit: String? = null
        var beforeRead: () -> Unit = {}
        var beforeCommit: () -> Unit = {}
        val preferences: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "getString" -> {
                    beforeRead()
                    assertEquals(BrowserSettingsStore.KEY, args!![0])
                    if (raw == null) args[1] else raw as String
                }
                "edit" -> { edits++; editor() }
                else -> error("unexpected preferences call ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            var pending: String? = null
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putString" -> {
                        assertEquals(BrowserSettingsStore.KEY, args!![0])
                        pending = args[1] as String
                        editor
                    }
                    "commit" -> { beforeCommit(); commits++; raw = replaceAfterCommit ?: pending; commitResult }
                    else -> error("unexpected editor call ${method.name}")
                }
            } as SharedPreferences.Editor
            return editor
        }
    }
}
