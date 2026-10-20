package app.daiyu.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.daiyu.android.model.generation.GenerationKind
import app.daiyu.android.model.generation.GenerationProfileSettings
import app.daiyu.android.model.generation.GenerationProtocol
import app.daiyu.android.model.generation.GenerationSettingsStore
import app.daiyu.android.model.generation.GenerationSettingsValues
import app.daiyu.android.ui.GenerationSettingsHost
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises the actual Host -> controlled Screen callbacks under the existing test Activity.
 * No Main route/catalog/manifest change. Store metadata is real; only credential vault is a fixture.
 */
@Suppress("DEPRECATION")
class GenerationSettingsHostActivityTest : WiredActivityTestCase() {
    private lateinit var prefs: SharedPreferences
    private lateinit var store: GenerationSettingsStore
    private lateinit var editor: GenerationSettingsEditor
    private val current = AtomicBoolean(true)
    private var visible by mutableStateOf(true)
    private val pageTitle = "独立生成模型设置"

    override fun setUp() {
        super.setUp()
        val namespace = "generation-host-ui-${UUID.randomUUID()}"
        prefs = instrumentation.targetContext.getSharedPreferences(namespace, Context.MODE_PRIVATE)
        val credentials = ConcurrentHashMap<String, String>()
        val checkedPrefs = object : SharedPreferences by prefs {
            override fun getString(key: String?, defValue: String?): String? {
                check(Looper.myLooper() != Looper.getMainLooper()) { "metadata read on UI" }
                return prefs.getString(key, defValue)
            }
            override fun edit(): SharedPreferences.Editor {
                check(Looper.myLooper() != Looper.getMainLooper()) { "metadata write on UI" }
                return prefs.edit()
            }
        }
        store = GenerationSettingsStore(checkedPrefs, { id ->
            check(Looper.myLooper() != Looper.getMainLooper())
            credentials.getValue(id)
        }, { id, key ->
            check(Looper.myLooper() != Looper.getMainLooper())
            credentials[id] = key
        }, namespace)
        store.save(GenerationSettingsValues(listOf(GenerationProfileSettings(
            label = "  exact label  ", kind = GenerationKind.IMAGE, protocol = GenerationProtocol.OPENAI_IMAGES_JSON,
            endpoint = "https://api.example/v1/images/generations", model = "  exact original  ",
            apiKey = "fixture-only-not-a-real-key")), 4, false, emptySet()), null)
        current.set(true)
        mainThread {
            visible = true
            editor = GenerationSettingsEditor(store)
            activity.setContent {
                MiuixTheme {
                    if (visible) GenerationSettingsHost(editor = editor,
                        onBack = { current.set(false); visible = false }, ownerCurrent = current::get)
                }
            }
        }
        awaitUniqueTag("generation-settings-reload")
        reveal("settings-field:显示名称", pageTitle).assertExists()
    }

    override fun tearDown() {
        try {
            mainThread { current.set(false); visible = false }
            prefs.edit().clear().commit()
        } finally { super.tearDown() }
    }

    fun testRealControlledFieldEditAndSaveAdoptsVerifiedReadback() {
        reveal("settings-field:生成模型名", pageTitle).assertEditableText("  exact original  ")
            .performTextReplacement("  exact edited  ")
        reveal("generation-settings-save", pageTitle).performClick()
        ui.waitUntil(5_000) {
            ui.onAllNodesWithText("生成配置已保存并核验回读；未运行或授权任何生成。").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("  exact edited  ", store.loadSnapshot().values.profiles.single().model)
        reveal("settings-field:生成模型名", pageTitle).assertEditableText("  exact edited  ")
        mainThread { activity.onBackPressedDispatcher.onBackPressed() }
        node("generation-settings-host").assertDoesNotExist()
    }

    fun testCorruptReloadNeverShowsBlankEditorButMissingReloadDoes() {
        prefs.edit().putString(GenerationSettingsStore.KEY, "{private-corrupt-data").commit()
        node("generation-settings-reload").performClick()
        ui.waitUntil(5_000) {
            ui.onAllNodesWithText("生成配置已损坏；未加载默认模型，请显式处理存储故障").fetchSemanticsNodes().isNotEmpty()
        }
        node("settings-field:显示名称").assertDoesNotExist()
        node("generation-settings-save").assertDoesNotExist()
        prefs.edit().clear().commit()
        node("generation-settings-reload").performClick()
        ui.waitUntil(5_000) {
            ui.onAllNodesWithText("生成配置已损坏；未加载默认模型，请显式处理存储故障").fetchSemanticsNodes().isEmpty()
        }
        awaitUniqueTag("generation-settings-reload")
        reveal("settings-field:显示名称", pageTitle).assertExists()
        reveal("settings-field:生成模型名", pageTitle).assertEditableText("")
        assertTrue(prefs.all.isEmpty()) // reopening a missing editor never persists defaults
    }
}
