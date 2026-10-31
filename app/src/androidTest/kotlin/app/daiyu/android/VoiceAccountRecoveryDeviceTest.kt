package app.daiyu.android

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ModelSettingsStore
import app.daiyu.android.voice.providers.DoubaoVoices
import kotlinx.coroutines.*
import java.io.File
import java.security.KeyStore
import java.util.UUID

@Suppress("DEPRECATION")
class VoiceAccountRecoveryDeviceTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var root: File
    override fun setUp() {
        super.setUp()
        val prefix = "voice-account-recovery-${UUID.randomUUID()}-"
        root = File(instrumentation.targetContext.cacheDir, prefix).apply { check(mkdirs()) }
        context = object : ContextWrapper(testContext(instrumentation.targetContext, prefix)) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = root
        }
    }
    override fun tearDown() {
        try {
            val prefs = context.getSharedPreferences("voice-credentials-v1", Context.MODE_PRIVATE)
            prefs.getString("key-alias", null)?.let { alias ->
                assertTrue(alias.startsWith("voice-api-key."))
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            }
            check(prefs.edit().clear().commit())
            root.deleteRecursively()
        } finally { super.tearDown() }
    }
    private fun account(key: String = "fixture-account-A") = VoiceConfiguration(engine = "DOUBAO",
        endpoint = "https://openspeech.bytedance.com", key = key, provider = "fixture-only",
        model = "seed-tts-2.0", voice = DoubaoVoices.DEFAULT_ID)

    fun testNewRuntimeSavingProfileBeforeLoadPreservesExistingPersonalAccount() = runBlocking {
        val original = ControllerVoiceRuntime(context)
        original.saveAccount(account())
        val saved = original.accountConfigurations.value.getValue("DOUBAO")
        val fresh = ControllerVoiceRuntime(context)
        fresh.save(VoiceConfiguration(), VoiceProfile.CONVERSATION)
        assertEquals(saved, fresh.accountConfigurations.value["DOUBAO"])
        val reopened = ControllerVoiceRuntime(context)
        reopened.load()
        assertEquals(saved, reopened.accountConfigurations.value["DOUBAO"])
    }

    fun testStaleRuntimeMergesLatestAccountInsteadOfRestoringItsOldSnapshot() = runBlocking {
        val owner = ControllerVoiceRuntime(context)
        owner.saveAccount(account())
        val stale = ControllerVoiceRuntime(context)
        stale.load()
        owner.saveAccount(account("fixture-account-B"))
        val latest = owner.accountConfigurations.value.getValue("DOUBAO")
        stale.save(VoiceConfiguration(), VoiceProfile.READ_ALOUD)
        assertEquals(latest, stale.accountConfigurations.value["DOUBAO"])
    }

    fun testUnreadableVaultCannotBeOverwrittenByEitherOrdinarySave() = runBlocking {
        val owner = ControllerVoiceRuntime(context)
        owner.saveAccount(account())
        val prefs = context.getSharedPreferences("voice-credentials-v1", Context.MODE_PRIVATE)
        check(prefs.edit().remove("key").commit())
        val before = prefs.all.toMap()
        val failed = ControllerVoiceRuntime(context)
        failed.load()
        assertEquals(ControllerVoiceRuntime.LoadState.FAILED, failed.loadState)
        try { failed.save(VoiceConfiguration()); fail("must retain unreadable envelope") }
        catch (_: app.daiyu.android.storage.CredentialUnavailableException) { }
        assertEquals(before, prefs.all)
        try { failed.saveAccount(account("fixture-other")); fail("must not replace unreadable account") }
        catch (_: app.daiyu.android.storage.CredentialUnavailableException) { }
        assertEquals(before, prefs.all)
    }

    fun testCancelledSaveWaitingForSharedLockDoesNotWriteOrPublish() = runBlocking {
        val runtime = ControllerVoiceRuntime(context)
        runtime.saveAccount(account())
        val original = runtime.accountConfigurations.value
        val prefs = context.getSharedPreferences("voice-credentials-v1", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        val monitor = ControllerVoiceRuntime::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(null)
        val waiting = CompletableDeferred<Thread>()
        lateinit var pending: Job
        synchronized(monitor) {
            pending = launch(Dispatchers.IO) {
                waiting.complete(Thread.currentThread())
                runtime.saveAccount(account("fixture-cancelled"))
            }
            val thread = runBlocking { waiting.await() }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, thread.state)
            pending.cancel()
        }
        pending.join()
        assertEquals(before, prefs.all)
        assertEquals(original, runtime.accountConfigurations.value)
        assertEquals(ControllerVoiceRuntime.LoadState.CANCELLED, runtime.loadState)
    }

    fun testLegacyAccountRecoverySurvivesProfileSaveWithoutConsumingLegacy() = runBlocking {
        val legacy = account()
        ModelSettingsStore(context, "voice-api-key", "voice-credentials-v1").save(
            ModelSettings("https://voice-credentials.invalid/v1/responses", "voice-credential", legacy.encode(), false))
        val runtime = ControllerVoiceRuntime(context)
        runtime.load()
        assertEquals(legacy, runtime.accountConfigurations.value["DOUBAO"])
        assertEquals(legacy, runtime.legacyConfiguration.value)
        assertNull(runtime.configuration.value)
        runtime.save(VoiceConfiguration(), VoiceProfile.CONVERSATION)
        val reopened = ControllerVoiceRuntime(context)
        reopened.load()
        assertEquals(legacy, reopened.accountConfigurations.value["DOUBAO"])
        assertEquals(legacy, reopened.legacyConfiguration.value)
        assertNull(reopened.configuration.value)
    }
}
