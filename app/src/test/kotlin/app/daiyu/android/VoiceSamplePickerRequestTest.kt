package app.daiyu.android

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.daiyu.android.voice.personal.MimoPersonalVoices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Dedicated review48cbba32 regression tests. No microphone, network, reference upload or paid calls. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [34])
class VoiceSamplePickerRequestTest {
    @get:Rule val folder = TemporaryFolder()

    private fun configured() = VoiceConfiguration(revision = "revision-A", engine = "MIMO",
        provider = "account-A", endpoint = "https://speech.invalid/v1", key = "fixture-key-A",
        model = "mimo-v2.5-tts-voiceclone", voice = "fixture-voice")

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun event(event: Lifecycle.Event) = registry.handleLifecycleEvent(event)
    }

    @Test fun getContentDeliveredOnStartWaitsForResumeThenImportsOnceWithLaunchIdentity() {
        val owner = Owner()
        val picker = VoiceSamplePickerRequest<Uri>()
        val saved = configured()
        var requestCode = -1
        var callbackState: Lifecycle.State? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) { requestCode = code }
        }
        val launcher = registry.register("personal-sample", owner, ActivityResultContracts.GetContent()) {
            callbackState = owner.lifecycle.currentState
            picker.deliver(it)
        }
        owner.event(Lifecycle.Event.ON_CREATE)
        owner.event(Lifecycle.Event.ON_START)
        owner.event(Lifecycle.Event.ON_RESUME)
        assertTrue(picker.begin(saved, "frozen sample name"))
        launcher.launch("audio/*")
        owner.event(Lifecycle.Event.ON_PAUSE)
        owner.event(Lifecycle.Event.ON_STOP)
        val uri = Uri.parse("content://fixture/owned.wav")
        assertTrue(registry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri)))
        owner.event(Lifecycle.Event.ON_START)
        assertEquals(Lifecycle.State.STARTED, callbackState)
        // Mirrors Main: voiceForeground/isCurrent is still false until onResume.
        assertNull(picker.take(resumed = false, pageCurrent = false, configuration = saved))
        assertTrue(picker.pending)
        owner.event(Lifecycle.Event.ON_RESUME)
        val imports = mutableListOf<VoiceSamplePickerRequest.Selection<Uri>>()
        picker.take(true, true, saved)?.let { imports += it }
        picker.take(true, true, saved)?.let { imports += it }
        assertEquals(1, imports.size)
        assertEquals(uri, imports.single().value)
        assertEquals("frozen sample name", imports.single().name)
        assertEquals(saved.referenceOwner(), imports.single().configuration.referenceOwner())
        assertFalse(picker.pending)
        launcher.unregister()
    }

    @Test fun acceptedResultOnlyImportsIntoLocalAccountOwnedSampleStore() = runBlocking {
        val saved = configured()
        val picker = VoiceSamplePickerRequest<Uri>()
        val sample = folder.newFile("owned.wav").apply { writeBytes("RIFF0000WAVEfixture".toByteArray()) }
        val personal = MimoPersonalVoices(File(folder.root, "personal"), durationReader = { 1_000L })
        assertTrue(picker.begin(saved, "owned recording"))
        picker.deliver(Uri.fromFile(sample))
        assertNull(picker.take(false, false, saved))
        assertTrue(personal.state.value.isEmpty())
        val selected = picker.take(true, true, saved)!!
        personal.import(RuntimeEnvironment.getApplication(), selected.value, selected.name,
            selected.configuration.referenceOwner())
        val voice = personal.state.value.single()
        assertEquals("owned recording", voice.name)
        assertEquals(saved.referenceOwner(), voice.providerId)
        assertTrue(voice.id.startsWith(MimoPersonalVoices.PREFIX))
        // Import must not implicitly select the sample or authorize a reference upload.
        assertEquals("fixture-voice", saved.voice)
        assertNull(picker.take(true, true, saved))
    }

    @Test fun alreadyResumedResultAlsoWorksAndCancellationFreesSlot() {
        val picker = VoiceSamplePickerRequest<String>()
        val saved = configured()
        assertTrue(picker.begin(saved, "cancelled"))
        picker.deliver(null)
        assertFalse(picker.pending)
        assertNull(picker.take(true, true, saved))
        assertTrue(picker.begin(saved, "next"))
        picker.deliver("local-uri")
        assertEquals("next", picker.take(true, true, saved)!!.name)
        assertNull(picker.take(true, true, saved))
    }

    @Test fun configurationAccountEndpointKeyOrEngineChangeRejectsBeforeLocalImport() {
        val saved = configured()
        val replacements = listOf(null, saved.copy(revision = "revision-B"),
            saved.copy(provider = "account-B"), saved.copy(endpoint = "https://other.invalid/v1"),
            saved.copy(key = "fixture-key-B"), saved.copy(engine = "SYSTEM"), saved.copy(model = "other-model"))
        for (replacement in replacements) {
            val picker = VoiceSamplePickerRequest<String>()
            assertTrue(picker.begin(saved, "sample"))
            picker.deliver("unread-local-uri")
            assertNull(picker.take(true, true, replacement))
            assertFalse(picker.pending)
            assertNull(picker.take(true, true, saved)) // Cannot resurrect a rejected result.
        }
    }

    @Test fun editsOrSaveRevokeEvenWhenDraftIsChangedBackBeforeResult() {
        val picker = VoiceSamplePickerRequest<String>()
        val saved = configured()
        assertTrue(picker.begin(saved, "sample"))
        picker.invalidate() // Panel calls this on every configuration edit and explicit save.
        assertTrue(picker.pending)
        assertFalse(picker.begin(saved, "replacement must not borrow old callback"))
        picker.deliver("old-uri")
        assertNull(picker.take(true, true, saved))
        assertFalse(picker.pending)
        assertTrue(picker.begin(saved, "fresh"))
        picker.deliver("fresh-uri")
        assertEquals("fresh-uri", picker.take(true, true, saved)!!.value)
    }

    @Test fun editAfterResultWhileWaitingForResumeAlsoRevokes() {
        val picker = VoiceSamplePickerRequest<String>()
        val saved = configured()
        picker.begin(saved, "sample")
        picker.deliver("unread-uri")
        assertNull(picker.take(false, false, saved))
        picker.invalidate()
        assertNull(picker.take(true, true, saved))
    }

    @Test fun leavingPageOrDisposingVisitRejectsOldPickerIncludingReturnToSameAccount() {
        val saved = configured()
        val old = VoiceSamplePickerRequest<String>()
        old.begin(saved, "old")
        old.close()
        val next = VoiceSamplePickerRequest<String>()
        assertTrue(next.begin(saved, "new visit"))
        old.deliver("old-uri")
        assertNull(old.take(true, true, saved))
        assertFalse(old.begin(saved, "cannot revive closed visit"))
        assertNull(next.take(true, true, saved))
        next.deliver("new-uri")
        assertEquals("new-uri", next.take(true, true, saved)!!.value)

        val leaving = VoiceSamplePickerRequest<String>()
        leaving.begin(saved, "sample")
        leaving.deliver("unread-uri")
        assertNull(leaving.take(true, false, saved))
        assertNull(leaving.take(true, true, saved))
    }

    @Test fun unsolicitedOrNonMimoResultCannotCreateImport() {
        val picker = VoiceSamplePickerRequest<String>()
        val saved = configured()
        picker.deliver("unsolicited-uri")
        assertNull(picker.take(true, true, saved))
        assertFalse(picker.begin(saved.copy(engine = "SYSTEM"), "sample"))
        picker.deliver("unlaunched-uri")
        assertNull(picker.take(true, true, saved))
    }
}
