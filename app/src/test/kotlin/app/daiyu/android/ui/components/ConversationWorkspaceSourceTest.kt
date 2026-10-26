package app.daiyu.android.ui.components

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Structural guards complement device gesture/callback tests; these do not simulate Compose. */
class ConversationWorkspaceSourceTest {
    @Test fun sheetRemainsControlledAndDoesNotAcquireFilesystemOrOwnerState() {
        val source = source("components/ConversationWorkspaceSheet.kt")
        assertTrue(source.contains("if (!visible) return"))
        assertTrue(source.contains("ConversationAgentSheet(onDismiss"))
        assertTrue(source.contains("ConversationAgentHandle()"))
        assertTrue(source.contains(".verticalScroll(rememberScrollState())"))
        assertTrue(source.contains("onClick = onSave"))
        assertTrue(source.contains("enabled = !busy"))
        assertTrue(source.contains("onSourceChange(source.id)"))
        assertTrue(source.contains("onValueChange = onPathChange"))
        for (forbidden in listOf("java.io", "java.nio", "LocalContext", "filesDir", "SharedPreferences",
            "ChatController", "rememberSaveable", "LaunchedEffect", "mutableStateOf", "onDismiss(); onSave")) {
            assertFalse("UI must not own persistence or a stale conversation draft: $forbidden", source.contains(forbidden))
        }
    }

    @Test fun gestureIsDedicatedContentSiblingAndRetainsImeDismissalProtection() {
        val composer = source("screens/chat/conversation/ChatComposer.kt")
        val surface = source("screens/chat/conversation/ChatComposerSurface.kt")
        val actions = source("screens/chat/conversation/ChatScreen.kt")
        assertTrue(actions.contains("val onOpenWorkspace: () -> Unit = {}"))
        assertTrue(composer.contains("onOpenWorkspace: () -> Unit = actions.onOpenWorkspace"))
        assertTrue(composer.contains("ComposerWorkspacePullUpArea {\n            lifecycleDismissInProgress = true"))
        assertTrue(composer.contains("try { hideChatInputIme(focus, keyboard, view) }\n            finally { lifecycleDismissInProgress = false }\n            onOpenWorkspace()"))
        assertTrue(composer.indexOf("ComposerWorkspacePullUpArea") < composer.indexOf("BasicTextField("))
        val background = surface.substringBefore("internal fun ComposerWorkspacePullUpArea")
        assertFalse("Background shield is not the opener", background.contains("latestOpenWorkspace()"))
        assertTrue(surface.contains("max(48.dp.toPx(), viewConfiguration.touchSlop * 3f)"))
        assertTrue(surface.contains("!change.pressed && change.previousPressed && !rejected && upward"))
        assertTrue(surface.contains("event.changes.size != 1"))
    }

    private fun source(path: String): String = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "app/src/main/kotlin/app/daiyu/android/ui/$path") }
        .first { it.isFile }.readText()
}
