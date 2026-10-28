package app.daiyu.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalPageReturnTest {
    @Test fun terminalCanPushEnvironmentAndReturnToItsOriginalParent() {
        for (parent in listOf("chat", "settings")) {
            val linux = linuxSettingsBackTarget("terminal", parent, "settings", false)
            assertEquals("terminal", linux)
            assertEquals(parent, terminalBackTarget("linux-settings", parent, linux))
        }
    }

    @Test fun linuxTerminalEnvironmentRoundTripDoesNotCreateACycle() {
        for (route in listOf("linux-settings", "chat-workspace")) {
            for (parent in listOf("chat", "settings")) {
                val terminal = terminalBackTarget(route, "chat", parent)
                assertEquals(route, terminal)
                assertEquals(parent, linuxSettingsBackTarget("terminal", terminal, parent, false))
                assertEquals(parent, linuxSettingsBackTarget("terminal", terminal, parent, true))
            }
        }
    }

    @Test fun settingsAndChatEntriesDoNotInheritAnOldTerminalTarget() {
        assertEquals("settings", linuxSettingsBackTarget("settings", "chat", "terminal", false))
        assertEquals("chat", linuxSettingsBackTarget("chat", "settings", "terminal", true))
        assertEquals("settings", terminalBackTarget("settings", "chat", "terminal"))
        assertEquals("chat", terminalBackTarget("chat", "settings", "terminal"))
    }

    @Test fun refreshingLinuxOwnerKeepsItsExistingReturnTarget() {
        for (route in listOf("linux-settings", "chat-workspace")) {
            for (parent in listOf("settings", "chat", "terminal")) {
                assertEquals(parent, linuxSettingsBackTarget(route, "chat", parent, false))
            }
        }
    }
}
