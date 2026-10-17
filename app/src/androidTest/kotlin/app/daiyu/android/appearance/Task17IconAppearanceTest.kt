package app.daiyu.android.appearance

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.appearance.IconAppearance
import app.daiyu.android.domain.appearance.IconComponentSnapshot
import app.daiyu.android.domain.appearance.IconComponentState
import app.daiyu.android.domain.appearance.LauncherComponentPort
import app.daiyu.android.domain.appearance.IconThemeMode
import app.daiyu.android.domain.appearance.Task17IconCatalog
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Source only: execution belongs to integrator. No launcher state is mutated by these tests. */
@Suppress("DEPRECATION")
class Task17IconAppearanceTest : InstrumentationTestCase() {
    fun testAll64OriginalResourcesLoadAndHavePixels() {
        val context = instrumentation.targetContext
        Task17IconCatalog.entries.forEach { entry ->
            assertTrue(entry.resourceName, Task17IconResources.drawableId(context, entry) != 0)
            val failures = mutableListOf<Exception>()
            val bitmap = Task17IconResources.largeIcon(context, entry, failures::add)
            assertNotNull(entry.resourceName, bitmap)
            assertTrue(failures.toString(), failures.isEmpty())
            assertTrue(bitmap!!.width > 0 && bitmap.height > 0)
        }
        assertNull(Task17IconResources.largeIcon(context, null) { fail(it.message) })
    }

    fun testStoreDefaultsRoundTripAndCurrentResource() {
        val prefix = "task17-test-${UUID.randomUUID()}-"
        val base = instrumentation.targetContext
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        try {
            val first = IconAppearanceStore(context)
            assertEquals(IconAppearance(), first.load())
            assertNull(first.current())
            val custom = IconAppearance(IconThemeMode.SYSTEM, 0x00F27A1A, 0x001C1C1E, 0x007B61FF)
            first.save(custom)
            first.recordCurrent(Task17IconCatalog.entries.last())
            val reopened = IconAppearanceStore(context)
            assertEquals(custom.normalized(), reopened.load())
            assertEquals(Task17IconCatalog.entries.last(), reopened.current())
        } finally {
            base.deleteSharedPreferences(prefix + "task17_icon_appearance")
            base.deleteSharedPreferences(prefix + "launcher_icon")
        }
    }

    fun testOriginalCatViewCanRenderAndSettingsCanBeConstructed() {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val preview = AppCatIconView(context)
            preview.layout(0, 0, 144, 144)
            val bitmap = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
            preview.draw(Canvas(bitmap))
            assertEquals(0, bitmap.getPixel(0, 0)) // 22% rounded transparent corner.
            val view = IconAppearanceSettingsView(context, IconAppearance()) {
                error("Rendering settings must not apply launcher changes")
            }
            assertTrue(view.childCount > 0)
        }
    }

    fun testWorkerMutationsFailBeforeEffectsAndMainThreadCanStillApply() {
        val prefix = "task17-thread-test-${UUID.randomUUID()}-"
        val base = instrumentation.targetContext
        val storageCalls = AtomicInteger()
        val storageWrites = AtomicInteger()
        val pmCalls = AtomicInteger()
        val componentCalls = AtomicInteger()
        val componentWrites = AtomicInteger()
        val refreshCalls = AtomicInteger()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getPackageManager(): PackageManager {
                pmCalls.incrementAndGet()
                error("This test must never use the real PackageManager")
            }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                storageCalls.incrementAndGet()
                val delegate = base.getSharedPreferences(prefix + name, mode)
                return object : SharedPreferences by delegate {
                    override fun getString(key: String, defValue: String?): String? {
                        storageCalls.incrementAndGet()
                        return delegate.getString(key, defValue)
                    }
                    override fun getInt(key: String, defValue: Int): Int {
                        storageCalls.incrementAndGet()
                        return delegate.getInt(key, defValue)
                    }
                    override fun edit(): SharedPreferences.Editor {
                        storageCalls.incrementAndGet()
                        storageWrites.incrementAndGet()
                        return delegate.edit()
                    }
                }
            }
        }
        val defaultName = Task17IconCatalog.defaultEntry.componentName(base.packageName)
        val states = Task17IconCatalog.entries.associate {
            it.componentName(base.packageName) to IconComponentState.DEFAULT
        }.toMutableMap()
        val components = object : LauncherComponentPort {
            override fun validate(componentName: String) {
                componentCalls.incrementAndGet()
                check(componentName in states)
            }
            override fun snapshot(componentName: String): IconComponentSnapshot {
                componentCalls.incrementAndGet()
                val state = states.getValue(componentName)
                return IconComponentSnapshot(state, state == IconComponentState.ENABLED ||
                    state == IconComponentState.DEFAULT && componentName == defaultName)
            }
            override fun setState(componentName: String, state: IconComponentState) {
                componentCalls.incrementAndGet()
                componentWrites.incrementAndGet()
                states[componentName] = state
            }
        }
        try {
            instrumentation.runOnMainSync {
                val notifications = MainThreadIconNotifications { refreshCalls.incrementAndGet() }
                val coordinator = IconAppearanceCoordinator(context, notifications, components)
                storageCalls.set(0) // Construction opens the isolated stores, not a mutation call.
                // The main thread deliberately holds the old coordinator monitor while waiting
                // only for bounded rejection. A worker must not acquire that monitor or post here.
                synchronized(coordinator) {
                    repeat(2) {
                        assertWorkerRejected { coordinator.saveAndApply(IconAppearance()) }
                        assertWorkerRejected { coordinator.applyCurrent() }
                        assertWorkerRejected { notifications.refresh(Task17IconCatalog.defaultEntry) }
                    }
                }
                assertEquals(0, storageCalls.get())
                assertEquals(0, pmCalls.get())
                assertEquals(0, componentCalls.get())
                assertEquals(0, refreshCalls.get())
                // Rejected workers did not queue a refresh or poison a lock. Main calls still work.
                assertTrue(coordinator.applyCurrent().complete)
                assertEquals(1, refreshCalls.get())
                assertTrue(coordinator.saveAndApply(IconAppearance()).complete)
                assertEquals(2, refreshCalls.get())
                assertEquals(Task17IconCatalog.defaultEntry, coordinator.store.current())
                // A previously recorded current alias can later be user-disabled externally.
                states[defaultName] = IconComponentState.DISABLED_USER
                val before = states.toMap()
                storageWrites.set(0)
                componentWrites.set(0)
                val automatic = coordinator.applyCurrent()
                assertFalse(automatic.launcherApplied)
                assertEquals("user-disabled", automatic.failures.single().stage)
                assertEquals(before, states)
                assertEquals(0, storageWrites.get())
                assertEquals(0, componentWrites.get())
                assertEquals(2, refreshCalls.get())
                assertEquals(Task17IconCatalog.defaultEntry, coordinator.store.current())
                // Only a subsequent explicit user action is allowed to re-enable that target.
                assertTrue(coordinator.saveAndApply(IconAppearance()).complete)
                assertEquals(IconComponentState.ENABLED, states[defaultName])
                assertEquals(3, refreshCalls.get())
                assertEquals(0, pmCalls.get())
            }
            // Drain the main queue: rejected notifications must not leave an asynchronous callback.
            instrumentation.runOnMainSync { assertEquals(3, refreshCalls.get()) }
        } finally {
            base.deleteSharedPreferences(prefix + "task17_icon_appearance")
            base.deleteSharedPreferences(prefix + "launcher_icon")
        }
    }

    /** Called while the main thread may own a monitor; never wait without a finite bound. */
    private fun assertWorkerRejected(call: () -> Unit) {
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        Thread {
            try { call() } catch (error: Throwable) { failure.set(error) }
            finally { finished.countDown() }
        }.apply { isDaemon = true; start() }
        assertTrue("Worker did not fail within 2 seconds", finished.await(2, TimeUnit.SECONDS))
        assertTrue("Expected main-thread IllegalStateException, got ${failure.get()}",
            failure.get() is IllegalStateException)
    }

    fun testMainThreadRefreshReceipt() = verifyMainThreadRefreshReceipt()

    private fun verifyMainThreadRefreshReceipt() {
        instrumentation.runOnMainSync {
            var called = false
            MainThreadIconNotifications { called = true }.refresh(Task17IconCatalog.defaultEntry)
            assertTrue(called)
        }
    }
}
