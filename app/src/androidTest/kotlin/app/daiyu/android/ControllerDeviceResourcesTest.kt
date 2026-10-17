package app.daiyu.android

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.integration.tools.*
import app.daiyu.android.storage.*
import app.daiyu.android.platform.DeviceResultPolicy
import app.daiyu.android.platform.PlatformReadTool
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class ControllerDeviceResourcesTest : InstrumentationTestCase() {
    private class FixtureApplication(base: Context) : Application() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
            if (!filter.hasAction(Intent.ACTION_BATTERY_CHANGED)) return super.registerReceiver(receiver, filter)
            check(receiver == null); entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
            return Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, 42)
                .putExtra(BatteryManager.EXTRA_SCALE, 100).putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
        }
    }
    private class Fixture(base: Context) {
        val id = UUID.randomUUID().toString()
        val app = FixtureApplication(testContext(base, "controller-devices-$id-"))
        val database = ConversationDatabase(app)
        val alias = "app.daiyu.android.test.devices.$id"
        val settings = ModelSettingsStore(app, alias)
        val modelEntered = CountDownLatch(1); val modelRelease = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(); val outcome = AtomicReference<DispatchOutcome?>()
        var reader: Thread? = null
        val controller: ChatController
        init {
            settings.save(ModelSettings("https://example.invalid", "device-fixture", "synthetic-device-key", true))
            controller = ChatController(app, database, settings, app.getSharedPreferences("conversation-ui", 0), {
                ModelPort { object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        modelEntered.countDown(); check(modelRelease.await(10, TimeUnit.SECONDS)); sink(ModelEvent.TextDelta("body", "complete"))
                    }
                    override fun cancel() { modelRelease.countDown() }
                } }
            })
        }
        fun startRead() {
            val tools = field(controller, "runTools") as RunCatalogTools
            val authorization = field(tools, "boundAuthorization") as TaskAuthorization
            val owner = field(tools, "owner") as RunExecutionOwner
            val devices = field(tools, "deviceResources") as RunDeviceResources
            val capabilities = SessionCapabilities(authorization.sessionId, setOf(AndroidToolRegistry.BATTERY_CAPABILITY))
            val dispatcher = ToolDispatcher(AndroidToolRegistry.create(devices).snapshot(), capabilities, ToolLedger(InMemoryToolLedgerStore()))
            reader = Thread {
                try { outcome.set(dispatcher.dispatchOwned(authorization, listOf(ToolInvocation(ToolCallId("held-device-consumer"), ReadOnlyTool.BATTERY.toolName, "{}")), owner).single().outcome) }
                catch (t: Throwable) { failure.set(t) }
            }.apply { isDaemon = true; start() }
        }
        fun releaseRead() {
            app.release.countDown(); reader?.join(5000); check(reader?.isAlive != true); failure.get()?.let { throw it }
        }
        companion object {
            fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        }
    }
    private val fixtures = mutableListOf<Fixture>()
    private fun fresh(): Fixture = Fixture(instrumentation.targetContext).also {
        fixtures.add(it); awaitControl("recovery ready") { it.controller.ready }; assertNull(it.controller.problem)
    }
    private fun start(f: Fixture): Message {
        assertNotNull(f.controller.send(null, "device lifetime"))
        val owner = checkNotNull(f.controller.active)
        assertTrue(f.modelEntered.await(5, TimeUnit.SECONDS)); f.startRead(); assertTrue(f.app.entered.await(5, TimeUnit.SECONDS))
        f.modelRelease.countDown()
        awaitControl("Runner finished while device close is pending") {
            val lifetime = Fixture.field(f.controller, "resources")
            lifetime != null && Fixture.field(lifetime, "devicesClosing") == true && Fixture.field(lifetime, "busy") == false
        }
        return owner
    }
    override fun tearDown() {
        try { fixtures.forEach { f ->
            f.modelRelease.countDown(); f.releaseRead(); f.controller.stop(); f.controller.close(); f.database.close()
            f.app.deleteDatabase("conversations.db"); f.app.deleteDatabase("questions.db"); f.database.runtimeLockFile.delete()
            f.app.getSharedPreferences("model-settings", 0).edit().clear().commit(); f.app.getSharedPreferences("conversation-ui", 0).edit().clear().commit()
            f.app.getSharedPreferences("platform-read-settings", 0).edit().clear().commit()
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it == f.alias || it.startsWith("${f.alias}.") }.forEach { keys.deleteEntry(it) }
        } } finally { super.tearDown() }
    }
    fun testProductionSendFreezesPlatformSelectionPolicyAndSameResourceBindingWithoutDataReads() {
        val f = fresh()
        val selected = PlatformReadTool.CONTACTS
        f.controller.platformSettings.select(selected, true)
        f.controller.platformSettings.consentModel(selected, true)
        assertNotNull(f.controller.send(null, "synthetic platform settings freeze"))
        assertTrue(f.modelEntered.await(5, TimeUnit.SECONDS))
        val tools = Fixture.field(f.controller, "runTools") as RunCatalogTools
        val devices = Fixture.field(tools, "deviceResources") as RunDeviceResources
        val grants = Fixture.field(tools, "capabilities") as SessionCapabilities
        val authorization = Fixture.field(tools, "boundAuthorization") as TaskAuthorization
        val owner = Fixture.field(tools, "owner") as RunExecutionOwner
        assertSame(grants, Fixture.field(devices, "capabilities"))
        assertSame(authorization, Fixture.field(devices, "authorization"))
        assertSame(owner, Fixture.field(devices, "owner"))
        assertEquals(setOf(selected.capability), grants.enabled.filter { it.startsWith("platform.") }.toSet())
        assertTrue(AndroidToolRegistry.BATTERY_CAPABILITY in grants.enabled)
        val policy = Fixture.field(checkNotNull(devices.platformBackend), "resultPolicy") as DeviceResultPolicy
        assertFalse(policy.allowsShared(selected.toolName))
        f.controller.platformSettings.select(selected, false)
        f.controller.platformSettings.select(PlatformReadTool.TIME, true)
        f.controller.platformSettings.consentHistory(selected, true)
        assertTrue(selected.capability in grants.enabled); assertFalse(PlatformReadTool.TIME.capability in grants.enabled)
        assertFalse(policy.allowsShared(selected.toolName))
        val next = f.controller.platformSettings.freeze()
        assertEquals(setOf(PlatformReadTool.TIME), next.platformAllowed)
        assertTrue(next.resultPolicy.allowsShared(selected.toolName))
        // No Catalog call is executed: only production wiring and frozen values are inspected.
        f.modelRelease.countDown()
        awaitControl("platform run closes through existing resources") { f.controller.active == null }
        assertNull(Fixture.field(f.controller, "resources"))
    }
    fun testCompletedRunnerRetainsRunUntilDeviceReadConfirmsClose() {
        val f = fresh(); val owner = start(f)
        controlMain {
            assertNotNull(Fixture.field(f.controller, "resources")); assertEquals(RunStatus.COMPLETED, f.controller.active!!.status)
        }
        assertNull(f.controller.send(null, "must remain fenced"))
        assertEquals(1, f.database.conversations().size); f.releaseRead()
        awaitControl("device confirmation releases completed run") { f.controller.active == null }
        assertTrue(f.outcome.get() is DispatchOutcome.Confirmed); assertNull(Fixture.field(f.controller, "resources"))
        assertEquals(RunStatus.COMPLETED, f.database.messages(owner.conversationId).last().second.status)
        assertNotNull(f.controller.send(null, "next run"))
        awaitControl("next run completes independently") { f.controller.active == null }
    }
    fun testMainStopCloseDoNotWaitAndBackgroundCloseRetainsCompatibilityJoin() {
        val f = fresh(); val owner = start(f)
        val mainReturned = CountDownLatch(1); val backgroundReturned = CountDownLatch(1); val failure = AtomicReference<Throwable?>()
        val mainRequester = Thread {
            try { controlMain { assertTrue(f.controller.stop(owner)); f.controller.close() } }
            catch (t: Throwable) { failure.set(t) } finally { mainReturned.countDown() }
        }.apply { isDaemon = true }
        val backgroundCloser = Thread {
            try { f.controller.close() } catch (t: Throwable) { failure.set(t) } finally { backgroundReturned.countDown() }
        }.apply { isDaemon = true }
        mainRequester.start()
        try {
            assertTrue("main stop/close must not join a device read", mainReturned.await(2, TimeUnit.SECONDS)); failure.get()?.let { throw it }
            assertNotNull(Fixture.field(f.controller, "resources")); assertEquals(1L, (Fixture.field(f.controller, "closeDone") as CountDownLatch).count)
            backgroundCloser.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (backgroundCloser.isAlive && backgroundCloser.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.TIMED_WAITING, backgroundCloser.state); assertEquals(1L, backgroundReturned.count)
            assertNull(f.controller.send(null, "closed"))
            controlMain { assertFalse(f.controller.ready); assertFalse(f.controller.stop(owner)) }
        } finally { f.releaseRead(); mainRequester.join(5000); if (backgroundCloser.state != Thread.State.NEW) backgroundCloser.join(5000) }
        assertFalse(mainRequester.isAlive); assertFalse(backgroundCloser.isAlive); failure.get()?.let { throw it }
        assertEquals(0L, backgroundReturned.count); assertTrue(f.outcome.get() is DispatchOutcome.Confirmed)
        assertNull(Fixture.field(f.controller, "resources")); assertNull(Fixture.field(f.controller, "runtimeStore"))
    }
}
