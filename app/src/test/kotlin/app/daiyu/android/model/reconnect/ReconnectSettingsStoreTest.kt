package app.daiyu.android.model.reconnect

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class ReconnectSettingsStoreTest {
    @Test fun missingRecordDoesNotWriteAndEachPresetSurvivesAnotherStore() {
        val fake = Preferences()
        val first = ReconnectSettingsStore(fake.preferences)
        val second = ReconnectSettingsStore(fake.preferences)
        assertEquals(ReconnectSettings(), first.snapshot())
        assertEquals(0, fake.edits)
        for (preset in ReconnectDurationPreset.entries) {
            val settings = ReconnectSettings(preset)
            assertEquals(settings, first.save(settings))
            assertEquals(settings, second.snapshot())
        }
        assertEquals(5, fake.commits)
    }

    @Test fun corruptOrWrongTypedRecordIsNotSilentlyEnabledOrReplaced() {
        for (raw in listOf("v2:on:3", "v1:on:0", "v1:true:3", 1)) {
            val fake = Preferences(raw)
            rejected { ReconnectSettingsStore(fake.preferences).snapshot() }
            assertEquals(raw, fake.raw)
            assertEquals(0, fake.edits)
        }
    }

    @Test fun futurePolicyIsOffAndUnmodifiedUntilExplicitSave() {
        val fake = Preferences("v1:future_policy")
        val store = ReconnectSettingsStore(fake.preferences)
        assertEquals(ReconnectSettings(ReconnectDurationPreset.NONE), store.snapshot())
        assertEquals("v1:future_policy", fake.raw); assertEquals(0, fake.edits)
        store.save(ReconnectSettings(ReconnectDurationPreset.WINDOW_1M))
        assertEquals("v1:window_1m", fake.raw)
    }

    @Test fun failedCommitFencesAllInstancesDespiteInMemoryMutationAndExplicitSaveRecovers() {
        val fake = Preferences()
        val first = ReconnectSettingsStore(fake.preferences)
        val second = ReconnectSettingsStore(fake.preferences)
        fake.commitResult = false
        rejected { first.save(ReconnectSettings(ReconnectDurationPreset.WINDOW_30S)) }
        assertEquals(ReconnectSettingsCodec.encode(ReconnectSettings(ReconnectDurationPreset.WINDOW_30S)), fake.raw)
        rejected { first.snapshot() }
        rejected { second.snapshot() }
        fake.commitResult = true
        second.save(ReconnectSettings())
        assertEquals(ReconnectSettings(), first.snapshot())
    }

    @Test fun commitExceptionAndMismatchedReadbackStayFencedUntilExplicitSave() {
        val fake = Preferences()
        val first = ReconnectSettingsStore(fake.preferences)
        val second = ReconnectSettingsStore(fake.preferences)
        fake.commitException = true
        rejected { first.save(ReconnectSettings(ReconnectDurationPreset.WINDOW_30S)) }
        rejected { second.snapshot() }
        fake.commitException = false
        fake.replaceAfterCommit = ReconnectSettingsCodec.encode(ReconnectSettings())
        rejected { first.save(ReconnectSettings(ReconnectDurationPreset.WINDOW_30S)) }
        rejected { second.snapshot() }
        fake.replaceAfterCommit = null
        assertEquals(ReconnectSettings(ReconnectDurationPreset.WINDOW_30S), second.save(ReconnectSettings(ReconnectDurationPreset.WINDOW_30S)))
        assertEquals(ReconnectSettings(ReconnectDurationPreset.WINDOW_30S), first.snapshot())
    }

    @Test fun frozenValueDoesNotObserveLaterSaves() {
        val fake = Preferences()
        val store = ReconnectSettingsStore(fake.preferences)
        val original = store.save(ReconnectSettings(ReconnectDurationPreset.WINDOW_30S))
        store.save(ReconnectSettings(ReconnectDurationPreset.NONE))
        assertEquals(ReconnectSettings(ReconnectDurationPreset.WINDOW_30S), original)
        assertEquals(ReconnectSettings(ReconnectDurationPreset.NONE), store.snapshot())
    }

    @Test fun queuedOldPageCannotWriteAfterRevocationAndRevokerDoesNotWaitForStorage() {
        val fake = Preferences()
        val first = ReconnectSettingsStore(fake.preferences)
        val second = ReconnectSettingsStore(fake.preferences)
        val owner = ReconnectSettingsSaveOwner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.beforeRead = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val reader = Worker { first.snapshot() }
        var queued: Worker? = null
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            queued = Worker { rejected { second.save(ReconnectSettings(ReconnectDurationPreset.WINDOW_30S), owner) } }
            queued.awaitBlocked()
            val revoker = Worker { owner.revoke() }
            revoker.awaitDone() // Before releasing the store lock.
            assertFalse(owner.isActive)
            assertEquals(0, fake.edits)
        } finally {
            release.countDown()
            reader.awaitDone()
            queued?.awaitDone()
        }
        assertEquals(0, fake.commits)
    }

    @Test fun alreadyAdmittedSaveFinishesButRevokedOwnerCannotWriteOrClearFenceAgain() {
        val fake = Preferences()
        val store = ReconnectSettingsStore(fake.preferences)
        val owner = ReconnectSettingsSaveOwner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fake.beforeCommit = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val writer = Worker { store.save(ReconnectSettings(ReconnectDurationPreset.WINDOW_30S), owner) }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            Worker { owner.revoke() }.awaitDone()
        } finally { release.countDown(); writer.awaitDone() }
        assertEquals(ReconnectSettings(ReconnectDurationPreset.WINDOW_30S), store.snapshot())
        fake.beforeCommit = {}
        fake.commitResult = false
        rejected { store.save(ReconnectSettings()) }
        val commits = fake.commits
        fake.commitResult = true
        rejected { store.save(ReconnectSettings(ReconnectDurationPreset.WINDOW_30S), owner) }
        assertEquals(commits, fake.commits)
        rejected { store.snapshot() }
        store.save(ReconnectSettings(), ReconnectSettingsSaveOwner())
        assertEquals(ReconnectSettings(), store.snapshot())
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected explicit configuration/fence error") }
        catch (_: IllegalStateException) { }
    }

    private class Worker(block: () -> Unit) {
        private val task = FutureTask { block() }
        private val thread = Thread(task, "reconnect-settings-test").apply { isDaemon = true; start() }
        fun awaitDone() { task.get(3, TimeUnit.SECONDS) }
        fun awaitBlocked() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (thread.state != Thread.State.BLOCKED && !task.isDone && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, thread.state)
        }
    }

    private class Preferences(var raw: Any? = null) {
        var edits = 0
        var commits = 0
        var commitResult = true
        var commitException = false
        var replaceAfterCommit: String? = null
        var beforeRead: () -> Unit = {}
        var beforeCommit: () -> Unit = {}
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "getString" -> { beforeRead(); check(args!![0] == ReconnectSettingsStore.KEY); raw?.let { it as String } ?: args[1] }
                "edit" -> { edits++; editor() }
                else -> error("unexpected preferences operation")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            var pending: String? = null
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putString" -> { check(args!![0] == ReconnectSettingsStore.KEY); pending = args[1] as String; editor }
                    "commit" -> {
                        beforeCommit(); commits++; raw = replaceAfterCommit ?: pending
                        check(!commitException) { "unconfirmed commit" }
                        commitResult
                    }
                    else -> error("unexpected editor operation")
                }
            } as SharedPreferences.Editor
            return editor
        }
    }
}
