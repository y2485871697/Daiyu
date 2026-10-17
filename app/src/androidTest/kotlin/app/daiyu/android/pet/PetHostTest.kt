package app.daiyu.android.pet

import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import android.test.InstrumentationTestCase
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.daiyu.android.domain.*
import app.daiyu.android.domain.pet.*
import java.util.UUID

@Suppress("DEPRECATION")
class PetHostTest : InstrumentationTestCase() {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private fun scopedContext(): Context {
        val base = instrumentation.targetContext
        val prefix = "pet-host-${UUID.randomUUID()}-"
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
    }
    fun testOwnerDestroyClosesRuntimeCancelsModelAndRemovesMountedLayer() {
        verifyOwnerDestroyClosesRuntimeCancelsModelAndRemovesMountedLayer()
    }

    private fun verifyOwnerDestroyClosesRuntimeCancelsModelAndRemovesMountedLayer() {
        instrumentation.runOnMainSync {
            val context = scopedContext(); val owner = Owner(); val mount = FrameLayout(context)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            var cancellations = 0; var callback: ((PetModelResult) -> Unit)? = null
            val host = PetHost(context, PetModelPort { _, done -> callback = done; PetCancel { cancellations++ } }, onOpenSettings = {})
            try {
                host.attach(mount, owner); owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                host.updateSettings(PetSettings(enabled = true, model = PetModelSelection("a", "p", "m")))
                host.feed(PetFood.BOWL); assertTrue(host.snapshot().pet.state.thinking)
                assertEquals(1, mount.childCount)
                owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                assertTrue(host.snapshot().closed); assertEquals(PetOverlayState.CLOSED, host.snapshot().overlayState)
                assertEquals(0, mount.childCount); assertEquals(1, cancellations)
                callback!!(PetModelResult.Completed("{\"mood\":\"happy\",\"speech\":\"迟到反应\"}"))
                assertFalse(host.snapshot().pet.state.speechVisible); assertTrue(host.snapshot().pet.state.memories.isEmpty())
                val before = Message(MessageId("a"), ConversationId("c"), RunId("r"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
                assertTrue(host.onRunTransition(before, before.copy(status = RunStatus.COMPLETED), "task") is PetCommandResult.Rejected)
            } finally { host.close(); context.getSharedPreferences("daiyu_pet_v1", 0).edit().clear().commit() }
        }
    }
    fun testPermissionStatusComesFromSystemAndNoUnownedOverlayIsCreated() {
        verifyPermissionStatusComesFromSystemAndNoUnownedOverlayIsCreated()
    }

    private fun verifyPermissionStatusComesFromSystemAndNoUnownedOverlayIsCreated() {
        instrumentation.runOnMainSync {
            val context = scopedContext(); val owner = Owner(); val mount = FrameLayout(context)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            val host = PetHost(context, PetModelPort { _, _ -> PetCancel {} }, onOpenSettings = {})
            try {
                host.setEnabled(true); host.setGlobalVisible(true)
                assertEquals(PetOverlayState.DISABLED, host.snapshot().overlayState)
                host.attach(mount, owner); host.refreshOverlayPermission()
                val actual = Settings.canDrawOverlays(context)
                assertEquals(actual, host.snapshot().canDrawOverlays)
                if (!actual) assertEquals(PetOverlayState.PERMISSION_REQUIRED, host.snapshot().overlayState)
                else assertTrue(host.snapshot().overlayState in setOf(PetOverlayState.VISIBLE, PetOverlayState.ERROR))
                // Toggling the setting is only a preference, never a grant or settings intent.
                assertEquals(actual, Settings.canDrawOverlays(context))
                host.setGlobalVisible(false); assertEquals(PetOverlayState.IN_APP, host.snapshot().overlayState)
                assertNotNull(host.createSettingsView(context))
                host.setEnabled(false); assertEquals(PetOverlayState.DISABLED, host.snapshot().overlayState)
            } finally { host.close(); context.getSharedPreferences("daiyu_pet_v1", 0).edit().clear().commit() }
        }
    }
}
