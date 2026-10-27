package app.daiyu.android.ui.components

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Structural guards complement device gesture/callback tests; these do not simulate Compose. */
class ConversationWorkspaceSourceTest {
    @Test fun cardAndFolderPickerRemainControlledAndUseOurTheme() {
        val source = source("components/ConversationWorkspaceSheet.kt")
        assertTrue(source.contains("if (!visible) return"))
        assertTrue(source.contains("ConversationAgentSheet(onDismiss"))
        assertTrue(source.contains("ConversationAgentHandle()"))
        assertTrue(source.contains("if (!choosing)"))
        assertTrue(source.contains("workspace-path-card"))
        assertTrue(source.contains("LazyColumn"))
        assertTrue(source.contains("onClick = onSave"))
        assertTrue(source.contains("onClick = onCancel"))
        assertTrue(source.contains("onSourceChange(source.id)"))
        assertTrue(source.contains("primaryColor = theme.secondary, onPrimaryColor = theme.onSecondary"))
        assertTrue(source.contains("primaryColor = theme.primary, onPrimaryColor = theme.onPrimary"))
        for (forbidden in listOf("java.io", "java.nio", "LocalContext", "filesDir", "SharedPreferences",
            "ChatController", "rememberSaveable", "LaunchedEffect", "mutableStateOf", "BasicTextField", "bodySmall", "Color(0x")) {
            assertFalse("UI must not own filesystem, persistence or screenshot colors: $forbidden", source.contains(forbidden))
        }
    }

    @Test fun editorAndStripShareInitialPassWithoutClaimingControls() {
        val composer = source("screens/chat/conversation/ChatComposer.kt")
        val surface = source("screens/chat/conversation/ChatComposerSurface.kt")
        val actions = source("screens/chat/conversation/ChatScreen.kt")
        assertTrue(actions.contains("val onOpenWorkspace: () -> Unit = {}"))
        assertTrue(composer.contains("onOpenWorkspace: () -> Unit = actions.onOpenWorkspace"))
        assertTrue(composer.contains("key(state.editorGeneration) { ComposerWorkspacePullUpArea(workspacePointers, openWorkspace) }"))
        assertTrue(composer.contains("try { hideChatInputIme(focus, keyboard, view) }\n            finally { lifecycleDismissInProgress = false }\n            onOpenWorkspace()"))
        assertTrue(composer.contains("testTag(\"chat-draft\").workspacePullUpGesture(workspacePointers, openWorkspace)"))
        assertTrue(surface.contains("requireUnconsumed = true, pass = PointerEventPass.Initial"))
        assertTrue(surface.contains("awaitPointerEvent(PointerEventPass.Initial)"))
        assertFalse(surface.contains("down.consume()"))
        assertTrue(surface.contains("event.changes.any { it.isConsumed }"))
        assertTrue(surface.contains("PointerEventType.Release && !change.pressed && change.previousPressed"))
        assertTrue(surface.contains("longPressTimeoutMillis"))
        assertTrue(surface.contains("event.changes.size != 1"))
    }

    private fun source(path: String): String = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "app/src/main/kotlin/app/daiyu/android/ui/$path") }
        .first { it.isFile }.readText()
}
