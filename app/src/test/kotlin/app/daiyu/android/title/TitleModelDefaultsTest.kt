package app.daiyu.android.title

import android.content.SharedPreferences
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.UUID

class TitleModelDefaultsTest {
    private val binding = TitleModelBinding("custom-provider", "custom-title")
    private val current = ModelSettings("https://current.invalid/v1", "current-chat", "synthetic-key", false)
    private fun store(): TitleModelSettingsStore {
        val values = mutableMapOf<String, String?>()
        val edits = mutableMapOf<String, String?>()
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { edits[args!![0] as String] = args[1] as String?; proxy }
                "commit" -> { values.putAll(edits); edits.clear(); true }
                else -> error("unexpected editor operation ${method.name}")
            }
        }
        val preferences = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor
                else -> error("unexpected preference operation ${method.name}")
            }
        } as SharedPreferences
        var custom: ModelSettings? = null
        return TitleModelSettingsStore(preferences, { checkNotNull(custom) },
            { model, _, _ -> custom = model }, UUID.randomUUID().toString())
    }

    @Test fun untouchedSettingsAdmitEachFrozenCurrentModelWithoutLoadingACustomKey() {
        val store = store()
        assertFalse(store.snapshot().enabled)
        assertNull(store.snapshot().binding)
        for (model in listOf("current-A", "current-B")) {
            val frozen = current.copy(model = model).requestSnapshot()
            assertEquals(model, store.whileCurrent(null, frozen, null) { frozen.model })
        }
    }

    @Test fun clearingCustomReturnsToCurrentAndDoesNotEraseTheChatConfiguration() {
        val store = store()
        store.save(TitleModelProfile(binding, current.copy(model = "custom-title")))
        store.disable()
        val saved = store.snapshot()
        assertFalse(saved.enabled)
        assertNull(saved.binding)
        val frozen = current.requestSnapshot()
        assertEquals("current-chat", store.whileCurrent(null, frozen, saved.revision) { frozen.model })
        assertEquals("current-chat", current.model)
    }

    @Test fun configuringCustomFencesThePreviouslyPreparedDefaultJob() {
        val store = store()
        store.save(TitleModelProfile(binding, current.copy(model = "custom-title")))
        assertThrows(TitleModelUnavailableException::class.java) {
            store.whileCurrent(null, current.requestSnapshot(), null) { fail("old default admitted") }
        }
        val config = store.snapshot()
        val frozen = store.resolve(binding)
        assertEquals("custom-title", store.whileCurrent(binding, frozen, config.revision) { frozen.model })
    }

    @Test fun clearingCustomAgainFencesAPreviouslyPreparedCurrentJob() {
        val store = store()
        val frozen = current.requestSnapshot()
        store.disable()
        assertThrows(TitleModelUnavailableException::class.java) {
            store.whileCurrent(null, frozen, null) { fail("old revision admitted") }
        }
    }

    @Test fun missingCustomBindingNeverFallsBackToCurrent() {
        val store = store()
        store.save(TitleModelProfile(binding, current.copy(model = "custom-title")))
        assertThrows(TitleModelUnavailableException::class.java) {
            store.resolve(TitleModelBinding("missing-provider", "missing-model"))
        }
    }

    @Test fun titleRequestKeepsTheEffectiveCurrentModelButNeverItsToolsOrBody() {
        val source = current.copy(requestOptions = ProviderRequestOptions(
            customBody = """{"model":"effective-chat","temperature":0.5}""", openAiWebSearchEnabled = true)).requestSnapshot()
        val title = TitleModelCoordinator.requestSnapshot(source)
        assertEquals("effective-chat", title.model)
        assertFalse(title.requestOptions.openAiWebSearchEnabled)
        assertEquals("{\"max_output_tokens\":512}", title.requestOptions.customBody)
        assertEquals("current-chat", source.model)
        assertTrue(source.requestOptions.openAiWebSearchEnabled)
    }
}
