package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.BackupScreen
import java.util.concurrent.atomic.AtomicInteger

class BackupScreenTest : WiredActivityTestCase() {
    private val exports = AtomicInteger()
    private val imports = AtomicInteger()
    private val otherActions = AtomicInteger()
    private val includedLinux = java.util.concurrent.CopyOnWriteArrayList<Boolean>()

    private fun show(mode: AppearanceThemeMode, busy: Boolean = false) {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = mode), applyInterfaceScale = false) {
                BackupScreen(
                    onBack = { otherActions.incrementAndGet() },
                    onExportCompact = { otherActions.incrementAndGet() },
                    onExportComplete = { exports.incrementAndGet() },
                    onImportCompact = { otherActions.incrementAndGet() },
                    onImportComplete = { imports.incrementAndGet() },
                    onOpenLegacyLibrary = { otherActions.incrementAndGet() },
                    restorePreviewText = "",
                    onPreviewRestore = { otherActions.incrementAndGet() },
                    onRestoreConfiguration = { otherActions.incrementAndGet() },
                    busy = busy,
                    onExport = { included -> includedLinux.add(included); exports.incrementAndGet() },
                )
            }
        } }
        awaitUniqueTag("backup-include-full-linux")
    }

    fun testLinuxOptionIsAboveExportWithoutDescriptionAndOnlyChangesLocalState() {
        val before = fixture.database.exportBackup()
        val opens = fixture.opens.get()
        for (mode in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
            show(mode)
            val option = node("backup-include-full-linux").assertIsDisplayed().assertIsEnabled().assertIsOff()
            option.assertTextEquals("包含完整 Linux 环境")
            val optionBounds = option.fetchSemanticsNode().boundsInRoot
            val exportBounds = node("backup-export").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val importBounds = node("backup-import").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val optionsCard = node("backup-options-card").fetchSemanticsNode().boundsInRoot
            val actionsCard = node("backup-actions-card").fetchSemanticsNode().boundsInRoot
            assertEquals(optionsCard.left, actionsCard.left)
            assertEquals(optionsCard.right, actionsCard.right)
            assertTrue(optionsCard.bottom < actionsCard.top)
            node("backup-export").assertTextEquals("导出数据")
            node("backup-import").assertTextEquals("导入数据")
            assertTrue(optionBounds.bottom <= exportBounds.top)
            assertTrue(exportBounds.bottom <= importBounds.top)
            option.performClick().assertIsOn()
            option.performClick().assertIsOff()
            assertEquals(0, exports.get())
            assertEquals(0, imports.get())
            assertEquals(0, otherActions.get())
        }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(opens, fixture.opens.get())
    }

    fun testLinuxOptionDoesNotReplaceExistingExportOrImportCallbacks() {
        show(AppearanceThemeMode.LIGHT)
        node("backup-include-full-linux").performClick().assertIsOn()
        node("backup-export").performClick()
        node("backup-import").performClick()
        assertEquals(listOf(true), includedLinux.toList())
        assertEquals(1, exports.get())
        assertEquals(1, imports.get())
        assertEquals(0, otherActions.get())
        node("backup-include-full-linux").assertIsOn()
    }

    fun testDefaultExportDoesNotIncludeLinuxUntilExplicitlyEnabled() {
        show(AppearanceThemeMode.LIGHT)
        node("backup-export").performClick()
        assertEquals(listOf(false), includedLinux.toList())
        assertEquals(1, exports.get())
        assertEquals(0, imports.get())
    }

    fun testBusyStateDisablesLinuxOptionAndKeepsDataActionsUnclickable() {
        show(AppearanceThemeMode.LIGHT, busy = true)
        val option = node("backup-include-full-linux").assertIsDisplayed().assertIsNotEnabled().assertIsOff()
        option.performTouchInput { click() }
        option.assertIsOff().assertTextEquals("包含完整 Linux 环境")
        node("backup-export").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        node("backup-import").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        assertEquals(0, exports.get())
        assertEquals(0, imports.get())
        assertEquals(0, otherActions.get())
    }
}
